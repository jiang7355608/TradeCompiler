package com.trading.signal.model;

/**
 * 策略引擎输出的交易信号
 *
 * 核心字段说明：
 *   action       - 交易方向：LONG（做多）/ SHORT（做空）/ NO_TRADE（无信号）
 *   reason       - 信号原因，用于日志和调试
 *   confidence   - 信号置信度（0.0 ~ 1.0），影响实际仓位大小
 *   positionSize - 基础仓位比例（0.0 ~ 1.0），占本金的百分比
 *   stopLoss     - 止损价格，由策略根据市场状态动态计算
 *   takeProfit   - 止盈价格，同上
 *
 * 置信度的作用：
 *   实际仓位 = positionSize × confidence
 *   
 *   示例1（试探仓）：
 *     positionSize = 0.10 (10%本金)
 *     confidence   = 0.60 (60%置信度)
 *     实际仓位     = 0.10 × 0.60 = 0.06 (6%本金)
 *   
 *   示例2（加仓）：
 *     positionSize = 0.30 (30%本金)
 *     confidence   = 0.85 (85%置信度)
 *     实际仓位     = 0.30 × 0.85 = 0.255 (25.5%本金)
 *   
 *   设计理念：
 *     - 高质量信号（浮盈达标、持仓时间充足）→ 高置信度 → 大仓位
 *     - 低质量信号（试探性入场）→ 低置信度 → 小仓位
 *     - 风险自适应，避免固定仓位导致的过度暴露
 *
 * 止损止盈由策略负责计算的原因：
 *   策略知道自己的风险偏好（激进 vs 保守），止损幅度应该由策略决定，
 *   而不是由 EmailService 这种通知层来算，避免逻辑分散。
 */
public class TradeSignal {

    public enum Action {
        LONG("long"),
        SHORT("short"),
        NO_TRADE("no_trade");

        private final String value;
        Action(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    private final Action action;
    private final String reason;
    private final double confidence;    // 0.0 ~ 1.0
    private final double positionSize;  // 0.0 ~ 1.0，建议仓位比例
    private final double stopLoss;      // 止损价格（0 表示无信号，不适用）
    private final double takeProfit;    // 止盈价格（0 表示无信号，不适用）

    /**
     * 完整构造（有效信号使用）
     */
    public TradeSignal(Action action, String reason, double confidence,
                       double positionSize, double stopLoss, double takeProfit) {
        if (confidence < 0.0 || confidence > 1.0)
            throw new IllegalArgumentException("confidence 必须在 0.0 ~ 1.0 之间");
        if (positionSize < 0.0 || positionSize > 1.0)
            throw new IllegalArgumentException("positionSize 必须在 0.0 ~ 1.0 之间");
        this.action       = action;
        this.reason       = reason;
        this.confidence   = confidence;
        this.positionSize = positionSize;
        this.stopLoss     = stopLoss;
        this.takeProfit   = takeProfit;
    }

    /**
     * NO_TRADE 快捷构造（所有数值字段为 0）
     */
    public TradeSignal(Action action, String reason) {
        this(action, reason, 0.0, 0.0, 0.0, 0.0);
    }

    public Action getAction()       { return action; }
    public String getReason()       { return reason; }
    public double getConfidence()   { return confidence; }
    public double getPositionSize() { return positionSize; }
    public double getStopLoss()     { return stopLoss; }
    public double getTakeProfit()   { return takeProfit; }

    @Override
    public String toString() {
        return String.format(
            "TradeSignal{action='%s', confidence=%.2f, position=%.0f%%, sl=%.2f, tp=%.2f, reason='%s'}",
            action.getValue(), confidence, positionSize * 100, stopLoss, takeProfit, reason);
    }
}
