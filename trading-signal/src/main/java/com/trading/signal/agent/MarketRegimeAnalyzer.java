package com.trading.signal.agent;

import com.trading.signal.model.KLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 市场状态分析器。
 * 
 * 基于技术指标（EMA、ATR、突破情况）将市场分为四种状态：
 * - TRENDING（趋势行情）：EMA有明显方向性偏离，ATR扩张，推荐使用 AggressiveStrategy
 * - RANGING（震荡行情）：EMA几乎持平，波动率低，推荐使用 MeanReversionStrategy
 * - CHOPPY（噪声行情）：高波动但无方向，两种策略均不适合，保持当前策略
 * - UNKNOWN（数据不足）：数据不够或分析异常，保持当前策略
 * 
 * 使用独立于现有 MarketAnalyzer（后者只算 ATR 供策略使用）的算法，
 * 基于更长周期的 K 线数据进行分析。
 * 
 * 技术指标计算：
 * 1. EMA 斜率：(EMA20 - EMA50) / EMA50，反映趋势方向和强度
 * 2. 波动率比：ATR14 / 当前价格，反映市场噪声水平
 * 3. ATR 扩张比：当前ATR / 近期平均ATR，反映波动扩张或收缩
 * 4. 突破有效率：近30根K线中有效突破所占比例，反映假突破情况
 * 
 * 设计原则：
 * - 无状态设计（线程安全）
 * - 不依赖任何 Spring Bean（仅做计算）
 * - 入参统一为 List<KLine>，与现有 MarketAnalyzer 一致
 * - 最少需要 60 根 K 线（50周期EMA基础 + 分析余量）
 */
