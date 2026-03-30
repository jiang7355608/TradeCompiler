package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 回测结果统计
 *
 * 记录每笔交易并计算汇总指标：
 *   胜率、平均盈亏比、最大连续亏损、最大回撤、总收益率
 */
public class BacktestResult {

    /** 单笔交易记录 */
    public static class Trade {
        public final long   entryTime;
        public final String direction;   // "long" / "short"
        public final double entryPrice;
        public final double stopLoss;
        public final double takeProfit;
        public final double positionSize; // 占本金比例
        public final String exitReason;  // "tp" / "sl"
        public final double pnlPct;      // 盈亏百分比（相对本金）
        public final double pnlU;        // 盈亏金额（U）

        public Trade(long entryTime, String direction, double entryPrice,
                     double stopLoss, double takeProfit, double positionSize,
                     String exitReason, double pnlPct, double pnlU) {
            this.entryTime    = entryTime;
            this.direction    = direction;
            this.entryPrice   = entryPrice;
            this.stopLoss     = stopLoss;
            this.takeProfit   = takeProfit;
            this.positionSize = positionSize;
            this.exitReason   = exitReason;
            this.pnlPct       = pnlPct;
            this.pnlU         = pnlU;
        }
    }

    private final double   initialCapital;
    private final int      leverage;
    private final List<Trade> trades = new ArrayList<>();

    // 实时跟踪
    private double currentCapital;
    private double peakCapital;

    // 统计
    private int    wins;
    private int    losses;
    private double totalWinPnl;
    private double totalLossPnl;
    private int    consecutiveLosses;
    private int    maxConsecutiveLosses;
    private double maxDrawdown; // 最大回撤（相对峰值）

    public BacktestResult(double initialCapital, int leverage) {
        this.initialCapital = initialCapital;
        this.currentCapital = initialCapital;
        this.peakCapital    = initialCapital;
        this.leverage       = leverage;
    }

    public void addTrade(Trade trade) {
        trades.add(trade);
        currentCapital += trade.pnlU;

        // 更新峰值和最大回撤
        if (currentCapital > peakCapital) {
            peakCapital = currentCapital;
        }
        double drawdown = (peakCapital - currentCapital) / peakCapital;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
        }

