package com.trading.signal.service;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
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
 * 核心业务流程：拉取 K 线 → 分析市场结构 → 策略决策 → 写入文件 → 邮件通知
 * 通过 @Scheduled 定时驱动，间隔由 application.yml 配置
 */
@Service
public class SignalService {

    private static final Logger log = LoggerFactory.getLogger(SignalService.class);

    private final OkxClient         okxClient;
    private final MarketAnalyzer    analyzer;
    private final StrategyRouter    strategyRouter;
    private final SignalWriter      signalWriter;
    private final EmailService      emailService;
    private final TradingProperties properties;

    public SignalService(OkxClient okxClient, MarketAnalyzer analyzer,
                         StrategyRouter strategyRouter, SignalWriter signalWriter,
                         EmailService emailService, TradingProperties properties) {
        this.okxClient      = okxClient;
        this.analyzer       = analyzer;
        this.strategyRouter = strategyRouter;
        this.signalWriter   = signalWriter;
        this.emailService   = emailService;
        this.properties     = properties;
    }

    /**
     * 定时执行主流程
     * 使用 cron 对齐到每15分钟整点后10秒（:00:10, :15:10, :30:10, :45:10）
     * 延迟10秒确保K线已收盘且OKX数据已更新
     */
    @Scheduled(cron = "10 0/15 * * * *")
    public void run() {
        TradingProperties.Okx okxCfg = properties.getOkx();
        log.info("── 开始新一轮分析 instId={} bar={} limit={}",
                okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());

        try {
            // Step 1: 拉取 K 线
            List<KLine> klines = okxClient.fetchCandles(
                okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());

            // Step 2: 分析市场结构
            MarketData marketData = analyzer.analyze(klines);

            // Step 3: 策略决策
            Strategy    strategy    = strategyRouter.current();
            TradeSignal tradeSignal;

            // 均值回归策略：额外拉4小时K线做大箱体分析
            if ("mean-reversion".equals(strategy.getName())) {
                List<KLine> klines4h = okxClient.fetchCandles(
                    okxCfg.getInstId(), "4H", okxCfg.getLimit());
                com.trading.signal.model.HtfRange htfRange = analyzer.analyzeHtf(klines4h);
                log.info("HTF分析: isRange={} high={} low={} trend={}",
                    htfRange.isRange(),
                    String.format("%.2f", htfRange.getRangeHigh()),
                    String.format("%.2f", htfRange.getRangeLow()),
                    htfRange.getTrendBias());
                tradeSignal = strategy.generateSignal(marketData, htfRange);
            } else {
                tradeSignal = strategy.generateSignal(marketData);
            }

            log.info("策略={} 决策={} 置信度={} 原因={}",
                strategy.getName(),
                tradeSignal.getAction().getValue(),
                tradeSignal.getConfidence(),
                tradeSignal.getReason());

            // Step 4: 写入 signal.json
            signalWriter.write(marketData, tradeSignal, strategy.getName());

            // Step 5: 邮件通知
            emailService.sendSignalEmail(tradeSignal, marketData.getCurrentPrice());

            // 试探仓作废通知（NO_TRADE 但需要通知人平仓）
            if (tradeSignal.getAction() == TradeSignal.Action.NO_TRADE) {
                String reason = tradeSignal.getReason();
                if (reason.startsWith("PROBE timeout") || reason.startsWith("PROBE hit")) {
                    emailService.sendCancelEmail(reason, marketData.getCurrentPrice());
                }
            }

        } catch (Exception e) {
            log.error("本轮执行出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发一次分析（供 REST 接口调用）
     */
    public TradeSignal runOnce() throws Exception {
        TradingProperties.Okx okxCfg = properties.getOkx();
        List<KLine> klines     = okxClient.fetchCandles(
            okxCfg.getInstId(), okxCfg.getTimeframe(), okxCfg.getLimit());
        MarketData  marketData = analyzer.analyze(klines);
        Strategy    strategy   = strategyRouter.current();
        TradeSignal signal;
        if ("mean-reversion".equals(strategy.getName())) {
            List<KLine> klines4h = okxClient.fetchCandles(okxCfg.getInstId(), "4H", okxCfg.getLimit());
            signal = strategy.generateSignal(marketData, analyzer.analyzeHtf(klines4h));
        } else {
            signal = strategy.generateSignal(marketData);
        }
        signalWriter.write(marketData, signal, strategy.getName());
        emailService.sendSignalEmail(signal, marketData.getCurrentPrice());
        return signal;
    }
}
