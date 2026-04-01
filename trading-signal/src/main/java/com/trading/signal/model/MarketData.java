package com.trading.signal.model;

/**
 * 市场结构分析结果，由 MarketAnalyzer 生成，传入策略进行决策
 *
 * 新增字段（本次优化）：
 *
 *   trendBias        - 趋势方向偏向："up" / "down" / "neutral"
 *                      基于 EMA5 vs EMA20 判断短期趋势方向
 *                      用于过滤逆势信号（局限性1修复）
 *
 *   strongContinuation - 强延续确认：连续2根K线收盘递增（做多）或递减（做空）
 *                        比单根 continuation 更严格，减少假延续（局限性2修复）
 *
 *   breakout14       - 基于14根窗口的突破方向："up" / "down" / "none"
 *                      只有同时突破6根和14根区间高低点才算有效突破（局限性3修复）
 *
 *   rangeHigh14 / rangeLow14 - 14根窗口的区间高低点，供策略日志使用
 */
public class MarketData {

    private final boolean isRange;
    private final boolean volumeSpike;
    private final String  breakout;             // 基于6根窗口
    private final boolean continuation;         // 单根延续（lastClose > prevClose）
    private final double  currentPrice;
    private final double  rangeHigh;            // 6根窗口最高价
    private final double  rangeLow;             // 6根窗口最低价
    private final double  lastKlineAmplitude;
    private final KLine   lastKline;
    private final double  avgVolume20;

    // ── 本次新增字段 ──────────────────────────────────────────────────────
    private final String  trendBias;            // "up" / "down" / "neutral"（EMA5 vs EMA20）
    private final boolean strongContinuation;   // 连续2根K线方向一致
    private final String  breakout14;           // 基于14根窗口的突破方向
    private final double  rangeHigh14;          // 14根窗口最高价
    private final double  rangeLow14;           // 14根窗口最低价
    private final KLine   prevKline;            // 前一根K线（用于动态止损）
    private final KLine   prev2Kline;           // 前两根K线（用于均值回归策略反转确认）
    private final double  ema5;                 // EMA短期值（用于趋势强度计算）
    private final double  ema20;                // EMA长期值
    private final double  atr;                  // ATR（平均真实波幅，14周期）
    private final double  ema50;                // EMA50（中期趋势）
    private final String  trendEstablished;     // 趋势成立状态："up"/"down"/"none"

    public MarketData(boolean isRange, boolean volumeSpike, String breakout,
                      boolean continuation, double currentPrice,
                      double rangeHigh, double rangeLow, double lastKlineAmplitude,
                      KLine lastKline, double avgVolume20,
                      String trendBias, boolean strongContinuation,
                      String breakout14, double rangeHigh14, double rangeLow14,
                      KLine prevKline, KLine prev2Kline,
                      double ema5, double ema20, double atr, double ema50,
                      String trendEstablished) {
        this.isRange             = isRange;
        this.volumeSpike         = volumeSpike;
        this.breakout            = breakout;
        this.continuation        = continuation;
        this.currentPrice        = currentPrice;
        this.rangeHigh           = rangeHigh;
        this.rangeLow            = rangeLow;
        this.lastKlineAmplitude  = lastKlineAmplitude;
        this.lastKline           = lastKline;
        this.avgVolume20         = avgVolume20;
        this.trendBias           = trendBias;
        this.strongContinuation  = strongContinuation;
        this.breakout14          = breakout14;
        this.rangeHigh14         = rangeHigh14;
        this.rangeLow14          = rangeLow14;
        this.prevKline           = prevKline;
        this.prev2Kline          = prev2Kline;
        this.ema5                = ema5;
        this.ema20               = ema20;
        this.atr                 = atr;
        this.ema50               = ema50;
        this.trendEstablished    = trendEstablished;
    }

    public boolean isRange()                { return isRange; }
    public boolean isVolumeSpike()          { return volumeSpike; }
    public String  getBreakout()            { return breakout; }
    public boolean isContinuation()         { return continuation; }
    public double  getCurrentPrice()        { return currentPrice; }
    public double  getRangeHigh()           { return rangeHigh; }
    public double  getRangeLow()            { return rangeLow; }
    public double  getLastKlineAmplitude()  { return lastKlineAmplitude; }
    public KLine   getLastKline()           { return lastKline; }
    public double  getAvgVolume20()         { return avgVolume20; }
    public String  getTrendBias()           { return trendBias; }
    public boolean isStrongContinuation()   { return strongContinuation; }
    public String  getBreakout14()          { return breakout14; }
    public double  getRangeHigh14()         { return rangeHigh14; }
    public double  getRangeLow14()          { return rangeLow14; }
    public KLine   getPrevKline()           { return prevKline; }
    public KLine   getPrev2Kline()          { return prev2Kline; }
    public double  getEma5()                { return ema5; }
    public double  getEma20()               { return ema20; }
    public double  getAtr()                 { return atr; }
    public double  getEma50()               { return ema50; }
    public String  getTrendEstablished()    { return trendEstablished; }

    @Override
    public String toString() {
        return String.format(
            "MarketData{isRange=%b, volumeSpike=%b, breakout='%s', breakout14='%s', " +
            "continuation=%b, strongCont=%b, trendBias='%s', price=%.2f, avgVol20=%.2f}",
            isRange, volumeSpike, breakout, breakout14,
            continuation, strongContinuation, trendBias, currentPrice, avgVolume20);
    }
}
