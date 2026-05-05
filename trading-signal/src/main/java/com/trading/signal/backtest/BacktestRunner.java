package com.trading.signal.backtest;

import com.trading.signal.strategy.AggressiveStrategy;
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
 * 策略选择：修改 STRATEGY 常量
 *   - "aggressive"     → AggressiveBacktestEngine（突破策略）
 *   - "mean-reversion" → MeanReversionBacktestEngine（均值回归策略）
 */
public class BacktestRunner {

    // ── 回测配置项 ──────────────────────────────────────────────────────
    /** 策略选择: "aggressive" 或 "mean-reversion" */
    private static final String STRATEGY = "aggressive";

    private static final String INST_ID = "BTC-USDT";
    private static final String BAR = "15m";   // 15分钟K线
    private static final String CSV_FILE = "backtest-data/btc_15m_2026-02-01_to_2026-04-15.csv";
    private static final double INITIAL_CAPITAL = 200.0;
    private static final int LEVERAGE = 20;

    /** Mean Reversion 专用：手动指定箱体上下沿（美元） */
    private static final double MR_RANGE_HIGH = 88000;
    private static final double MR_RANGE_LOW  = 80000;

    // 数据时间范围
    private static final long END_MS = LocalDate.of(2026, 4, 15)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final long START_MS = LocalDate.of(2026, 2, 1)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    // ─────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           BTC 策略回测系统                           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.printf("策略: %s  本金: %.0fU  杠杆: %dx  品种: %s  周期: %s%n%n",
            STRATEGY, INITIAL_CAPITAL, LEVERAGE, INST_ID, BAR);

        // ── Step 1: 抓取历史数据 ──────────────────────────────────────────
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

        // ── Step 2: 执行回测 ──────────────────────────────────────────────
        BacktestResult result;

        if ("mean-reversion".equals(STRATEGY)) {
            System.out.println("\n── Step 2: 均值回归策略回测 ─────────────────────────");
            System.out.printf("箱体配置: %.0f - %.0f (宽度 %.0f)%n",
                MR_RANGE_LOW, MR_RANGE_HIGH, MR_RANGE_HIGH - MR_RANGE_LOW);

            MeanReversionBacktestEngine.MockBoxRangeDetector mockBox =
                new MeanReversionBacktestEngine.MockBoxRangeDetector(MR_RANGE_HIGH, MR_RANGE_LOW);
            MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
            MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();

            result = engine.run(CSV_FILE, strategy, INITIAL_CAPITAL, LEVERAGE);
            result.printSummary("Mean Reversion Strategy");

        } else {
            System.out.println("\n── Step 2: 突破策略回测 ─────────────────────────────");

            AggressiveBacktestEngine engine = new AggressiveBacktestEngine();
            AggressiveStrategy strategy = new AggressiveStrategy(INITIAL_CAPITAL);

            result = engine.run(CSV_FILE, strategy, INITIAL_CAPITAL, LEVERAGE);
            result.printSummary("Aggressive Strategy");
        }

        // ── Step 3: 结果总结 ──────────────────────────────────────────────
        printSummary(result);
    }

    private static void printSummary(BacktestResult result) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║                  回测总结                            ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");

        int trades = result.getTrades().size();
        long wins = result.getTrades().stream().filter(t -> t.pnlU > 0).count();
        double winRate = trades > 0 ? (double) wins / trades * 100 : 0;
        double totalPnl = result.getCurrentCapital() - INITIAL_CAPITAL;
        double totalPnlPct = totalPnl / INITIAL_CAPITAL * 100;

        System.out.printf("║  策略          : %-30s   ║%n", STRATEGY);
        System.out.printf("║  交易次数      : %-12d                    ║%n", trades);
        System.out.printf("║  盈利次数      : %-12d                    ║%n", (int) wins);
        System.out.printf("║  胜率          : %-11.1f%%                   ║%n", winRate);
        System.out.printf("║  总盈亏        : %+.2fU (%.2f%%)%-12s    ║%n",
            totalPnl, totalPnlPct, "");
        System.out.printf("║  最大回撤      : %-11.2f%%                   ║%n",
            result.getMaxDrawdown() * 100);
        System.out.printf("║  最大连续亏损  : %-12d                    ║%n",
            result.getMaxConsecLosses());

        System.out.println("╚══════════════════════════════════════════════════════╝");

        if (totalPnl > 0) {
            System.out.println("\n✅ 策略表现良好，具有正期望值");
        } else {
            System.out.println("\n❌ 策略需要优化，当前为负期望值");
        }
    }
}
