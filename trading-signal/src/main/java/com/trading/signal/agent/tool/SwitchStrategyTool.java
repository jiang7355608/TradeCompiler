package com.trading.signal.agent.tool;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.service.EmailService;
import com.trading.signal.service.TrailingStopMonitor;
import com.trading.signal.strategy.MeanReversionStrategy;
import com.trading.signal.strategy.StrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 策略切换工具。
 * 
 * 用于自动切换当前使用的交易策略。内部包含完整的校验链（Guardrail），
 * 确保所有切换请求都经过严格检查，防止在不合适的时机切换策略。
 * 
 * 校验链（按顺序执行，任一失败则拒绝切换）：
 * 
 * 1. 参数合法性
 *    目标策略必须是 StrategyRouter 支持的策略名称。
 *    防止传入不存在的策略名。
 * 
 * 2. 相同策略检查
 *    如果当前策略已经是目标策略，直接拒绝（no-op）。
 *    避免无意义的切换操作，防止重置策略内部状态。
 * 
 * 3. AggressiveStrategy 持仓检查
 *    TrailingStopMonitor 报告有活跃持仓时，拒绝切换。
 *    防止在 Trailing Stop 监控期间切换策略，导致持仓孤立。
 * 
 * 4. MeanReversionStrategy 持仓检查
 *    MeanReversionStrategy 报告有活跃持仓时，拒绝切换。
 *    防止箱体策略持仓期间切换，导致持仓脱离管理。
 * 
 * 5. 切换冷却期（4小时）
 *    上次切换成功后 4 小时内不允许再次切换。
 *    防止频繁切换策略，影响交易连续性。
 * 
 * 调用示例：
 * <pre>
 * AgentToolResult<String> result = switchStrategyTool.execute(
 *     "mean-reversion",             // 目标策略名称
 *     "ATR收窄，市场进入震荡行情"    // 切换理由（用于邮件通知）
 * );
 * </pre>
 * 
 * args[0]  String  目标策略名称（"aggressive" 或 "mean-reversion"）
 * args[1]  String  切换理由（可选，默认为 "自动策略切换"）
 */
@Component
public class SwitchStrategyTool implements AgentTool<String> {

    private static final Logger log = LoggerFactory.getLogger(SwitchStrategyTool.class);

    /** 切换冷却期（4小时）：防止 AI 频繁切换策略 */
    private static final long SWITCH_COOLDOWN_MS = 4L * 60 * 60 * 1000;

    private final TradingProperties properties;
    private final StrategyRouter strategyRouter;
    private final TrailingStopMonitor trailingStopMonitor;
    private final MeanReversionStrategy meanReversionStrategy;
    private final EmailService emailService;

    /** 上次成功切换的时间戳（程序生命周期内持久） */
    private volatile long lastSwitchTimeMs = 0L;

    public SwitchStrategyTool(TradingProperties properties,
                               StrategyRouter strategyRouter,
                               TrailingStopMonitor trailingStopMonitor,
                               MeanReversionStrategy meanReversionStrategy,
                               EmailService emailService) {
        this.properties = properties;
        this.strategyRouter = strategyRouter;
        this.trailingStopMonitor = trailingStopMonitor;
        this.meanReversionStrategy = meanReversionStrategy;
        this.emailService = emailService;
    }

    @Override
    public String name() {
        return "switchStrategy";
    }

    @Override
    public String description() {
        return """
                请求切换交易策略。
                参数：
                  args[0] String 目标策略名称（"aggressive" 或 "mean-reversion"）
                  args[1] String 切换理由（可选）
                拒绝条件：
                  - 不支持的策略名称
                  - 已经是目标策略（无需切换）
                  - AggressiveStrategy 有活跃持仓
                  - MeanReversionStrategy 有活跃持仓
                  - 切换冷却期内（4小时）
                """;
    }

    /**
     * 执行策略切换（含完整 Guardrail 链）
     *
     * @param args args[0]=目标策略名, args[1]=切换理由（可选）
     * @return ACCEPTED（切换成功）或 REJECTED（含拒绝原因）
     */
    @Override
    public AgentToolResult<String> execute(Object... args) {

        // ── 参数解析 ─────────────────────────────────────────────────
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return AgentToolResult.rejected("参数错误：须提供目标策略名称（String）");
        }
        String requestedStrategy = (String) args[0];
        String reason = (args.length > 1 && args[1] instanceof String s) ? s : "AI Agent 请求切换";

