package com.trading.signal.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.signal.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OKX 交易 API 客户端（支持模拟盘）
 *
 * 接口：
 *   POST /api/v5/trade/order — 下单
 *   POST /api/v5/account/set-leverage — 设置杠杆
 *   POST /api/v5/trade/close-position — 平仓
 *
 * 模拟盘：请求头加 x-simulated-trading: 1
 * 签名：HMAC-SHA256(timestamp + method + path + body)
 */
@Component
public class OkxTradeClient {

    private static final Logger log = LoggerFactory.getLogger(OkxTradeClient.class);
    private static final String BASE_URL = "https://www.okx.com";

    private final TradingProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OkxTradeClient(TradingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        TradingProperties.Proxy proxyCfg = properties.getProxy();
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (proxyCfg.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
        }
        this.httpClient = builder.build();
    }

    /**
     * 设置杠杆倍数
     */
    public JsonNode setLeverage(String instId, int lever, String mgnMode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instId);
        body.put("lever", String.valueOf(lever));
        body.put("mgnMode", mgnMode); // "cross" 或 "isolated"
        return post("/api/v5/account/set-leverage", body);
    }

    /**
     * 下单
     * @param instId    交易对，如 "BTC-USDT-SWAP"
     * @param tdMode    交易模式："cross"(全仓) / "isolated"(逐仓)
     * @param side      "buy" / "sell"
     * @param posSide   "long" / "short"（双向持仓模式）
     * @param ordType   "market"(市价) / "limit"(限价)
     * @param sz        数量（张数）
     * @param px        价格（限价单必填，市价单不填）
     * @param slTriggerPx 止损触发价（可选）
     * @param tpTriggerPx 止盈触发价（可选）
     */
    public JsonNode placeOrder(String instId, String tdMode, String side, String posSide,
                                String ordType, String sz, String px,
                                String slTriggerPx, String tpTriggerPx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instId);
        body.put("tdMode", tdMode);
        body.put("side", side);
        if (posSide != null) body.put("posSide", posSide);
        body.put("ordType", ordType);
        body.put("sz", sz);
        if (px != null && !px.isEmpty()) body.put("px", px);

        // 附带止盈止损（TP/SL）
        if (slTriggerPx != null || tpTriggerPx != null) {
            var attachAlgoOrds = new java.util.ArrayList<Map<String, String>>();
            if (slTriggerPx != null) {
                Map<String, String> sl = new LinkedHashMap<>();
                sl.put("tpTriggerPxType", "last");
                sl.put("slTriggerPx", slTriggerPx);
                sl.put("slOrdPx", "-1"); // 市价止损
                sl.put("slTriggerPxType", "last");
                if (tpTriggerPx != null) {
                    sl.put("tpTriggerPx", tpTriggerPx);
                    sl.put("tpOrdPx", "-1"); // 市价止盈
                }
                attachAlgoOrds.add(sl);
            }
            body.put("attachAlgoOrds", attachAlgoOrds);
        }

        return post("/api/v5/trade/order", body);
    }

    /**
     * 查询账户余额（USDT）
     */
    public double getBalance() throws Exception {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        String path = "/api/v5/account/balance";
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        String sign = sign(timestamp, "GET", path, "", cfg.getSecretKey());

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("OK-ACCESS-KEY", cfg.getApiKey())
            .header("OK-ACCESS-SIGN", sign)
            .header("OK-ACCESS-TIMESTAMP", timestamp)
            .header("OK-ACCESS-PASSPHRASE", cfg.getPassphrase())
            .GET();
        if (cfg.isSimulated()) reqBuilder.header("x-simulated-trading", "1");

        HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = objectMapper.readTree(resp.body());

        if (!"0".equals(result.path("code").asText())) {
            log.error("查询余额失败: {}", result.path("msg").asText());
            return 0;
        }

        // 遍历币种找 USDT
        JsonNode details = result.path("data").get(0).path("details");
        for (JsonNode d : details) {
            if ("USDT".equals(d.path("ccy").asText())) {
                return d.path("availBal").asDouble();
            }
        }
        return 0;
    }

    /**
     * 查询当前持仓
     */
    public JsonNode getPositions(String instId) throws Exception {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        String path = "/api/v5/account/positions?instId=" + instId;
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        String sign = sign(timestamp, "GET", path, "", cfg.getSecretKey());

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("OK-ACCESS-KEY", cfg.getApiKey())
            .header("OK-ACCESS-SIGN", sign)
            .header("OK-ACCESS-TIMESTAMP", timestamp)
            .header("OK-ACCESS-PASSPHRASE", cfg.getPassphrase())
            .GET();
        if (cfg.isSimulated()) reqBuilder.header("x-simulated-trading", "1");

        HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(resp.body());
    }

    /**
     * 对整个持仓设置止损止盈（algo order）
     */
    public JsonNode placeAlgoOrder(String instId, String tdMode, String side,
                                    String slTriggerPx, String tpTriggerPx, String sz) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instId);
        body.put("tdMode", tdMode);
        body.put("side", side);           // 平仓方向：多头持仓用sell，空头用buy
        body.put("ordType", "oco");       // OCO = 止盈止损二选一
        body.put("sz", sz);               // 张数（整个持仓）
        if (slTriggerPx != null) {
            body.put("slTriggerPx", slTriggerPx);
            body.put("slOrdPx", "-1");    // 市价止损
            body.put("slTriggerPxType", "last");
        }
        if (tpTriggerPx != null) {
            body.put("tpTriggerPx", tpTriggerPx);
            body.put("tpOrdPx", "-1");    // 市价止盈
            body.put("tpTriggerPxType", "last");
        }
        return post("/api/v5/trade/order-algo", body);
    }

    /**
     * 市价平仓
     */
    public JsonNode closePosition(String instId, String mgnMode, String posSide) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instId);
        body.put("mgnMode", mgnMode);
        if (posSide != null) body.put("posSide", posSide);
        return post("/api/v5/trade/close-position", body);
    }

    // ── 签名和请求 ────────────────────────────────────────────────────

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        TradingProperties.TradeApi cfg = properties.getTradeApi();
        String bodyJson = objectMapper.writeValueAsString(body);
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        String sign = sign(timestamp, "POST", path, bodyJson, cfg.getSecretKey());

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("OK-ACCESS-KEY", cfg.getApiKey())
            .header("OK-ACCESS-SIGN", sign)
            .header("OK-ACCESS-TIMESTAMP", timestamp)
            .header("OK-ACCESS-PASSPHRASE", cfg.getPassphrase())
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson));

        // 模拟盘标识
        if (cfg.isSimulated()) {
            reqBuilder.header("x-simulated-trading", "1");
        }

        HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = objectMapper.readTree(resp.body());

        String code = result.path("code").asText();
        if (!"0".equals(code)) {
            log.error("OKX Trade API error: code={} msg={} path={} body={}",
                code, result.path("msg").asText(), path, result.toString());
        } else {
            log.info("OKX Trade API success: path={}", path);
        }
        return result;
    }

    private String sign(String timestamp, String method, String path, String body, String secretKey)
            throws Exception {
        String preSign = timestamp + method + path + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(preSign.getBytes("UTF-8")));
    }
}
