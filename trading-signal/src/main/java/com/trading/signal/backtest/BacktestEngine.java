package com.trading.signal.backtest;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
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
     * 对指定策略运行回测（使用预加载的K线数据，优化器用）
     */
    public BacktestResult run(List<KLine> allKlines, Strategy strategy) {
        if (!silent) {
            System.out.printf("%n开始回测: %s | 共 %d 根K线%n",
                strategy.getName().toUpperCase(), allKlines.size());
        }

        BacktestResult result = new BacktestResult(initialCapital, leverage);

        boolean inPosition  = false;
        String  posDirection = null;
        double  entryPrice  = 0;
        double  stopLoss    = 0;
        double  takeProfit  = 0;
        double  posSize     = 0;
        long    entryTime   = 0;

        int signalCount = 0;

        for (int i = WARMUP_BARS; i < allKlines.size(); i++) {

            KLine current = allKlines.get(i);

            // ── 如果有持仓，先检查当前K线是否触及止损/止盈 ──────────────
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
                TradeSignal signal     = strategy.generateSignal(marketData);

                if (signal.getAction() != TradeSignal.Action.NO_TRADE) {
                    signalCount++;
                    inPosition   = true;
                    posDirection = signal.getAction().getValue();
                    entryPrice   = current.getClose(); // 以当前收盘价入场
                    stopLoss     = signal.getStopLoss();
                    takeProfit   = signal.getTakeProfit();
                    posSize      = signal.getPositionSize();
                    entryTime    = current.getTimestamp();
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
}
