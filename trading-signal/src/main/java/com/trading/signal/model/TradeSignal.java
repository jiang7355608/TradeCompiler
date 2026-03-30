package com.trading.signal.model;

/**
 * 策略引擎输出的交易信号
 *
 * 新增字段：
 *   stopLoss   - 止损价格，由策略根据 currentPrice 计算，执行层直接使用
 *   takeProfit - 止盈价格，同上
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
