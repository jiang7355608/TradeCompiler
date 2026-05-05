package com.trading.signal.strategy;

import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;

/**
 * 交易策略接口
 * 
 * 所有交易策略必须实现此接口，提供统一的信号生成方法。
 * 策略通过 Spring 容器管理，由 StrategyRouter 根据配置选择具体实现。
 * 
 * 设计原则：
 * - 策略只负责信号生成，不负责交易执行
 * - 策略应该是无状态的（除了必要的风控状态）
 * - 策略应该基于市场数据做出决策，不依赖外部状态
 * 
 * 实现策略：
 * - AggressiveStrategy：突破策略，追求趋势启动
 * 
 * @author Trading System
 * @version 2.0 (Simplified interface)
 */
public interface Strategy {
    
    /**
     * 根据市场数据生成交易信号
     * 
     * 策略分析市场数据，决定是否开仓以及开仓参数：
     * - 方向：做多/做空/不交易
     * - 入场价：建议的入场价格
     * - 止损价：风险控制价格
     * - 止盈价：盈利目标价格（可选）
     * - 仓位大小：占账户资金的比例
     * - 置信度：对信号质量的评估
     * 
     * @param data 市场数据，包含K线列表和技术指标
     * @return 交易信号，包含完整的交易参数
     */
    TradeSignal generateSignal(MarketData data);
    
    /**
     * 获取策略名称
     * 
     * 用于：
     * - 日志记录和调试
     * - 配置文件中的策略选择
     * - 回测报告中的策略标识
     * 
     * @return 策略名称，如 "aggressive"
     */
    String getName();
}