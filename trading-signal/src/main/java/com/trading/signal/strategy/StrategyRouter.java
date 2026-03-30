package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 策略路由器（替代原来的静态 StrategyFactory）
 *
 * Spring 启动时自动收集所有 Strategy Bean，构建 name→strategy 映射。
 * 新增策略只需实现 Strategy 接口并加 @Component，无需修改此类。
 *
 * 当前激活策略由 application.yml 中 trading.strategy 决定，
 * 运行时可通过 /api/strategy 接口热切换，无需重启。
 */
@Component
public class StrategyRouter {

    private static final Logger log = LoggerFactory.getLogger(StrategyRouter.class);

    private final Map<String, Strategy> strategyMap;
    private final TradingProperties     properties;

    public StrategyRouter(List<Strategy> strategies, TradingProperties properties) {
        this.properties  = properties;
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(Strategy::getName, Function.identity()));
        log.info("已注册策略: {}", strategyMap.keySet());
    }

    /**
     * 返回当前配置激活的策略实例
     */
    public Strategy current() {
        String name = properties.getStrategy();
        Strategy strategy = strategyMap.get(name.toLowerCase());
        if (strategy == null) {
            throw new IllegalStateException(
                String.format("未知策略: '%s'，可用策略: %s", name, strategyMap.keySet()));
        }
        return strategy;
    }

    /**
     * 热切换策略（修改内存中的配置，无需重启）
     *
     * @param name 策略名称
     */
    public void switchStrategy(String name) {
        if (!strategyMap.containsKey(name.toLowerCase())) {
            throw new IllegalArgumentException(
                String.format("未知策略: '%s'，可用策略: %s", name, strategyMap.keySet()));
        }
        properties.setStrategy(name.toLowerCase());
        log.info("策略已切换为: {}", name);
    }

    /** 返回所有已注册的策略名称 */
    public java.util.Set<String> availableStrategies() {
        return java.util.Collections.unmodifiableSet(strategyMap.keySet());
    }
}
