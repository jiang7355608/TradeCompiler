package com.trading.signal.model;

/**
 * 高时间框架（Higher Time Frame）箱体数据
 * 由4小时K线分析得出，供均值回归策略做大箱体判断
 */
public class HtfRange {

    private final boolean isRange;
    private final double  rangeHigh;
    private final double  rangeLow;
    private final String  trendBias;  // 大周期趋势方向

    public HtfRange(boolean isRange, double rangeHigh, double rangeLow, String trendBias) {
        this.isRange   = isRange;
        this.rangeHigh = rangeHigh;
        this.rangeLow  = rangeLow;
        this.trendBias = trendBias;
    }

    public boolean isRange()      { return isRange; }
    public double  getRangeHigh() { return rangeHigh; }
    public double  getRangeLow()  { return rangeLow; }
    public String  getTrendBias() { return trendBias; }
    public double  getSpan()      { return rangeHigh - rangeLow; }
}
