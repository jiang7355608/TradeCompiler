package com.trading.signal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.signal.client.OkxClient;
import com.trading.signal.client.OkxTradeClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.StrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Trailing Stop 监控服务（仅用于 AggressiveStrategy 实盘）
 * 
 * 功能：
 * 1. 每分钟检查持仓
 * 2. 更新 trailing stop
 * 3. 触发时平仓
 */
@Service
public class TrailingStopMonitor {

    private static final Logger log = LoggerFactory.getLogger(TrailingStopMonitor.class);
    
    private final OkxClient okxClient;
    private final OkxTradeClient tradeClient;
    private final TradingProperties properties;
    private final StrategyRouter strategyRouter;
    private final EmailService emailService;
    
    // Trailing stop 状态
    private volatile boolean hasPosition = false;
    private volatile String direction;  // "long" or "short"
    private volatile double entryPrice;
    private volatile double entryAtr;
    private volatile double trailingStop;
    private volatile double maxProfit;

    public TrailingStopMonitor(OkxClient okxClient, OkxTradeClient tradeClient,
                              TradingProperties properties, StrategyRouter strategyRouter,
                              EmailService emailService) {
        this.okxClient = okxClient;
        this.tradeClient = tradeClient;
        this.properties = properties;
        this.strategyRouter = strategyRouter;
        this.emailService = emailService;
    }

    /**
     * 记录开仓信息（由 TradeExecutor 在开仓后调用）
     */
    public void onPositionOpened(String direction, double entryPrice, double stopLoss, double atr) {
        this.hasPosition = true;
        this.direction = direction;
        this.entryPrice = entryPrice;
        this.entryAtr = atr;
        this.trailingStop = stopLoss;  // 初始 trailing stop = 初始止损
        this.maxProfit = 0;
        
        log.info("Trailing Stop 监控已启动: direction={} entry={} initialStop={} ATR={}", 
            direction, entryPrice, stopLoss, atr);
    }

    /**
     * 清除持仓状态（平仓后调用）
     */
    public void onPositionClosed() {
        this.hasPosition = false;
        log.info("Trailing Stop 监控已停止");
    }

    /**
     * 每分钟检查一次 trailing stop
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkTrailingStop() {
        // 检查策略是否启用
        if (!properties.getTradeApi().isEnabled()) {
            return;
        }
        
        // 只有 AggressiveStrategy 才使用 trailing stop
        if (!(strategyRouter.current() instanceof AggressiveStrategy)) {
            return;
        }
        
        // 没有持仓，不需要监控
        if (!hasPosition) {
            return;
        }
        
        try {
            // 获取当前价格（使用1分钟K线的最新收盘价）
            List<KLine> klines1m = okxClient.fetchCandles(
                properties.getOkx().getInstId(), "1m", 1);
            
            if (klines1m.isEmpty()) {
                log.warn("Trailing Stop: 无法获取当前价格");
                return;
            }
            
            double currentPrice = klines1m.get(0).getClose();
            
            // 更新 trailing stop
            boolean shouldClose = updateTrailingStop(currentPrice);
            
            if (shouldClose) {
                // 触发 trailing stop，平仓
                closePosition(currentPrice);
            }
            
        } catch (Exception e) {
            log.error("Trailing Stop 监控异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 更新 trailing stop
     * @return true 表示应该平仓
     */
    private boolean updateTrailingStop(double currentPrice) {
        if ("long".equals(direction)) {
            // 做多：trailing stop 只能上移
            double newTrailingStop = currentPrice - entryAtr * 2.5;
            
            if (newTrailingStop > trailingStop) {
                double oldStop = trailingStop;
                trailingStop = newTrailingStop;
                log.info("Trailing Stop 上移: {} -> {} (price={}, profit={})", 
                    String.format("%.2f", oldStop),
                    String.format("%.2f", trailingStop),
                    String.format("%.2f", currentPrice),
                    String.format("%.2f%%", (currentPrice - entryPrice) / entryPrice * 100));
            }
            
            // 更新最大浮盈
            double currentProfit = (currentPrice - entryPrice) / entryPrice * 100;
            maxProfit = Math.max(maxProfit, currentProfit);
            
            // 检查是否触及 trailing stop
            if (currentPrice <= trailingStop) {
                log.warn("触及 Trailing Stop LONG: price={} <= stop={}", currentPrice, trailingStop);
                return true;
            }
            
        } else {
            // 做空：trailing stop 只能下移
            double newTrailingStop = currentPrice + entryAtr * 2.5;
            
            if (newTrailingStop < trailingStop) {
                double oldStop = trailingStop;
                trailingStop = newTrailingStop;
                log.info("Trailing Stop 下移: {} -> {} (price={}, profit={})", 
                    String.format("%.2f", oldStop),
                    String.format("%.2f", trailingStop),
                    String.format("%.2f", currentPrice),
                    String.format("%.2f%%", (entryPrice - currentPrice) / entryPrice * 100));
            }
            
            // 更新最大浮盈
            double currentProfit = (entryPrice - currentPrice) / entryPrice * 100;
            maxProfit = Math.max(maxProfit, currentProfit);
            
            // 检查是否触及 trailing stop
            if (currentPrice >= trailingStop) {
                log.warn("触及 Trailing Stop SHORT: price={} >= stop={}", currentPrice, trailingStop);
                return true;
            }
        }
        
        return false;
    }

    /**
     * 平仓
     */
    private void closePosition(double currentPrice) {
        try {
            log.warn("执行 Trailing Stop 平仓: direction={} price={} maxProfit={}%", 
                direction, currentPrice, String.format("%.2f", maxProfit));
            
            // 调用交易所平仓
            JsonNode result = tradeClient.closePosition(
                properties.getOkx().getInstId() + "-SWAP", "cross", null);
            
            boolean success = "0".equals(result.path("code").asText());
            
            if (success) {
                log.info("Trailing Stop 平仓成功");
                
                // 发送邮件通知
                emailService.sendCancelEmail(
                    String.format("Trailing Stop 平仓 %s | 最大浮盈: %.2f%%", 
                        direction.toUpperCase(), maxProfit),
                    currentPrice);
                
                // 清除状态
                onPositionClosed();
            } else {
                log.error("Trailing Stop 平仓失败！请立即手动处理");
                emailService.sendCancelEmail(
                    "【紧急】Trailing Stop 平仓失败，请立即手动处理", 
                    currentPrice);
                // 平仓失败也要清除状态，避免重复告警
                onPositionClosed();
            }
            
        } catch (Exception e) {
            log.error("Trailing Stop 平仓异常: {}", e.getMessage(), e);
            emailService.sendCancelEmail(
                "【紧急】Trailing Stop 平仓异常: " + e.getMessage(), 
                currentPrice);
            // 异常也要清除状态，避免重复告警
            onPositionClosed();
        }
    }
    
    /**
     * 获取当前 trailing stop 状态（用于调试）
     */
    public String getStatus() {
        if (!hasPosition) {
            return "无持仓";
        }
        return String.format("direction=%s entry=%.2f trailingStop=%.2f maxProfit=%.2f%%",
            direction, entryPrice, trailingStop, maxProfit);
    }
}
