package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.HtfRange;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 均值回归策略（Mean Reversion）
 *
 * 适用场景：震荡行情，价格在区间内反复运动
 * 失效场景：单边趋势行情，价格突破区间后不回头
 *
 * 核心思路：在区间上下沿做反转交易
 *   - 下沿反弹 → 做多（价格跌到区间底部后出现反弹信号）
 *   - 上沿回落 → 做空（价格涨到区间顶部后出现转弱信号）
 *
 * 关键过滤：
 *   - 必须处于区间内（isRange=true）
 *   - 任何窗口出现突破信号时禁止入场（防止在趋势启动时做反转）
 *   - 振幅过大的K线不做反转（大阳/大阴说明趋势力量强，不适合逆向）
 *   - 要求"先跌再弹"或"先涨再落"的两根K线形态（避免接飞刀）
 */
@Component
public class MeanReversionStrategy implements Strategy {

    private final TradingProperties.StrategyParams p;
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>();

    // ── 熔断机制：连续亏损后暂停交易 ──────────────────────────────────────
    private int  consecutiveLosses = 0;
    private long circuitBreakerUntil = 0; // 熔断恢复时间（K线时间戳）

    /** 记录交易结果，供回测引擎调用 */
    public void recordTradeResult(boolean isWin, long currentTs) {
        if (isWin) {
            consecutiveLosses = 0;
        } else {
            consecutiveLosses++;
            if (consecutiveLosses >= 3) {
                // 连续亏损3次，熔断2小时（8根15分钟K线）
                circuitBreakerUntil = currentTs + 7200000L;
                consecutiveLosses = 0; // 重置，熔断结束后重新计数
            }
        }
    }

    @Autowired
    public MeanReversionStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    /** 回测引擎专用构造器 */
    public MeanReversionStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override
    public String getName() { return "mean-reversion"; }

    /**
     * 大小周期配合版本：
     * - 大箱体（4h）定方向和止盈目标
     * - 小周期（15m）定入场时机和止损
     */
    @Override
    public TradeSignal generateSignal(MarketData data, HtfRange htf) {
        // 熔断检查
        long currentTs = data.getLastKline().getTimestamp();
        if (currentTs < circuitBreakerUntil) {
            return noTrade(String.format("Circuit breaker active (resumes in %d min)",
                (circuitBreakerUntil - currentTs) / 60000));
        }

        // 如果没有大箱体数据或大周期不在区间，退回小周期逻辑
        if (htf == null || !htf.isRange()) {
            return generateSignal(data);
        }

        // 大箱体趋势过滤（软惩罚）：大周期有趋势时降低置信度，不直接禁止
        // 3月涨3%这种弱趋势里日内仍有震荡机会，不应一刀切
        boolean htfTrendActive = !"neutral".equals(htf.getTrendBias());

        // 用15分钟数据做入场过滤（复用现有逻辑的过滤条件）
        // 但箱体上下沿用大箱体的
        double price    = data.getCurrentPrice();
        double htfHigh  = htf.getRangeHigh();
        double htfLow   = htf.getRangeLow();
        double htfSpan  = htf.getSpan();

        KLine last  = data.getLastKline();
        KLine prev  = data.getPrevKline();
        KLine prev2 = data.getPrev2Kline();

        // 15分钟级别过滤
        boolean b14Active = !"none".equals(data.getBreakout14());
        if (b14Active) {
            return noTrade(String.format("15m breakout14 detected (%s) — no HTF reversal", data.getBreakout14()));
        }
        boolean b6Active = !"none".equals(data.getBreakout());
        boolean highAmplitude = data.getLastKlineAmplitude() > p.getMrMaxAmplitude();
        if (data.getLastKlineAmplitude() > p.getMrMaxAmplitude() * 1.5) {
            return noTrade("Extreme 15m amplitude — hard block");
        }

        String bias15m = data.getTrendBias();
        boolean trendInvading = data.getEma20() > 0
            && Math.abs(data.getEma5() - data.getEma20()) / data.getEma20() > p.getMrTrendStrengthLimit();
        boolean strongDownTrend = "down".equals(bias15m) && data.isStrongContinuation();
        boolean strongUpTrend   = "up".equals(bias15m) && data.isStrongContinuation();
        boolean risingMomentum  = last.getClose() > prev.getClose() && prev.getClose() > prev2.getClose();
        boolean fallingMomentum = last.getClose() < prev.getClose() && prev.getClose() < prev2.getClose();

        // 接近大箱体下沿 → 做多
        double lowerZone = htfLow + htfSpan * p.getMrBuffer();
        if (price <= lowerZone) {
            boolean weakBounce   = last.getClose() > prev.getClose();
            boolean strongBounce = weakBounce && prev.getClose() < prev2.getClose();
            if (strongBounce) {
                return buildHtfLongSignal(data, htf, 0.70, b6Active, highAmplitude,
                    trendInvading, strongDownTrend, fallingMomentum, htfTrendActive);
            }
            if (weakBounce) {
                return buildHtfLongSignal(data, htf, 0.55, b6Active, highAmplitude,
                    trendInvading, strongDownTrend, fallingMomentum, htfTrendActive);
            }
            return noTrade("Near HTF lower bound but no bounce yet");
        }

        // 接近大箱体上沿 → 做空
        double upperZone = htfHigh - htfSpan * p.getMrBuffer();
        if (price >= upperZone) {
            boolean weakReject   = last.getClose() < prev.getClose();
            boolean strongReject = weakReject && prev.getClose() > prev2.getClose();
            if (strongReject) {
                return buildHtfShortSignal(data, htf, 0.70, b6Active, highAmplitude,
                    trendInvading, strongUpTrend, risingMomentum, htfTrendActive);
            }
            if (weakReject) {
                return buildHtfShortSignal(data, htf, 0.55, b6Active, highAmplitude,
                    trendInvading, strongUpTrend, risingMomentum, htfTrendActive);
            }
            return noTrade("Near HTF upper bound but no weakness yet");
        }

        return noTrade(String.format("Price in HTF mid-range (%.0f%%), waiting",
            htfSpan > 0 ? (price - htfLow) / htfSpan * 100 : 50));
    }

