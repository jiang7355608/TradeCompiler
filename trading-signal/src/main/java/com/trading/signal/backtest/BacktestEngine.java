package com.trading.signal.backtest;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.HtfRange;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.MeanReversionStrategy;
import com.trading.signal.strategy.Strategy;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测引擎
 *
 * 工作原理：
 *   1. 从 CSV 读取历史K线
 *   2. 用滑动窗口逐根喂给 MarketAnalyzer + Strategy
 *   3. 收到有效信号后，用后续K线模拟止损/止盈触发
 *   4. 统计所有交易结果
 *
 * 回测假设（简化）：
 *   - 以信号触发时的收盘价作为入场价（实际会有滑点，偏保守）
 *   - 后续每根K线检查 high/low 是否触及止损或止盈
 *   - 止损优先于止盈（同一根K线同时触及时，认为先止损）
 *   - 不考虑手续费（OKX Taker 费率约 0.05%，可在结果中手动扣除）
 *   - 不考虑资金费率
 *   - 同一时间只持有一个方向的仓位（有持仓时不开新仓）
 *
 * 窗口大小：
 *   MarketAnalyzer 需要至少 23 根K线（VOL_WINDOW=20 + 3）
 *   回测从第 50 根开始，给 EMA 足够的预热期
 */
public class BacktestEngine {

    private static final int WARMUP_BARS = 50;

    private final MarketAnalyzer analyzer;
    private final double         initialCapital;
    private final int            leverage;
    private boolean              silent = false;

    /** 设置静默模式（优化器用，不打印每次回测的详情） */
    public BacktestEngine setSilent(boolean silent) {
        this.silent = silent;
        return this;
    }

    /** Spring 环境使用（从配置读参数） */
    public BacktestEngine(TradingProperties props) {
        this.analyzer       = new MarketAnalyzer(props.getParams());
        this.initialCapital = props.getBacktest().getInitialCapital();
        this.leverage       = props.getBacktest().getLeverage();
    }

    /** 独立运行使用（直接传参数，默认参数） */
    public BacktestEngine(double initialCapital, int leverage) {
        TradingProperties.StrategyParams defaultParams = new TradingProperties.StrategyParams();
        this.analyzer       = new MarketAnalyzer(defaultParams);
        this.initialCapital = initialCapital;
        this.leverage       = leverage;
    }

    /** 优化器专用：自定义参数 */
    public BacktestEngine(double initialCapital, int leverage, TradingProperties.StrategyParams params) {
        this.analyzer       = new MarketAnalyzer(params);
        this.initialCapital = initialCapital;
        this.leverage       = leverage;
    }

    /**
     * 对指定策略运行回测
     *
     * @param csvPath  历史K线 CSV 文件路径
     * @param strategy 要测试的策略
     * @return 回测结果
     */
    public BacktestResult run(String csvPath, Strategy strategy) throws Exception {
        return run(loadCsv(csvPath), strategy);
    }

    /**
     * 带时间范围的回测
     * @param startMs 开始时间（毫秒时间戳，包含），0表示不限
     * @param endMs   结束时间（毫秒时间戳，包含），0表示不限
     */
    public BacktestResult run(List<KLine> allKlines, Strategy strategy, long startMs, long endMs) {
        if (startMs > 0 || endMs > 0) {
            List<KLine> filtered = new java.util.ArrayList<>();
            for (KLine k : allKlines) {
                if (startMs > 0 && k.getTimestamp() < startMs) continue;
                if (endMs > 0 && k.getTimestamp() > endMs) continue;
                filtered.add(k);
            }
            if (!silent) {
                System.out.printf("  时间过滤: %d → %d 根K线%n", allKlines.size(), filtered.size());
            }
            return run(filtered, strategy);
        }
        return run(allKlines, strategy);
    }

