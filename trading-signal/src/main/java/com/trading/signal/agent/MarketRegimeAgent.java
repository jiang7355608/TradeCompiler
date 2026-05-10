package com.trading.signal.agent;

import com.trading.signal.agent.llm.LlmMessage;
import com.trading.signal.agent.llm.OpenRouterClient;
import com.trading.signal.agent.llm.ToolCallResult;
import com.trading.signal.agent.tool.AgentToolResult;
import com.trading.signal.agent.tool.SwitchStrategyTool;
import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 市场状态感知 Agent，每 8 小时调用一次大语言模型，
 * 由 LLM 自己分析市场数据并决定是否切换交易策略。
 * 
 * 整体流程：
 * 1. 拉取近 100 根 K 线
 * 2. 用 MarketRegimeAnalyzer 算出技术指标（EMA斜率、ATR、波动率等）
 * 3. 把数据整理成 prompt，通过 OpenRouterClient 发给 LLM
 * 4. 如果 LLM 调用了 switchStrategy 工具，执行对应的 SwitchStrategyTool
 * 5. 如果 LLM 不调用工具（认为不需要切换），什么都不做
 * 
 * 职责边界：
 * - 只读 OkxClient 行情数据、TradingProperties 配置
 * - 写入操作只通过 SwitchStrategyTool 进行（含完整校验链）
 * - 不注入任何交易相关组件
 */
