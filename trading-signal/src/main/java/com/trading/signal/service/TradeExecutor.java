package com.trading.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.signal.client.OkxClient;
import com.trading.signal.client.OkxTradeClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.TradeSignal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private final TrailingStopMonitor trailingStopMonitor;
    private final OkxClient okxClient;
    private boolean leverageSet = false;

    // NEW: 账户级永久熔断（Equity Kill Switch）
    // initialBalance：程序启动时从交易所读取，作为风控基准
    // isKilled：一旦置 true，程序生命周期内不可恢复，必须重启
    private double  initialBalance = 0;
    private boolean isKilled       = false;
    private static final double KILL_THRESHOLD = 0.60; // 跌至初始资金60%触发

    public TradeExecutor(OkxTradeClient tradeClient, TradingProperties properties,
                         EmailService emailService, TrailingStopMonitor trailingStopMonitor,
                         OkxClient okxClient) {
        this.tradeClient = tradeClient;
        this.properties  = properties;
        this.emailService = emailService;
        this.trailingStopMonitor = trailingStopMonitor;
        this.okxClient = okxClient;
    }
    
    /**
     * 获取 OkxTradeClient 实例（用于其他服务获取余额等信息）
     */
    public OkxTradeClient getOkxTradeClient() {
        return tradeClient;
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
            boolean isBreakout = reason.contains("BREAKOUT");  // AggressiveStrategy 信号

            // 查询交易所实际持仓
            double currentPos = queryPositionSize();
            if (Double.isNaN(currentPos)) {
                log.error("无法确认持仓状态，拒绝下单");
                return false;
            }
            boolean hasPosition = currentPos != 0;

            // ── BREAKOUT 信号：交易所必须无持仓 ────────────────────────────
            if (isBreakout) {
                if (hasPosition) {
                    log.warn("交易所已有持仓({}张)，拒绝 BREAKOUT 开仓", currentPos);
                    return false;
                }
                boolean ok = openPosition(signal, currentPrice, cfg);
                if (ok) {
                    sendBreakoutEmail(signal, currentPrice);
                    // 启动 Trailing Stop 监控
                    try {
                        double atr = calculateATR();
                        String direction = signal.getAction() == TradeSignal.Action.LONG ? "long" : "short";
                        trailingStopMonitor.onPositionOpened(
                            direction, 
                            currentPrice, 
                            signal.getStopLoss(), 
                            atr
                        );
                        log.info("Trailing Stop 监控已启动: direction={} entry={} SL={} ATR={}", 
                            direction, currentPrice, signal.getStopLoss(), atr);
                    } catch (Exception e) {
                        log.error("启动 Trailing Stop 监控失败: {}", e.getMessage());
                    }
                }
                return ok;
            }

            // ── 试探仓：交易所必须无持仓 ────────────────────────────────
            if (isProbe) {
                if (hasPosition) {
                    log.warn("交易所已有持仓({}张)，拒绝开试探仓", currentPos);
                    return false;
                }
                boolean ok = openPosition(signal, currentPrice, cfg);
                if (ok) {
                    sendProbeEmail(signal, currentPrice);
                }
                return ok;
            }

            // ── 加仓：交易所必须已有持仓（试探仓）────────────────────────
            if (isAdd) {
                if (!hasPosition) {
                    log.warn("交易所无持仓，拒绝加仓（试探仓可能已被止损）");
                    return false;
                }
                boolean ok = openPosition(signal, currentPrice, cfg);
                if (ok) sendAddEmail(signal, currentPrice);
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
     * @return true=平仓成功或无需平仓，false=平仓失败（需要人工处理）
     */
    public synchronized boolean closeProbe(String direction) {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        if (!cfg.isEnabled()) return true;  // 未启用交易API，返回true避免误报

        double currentPos = queryPositionSize();
        if (Double.isNaN(currentPos)) {
            // 查询失败，无法确认持仓状态，返回false触发告警
            log.error("查询持仓失败，无法确认是否需要平仓");
            return false;
        }
        
        if (currentPos == 0) {
            // 交易所无持仓，可能已被止损或手动平仓，无需平仓
            log.info("交易所无持仓，无需平仓（可能已被止损或手动平仓）");
            return true;  // 返回true，不触发告警邮件
        }

        try {
            JsonNode result = tradeClient.closePosition(SWAP_INST_ID, "cross", null);
            boolean ok = "0".equals(result.path("code").asText());
            if (ok) {
                log.info("试探仓平仓成功");
                sendTradeEmail("平试探仓成功", null, 0);
                // 停止 Trailing Stop 监控
                trailingStopMonitor.onPositionClosed();
            } else {
                log.error("平仓API调用失败: {}", result.toString());
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
        
        // 置信度调整仓位：实际仓位 = 基础仓位 × 置信度
        // 例如：positionSize=0.10, confidence=0.60 → 实际仓位=0.06 (6%本金)
        //      positionSize=0.30, confidence=0.85 → 实际仓位=0.255 (25.5%本金)
        // 这样高质量信号自动放大仓位，低质量信号自动缩小仓位
        double effectivePositionSize = signal.getPositionSize() * signal.getConfidence();
        double positionValue = capital * effectivePositionSize * cfg.getLeverage();
        int contracts = Math.max(1, (int)(positionValue / (CONTRACT_SIZE * currentPrice)));

        log.info("仓位计算: 本金={} 基础仓位={} 置信度={} 实际仓位={} 杠杆={} 名义价值={} 合约张数={}",
            String.format("%.2f", capital),
            String.format("%.2f", signal.getPositionSize()),
            String.format("%.2f", signal.getConfidence()),
            String.format("%.4f", effectivePositionSize),
            cfg.getLeverage(),
            String.format("%.2f", positionValue),
            contracts);

        // 止盈止损处理：
        // - 试探仓（stopLoss=0）：不带止盈止损，策略监控保护
        // - 加仓（stopLoss>0）：attachAlgoOrds 原子绑定整仓止盈止损
        String sl = (signal.getStopLoss() > 0)
            ? String.format("%.1f", signal.getStopLoss()) : null;
        String tp = (signal.getTakeProfit() > 0)
            ? String.format("%.1f", signal.getTakeProfit()) : null;

        if (isAdd && sl != null) {
            log.info("加仓: {} {}张 整仓止损={} 整仓止盈={} (attachAlgoOrds原子绑定)",
                side, contracts, sl, tp);
        } else if (!isAdd && sl == null) {
            log.info("试探仓: {} {}张 无交易所止损（策略监控保护）", side, contracts);
        } else {
            log.info("下单: {} {}张 {} SL={} TP={}", side, contracts, isAdd ? "加仓" : "试探仓", sl, tp);
        }

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

    /**
     * 发送试探仓邮件（不带止盈止损）
     */
    private void sendProbeEmail(TradeSignal signal, double price) {
        TradingProperties.Email emailCfg = properties.getEmail();
        if (!emailCfg.isEnabled()) return;

        String subject = String.format("[试探仓开仓] %s", properties.getOkx().getInstId().toUpperCase());
        String body = String.format(
            "===== 试探仓开仓成功 =====\n\n" +
            "方向    : %s\n" +
            "价格    : %.2f\n" +
            "仓位    : %.0f%% (实际 %.1f%%)\n" +
            "止损    : 无（策略监控保护）\n" +
            "止盈    : 无\n" +
            "超时    : 3小时自动平仓\n" +
            "模式    : %s\n\n" +
            "【说明】\n" +
            "试探仓不带交易所止损，由策略监控保护：\n" +
            "- 每1分钟检查止损条件\n" +
            "- 持仓超过3小时自动平仓\n" +
            "- 浮盈达标+持仓45分钟后触发加仓\n\n" +
            "========================",
            signal.getAction().getValue().toUpperCase(),
            price,
            signal.getPositionSize() * 100,
            signal.getPositionSize() * signal.getConfidence() * 100,
            properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘");

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("试探仓邮件发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送 BREAKOUT 开仓邮件（使用 Trailing Stop）
     */
    private void sendBreakoutEmail(TradeSignal signal, double price) {
        TradingProperties.Email emailCfg = properties.getEmail();
        if (!emailCfg.isEnabled()) return;

        String subject = String.format("[突破开仓] %s", properties.getOkx().getInstId().toUpperCase());
        String body = String.format(
            "===== 突破开仓成功 =====\n\n" +
            "方向    : %s\n" +
            "价格    : %.2f\n" +
            "仓位    : %.0f%% (实际 %.1f%%)\n" +
            "初始止损: %.2f\n" +
            "止盈    : Trailing Stop (ATR × 2.5)\n" +
            "模式    : %s\n\n" +
            "【说明】\n" +
            "使用 Trailing Stop 策略：\n" +
            "- 每1分钟更新 trailing stop\n" +
            "- 止损线只能向有利方向移动\n" +
            "- 距离当前价格 ATR × 2.5\n" +
            "- 目标：持有趋势，获取大额利润\n\n" +
            "========================",
            signal.getAction().getValue().toUpperCase(),
            price,
            signal.getPositionSize() * 100,
            signal.getPositionSize() * signal.getConfidence() * 100,
            signal.getStopLoss(),
            properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘");

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("突破开仓邮件发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送加仓邮件（带整仓止盈止损）
     */
    private void sendAddEmail(TradeSignal signal, double price) {
        TradingProperties.Email emailCfg = properties.getEmail();
        if (!emailCfg.isEnabled()) return;

        String subject = String.format("[加仓成功] %s", properties.getOkx().getInstId().toUpperCase());
        String body = String.format(
            "===== 加仓成功 =====\n\n" +
            "方向    : %s\n" +
            "价格    : %.2f\n" +
            "仓位    : %.0f%% (实际 %.1f%%)\n" +
            "整仓止损: %.2f\n" +
            "整仓止盈: %.2f\n" +
            "模式    : %s\n\n" +
            "【说明】\n" +
            "加仓时已通过 attachAlgoOrds 原子绑定整仓止盈止损。\n" +
            "交易所会自动执行，无需人工干预。\n" +
            "如需调整止盈止损，请登录交易所手动修改。\n\n" +
            "========================",
            signal.getAction().getValue().toUpperCase(),
            price,
            signal.getPositionSize() * 100,
            signal.getPositionSize() * signal.getConfidence() * 100,
            signal.getStopLoss(),
            signal.getTakeProfit(),
            properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘");

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("加仓邮件发送失败: {}", e.getMessage());
        }
    }

    /**
     * 计算当前 ATR（用于 Trailing Stop）
     * 使用最近 14 根 K 线计算 ATR
     */
    private double calculateATR() throws Exception {
        TradingProperties.Okx okxCfg = properties.getOkx();
        List<KLine> klines = okxClient.fetchCandles(okxCfg.getInstId(), okxCfg.getTimeframe(), 15);
        
        if (klines.size() < 2) {
            throw new IllegalStateException("K线数据不足，无法计算ATR");
        }
        
        double sum = 0;
        for (int i = 1; i < klines.size(); i++) {
            KLine curr = klines.get(i);
            KLine prev = klines.get(i - 1);
            double tr = Math.max(
                curr.getHigh() - curr.getLow(),
                Math.max(
                    Math.abs(curr.getHigh() - prev.getClose()),
                    Math.abs(curr.getLow() - prev.getClose())
                )
            );
            sum += tr;
        }
        
        return sum / (klines.size() - 1);
    }
}
