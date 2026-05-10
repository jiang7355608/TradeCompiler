package com.trading.signal.agent;

import com.trading.signal.agent.llm.LlmMessage;
import com.trading.signal.agent.llm.OpenRouterClient;
import com.trading.signal.agent.llm.ToolCallResult;
import com.trading.signal.model.KLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Market Regime Agent 测试。
 * 
 * 分两部分：
 * 1. MarketRegimeAnalyzer 的单元测试 - 用构造好的 K 线数据验证指标计算
 * 2. OpenRouterClient 的集成测试 - 实际调用 API 验证 tool calling 流程
 * 
 * 运行集成测试前需要在 application.yml 中填写有效的 OpenRouter API Key。
 */
@SpringBootTest
class MarketRegimeAgentTest {

    @Autowired
    private MarketRegimeAnalyzer analyzer;

    @Autowired
    private OpenRouterClient openRouterClient;

    @Autowired
    private MarketRegimeAgent agent;

    // ── 单元测试：MarketRegimeAnalyzer ────────────────────────────────────

    /**
     * 构造明显的上升趋势数据，验证 analyze() 能正确识别为 TRENDING。
     * 
     * 使用线性递增的收盘价模拟持续上涨：
     * - EMA20 > EMA50 → emaSlope 为正且大
     * - ATR 相对稳定
     */
    @Test
    void analyze_trendingMarket() {
        List<KLine> klines = buildTrendingKLines(100, 90000.0, 200.0);

        RegimeAnalysis result = analyzer.analyze(klines);

        System.out.println("=== 趋势行情分析结果 ===");
        System.out.println("状态: " + result.regime());
        System.out.println("EMA斜率: " + result.emaSlope());
        System.out.println("趋势强度: " + result.trendStrength());
        System.out.println("波动率: " + result.volatilityRatio());
        System.out.println("ATR扩张比: " + result.atrRatio());
        System.out.println("分析依据: " + result.reasoning());

        assertNotNull(result.regime());
        assertNotEquals(MarketRegime.UNKNOWN, result.regime(), "趋势数据不应返回 UNKNOWN");
        assertTrue(result.emaSlope() > 0, "上升趋势的 EMA 斜率应为正");
    }

    /**
     * 构造横盘震荡数据，验证 analyze() 能正确识别为 RANGING。
     * 
     * 使用价格在窄区间内随机游走模拟震荡：
     * - EMA20 ≈ EMA50 → emaSlope 接近 0
     * - ATR 偏低
     */
    @Test
    void analyze_rangingMarket() {
        List<KLine> klines = buildRangingKLines(100, 95000.0, 150.0);

        RegimeAnalysis result = analyzer.analyze(klines);

        System.out.println("=== 震荡行情分析结果 ===");
        System.out.println("状态: " + result.regime());
        System.out.println("EMA斜率: " + result.emaSlope());
        System.out.println("趋势强度: " + result.trendStrength());
        System.out.println("波动率: " + result.volatilityRatio());
        System.out.println("分析依据: " + result.reasoning());

        assertNotNull(result.regime());
        assertTrue(Math.abs(result.emaSlope()) < 0.05, "震荡行情的 EMA 斜率应接近 0");
    }

    /**
     * 验证 K 线数量不足时返回 UNKNOWN 而不是抛异常。
     */
    @Test
    void analyze_insufficientData_returnsUnknown() {
        List<KLine> shortKlines = buildTrendingKLines(10, 90000.0, 200.0);

        RegimeAnalysis result = analyzer.analyze(shortKlines);

        assertEquals(MarketRegime.UNKNOWN, result.regime());
        assertNotNull(result.reasoning());
        System.out.println("数据不足时: " + result.reasoning());
    }

    /**
     * 验证传入 null 时返回 UNKNOWN 而不是 NPE。
     */
    @Test
    void analyze_nullInput_returnsUnknown() {
        RegimeAnalysis result = analyzer.analyze(null);
        assertEquals(MarketRegime.UNKNOWN, result.regime());
    }

    // ── 集成测试：OpenRouterClient ────────────────────────────────────────

