package com.trading.signal.backtest;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.BoxRangeDetector;
import com.trading.signal.strategy.MeanReversionStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * MeanReversionStrategy 专用回测引擎
 * 
 * 特点：
 * 1. 箱体震荡交易，低买高卖
 * 2. 固定止损（箱体边沿外）+ 固定止盈（箱体中线）
 * 3. 使用手动箱体配置（回测时需要明确指定箱体上下沿）
 * 
 * 与 AggressiveBacktestEngine 的区别：
 * - AggressiveStrategy：突破交易，使用 Trailing Stop
 * - MeanReversionStrategy：震荡交易，使用固定止盈止损
 * 
 * 使用方式：
 * <pre>
 * MockBoxRangeDetector mockBox = new MockBoxRangeDetector(85000, 80000);
 * MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
 * MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();
 * BacktestResult result = engine.run(csvPath, strategy, 200.0, 20);
 * </pre>
 * 
 * @author Trading System
 * @version 1.0
 */
public class MeanReversionBacktestEngine {
    
    private static final int WARMUP_BARS = 60;
    
    private final MarketAnalyzer analyzer;
    private boolean silent = false;
    
    // 持仓状态
    private boolean inPosition = false;
    private String direction;
    private double entryPrice;
    private long entryTime;
    private double positionSize;
    private double stopLoss;
    private double takeProfit;
    
    public MeanReversionBacktestEngine() {
        this.analyzer = new MarketAnalyzer();
    }
    
    /**
     * Mock BoxRangeDetector 用于回测
     * 
     * 回测时使用手动指定的箱体上下沿，不进行动态识别
     */
    public static class MockBoxRangeDetector extends BoxRangeDetector {
        private final double rangeHigh;
        private final double rangeLow;
        private final boolean valid;
        
        /**
         * 构造函数
         * 
         * @param rangeHigh 箱体上沿（美元）
         * @param rangeLow 箱体下沿（美元）
         */
        public MockBoxRangeDetector(double rangeHigh, double rangeLow) {
            super(null, null, null, null);  // 回测时不需要这些依赖
            this.rangeHigh = rangeHigh;
            this.rangeLow = rangeLow;
            this.valid = (rangeHigh > rangeLow) && (rangeHigh - rangeLow >= 1000) && (rangeHigh - rangeLow <= 12000);
        }
        
        @Override
        public double getCurrentRangeHigh() {
            return rangeHigh;
        }
        
        @Override
        public double getCurrentRangeLow() {
            return rangeLow;
        }
        
        @Override
        public boolean isValid() {
            return valid;
        }
    }
    
    public MeanReversionBacktestEngine setSilent(boolean silent) {
        this.silent = silent;
        return this;
    }
    
