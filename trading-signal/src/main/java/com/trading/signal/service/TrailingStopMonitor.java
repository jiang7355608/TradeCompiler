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
 * Trailing Stop 监控服务 - 专用于 AggressiveStrategy 的动态止损
 * 
 * 功能：
 * - 每分钟检查持仓状态
 * - 动态更新 trailing stop（只能向有利方向移动）
 * - 触发时自动平仓并发送邮件通知
 * 
 * Trailing Stop 规则：
 * - 做多：止损线 = 当前价格 - ATR × 2.5（只能上移）
 * - 做空：止损线 = 当前价格 + ATR × 2.5（只能下移）
 * - 目标：持有趋势，获取大额利润
 * 
 * 使用场景：
 * - 仅用于 AggressiveStrategy 的 BREAKOUT 信号
 * - 实盘交易时自动启动
 * - 回测时不使用（回测引擎有独立实现）
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
    private volatile String direction;      // "long" or "short"
    private volatile double entryPrice;     // 入场价格
    private volatile double entryAtr;       // 入场时的 ATR
    private volatile double trailingStop;   // 当前 trailing stop 价格
    private volatile double maxProfit;      // 最大浮盈百分比

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
     * 记录开仓信息（由 TradeExecutor 在 BREAKOUT 开仓后调用）
     * 
     * @param direction "long" or "short"
     * @param entryPrice 入场价格
     * @param stopLoss 初始止损价格
     * @param atr 当前 ATR 值
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
     * 
     * 执行条件：
     * - 交易 API 已启用
     * - 当前策略是 AggressiveStrategy
     * - 有持仓需要监控
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
     * 更新 trailing stop 价格
     * 
     * @param currentPrice 当前市场价格
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
     * 执行 trailing stop 平仓
     * 
     * @param currentPrice 触发时的价格
     */
    private void closePosition(double currentPrice) {
        try {
            log.warn("执行 Trailing Stop 平仓: direction={} price={} maxProfit={}%", 
                direction, currentPrice, String.format("%.2f", maxProfit));
            
            // 调用交易所平仓 API
            JsonNode result = tradeClient.closePosition(
                properties.getOkx().getInstId() + "-SWAP", "cross", null);
            
            boolean success = "0".equals(result.path("code").asText());
            
            if (success) {
                log.info("Trailing Stop 平仓成功");
                
                // 发送邮件通知
                sendTrailingStopEmail(currentPrice, true);
                
                // 清除状态
                onPositionClosed();
            } else {
                log.error("Trailing Stop 平仓失败！请立即手动处理: {}", result.toString());
                sendTrailingStopEmail(currentPrice, false);
                // 平仓失败也要清除状态，避免重复告警
                onPositionClosed();
            }
            
        } catch (Exception e) {
            log.error("Trailing Stop 平仓异常: {}", e.getMessage(), e);
            sendTrailingStopEmail(currentPrice, false);
            // 异常也要清除状态，避免重复告警
            onPositionClosed();
        }
    }
    
    /**
     * 发送 trailing stop 平仓邮件
     * 
     * @param currentPrice 平仓价格
     * @param success 是否平仓成功
     */
    private void sendTrailingStopEmail(double currentPrice, boolean success) {
        if (!properties.getEmail().isEnabled()) {
            return;
        }
        
        String subject = success 
            ? String.format("[Trailing Stop 平仓] %s", properties.getOkx().getInstId().toUpperCase())
            : String.format("[紧急] Trailing Stop 平仓失败 - %s", properties.getOkx().getInstId().toUpperCase());
            
        String body = String.format(
            "===== Trailing Stop %s =====\n\n" +
            "方向        : %s\n" +
            "入场价格    : %.2f\n" +
            "平仓价格    : %.2f\n" +
            "最大浮盈    : %.2f%%\n" +
            "平仓状态    : %s\n" +
            "模式        : %s\n\n" +
            "%s\n" +
            "========================",
            success ? "平仓成功" : "平仓失败",
            direction.toUpperCase(),
            entryPrice,
            currentPrice,
            maxProfit,
            success ? "成功" : "失败 - 请立即手动处理",
            properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘",
            success 
                ? "Trailing Stop 已成功保护利润。"
                : "【紧急】平仓失败，请立即登录交易所手动平仓！");

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("Trailing Stop 邮件发送失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取当前 trailing stop 状态（用于调试和监控）
     * 
     * @return 状态描述字符串
     */
    public String getStatus() {
        if (!hasPosition) {
            return "无持仓";
        }
        return String.format("direction=%s entry=%.2f trailingStop=%.2f maxProfit=%.2f%%",
            direction, entryPrice, trailingStop, maxProfit);
    }
}
