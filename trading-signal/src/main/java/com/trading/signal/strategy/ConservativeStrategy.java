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
 * ConservativeStrategy — 所有参数从 application.yml 读取
 */
@Component
public class ConservativeStrategy implements Strategy {

    private final TradingProperties.StrategyParams p;
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>(); // 存K线timestamp（毫秒）
    @Autowired
    public ConservativeStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    /** 回测引擎专用构造器 */
    public ConservativeStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override
    public String getName() { return "conservative"; }

    @Override
    public TradeSignal generateSignal(MarketData data) {

        // 1. 放量
        if (!data.isVolumeSpike()) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("No volume spike (ratio=%.2f)",
                    data.getLastKline().getVolume() / Math.max(data.getAvgVolume20(), 1)));
        }
        // 2. 双窗口突破一致
        String b6 = data.getBreakout(), b14 = data.getBreakout14();
        if ("none".equals(b6)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE, "No breakout on 6-bar window");
        }
        if (!b6.equals(b14)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Breakout mismatch: 6-bar=%s, 14-bar=%s", b6, b14));
        }
        // 3. 趋势过滤（neutral 也拒绝）
        String trendBias = data.getTrendBias();
        if ("neutral".equals(trendBias)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                "Trend neutral — conservative requires clear trend");
        }
        if (isAgainstTrend(b6, trendBias)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Against trend: breakout=%s trendBias=%s", b6, trendBias));
        }
        // 4. 强延续（必须，不可降级）
        if (!isStrongContinuationForDirection(data, b6)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                "No strong continuation (2-bar) — conservative requires double confirmation");
        }
        // 5. 扫单K线过滤
        if (isFakeBreakCandle(data.getLastKline())) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Sweep candle (amplitude=%.2f%%)", data.getLastKlineAmplitude() * 100));
        }
        // 6. 位置过滤
        if (isMidRange(data)) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE,
                String.format("Mid-range (%.0f%%)", rangePosition(data) * 100));
        }
        // 7. 冷却
        if (isInCooldown(b6, data.getLastKline().getTimestamp())) {
            return new TradeSignal(TradeSignal.Action.NO_TRADE, "Same direction within cooldown");
        }

        TradeSignal signal = buildSignal(data, b6);
        lastSignalTime.put(b6, data.getLastKline().getTimestamp());
        return signal;
    }

    private boolean isAgainstTrend(String breakout, String trendBias) {
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
        // 保守策略复用激进策略的扫单阈值
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
        return (currentTs - last) < p.getConCooldownMs();
    }

    private TradeSignal buildSignal(MarketData data, String breakout) {
        boolean hasRange  = data.isRange();
        double  price     = data.getCurrentPrice();
        double  confidence = 0.65 + (hasRange ? 0.10 : 0.0);
        // 动态仓位：基础仓位 × (置信度 / 0.50)，上限40%
        double  basePosition = hasRange ? p.getConPositionBonus() : p.getConPositionBase();
        double  position     = Math.min(basePosition * (confidence / 0.50), 0.40);
        String  label      = hasRange ? " + consolidation" : "";

        // 动态止损：前一根K线的中点
        KLine prev = data.getPrevKline();
        double prevMid = (prev.getHigh() + prev.getLow()) / 2.0;

        // 兜底：止损距离不能小于最小比例
        double minSlDistance = price * p.getConMinSlPct();

        double stopLoss, takeProfit;
        if ("up".equals(breakout)) {
            double slDistance = Math.max(Math.abs(price - prevMid), minSlDistance);
            stopLoss   = price - slDistance;
            takeProfit = price + slDistance * p.getConRiskRewardRatio();
            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("Breakout UP + volume + strong-cont + trend-up%s — %.0f%% pos | SL=%.2f TP=%.2f",
                    label, position * 100, stopLoss, takeProfit),
                confidence, position, stopLoss, takeProfit);
        }
        double slDistance = Math.max(Math.abs(prevMid - price), minSlDistance);
        stopLoss   = price + slDistance;
        takeProfit = price - slDistance * p.getConRiskRewardRatio();
        return new TradeSignal(TradeSignal.Action.SHORT,
            String.format("Breakout DOWN + volume + strong-cont + trend-down%s — %.0f%% pos | SL=%.2f TP=%.2f",
                label, position * 100, stopLoss, takeProfit),
            confidence, position, stopLoss, takeProfit);
    }
}
