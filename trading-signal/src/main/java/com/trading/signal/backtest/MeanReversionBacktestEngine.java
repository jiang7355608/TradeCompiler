package com.trading.signal.backtest;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.HtfRange;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.strategy.MeanReversionStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 均值回归策略专用回测引擎
 * 
 * 设计原则：
 * 1. 只处理均值回归策略，不考虑其他策略
 * 2. 清晰的状态机：IDLE → PROBE → CONFIRMED → IDLE
 * 3. 准确的盈亏计算：试探仓和加仓分开计算
 * 4. 完整的止损监控：模拟1分钟止损检查和12小时超时
 */
public class MeanReversionBacktestEngine {
    
    private static final int WARMUP_BARS = 60;
    
    private final MarketAnalyzer analyzer;
    private final double initialCapital;
    private final int leverage;
    private final long probeTimeoutMs;
    private boolean silent = false;
    
    // 动态箱体缓存（模拟实盘的24小时更新机制）
    private double cachedRangeHigh = 0;
    private double cachedRangeLow = 0;
    private long lastBoxUpdateTime = 0;
    private static final long BOX_UPDATE_INTERVAL_MS = 24 * 3600 * 1000L;  // 24小时
    
    // 持仓状态
    private enum PositionState { IDLE, PROBE, CONFIRMED }
    private PositionState state = PositionState.IDLE;
    private String direction;           // "long" or "short"
    private double probeEntryPrice;
    private long probeEntryTime;
    private double probeSize;
    private double confirmedEntryPrice;
    private long confirmedEntryTime;    // 加仓时间戳
    private double confirmedSize;
    private double stopLoss;
    private double takeProfit;
    
    public MeanReversionBacktestEngine(TradingProperties props) {
        this.analyzer = new MarketAnalyzer(props.getParams());
        this.initialCapital = props.getBacktest().getInitialCapital();
        this.leverage = props.getBacktest().getLeverage();
        this.probeTimeoutMs = props.getParams().getMrProbeTimeoutMs();
    }
    
    public MeanReversionBacktestEngine setSilent(boolean silent) {
        this.silent = silent;
        return this;
    }
    
