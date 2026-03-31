package com.trading.signal.strategy;

import com.trading.signal.model.HtfRange;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;

/**
 * 交易策略接口
 * 所有策略实现此接口并注册为 Spring Bean，由 StrategyRouter 按配置选取
 */
public interface Strategy {

    /** 根据市场数据生成交易信号 */
    TradeSignal generateSignal(MarketData data);

    /** 根据市场数据 + 大周期箱体生成交易信号（默认忽略大周期） */
    default TradeSignal generateSignal(MarketData data, HtfRange htfRange) {
        return generateSignal(data);
    }

    /** 策略唯一名称，对应 application.yml 中 trading.strategy 的值 */
    String getName();
}
