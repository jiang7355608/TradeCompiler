package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数调整顾问
 *
 * 根据回测结果的具体指标，诊断当前参数的问题，
 * 并给出明确的参数名称 + 当前值 + 建议值 + 调整原因。
 *
 * 诊断逻辑：
 *
 *   ① 胜率 < 45%
 *      → 进场条件太松，假信号太多
 *      → 收紧放量倍数（volumeSpikeMultiplier +0.2）
 *      → 收紧横盘阈值（rangeThreshold -0.001）
 *
 *   ② 胜率 > 75%
 *      → 进场条件太严，信号太少，可能错过行情
 *      → 放松放量倍数（volumeSpikeMultiplier -0.1）
 *
 *   ③ 盈亏比 < 1.2（平均盈利 < 平均亏损 × 1.2）
 *      → 止盈太小或止损太大
 *      → 增大止盈比例（TP +0.002）
 *      → 或缩小止损比例（SL -0.001）
 *
 *   ④ 最大连续亏损 > 8 次
 *      → 止损太紧，被正常波动频繁打掉
 *      → 放宽止损（SL +0.001）
 *      → 同时适当缩小仓位（position -0.05）
 *
 *   ⑤ 最大回撤 > 35%
 *      → 仓位过重，单次亏损太大
 *      → 降低强信号仓位（positionStrong -0.05）
 *      → 降低弱信号仓位（positionWeak -0.05）
 *
 *   ⑥ 交易次数 < 15 次（3个月）
 *      → 信号太少，条件过于严格，可能错过大量机会
 *      → 放松横盘阈值（rangeThreshold +0.001）
 *      → 放松放量倍数（volumeSpikeMultiplier -0.1）
 *
 *   ⑦ 交易次数 > 180 次（3个月，平均每天2次以上）
 *      → 信号太频繁，过滤不够，质量下降
 *      → 收紧放量倍数（volumeSpikeMultiplier +0.2）
 *      → 延长冷却时间（cooldownMs +120000，即+2分钟）
 *
 *   ⑧ 止损触发率 > 70%（超过70%的交易以止损结束）
 *      → 止损位置太近，被正常波动打掉
 *      → 放宽止损（SL +0.001）
 */
public class ParameterAdvisor {

    /** 单条参数建议 */
    public static class Advice {
        public final String paramKey;      // application.yml 中的参数键名
        public final double currentValue;  // 当前值
        public final double suggestedValue;// 建议值
        public final String reason;        // 调整原因

        public Advice(String paramKey, double currentValue, double suggestedValue, String reason) {
            this.paramKey       = paramKey;
            this.currentValue   = currentValue;
            this.suggestedValue = suggestedValue;
            this.reason         = reason;
        }
    }

    /**
     * 为激进策略生成参数调整建议
     *
     * @param result 回测结果
     * @param p      当前参数配置
     * @return 建议列表（空列表表示参数无需调整）
     */
    public static List<Advice> adviseAggressive(BacktestResult result,
                                                 TradingProperties.StrategyParams p) {
        return diagnose(result, p, true);
    }

    /**
     * 为保守策略生成参数调整建议
     */
    public static List<Advice> adviseConservative(BacktestResult result,
                                                   TradingProperties.StrategyParams p) {
        return diagnose(result, p, false);
    }

