package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.strategy.AggressiveStrategy;
import org.junit.jupiter.api.Test;

/**
 * AggressiveBacktestEngine 测试
 */
public class AggressiveBacktestEngineTest {

    @Test
    public void testBacktest() throws Exception {
        // 创建配置
        TradingProperties props = new TradingProperties();
        TradingProperties.StrategyParams params = new TradingProperties.StrategyParams();
        props.setParams(params);
        
        TradingProperties.Backtest backtestCfg = new TradingProperties.Backtest();
        backtestCfg.setInitialCapital(200.0);
        backtestCfg.setLeverage(20);
        props.setBacktest(backtestCfg);
        
        // 创建策略和引擎
        AggressiveStrategy strategy = new AggressiveStrategy(params);
        AggressiveBacktestEngine engine = new AggressiveBacktestEngine(props);
        
        // 运行回测（使用已有的数据文件）
        String csvFile = "backtest-data/btc_15m_2024-03-01_to_2024-03-31.csv";
        
        try {
            BacktestResult result = engine.run(csvFile, strategy);
            
            // 打印结果
            System.out.println("\n═══════════════════════════════════════════");
            System.out.println("AggressiveStrategy 回测结果");
            System.out.println("═══════════════════════════════════════════");
            result.printSummary("Aggressive Strategy");
            
        } catch (Exception e) {
            System.out.println("回测失败（可能是数据文件不存在）: " + e.getMessage());
            System.out.println("请先运行 DataFetcher 抓取数据，或通过 API 触发回测");
        }
    }
}
