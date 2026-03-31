package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AggressiveStrategy — 状态机模型
 *
 * 模拟人类交易员的决策流程：
 *   IDLE → 发现方向信号 → 开试探仓（小仓位）
 *   PROBE → 价格确认 → 加仓（大仓位）/ 未确认 → 平仓认赔
 *   CONFIRMED → 止盈或止损 → 回到 IDLE
 *
 * 入场条件简单：EMA方向 + 价格动量（不需要横盘、不需要双窗口突破）
 * 确认条件：试探仓盈利超过阈值
 *
 * 信号输出：
 *   PROBE_LONG / PROBE_SHORT — 试探仓信号（小仓位）
 *   ADD_LONG / ADD_SHORT — 加仓信号（大仓位，回测引擎处理）
 *   CLOSE — 平仓信号（试探失败或止盈止损）
 */
@Component
public class AggressiveStrategy implements Strategy {

    public enum State { IDLE, PROBE, CONFIRMED }

    private final TradingProperties.StrategyParams p;

    // ── 状态机 ────────────────────────────────────────────────────────────
    private State  state = State.IDLE;
    private String direction;       // "up" / "down"
    private double probeEntryPrice; // 试探仓入场价
    private long   probeEntryTime;  // 试探仓入场时间（K线时间戳）
    private double stopLoss;
    private double takeProfit;

    @Autowired
    public AggressiveStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    public AggressiveStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override
    public String getName() { return "aggressive"; }

    public State getState() { return state; }

    @Override
    public TradeSignal generateSignal(MarketData data) {
        return switch (state) {
            case IDLE      -> evaluateEntry(data);
            case PROBE     -> evaluateConfirmation(data);
            case CONFIRMED -> evaluateExit(data);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDLE → 寻找方向信号，开试探仓
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateEntry(MarketData data) {
        KLine last  = data.getLastKline();
        KLine prev  = data.getPrevKline();
        KLine prev2 = data.getPrev2Kline();
        String trend = data.getTrendBias();

        // 做多条件：EMA趋势向上 + 连续两根阳线（价格动量）
        boolean longSignal = "up".equals(trend)
            && last.getClose() > prev.getClose()
            && prev.getClose() > prev2.getClose();

        // 做空条件：EMA趋势向下 + 连续两根阴线
        boolean shortSignal = "down".equals(trend)
            && last.getClose() < prev.getClose()
            && prev.getClose() < prev2.getClose();

        if (!longSignal && !shortSignal) {
            return noTrade(String.format("IDLE: no momentum (trend=%s)", trend));
        }

        // 开试探仓
        double price = data.getCurrentPrice();
        direction = longSignal ? "up" : "down";
        probeEntryPrice = price;
        probeEntryTime  = last.getTimestamp();

        // 止损：前一根K线中点
        double prevMid = (prev.getHigh() + prev.getLow()) / 2.0;
        double slDistance = Math.max(Math.abs(price - prevMid), price * p.getAggMinSlPct());
        stopLoss   = "up".equals(direction) ? price - slDistance : price + slDistance;
        takeProfit = "up".equals(direction)
            ? price + slDistance * p.getAggRiskRewardRatio()
            : price - slDistance * p.getAggRiskRewardRatio();

        state = State.PROBE;

        TradeSignal.Action action = "up".equals(direction)
            ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
        return new TradeSignal(action,
            String.format("PROBE %s: trend=%s momentum confirmed — SL=%.2f TP=%.2f",
                direction.toUpperCase(), trend, stopLoss, takeProfit),
            0.50, p.getAggPositionWeak(), stopLoss, takeProfit);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROBE → 检查试探仓是否确认（盈利超过阈值）或失败
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateConfirmation(MarketData data) {
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        // 计算试探仓浮盈
        double pnlPct = "up".equals(direction)
            ? (price - probeEntryPrice) / probeEntryPrice
            : (probeEntryPrice - price) / probeEntryPrice;

        // 确认条件：浮盈超过0.3%（方向对了）
        if (pnlPct > 0.003) {
            state = State.CONFIRMED;

            // 加仓信号：更新止损到保本附近，保持原止盈
            double newSl = "up".equals(direction)
                ? probeEntryPrice + (price - probeEntryPrice) * 0.2  // 保护20%浮盈
                : probeEntryPrice - (probeEntryPrice - price) * 0.2;
            stopLoss = newSl;

            TradeSignal.Action action = "up".equals(direction)
                ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
            return new TradeSignal(action,
                String.format("ADD %s: probe +%.2f%% confirmed — new SL=%.2f TP=%.2f",
                    direction.toUpperCase(), pnlPct * 100, stopLoss, takeProfit),
                0.80, p.getAggPositionStrong(), stopLoss, takeProfit);
        }

        // 超时未确认（6根K线=1.5小时）→ 平仓
        if (now - probeEntryTime > 5400000L) {
            reset();
            return noTrade("PROBE timeout (1.5h) — closing probe position");
        }

        // 浮亏超过止损 → 平仓
        if (("up".equals(direction) && price <= stopLoss)
            || ("down".equals(direction) && price >= stopLoss)) {
            reset();
            return noTrade("PROBE hit stop loss — closing");
        }

        return noTrade(String.format("PROBE %s: waiting confirmation (pnl=%.2f%%)",
            direction, pnlPct * 100));
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIRMED → 回测引擎接管止盈止损，策略不再干预
    // 实盘中这个状态不会被调用（人挂单管理）
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateExit(MarketData data) {
        // 回测中不应该走到这里，由回测引擎直接管理止盈止损
        // 但如果被调用了，返回 NO_TRADE 不影响持仓
        return noTrade(String.format("CONFIRMED %s: held by engine (SL=%.2f TP=%.2f)",
            direction, stopLoss, takeProfit));
    }

    /** 回测引擎平仓后调用，重置状态机 */
    public void reset() {
        state = State.IDLE;
        direction = null;
        probeEntryPrice = 0;
        probeEntryTime = 0;
        stopLoss = 0;
        takeProfit = 0;
    }

    /** 供回测引擎读取当前止损（移动止损后会变） */
    public double getStopLoss() { return stopLoss; }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
