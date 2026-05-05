package com.trading.signal.model;

/**
 * 交易信号
 * 
 * 策略引擎输出的完整交易信号，包含交易方向、仓位大小、止损止盈等参数。
 * 
 * 核心字段说明：
 * - action: 交易方向（LONG/SHORT/NO_TRADE）
 * - reason: 信号原因，用于日志和调试
 * - confidence: 信号置信度（0.0~1.0），影响实际仓位大小
 * - positionSize: 基础仓位比例（0.0~1.0），占本金的百分比
 * - stopLoss: 止损价格，由策略根据市场状态动态计算
 * - takeProfit: 止盈价格，0表示使用追踪止损
 * 
 * 置信度机制：
 * 实际仓位 = positionSize × confidence
 * 
 * 示例：
 * - 试探仓：positionSize=0.10, confidence=0.60 → 实际仓位=6%
 * - 确认仓：positionSize=0.30, confidence=0.85 → 实际仓位=25.5%
 * 
 * 设计理念：
 * - 高质量信号 → 高置信度 → 大仓位
 * - 低质量信号 → 低置信度 → 小仓位
 * - 风险自适应，避免固定仓位的过度暴露
 * 
 * @author Trading System
 * @version 2.0 (Enhanced documentation)
 */
public class TradeSignal {

    /**
     * 交易方向枚举
     */
    public enum Action {
        /** 做多（买入） */
        LONG("long"),
        
        /** 做空（卖出） */
        SHORT("short"),
        
        /** 无交易信号 */
        NO_TRADE("no_trade");

        private final String value;
        
        Action(String value) { 
            this.value = value; 
        }
        
        public String getValue() { 
            return value; 
        }
    }

    private final Action action;        // 交易方向
    private final String reason;        // 信号原因
    private final double confidence;    // 信号置信度（0.0~1.0）
    private final double positionSize;  // 基础仓位比例（0.0~1.0）
    private final double stopLoss;      // 止损价格
    private final double takeProfit;    // 止盈价格（0表示使用追踪止损）

    /**
     * 构造完整的交易信号（用于有效信号）
     * 
     * @param action 交易方向
     * @param reason 信号原因，用于日志记录
     * @param confidence 信号置信度（0.0~1.0）
     * @param positionSize 基础仓位比例（0.0~1.0）
     * @param stopLoss 止损价格
     * @param takeProfit 止盈价格（0表示使用追踪止损）
     * @throws IllegalArgumentException 如果参数超出有效范围
     */
    public TradeSignal(Action action, String reason, double confidence,
                       double positionSize, double stopLoss, double takeProfit) {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 0.0~1.0 之间");
        }
        if (positionSize < 0.0 || positionSize > 1.0) {
            throw new IllegalArgumentException("positionSize 必须在 0.0~1.0 之间");
        }
        
        this.action = action;
        this.reason = reason;
        this.confidence = confidence;
        this.positionSize = positionSize;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
    }

    /**
     * 构造无交易信号（快捷方式）
     * 
     * @param action 交易方向（通常是 NO_TRADE）
     * @param reason 无交易的原因
     */
    public TradeSignal(Action action, String reason) {
        this(action, reason, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * 获取交易方向
     * 
     * @return 交易方向枚举
     */
    public Action getAction() { 
        return action; 
    }

    /**
     * 获取信号原因
     * 
     * @return 信号原因描述
     */
    public String getReason() { 
        return reason; 
    }

    /**
     * 获取信号置信度
     * 
     * @return 置信度（0.0~1.0）
     */
    public double getConfidence() { 
        return confidence; 
    }

    /**
     * 获取基础仓位比例
     * 
     * @return 仓位比例（0.0~1.0）
     */
    public double getPositionSize() { 
        return positionSize; 
    }

    /**
     * 获取止损价格
     * 
     * @return 止损价格
     */
    public double getStopLoss() { 
        return stopLoss; 
    }

    /**
     * 获取止盈价格
     * 
     * @return 止盈价格（0表示使用追踪止损）
     */
    public double getTakeProfit() { 
        return takeProfit; 
    }

    /**
     * 计算实际仓位大小
     * 
     * @return 实际仓位 = positionSize × confidence
     */
    public double getActualPositionSize() {
        return positionSize * confidence;
    }

    /**
     * 判断是否为有效交易信号
     * 
     * @return true 如果是 LONG 或 SHORT 信号
     */
    public boolean isValidTrade() {
        return action == Action.LONG || action == Action.SHORT;
    }

    @Override
    public String toString() {
        if (action == Action.NO_TRADE) {
            return String.format("TradeSignal{action='%s', reason='%s'}", 
                action.getValue(), reason);
        }
        
        return String.format(
            "TradeSignal{action='%s', confidence=%.2f, position=%.1f%%, " +
            "actualPosition=%.1f%%, sl=%.2f, tp=%.2f, reason='%s'}",
            action.getValue(), confidence, positionSize * 100, 
            getActualPositionSize() * 100, stopLoss, takeProfit, reason);
    }
}