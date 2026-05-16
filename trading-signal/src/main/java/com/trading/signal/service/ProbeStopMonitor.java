package com.trading.signal.service;

import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.strategy.MeanReversionStrategy;
import com.trading.signal.strategy.StrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ProbeStopMonitor — 试探仓虚拟止损监控服务
 * 
 * 功能：
 * - 每 1 分钟检查试探仓虚拟止损状态
 * - 触发时主动平仓并发送邮件通知
 * - 平仓成功后重置策略状态
 * 
 * 虚拟止损规则：
 * - 做多：虚拟止损 = 入场价 - 15% 箱体宽度
 * - 做空：虚拟止损 = 入场价 + 15% 箱体宽度
 * - 目标：保护小仓位试探，避免 15 分钟检查窗口的风险
 * 
 * 使用场景：
 * - 仅用于 MeanReversionStrategy 的 PROBE 状态
 * - 补充 15 分钟主策略检查的风控漏洞
 * - 实盘交易时自动启动
 * 
 * @author Trading System
 * @version 1.0 (Probe Stop Protection)
 */
@Service
public class ProbeStopMonitor {

    private static final Logger log = LoggerFactory.getLogger(ProbeStopMonitor.class);
    
    private final OkxClient okxClient;
    private final TradeExecutor tradeExecutor;
    private final TradingProperties properties;
    private final StrategyRouter strategyRouter;
    private final EmailService emailService;

    public ProbeStopMonitor(OkxClient okxClient, TradeExecutor tradeExecutor,
                           TradingProperties properties, StrategyRouter strategyRouter,
                           EmailService emailService) {
        this.okxClient = okxClient;
        this.tradeExecutor = tradeExecutor;
        this.properties = properties;
        this.strategyRouter = strategyRouter;
        this.emailService = emailService;
    }

