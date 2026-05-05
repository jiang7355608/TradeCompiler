package com.trading.signal.analyzer;

import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 市场技术指标计算器
 * 
 * 负责计算交易策略所需的技术指标，当前只计算 ATR（平均真实波幅）。
 * 
 * ATR 用途：
 * - 止损距离计算：SL = entryPrice ± N×ATR
 * - 止盈距离计算：TP = entryPrice ± M×ATR  
 * - 突破动量确认：突破幅度 > 0.3×ATR
 * - 实体过滤：K线实体 > 0.8×ATR
 * 
 * 设计原则：
 * - 专注于技术指标计算，不做市场结构分析
 * - 算法标准化，确保回测和实盘一致
 * - 无状态设计，线程安全
 * 
 * @author Trading System
 * @version 2.0 (Simplified to ATR calculation only)
 */
@Service
public class MarketAnalyzer {
    
    private static final Logger log = LoggerFactory.getLogger(MarketAnalyzer.class);
    
    /**
     * 分析市场数据，计算技术指标
     * 
     * 当前只计算 ATR 指标，返回包含 K线和 ATR 的 MarketData 对象。
     * 
     * @param klines K线列表，至少需要 20 根用于 ATR 计算
     * @return 市场数据对象，包含 K线和 ATR
     * @throws IllegalArgumentException 如果 K线数量不足
     */
    public MarketData analyze(List<KLine> klines) {
        if (klines == null || klines.size() < 20) {
            throw new IllegalArgumentException(
                String.format("K线数量不足，至少需要 20 根，当前: %d", 
                    klines == null ? 0 : klines.size()));
        }
        
        // 计算 ATR（14周期）
        double atr = calculateAtr(klines, 14);
        
        log.debug("技术指标计算完成: ATR={:.2f}, K线数量={}", atr, klines.size());
        
        return new MarketData(klines, atr);
    }
    
    /**
     * 计算 ATR（平均真实波幅）
     * 
     * 算法：
     * 1. 计算每根K线的真实波幅（True Range）
     *    TR = max(high - low, abs(high - prevClose), abs(low - prevClose))
     * 2. 对 TR 序列计算指数移动平均（EMA）
     *    ATR = EMA(TR, period)
     * 
     * @param klines K线列表
     * @param period ATR 周期，通常使用 14
     * @return ATR 值
     */
    private double calculateAtr(List<KLine> klines, int period) {
        if (klines.size() < period + 1) {
            throw new IllegalArgumentException(
                String.format("计算 ATR 需要至少 %d 根K线，当前: %d", 
                    period + 1, klines.size()));
        }
        
        // 计算 True Range 序列
        double[] trValues = new double[klines.size() - 1];
        
        for (int i = 1; i < klines.size(); i++) {
            KLine current = klines.get(i);
            KLine previous = klines.get(i - 1);
            
            double tr1 = current.getHigh() - current.getLow();
            double tr2 = Math.abs(current.getHigh() - previous.getClose());
            double tr3 = Math.abs(current.getLow() - previous.getClose());
            
            trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
        }
        
        // 计算 ATR（TR 的 EMA）
        return calculateEma(trValues, period);
    }
    
    /**
     * 计算指数移动平均（EMA）
     * 
     * 算法：
     * - 平滑因子：α = 2 / (period + 1)
     * - 初始值：前 period 个数据的简单平均
     * - 递推公式：EMA[i] = α × value[i] + (1 - α) × EMA[i-1]
     * 
     * @param values 数值序列
     * @param period EMA 周期
     * @return EMA 值
     */
    private double calculateEma(double[] values, int period) {
        if (values.length < period) {
            throw new IllegalArgumentException("数据不足以计算 EMA");
        }
        
        // 计算初始 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += values[i];
        }
        double ema = sum / period;
        
        // 计算 EMA
        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < values.length; i++) {
            ema = (values[i] * multiplier) + (ema * (1 - multiplier));
        }
        
        return ema;
    }
}