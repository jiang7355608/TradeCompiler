package com.trading.signal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 交易系统配置管理器 - 简化版
 * 
 * 绑定 application.yml 中 trading.* 下的配置项
 * 
 * 配置结构：
 * - strategy: 策略选择（"aggressive"）
 * - okx: OKX API 连接配置
 * - tradeApi: OKX 交易 API 配置
 * - signal: 信号输出配置
 * - email: 邮件通知配置
 * - proxy: 代理配置
 * - backtest: 回测配置
 * 
 * 注意：删除了复杂的策略参数配置，所有参数硬编码在策略类中
 */
@Component
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private String strategy = "aggressive";
    private Okx okx = new Okx();
    private TradeApi tradeApi = new TradeApi();
    private Signal signal = new Signal();
    private Email email = new Email();
    private Proxy proxy = new Proxy();
    private Backtest backtest = new Backtest();

    // ── Getter/Setter ────────────────────────────────────────────────────

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    
    public Okx getOkx() { return okx; }
    public void setOkx(Okx okx) { this.okx = okx; }
    
    public TradeApi getTradeApi() { return tradeApi; }
    public void setTradeApi(TradeApi tradeApi) { this.tradeApi = tradeApi; }
    
    public Signal getSignal() { return signal; }
    public void setSignal(Signal signal) { this.signal = signal; }
    
    public Email getEmail() { return email; }
    public void setEmail(Email email) { this.email = email; }
    
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy proxy) { this.proxy = proxy; }
    
    public Backtest getBacktest() { return backtest; }
    public void setBacktest(Backtest backtest) { this.backtest = backtest; }

    // ── OKX 连接配置 ──────────────────────────────────────────────────────

    /**
     * OKX 公共 API 配置
     */
    public static class Okx {
        private String instId = "BTC-USDT";        // 交易对
        private String timeframe = "15m";          // K线周期
        private int limit = 50;                    // K线数量

        public String getInstId() { return instId; }
        public void setInstId(String instId) { this.instId = instId; }
        
        public String getTimeframe() { return timeframe; }
        public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
        
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
    }

    // ── 交易 API 配置 ─────────────────────────────────────────────────────

    /**
     * OKX 交易 API 配置
     */
    public static class TradeApi {
        private boolean enabled = false;           // 是否启用交易
        private String apiKey = "";                // API Key
        private String secretKey = "";             // Secret Key
        private String passphrase = "";            // Passphrase
        private boolean simulated = true;          // 是否模拟盘
        private int leverage = 20;                 // 杠杆倍数

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        
        public String getPassphrase() { return passphrase; }
        public void setPassphrase(String passphrase) { this.passphrase = passphrase; }
        
        public boolean isSimulated() { return simulated; }
        public void setSimulated(boolean simulated) { this.simulated = simulated; }
        
        public int getLeverage() { return leverage; }
        public void setLeverage(int leverage) { this.leverage = leverage; }
    }

    // ── 信号输出配置 ──────────────────────────────────────────────────────

    /**
     * 信号输出配置
     */
    public static class Signal {
        private String outputFile = "signal.json"; // 输出文件路径

        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String outputFile) { this.outputFile = outputFile; }
    }

    // ── 邮件配置 ──────────────────────────────────────────────────────────

    /**
     * 邮件通知配置
     */
    public static class Email {
        private String senderEmail = "";           // 发送方邮箱
        private String appPassword = "";           // 应用密码
        private String receiverEmail = "";         // 接收方邮箱
        private boolean enabled = false;           // 是否启用邮件
        private long cooldownMs = 300000;          // 冷却时间（5分钟）

        public String getSenderEmail() { return senderEmail; }
        public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
        
        public String getAppPassword() { return appPassword; }
        public void setAppPassword(String appPassword) { this.appPassword = appPassword; }
        
        public String getReceiverEmail() { return receiverEmail; }
        public void setReceiverEmail(String receiverEmail) { this.receiverEmail = receiverEmail; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public long getCooldownMs() { return cooldownMs; }
        public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }
    }

    // ── 代理配置 ──────────────────────────────────────────────────────────

    /**
     * HTTP 代理配置
     */
    public static class Proxy {
        private boolean enabled = false;           // 是否启用代理
        private String host = "127.0.0.1";        // 代理主机
        private int port = 7890;                   // 代理端口

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    // ── 回测配置 ──────────────────────────────────────────────────────────

    /**
     * 回测引擎配置
     */
    public static class Backtest {
        private double initialCapital = 200.0;     // 初始资金（USDT）
        private int leverage = 20;                 // 杠杆倍数
        private String dataDir = "backtest-data";  // 数据目录

        public double getInitialCapital() { return initialCapital; }
        public void setInitialCapital(double initialCapital) { this.initialCapital = initialCapital; }
        
        public int getLeverage() { return leverage; }
        public void setLeverage(int leverage) { this.leverage = leverage; }
        
        public String getDataDir() { return dataDir; }
        public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    }
}