@Service
public class MarketRegimeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeAnalyzer.class);

    // ── 状态分类阈值 ─────────────────────────────────────────────────────

    /** EMA斜率判定为"有趋势"的门槛（EMA20偏离EMA50超过1.5%） */
    private static final double TREND_EMA_SLOPE_THRESHOLD   = 0.015;

    /** EMA斜率判定为"无趋势/震荡"的门槛（偏离小于0.8%） */
    private static final double RANGING_EMA_SLOPE_THRESHOLD = 0.008;

    /** 波动率低于此值视为震荡行情（ATR/price < 1.0%） */
    private static final double RANGING_VOLATILITY_MAX      = 0.020;

    /** 波动率高于此值视为噪声行情（ATR/price > 2.5%） */
    private static final double CHOPPY_VOLATILITY_MIN       = 0.025;

    /** ATR扩张比高于此值视为趋势性扩张 */
    private static final double TREND_ATR_RATIO_MIN         = 1.0;

    /** 最少需要的 K 线数量 */
    private static final int MIN_KLINES                     = 60;

    // ═══════════════════════════════════════════════════════════════════
    // 公开分析入口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 对给定 K 线列表进行市场状态分析
     *
     * 建议传入至少 100 根 K 线以提高分析稳定性。
     * 本分析器使用 15m 或 4H K 线均可，粒度越大噪声越少。
     *
     * @param klines K 线列表（时序从旧到新，最后一根为当前 K 线）
     * @return 分析结果，包含市场状态和各指标值
     */
    public RegimeAnalysis analyze(List<KLine> klines) {
        if (klines == null || klines.size() < MIN_KLINES) {
            int size = klines == null ? 0 : klines.size();
            String reason = String.format("K线数量不足（%d < %d），无法判断市场状态", size, MIN_KLINES);
            log.warn("[MarketRegimeAnalyzer] {}", reason);
            return RegimeAnalysis.unknown(reason);
        }

        try {
            // ── 1. 计算各指标 ────────────────────────────────────────
            double ema20        = computeEma(klines, 20);
            double ema50        = computeEma(klines, 50);
            double emaSlope     = (ema20 - ema50) / ema50;               // 正=多头，负=空头
            double trendStrength = Math.abs(emaSlope);                    // 趋势强度（无方向）

            double atr14         = computeAtr(klines, 14);
            double currentPrice  = klines.get(klines.size() - 1).getClose();
            double volatilityRatio = atr14 / currentPrice;               // ATR/price

            double atr50mean    = computeAtrMean(klines, 50);
            double atrRatio     = atr50mean > 0 ? atr14 / atr50mean : 1.0; // 扩张比

            double breakoutSuccessRate = computeBreakoutSuccessRate(klines, 30);

            // ── 2. 状态分类 ──────────────────────────────────────────
            MarketRegime regime = classifyRegime(trendStrength, emaSlope, volatilityRatio, atrRatio);

            // ── 3. 构建说明文字 ──────────────────────────────────────
            String reasoning = buildReasoning(regime, trendStrength, emaSlope,
                    volatilityRatio, atrRatio, breakoutSuccessRate);

            log.info("[MarketRegimeAnalyzer] 分析完成: regime={} emaSlope={} volatility={} atrRatio={} breakoutRate={}",
                    regime,
                    String.format("%.4f", emaSlope),
                    String.format("%.4f", volatilityRatio),
                    String.format("%.2f", atrRatio),
                    String.format("%.2f", breakoutSuccessRate));

            return new RegimeAnalysis(
                    regime, trendStrength, volatilityRatio, emaSlope,
                    atrRatio, breakoutSuccessRate, reasoning, System.currentTimeMillis()
            );

        } catch (Exception e) {
            String reason = "分析异常: " + e.getMessage();
            log.error("[MarketRegimeAnalyzer] {}", reason, e);
            return RegimeAnalysis.unknown(reason);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 状态分类逻辑
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 根据各指标值分类市场状态
     *
     * 分类优先级：
     * 1. CHOPPY  - 高噪声行情（最先排除，避免在噪声中切换）
     * 2. TRENDING - 明确趋势行情
     * 3. RANGING  - 低波动震荡行情
     * 4. UNKNOWN  - 模糊状态（保持当前策略）
     */
    private MarketRegime classifyRegime(double trendStrength, double emaSlope,
                                         double volatilityRatio, double atrRatio) {
        // CHOPPY：高波动但无方向性（EMA 乱序）
        if (volatilityRatio > CHOPPY_VOLATILITY_MIN && trendStrength < TREND_EMA_SLOPE_THRESHOLD) {
            return MarketRegime.CHOPPY;
        }

        // TRENDING：EMA 明显分层 + 波动扩张
        if (trendStrength > TREND_EMA_SLOPE_THRESHOLD && atrRatio > TREND_ATR_RATIO_MIN) {
            return MarketRegime.TRENDING;
        }

        // RANGING：EMA 几乎平行 + 波动收窄
        if (trendStrength < RANGING_EMA_SLOPE_THRESHOLD && volatilityRatio < RANGING_VOLATILITY_MAX) {
            return MarketRegime.RANGING;
        }

        // 其余情况：状态模糊，保持当前策略
        return MarketRegime.UNKNOWN;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 指标计算（无状态，线程安全）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 计算 EMA（指数移动平均）
     *
     * 初始值使用前 period 根 K 线的 SMA（简单均值）。
     * 之后使用 α = 2/(period+1) 递推。
     *
     * @param klines K 线列表（使用收盘价）
     * @param period EMA 周期
     * @return 最新的 EMA 值
     */
    double computeEma(List<KLine> klines, int period) {
        if (klines.size() < period) {
            throw new IllegalArgumentException(
                    String.format("计算 EMA%d 需要至少 %d 根K线，当前: %d", period, period, klines.size()));
        }

        // SMA 初始值
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += klines.get(i).getClose();
        }
        double ema = sum / period;

        // EMA 递推
        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < klines.size(); i++) {
            ema = klines.get(i).getClose() * multiplier + ema * (1 - multiplier);
        }

        return ema;
    }

    /**
     * 计算 ATR14（平均真实波幅，14周期）
     *
     * TR = max(high-low, |high-prevClose|, |low-prevClose|)
     * ATR = EMA(TR, 14)
     *
     * @param klines K 线列表（需要至少 15 根）
     * @param period ATR 周期
     * @return ATR 值
     */
    double computeAtr(List<KLine> klines, int period) {
        if (klines.size() < period + 1) {
            throw new IllegalArgumentException(
                    String.format("计算 ATR%d 需要至少 %d 根K线", period, period + 1));
        }

        double[] trValues = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);
            double tr1 = cur.getHigh() - cur.getLow();
            double tr2 = Math.abs(cur.getHigh() - prev.getClose());
            double tr3 = Math.abs(cur.getLow()  - prev.getClose());
            trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
        }

        // TR 序列的 SMA 初始化
        double sum = 0;
        for (int i = 0; i < period; i++) sum += trValues[i];
        double atr = sum / period;

        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < trValues.length; i++) {
            atr = trValues[i] * multiplier + atr * (1 - multiplier);
        }

        return atr;
    }

    /**
     * 计算历史 ATR 均值（用于计算 ATR 扩张比）
     *
     * 对最后 lookback 根 K 线，每根计算其单周期 TR，然后取均值。
     * 这是对 ATR 扩张/收缩判断的简化近似。
     *
     * @param klines   K 线列表
     * @param lookback 回顾窗口
     * @return 近期 TR 均值
     */
    private double computeAtrMean(List<KLine> klines, int lookback) {
        int start = Math.max(1, klines.size() - lookback);
        double trSum = 0;
        int count = 0;
        for (int i = start; i < klines.size(); i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);
            double tr  = Math.max(cur.getHigh() - cur.getLow(),
                         Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                  Math.abs(cur.getLow()  - prev.getClose())));
            trSum += tr;
            count++;
        }
        return count > 0 ? trSum / count : 0;
    }

    /**
     * 计算突破有效率近似值
     *
     * 定义：在近 lookback 根 K 线中，
     *   "有效突破 K 线" = K 线收盘突破前一根 K 线的高低点，且 K 线实体 > 0.5×(high-low)
     *   突破有效率 = 有效突破数 / (总突破数 + 1)（+1 避免除零）
     *
     * 近似含义：
     *   高值（>0.6）= 突破普遍有效 → 趋势行情
     *   低值（<0.3）= 突破普遍被回测 → 震荡行情
     *
     * @param klines   K 线列表
     * @param lookback 分析窗口（近 N 根 K 线）
     * @return 突破有效率 0.0-1.0
     */
    private double computeBreakoutSuccessRate(List<KLine> klines, int lookback) {
        int start = Math.max(1, klines.size() - lookback);
        int totalBreakouts = 0;
        int validBreakouts = 0;

        for (int i = start; i < klines.size(); i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);

            boolean breakoutUp   = cur.getClose() > prev.getHigh();
            boolean breakoutDown = cur.getClose() < prev.getLow();

            if (breakoutUp || breakoutDown) {
                totalBreakouts++;
                double bodySize = Math.abs(cur.getClose() - cur.getOpen());
                double range    = cur.getHigh() - cur.getLow();
                // 实体 > 50% 的 K 线范围，认为是有效突破（非影线主导）
                if (range > 0 && bodySize / range > 0.5) {
                    validBreakouts++;
                }
            }
        }

        return (double) validBreakouts / (totalBreakouts + 1);
    }

    /**
     * 构建人类可读的状态说明文字
     */
    private String buildReasoning(MarketRegime regime, double trendStrength, double emaSlope,
                                   double volatilityRatio, double atrRatio, double breakoutSuccessRate) {
        String emaDirection = emaSlope > 0 ? "向上" : "向下";
        return String.format(
                "判定=%s | EMA斜率=%.4f(%s) 趋势强度=%.4f | 波动率=%.4f | ATR扩张比=%.2f | 突破有效率=%.2f",
                regime, emaSlope, emaDirection, trendStrength, volatilityRatio, atrRatio, breakoutSuccessRate
        );
    }
}
