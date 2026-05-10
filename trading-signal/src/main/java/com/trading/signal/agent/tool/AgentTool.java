package com.trading.signal.agent.tool;

/**
 * 工具接口，用于定义 Agent 可以执行的操作。
 * 
 * 每个工具都是一个独立的操作单元，内部包含必要的校验逻辑（Guardrail）。
 * Agent 通过调用工具来影响系统配置，而不是直接操作交易组件。
 * 
 * 允许的操作：
 * - 修改 TradingProperties.strategy（切换策略）
 * - 发送邮件通知
 * - 写入审计日志
 * 
 * 禁止的操作：
 * - 直接调用 OkxTradeClient 下单
 * - 修改止损/止盈、TrailingStop 状态、仓位大小
 * - 绕过 Kill Switch 等风控机制
 * 
 * 所有交易行为（开仓、平仓、止损）仍由确定性系统控制：
 * SignalService → Strategy → TradeExecutor → OkxTradeClient
 * 
 * @param <T> 工具执行成功时返回的结果类型
 */
public interface AgentTool<T> {

    /**
     * Tool 名称（用于日志、审计、Agent 决策记录）
     * 命名规范：camelCase，如 "switchStrategy"、"notifyHuman"
     *
     * @return Tool 名称
     */
    String name();

    /**
     * Tool 功能描述（用于 AI 提示词注入和文档）
     * 描述应包含：
     * - Tool 做了什么
     * - 参数期望值
     * - 可能的拒绝原因（Guardrail）
     *
     * @return Tool 功能描述
     */
    String description();

    /**
     * 执行 Tool（含 Guardrail 校验）
     *
     * 执行顺序（必须遵守）：
     *   1. 参数合法性校验
     *   2. Guardrail 条件校验（持仓/冷却/KillSwitch等）
     *   3. 执行副作用（修改配置/发送通知）
     *   4. 返回 AgentToolResult
     *
     * 注意：
     *   - 即使 Guardrail 拒绝，也不得抛出异常，应返回 rejected 结果
     *   - 调用方（MarketRegimeAgent）只能通过返回值判断执行结果
     *
     * @param args 执行参数（具体参数由实现类定义）
     * @return 执行结果（ACCEPTED 或 REJECTED，含原因）
     */
    AgentToolResult<T> execute(Object... args);
}
