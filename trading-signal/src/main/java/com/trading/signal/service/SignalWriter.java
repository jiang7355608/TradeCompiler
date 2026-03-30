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
 * 信号写入器
 * 将市场数据 + 策略决策合并写入 signal.json（每次覆盖，保持最新）
 */
@Service
public class SignalWriter {

    private static final Logger log = LoggerFactory.getLogger(SignalWriter.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper      objectMapper;
    private final TradingProperties properties;

    public SignalWriter(TradingProperties properties) {
        this.properties   = properties;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 将市场数据 + 策略决策写入 signal.json
     */
    public void write(MarketData market, TradeSignal signal, String strategyName) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            // 市场数据
            node.put("is_range",      market.isRange());
            node.put("volume_spike",  market.isVolumeSpike());
            node.put("breakout",      market.getBreakout());
            node.put("continuation",  market.isContinuation());
            node.put("current_price", market.getCurrentPrice());
            // 策略决策
            node.put("strategy",       strategyName);
            node.put("action",         signal.getAction().getValue());
            node.put("reason",         signal.getReason());
            node.put("confidence",     signal.getConfidence());
            node.put("position_size",  signal.getPositionSize());
            node.put("stop_loss",      signal.getStopLoss());
            node.put("take_profit",    signal.getTakeProfit());
            node.put("generated_at",   LocalDateTime.now().format(FMT));

            File file = new File(properties.getSignal().getOutputFile());
            objectMapper.writeValue(file, node);
            log.info("信号已写入: {}", file.getAbsolutePath());

        } catch (Exception e) {
            log.error("写入 signal.json 失败: {}", e.getMessage(), e);
        }
    }
}