        // ── Guardrail 1: 策略名称合法性 ──────────────────────────────
        if (!strategyRouter.isStrategySupported(requestedStrategy)) {
            String msg = String.format("不支持的策略名称: '%s'，支持的策略: %s",
                    requestedStrategy,
                    String.join(", ", strategyRouter.getSupportedStrategies()));
            log.warn("[SwitchStrategyTool] REJECTED - {}", msg);
            return AgentToolResult.rejected(msg);
        }

        // ── Guardrail 2: 相同策略 no-op ───────────────────────────────
        String currentStrategy = properties.getStrategy();
        if (currentStrategy.equals(requestedStrategy)) {
            String msg = String.format("已经是目标策略（%s），无需切换", requestedStrategy);
            log.info("[SwitchStrategyTool] REJECTED (no-op) - {}", msg);
            return AgentToolResult.rejected(msg);
        }

        // ── Guardrail 3: AggressiveStrategy 活跃持仓检查 ─────────────
        if (trailingStopMonitor.hasActivePosition()) {
            String msg = "AggressiveStrategy 存在活跃持仓（Trailing Stop 监控中），拒绝切换";
            log.warn("[SwitchStrategyTool] REJECTED - {}", msg);
            return AgentToolResult.rejected(msg);
        }

        // ── Guardrail 4: MeanReversionStrategy 活跃持仓检查 ──────────
        if (meanReversionStrategy.hasPosition()) {
            String msg = "MeanReversionStrategy 存在活跃持仓，拒绝切换";
            log.warn("[SwitchStrategyTool] REJECTED - {}", msg);
            return AgentToolResult.rejected(msg);
        }

        // ── Guardrail 5: 切换冷却期（4小时）─────────────────────────
        long now = System.currentTimeMillis();
        long elapsed = now - lastSwitchTimeMs;
        if (lastSwitchTimeMs > 0 && elapsed < SWITCH_COOLDOWN_MS) {
            long remainingSec = (SWITCH_COOLDOWN_MS - elapsed) / 1000;
            String msg = String.format("切换冷却中，剩余 %d 分 %d 秒",
                    remainingSec / 60, remainingSec % 60);
            log.warn("[SwitchStrategyTool] REJECTED - {}", msg);
            return AgentToolResult.rejected(msg);
        }

        // ── 所有 Guardrail 通过，执行切换 ────────────────────────────
        properties.setStrategy(requestedStrategy);
        lastSwitchTimeMs = now;

        String successMsg = String.format("策略已切换: %s → %s（原因: %s）",
                currentStrategy, requestedStrategy, reason);
        log.warn("[SwitchStrategyTool] ACCEPTED - {}", successMsg);

        // 发送邮件通知
        sendSwitchNotification(currentStrategy, requestedStrategy, reason, now);

        return AgentToolResult.accepted(requestedStrategy, successMsg);
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有方法
    // ═══════════════════════════════════════════════════════════════

    private void sendSwitchNotification(String from, String to, String reason, long timeMs) {
        try {
            String timeStr = Instant.ofEpochMilli(timeMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            String subject = String.format("[AI Agent] 策略切换: %s → %s", from, to);
            String body = String.format(
                    "===== AI Market Regime Agent 策略切换通知 =====\n\n" +
                    "切换时间: %s\n" +
                    "旧策略  : %s\n" +
                    "新策略  : %s\n" +
                    "切换原因: %s\n\n" +
                    "【说明】\n" +
                    "本次切换由 AI Market Regime Agent 自动触发，\n" +
                    "已通过所有 Guardrail 校验（无持仓、无冷却限制）。\n\n" +
                    "新策略将在下一个 15 分钟 K 线周期生效。\n\n" +
                    "===================================================",
                    timeStr, from, to, reason
            );

            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("[SwitchStrategyTool] 发送切换通知邮件失败: {}", e.getMessage());
        }
    }

    /**
     * 查询上次切换时间（供 Agent 决策层查询冷却状态）
     *
     * @return 上次切换成功的 Unix 毫秒时间戳，0 表示从未切换
     */
    public long getLastSwitchTimeMs() {
        return lastSwitchTimeMs;
    }
}
