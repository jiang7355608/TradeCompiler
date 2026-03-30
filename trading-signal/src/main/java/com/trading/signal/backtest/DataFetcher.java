package com.trading.signal.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OKX 历史K线数据抓取器
 *
 * 使用 OKX history-candles 接口分页拉取，每次最多100根，
 * 通过 after 参数向前翻页，直到覆盖目标时间范围。
 *
 * 输出格式（CSV）：
 *   timestamp,open,high,low,close,volume
 *
 * OKX 接口说明：
 *   GET /api/v5/market/history-candles
 *   参数：instId, bar, limit(max=100), after(毫秒时间戳，返回此时间之前的数据)
 *   返回：最新在前，需要反转
 */
public class DataFetcher {

    private static final String BASE_URL  = "https://www.okx.com/api/v5/market/history-candles";
    private static final int    PAGE_SIZE = 100;
    // 每次请求之间的间隔，避免触发限流（OKX 公共接口限制 20次/2秒）
    private static final long   RATE_LIMIT_MS = 200;

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;

    public DataFetcher() {
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 支持代理的构造器（本地测试时使用）
     */
    public DataFetcher(String proxyHost, int proxyPort) {
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .proxy(ProxySelector.of(new java.net.InetSocketAddress(proxyHost, proxyPort)))
                .build();
        this.objectMapper = new ObjectMapper();
    }


    /**
     * 拉取指定时间范围的历史K线并写入 CSV 文件
     *
     * @param instId    交易对，如 "BTC-USDT"
     * @param bar       周期，如 "1m"
     * @param startMs   开始时间（毫秒时间戳，包含）
     * @param endMs     结束时间（毫秒时间戳，包含）
     * @param outputCsv 输出文件路径
     */
    public void fetch(String instId, String bar, long startMs, long endMs, String outputCsv)
            throws Exception {

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());

        System.out.printf("开始抓取 %s %s 数据%n", instId, bar);
        System.out.printf("时间范围: %s → %s%n",
            fmt.format(Instant.ofEpochMilli(startMs)),
            fmt.format(Instant.ofEpochMilli(endMs)));

        List<String[]> allRows = new ArrayList<>();
        long afterTs = endMs; // 从结束时间向前翻页
        int  page    = 0;

        while (true) {
            String url = String.format("%s?instId=%s&bar=%s&limit=%d&after=%d",
                BASE_URL, instId, bar, PAGE_SIZE, afterTs);

            List<String[]> rows = fetchPage(url);
            if (rows.isEmpty()) {
                System.out.println("  → 无更多数据，抓取完成");
                break;
            }

            // rows 是最新在前，找到最旧的时间戳
            long oldestTs = Long.parseLong(rows.get(rows.size() - 1)[0]);

            // 过滤掉 startMs 之前的数据
            int before = rows.size();
            rows.removeIf(r -> Long.parseLong(r[0]) < startMs);

            allRows.addAll(rows);
            page++;
            System.out.printf("  第%d页: %d根 (最旧: %s, 累计: %d根)%n",
                page, rows.size(),
                fmt.format(Instant.ofEpochMilli(oldestTs)),
                allRows.size());

            // 如果这页有数据被过滤掉，说明已经到达 startMs 边界
            if (rows.size() < before || oldestTs <= startMs) {
                System.out.println("  → 已到达起始时间，停止翻页");
                break;
            }

            // 下一页从当前最旧时间戳继续向前
            afterTs = oldestTs;

            // 限流
            Thread.sleep(RATE_LIMIT_MS);
        }

        if (allRows.isEmpty()) {
            System.out.println("警告：未抓取到任何数据");
            return;
        }

        // OKX 返回最新在前，反转为时间正序（旧→新）
        Collections.reverse(allRows);

        // 写入 CSV
        writeCsv(allRows, outputCsv);
        System.out.printf("%n✓ 共抓取 %d 根K线，已写入: %s%n", allRows.size(), outputCsv);
    }

    private List<String[]> fetchPage(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String code = root.path("code").asText();
        if (!"0".equals(code)) {
            throw new RuntimeException("OKX API 错误: " + root.path("msg").asText());
        }

        JsonNode data = root.path("data");
        List<String[]> rows = new ArrayList<>();
        for (JsonNode item : data) {
            // [ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm]
            rows.add(new String[]{
                item.get(0).asText(), // timestamp
                item.get(1).asText(), // open
                item.get(2).asText(), // high
                item.get(3).asText(), // low
                item.get(4).asText(), // close
                item.get(5).asText()  // volume
            });
        }
        return rows;
    }

    private void writeCsv(List<String[]> rows, String path) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("timestamp,open,high,low,close,volume");
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }
}
