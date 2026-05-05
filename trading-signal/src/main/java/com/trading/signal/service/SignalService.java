package com.trading.signal.service;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.MeanReversionStrategy;
import com.trading.signal.strategy.Strategy;
import com.trading.signal.strategy.StrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 信号生成服务
 * 
 * 交易系统的核心服务，负责定时执行完整的交易流程：
 * 1. 拉取市场数据（K线）
 * 2. 计算技术指标（ATR等）
 * 3. 策略分析决策
 * 4. 执行交易操作
 * 5. 记录和通知
 * 
 * 定时机制：
 * - 每15分钟执行一次（与K线周期对齐）
 * - 在整点后10秒执行，确保K线已收盘
 * - 可通过配置开关控制启停
 * 
 * 设计原则：
 * - 统一的交易流程，适用于所有策略
 * - 完整的错误处理和日志记录
 * - 支持实盘和模拟盘切换
 * - 账户余额实时获取
 * 
 * @author Trading System
 * @version 2.0 (Simplified and cleaned)
 */
@Service
public class SignalService {

    private static final Logger log = LoggerFactory.getLogger(SignalService.class);

    private final OkxClient okxClient;
    private final MarketAnalyzer analyzer;
    private final StrategyRouter strategyRouter;
    private final SignalWriter signalWriter;
    private final EmailService emailService;
    private final TradingProperties properties;
    private final TradeExecutor tradeExecutor;

    /**
     * 构造信号生成服务
     * 
     * @param okxClient OKX API 客户端
     * @param analyzer 市场分析器
     * @param strategyRouter 策略路由器
     * @param signalWriter 信号写入器
     * @param emailService 邮件服务
     * @param properties 交易配置
     * @param tradeExecutor 交易执行器
     */
    public SignalService(OkxClient okxClient, MarketAnalyzer analyzer,
                         StrategyRouter strategyRouter, SignalWriter signalWriter,
                         EmailService emailService, TradingProperties properties,
                         TradeExecutor tradeExecutor) {
        this.okxClient = okxClient;
        this.analyzer = analyzer;
        this.strategyRouter = strategyRouter;
        this.signalWriter = signalWriter;
        this.emailService = emailService;
        this.properties = properties;
        this.tradeExecutor = tradeExecutor;
        
        log.info("信号生成服务初始化完成");
    }

    /**
     * 定时执行主流程
     * 
     * 执行时间：每15分钟整点后10秒
     * - :00:10, :15:10, :30:10, :45:10
     * - 延迟10秒确保K线已收盘且OKX数据已更新
     * 
     * 执行流程：
     * 1. 检查交易开关
     * 2. 拉取K线数据
     * 3. 计算技术指标
     * 4. 策略分析决策
     * 5. 写入信号文件
     * 6. 执行交易（如果启用）
     */
    @Scheduled(cron = "10 0/15 * * * *")
    public void run() {
        // 检查交易开关
        if (!properties.getTradeApi().isEnabled()) {
            log.info("⏸️  交易已停止，跳过本轮分析");
            return;
        }
        
        TradingProperties.Okx okxCfg = properties.getOkx();
        log.info("── 开始新一轮分析 instId={} timeframe={} limit={}",
                okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());

        try {
            // Step 1: 拉取K线数据
            List<KLine> klines = okxClient.fetchCandles(
                okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());
            
            log.debug("拉取K线完成: {} 根，时间范围: {} ~ {}", 
                klines.size(),
                klines.get(0).getTimestamp(),
                klines.get(klines.size() - 1).getTimestamp());

            // Step 2: 计算技术指标
            MarketData marketData = analyzer.analyze(klines);
            
            log.debug("技术指标计算完成: ATR={:.2f}, 当前价格={:.2f}", 
                marketData.getAtr(), marketData.getCurrentPrice());

            // Step 3: 获取策略并更新账户余额
            Strategy strategy = strategyRouter.current();
            
            // 为各策略更新实时账户余额
            if (strategy instanceof AggressiveStrategy aggStrategy) {
                updateAccountBalance(aggStrategy);
            } else if (strategy instanceof MeanReversionStrategy mrStrategy) {
                updateAccountBalance(mrStrategy);
            }

            // Step 4: 策略分析决策
            TradeSignal tradeSignal = strategy.generateSignal(marketData);
            
            log.info("策略决策完成: 策略={} 信号={} 置信度={:.2f} 原因={}",
                strategy.getName(),
                tradeSignal.getAction().getValue(),
                tradeSignal.getConfidence(),
                tradeSignal.getReason());

            // Step 5: 写入信号文件
            signalWriter.write(marketData, tradeSignal, strategy.getName());

            // Step 6: 执行交易
            handleExecution(tradeSignal, marketData.getCurrentPrice());

        } catch (Exception e) {
            log.error("信号生成流程执行失败: {}", e.getMessage(), e);
            
            // 发送错误通知邮件
            try {
                emailService.sendRawEmail(
                    "[交易系统错误] 信号生成失败",
                    String.format("错误信息: %s\n时间: %s", 
                        e.getMessage(), 
                        java.time.LocalDateTime.now())
                );
            } catch (Exception emailError) {
                log.error("发送错误通知邮件失败: {}", emailError.getMessage());
            }
        }
    }

