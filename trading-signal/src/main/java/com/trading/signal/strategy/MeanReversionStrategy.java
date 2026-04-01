package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MeanReversionStrategy — 人工大箱体 + 试探加仓
 *
 * 修复清单：
 *   #1 线程安全：所有状态操作加 synchronized
 *   #2 冷却期：改为全局冷却（不分方向）
 *   #3 日志：确认阈值动态显示
 *   #4 加仓止损：动态计算（箱体宽度×5%）
 *   #5 CONFIRMED状态：不立即reset，保留持仓信息供API查询
 *   #6 冷却方向：用实际开仓方向，不用K线方向
 */
@Component
public class MeanReversionStrategy implements Strategy {

    public enum State { IDLE, PROBE, CONFIRMED }

    private final TradingProperties.StrategyParams p;

    // ── 状态（全部通过 synchronized 保护）──────────────────────────────
    private State  state = State.IDLE;
    private String direction;
    private double probeEntryPrice;
    private long   probeEntryTime;
    private double stopLoss;
    private double takeProfit;
    private int    consecutiveLosses = 0;
    private long   circuitBreakerUntil = 0;
    private long   lastTradeTime = 0;  // 全局冷却（不分方向）

    @Autowired
    public MeanReversionStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    public MeanReversionStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override public String getName() { return "mean-reversion"; }

    // ── 线程安全的状态访问 ─────────────────────────────────────────────
    public synchronized State  getState()      { return state; }
    public synchronized double getStopLoss()   { return stopLoss; }
    public synchronized double getTakeProfit()  { return takeProfit; }
    public synchronized String getDirection()   { return direction; }
    public synchronized boolean hasPosition()   { return state != State.IDLE; }

    @Override
    public synchronized TradeSignal generateSignal(MarketData data) {
        return switch (state) {
            case IDLE      -> evaluateEntry(data);
            case PROBE     -> evaluateConfirmation(data);
            case CONFIRMED -> evaluateExit(data);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDLE → 寻找上下沿入场机会
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateEntry(MarketData data) {
        long currentTs = data.getLastKline().getTimestamp();

        // 熔断
        if (currentTs < circuitBreakerUntil) return noTrade("Circuit breaker");

        // 全局冷却（不分方向，防止两头开仓）
        if (currentTs - lastTradeTime < p.getMrCooldownMs()) return noTrade("Cooldown");

        double price     = data.getCurrentPrice();
        double atr       = data.getAtr();
        double rangeHigh = p.getMrRangeHigh();
        double rangeLow  = p.getMrRangeLow();
        double buffer    = p.getMrEntryBuffer();
        double rangeSpan = rangeHigh - rangeLow;
        double rangeMid  = (rangeHigh + rangeLow) / 2.0;

        if (atr <= 0 || rangeSpan <= 0) return noTrade("Invalid ATR or range");

        // 动态止损距离：箱体宽度×30%，但不小于2×ATR
        double slDist = Math.max(rangeSpan * 0.30, atr * 2);

        KLine last = data.getLastKline();
        KLine prev = data.getPrevKline();

        // 下沿做多
        if (price <= rangeLow + buffer
                && last.getClose() > last.getOpen()
                && last.getClose() > prev.getClose()) {
            direction       = "up";
            probeEntryPrice = price;
            probeEntryTime  = currentTs;
            stopLoss        = rangeLow - slDist;
            takeProfit      = rangeMid;
            state           = State.PROBE;
            lastTradeTime   = currentTs;

            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("MR-PROBE LONG: price=%.0f near lower %.0f SL=%.0f TP=%.0f",
                    price, rangeLow, stopLoss, takeProfit),
                0.60, 0.10, stopLoss, takeProfit);
        }

        // 上沿做空
        if (price >= rangeHigh - buffer
                && last.getClose() < last.getOpen()
                && last.getClose() < prev.getClose()) {
            direction       = "down";
            probeEntryPrice = price;
            probeEntryTime  = currentTs;
            stopLoss        = rangeHigh + slDist;
            takeProfit      = rangeMid;
            state           = State.PROBE;
            lastTradeTime   = currentTs;

            return new TradeSignal(TradeSignal.Action.SHORT,
                String.format("MR-PROBE SHORT: price=%.0f near upper %.0f SL=%.0f TP=%.0f",
                    price, rangeHigh, stopLoss, takeProfit),
                0.60, 0.10, stopLoss, takeProfit);
        }

        return noTrade(String.format("Price %.0f in mid-range (%.0f - %.0f)", price, rangeLow, rangeHigh));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROBE → 确认加仓 or 止损/超时
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateConfirmation(MarketData data) {
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        double pnl = "up".equals(direction)
            ? price - probeEntryPrice : probeEntryPrice - price;

        // 动态确认阈值：箱体宽度×25%
        double rangeSpan = p.getMrRangeHigh() - p.getMrRangeLow();
        double confirmThreshold = rangeSpan * 0.25;

        // 浮盈超过阈值 + 持仓至少45分钟
        boolean pnlOk  = pnl > confirmThreshold;
        boolean timeOk = (now - probeEntryTime) >= 2700000L;

        if (pnlOk && timeOk) {
            state = State.CONFIRMED;

            // 动态保本止损：入场价 + 箱体宽度×5%
            double protectDist = rangeSpan * 0.05;
            double newSl = "up".equals(direction)
                ? probeEntryPrice + protectDist
                : probeEntryPrice - protectDist;
            stopLoss = newSl;

            TradeSignal.Action action = "up".equals(direction)
                ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
            return new TradeSignal(action,
                String.format("MR-ADD %s: pnl=+%.0f > %.0f(25%%span) held>45min SL=%.0f TP=%.0f",
                    direction.toUpperCase(), pnl, confirmThreshold, stopLoss, takeProfit),
                0.85, 0.30, stopLoss, takeProfit);
        }

        // 超时（24小时）
        if (now - probeEntryTime > 86400000L) {
            reset();
            return noTrade("MR-PROBE timeout 24h — closing");
        }

        // 止损
        if (("up".equals(direction) && price <= stopLoss)
                || ("down".equals(direction) && price >= stopLoss)) {
            reset();
            return noTrade("MR-PROBE hit stop loss — closing");
        }

        return noTrade(String.format("MR-PROBE %s: pnl=%.0f waiting for +%.0f(25%%span)",
            direction, pnl, confirmThreshold));
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIRMED → 保留状态供API查询，不立即reset
    // 实盘：API下单后调用 confirmHandedOff() 手动reset
    // 回测：引擎平仓后调用 reset()
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateExit(MarketData data) {
        // 不自动reset，保留持仓信息
        // 返回NO_TRADE让调用方知道当前有确认仓在场
        return noTrade(String.format("CONFIRMED %s: SL=%.0f TP=%.0f — awaiting API/manual",
            direction, stopLoss, takeProfit));
    }

    /** API下单完成后调用，确认持仓已交接 */
    public synchronized void confirmHandedOff() {
        if (state == State.CONFIRMED) {
            reset();
        }
    }

    public synchronized void reset() {
        state           = State.IDLE;
        direction       = null;
        probeEntryPrice = 0;
        probeEntryTime  = 0;
        stopLoss        = 0;
        takeProfit      = 0;
    }

    public synchronized void recordTradeResult(boolean isWin, long currentTs) {
        if (isWin) {
            consecutiveLosses = 0;
        } else {
            consecutiveLosses++;
            if (consecutiveLosses >= 3) {
                circuitBreakerUntil = currentTs + 14400000L;
                consecutiveLosses = 0;
            }
        }
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
