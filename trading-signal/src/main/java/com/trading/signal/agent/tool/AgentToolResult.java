package com.trading.signal.agent.tool;

/**
 * 工具执行结果。
 * 
 * 记录每次工具调用的执行情况：
 * - accepted: 是否被接受执行
 * - value: 执行成功时的返回值（accepted=true 时有效）
 * - reason: 原因说明（成功时为描述，失败时为拒绝原因）
 * 
 * 设计目的：强制工具通过返回值表达结果，而不是抛出异常，
 * 使得上层可以统一处理成功和失败的场景，并完整记录审计日志。
 * 
 * 示例：
 * AgentToolResult<String> result = switchStrategyTool.execute("aggressive", "市场进入趋势");
 * if (result.accepted()) {
 *     log.info("策略已切换: {}", result.value());
 * } else {
 *     log.info("策略切换被拒绝: {}", result.reason());
 * }
 * 
 * @param <T> 返回值类型
 */
public record AgentToolResult<T>(

        /** true = Tool 执行成功；false = Guardrail 拒绝或参数非法 */
        boolean accepted,

        /** 执行结果值（accepted=true 时有效，rejected 时为 null） */
        T value,

        /** 原因说明（accepted 时为成功描述；rejected 时为明确的拒绝原因） */
        String reason

) {

    /**
     * 工厂方法：构建 ACCEPTED 结果
     *
     * @param value  执行结果值
     * @param reason 成功说明
     * @param <T>    结果值类型
     * @return ACCEPTED 结果
     */
    public static <T> AgentToolResult<T> accepted(T value, String reason) {
        return new AgentToolResult<>(true, value, reason);
    }

    /**
     * 工厂方法：构建 REJECTED 结果（Guardrail 拦截）
     *
     * @param reason 拒绝原因（需要明确，供审计日志使用）
     * @param <T>    结果值类型（rejected 时值为 null）
     * @return REJECTED 结果
     */
    public static <T> AgentToolResult<T> rejected(String reason) {
        return new AgentToolResult<>(false, null, reason);
    }

    /**
     * 是否被 Guardrail 拒绝（语义化方法，与 !accepted() 等价）
     *
     * @return true = 被拒绝
     */
    public boolean rejected() {
        return !accepted;
    }

    @Override
    public String toString() {
        return accepted
                ? String.format("AgentToolResult{ACCEPTED, value=%s, reason='%s'}", value, reason)
                : String.format("AgentToolResult{REJECTED, reason='%s'}", reason);
    }
}
