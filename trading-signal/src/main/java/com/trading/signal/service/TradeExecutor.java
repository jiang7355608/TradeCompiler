package com.trading.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.signal.client.OkxTradeClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public TradeExecutor(OkxTradeClient tradeClient, TradingProperties properties,
                         EmailService emailService) {
        this.tradeClient = tradeClient;
        this.properties  = properties;
        this.emailService = emailService;
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
