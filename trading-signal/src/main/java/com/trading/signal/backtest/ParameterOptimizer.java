package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.ConservativeStrategy;
import com.trading.signal.strategy.MeanReversionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * 参数网格搜索优化器（Spring Service）
 *
 * 评分公式：score = expectancy × sqrt(tradeCount) × (1 - maxDrawdown×2) × profitFactor
 * 硬性过滤：交易次数 >= 15 / 最大回撤 <= 20% / 胜率 >= 40%
 */
@Service
public class ParameterOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ParameterOptimizer.class);

    private static final int    MIN_TRADES   = 15;
    private static final double MAX_DRAWDOWN = 0.20;
    private static final double MIN_WIN_RATE = 0.40;

    private final TradingProperties properties;

    public ParameterOptimizer(TradingProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> optimize(int topN) throws Exception {
        double initialCapital = properties.getBacktest().getInitialCapital();
        int    leverage       = properties.getBacktest().getLeverage();
        String csvFile        = findLatestCsv();

        BacktestEngine loader = new BacktestEngine(initialCapital, leverage);
        List<KLine> klines = loader.loadCsv(csvFile);
        log.info("参数优化开始: {} 根K线, 本金={}U, 杠杆={}x",
            klines.size(), initialCapital, leverage);

        List<ScoredResult> aggResults = runGrid(klines, initialCapital, leverage, "aggressive");
        List<ScoredResult> conResults = runGrid(klines, initialCapital, leverage, "conservative");
        List<ScoredResult> mrResults  = runGrid(klines, initialCapital, leverage, "mean-reversion");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dataFile", csvFile);
        response.put("klineCount", klines.size());
        response.put("aggressive", formatResults(aggResults, topN, "aggressive", initialCapital));
        response.put("conservative", formatResults(conResults, topN, "conservative", initialCapital));
        response.put("meanReversion", formatResults(mrResults, topN, "mean-reversion", initialCapital));
        return response;
    }

    private List<ScoredResult> runGrid(List<KLine> klines, double capital, int leverage,
                                        String strategyType) {
        return "mean-reversion".equals(strategyType)
            ? runMrGrid(klines, capital, leverage)
            : runBreakoutGrid(klines, capital, leverage, strategyType);
    }

    /** 突破策略（激进/保守）的搜索空间 */
    private List<ScoredResult> runBreakoutGrid(List<KLine> klines, double capital, int leverage,
                                                String strategyType) {
        List<ScoredResult> results = new ArrayList<>();

        double[] rangeThresholds   = {0.006, 0.008, 0.010, 0.012, 0.015};
        double[] volumeMultipliers = {1.2, 1.5, 1.8, 2.0, 2.5};
        double[] riskRewardRatios  = {1.5, 2.0, 2.5, 3.0};
        double[] minSlPcts         = {0.001, 0.002, 0.003, 0.004};

        int total = 0;
        for (double rt : rangeThresholds) {
            for (double vm : volumeMultipliers) {
                for (double rr : riskRewardRatios) {
                    for (double minSl : minSlPcts) {
                        total++;
                        TradingProperties.StrategyParams p = buildParams(rt, vm, rr, minSl);
                        BacktestEngine engine = new BacktestEngine(capital, leverage, p).setSilent(true);
                        BacktestResult result;
                        try { result = engine.run(klines, createStrategy(strategyType, p)); }
                        catch (Exception e) { continue; }

                        int n = result.getTrades().size();
                        if (n == 0) continue;
                        double winRate  = (double) result.getWins() / n;
                        double drawdown = result.getMaxDrawdown();
                        results.add(new ScoredResult(calcScore(result), p, result, n, winRate, drawdown));
                    }
                }
            }
        }
        log.info("{}策略: 搜索 {} 组, 通过 {} 组", strategyType, total, results.size());
        results.sort(Comparator.comparingDouble(r -> -r.score));
        return results;
    }

    /** 均值回归策略的搜索空间：buffer / slBuffer / maxSlPct / riskRewardRatio */
    private List<ScoredResult> runMrGrid(List<KLine> klines, double capital, int leverage) {
        List<ScoredResult> results = new ArrayList<>();

        double[] buffers       = {0.003, 0.005, 0.008, 0.010};
        double[] slBuffers     = {0.15, 0.20, 0.30, 0.40};
        double[] maxSlPcts     = {0.005, 0.008, 0.010, 0.015};
        double[] rrRatios      = {1.5, 2.0, 2.5, 3.0};

        int total = 0;
        for (double buf : buffers) {
            for (double slBuf : slBuffers) {
                for (double maxSl : maxSlPcts) {
                    for (double rr : rrRatios) {
                        total++;
                        TradingProperties.StrategyParams p = new TradingProperties.StrategyParams();
                        p.setMrBuffer(buf);
                        p.setMrSlBuffer(slBuf);
                        p.setMrMaxSlPct(maxSl);
                        p.setMrRiskRewardRatio(rr);
                        BacktestEngine engine = new BacktestEngine(capital, leverage, p).setSilent(true);
                        BacktestResult result;
                        try { result = engine.run(klines, new MeanReversionStrategy(p)); }
                        catch (Exception e) { continue; }

                        int n = result.getTrades().size();
                        if (n == 0) continue;
                        double winRate  = (double) result.getWins() / n;
                        double drawdown = result.getMaxDrawdown();
                        results.add(new ScoredResult(calcScore(result), p, result, n, winRate, drawdown));
                    }
                }
            }
        }
        log.info("均值回归策略: 搜索 {} 组, 通过 {} 组", total, results.size());
        results.sort(Comparator.comparingDouble(r -> -r.score));
        return results;
    }

    private double calcScore(BacktestResult r) {
        int    n       = r.getTrades().size();
        double winRate = (double) r.getWins() / n;
        double avgWin  = r.getWins() > 0
            ? r.getTrades().stream().filter(t -> t.pnlU > 0)
                .mapToDouble(t -> t.pnlU).average().orElse(0) : 0;
        double avgLoss = r.getLosses() > 0
            ? r.getTrades().stream().filter(t -> t.pnlU <= 0)
                .mapToDouble(t -> Math.abs(t.pnlU)).average().orElse(0) : 0;
        double expectancy      = winRate * avgWin - (1 - winRate) * avgLoss;
        double profitFactor    = avgLoss > 0
            ? (winRate * avgWin) / ((1 - winRate) * avgLoss) : 1.0;
        double drawdownPenalty = Math.max(0, 1 - r.getMaxDrawdown() * 2);
        return expectancy * Math.sqrt(n) * drawdownPenalty * profitFactor;
    }

    private com.trading.signal.strategy.Strategy createStrategy(
            String type, TradingProperties.StrategyParams p) {
        return switch (type) {
            case "aggressive"      -> new AggressiveStrategy(p);
            case "conservative"    -> new ConservativeStrategy(p);
            case "mean-reversion"  -> new MeanReversionStrategy(p);
            default -> throw new IllegalArgumentException("Unknown strategy: " + type);
        };
    }

    private TradingProperties.StrategyParams buildParams(
            double rt, double vm, double rr, double minSl) {
        TradingProperties.StrategyParams p = new TradingProperties.StrategyParams();
        p.setRangeThreshold(rt);
        p.setVolumeSpikeMultiplier(vm);
        p.setAggRiskRewardRatio(rr);
        p.setAggMinSlPct(minSl);
        p.setConRiskRewardRatio(rr);
        p.setConMinSlPct(minSl);
        p.setMrRiskRewardRatio(rr);
        p.setMrSlBuffer(minSl);
        return p;
    }

    private List<Map<String, Object>> formatResults(List<ScoredResult> results, int topN,
                                                     String strategyType, double capital) {
        if (results.isEmpty()) {
            return List.of(Map.of("message", "没有参数组合通过硬性过滤"));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ScoredResult r : results.subList(0, Math.min(topN, results.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", list.size() + 1);
            item.put("score", Math.round(r.score * 1000.0) / 1000.0);
            item.put("totalPnl",
                Math.round((r.result.getCurrentCapital() - capital) * 100.0) / 100.0);
            item.put("trades", r.tradeCount);
            item.put("winRate", Math.round(r.winRate * 1000.0) / 10.0 + "%");
            item.put("maxDrawdown", Math.round(r.drawdown * 1000.0) / 10.0 + "%");

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("rangeWindow", r.params.getRangeWindow());
            params.put("rangeThreshold", r.params.getRangeThreshold());
            params.put("breakoutWindowLong", r.params.getBreakoutWindowLong());
            params.put("volumeSpikeMultiplier", r.params.getVolumeSpikeMultiplier());
            params.put("trendThreshold", r.params.getTrendThreshold());
            params.put("riskRewardRatio", "aggressive".equals(strategyType)
                ? r.params.getAggRiskRewardRatio()
                : "mean-reversion".equals(strategyType)
                    ? r.params.getMrRiskRewardRatio()
                    : r.params.getConRiskRewardRatio());
            if ("mean-reversion".equals(strategyType)) {
                params.put("mrBuffer", r.params.getMrBuffer());
                params.put("mrSlBuffer", r.params.getMrSlBuffer());
                params.put("mrMaxSlPct", r.params.getMrMaxSlPct());
            } else {
                params.put("minSlPct", "aggressive".equals(strategyType)
                    ? r.params.getAggMinSlPct() : r.params.getConMinSlPct());
            }
            item.put("params", params);
            list.add(item);
        }
        return list;
    }

    private String findLatestCsv() {
        String dataDir = properties.getBacktest().getDataDir();
        File dir = new File(dataDir);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new RuntimeException("回测数据目录不存在: " + dataDir);
        }
        File[] csvFiles = dir.listFiles(
            (d, name) -> name.endsWith(".csv") && name.contains("15m"));
        if (csvFiles == null || csvFiles.length == 0) {
            throw new RuntimeException("未找到15分钟K线CSV文件，请先运行回测数据抓取");
        }
        Arrays.sort(csvFiles, Comparator.comparingLong(File::lastModified).reversed());
        return csvFiles[0].getPath();
    }

    private static class ScoredResult {
        final double score;
        final TradingProperties.StrategyParams params;
        final BacktestResult result;
        final int tradeCount;
        final double winRate;
        final double drawdown;

        ScoredResult(double score, TradingProperties.StrategyParams params,
                     BacktestResult result, int tradeCount,
                     double winRate, double drawdown) {
            this.score      = score;
            this.params     = params;
            this.result     = result;
            this.tradeCount = tradeCount;
            this.winRate    = winRate;
            this.drawdown   = drawdown;
        }
    }
}
