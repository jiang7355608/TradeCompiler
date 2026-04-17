package com.trading.signal.service;

import com.trading.signal.analyzer.MarketAnalyzer;
import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.strategy.AggressiveStrategy;
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
    private final TradeExecutor     tradeExecutor;

    public SignalService(OkxClient okxClient, MarketAnalyzer analyzer,
                         StrategyRouter strategyRouter, SignalWriter signalWriter,
                         EmailService emailService, TradingProperties properties,
                         TradeExecutor tradeExecutor) {
        this.okxClient      = okxClient;
        this.analyzer       = analyzer;
        this.strategyRouter = strategyRouter;
        this.signalWriter   = signalWriter;
        this.emailService   = emailService;
        this.properties     = properties;
        this.tradeExecutor  = tradeExecutor;
    }

    /**
     * 定时执行主流程
     * 使用 cron 对齐到每15分钟整点后10秒（:00:10, :15:10, :30:10, :45:10）
     * 延迟10秒确保K线已收盘且OKX数据已更新
     */
    @Scheduled(cron = "10 0/15 * * * *")
    public void run() {
        // 检查策略是否启用
        if (!properties.getTradeApi().isEnabled()) {
            log.info("⏸️  策略已停止，跳过本轮分析");
            return;
        }
        
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
            
            // 修复2：实盘从交易所获取账户余额（仅对 AggressiveStrategy）
            if (strategy instanceof AggressiveStrategy aggStrategy) {
                if (properties.getTradeApi().isEnabled()) {
                    try {
                        double balance = tradeExecutor.getOkxTradeClient().getBalance();
                        aggStrategy.setAccountBalance(balance);
                        log.debug("从交易所获取账户余额: {}U", String.format("%.2f", balance));
                    } catch (Exception e) {
                        log.warn("获取账户余额失败，使用默认值: {}", e.getMessage());
                    }
                }
            }
            
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

            // Step 5: 执行下单 / 平仓
            if (properties.getTradeApi().isEnabled()) {
                handleExecution(strategy, tradeSignal, marketData.getCurrentPrice());
            } else {
                // 未启用自动下单时仅发邮件
                emailService.sendSignalEmail(tradeSignal, marketData.getCurrentPrice());
            }

        } catch (Exception e) {
            log.error("本轮执行出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 统一处理下单/平仓逻辑，避免重复调用
     */
    private void handleExecution(Strategy strategy, TradeSignal tradeSignal, double currentPrice) {
        String reason = tradeSignal.getReason();

        // ── 均值回归：加仓信号 ────────────────────────────────────────────
        if (strategy instanceof com.trading.signal.strategy.MeanReversionStrategy mr
                && reason.contains("ADD")) {
            boolean addOk = tradeExecutor.execute(tradeSignal, currentPrice);
            if (addOk) {
                mr.confirmHandedOff();
            } else {
                // 加仓失败（无论原因）一律 reset 回 IDLE
                // 交易所侧的试探仓有止损单兜底，策略不需要继续跟踪
                log.warn("加仓下单失败，策略 reset 回 IDLE，交易所止损单负责保护试探仓");
                mr.reset();
            }
            return;
        }

        // ── 均值回归：试探仓止损/超时 or CONFIRMED 超时兜底 → 平仓 ─────
        if (tradeSignal.getAction() == TradeSignal.Action.NO_TRADE) {
            if (reason.startsWith("MR-PROBE timeout") || reason.startsWith("MR-PROBE hit")
                    || reason.startsWith("MR-PROBE range invalidated")
                    || reason.startsWith("MR-CONFIRMED timeout")) {
                if (strategy instanceof com.trading.signal.strategy.MeanReversionStrategy mr) {
                    boolean closeOk = tradeExecutor.closeProbe(mr.getDirection());
                    if (!closeOk) {
                        log.error("【警告】试探仓平仓失败，请立即手动处理！原因: {}", reason);
                        emailService.sendCancelEmail("【紧急】平仓失败，请立即手动处理: " + reason, currentPrice);
                        if (!tradeExecutor.hasExchangePosition()) {
                            log.warn("交易所确认无持仓，策略状态已 reset，不再重复发送邮件");
                        }
                        return;
                    }
                    // 平仓成功，发送邮件（所有平仓都通知）
                    emailService.sendCancelEmail(reason, currentPrice);
                }
            }
            return;
        }

        // ── 通用开仓信号 ──────────────────────────────────────────────────
        tradeExecutor.execute(tradeSignal, currentPrice);
    }

    /**
     * 止损监控任务（每1分钟执行一次）
     * 
     * 专门用于监控试探仓止损，不进行完整的策略分析。
     * 只查询当前价格，检查是否触及止损线。
     * 
     * 为什么需要独立任务？
     * - 主任务每15分钟执行一次（对齐K线周期）
     * - 试探仓没有交易所止损，需要更频繁的监控
     * - 1分钟检查一次可以及时止损，避免损失扩大
     */
    @Scheduled(cron = "0 * * * * *")  // 每分钟整点执行
    public void monitorStopLoss() {
        // 检查策略是否启用
        if (!properties.getTradeApi().isEnabled()) {
            return;
        }
        
        Strategy strategy = strategyRouter.current();
        
        // 只有均值回归策略需要止损监控
        if (!(strategy instanceof com.trading.signal.strategy.MeanReversionStrategy mr)) {
            return;
        }
        
        // 只有 PROBE 状态需要监控（IDLE 无持仓，CONFIRMED 有交易所止损）
        if (mr.getState() != com.trading.signal.strategy.MeanReversionStrategy.State.PROBE) {
            return;
        }
        
        try {
            // 查询当前价格（使用1分钟K线的最新收盘价）
            TradingProperties.Okx okxCfg = properties.getOkx();
            List<KLine> klines1m = okxClient.fetchCandles(okxCfg.getInstId(), "1m", 1);
            if (klines1m.isEmpty()) return;
            
            double currentPrice = klines1m.get(0).getClose();
            double stopLoss = mr.getStopLoss();
            String direction = mr.getDirection();
            
            // 检查是否触及止损
            boolean hitStopLoss = ("up".equals(direction) && currentPrice <= stopLoss)
                               || ("down".equals(direction) && currentPrice >= stopLoss);
            
            if (hitStopLoss) {
                log.warn("止损监控: 价格={} 触及止损={} 方向={}", currentPrice, stopLoss, direction);
                
                // 调用策略的 reset（与主任务逻辑一致）
                mr.reset();
                
                // 平仓
                boolean closeOk = tradeExecutor.closeProbe(direction);
                if (!closeOk) {
                    log.error("【警告】止损监控平仓失败，请立即手动处理！");
                    emailService.sendCancelEmail("【紧急】止损监控平仓失败，请立即手动处理", currentPrice);
                } else {
                    // 发送止损平仓邮件
                    emailService.sendCancelEmail("MR-PROBE hit stop loss — closing", currentPrice);
                }
            }
        } catch (Exception e) {
            log.error("止损监控执行出错: {}", e.getMessage(), e);
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