    private static List<Advice> diagnose(BacktestResult result,
                                          TradingProperties.StrategyParams p,
                                          boolean isAggressive) {
        List<Advice> advices = new ArrayList<>();
        int total = result.getWins() + result.getLosses();
        if (total == 0) return advices;

        double winRate    = (double) result.getWins() / total * 100;
        double avgWin     = result.getWins() > 0
            ? result.getTrades().stream().filter(t -> t.pnlU > 0).mapToDouble(t -> t.pnlU).average().orElse(0)
            : 0;
        double avgLoss    = result.getLosses() > 0
            ? result.getTrades().stream().filter(t -> t.pnlU <= 0).mapToDouble(t -> Math.abs(t.pnlU)).average().orElse(0)
            : 0;
        double rrRatio    = avgLoss > 0 ? avgWin / avgLoss : 0;
        double drawdown   = result.getMaxDrawdown() * 100;
        int    maxConsec  = result.getMaxConsecLosses();
        long   slCount    = result.getTrades().stream().filter(t -> "sl".equals(t.exitReason)).count();
        double slRate     = (double) slCount / total * 100;

        double minSlPct   = isAggressive ? p.getAggMinSlPct()       : p.getConMinSlPct();
        double posStrong  = isAggressive ? p.getAggPositionStrong(): p.getConPositionBonus();
        double posWeak    = isAggressive ? p.getAggPositionWeak()  : p.getConPositionBase();
        String rrKey      = isAggressive ? "trading.params.agg-risk-reward-ratio" : "trading.params.con-risk-reward-ratio";
        String minSlKey   = isAggressive ? "trading.params.agg-min-sl-pct"        : "trading.params.con-min-sl-pct";
        String posStrongKey = isAggressive ? "trading.params.agg-position-strong" : "trading.params.con-position-bonus";
        String posWeakKey   = isAggressive ? "trading.params.agg-position-weak"   : "trading.params.con-position-base";
        String cooldownKey  = isAggressive ? "trading.params.agg-cooldown-ms"     : "trading.params.con-cooldown-ms";
        long   cooldownMs   = isAggressive ? p.getAggCooldownMs()  : p.getConCooldownMs();

        // ── ① 胜率过低：进场条件太松 ──────────────────────────────────
        if (winRate < 45.0) {
            advices.add(new Advice(
                "trading.params.volume-spike-multiplier",
                p.getVolumeSpikeMultiplier(),
                round2(p.getVolumeSpikeMultiplier() + 0.2),
                String.format("胜率仅 %.1f%%（<45%%），假信号过多，提高放量门槛可过滤低质量突破", winRate)
            ));
            if (p.getRangeThreshold() > 0.003) {
                advices.add(new Advice(
                    "trading.params.range-threshold",
                    p.getRangeThreshold(),
                    round4(p.getRangeThreshold() - 0.001),
                    String.format("胜率仅 %.1f%%，收紧横盘阈值可要求更严格的蓄力结构", winRate)
                ));
            }
        }

        // ── ② 胜率过高：条件太严，信号太少 ───────────────────────────
        if (winRate > 75.0 && total < 30) {
            advices.add(new Advice(
                "trading.params.volume-spike-multiplier",
                p.getVolumeSpikeMultiplier(),
                round2(Math.max(1.2, p.getVolumeSpikeMultiplier() - 0.1)),
                String.format("胜率 %.1f%% 但仅 %d 笔交易，条件过严导致错过行情，适当放松放量门槛", winRate, total)
            ));
        }

        // ── ③ 盈亏比不足：风险回报比太低 ─────────────────────────
        if (rrRatio < 1.2 && rrRatio > 0) {
            double currentRR = isAggressive ? p.getAggRiskRewardRatio() : p.getConRiskRewardRatio();
            advices.add(new Advice(
                rrKey,
                currentRR,
                round2(currentRR + 0.5),
                String.format("盈亏比仅 %.2f（<1.2），建议提高风险回报比", rrRatio)
            ));
        }

        // ── ④ 最大连续亏损过多：最小止损兜底太紧 ─────────────────────
        if (maxConsec > 8) {
            advices.add(new Advice(
                minSlKey,
                minSlPct,
                round4(minSlPct + 0.001),
                String.format("最大连续亏损 %d 次，止损过紧被正常波动频繁打掉，建议放宽最小止损兜底", maxConsec)
            ));
            advices.add(new Advice(
                posStrongKey,
                posStrong,
                round2(Math.max(0.10, posStrong - 0.05)),
                String.format("连续亏损 %d 次期间本金损耗严重，同步降低仓位以控制回撤", maxConsec)
            ));
        }

        // ── ⑤ 最大回撤过大：仓位过重 ─────────────────────────────────
        if (drawdown > 35.0) {
            advices.add(new Advice(
                posStrongKey,
                posStrong,
                round2(Math.max(0.10, posStrong - 0.05)),
                String.format("最大回撤 %.1f%%（>35%%），单次亏损过大，降低强信号仓位", drawdown)
            ));
            advices.add(new Advice(
                posWeakKey,
                posWeak,
                round2(Math.max(0.05, posWeak - 0.05)),
                String.format("最大回撤 %.1f%%，同步降低弱信号仓位", drawdown)
            ));
        }

        // ── ⑥ 信号太少：条件过于严格 ─────────────────────────────────
        if (total < 10) {
            advices.add(new Advice(
                "trading.params.range-threshold",
                p.getRangeThreshold(),
                round4(Math.min(0.015, p.getRangeThreshold() + 0.001)),
                String.format("3个月仅 %d 笔交易，信号极少，放松横盘阈值以增加进场机会", total)
            ));
            advices.add(new Advice(
                "trading.params.volume-spike-multiplier",
                p.getVolumeSpikeMultiplier(),
                round2(Math.max(1.2, p.getVolumeSpikeMultiplier() - 0.1)),
                String.format("3个月仅 %d 笔交易，适当降低放量门槛", total)
            ));
        }

        // ── ⑦ 信号太多：过滤不够 ─────────────────────────────────────
        if (total > 90) {
            advices.add(new Advice(
                "trading.params.volume-spike-multiplier",
                p.getVolumeSpikeMultiplier(),
                round2(p.getVolumeSpikeMultiplier() + 0.2),
                String.format("3个月 %d 笔交易（日均%.1f笔），信号过于频繁，提高放量门槛", total, total / 90.0)
            ));
            advices.add(new Advice(
                cooldownKey,
                cooldownMs,
                cooldownMs + 900000,
                String.format("信号过频，延长冷却时间 +15分钟（当前 %d 分钟）", cooldownMs / 60000)
            ));
        }

        // ── ⑧ 止损触发率过高：最小止损兜底太近 ───────────────────
        if (slRate > 70.0 && total >= 20) {
            advices.add(new Advice(
                minSlKey,
                minSlPct,
                round4(minSlPct + 0.001),
                String.format("止损触发率 %.1f%%（>70%%），止损位置过近，建议放宽最小止损兜底", slRate)
            ));
        }

        return advices;
    }