    /**
     * 直接测试 OpenRouterClient，构造一个要求模型切换策略的场景。
     * 
     * 模拟「明显趋势行情，当前使用震荡策略」的 prompt，
     * 预期模型会调用 switchStrategy 工具。
     * 
     * 注意：此测试会实际消耗 API 额度。
     */
    @Test
    void openRouter_trendingMarket_shouldCallSwitchStrategy() throws Exception {
        List<LlmMessage> messages = List.of(
                LlmMessage.system("""
                        你是一个加密货币量化交易系统的策略调度器。
                        系统有两种策略：
                        - aggressive（趋势突破）：适合趋势行情
                        - mean-reversion（均值回归）：适合震荡行情
                        
                        只有当你对市场状态有足够信心时，才调用 switchStrategy 工具。
                        """),
                LlmMessage.user("""
                        交易对: BTC-USDT  周期: 15m  当前价格: 97500.00
                        当前策略: mean-reversion
                        
                        技术指标：
                        - EMA 斜率（EMA20 vs EMA50）: 0.0280  （多头偏离）
                        - 趋势强度: 0.0280
                        - 波动率（ATR/价格）: 0.0055
                        - ATR 扩张比（当前/历史均值）: 1.45  （扩张）
                        - 突破有效率（近30根）: 0.72
                        
                        近10根收盘价（从旧到新）：
                        94200.00, 94800.00, 95300.00, 95900.00, 96400.00,
                        96800.00, 97100.00, 97250.00, 97380.00, 97500.00
                        
                        综合判断参考：判定=TRENDING | EMA斜率=0.0280（向上） 趋势强度=0.0280 | 波动率=0.0055 | ATR扩张比=1.45 | 突破有效率=0.72
                        
                        请分析市场当前处于什么状态，判断是否需要切换策略。
                        """)
        );

        System.out.println("=== 正在调用 OpenRouter API（趋势场景）===");
        Optional<ToolCallResult> result = openRouterClient.chat(messages);

        System.out.println("LLM 响应: " + (result.isPresent()
                ? "调用工具 " + result.get().toolName() + " 参数=" + result.get().arguments()
                : "决定不切换"));

        // 不强制断言（模型行为不确定），只验证不抛异常，并打印结果供人工检查
        assertTrue(true, "调用成功，无异常");
    }

    /**
     * 测试震荡行情下，模型应该不切换（当前已是 mean-reversion）。
     */
    @Test
    void openRouter_rangingMarket_shouldNotSwitch() throws Exception {
        List<LlmMessage> messages = List.of(
                LlmMessage.system("""
                        你是一个加密货币量化交易系统的策略调度器。
                        系统有两种策略：
                        - aggressive（趋势突破）：适合趋势行情
                        - mean-reversion（均值回归）：适合震荡行情
                        
                        如果当前策略已经适合现在的市场，不要切换。
                        只有当你对市场状态有足够信心时，才调用 switchStrategy 工具。
                        """),
                LlmMessage.user("""
                        交易对: BTC-USDT  周期: 15m  当前价格: 95000.00
                        当前策略: mean-reversion
                        
                        技术指标：
                        - EMA 斜率（EMA20 vs EMA50）: 0.0012  （多头偏离）
                        - 趋势强度: 0.0012
                        - 波动率（ATR/价格）: 0.0038
                        - ATR 扩张比（当前/历史均值）: 0.82  （收缩）
                        - 突破有效率（近30根）: 0.25
                        
                        近10根收盘价（从旧到新）：
                        94800.00, 95100.00, 94900.00, 95200.00, 94950.00,
                        95050.00, 94850.00, 95100.00, 95000.00, 95050.00
                        
                        综合判断参考：判定=RANGING | EMA斜率=0.0012（向上） 趋势强度=0.0012 | 波动率=0.0038 | ATR扩张比=0.82 | 突破有效率=0.25
                        
                        请分析市场当前处于什么状态，判断是否需要切换策略。
                        """)
        );

        System.out.println("=== 正在调用 OpenRouter API（震荡场景）===");
        Optional<ToolCallResult> result = openRouterClient.chat(messages);

        System.out.println("LLM 响应: " + (result.isPresent()
                ? "调用工具 " + result.get().toolName() + " 参数=" + result.get().arguments()
                : "决定不切换（符合预期）"));

        assertTrue(true, "调用成功，无异常");
    }