    @Override
    public TradeSignal generateSignal(MarketData data) {

        // 熔断检查
        long currentTs = data.getLastKline().getTimestamp();
        if (currentTs < circuitBreakerUntil) {
            return noTrade(String.format("Circuit breaker active (resumes in %d min)",
                (circuitBreakerUntil - currentTs) / 60000));
        }

        // 1. 必须处于区间（非区间说明市场在趋势中，不做反转）
        if (!data.isRange()) {
            return noTrade("Not in range — mean reversion requires consolidation");
        }

        // 1.5 趋势入侵检测（软惩罚）：EMA偏离度大 → 降低置信度，不直接禁止
        double trendStrength = data.getEma20() > 0
            ? Math.abs(data.getEma5() - data.getEma20()) / data.getEma20() : 0;
        boolean trendInvading = trendStrength > p.getMrTrendStrengthLimit();

        // 1.6 趋势萌芽检测：拆分方向，避免误杀顺势回调
        //     strongUpTrend: 上涨趋势启动 → 禁止在上沿做空（摸顶），但允许下沿做多（顺势）
        //     strongDownTrend: 下跌趋势启动 → 禁止在下沿做多（接飞刀），但允许上沿做空（顺势）
        String bias = data.getTrendBias();
        boolean strongUpTrend   = "up".equals(bias) && data.isStrongContinuation();
        boolean strongDownTrend = "down".equals(bias) && data.isStrongContinuation();

        // 1.7 方向性动量：连续两根K线同向
        KLine last  = data.getLastKline();
        KLine prev  = data.getPrevKline();
        KLine prev2 = data.getPrev2Kline();
        boolean risingMomentum  = last.getClose() > prev.getClose() && prev.getClose() > prev2.getClose();
        boolean fallingMomentum = last.getClose() < prev.getClose() && prev.getClose() < prev2.getClose();

        // 2. 突破过滤（软化）：
        //    仅当长窗口(14根)出现突破时才禁止——短窗口突破在区间内常见，不一定是趋势启动
        //    短窗口突破降低置信度但不禁止
        boolean b14Active = !"none".equals(data.getBreakout14());
        if (b14Active) {
            return noTrade(String.format("Long-window breakout detected (b14=%s) — no reversal",
                data.getBreakout14()));
        }
        // 短窗口突破作为置信度惩罚因子
        boolean b6Active = !"none".equals(data.getBreakout());

        // 3. 振幅过滤（软化）：振幅大不直接禁止，而是降低置信度
        //    超过阈值×1.5才硬禁止（极端行情），阈值内用置信度惩罚
        boolean highAmplitude = data.getLastKlineAmplitude() > p.getMrMaxAmplitude();
        if (data.getLastKlineAmplitude() > p.getMrMaxAmplitude() * 1.5) {
            return noTrade(String.format("Extreme amplitude (%.2f%% > %.2f%%) — hard block",
                data.getLastKlineAmplitude() * 100, p.getMrMaxAmplitude() * 1.5 * 100));
        }

        double price = data.getCurrentPrice();
        double rangeHigh = data.getRangeHigh();
        double rangeLow  = data.getRangeLow();

        // 4. 判断是否接近下沿（做多条件）
        double lowerZone = rangeLow * (1 + p.getMrBuffer());
        if (price <= lowerZone) {
            boolean weakBounce   = last.getClose() > prev.getClose();
            boolean strongBounce = weakBounce && prev.getClose() < prev2.getClose();

            if (strongBounce) {
                return buildLongSignal(data, 0.70, b6Active, highAmplitude,
                    trendInvading, strongDownTrend, fallingMomentum);
            }
            if (weakBounce) {
                return buildLongSignal(data, 0.55, b6Active, highAmplitude,
                    trendInvading, strongDownTrend, fallingMomentum);
            }
            return noTrade("Near lower bound but no bounce yet");
        }

        // 5. 判断是否接近上沿（做空条件）
        double upperZone = rangeHigh * (1 - p.getMrBuffer());
        if (price >= upperZone) {
            boolean weakReject   = last.getClose() < prev.getClose();
            boolean strongReject = weakReject && prev.getClose() > prev2.getClose();

            if (strongReject) {
                return buildShortSignal(data, 0.70, b6Active, highAmplitude,
                    trendInvading, strongUpTrend, risingMomentum);
            }
            if (weakReject) {
                return buildShortSignal(data, 0.55, b6Active, highAmplitude,
                    trendInvading, strongUpTrend, risingMomentum);
            }
            return noTrade("Near upper bound but no weakness yet");
        }

        return noTrade(String.format("Price in mid-range (%.0f%%), waiting for extremes",
            rangePosition(data) * 100));
    }

