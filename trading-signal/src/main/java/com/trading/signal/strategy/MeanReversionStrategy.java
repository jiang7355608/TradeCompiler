package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MeanReversionStrategy — 人工大箱体 + 试探加仓
 *
 * 逻辑：
 *   IDLE → 价格到达上下沿 + 反转确认 → 10%仓位入场
 *   PROBE → 浮盈 > 1000美元 → 发加仓信号（30%）
 *   PROBE → 浮亏触及止损 or 超时 → 平仓
 *   CONFIRMED → 实盘由人管理，回测由引擎管止盈止损
 */
@Component
public class MeanReversionStrategy implements Strategy {

    public enum State { IDLE, PROBE, CONFIRMED }

    private final TradingProperties.StrategyParams p;
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>();

    private State  state = State.IDLE;
    private String direction;
    private double probeEntryPrice;
    private long   probeEntryTime;
    private double stopLoss;
    private double takeProfit;
    private int    consecutiveLosses = 0;
    private long   circuitBreakerUntil = 0;

    @Autowired
    public MeanReversionStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    public MeanReversionStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override public String getName() { return "mean-reversion"; }
    public State getState() { return state; }
    public double getStopLoss() { return stopLoss; }
    public double getTakeProfit() { return takeProfit; }

    @Override
    public TradeSignal generateSignal(MarketData data) {
        return switch (state) {
            case IDLE      -> evaluateEntry(data);
            case PROBE     -> evaluateConfirmation(data);
            case CONFIRMED -> evaluateExit(data);
        };
    }

    private TradeSignal evaluateEntry(MarketData data) {
        long currentTs = data.getLastKline().getTimestamp();
        if (currentTs < circuitBreakerUntil) return noTrade("Circuit breaker");

        double price     = data.getCurrentPrice();
        double rangeHigh = p.getMrRangeHigh();
        double rangeLow  = p.getMrRangeLow();
        double buffer    = p.getMrEntryBuffer();
        double rangeMid  = (rangeHigh + rangeLow) / 2.0;

        KLine last = data.getLastKline();
        KLine prev = data.getPrevKline();

        String dir = last.getClose() > last.getOpen() ? "up" : "down";
        if (isInCooldown(dir, currentTs)) return noTrade("Cooldown");

        // 下沿做多
        if (price <= rangeLow + buffer
                && last.getClose() > last.getOpen()
                && last.getClose() > prev.getClose()) {
            direction = "up";
            probeEntryPrice = price;
            probeEntryTime = currentTs;
            stopLoss = rangeLow - 2000;
            takeProfit = rangeMid;
            state = State.PROBE;
            lastSignalTime.put("up", currentTs);

            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("MR-PROBE LONG: price=%.0f near lower %.0f SL=%.0f TP=%.0f",
                    price, rangeLow, stopLoss, takeProfit),
                0.60, 0.10, stopLoss, takeProfit);
        }

        // 上沿做空
        if (price >= rangeHigh - buffer
                && last.getClose() < last.getOpen()
                && last.getClose() < prev.getClose()) {
            direction = "down";
            probeEntryPrice = price;
            probeEntryTime = currentTs;
            stopLoss = rangeHigh + 2000;
            takeProfit = rangeMid;
            state = State.PROBE;
            lastSignalTime.put("down", currentTs);

            return new TradeSignal(TradeSignal.Action.SHORT,
                String.format("MR-PROBE SHORT: price=%.0f near upper %.0f SL=%.0f TP=%.0f",
                    price, rangeHigh, stopLoss, takeProfit),
                0.60, 0.10, stopLoss, takeProfit);
        }

        return noTrade(String.format("Price %.0f in mid-range (%.0f - %.0f)", price, rangeLow, rangeHigh));
    }

    private TradeSignal evaluateConfirmation(MarketData data) {
        double price = data.getCurrentPrice();
        long now = data.getLastKline().getTimestamp();

        double pnl = "up".equals(direction)
            ? price - probeEntryPrice : probeEntryPrice - price;

        // 浮盈 > 2000美元 → 加仓
        if (pnl > 2000) {
            state = State.CONFIRMED;

            // 加仓后止损移到保本附近
            double newSl = "up".equals(direction)
                ? probeEntryPrice + 200 : probeEntryPrice - 200;
            stopLoss = newSl;

            TradeSignal.Action action = "up".equals(direction)
                ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
            return new TradeSignal(action,
                String.format("MR-ADD %s: pnl=+%.0f confirmed SL=%.0f TP=%.0f",
                    direction.toUpperCase(), pnl, stopLoss, takeProfit),
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

        return noTrade(String.format("MR-PROBE %s: pnl=%.0f waiting for +1000", direction, pnl));
    }

    private TradeSignal evaluateExit(MarketData data) {
        reset();
        return noTrade("CONFIRMED handed off — reset");
    }

    public void reset() {
        state = State.IDLE;
        direction = null;
        probeEntryPrice = 0;
        probeEntryTime = 0;
        stopLoss = 0;
        takeProfit = 0;
    }

    public void recordTradeResult(boolean isWin, long currentTs) {
        if (isWin) { consecutiveLosses = 0; }
        else {
            consecutiveLosses++;
            if (consecutiveLosses >= 3) {
                circuitBreakerUntil = currentTs + 14400000L;
                consecutiveLosses = 0;
            }
        }
    }

    private boolean isInCooldown(String dir, long currentTs) {
        Long last = lastSignalTime.get(dir);
        if (last == null) return false;
        return (currentTs - last) < p.getMrCooldownMs();
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