@Component
public class MarketRegimeAgent {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeAgent.class);

    /** K 线数量，100 根 15 分钟 K 线约覆盖 25 小时 */
    private static final int KLINE_LIMIT = 100;

    private final OkxClient okxClient;
    private final MarketRegimeAnalyzer regimeAnalyzer;
    private final OpenRouterClient openRouterClient;
    private final SwitchStrategyTool switchStrategyTool;
    private final TradingProperties properties;

    public MarketRegimeAgent(OkxClient okxClient,
                              MarketRegimeAnalyzer regimeAnalyzer,
                              OpenRouterClient openRouterClient,
                              SwitchStrategyTool switchStrategyTool,
                              TradingProperties properties) {
        this.okxClient          = okxClient;
        this.regimeAnalyzer     = regimeAnalyzer;
        this.openRouterClient   = openRouterClient;
        this.switchStrategyTool = switchStrategyTool;
        this.properties         = properties;
        log.info("[MarketRegimeAgent] 初始化完成，模型: {}", properties.getAgent().getModel());
    }


    // ─────────────────────────────────────────────────────────────────────
    // 定时任务入口
    // ─────────────────────────────────────────────────────────────────────

    /** 每 8 小时（0:00 / 8:00 / 16:00 UTC）执行一次 */
    @Scheduled(cron = "0 0 0/8 * * *")
    public void analyzeAndDecide() {
        TradingProperties.Agent agentCfg = properties.getAgent();

        if (!agentCfg.isEnabled()) {
            log.debug("[MarketRegimeAgent] Agent 未启用，跳过");
            return;
        }
        if (!properties.getTradeApi().isEnabled()) {
            log.debug("[MarketRegimeAgent] 交易 API 未启用，跳过");
            return;
        }

        log.info("[MarketRegimeAgent] 开始市场分析，模型: {}", agentCfg.getModel());

        try {
            // 1. 拉取 K 线
            String instId    = properties.getOkx().getInstId();
            String timeframe = properties.getOkx().getTimeframe();
            List<KLine> klines = okxClient.fetchCandles(instId, timeframe, KLINE_LIMIT);

            if (klines == null || klines.size() < 60) {
                log.warn("[MarketRegimeAgent] K线不足（{}根），跳过本轮", klines == null ? 0 : klines.size());
                return;
            }

            // 2. 计算技术指标
            RegimeAnalysis analysis = regimeAnalyzer.analyze(klines);

            // 3. 构造发给 LLM 的消息
            List<LlmMessage> messages = buildMessages(instId, timeframe, klines, analysis);

            // 4. 调用 LLM
            Optional<ToolCallResult> toolCallOpt = openRouterClient.chat(messages);

            // 5. 执行工具（如果 LLM 决定调用）
            if (toolCallOpt.isPresent()) {
                ToolCallResult toolCall = toolCallOpt.get();
                handleToolCall(toolCall, analysis);
            } else {
                log.info("[MarketRegimeAgent] LLM 决定不切换策略，保持当前: {}", properties.getStrategy());
            }

        } catch (Exception e) {
            log.error("[MarketRegimeAgent] 执行异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发一次分析（只分析不执行，用于调试）。
     */
    public RegimeAnalysis analyzeOnly() {
        try {
            String instId    = properties.getOkx().getInstId();
            String timeframe = properties.getOkx().getTimeframe();
            List<KLine> klines = okxClient.fetchCandles(instId, timeframe, KLINE_LIMIT);
            if (klines == null || klines.isEmpty()) return RegimeAnalysis.unknown("无法获取 K 线");
            RegimeAnalysis result = regimeAnalyzer.analyze(klines);
            log.info("[MarketRegimeAgent] 手动分析: {}", result);
            return result;
        } catch (Exception e) {
            log.error("[MarketRegimeAgent] 手动分析失败: {}", e.getMessage(), e);
            return RegimeAnalysis.unknown("异常: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 私有方法
    // ─────────────────────────────────────────────────────────────────────

    private void handleToolCall(ToolCallResult toolCall, RegimeAnalysis analysis) {
        if (!"switchStrategy".equals(toolCall.toolName())) {
            log.warn("[MarketRegimeAgent] 收到未知工具调用: {}", toolCall.toolName());
            return;
        }

        String strategyName = toolCall.arg("strategyName");
        String reason       = toolCall.arg("reason");

        if (strategyName == null || strategyName.isBlank()) {
            log.warn("[MarketRegimeAgent] LLM 未提供 strategyName，忽略");
            return;
        }

        log.info("[MarketRegimeAgent] LLM 请求切换策略: {} 原因: {}", strategyName, reason);

        AgentToolResult<String> result = switchStrategyTool.execute(strategyName, reason);

        // 审计日志
        log.warn("[AUDIT] MarketRegimeAgent | status={} | llmRequested={} | toolResult={}",
                result.accepted() ? "ACCEPTED" : "REJECTED",
                strategyName,
                result.reason());
    }

    /**
     * 构造发给 LLM 的消息列表。
     * 
     * system prompt 解释系统背景和两种策略的适用场景；
     * user prompt 提供当前市场数据和技术指标。
     */
    private List<LlmMessage> buildMessages(String instId, String timeframe,
                                            List<KLine> klines, RegimeAnalysis analysis) {
        List<LlmMessage> messages = new ArrayList<>();

        messages.add(LlmMessage.system(buildSystemPrompt()));
        messages.add(LlmMessage.user(buildUserPrompt(instId, timeframe, klines, analysis)));

        return messages;
    }

    private String buildSystemPrompt() {
        return """
                你是一个加密货币量化交易系统的策略调度器。
                系统当前有两种交易策略，你需要根据市场状态决定是否切换：
                
                - aggressive（趋势突破）：适合趋势行情。靠突破前高/前低入场，用 Trailing Stop 持有趋势。
                  适用条件：EMA 方向一致，ATR 扩张，价格有明显单边方向。
                
                - mean-reversion（均值回归）：适合震荡行情。靠偏离 EMA 的程度判断超买/超卖入场。
                  适用条件：价格在区间内反复，ATR 偏低，没有明显趋势。
                
                决策规则：
                1. 只有当你对市场状态有足够信心时，才调用 switchStrategy 工具
                2. 如果市场状态不明确，或者数据不足以判断，不要调用任何工具
                3. 如果当前策略已经适合现在的市场，不要切换
                4. 高噪声行情（高波动但无方向）下，两种策略都不好，也不要切换
                """;
    }

    private String buildUserPrompt(String instId, String timeframe,
                                    List<KLine> klines, RegimeAnalysis analysis) {
        // 取最近 10 根收盘价
        int recentCount = Math.min(10, klines.size());
        String recentCloses = klines.subList(klines.size() - recentCount, klines.size())
                .stream()
                .map(k -> String.format("%.2f", k.getClose()))
                .collect(Collectors.joining(", "));

        KLine latest = klines.get(klines.size() - 1);

        return String.format("""
                交易对: %s  周期: %s  当前价格: %.2f
                当前策略: %s
                
                技术指标：
                - EMA 斜率（EMA20 vs EMA50）: %.4f  %s
                - 趋势强度: %.4f
                - 波动率（ATR/价格）: %.4f
                - ATR 扩张比（当前/历史均值）: %.2f  %s
                - 突破有效率（近30根）: %.2f
                
                近 %d 根收盘价（从旧到新）：
                %s
                
                综合判断参考：%s
                
                请分析市场当前处于什么状态，判断是否需要切换策略。
                """,
                instId, timeframe, latest.getClose(),
                properties.getStrategy(),
                analysis.emaSlope(), analysis.emaSlope() > 0 ? "（多头偏离）" : "（空头偏离）",
                analysis.trendStrength(),
                analysis.volatilityRatio(),
                analysis.atrRatio(), analysis.atrRatio() > 1.2 ? "（扩张）" : "（收缩）",
                analysis.breakoutSuccessRate(),
                recentCount, recentCloses,
                analysis.reasoning()
        );
    }
}
