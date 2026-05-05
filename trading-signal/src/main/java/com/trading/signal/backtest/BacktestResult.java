package com.trading.signal.backtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回测结果统计器 - 记录交易并计算性能指标
 *
 * 功能：
 * - 记录每笔交易的详细信息
 * - 计算胜率、盈亏比、最大回撤等关键指标
 * - 生成格式化的回测报告
 * - 提供期望值分析和策略评估
 * 
 * 核心指标：
 * - 胜率：盈利交易占总交易的百分比
 * - 盈亏比：平均盈利 / 平均亏损
 * - 利润因子：总盈利 / 总亏损
 * - 最大回撤：相对于峰值资金的最大跌幅
 * - 期望值：每笔交易的平均盈亏
 */
public class BacktestResult {

    /**
     * 单笔交易记录
     */
    public static class Trade {
        public final long entryTime;        // 入场时间戳
        public final String direction;      // "long" / "short"
        public final double entryPrice;     // 入场价格
        public final double stopLoss;       // 止损价格
        public final double takeProfit;     // 止盈价格
        public final double positionSize;   // 仓位大小（占本金比例）
        public final String exitReason;    // 平仓原因："tp" / "sl" / "trailing"
        public final double pnlPct;         // 盈亏百分比（相对本金）
        public final double pnlU;           // 盈亏金额（USDT）

        public Trade(long entryTime, String direction, double entryPrice,
                     double stopLoss, double takeProfit, double positionSize,
                     String exitReason, double pnlPct, double pnlU) {
            this.entryTime = entryTime;
            this.direction = direction;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.positionSize = positionSize;
            this.exitReason = exitReason;
            this.pnlPct = pnlPct;
            this.pnlU = pnlU;
        }
    }

    private final double initialCapital;    // 初始资金
    private final int leverage;             // 杠杆倍数
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Integer> rejectReasons = new java.util.LinkedHashMap<>();

    // 实时跟踪
    private double currentCapital;          // 当前资金
    private double peakCapital;             // 峰值资金

    // 统计指标
    private int wins;                       // 盈利次数
    private int losses;                     // 亏损次数
    private double totalWinPnl;             // 总盈利金额
    private double totalLossPnl;            // 总亏损金额
    private int consecutiveLosses;          // 当前连续亏损次数
    private int maxConsecutiveLosses;       // 最大连续亏损次数
    private double maxDrawdown;             // 最大回撤（相对峰值）

    /**
     * 构造回测结果统计器
     * 
     * @param initialCapital 初始资金（USDT）
     * @param leverage 杠杆倍数
     */
    public BacktestResult(double initialCapital, int leverage) {
        this.initialCapital = initialCapital;
        this.currentCapital = initialCapital;
        this.peakCapital = initialCapital;
        this.leverage = leverage;
    }

    /**
     * 添加一笔交易记录
     * 
     * @param trade 交易记录
     */
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

    /**
     * 记录被拒绝的信号（用于分析策略过滤效果）
     * 
     * @param reason 拒绝原因
     */
    public void recordReject(String reason) {
        // 提取拒绝原因的关键词作为分类
        String key = reason;
        int idx = reason.indexOf('(');
        if (idx > 0) key = reason.substring(0, idx).trim();
        idx = key.indexOf(':');
        if (idx > 0) key = key.substring(0, idx).trim();
        if (key.length() > 60) key = key.substring(0, 60);
        rejectReasons.merge(key, 1, Integer::sum);
    }

    /**
     * 打印回测结果摘要
     * 
     * @param strategyName 策略名称
     */
    public void printSummary(String strategyName) {
        int total = wins + losses;
        if (total == 0) {
            System.out.println("无交易信号产生");
            return;
        }

        double winRate = (double) wins / total * 100;
        double avgWin = wins > 0 ? totalWinPnl / wins : 0;
        double avgLoss = losses > 0 ? totalLossPnl / losses : 0;
        double profitFactor = totalLossPnl > 0 ? totalWinPnl / totalLossPnl : Double.MAX_VALUE;
        double totalPnl = currentCapital - initialCapital;
        double totalPnlPct = totalPnl / initialCapital * 100;

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

    /**
     * 生成简化的邮件报告
     * 
     * @param strategyName 策略名称
     * @return 报告文本
     */
    public String buildEmailReport(String strategyName) {
        int total = wins + losses;
        if (total == 0) {
            return strategyName + ": 本期无交易信号产生\n";
        }
        
        double winRate = (double) wins / total * 100;
        double avgWin = wins > 0 ? totalWinPnl / wins : 0;
        double avgLoss = losses > 0 ? totalLossPnl / losses : 0;
        double totalPnl = currentCapital - initialCapital;
        double expectancy = (winRate / 100 * avgWin) - ((1 - winRate / 100) * avgLoss);
        
        return String.format("【%s】总盈亏: %+.2fU 胜率: %.1f%% 期望值: %+.2fU\n",
            strategyName, totalPnl, winRate, expectancy);
    }

    // ── Getter 方法 ──────────────────────────────────────────────────────

    public List<Trade> getTrades() { return trades; }
    public double getCurrentCapital() { return currentCapital; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public int getMaxConsecLosses() { return maxConsecutiveLosses; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public double getInitialCapital() { return initialCapital; }
    public int getLeverage() { return leverage; }
    public Map<String, Integer> getRejectReasons() { return rejectReasons; }
}