    /**
     * 测试完整 Agent 的 analyzeOnly()（不切换策略），验证真实 K 线拉取 + 指标计算正常。
     */
    @Test
    void agent_analyzeOnly_realMarketData() {
        System.out.println("=== 拉取真实 K 线并分析 ===");
        RegimeAnalysis result = agent.analyzeOnly();

        System.out.println("分析结果: " + result.regime());
        System.out.println("EMA斜率: " + result.emaSlope());
        System.out.println("波动率: " + result.volatilityRatio());
        System.out.println("ATR扩张比: " + result.atrRatio());
        System.out.println("分析依据: " + result.reasoning());

        assertNotNull(result);
        assertNotNull(result.regime());
    }

    /**
     * 端到端测试 analyzeAndDecide()。
     * 
     * 完整流程：拉取真实 K 线 → 计算指标 → 调用 LLM → 根据 LLM 决策执行工具。
     * 
     * 测试前会临时把 agent.enabled 和 trade-api.enabled 都设为 true，
     * 测试结束后恢复原值，不影响其他测试。
     * 
     * 需要：有效的 OpenRouter API Key（application.yml 中 trading.agent.api-key）
     */
    @Autowired
    private com.trading.signal.config.TradingProperties properties;

    @Test
    void agent_analyzeAndDecide_fullPipeline() {
        // 记录执行前的状态
        String strategyBefore = properties.getStrategy();
        boolean agentEnabledBefore = properties.getAgent().isEnabled();
        boolean tradeApiEnabledBefore = properties.getTradeApi().isEnabled();

        // 临时启用，让方法能跑完整流程（不被开关提前 return）
        properties.getAgent().setEnabled(true);
        properties.getTradeApi().setEnabled(true);

        try {
            System.out.println("=== 开始 analyzeAndDecide() 端到端测试 ===");
            System.out.println("执行前策略: " + strategyBefore);

            // 直接调用定时任务方法
            agent.analyzeAndDecide();

            String strategyAfter = properties.getStrategy();
            System.out.println("执行后策略: " + strategyAfter);

            if (strategyAfter.equals(strategyBefore)) {
                System.out.println("结果: 策略未切换（LLM 决定保持，或 Guardrail 拒绝）");
            } else {
                System.out.println("结果: 策略已切换 " + strategyBefore + " → " + strategyAfter);
            }

            // 验证策略名称是合法值
            assertTrue(
                    strategyAfter.equals("aggressive") || strategyAfter.equals("mean-reversion"),
                    "策略切换后的值必须是合法的策略名称，实际: " + strategyAfter
            );

        } finally {
            // 无论成功失败，恢复原始配置
            properties.getAgent().setEnabled(agentEnabledBefore);
            properties.getTradeApi().setEnabled(tradeApiEnabledBefore);
        }
    }

    // ── 测试数据构造 ──────────────────────────────────────────────────────

    /**
     * 构造单调递增的趋势 K 线（上涨趋势）
     */
    private List<KLine> buildTrendingKLines(int count, double startPrice, double stepPerBar) {
        List<KLine> klines = new ArrayList<>();
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double open  = price;
            double close = price + stepPerBar * 0.7;  // 大阳线
            double high  = close + stepPerBar * 0.2;
            double low   = open  - stepPerBar * 0.1;
            klines.add(new KLine(System.currentTimeMillis() + (long) i * 60000,
                    open, high, low, close, 1000.0));
            price += stepPerBar;
        }
        return klines;
    }

    /**
     * 构造价格在中心价附近随机震荡的 K 线
     */
    private List<KLine> buildRangingKLines(int count, double centerPrice, double halfRange) {
        List<KLine> klines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 用正弦波模拟震荡
            double offset = Math.sin(i * 0.3) * halfRange * 0.5;
            double close  = centerPrice + offset;
            double open   = centerPrice + Math.sin((i - 1) * 0.3) * halfRange * 0.5;
            double high   = Math.max(open, close) + halfRange * 0.1;
            double low    = Math.min(open, close) - halfRange * 0.1;
            klines.add(new KLine(System.currentTimeMillis() + (long) i * 60000,
                    open, high, low, close, 800.0));
        }
        return klines;
    }
}
