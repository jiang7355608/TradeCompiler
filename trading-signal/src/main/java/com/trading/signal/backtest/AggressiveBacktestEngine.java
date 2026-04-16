package com.trading.signal.backtest;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.strategy.AggressiveStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * AggressiveStrategy v7 专用回测引擎（简化版）
 * 
 * 特点：
 * 1. 突破即入场，无状态机
 * 2. 固定止损止盈（RR=2:1）
 * 3. 可选 Trailing Stop
 */
public class AggressiveBacktestEngine {
    
    private static final int WARMUP_BARS = 60;
    
    private final MarketAnalyzer analyzer;
    private final double initialCapital;
    private final int leverage;
    private final boolean useTrailingStop;
    private boolean silent = false;
    
    // 持仓状态
    private boolean inPosition = false;
    private String direction;
    private double entryPrice;
    private long entryTime;
    private double positionSize;
    private double stopLoss;
    private double takeProfit;
    
    // Trailing Stop
    private double highestSinceEntry;
    private double lowestSinceEntry;
    
    public AggressiveBacktestEngine(TradingProperties props) {
        this(props, false);
    }
    
    public AggressiveBacktestEngine(TradingProperties props, boolean useTrailingStop) {
        this.analyzer = new MarketAnalyzer(props.getParams());
        this.initialCapital = props.getBacktest().getInitialCapital();
        this.leverage = props.getBacktest().getLeverage();
        this.useTrailingStop = useTrailingStop;
    }
    
    public AggressiveBacktestEngine setSilent(boolean silent) {
        this.silent = silent;
        return this;
    }
    
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
    
    public BacktestResult run(String csvPath, AggressiveStrategy strategy) throws Exception {
        return run(loadCsv(csvPath), strategy);
    }
    
    public BacktestResult run(List<KLine> allKlines, AggressiveStrategy strategy) {
        if (!silent) {
            System.out.printf("%n开始回测: AGGRESSIVE v7 (Simple Breakout) | 共 %d 根K线%n", allKlines.size());
        }
        
        BacktestResult result = new BacktestResult(initialCapital, leverage);
        strategy.reset();
        
        // 修复2：回测时设置账户余额为 initialCapital
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
                        openPosition(signal, current, marketData.getAtr());
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
    
    private void openPosition(TradeSignal signal, KLine current, double atr) {
        inPosition = true;
        direction = signal.getAction().getValue();
        entryPrice = current.getClose();
        entryTime = current.getTimestamp();
        positionSize = signal.getPositionSize() * signal.getConfidence();
        stopLoss = signal.getStopLoss();
        takeProfit = signal.getTakeProfit();
        
        highestSinceEntry = current.getHigh();
        lowestSinceEntry = current.getLow();
    }
    
    private boolean checkExit(KLine current, BacktestResult result, AggressiveStrategy strategy) {
        double currentPrice = current.getClose();
        
        // 更新最高/最低价
        highestSinceEntry = Math.max(highestSinceEntry, current.getHigh());
        lowestSinceEntry = Math.min(lowestSinceEntry, current.getLow());
        
        // 检查止损
        if ("long".equals(direction)) {
            if (current.getLow() <= stopLoss) {
                closePosition(current, result, strategy, "stop-loss");
                return true;
            }
            
            // Trailing Stop（可选）
            if (useTrailingStop && highestSinceEntry > entryPrice) {
                double trailingStop = highestSinceEntry - (entryPrice - stopLoss);
                if (current.getLow() <= trailingStop) {
                    closePosition(current, result, strategy, "trailing-stop");
                    return true;
                }
            }
            
            // 检查止盈
            if (current.getHigh() >= takeProfit) {
                closePosition(current, result, strategy, "take-profit");
                return true;
            }
        } else {
            if (current.getHigh() >= stopLoss) {
                closePosition(current, result, strategy, "stop-loss");
                return true;
            }
            
            // Trailing Stop（可选）
            if (useTrailingStop && lowestSinceEntry < entryPrice) {
                double trailingStop = lowestSinceEntry + (stopLoss - entryPrice);
                if (current.getHigh() >= trailingStop) {
                    closePosition(current, result, strategy, "trailing-stop");
                    return true;
                }
            }
            
            // 检查止盈
            if (current.getLow() <= takeProfit) {
                closePosition(current, result, strategy, "take-profit");
                return true;
            }
        }
        
        return false;
    }
    
    private void closePosition(KLine current, BacktestResult result, AggressiveStrategy strategy, String reason) {
        double exitPrice;
        
        if ("stop-loss".equals(reason)) {
            exitPrice = stopLoss;
        } else if ("take-profit".equals(reason)) {
            exitPrice = takeProfit;
        } else if ("trailing-stop".equals(reason)) {
            exitPrice = "long".equals(direction) 
                ? (highestSinceEntry - (entryPrice - stopLoss))
                : (lowestSinceEntry + (stopLoss - entryPrice));
        } else {
            exitPrice = current.getClose();
        }
        
        double pnl;
        if ("long".equals(direction)) {
            pnl = (exitPrice - entryPrice) / entryPrice * positionSize * leverage * result.getInitialCapital();
        } else {
            pnl = (entryPrice - exitPrice) / entryPrice * positionSize * leverage * result.getInitialCapital();
        }
        
        double pnlPct = pnl / result.getInitialCapital();
        
        // 创建 Trade 对象并添加到结果
        BacktestResult.Trade trade = new BacktestResult.Trade(
            entryTime, direction, entryPrice, stopLoss, takeProfit, 
            positionSize, reason, pnlPct, pnl
        );
        result.addTrade(trade);
        
        // 记录交易结果到策略（用于方向暂停逻辑）
        // 修复4：传入K线时间而不是系统时间
        strategy.recordTradeResult(direction, pnl > 0, current.getTimestamp());
        
        if (!silent) {
            System.out.printf("  EXIT %s @ %.2f | PnL: %.2fU (%.2f%%) | Reason: %s%n", 
                direction.toUpperCase(), exitPrice, pnl, pnlPct * 100, reason);
        }
        
        inPosition = false;
    }
}