    // ── 信号构建 ──────────────────────────────────────────────────────────

    private TradeSignal buildLongSignal(MarketData data, double baseConfidence,
                                        boolean b6Active, boolean highAmplitude,
                                        boolean trendInvading, boolean counterTrendSprouting,
                                        boolean counterMomentum) {
        String direction = "up";
        if (isInCooldown(direction, data.getLastKline().getTimestamp())) {
            return noTrade("Long signal in cooldown");
        }

        // 唯一硬禁止：强下跌趋势萌芽时禁止做多（接飞刀）
        // 强上涨趋势萌芽时允许做多（顺势回调买入）
        double confidence = baseConfidence;
        if (counterTrendSprouting) {
            return noTrade("Strong downtrend sprouting — hard block long (catching knife)");
        }
        if (b6Active)        confidence -= 0.10;  // 短窗口突破
        if (highAmplitude)   confidence -= 0.05;  // 高振幅
        if (trendInvading)   confidence -= 0.15;  // EMA趋势入侵
        if (counterMomentum) confidence -= 0.10;  // 逆向动量（下沿做多但连续下跌）
        if ("down".equals(data.getTrendBias())) confidence -= 0.15; // 趋势偏空（软惩罚）
        confidence = Math.max(confidence, 0.30);  // 下限

        double price    = data.getCurrentPrice();
        double rangeLow = data.getRangeLow();
        double rangeSpan = data.getRangeHigh() - rangeLow;

        // 止损：区间下沿 - 区间宽度×slBuffer，但不超过价格的maxSlPct
        double rawSlDistance  = price - (rangeLow - rangeSpan * p.getMrSlBuffer());
        double maxSlDistance  = price * p.getMrMaxSlPct();
        double slDistance     = Math.min(rawSlDistance, maxSlDistance);
        double stopLoss       = price - slDistance;

        // 止盈：用 rawSlDistance（真实箱体结构）计算，不受 maxSlPct 截断影响
        // 越界保护：做多止盈不超过 rangeHigh 内侧 10%
        double maxReasonableTp = data.getRangeHigh() - rangeSpan * 0.05;
        double takeProfit      = Math.min(price + rawSlDistance * p.getMrRiskRewardRatio(), maxReasonableTp);

        lastSignalTime.put(direction, data.getLastKline().getTimestamp());
        return new TradeSignal(TradeSignal.Action.LONG,
            String.format("MR: bounce off lower bound (%.2f) — conf=%.2f SL=%.2f TP=%.2f",
                rangeLow, confidence, stopLoss, takeProfit),
            confidence, calcPosition(confidence), stopLoss, takeProfit);
    }