    /**
     * 手动触发一次信号生成（用于测试和调试）
     * 
     * @return 生成的交易信号
     * @throws Exception 如果执行过程中发生错误
     */
    public TradeSignal runOnce() throws Exception {
        log.info("手动触发信号生成");
        
        TradingProperties.Okx okxCfg = properties.getOkx();
        
        // 拉取数据并分析
        List<KLine> klines = okxClient.fetchCandles(
            okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());
        
        MarketData marketData = analyzer.analyze(klines);
        
        // 策略决策
        Strategy strategy = strategyRouter.current();
        if (strategy instanceof AggressiveStrategy aggStrategy) {
            updateAccountBalance(aggStrategy);
        } else if (strategy instanceof MeanReversionStrategy mrStrategy) {
            updateAccountBalance(mrStrategy);
        }
        
        TradeSignal signal = strategy.generateSignal(marketData);
        
        log.info("手动信号生成完成: {}", signal);
        return signal;
    }

    /**
     * 更新 AggressiveStrategy 的账户余额
     * 
     * @param strategy AggressiveStrategy 实例
     */
    private void updateAccountBalance(AggressiveStrategy strategy) {
        try {
            double balance = tradeExecutor.getOkxTradeClient().getBalance();
            strategy.setAccountBalance(balance);
            log.debug("账户余额更新 (Aggressive): {} USDT", String.format("%.2f", balance));
        } catch (Exception e) {
            log.warn("获取账户余额失败，使用默认值: {}", e.getMessage());
        }
    }

    /**
     * 更新 MeanReversionStrategy 的账户余额
     * 
     * @param strategy MeanReversionStrategy 实例
     */
    private void updateAccountBalance(MeanReversionStrategy strategy) {
        try {
            double balance = tradeExecutor.getOkxTradeClient().getBalance();
            strategy.setAccountBalance(balance);
            log.debug("账户余额更新 (MeanReversion): {} USDT", String.format("%.2f", balance));
        } catch (Exception e) {
            log.warn("获取账户余额失败，使用默认值: {}", e.getMessage());
        }
    }

    /**
     * 处理交易执行
     * 
     * @param signal 交易信号
     * @param currentPrice 当前价格
     */
    private void handleExecution(TradeSignal signal, double currentPrice) {
        try {
            if (signal.isValidTrade()) {
                // 执行交易
                tradeExecutor.execute(signal, currentPrice);
                log.info("交易信号已执行: {}", signal.getAction().getValue());
            } else {
                log.debug("无交易信号: {}", signal.getReason());
            }
            
            // 发送邮件通知（无论是否有交易）
            emailService.sendSignalEmail(signal, currentPrice);
            
        } catch (Exception e) {
            log.error("交易执行失败: {}", e.getMessage(), e);
            
            // 发送执行失败通知
            try {
                emailService.sendRawEmail(
                    "[交易系统错误] 交易执行失败",
                    String.format("信号: %s\n错误: %s\n时间: %s", 
                        signal, e.getMessage(), 
                        java.time.LocalDateTime.now())
                );
            } catch (Exception emailError) {
                log.error("发送执行失败通知邮件失败: {}", emailError.getMessage());
            }
        }
    }
}