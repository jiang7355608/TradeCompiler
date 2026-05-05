package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 策略路由器
 * 
 * 根据配置文件中的策略名称，选择并返回对应的策略实现。
 * 当前支持的策略：
 * - aggressive:     突破策略，追求趋势启动（趋势行情）
 * - mean-reversion: 均值回归策略，箱体震荡交易（震荡行情）
 * 
 * 设计原则：
 * - 策略通过 Spring 依赖注入管理
 * - 配置驱动的策略选择
 * - 清晰的错误处理和日志记录
 * 
 * 配置示例：
 * ```yaml
 * trading:
 *   strategy: aggressive      # 或 mean-reversion
 * ```
 * 
 * @author Trading System
 * @version 2.1 (Added MeanReversionStrategy)
 */
@Component
public class StrategyRouter {

    private static final Logger log = LoggerFactory.getLogger(StrategyRouter.class);

    private final TradingProperties properties;
    private final AggressiveStrategy aggressiveStrategy;
    private final MeanReversionStrategy meanReversionStrategy;

    /**
     * 构造策略路由器
     * 
     * @param properties 交易配置
     * @param aggressiveStrategy 突破策略实现
     * @param meanReversionStrategy 均值回归策略实现
     */
    public StrategyRouter(TradingProperties properties,
                         AggressiveStrategy aggressiveStrategy,
                         MeanReversionStrategy meanReversionStrategy) {
        this.properties = properties;
        this.aggressiveStrategy = aggressiveStrategy;
        this.meanReversionStrategy = meanReversionStrategy;
        
        log.info("策略路由器初始化完成，当前策略: {}", properties.getStrategy());
    }

    /**
     * 获取当前激活的策略
     * 
     * 根据配置文件中的 trading.strategy 设置返回对应的策略实现。
     * 
     * @return 当前策略实现
     * @throws IllegalArgumentException 如果配置的策略名称不支持
     */
    public Strategy current() {
        String strategyName = properties.getStrategy();
        
        return switch (strategyName) {
            case "aggressive" -> {
                log.debug("使用突破策略 (AggressiveStrategy)");
                yield aggressiveStrategy;
            }
            case "mean-reversion" -> {
                log.debug("使用均值回归策略 (MeanReversionStrategy)");
                yield meanReversionStrategy;
            }
            default -> {
                String errorMsg = String.format(
                    "不支持的策略: '%s'。当前支持的策略: aggressive, mean-reversion", 
                    strategyName);
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }
        };
    }

    /**
     * 获取策略名称
     * 
     * @return 当前配置的策略名称
     */
    public String getCurrentStrategyName() {
        return properties.getStrategy();
    }

    /**
     * 检查策略是否支持
     * 
     * @param strategyName 策略名称
     * @return true 如果策略被支持
     */
    public boolean isStrategySupported(String strategyName) {
        return "aggressive".equals(strategyName) || "mean-reversion".equals(strategyName);
    }

    /**
     * 获取支持的策略列表
     * 
     * @return 支持的策略名称数组
     */
    public String[] getSupportedStrategies() {
        return new String[]{"aggressive", "mean-reversion"};
    }
}
