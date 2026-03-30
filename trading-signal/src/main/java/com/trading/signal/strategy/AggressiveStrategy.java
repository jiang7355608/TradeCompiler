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
 * AggressiveStrategy — 所有参数从 application.yml 读取
 */
@Component
public class AggressiveStrategy implements Strategy {

    private final TradingProperties.StrategyParams p;
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>(); // 存K线timestamp（毫秒）
    @Autowired
    public AggressiveStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    /** 回测引擎专用构造器 */
    public AggressiveStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override
    public String getName() { return "aggressive"; }

    @Override
    public TradeSignal generateSignal(MarketData data) {

        // 1. 横盘蓄力
        if (!data.isRange()) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                "No consolidation range — breakout lacks momentum");
        }
        // 2. 放量
        if (!data.isVolumeSpike()) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("No volume spike (ratio=%.2f)",
                    data.getLastKline().getVolume() / Math.max(data.getAvgVolume20(), 1)));
        }
        // 3. 双窗口突破一致
        String b6 = data.getBreakout(), b14 = data.getBreakout14();
        if ("none".equals(b6)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE, "No breakout on 6-bar window");
        }
        if (!b6.equals(b14)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Breakout mismatch: 6-bar=%s, 14-bar=%s — noise", b6, b14));
        }
        // 4. 趋势过滤（neutral 允许）
        if (isAgainstTrend(b6, data.getTrendBias())) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Against trend: breakout=%s trendBias=%s", b6, data.getTrendBias()));
        }
        // 5. 至少单根延续
        if (!data.isContinuation()) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                "No continuation — price not moving in breakout direction");
        }
        // 6. 扫单K线过滤
        if (isFakeBreakCandle(data.getLastKline())) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Sweep candle (amplitude=%.2f%%)", data.getLastKlineAmplitude() * 100));
        }
        // 7. 位置过滤
        if (isMidRange(data)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Mid-range (%.0f%%)", rangePosition(data) * 100));
        }
        // 8. 冷却
        if (isInCooldown(b6, data.getLastKline().getTimestamp())) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                "Same direction within cooldown period");
        }

        boolean strongCont = isStrongContinuationForDirection(data, b6);
        TradeSignal signal = buildSignal(data, b6, strongCont);
        lastSignalTime.put(b6, data.getLastKline().getTimestamp());
        return signal;
    }

    private boolean isAgainstTrend(String breakout, String trendBias) {
        if ("neutral".equals(trendBias)) return false;
        return ("up".equals(breakout) && "down".equals(trendBias))
            || ("down".equals(breakout) && "up".equals(trendBias));
    }

    private boolean isStrongContinuationForDirection(MarketData data, String breakout) {
        if (!data.isStrongContinuation()) return false;
        KLine last = data.getLastKline();
        return "up".equals(breakout) ? last.getClose() > last.getOpen()
                                     : last.getClose() < last.getOpen();
    }

    private boolean isFakeBreakCandle(KLine k) {
        double range = k.getHigh() - k.getLow();
        if (range <= 0) return false;
        double bodyRatio = Math.abs(k.getClose() - k.getOpen()) / range;
        double amplitude = range / k.getClose();
        return bodyRatio < p.getAggSweepBodyRatio() && amplitude > p.getAggSweepAmplitude();
    }

    private boolean isMidRange(MarketData data) {
        double span = data.getRangeHigh() - data.getRangeLow();
        if (span <= 0) return false;
        double pos = rangePosition(data);
        return pos > p.getAggMidRangeLower() && pos < p.getAggMidRangeUpper();
    }

    private double rangePosition(MarketData data) {
        double span = data.getRangeHigh() - data.getRangeLow();
        if (span <= 0) return 0.5;
        return (data.getCurrentPrice() - data.getRangeLow()) / span;
    }

    private boolean isInCooldown(String direction, long currentTs) {
        Long last = lastSignalTime.get(direction);
        if (last == null) return false;
        return (currentTs - last) < p.getAggCooldownMs();
    }

    private TradeSignal buildSignal(MarketData data, String breakout, boolean strongCont) {
        double price      = data.getCurrentPrice();
        double confidence = strongCont ? 0.75 : 0.65;
        double position   = strongCont ? p.getAggPositionStrong() : p.getAggPositionWeak();
        String label      = strongCont ? "strong-cont(2-bar)" : "single-cont";

        // 动态止损：前一根K线的中点
        KLine prev = data.getPrevKline();
        double prevMid = (prev.getHigh() + prev.getLow()) / 2.0;

        // 兜底：止损距离不能小于最小比例
        double minSlDistance = price * p.getAggMinSlPct();

        double stopLoss, takeProfit;
        if ("up".equals(breakout)) {
            double slDistance = Math.max(Math.abs(price - prevMid), minSlDistance);
            stopLoss   = price - slDistance;
            takeProfit = price + slDistance * p.getAggRiskRewardRatio();
            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("Breakout UP + volume + %s + trend — %.0f%% pos | SL=%.2f TP=%.2f",
                    label, position * 100, stopLoss, takeProfit),
                confidence, position, stopLoss, takeProfit);
        }
        double slDistance = Math.max(Math.abs(prevMid - price), minSlDistance);
        stopLoss   = price + slDistance;
        takeProfit = price - slDistance * p.getAggRiskRewardRatio();
        return new TradeSignal(TradeSignal.Action.SHORT,
            String.format("Breakout DOWN + volume + %s + trend — %.0f%% pos | SL=%.2f TP=%.2f",
                label, position * 100, stopLoss, takeProfit),
            confidence, position, stopLoss, takeProfit);
    }
}
