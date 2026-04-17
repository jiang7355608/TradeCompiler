package com.trading.signal.controller;

import com.trading.signal.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 策略控制接口
 * 用于手机快捷指令远程控制策略启停
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private static final Logger log = LoggerFactory.getLogger(ControlController.class);
    
    // 秘钥（硬编码，简单安全）
    private static final String SECRET_TOKEN = "jiangyuxuanGGbond339@!";
    
    private final TradingProperties properties;
    
    // 策略运行状态（内存标志）
    private volatile boolean strategyEnabled = true;

    public ControlController(TradingProperties properties) {
        this.properties = properties;
        this.strategyEnabled = properties.getTradeApi().isEnabled();
    }
    
    /**
     * 验证秘钥
     */
    private Map<String, Object> unauthorized() {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "Unauthorized - Invalid token");
        return error;
    }

    /**
     * 启动策略
     * POST /api/control/start
     */
    @PostMapping("/start")
    public Map<String, Object> startStrategy(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (!SECRET_TOKEN.equals(token)) {
            log.warn("启动策略失败：秘钥错误");
            return unauthorized();
        }
        
        log.warn("✅ 收到启动策略指令（已验证）");
        strategyEnabled = true;
        properties.getTradeApi().setEnabled(true);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "策略已启动");
        response.put("status", "RUNNING");
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }

    /**
     * 停止策略
     * POST /api/control/stop
     */
    @PostMapping("/stop")
    public Map<String, Object> stopStrategy(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (!SECRET_TOKEN.equals(token)) {
            log.warn("停止策略失败：秘钥错误");
            return unauthorized();
        }
        
        log.warn("🛑 收到停止策略指令（已验证）");
        strategyEnabled = false;
        properties.getTradeApi().setEnabled(false);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "策略已停止");
        response.put("status", "STOPPED");
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }

    /**
     * 查询策略状态
     * GET /api/control/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (!SECRET_TOKEN.equals(token)) {
            log.warn("查询状态失败：秘钥错误");
            return unauthorized();
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("enabled", strategyEnabled);
        response.put("status", strategyEnabled ? "RUNNING" : "STOPPED");
        response.put("strategy", properties.getStrategy());
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }

    /**
     * 紧急平仓（预留接口）
     * POST /api/control/emergency-close
     */
    @PostMapping("/emergency-close")
    public Map<String, Object> emergencyClose(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (!SECRET_TOKEN.equals(token)) {
            log.warn("紧急平仓失败：秘钥错误");
            return unauthorized();
        }
        
        log.error("🚨 收到紧急平仓指令！（已验证）");
        strategyEnabled = false;
        properties.getTradeApi().setEnabled(false);
        
        // TODO: 调用 TradeExecutor 平掉所有持仓
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "策略已停止，请手动确认持仓");
        response.put("status", "EMERGENCY_STOPPED");
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }
}