    /**
     * 对指定策略运行回测（使用预加载的K线数据，优化器用）
     */
    public BacktestResult run(List<KLine> allKlines, Strategy strategy) {
        if (!silent) {
            System.out.printf("%n开始回测: %s | 共 %d 根K线%n",
                strategy.getName().toUpperCase(), allKlines.size());
        }

        BacktestResult result = new BacktestResult(initialCapital, leverage);
        boolean isStateMachine = strategy instanceof AggressiveStrategy;

        boolean inPosition  = false;
        String  posDirection = null;
        double  entryPrice  = 0;
        double  stopLoss    = 0;
        double  takeProfit  = 0;
        double  posSize     = 0;
        long    entryTime   = 0;
        // 状态机：试探仓和加仓分开跟踪
        boolean inProbe     = false;
        double  probeSize   = 0;
        double  addSize     = 0;

        int signalCount = 0;

        for (int i = WARMUP_BARS; i < allKlines.size(); i++) {

            KLine current = allKlines.get(i);

            // ── 状态机策略（AggressiveStrategy）──────────────────────────
            if (isStateMachine) {
                AggressiveStrategy agg = (AggressiveStrategy) strategy;

                // 阶段3：已确认持仓 → 回测引擎管止盈止损，不调用策略
                if (inPosition && !inProbe) {
                    boolean slHit = "long".equals(posDirection)
                        ? current.getLow() <= stopLoss : current.getHigh() >= stopLoss;
                    boolean tpHit = "long".equals(posDirection)
                        ? current.getHigh() >= takeProfit : current.getLow() <= takeProfit;

                    if (slHit || tpHit) {
                        String exitReason = slHit ? "sl" : "tp";
                        double exitPrice = slHit ? stopLoss : takeProfit;
                        double pricePct = "long".equals(posDirection)
                            ? (exitPrice - entryPrice) / entryPrice
                            : (entryPrice - exitPrice) / entryPrice;
                        double pnlU = result.getCurrentCapital() * posSize * leverage * pricePct;
                        result.addTrade(new BacktestResult.Trade(
                            entryTime, posDirection, entryPrice,
                            stopLoss, takeProfit, posSize,
                            exitReason, pricePct * 100, pnlU));
                        agg.reset();
                        inPosition = false;
                        if (result.getCurrentCapital() <= 0) break;
                    }
                    continue;
                }

                // 阶段1&2：IDLE 或 PROBE → 喂数据给策略
                List<KLine> window = allKlines.subList(Math.max(0, i - 49), i + 1);
                if (window.size() < 23) continue;

                try {
                    MarketData marketData = analyzer.analyze(window);
                    TradeSignal signal = agg.generateSignal(marketData);
                    AggressiveStrategy.State st = agg.getState();

                    // 阶段1：开试探仓
                    if (st == AggressiveStrategy.State.PROBE && !inPosition
                            && signal.getAction() != TradeSignal.Action.NO_TRADE) {
                        signalCount++;
                        inPosition   = true;
                        inProbe      = true;
                        posDirection = signal.getAction().getValue();
                        entryPrice   = current.getClose();
                        stopLoss     = signal.getStopLoss();
                        takeProfit   = signal.getTakeProfit();
                        probeSize    = signal.getPositionSize();
                        posSize      = probeSize;
                        entryTime    = current.getTimestamp();
                    }
                    // 阶段2：确认加仓
                    else if (st == AggressiveStrategy.State.CONFIRMED && inPosition && inProbe
                            && signal.getAction() != TradeSignal.Action.NO_TRADE) {
                        posSize    = probeSize + signal.getPositionSize();
                        stopLoss   = signal.getStopLoss();
                        takeProfit = signal.getTakeProfit();
                        inProbe    = false; // 进入确认持仓，后续由引擎管止盈止损
                    }
                    // 试探失败（策略reset回IDLE）→ 平仓
                    else if (st == AggressiveStrategy.State.IDLE && inPosition) {
                        double exitPrice = current.getClose();
                        double pricePct = "long".equals(posDirection)
                            ? (exitPrice - entryPrice) / entryPrice
                            : (entryPrice - exitPrice) / entryPrice;
                        double pnlU = result.getCurrentCapital() * posSize * leverage * pricePct;
                        result.addTrade(new BacktestResult.Trade(
                            entryTime, posDirection, entryPrice,
                            stopLoss, takeProfit, posSize,
                            "probe-fail", pricePct * 100, pnlU));
                        inPosition = false;
                        inProbe    = false;
                        if (result.getCurrentCapital() <= 0) break;
                    }
                    else if (!inPosition && signal.getAction() == TradeSignal.Action.NO_TRADE) {
                        result.recordReject(signal.getReason());
                    }

                    // 试探仓阶段：引擎兜底止损检查
                    if (inPosition && inProbe) {
                        boolean slHit = "long".equals(posDirection)
                            ? current.getLow() <= stopLoss : current.getHigh() >= stopLoss;
                        if (slHit) {
                            double pricePct = "long".equals(posDirection)
                                ? (stopLoss - entryPrice) / entryPrice
                                : (entryPrice - stopLoss) / entryPrice;
                            double pnlU = result.getCurrentCapital() * posSize * leverage * pricePct;
                            result.addTrade(new BacktestResult.Trade(
                                entryTime, posDirection, entryPrice,
                                stopLoss, takeProfit, posSize,
                                "probe-sl", pricePct * 100, pnlU));
                            agg.reset();
                            inPosition = false;
                            inProbe    = false;
                            if (result.getCurrentCapital() <= 0) break;
                        }
                    }
                } catch (Exception e) { /* skip */ }
                continue;
            }

            // ── 非状态机策略（保守/均值回归）：原有逻辑 ──────────────────
            if (inPosition) {
                boolean slHit = false;
                boolean tpHit = false;

                if ("long".equals(posDirection)) {
                    slHit = current.getLow()  <= stopLoss;
                    tpHit = current.getHigh() >= takeProfit;
                } else {
                    slHit = current.getHigh() >= stopLoss;
                    tpHit = current.getLow()  <= takeProfit;
                }

                // 止损优先（同一根K线同时触及，认为先止损）
                if (slHit || tpHit) {
                    String exitReason = slHit ? "sl" : "tp";
                    double exitPrice  = slHit ? stopLoss : takeProfit;

                    // 计算盈亏
                    double pricePct;
                    if ("long".equals(posDirection)) {
                        pricePct = (exitPrice - entryPrice) / entryPrice;
                    } else {
                        pricePct = (entryPrice - exitPrice) / entryPrice;
                    }

                    // 实际盈亏 = 本金 × 仓位比例 × 杠杆 × 价格变动%
                    double nominalCapital = result.getCurrentCapital() * posSize * leverage;
                    double pnlU           = nominalCapital * pricePct;
                    double pnlPct         = pnlU / result.getCurrentCapital() * 100;

                    result.addTrade(new BacktestResult.Trade(
                        entryTime, posDirection, entryPrice,
                        stopLoss, takeProfit, posSize,
                        exitReason, pnlPct, pnlU
                    ));

                    // 通知均值回归策略交易结果（熔断机制）
                    if (strategy instanceof MeanReversionStrategy mr) {
                        mr.recordTradeResult(pnlU > 0, current.getTimestamp());
                    }

                    inPosition = false;

                    // 如果本金归零，停止回测
                    if (result.getCurrentCapital() <= 0) {
                        if (!silent) System.out.println("  ⚠ 本金归零，回测终止");
                        break;
                    }
                }
                // 持仓中不开新仓，跳过信号生成
                continue;
            }

            // ── 无持仓，用滑动窗口生成信号 ───────────────────────────────
            // 取到当前K线（含）的历史数据喂给分析器
            List<KLine> window = allKlines.subList(Math.max(0, i - 49), i + 1);
            if (window.size() < 23) continue; // 数据不足，跳过

            try {
                MarketData  marketData = analyzer.analyze(window);

                // 均值回归策略：从15分钟数据聚合4小时K线做大箱体分析
                TradeSignal signal;
                if ("mean-reversion".equals(strategy.getName())) {
                    List<KLine> klines4h = aggregate4h(allKlines, i);
                    HtfRange htfRange = klines4h.size() >= 23
                        ? analyzer.analyzeHtf(klines4h) : new HtfRange(false, 0, 0, "neutral");
                    signal = strategy.generateSignal(marketData, htfRange);
                } else {
                    signal = strategy.generateSignal(marketData);
                }

                if (signal.getAction() != TradeSignal.Action.NO_TRADE) {
                    signalCount++;
                    inPosition   = true;
                    posDirection = signal.getAction().getValue();
                    entryPrice   = current.getClose();
                    stopLoss     = signal.getStopLoss();
                    takeProfit   = signal.getTakeProfit();
                    posSize      = signal.getPositionSize();
                    entryTime    = current.getTimestamp();
                } else {
                    result.recordReject(signal.getReason());
                }
            } catch (Exception e) {
                // 数据不足或分析异常，跳过这根K线
            }
        }

        // 如果回测结束时还有未平仓的持仓，以最后一根K线收盘价平仓
        if (inPosition) {
            KLine last = allKlines.get(allKlines.size() - 1);
            double exitPrice = last.getClose();
            double pricePct;
            if ("long".equals(posDirection)) {
                pricePct = (exitPrice - entryPrice) / entryPrice;
            } else {
                pricePct = (entryPrice - exitPrice) / entryPrice;
            }
            double nominalCapital = result.getCurrentCapital() * posSize * leverage;
            double pnlU           = nominalCapital * pricePct;
            result.addTrade(new BacktestResult.Trade(
                entryTime, posDirection, entryPrice,
                stopLoss, takeProfit, posSize,
                "end-of-data", pricePct * 100, pnlU
            ));
        }

        if (!silent) {
            System.out.printf("  信号触发: %d 次 | 实际交易: %d 笔%n",
                signalCount, result.getTrades().size());
        }

        return result;
    }