    private TradeSignal buildShortSignal(MarketData data, double baseConfidence,
                                          boolean b6Active, boolean highAmplitude,
                                          boolean trendInvading, boolean counterTrendSprouting,
                                          boolean counterMomentum) {
        String direction = "down";
        if (isInCooldown(direction, data.getLastKline().getTimestamp())) {
            return noTrade("Short signal in cooldown");
        }

        // 唯一硬禁止：强上涨趋势萌芽时禁止做空（摸顶）
        // 强下跌趋势萌芽时允许做空（顺势反弹卖出）
        double confidence = baseConfidence;
        if (counterTrendSprouting) {
            return noTrade("Strong uptrend sprouting — hard block short (fading top)");
        }
        if (b6Active)        confidence -= 0.10;
        if (highAmplitude)   confidence -= 0.05;
        if (trendInvading)   confidence -= 0.15;
        if (counterMomentum) confidence -= 0.10;  // 上沿做空但连续上涨
        if ("up".equals(data.getTrendBias())) confidence -= 0.15; // 趋势偏多（软惩罚）
        confidence = Math.max(confidence, 0.30);

        double price     = data.getCurrentPrice();
        double rangeHigh = data.getRangeHigh();
        double rangeSpan = rangeHigh - data.getRangeLow();

        // 止损：区间上沿 + 区间宽度×slBuffer，但不超过价格的maxSlPct
        double rawSlDistance  = (rangeHigh + rangeSpan * p.getMrSlBuffer()) - price;
        double maxSlDistance  = price * p.getMrMaxSlPct();
        double slDistance     = Math.min(rawSlDistance, maxSlDistance);
        double stopLoss       = price + slDistance;

        // 止盈：用 rawSlDistance（真实箱体结构）计算，不受 maxSlPct 截断影响
        // 越界保护：做空止盈不低于 rangeLow 内侧 10%
        double minReasonableTp = data.getRangeLow() + rangeSpan * 0.05;
        double takeProfit      = Math.max(price - rawSlDistance * p.getMrRiskRewardRatio(), minReasonableTp);

        lastSignalTime.put(direction, data.getLastKline().getTimestamp());
        return new TradeSignal(TradeSignal.Action.SHORT,
            String.format("MR: rejection at upper bound (%.2f) — conf=%.2f SL=%.2f TP=%.2f",
                rangeHigh, confidence, stopLoss, takeProfit),
            confidence, calcPosition(confidence), stopLoss, takeProfit);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /** 大箱体做多：止损用15分钟前K线中点，止盈目标大箱体上沿 */
    private TradeSignal buildHtfLongSignal(MarketData data, HtfRange htf, double baseConfidence,
                                            boolean b6Active, boolean highAmplitude,
                                            boolean trendInvading, boolean counterTrendSprouting,
                                            boolean counterMomentum, boolean htfTrendActive) {
        if (isInCooldown("up", data.getLastKline().getTimestamp())) return noTrade("HTF long in cooldown");
        if (counterTrendSprouting) return noTrade("Strong downtrend — hard block HTF long");

        double confidence = baseConfidence;
        if (b6Active)        confidence -= 0.10;
        if (highAmplitude)   confidence -= 0.05;
        if (trendInvading)   confidence -= 0.15;
        if (counterMomentum) confidence -= 0.10;
        if ("down".equals(data.getTrendBias())) confidence -= 0.15;
        if (htfTrendActive && "down".equals(htf.getTrendBias())) confidence -= 0.15; // HTF逆势软惩罚
        confidence = Math.max(confidence, 0.30);

        double price = data.getCurrentPrice();
        // 止损：大箱体下沿外 slBuffer 比例（用大箱体结构，不用15分钟紧止损）
        double slDistance = (price - htf.getRangeLow()) + htf.getSpan() * p.getMrSlBuffer();
        double maxSlDistance = price * p.getMrMaxSlPct() * 3; // 大箱体上限放宽3倍
        slDistance = Math.min(slDistance, maxSlDistance);
        slDistance = Math.max(slDistance, price * 0.002); // 兜底
        double stopLoss = price - slDistance;
        // 止盈：止损距离 × 风险回报比，但不超过大箱体上沿内侧5%
        double rrTakeProfit  = price + slDistance * p.getMrRiskRewardRatio();
        double maxTakeProfit = htf.getRangeHigh() - htf.getSpan() * 0.05;
        double takeProfit    = Math.min(rrTakeProfit, maxTakeProfit);

        lastSignalTime.put("up", data.getLastKline().getTimestamp());
        return new TradeSignal(TradeSignal.Action.LONG,
            String.format("MR-HTF: bounce off HTF lower (%.0f) — conf=%.2f SL=%.2f TP=%.2f",
                htf.getRangeLow(), confidence, stopLoss, takeProfit),
            confidence, calcPosition(confidence), stopLoss, takeProfit);
    }

    /** 大箱体做空：止损用15分钟前K线中点，止盈目标大箱体下沿 */
    private TradeSignal buildHtfShortSignal(MarketData data, HtfRange htf, double baseConfidence,
                                             boolean b6Active, boolean highAmplitude,
                                             boolean trendInvading, boolean counterTrendSprouting,
                                             boolean counterMomentum, boolean htfTrendActive) {
        if (isInCooldown("down", data.getLastKline().getTimestamp())) return noTrade("HTF short in cooldown");
        if (counterTrendSprouting) return noTrade("Strong uptrend — hard block HTF short");

        double confidence = baseConfidence;
        if (b6Active)        confidence -= 0.10;
        if (highAmplitude)   confidence -= 0.05;
        if (trendInvading)   confidence -= 0.15;
        if (counterMomentum) confidence -= 0.10;
        if ("up".equals(data.getTrendBias())) confidence -= 0.15;
        if (htfTrendActive && "up".equals(htf.getTrendBias())) confidence -= 0.15; // HTF逆势软惩罚
        confidence = Math.max(confidence, 0.30);

        double price = data.getCurrentPrice();
        // 止损：大箱体上沿外 slBuffer 比例
        double slDistance = (htf.getRangeHigh() - price) + htf.getSpan() * p.getMrSlBuffer();
        double maxSlDistance = price * p.getMrMaxSlPct() * 3;
        slDistance = Math.min(slDistance, maxSlDistance);
        slDistance = Math.max(slDistance, price * 0.002);
        double stopLoss = price + slDistance;
        // 止盈：止损距离 × 风险回报比，但不低于大箱体下沿内侧5%
        double rrTakeProfit  = price - slDistance * p.getMrRiskRewardRatio();
        double minTakeProfit = htf.getRangeLow() + htf.getSpan() * 0.05;
        double takeProfit    = Math.max(rrTakeProfit, minTakeProfit);

        lastSignalTime.put("down", data.getLastKline().getTimestamp());
        return new TradeSignal(TradeSignal.Action.SHORT,
            String.format("MR-HTF: rejection at HTF upper (%.0f) — conf=%.2f SL=%.2f TP=%.2f",
                htf.getRangeHigh(), confidence, stopLoss, takeProfit),
            confidence, calcPosition(confidence), stopLoss, takeProfit);
    }

    private boolean isInCooldown(String direction, long currentTs) {
        Long last = lastSignalTime.get(direction);
        if (last == null) return false;
        return (currentTs - last) < p.getMrCooldownMs();
    }

    /**
     * 动态仓位：根据置信度调整仓位大小
     * confidence=0.70 → 140% base, confidence=0.50 → 100% base, confidence=0.30 → 60% base
     * 上限40%防止单笔风险过大
     */
    private double calcPosition(double confidence) {
        double position = p.getMrPosition() * (confidence / 0.50);
        return Math.min(position, 0.40);
    }

    private double rangePosition(MarketData data) {
        double span = data.getRangeHigh() - data.getRangeLow();
        if (span <= 0) return 0.5;
        return (data.getCurrentPrice() - data.getRangeLow()) / span;
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