        // 胜负统计
        if (trade.pnlU > 0) {
            wins++;
            totalWinPnl += trade.pnlU;
            consecutiveLosses = 0;
        } else {
            losses++;
            totalLossPnl += Math.abs(trade.pnlU);
            consecutiveLosses++;
            if (consecutiveLosses > maxConsecutiveLosses) {
                maxConsecutiveLosses = consecutiveLosses;
            }
        }
    }

    public void printSummary(String strategyName) {
        int total = wins + losses;
        if (total == 0) {
            System.out.println("无交易信号产生");
            return;
        }

        double winRate      = (double) wins / total * 100;
        double avgWin       = wins > 0 ? totalWinPnl / wins : 0;
        double avgLoss      = losses > 0 ? totalLossPnl / losses : 0;
        double profitFactor = totalLossPnl > 0 ? totalWinPnl / totalLossPnl : Double.MAX_VALUE;
        double totalPnl     = currentCapital - initialCapital;
        double totalPnlPct  = totalPnl / initialCapital * 100;

        System.out.println("\n" + "=".repeat(55));
        System.out.printf("  回测结果 — %s%n", strategyName.toUpperCase());
        System.out.println("=".repeat(55));
        System.out.printf("  初始本金    : %.2f U  (杠杆 %dx)%n", initialCapital, leverage);
        System.out.printf("  最终本金    : %.2f U%n", currentCapital);
        System.out.printf("  总盈亏      : %+.2f U  (%+.2f%%)%n", totalPnl, totalPnlPct);
        System.out.println("-".repeat(55));
        System.out.printf("  总交易次数  : %d 次%n", total);
        System.out.printf("  盈利次数    : %d 次%n", wins);
        System.out.printf("  亏损次数    : %d 次%n", losses);
        System.out.printf("  胜率        : %.1f%%%n", winRate);
        System.out.println("-".repeat(55));
        System.out.printf("  平均盈利    : +%.2f U%n", avgWin);
        System.out.printf("  平均亏损    : -%.2f U%n", avgLoss);
        System.out.printf("  盈亏比      : %.2f%n", avgLoss > 0 ? avgWin / avgLoss : 0);
        System.out.printf("  利润因子    : %.2f%n", profitFactor);
        System.out.println("-".repeat(55));
        System.out.printf("  最大连续亏损: %d 次%n", maxConsecutiveLosses);
        System.out.printf("  最大回撤    : %.2f%%%n", maxDrawdown * 100);
        System.out.println("=".repeat(55));

        // 期望值判断
        double expectancy = (winRate / 100 * avgWin) - ((1 - winRate / 100) * avgLoss);
        System.out.printf("  每笔期望值  : %+.2f U%n", expectancy);
        if (expectancy > 0) {
            System.out.println("  结论        : ✓ 正期望值策略，理论上长期盈利");
        } else {
            System.out.println("  结论        : ✗ 负期望值策略，长期运行会亏损");
        }
        System.out.println("=".repeat(55));
    }

    public List<Trade> getTrades()       { return trades; }
    public double getCurrentCapital()    { return currentCapital; }
    public double getMaxDrawdown()       { return maxDrawdown; }
    public int    getMaxConsecLosses()   { return maxConsecutiveLosses; }
    public int    getWins()              { return wins; }
    public int    getLosses()            { return losses; }
    public double getInitialCapital()    { return initialCapital; }

    /**
     * 生成邮件报告文本（含参数调整建议）
     *
     * @param strategyName 策略名称
     * @param params       当前参数配置（用于生成调整建议）
     * @param isAggressive true=激进策略，false=保守策略
     */
    public String buildEmailReport(String strategyName,
                                   TradingProperties.StrategyParams params,
                                   boolean isAggressive) {
        int total = wins + losses;
        if (total == 0) return "【" + strategyName + "】\n  本期无交易信号产生\n\n";

        double winRate      = (double) wins / total * 100;
        double avgWin       = wins > 0 ? totalWinPnl / wins : 0;
        double avgLoss      = losses > 0 ? totalLossPnl / losses : 0;
        double rrRatio      = avgLoss > 0 ? avgWin / avgLoss : 0;
        double profitFactor = totalLossPnl > 0 ? totalWinPnl / totalLossPnl : 999;
        double totalPnl     = currentCapital - initialCapital;
        double totalPnlPct  = totalPnl / initialCapital * 100;
        double expectancy   = (winRate / 100 * avgWin) - ((1 - winRate / 100) * avgLoss);
        long   slCount      = trades.stream().filter(t -> "sl".equals(t.exitReason)).count();
        long   tpCount      = trades.stream().filter(t -> "tp".equals(t.exitReason)).count();

        // ── 基础统计 ──────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s】\n", strategyName));
        sb.append(String.format("  止损方式    : 动态（前一根K线中点，兜底%.2f%%）\n",
            isAggressive ? params.getAggMinSlPct() * 100 : params.getConMinSlPct() * 100));
        sb.append(String.format("  止盈方式    : 动态（止损距离 × %.1f倍风险回报比）\n",
            isAggressive ? params.getAggRiskRewardRatio() : params.getConRiskRewardRatio()));
        sb.append(String.format("  总盈亏      : %+.2fU (%+.2f%%)\n", totalPnl, totalPnlPct));
        sb.append(String.format("  交易次数    : %d次（盈%d / 亏%d）\n", total, wins, losses));
        sb.append(String.format("  胜率        : %.1f%%\n", winRate));
        sb.append(String.format("  平均盈利    : +%.2fU  平均亏损: -%.2fU\n", avgWin, avgLoss));
        sb.append(String.format("  盈亏比      : %.2f  利润因子: %.2f\n", rrRatio, profitFactor));
        sb.append(String.format("  止盈触发    : %d次  止损触发: %d次（止损率%.1f%%）\n",
            tpCount, slCount, (double) slCount / total * 100));
        sb.append(String.format("  最大连续亏损: %d次  最大回撤: %.1f%%\n",
            maxConsecutiveLosses, maxDrawdown * 100));
        sb.append(String.format("  每笔期望值  : %+.2fU\n", expectancy));

        // 总体结论
        if (expectancy > 0) {
            sb.append("  总体结论    : ✓ 正期望值策略\n");
        } else {
            sb.append("  总体结论    : ✗ 负期望值策略，必须调整参数\n");
        }
        sb.append("\n");

        // ── 参数调整建议 ──────────────────────────────────────────────────
        sb.append("  ─── 参数调整建议 ───────────────────────────\n");
        List<ParameterAdvisor.Advice> advices = isAggressive
            ? ParameterAdvisor.adviseAggressive(this, params)
            : ParameterAdvisor.adviseConservative(this, params);
        sb.append(ParameterAdvisor.formatAdvices(advices, strategyName));
        sb.append("\n");

        return sb.toString();
    }

    /**
     * 兼容旧调用（无参数建议版本）
     */
    public String buildEmailReport(String strategyName) {
        int total = wins + losses;
        if (total == 0) return strategyName + ": 本期无交易信号产生\n";
        double winRate  = (double) wins / total * 100;
        double avgWin   = wins > 0 ? totalWinPnl / wins : 0;
        double avgLoss  = losses > 0 ? totalLossPnl / losses : 0;
        double totalPnl = currentCapital - initialCapital;
        double expectancy = (winRate / 100 * avgWin) - ((1 - winRate / 100) * avgLoss);
        return String.format("【%s】总盈亏: %+.2fU 胜率: %.1f%% 期望值: %+.2fU\n",
            strategyName, totalPnl, winRate, expectancy);
    }
}
