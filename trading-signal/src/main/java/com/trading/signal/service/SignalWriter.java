package com.trading.signal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 信号写入器 - 将策略决策输出到 JSON 文件
 * 
 * 功能：
 * - 将市场数据和策略信号合并写入 signal.json
 * - 每次覆盖文件，保持最新状态
 * - 用于外部系统集成或调试监控
 * 
 * 输出格式：
 * {
 *   "current_price": 67500.0,
 *   "atr": 850.5,
 *   "strategy": "aggressive",
 *   "action": "long",
 *   "reason": "BREAKOUT LONG: entry=67500 SL=66650...",
 *   "confidence": 0.75,
 *   "position_size": 0.25,
 *   "stop_loss": 66650.0,
 *   "take_profit": 0.0,
 *   "generated_at": "2024-03-15 14:30:00"
 * }
 */
@Service
public class SignalWriter {

    private static final Logger log = LoggerFactory.getLogger(SignalWriter.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final TradingProperties properties;

    public SignalWriter(TradingProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 将市场数据和策略信号写入 signal.json 文件
     * 
     * @param market 市场数据（包含当前价格和 ATR）
     * @param signal 策略信号（包含交易决策）
     * @param strategyName 策略名称
     */
    public void write(MarketData market, TradeSignal signal, String strategyName) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            
            // 市场数据
            node.put("current_price", market.getCurrentPrice());
            node.put("atr", market.getAtr());
            
            // 策略决策
            node.put("strategy", strategyName);
            node.put("action", signal.getAction().getValue());
            node.put("reason", signal.getReason());
            node.put("confidence", signal.getConfidence());
            node.put("position_size", signal.getPositionSize());
            node.put("stop_loss", signal.getStopLoss());
            node.put("take_profit", signal.getTakeProfit());
            node.put("generated_at", LocalDateTime.now().format(FMT));

            File file = new File(properties.getSignal().getOutputFile());
            objectMapper.writeValue(file, node);
            log.info("信号已写入: {}", file.getAbsolutePath());

        } catch (Exception e) {
            log.error("写入 signal.json 失败: {}", e.getMessage(), e);
        }
    }
}
