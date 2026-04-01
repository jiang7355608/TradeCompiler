package com.trading.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.signal.client.OkxTradeClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.TradeSignal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 交易执行器：将策略信号转化为 OKX 下单操作
 *
 * 信号类型处理：
 *   MR-PROBE → 市价开仓（小仓位）+ 止损单
 *   MR-ADD   → 市价加仓（大仓位）+ 止盈止损单
 *   MR-PROBE timeout/hit → 市价平仓
 *
 * 下单后发邮件通知结果
 */
@Service
public class TradeExecutor {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutor.class);
    // BTC-USDT 永续合约，每张面值0.01BTC
    private static final String SWAP_INST_ID = "BTC-USDT-SWAP";
    private static final double CONTRACT_SIZE = 0.01; // 每张合约面值

    private final OkxTradeClient tradeClient;
    private final TradingProperties properties;
    private final EmailService emailService;
    private boolean leverageSet = false;

    // NEW: 账户级永久熔断（Equity Kill Switch）
    // initialBalance：程序启动时从交易所读取，作为风控基准
    // isKilled：一旦置 true，程序生命周期内不可恢复，必须重启
    private double  initialBalance = 0;
    private boolean isKilled       = false;
    private static final double KILL_THRESHOLD = 0.60; // 跌至初始资金60%触发

    public TradeExecutor(OkxTradeClient tradeClient, TradingProperties properties,
                         EmailService emailService) {
        this.tradeClient = tradeClient;
        this.properties  = properties;
        this.emailService = emailService;
    }

    // NEW: 程序启动后自动执行，从交易所读取当前余额作为初始基准
    // 如果查询失败（网络/API问题），initialBalance=0，熔断不会误触发
    // 但会打印警告，提示人工确认
    @PostConstruct
    public void init() {
        if (!properties.getTradeApi().isEnabled()) return;
        try {
            initialBalance = tradeClient.getBalance();
            if (initialBalance > 0) {
        log.info("Equity Kill Switch 初始化: initialBalance={} USDT, 熔断线={} USDT",
                    String.format("%.2f", initialBalance),
                    String.format("%.2f", initialBalance * KILL_THRESHOLD));
            } else {
                log.warn("Equity Kill Switch: 初始余额查询失败，熔断保护未激活，请检查 API 配置");
            }
        } catch (Exception e) {
            log.warn("Equity Kill Switch: 初始化失败 — {}", e.getMessage());
        }
    }

    /**
     * 查询交易所实际持仓张数（以交易所为唯一真相源）
     * @return 持仓张数（正=多头，负=空头，0=无持仓）
     */
    private double queryPositionSize() {
        try {
            com.fasterxml.jackson.databind.JsonNode result = tradeClient.getPositions(SWAP_INST_ID);
            if (!"0".equals(result.path("code").asText())) return 0;
            com.fasterxml.jackson.databind.JsonNode data = result.path("data");
            if (!data.isArray() || data.isEmpty()) return 0;
            for (com.fasterxml.jackson.databind.JsonNode pos : data) {
                double amt = pos.path("pos").asDouble();
                if (amt != 0) return amt;
            }
            return 0;
        } catch (Exception e) {
            log.error("查询持仓失败: {}", e.getMessage());
            return Double.NaN; // 查询失败返回NaN，调用方拒绝操作
        }
    }

    /**
     * 执行交易信号（每次从交易所查持仓做严格校验）
     */
    public synchronized boolean execute(TradeSignal signal, double currentPrice) {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        if (!cfg.isEnabled()) return false;

        // NEW: 永久熔断检查 — 已触发则拒绝一切新交易
        if (isKilled) {
            log.error("Equity Kill Switch 已触发，拒绝下单");
            return false;
        }

        // NEW: 每次下单前检查当前余额是否触发熔断线
        if (initialBalance > 0) {
            try {
                double currentBalance = tradeClient.getBalance();
                if (currentBalance > 0 && currentBalance <= initialBalance * KILL_THRESHOLD) {
                    triggerKillSwitch(currentBalance);
                    return false;
                }
            } catch (Exception e) {
                // 余额查询失败：保守处理，拒绝下单
                log.error("Equity Kill Switch: 余额查询失败，拒绝下单以保护资金 — {}", e.getMessage());
                return false;
            }
        }

        String reason = signal.getReason();

        try {
            if (!leverageSet) {
                tradeClient.setLeverage(SWAP_INST_ID, cfg.getLeverage(), "cross");
                leverageSet = true;
            }

            boolean isProbe = reason.contains("PROBE") && !reason.contains("ADD");
            boolean isAdd   = reason.contains("ADD");

            // 查询交易所实际持仓
            double currentPos = queryPositionSize();
            if (Double.isNaN(currentPos)) {
                log.error("无法确认持仓状态，拒绝下单");
                return false;
            }
            boolean hasPosition = currentPos != 0;

            // ── 试探仓：交易所必须无持仓 ────────────────────────────────
            if (isProbe) {
                if (hasPosition) {
                    log.warn("交易所已有持仓({}张)，拒绝开试探仓", currentPos);
                    return false;
                }
                boolean ok = openPosition(signal, currentPrice, cfg);
                if (ok) sendTradeEmail("试探仓下单成功", signal, currentPrice);
                return ok;
            }

            // ── 加仓：交易所必须已有持仓（试探仓）────────────────────────
            if (isAdd) {
                if (!hasPosition) {
                    log.warn("交易所无持仓，拒绝加仓（试探仓可能已被止损）");
                    return false;
                }
                boolean ok = openPosition(signal, currentPrice, cfg);
                if (ok) sendTradeEmail("加仓成功（请手动调整整仓止盈止损）", signal, currentPrice);
                return ok;
            }

            return false;
        } catch (Exception e) {
            log.error("下单失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 平仓试探仓：交易所必须有持仓
     */
    public synchronized boolean closeProbe(String direction) {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        if (!cfg.isEnabled()) return false;

        double currentPos = queryPositionSize();
        if (Double.isNaN(currentPos) || currentPos == 0) {
            log.warn("交易所无持仓，无需平仓");
            return false;
        }

        try {
            JsonNode result = tradeClient.closePosition(SWAP_INST_ID, "cross", null);
            boolean ok = "0".equals(result.path("code").asText());
            if (ok) {
                log.info("试探仓平仓成功");
                sendTradeEmail("平试探仓成功", null, 0);
            }
            return ok;
        } catch (Exception e) {
            log.error("平仓失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 查询交易所是否有持仓（供外部判断策略状态是否需要 reset）*/
    public boolean hasExchangePosition() {
        double pos = queryPositionSize();
        return !Double.isNaN(pos) && pos != 0;
    }

    /** 不再需要内存状态重置 */
    public void resetPositionState() { /* no-op, 以交易所为准 */ }

    private boolean openPosition(TradeSignal signal, double currentPrice,
                                  TradingProperties.TradeApi cfg) throws Exception {
        boolean isLong = signal.getAction() == TradeSignal.Action.LONG;
        boolean isAdd  = signal.getReason().contains("ADD");
        String side    = isLong ? "buy" : "sell";

        double capital = tradeClient.getBalance();
        if (capital <= 0) {
            log.error("账户余额为0或查询失败，拒绝下单");
            return false;
        }
        double positionValue = capital * signal.getPositionSize() * cfg.getLeverage();
        int contracts = Math.max(1, (int)(positionValue / (CONTRACT_SIZE * currentPrice)));

        // 加仓：下单时直接附带止损止盈（attachAlgoOrds），与下单原子同步
        // 人工收到邮件后再手动调整整仓止盈止损
        // 试探仓：同样附带止损，防止服务重启/调度延迟导致裸奔
        String sl = signal.getStopLoss() > 0
            ? String.format("%.1f", signal.getStopLoss()) : null;
        String tp = signal.getTakeProfit() > 0
            ? String.format("%.1f", signal.getTakeProfit()) : null;

        log.info("下单: {} {}张 {} SL={} TP={}", side, contracts, isAdd ? "加仓" : "试探仓", sl, tp);

        JsonNode result = tradeClient.placeOrder(
            SWAP_INST_ID, "cross", side, null,
            "market", String.valueOf(contracts), null,
            sl, tp);

        boolean ok = "0".equals(result.path("code").asText());
        if (!ok) {
            log.error("下单失败: {}", result.toString());
        }
        return ok;
    }

    // NEW: 触发永久熔断，发送告警邮件，此后程序生命周期内不再交易
    private void triggerKillSwitch(double currentBalance) {
        isKilled = true;
        double drawdownPct = (1.0 - currentBalance / initialBalance) * 100;
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(Instant.now());

        log.error("【紧急】Equity Kill Switch 触发！currentBalance={} initialBalance={} drawdown={}%",
            String.format("%.2f", currentBalance),
            String.format("%.2f", initialBalance),
            String.format("%.1f", drawdownPct));

        String subject = "【紧急】账户熔断触发 — 交易已永久停止";
        String body = String.format(
            "===== EQUITY KILL SWITCH =====\n\n" +
            "触发时间  : %s\n"                    +
            "当前余额  : %.2f USDT\n"             +
            "初始余额  : %.2f USDT\n"             +
            "跌幅      : -%.1f%%\n"               +
            "熔断线    : %.0f%% (%.2f USDT)\n\n"  +
            "所有新交易已永久停止。\n"             +
            "请人工检查账户并重启程序以恢复交易。\n\n" +
            "==============================",
            time, currentBalance, initialBalance, drawdownPct,
            KILL_THRESHOLD * 100, initialBalance * KILL_THRESHOLD);

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("熔断告警邮件发送失败: {}", e.getMessage());
        }
    }

    private void sendTradeEmail(String type, TradeSignal signal, double price) {
        TradingProperties.Email emailCfg = properties.getEmail();
        if (!emailCfg.isEnabled()) return;

        String subject = String.format("[%s] %s", type, properties.getOkx().getInstId().toUpperCase());
        String body;
        if (signal != null) {
            body = String.format(
                "===== %s =====\n\n" +
                "方向    : %s\n" +
                "价格    : %.2f\n" +
                "止损    : %.2f\n" +
                "止盈    : %.2f\n" +
                "仓位    : %.0f%%\n" +
                "模式    : %s\n\n" +
                "========================",
                type, signal.getAction().getValue().toUpperCase(), price,
                signal.getStopLoss(), signal.getTakeProfit(),
                signal.getPositionSize() * 100,
                properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘");
        } else {
            body = String.format("===== %s =====\n模式: %s\n========================",
                type, properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘");
        }

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("交易通知邮件发送失败: {}", e.getMessage());
        }
    }
}