    public List<KLine> loadCsv(String path) throws Exception {
        List<KLine> klines = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line = br.readLine(); // 跳过表头
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
    
    public BacktestResult run(String csvPath, MeanReversionStrategy strategy) throws Exception {
        return run(loadCsv(csvPath), strategy);
    }
    
    public BacktestResult run(List<KLine> allKlines, MeanReversionStrategy strategy) {
        if (!silent) {
            System.out.printf("%n开始回测: MEAN-REVERSION | 共 %d 根K线%n", allKlines.size());
        }
        
        BacktestResult result = new BacktestResult(initialCapital, leverage);
        resetPosition();
        
        for (int i = WARMUP_BARS; i < allKlines.size(); i++) {
            KLine current = allKlines.get(i);
            
            // 持仓中：检查止损、止盈、超时
            if (state == PositionState.PROBE) {
                if (checkProbeExit(current, result, strategy)) {
                    continue;
                }
            } else if (state == PositionState.CONFIRMED) {
                if (checkConfirmedExit(current, result, strategy)) {
                    continue;
                }
            }
            
            // 生成信号
            List<KLine> window = allKlines.subList(Math.max(0, i - 59), i + 1);
            if (window.size() < 23) continue;
            
            try {
                MarketData marketData = analyzer.analyze(window);
                
                // 动态箱体更新逻辑（模拟实盘的24小时更新机制）
                HtfRange htfRange;
                TradingProperties.StrategyParams params = strategy.getParams();
                
                if (params.getMrRangeHigh() > 0 && params.getMrRangeLow() > 0) {
                    // 手动箱体：直接使用配置值
                    htfRange = new HtfRange(true, params.getMrRangeHigh(), params.getMrRangeLow(), "neutral");
                } else {
                    // 动态箱体：模拟24小时更新机制
                    long currentTime = current.getTimestamp();
                    boolean shouldUpdate = (currentTime - lastBoxUpdateTime) >= BOX_UPDATE_INTERVAL_MS;
                    boolean hasPosition = (state != PositionState.IDLE);
                    
                    // 更新条件：距离上次更新>=24小时 且 无持仓
                    if (shouldUpdate && !hasPosition) {
                        // 聚合4小时K线分析箱体（只用当前时间点之前的数据，避免未来函数）
                        List<KLine> historicalData = allKlines.subList(0, i + 1);
                        List<KLine> klines4h = aggregate4hFromList(historicalData);
                        if (klines4h.size() >= 42) {  // 至少需要42根4h K线（7天）
                            HtfRange newRange = analyzer.analyzeHtf(klines4h);
                            if (newRange.isRange()) {
                                double oldHigh = cachedRangeHigh;
                                double oldLow = cachedRangeLow;
                                cachedRangeHigh = newRange.getRangeHigh();
                                cachedRangeLow = newRange.getRangeLow();
                                lastBoxUpdateTime = currentTime;
                                if (!silent) {
                                    System.out.printf("  [箱体更新] 时间=%d, 箱体=[%.0f - %.0f] (旧箱体=[%.0f - %.0f])%n",
                                        currentTime, cachedRangeLow, cachedRangeHigh, oldLow, oldHigh);
                                }
                            }
                        }
                    } else if (shouldUpdate && hasPosition) {
                        if (!silent) {
                            System.out.printf("  [箱体更新] 跳过（存在持仓）%n");
                        }
                    }
                    
                    // 使用缓存的箱体
                    if (cachedRangeHigh > 0 && cachedRangeLow > 0) {
                        htfRange = new HtfRange(true, cachedRangeHigh, cachedRangeLow, "neutral");
                        // DEBUG: 打印缓存箱体
                        if (!silent && i % 100 == 0) {  // 每100根K线打印一次，避免刷屏
                            System.out.printf("  [DEBUG] 使用缓存箱体: [%.0f - %.0f], 中线=%.0f%n",
                                cachedRangeLow, cachedRangeHigh, (cachedRangeHigh + cachedRangeLow) / 2.0);
                        }
                    } else {
                        // 首次运行，尝试识别箱体（只用当前时间点之前的数据）
                        List<KLine> historicalData = allKlines.subList(0, i + 1);
                        List<KLine> klines4h = aggregate4hFromList(historicalData);
                        htfRange = klines4h.size() >= 42
                            ? analyzer.analyzeHtf(klines4h)
                            : new HtfRange(false, 0, 0, "neutral");
                        
                        if (htfRange.isRange()) {
                            cachedRangeHigh = htfRange.getRangeHigh();
                            cachedRangeLow = htfRange.getRangeLow();
                            lastBoxUpdateTime = currentTime;
                        }
                    }
                }
                
                TradeSignal signal = strategy.generateSignal(marketData, htfRange);
                
                // 处理信号
                if (signal.getAction() != TradeSignal.Action.NO_TRADE) {
                    MeanReversionStrategy.State strategyState = strategy.getState();
                    
                    if (strategyState == MeanReversionStrategy.State.PROBE && state == PositionState.IDLE) {
                        // 试探仓入场
                        openProbe(signal, current, strategy);
                    } else if (strategyState == MeanReversionStrategy.State.CONFIRMED && state == PositionState.PROBE) {
                        // 加仓确认
                        addConfirmed(signal, current, result, strategy);
                    }
                } else {
                    // NO_TRADE 且策略回到 IDLE：试探仓失败
                    if (strategy.getState() == MeanReversionStrategy.State.IDLE && state == PositionState.PROBE) {
                        closeProbe(current, result, strategy, "probe-fail");
                    } else if (state == PositionState.IDLE) {
                        result.recordReject(signal.getReason());
                    }
                }
            } catch (Exception e) {
                // 数据异常，跳过
            }
        }
        
        // 回测结束时如果还有持仓，强制平仓
        if (state != PositionState.IDLE) {
            KLine last = allKlines.get(allKlines.size() - 1);
            if (state == PositionState.PROBE) {
                closeProbe(last, result, null, "end-of-data");
            } else {
                closeConfirmed(last, result, null, "end-of-data");
            }
        }
        
        if (!silent) {
            System.out.printf("  回测完成 | 实际交易: %d 笔%n", result.getTrades().size());
        }
        
        return result;
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 试探仓逻辑
    // ═══════════════════════════════════════════════════════════════════
    
    private void openProbe(TradeSignal signal, KLine current, MeanReversionStrategy strategy) {
        state = PositionState.PROBE;
        direction = signal.getAction().getValue();
        probeEntryPrice = current.getClose();
        probeEntryTime = current.getTimestamp();
        probeSize = signal.getPositionSize() * signal.getConfidence();
        stopLoss = strategy.getStopLoss();
        takeProfit = strategy.getTakeProfit();
    }
    
    private boolean checkProbeExit(KLine current, BacktestResult result, MeanReversionStrategy strategy) {
        // 1. 检查止损
        boolean slHit = "long".equals(direction)
            ? current.getLow() <= stopLoss
            : current.getHigh() >= stopLoss;
        
        if (slHit) {
            closeProbe(current, result, strategy, "probe-sl");
            return true;
        }
        
        // 2. 检查超时
        long holdTime = current.getTimestamp() - probeEntryTime;
        if (holdTime >= probeTimeoutMs) {
            closeProbe(current, result, strategy, "probe-timeout");
            return true;
        }
        
        return false;
    }
    
    private void closeProbe(KLine current, BacktestResult result, MeanReversionStrategy strategy, String reason) {
        double exitPrice = "probe-sl".equals(reason) ? stopLoss : current.getClose();
        double pricePct = "long".equals(direction)
            ? (exitPrice - probeEntryPrice) / probeEntryPrice
            : (probeEntryPrice - exitPrice) / probeEntryPrice;
        double pnlU = result.getCurrentCapital() * probeSize * leverage * pricePct;
        
        result.addTrade(new BacktestResult.Trade(
            probeEntryTime, direction, probeEntryPrice,
            stopLoss, takeProfit, probeSize,
            reason, pricePct * 100, pnlU));
        
        if (strategy != null) {
            strategy.recordTradeResult(pnlU > 0, current.getTimestamp());
            strategy.reset();
        }
        
        resetPosition();
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 加仓逻辑
    // ═══════════════════════════════════════════════════════════════════
    
    private void addConfirmed(TradeSignal signal, KLine current, BacktestResult result, MeanReversionStrategy strategy) {
        // 1. 先结算试探仓盈亏
        double probeExitPrice = current.getClose();
        double probePricePct = "long".equals(direction)
            ? (probeExitPrice - probeEntryPrice) / probeEntryPrice
            : (probeEntryPrice - probeExitPrice) / probeEntryPrice;
        double probePnlU = result.getCurrentCapital() * probeSize * leverage * probePricePct;
        
        result.addTrade(new BacktestResult.Trade(
            probeEntryTime, direction, probeEntryPrice,
            stopLoss, takeProfit, probeSize,
            "probe-confirmed", probePricePct * 100, probePnlU));
        
        // 2. 开确认仓（记录加仓时间）
        state = PositionState.CONFIRMED;
        confirmedEntryPrice = current.getClose();
        confirmedEntryTime = current.getTimestamp();  // 记录加仓时间
        confirmedSize = signal.getPositionSize() * signal.getConfidence();
        stopLoss = signal.getStopLoss();
        takeProfit = signal.getTakeProfit();
        
        // 3. 日志输出（DEBUG：打印signal中的止盈价格）
        if (!silent) {
            System.out.printf("  [加仓] %s @ %.2f, size=%.4f, SL=%.0f, TP=%.0f (signal.TP=%.0f)%n",
                direction.toUpperCase(), confirmedEntryPrice, confirmedSize, stopLoss, takeProfit, signal.getTakeProfit());
        }
    }
    
    private boolean checkConfirmedExit(KLine current, BacktestResult result, MeanReversionStrategy strategy) {
        // 止损优先于止盈
        boolean slHit = "long".equals(direction)
            ? current.getLow() <= stopLoss
            : current.getHigh() >= stopLoss;
        
        boolean tpHit = "long".equals(direction)
            ? current.getHigh() >= takeProfit
            : current.getLow() <= takeProfit;
        
        if (slHit || tpHit) {
            String reason = slHit ? "sl" : "tp";
            closeConfirmed(current, result, strategy, reason);
            return true;
        }
        
        return false;
    }
    
    private void closeConfirmed(KLine current, BacktestResult result, MeanReversionStrategy strategy, String reason) {
        double exitPrice = "sl".equals(reason) ? stopLoss
                         : "tp".equals(reason) ? takeProfit
                         : current.getClose();
        
        double pricePct = "long".equals(direction)
            ? (exitPrice - confirmedEntryPrice) / confirmedEntryPrice
            : (confirmedEntryPrice - exitPrice) / confirmedEntryPrice;
        double pnlU = result.getCurrentCapital() * confirmedSize * leverage * pricePct;
        
        // DEBUG: 打印详细计算过程
        if (!silent) {
            System.out.printf("  [DEBUG] closeConfirmed: %s %s @ %.2f → %.2f%n",
                direction.toUpperCase(), reason, confirmedEntryPrice, exitPrice);
            System.out.printf("    pricePct=%.4f%%, capital=%.2f, size=%.4f, leverage=%d%n",
                pricePct * 100, result.getCurrentCapital(), confirmedSize, leverage);
            System.out.printf("    pnlU = %.2f * %.4f * %d * %.4f = %.2fU%n",
                result.getCurrentCapital(), confirmedSize, leverage, pricePct, pnlU);
        }
        
        // 使用正确的加仓时间戳
        result.addTrade(new BacktestResult.Trade(
            confirmedEntryTime, direction, confirmedEntryPrice,
            stopLoss, takeProfit, confirmedSize,
            reason, pricePct * 100, pnlU));
        
        if (strategy != null) {
            strategy.recordTradeResult(pnlU > 0, current.getTimestamp());
            strategy.reset();
        }
        
        resetPosition();
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════
    
    private void resetPosition() {
        state = PositionState.IDLE;
        direction = null;
        probeEntryPrice = 0;
        probeEntryTime = 0;
        probeSize = 0;
        confirmedEntryPrice = 0;
        confirmedEntryTime = 0;
        confirmedSize = 0;
        stopLoss = 0;
        takeProfit = 0;
    }
    
    /**
     * 从15分钟K线聚合为4小时K线（只用历史数据，避免未来函数）
     * 
     * @param klines15m 15分钟K线列表（只包含当前时间点之前的数据）
     * @return 4小时K线列表
     */
    private List<KLine> aggregate4hFromList(List<KLine> klines15m) {
        int barsPerCandle = 16;  // 4小时 = 16 * 15分钟
        int maxCandles = 100;    // 最多保留100根4h K线
        List<KLine> result = new ArrayList<>();
        
        int totalBars = klines15m.size();
        int start = Math.max(0, totalBars - maxCandles * barsPerCandle);
        
        for (int i = start; i + barsPerCandle <= totalBars; i += barsPerCandle) {
            long ts = klines15m.get(i).getTimestamp();
            double open = klines15m.get(i).getOpen();
            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            double vol = 0;
            double close = klines15m.get(i + barsPerCandle - 1).getClose();
            
            for (int j = i; j < i + barsPerCandle; j++) {
                KLine k = klines15m.get(j);
                high = Math.max(high, k.getHigh());
                low = Math.min(low, k.getLow());
                vol += k.getVolume();
            }
            result.add(new KLine(ts, open, high, low, close, vol));
        }
        return result;
    }
}