    /**
     * 将建议列表格式化为邮件文本
     */
    public static String formatAdvices(List<Advice> advices, String strategyName) {
        if (advices.isEmpty()) {
            return String.format("  ✓ %s 参数无需调整，继续观察\n", strategyName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  ⚠ %s 发现 %d 项参数问题，建议调整：\n", strategyName, advices.size()));
        sb.append("\n");

        for (int i = 0; i < advices.size(); i++) {
            Advice a = advices.get(i);
            // 判断值的显示格式（毫秒用整数，比例用小数）
            String currentStr  = a.paramKey.endsWith("-ms")
                ? String.format("%.0f（%d分钟）", a.currentValue, (long) a.currentValue / 60000)
                : String.format("%.4f", a.currentValue);
            String suggestStr  = a.paramKey.endsWith("-ms")
                ? String.format("%.0f（%d分钟）", a.suggestedValue, (long) a.suggestedValue / 60000)
                : String.format("%.4f", a.suggestedValue);

            sb.append(String.format("  [%d] %s\n", i + 1, a.paramKey));
            sb.append(String.format("      当前值: %s  →  建议值: %s\n", currentStr, suggestStr));
            sb.append(String.format("      原因  : %s\n", a.reason));
            sb.append("\n");
        }

        sb.append("  修改方法：编辑 application.yml 对应参数后重启服务\n");
        return sb.toString();
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