    /**
     * 每分钟检查一次试探仓虚拟止损和超时
     * 
     * 执行条件：
     * - 交易 API 已启用
     * - 当前策略是 MeanReversionStrategy
     * - 策略状态为 PROBE（有试探仓）
     * 
     * 检查内容：
     * 1. 虚拟止损触发
     * 2. 持仓超时（3小时）
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkProbeStop() {
        // 检查策略是否启用
        if (!properties.getTradeApi().isEnabled()) {
            return;
        }
        
        // 只有 MeanReversionStrategy 才有试探仓
        if (!(strategyRouter.current() instanceof MeanReversionStrategy mrStrategy)) {
            return;
        }
        
        // 只在 PROBE 状态时检查
        if (mrStrategy.getState() != MeanReversionStrategy.State.PROBE) {
            return;
        }
        
        try {
            // 获取当前价格（使用1分钟K线的最新收盘价）
            List<KLine> klines1m = okxClient.fetchCandles(
                properties.getOkx().getInstId(), "1m", 1);
            
            if (klines1m.isEmpty()) {
                log.warn("ProbeStopMonitor: 无法获取当前价格");
                return;
            }
            
            double currentPrice = klines1m.get(0).getClose();
            long currentTime = klines1m.get(0).getTimestamp();
            double virtualStop = mrStrategy.getStopLoss();
            String direction = mrStrategy.getDirection();
            
            // 获取试探仓入场时间（需要从 MeanReversionStrategy 暴露）
            long probeEntryTime = getProbeEntryTime(mrStrategy);
            long holdingTime = currentTime - probeEntryTime;
            
            // 检查1：虚拟止损触发
            boolean stopHit = ("long".equals(direction) && currentPrice <= virtualStop)
                           || ("short".equals(direction) && currentPrice >= virtualStop);
            
            if (stopHit) {
                log.warn("试探仓虚拟止损触发: direction={} price={} vs stop={}", 
                    direction.toUpperCase(), currentPrice, virtualStop);
                closeProbePosition(mrStrategy, direction, currentPrice, virtualStop, "虚拟止损触发");
                return;
            }
            
            // 检查2：持仓超时（3小时 = 10800000ms）
            if (holdingTime > 10800000L) {
                log.warn("试探仓超时: direction={} holdingTime={}min", 
                    direction.toUpperCase(), holdingTime / 60000);
                closeProbePosition(mrStrategy, direction, currentPrice, virtualStop, 
                    String.format("持仓超时（%.1f小时）", holdingTime / 3600000.0));
                return;
            }
            
        } catch (Exception e) {
            log.error("ProbeStopMonitor 监控异常: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取试探仓入场时间（通过反射或增加 getter）
     * TODO: 在 MeanReversionStrategy 中添加 public long getProbeEntryTime() 方法
     */
    private long getProbeEntryTime(MeanReversionStrategy strategy) {
        try {
            // 暂时使用反射获取私有字段
            var field = strategy.getClass().getDeclaredField("probeEntryTime");
            field.setAccessible(true);
            return (long) field.get(strategy);
        } catch (Exception e) {
            log.error("无法获取 probeEntryTime: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * 平仓试探仓（统一处理逻辑）
     */
    private void closeProbePosition(MeanReversionStrategy strategy, String direction, 
                                    double currentPrice, double virtualStop, String reason) {
        // 调用 TradeExecutor 平仓试探仓
        boolean success = tradeExecutor.closeProbe(direction);
        
        if (success) {
            log.info("试探仓平仓成功（{}），重置策略状态", reason);
            strategy.reset();
            sendProbeStopEmail(direction, currentPrice, virtualStop, true, reason);
        } else {
            log.error("试探仓平仓失败（{}）！已发送告警邮件，请人工检查", reason);
            sendProbeStopEmail(direction, currentPrice, virtualStop, false, reason);
            // 平仓失败也要 reset，避免策略状态错乱
            strategy.reset();
        }
    }
    
    /**
     * 发送试探仓虚拟止损触发邮件
     * 
     * @param direction 方向（long/short）
     * @param currentPrice 触发时的价格
     * @param virtualStop 虚拟止损价格
     * @param success 是否平仓成功
     * @param reason 触发原因（虚拟止损触发 / 持仓超时）
     */
    private void sendProbeStopEmail(String direction, double currentPrice, 
                                    double virtualStop, boolean success, String reason) {
        if (!properties.getEmail().isEnabled()) {
            return;
        }
        
        String subject = success 
            ? String.format("[试探仓止损] %s", properties.getOkx().getInstId().toUpperCase())
            : String.format("[紧急] 试探仓止损失败 - %s", properties.getOkx().getInstId().toUpperCase());
            
        String body = String.format(
            "===== 试探仓平仓 %s =====\n\n" +
            "触发原因       : %s\n" +
            "方向           : %s\n" +
            "当前价格       : %.2f\n" +
            "虚拟止损       : %.2f\n" +
            "仓位           : 6%% (试探仓)\n" +
            "平仓状态       : %s\n" +
            "模式           : %s\n\n" +
            "%s\n\n" +
            "【说明】\n" +
            "试探仓由 ProbeStopMonitor 每 1 分钟监控（虚拟止损 + 超时检查）。\n" +
            "触发后自动平仓，避免 15 分钟主策略检查的风险窗口。\n" +
            "超时阈值：3 小时\n\n" +
            "========================",
            success ? "平仓成功" : "平仓失败",
            reason,
            direction.toUpperCase(),
            currentPrice,
            virtualStop,
            success ? "成功" : "失败 - 请立即手动处理",
            properties.getTradeApi().isSimulated() ? "模拟盘" : "实盘",
            success 
                ? "试探仓已平仓退出，策略已重置到 IDLE 状态。"
                : "【紧急】平仓失败，请立即登录交易所检查试探仓状态！");

        try {
            emailService.sendRawEmail(subject, body);
        } catch (Exception e) {
            log.error("试探仓平仓邮件发送失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取当前监控状态（用于调试和监控）
     * 
     * @return 状态描述字符串
     */
    public String getStatus() {
        if (!(strategyRouter.current() instanceof MeanReversionStrategy mrStrategy)) {
            return "当前策略非 MeanReversion";
        }
        
        if (mrStrategy.getState() != MeanReversionStrategy.State.PROBE) {
            return "无试探仓（状态: " + mrStrategy.getState() + "）";
        }
        
        return String.format("监控中: direction=%s virtualStop=%.2f",
            mrStrategy.getDirection(), mrStrategy.getStopLoss());
    }
}