    /**
     * 从 CSV 文件加载 K线数据
     * 
     * @param path CSV 文件路径
     * @return K线列表
     * @throws Exception 文件读取异常
     */
    public List<KLine> loadCsv(String path) throws Exception {
        List<KLine> klines = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line = br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 6) continue;
                klines.add(new KLine(
                    Long.parseLong(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    Double.parseDouble(parts[4].trim()),
                    Double.parseDouble(parts[5].trim())
                ));
            }
        }
        if (!silent) {
            System.out.printf("  已加载 %d 根K线 from %s%n", klines.size(), path);
        }
        return klines;
    }
    
    /**
     * 运行回测（从 CSV 文件）
     * 
     * @param csvPath CSV 文件路径
     * @param strategy 均值回归策略
     * @param initialCapital 初始资金
     * @param leverage 杠杆倍数
     * @return 回测结果
     * @throws Exception 文件读取异常
     */
    public BacktestResult run(String csvPath, MeanReversionStrategy strategy, double initialCapital, int leverage) throws Exception {
        return run(loadCsv(csvPath), strategy, initialCapital, leverage);
    }
    
    /**
     * 运行回测（从 K线列表）
     * 
     * @param allKlines K线列表
     * @param strategy 均值回归策略（必须使用 MockBoxRangeDetector 构造）
     * @param initialCapital 初始资金
     * @param leverage 杠杆倍数
     * @return 回测结果
     */
    public BacktestResult run(List<KLine> allKlines, MeanReversionStrategy strategy, double initialCapital, int leverage) {
        if (!silent) {
            System.out.printf("%n开始回测: MEAN REVERSION (Box Range Trading) | 共 %d 根K线%n", allKlines.size());
        }
        
        BacktestResult result = new BacktestResult(initialCapital, leverage);
        strategy.reset();
        strategy.setAccountBalance(initialCapital);
        
        inPosition = false;
        
        for (int i = WARMUP_BARS; i < allKlines.size(); i++) {
            KLine current = allKlines.get(i);
            
            // 持仓中：检查止损、止盈
            if (inPosition) {
                if (checkExit(current, result, strategy)) {
                    continue;
                }
            }
            
            // 无持仓：检查入场信号
            if (!inPosition) {
                List<KLine> window = allKlines.subList(Math.max(0, i - 59), i + 1);
                if (window.size() < 23) continue;
                
                try {
                    MarketData marketData = analyzer.analyze(window);
                    TradeSignal signal = strategy.generateSignal(marketData);
                    
                    if (signal.getAction() == TradeSignal.Action.LONG || signal.getAction() == TradeSignal.Action.SHORT) {
                        openPosition(signal, current);
                        if (!silent) {
                            System.out.printf("  [Bar %d] ENTRY %s @ %.2f | %s%n", 
                                i, signal.getAction(), current.getClose(), signal.getReason());
                        }
                    } else {
                        result.recordReject(signal.getReason());
                    }
                } catch (Exception e) {
                    // 数据异常，跳过
                }
            }
        }
        
        // 回测结束时如果还有持仓，强制平仓
        if (inPosition) {
            KLine last = allKlines.get(allKlines.size() - 1);
            closePosition(last, result, strategy, "end-of-data");
        }
        
        if (!silent) {
            System.out.printf("  回测完成 | 实际交易: %d 笔%n", result.getTrades().size());
            
            if (result.getTrades().isEmpty()) {
                System.out.println("\n  拒绝原因统计 (Top 10):");
                result.getRejectReasons().entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(10)
                    .forEach(e -> System.out.printf("    %5d | %s%n", e.getValue(), e.getKey()));
            }
        }
        
        return result;
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 持仓管理
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * 开仓
     * 
     * @param signal 交易信号
     * @param current 当前K线
     */
    private void openPosition(TradeSignal signal, KLine current) {
        inPosition = true;
        direction = signal.getAction().getValue();
        entryPrice = current.getClose();
        entryTime = current.getTimestamp();
        positionSize = signal.getPositionSize() * signal.getConfidence();
        stopLoss = signal.getStopLoss();
        takeProfit = signal.getTakeProfit();
    }
    
    /**
     * 检查是否触发退出条件
     * 
     * @param current 当前K线
     * @param result 回测结果
     * @param strategy 策略
     * @return true 如果已退出
     */
    private boolean checkExit(KLine current, BacktestResult result, MeanReversionStrategy strategy) {
        double currentPrice = current.getClose();
        
        if ("long".equals(direction)) {
            // 做多：检查止盈和止损
            if (current.getHigh() >= takeProfit) {
                closePosition(current, result, strategy, "tp");
                return true;
            }
            if (current.getLow() <= stopLoss) {
                closePosition(current, result, strategy, "sl");
                return true;
            }
        } else {
            // 做空：检查止盈和止损
            if (current.getLow() <= takeProfit) {
                closePosition(current, result, strategy, "tp");
                return true;
            }
            if (current.getHigh() >= stopLoss) {
                closePosition(current, result, strategy, "sl");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 平仓
     * 
     * @param current 当前K线
     * @param result 回测结果
     * @param strategy 策略
     * @param reason 平仓原因
     */
    private void closePosition(KLine current, BacktestResult result, MeanReversionStrategy strategy, String reason) {
        double exitPrice;
        
        // 根据平仓原因确定退出价格
        if ("tp".equals(reason)) {
            exitPrice = takeProfit;
        } else if ("sl".equals(reason)) {
            exitPrice = stopLoss;
        } else {
            exitPrice = current.getClose();
        }
        
        // 计算盈亏
        double pnl;
        if ("long".equals(direction)) {
            pnl = (exitPrice - entryPrice) / entryPrice * positionSize * result.getLeverage() * result.getInitialCapital();
        } else {
            pnl = (entryPrice - exitPrice) / entryPrice * positionSize * result.getLeverage() * result.getInitialCapital();
        }
        
        double pnlPct = pnl / result.getInitialCapital();
        
        // 创建 Trade 对象并添加到结果
        BacktestResult.Trade trade = new BacktestResult.Trade(
            entryTime, direction, entryPrice, stopLoss, takeProfit,
            positionSize, reason, pnlPct, pnl
        );
        result.addTrade(trade);
        
        // 记录交易结果到策略（用于方向暂停逻辑）
        strategy.recordTradeResult(direction, pnl > 0, current.getTimestamp());
        
        if (!silent) {
            System.out.printf("  EXIT %s @ %.2f | PnL: %.2fU (%.2f%%) | Reason: %s%n", 
                direction.toUpperCase(), exitPrice, pnl, pnlPct * 100, reason);
        }
        
        inPosition = false;
    }
}
