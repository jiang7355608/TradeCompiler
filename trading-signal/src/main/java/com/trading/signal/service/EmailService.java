package com.trading.signal.service;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.TradeSignal;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件通知服务
 *
 * 触发条件（由调用方保证）：
 *   signal.action != NO_TRADE
 *   signal.confidence >= trading.email.min-confidence（默认 0.6）
 *
 * 防轰炸机制：
 *   同方向信号（LONG / SHORT）在 cooldownMs 内只发送一次
 *   使用 ConcurrentHashMap 保证线程安全
 *
 * Gmail 配置说明：
 *   需要在 Google 账户 → 安全 → 两步验证 → 应用专用密码 中生成 appPassword
 *   不能直接使用 Gmail 登录密码
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";

    // key: 信号方向（"long" / "short"），value: 上次发送时间戳（ms）
    private final Map<String, Long> lastSendTime = new ConcurrentHashMap<>();

    private final TradingProperties properties;

    public EmailService(TradingProperties properties) {
        this.properties = properties;
    }

    /**
     * 发送交易信号邮件
     *
     * @param signal       策略生成的交易信号
     * @param currentPrice 当前价格（用于计算止盈止损）
     */
    public void sendSignalEmail(TradeSignal signal, double currentPrice) {
        TradingProperties.Email cfg = properties.getEmail();

        // 未启用邮件通知，直接跳过
        if (!cfg.isEnabled()) {
            log.debug("邮件通知未启用，跳过发送");
            return;
        }

        // NO_TRADE 不发送
        if (signal.getAction() == TradeSignal.Action.NO_TRADE) {
            return;
        }

        // 置信度不足，不发送
        if (signal.getConfidence() < cfg.getMinConfidence()) {
            log.debug("置信度 {} 低于阈值 {}，跳过邮件", signal.getConfidence(), cfg.getMinConfidence());
            return;
        }

        // 冷却检查：同方向信号在 cooldownMs 内只发一次
        String direction = signal.getAction().getValue(); // "long" or "short"
        if (isInCooldown(direction, cfg.getCooldownMs())) {
            log.info("方向 {} 处于冷却期，跳过邮件发送", direction);
            return;
        }

        // 止盈止损直接从信号读取（由策略计算，不在通知层重复计算）
        double stopLoss   = signal.getStopLoss();
        double takeProfit = signal.getTakeProfit();

        // 构建邮件内容
        String subject = buildSubject(signal, properties.getOkx().getInstId());
        String body    = buildBody(signal, currentPrice, stopLoss, takeProfit);

        // 发送
        try {
            send(cfg, subject, body);
            // 发送成功后记录时间，开始冷却
            lastSendTime.put(direction, System.currentTimeMillis());
            log.info("邮件发送成功 → {} subject='{}'", cfg.getReceiverEmail(), subject);
        } catch (Exception e) {
            // SMTP 失败只记录日志，不影响主流程
            log.error("邮件发送失败: {}", e.getMessage(), e);
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────

    /**
     * 检查指定方向是否处于冷却期
     */
    private boolean isInCooldown(String direction, long cooldownMs) {
        Long last = lastSendTime.get(direction);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < cooldownMs;
    }

    /**
     * 构建邮件标题
     * 格式：[TRADE SIGNAL] BTC-USDT LONG
     */
    private String buildSubject(TradeSignal signal, String instId) {
        String reason = signal.getReason();
        String type;
        if (reason.startsWith("PROBE timeout") || reason.startsWith("PROBE hit")) {
            type = "CANCEL";  // 试探仓作废
        } else if (reason.startsWith("PROBE")) {
            type = "PROBE";   // 试探仓
        } else if (reason.startsWith("ADD")) {
            type = "CONFIRM"; // 确认加仓
        } else {
            type = "SIGNAL";  // 其他策略的普通信号
        }
        return String.format("[%s] %s %s",
            type, instId.toUpperCase(),
            signal.getAction().getValue().toUpperCase());
    }

    /**
     * 构建邮件正文（纯文本）
     */
    private String buildBody(TradeSignal signal, double price, double stopLoss, double takeProfit) {
        return String.format(
            "===== TRADE SIGNAL =====\n\n" +
            "Action     : %s\n"            +
            "Price      : %.2f\n"          +
            "Stop Loss  : %.2f\n"          +
            "Take Profit: %.2f\n"          +
            "Confidence : %.2f\n"          +
            "Position   : %.0f%%\n"        +
            "Reason     : %s\n\n"          +
            "========================",
            signal.getAction().getValue().toUpperCase(),
            price,
            stopLoss,
            takeProfit,
            signal.getConfidence(),
            signal.getPositionSize() * 100,
            signal.getReason()
        );
    }

    /**
     * 通过 Gmail SMTP 发送邮件
     * 使用 STARTTLS（端口 587）
     */
    private void send(TradingProperties.Email cfg, String subject, String body) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host",            SMTP_HOST);
        props.put("mail.smtp.port",            SMTP_PORT);
        props.put("mail.smtp.ssl.trust",       SMTP_HOST);

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.getSenderEmail(), cfg.getAppPassword());
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(cfg.getSenderEmail()));
        message.setRecipients(Message.RecipientType.TO,
            InternetAddress.parse(cfg.getReceiverEmail()));
        message.setSubject(subject);
        message.setText(body);

        Transport.send(message);
    }

    /**
     * 发送试探仓作废通知
     */
    public void sendCancelEmail(String reason, double currentPrice) {
        TradingProperties.Email cfg = properties.getEmail();
        if (!cfg.isEnabled()) return;

        String subject = String.format("[CANCEL] %s 试探仓作废",
            properties.getOkx().getInstId().toUpperCase());
        String body = String.format(
            "===== PROBE CANCELLED =====\n\n" +
            "Reason : %s\n" +
            "Price  : %.2f\n\n" +
            "请手动平掉试探仓位\n" +
            "===========================",
            reason, currentPrice);

        try {
            send(cfg, subject, body);
        } catch (Exception e) {
            log.error("作废通知邮件发送失败: {}", e.getMessage());
        }
    }
}
