package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.ConservativeStrategy;
import com.trading.signal.strategy.MeanReversionStrategy;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 回测独立入口（不依赖 Spring Boot 上下文，直接运行 main 方法）
 *
 * 运行方式：
 *   mvn compile
 *   mvn exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
 *
 * 所有参数与 application.yml 保持一致，修改此处配置项即可。
 */
public class BacktestRunner {

    // ── 与 application.yml 保持一致的配置项 ──────────────────────────────
    private static final String INST_ID          = "BTC-USDT";
    private static final String BAR              = "15m";   // 15分钟K线
    private static final String CSV_FILE         = "backtest-data/btc_15m_2025-12-30_to_2026-03-30.csv";
    private static final double INITIAL_CAPITAL  = 200.0;
    private static final int    LEVERAGE         = 20;

    // 数据时间范围：最近3个月（2025-12-30 → 2026-03-30）
    private static final long END_MS   = LocalDate.of(2026, 3, 30)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long START_MS = LocalDate.of(2025, 12, 30)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    // ─────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           BTC 交易策略回测系统                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("本金: %.0fU  杠杆: %dx  品种: %s  周期: %s%n%n",
            INITIAL_CAPITAL, LEVERAGE, INST_ID, BAR);

        // ── Step 1: 抓取历史数据 ──────────────────────────────────────────
        // CSV 已存在则跳过，删除文件后重新运行可强制重新抓取
        java.io.File csvFile = new java.io.File(CSV_FILE);
        if (csvFile.exists()) {
            System.out.printf("CSV 文件已存在 (%s)，跳过数据抓取%n", CSV_FILE);
            System.out.println("如需重新抓取，请删除该文件后重新运行");
        } else {
            System.out.println("── Step 1: 抓取历史K线数据 ──────────────────────────");
            // 本地测试走 Clash 代理（127.0.0.1:7890），生产环境用无参构造
            DataFetcher fetcher = new DataFetcher("127.0.0.1", 7897);
            fetcher.fetch(INST_ID, BAR, START_MS, END_MS, CSV_FILE);
        }

        // ── Step 2: 使用默认参数（与 application.yml 默认值一致）──────────
        TradingProperties.StrategyParams params = new TradingProperties.StrategyParams();
        BacktestEngine engine = new BacktestEngine(INITIAL_CAPITAL, LEVERAGE);

        // ── 激进策略回测 ──────────────────────────────────────────────────
        System.out.println("\n── Step 2a: 激进策略回测 ─────────────────────────────");
        BacktestResult aggResult = engine.run(CSV_FILE, new AggressiveStrategy(params));
        aggResult.printSummary("Aggressive Strategy");

        // ── 保守策略回测 ──────────────────────────────────────────────────
        System.out.println("\n── Step 2b: 保守策略回测 ─────────────────────────────");
        BacktestResult conResult = engine.run(CSV_FILE, new ConservativeStrategy(params));
        conResult.printSummary("Conservative Strategy");

        // ── 均值回归策略回测 ──────────────────────────────────────────────
        System.out.println("\n── Step 2c: 均值回归策略回测 ─────────────────────────");
        BacktestResult mrResult = engine.run(CSV_FILE, new MeanReversionStrategy(params));
        mrResult.printSummary("Mean Reversion Strategy");

        // ── Step 3: 对比总结 ──────────────────────────────────────────────
        printComparison(aggResult, conResult);
    }

    private static void printComparison(BacktestResult agg, BacktestResult con) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║                  策略对比                            ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf("║  %-20s  %-12s  %-12s ║%n", "指标", "激进策略", "保守策略");
        System.out.println("╠══════════════════════════════════════════════════════╣");

        int    aggTrades  = agg.getTrades().size();
        int    conTrades  = con.getTrades().size();
        long   aggWins    = agg.getTrades().stream().filter(t -> t.pnlU > 0).count();
        long   conWins    = con.getTrades().stream().filter(t -> t.pnlU > 0).count();
        double aggWinRate = aggTrades > 0 ? (double) aggWins / aggTrades * 100 : 0;
        double conWinRate = conTrades > 0 ? (double) conWins / conTrades * 100 : 0;
        double aggPnl     = agg.getCurrentCapital() - INITIAL_CAPITAL;
        double conPnl     = con.getCurrentCapital() - INITIAL_CAPITAL;

        System.out.printf("║  %-20s  %-12d  %-12d ║%n", "交易次数", aggTrades, conTrades);
        System.out.printf("║  %-20s  %-11.1f%%  %-11.1f%% ║%n", "胜率", aggWinRate, conWinRate);
        System.out.printf("║  %-20s  %-11.2f%%  %-11.2f%% ║%n", "最大回撤",
            agg.getMaxDrawdown() * 100, con.getMaxDrawdown() * 100);
        System.out.printf("║  %-20s  %-12d  %-12d ║%n", "最大连续亏损",
            agg.getMaxConsecLosses(), con.getMaxConsecLosses());
        System.out.printf("║  %-20s  %+.2fU%-8s  %+.2fU%-8s ║%n", "总盈亏",
            aggPnl, "", conPnl, "");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }
}
