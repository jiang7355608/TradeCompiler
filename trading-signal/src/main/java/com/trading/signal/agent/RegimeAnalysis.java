package com.trading.signal.agent;

/**
 * 市场状态分析结果。
 * 
 * 包含技术指标计算结果和市场状态分类。
 * 由 MarketRegimeAnalyzer 计算后传递给 MarketRegimeAgent 进行策略决策。
 * 
 * 技术指标说明：
 * - trendStrength: 趋势强度，基于 EMA20 与 EMA50 的偏离幅度（0.0-1.0）
 * - volatilityRatio: 波动率，ATR / 当前价格，反映市场噪声水平
 * - emaSlope: EMA斜率，(EMA20 - EMA50) / EMA50，正值表示上升趋势
 * - atrRatio: ATR扩张比，当前ATR / 近期平均ATR，>1.2表示波动扩张
 * - breakoutSuccessRate: 突破有效率，近30根K线中有效突破的比例
 * - reasoning: 分析依据说明，用于日志和邮件展示
 * - analysisTimeMs: 分析时间戳（Unix毫秒）
 */
public record RegimeAnalysis(

        /** 判断出的市场状态 */
        MarketRegime regime,

        /** 趋势强度：EMA20 vs EMA50 偏离幅度（0.0-1.0归一化） */
        double trendStrength,

        /** 波动率：ATR / 当前价格 */
        double volatilityRatio,

        /** EMA斜率：(EMA20 - EMA50) / EMA50 */
        double emaSlope,

        /** ATR扩张比：当前ATR / 近期平均ATR */
        double atrRatio,

        /** 过去N根K线的突破有效率（0.0-1.0） */
        double breakoutSuccessRate,

        /** 分析依据说明（供日志和邮件展示） */
        String reasoning,

        /** 分析执行时间（Unix ms） */
        long analysisTimeMs

) {

    /**
     * 工厂方法：构建 UNKNOWN 结果（数据不足或分析异常）
     *
     * @param reason 原因说明
     */
    public static RegimeAnalysis unknown(String reason) {
        return new RegimeAnalysis(
                MarketRegime.UNKNOWN,
                0.0, 0.0, 0.0, 0.0, 0.0,
                reason,
                System.currentTimeMillis()
        );
    }

    /**
     * 置信度评估（供 Agent 决策参考）
     *
     * 置信度 = 指标一致性程度，越高越可信：
     * - TRENDING：trendStrength 和 emaSlope 方向一致
     * - RANGING ：volatilityRatio 低且 trendStrength 低
     * - CHOPPY  ：volatilityRatio 高
     *
     * @return 置信度 0.0-1.0
     */
    public double confidence() {
        return switch (regime) {
            case TRENDING -> Math.min(trendStrength * 40, 1.0);
            case RANGING  -> Math.min((0.025 - volatilityRatio) * 80, 1.0);
            case CHOPPY   -> Math.min((volatilityRatio - 0.025) * 40, 1.0);
            case UNKNOWN  -> 0.0;
        };
    }

    @Override
    public String toString() {
        return String.format(
                "RegimeAnalysis{regime=%s, confidence=%.2f, trendStrength=%.4f, " +
                "volatility=%.4f, emaSlope=%.4f, atrRatio=%.2f, breakoutRate=%.2f, reason='%s'}",
                regime, confidence(), trendStrength, volatilityRatio, emaSlope,
                atrRatio, breakoutSuccessRate, reasoning
        );
    }
}