    /**
     * 从 CSV 文件加载K线数据（public 供优化器预加载）
     * 格式：timestamp,open,high,low,close,volume
     */
    public List<KLine> loadCsv(String path) throws Exception {
        List<KLine> klines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
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

    /**
     * 从15分钟K线聚合出4小时K线（16根15m = 1根4h）
     * 取当前位置之前的数据，最多聚合100根4h K线
     */
    private List<KLine> aggregate4h(List<KLine> klines15m, int currentIndex) {
        int barsPerCandle = 16; // 4h / 15m = 16
        int maxCandles = 100;
        List<KLine> result = new ArrayList<>();

        // 从当前位置往前，每16根聚合一根
        int end = currentIndex + 1; // exclusive
        int start = Math.max(0, end - maxCandles * barsPerCandle);

        for (int i = start; i + barsPerCandle <= end; i += barsPerCandle) {
            long   ts   = klines15m.get(i).getTimestamp();
            double open = klines15m.get(i).getOpen();
            double high = Double.MIN_VALUE;
            double low  = Double.MAX_VALUE;
            double vol  = 0;
            double close = klines15m.get(Math.min(i + barsPerCandle - 1, end - 1)).getClose();

            for (int j = i; j < i + barsPerCandle && j < end; j++) {
                KLine k = klines15m.get(j);
                high = Math.max(high, k.getHigh());
                low  = Math.min(low, k.getLow());
                vol += k.getVolume();
            }
            result.add(new KLine(ts, open, high, low, close, vol));
        }
        return result;
    }
}
