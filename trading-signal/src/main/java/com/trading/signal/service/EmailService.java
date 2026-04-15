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
        if (reason.contains("PROBE timeout") || reason.contains("PROBE hit")) {
            type = "CANCEL";
        } else if (reason.contains("PROBE")) {
            type = "PROBE";
        } else if (reason.contains("ADD")) {
            type = "CONFIRM";
        } else {
            type = "SIGNAL";
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
        // 1. 强制要求 TLS 握手（避免谷歌新版安全策略拦截）
        props.put("mail.smtp.starttls.required", "true");

        // 2. 强行指定走 Socks5 代理！（这是解决 Connection reset 的核心）
        // 注意端口换成了你刚才测通的 7897
        props.put("mail.smtp.socks.host", "127.0.0.1");
        props.put("mail.smtp.socks.port", "7897");

        // 3. 救命的超时设置（防止梯子卡顿导致你的量化主线程被卡死）
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
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
     * 通用邮件发送（供 TradeExecutor 等外部调用）
     */
    public void sendRawEmail(String subject, String body) {
        TradingProperties.Email cfg = properties.getEmail();
        if (!cfg.isEnabled()) return;
        try {
            send(cfg, subject, body);
            log.info("邮件发送成功: {}", subject);
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送试探仓平仓通知（区分不同原因）
     */
    public void sendCancelEmail(String reason, double currentPrice) {
        TradingProperties.Email cfg = properties.getEmail();
        if (!cfg.isEnabled()) return;

        // 根据原因判断平仓类型
        String title;
        String explanation;
        
        if (reason.contains("timeout")) {
            title = "试探仓超时平仓";
            explanation = "试探仓持仓超过3小时未触发加仓条件，已自动平仓。\n" +
                         "可能原因：\n" +
                         "- 浮盈未达到箱体宽度的25%\n" +
                         "- 持仓时间不足45分钟\n" +
                         "- 价格在箱体内震荡，未形成明确方向";
        } else if (reason.contains("hit stop loss")) {
            title = "试探仓止损平仓";
            explanation = "价格触及策略监控止损线，已自动平仓。\n" +
                         "止损由策略每1分钟检查一次。";
        } else if (reason.contains("range invalidated")) {
            title = "箱体失效平仓";
            explanation = "价格强势突破箱体（连续两根K线确认），\n" +
                         "箱体震荡结构失效，已自动平仓。";
        } else if (reason.contains("CONFIRMED timeout")) {
            title = "加仓超时平仓";
            explanation = "加仓信号发出后6小时未确认，\n" +
                         "可能是API异常或系统故障，已强制平仓。";
        } else {
            title = "试探仓平仓";
            explanation = "策略触发平仓条件。";
        }

        String subject = String.format("[%s] %s", title, properties.getOkx().getInstId().toUpperCase());
        String body = String.format(
            "===== %s =====\n\n" +
            "当前价格: %.2f\n" +
            "原因    : %s\n\n" +
            "【说明】\n%s\n\n" +
            "【操作】\n" +
            "系统已自动调用平仓接口。\n" +
            "请登录交易所确认持仓已平仓。\n" +
            "如平仓失败，请立即手动平仓。\n\n" +
            "===========================",
            title, currentPrice, reason, explanation);

        try {
            send(cfg, subject, body);
        } catch (Exception e) {
            log.error("平仓通知邮件发送失败: {}", e.getMessage());
        }
    }
}
