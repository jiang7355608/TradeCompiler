package com.trading.signal.model;

import java.util.List;

/**
 * 市场数据容器
 * 
 * 包含策略分析所需的核心数据：
 * - K线列表：用于策略计算区间高低点
 * - ATR指标：用于止损止盈和动量确认计算
 * 
 * 设计原则：
 * - 简洁明了，只包含必要数据
 * - 不包含市场结构分析结果（横盘、突破等）
 * - 策略自己计算所需的技术指标
 * 
 * @author Trading System
 * @version 2.0 (Simplified for clean architecture)
 */
public class MarketData {
    
    private final List<KLine> klines;      // K线列表（时间正序，旧→新）
    private final double atr;              // ATR指标（14周期平均真实波幅）
    
    /**
     * 构造市场数据
     * 
     * @param klines K线列表，必须按时间正序排列（旧→新）
     * @param atr ATR指标值，用于止损止盈计算
     */
    public MarketData(List<KLine> klines, double atr) {
        if (klines == null || klines.isEmpty()) {
            throw new IllegalArgumentException("K线列表不能为空");
        }
        if (atr <= 0) {
            throw new IllegalArgumentException("ATR必须大于0");
        }
        
        this.klines = klines;
        this.atr = atr;
    }
    
    /**
     * 获取K线列表
     * 
     * @return K线列表（时间正序）
     */
    public List<KLine> getKlines() {
        return klines;
    }
    
    /**
     * 获取最新K线（当前K线）
     * 
     * @return 最新的K线数据
     */
    public KLine getLastKline() {
        return klines.get(klines.size() - 1);
    }
    
    /**
     * 获取当前价格（最新K线收盘价）
     * 
     * @return 当前价格
     */
    public double getCurrentPrice() {
        return getLastKline().getClose();
    }
    
    /**
     * 获取ATR指标
     * 
     * ATR（Average True Range）平均真实波幅，用于：
     * - 止损距离计算
     * - 止盈距离计算
     * - 突破动量确认
     * 
     * @return ATR值
     */
    public double getAtr() {
        return atr;
    }
    
    /**
     * 获取K线数量
     * 
     * @return K线总数
     */
    public int getKlineCount() {
        return klines.size();
    }
    
    @Override
    public String toString() {
        return String.format("MarketData{klines=%d, currentPrice=%.2f, atr=%.2f}", 
            klines.size(), getCurrentPrice(), atr);
    }
}