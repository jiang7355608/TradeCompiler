package com.trading.signal.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OKX 公共 REST API 客户端
 *
 * GET https://www.okx.com/api/v5/market/candles
 * 返回字段顺序：[ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm]
 */
@Component
public class OkxClient {

    private static final Logger log = LoggerFactory.getLogger(OkxClient.class);
    private static final String BASE_URL = "https://www.okx.com/api/v5/market/candles";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OkxClient(ObjectMapper objectMapper, TradingProperties properties) {
        this.objectMapper = objectMapper;
        TradingProperties.Proxy proxyCfg = properties.getProxy();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyCfg.isEnabled()) {
            builder.proxy(ProxySelector.of(
                new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
            log.info("HTTP代理已启用: {}:{}", proxyCfg.getHost(), proxyCfg.getPort());
        }
        this.httpClient = builder.build();
    }

    /**
     * 拉取 K 线数据，返回时间正序列表（旧→新）
     *
     * @param instId    交易对，如 "BTC-USDT"
     * @param timeframe 周期，如 "1m" / "5m" / "1H"
     * @param limit     数量，最大 300
     */
    public List<KLine> fetchCandles(String instId, String timeframe, int limit) throws Exception {
        String url = String.format("%s?instId=%s&bar=%s&limit=%d", BASE_URL, instId, timeframe, limit);
        log.info("拉取 K 线: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OKX API 非200响应: " + response.statusCode());
        }

        return parseCandles(response.body());
    }

    private List<KLine> parseCandles(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        String code = root.path("code").asText();
        if (!"0".equals(code)) {
            throw new RuntimeException("OKX API 错误: " + root.path("msg").asText());
        }

        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new RuntimeException("OKX API data 字段不是数组");
        }

        List<KLine> klines = new ArrayList<>();
        for (JsonNode item : data) {
            klines.add(new KLine(
                item.get(0).asLong(),    // timestamp
                item.get(1).asDouble(),  // open
                item.get(2).asDouble(),  // high
                item.get(3).asDouble(),  // low
                item.get(4).asDouble(),  // close
                item.get(5).asDouble()   // volume
            ));
        }

        // OKX 返回最新在前，反转为时间正序
        Collections.reverse(klines);
        log.info("解析完成，共 {} 根 K 线", klines.size());
        return klines;
    }
}
