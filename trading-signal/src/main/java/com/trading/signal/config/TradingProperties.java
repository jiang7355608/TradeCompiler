package com.trading.signal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 交易引擎全局配置
 * 绑定 application.yml 中 trading.* 下的所有配置项
 */
@Component
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private String strategy = "aggressive";
    private Okx okx = new Okx();
    private Scheduler scheduler = new Scheduler();
    private Signal signal = new Signal();
    private Email email = new Email();
    private Proxy proxy = new Proxy();
    private StrategyParams params = new StrategyParams();
    private Backtest backtest = new Backtest();

    public String getStrategy()              { return strategy; }
    public void setStrategy(String s)        { this.strategy = s; }
    public Okx getOkx()                      { return okx; }
    public void setOkx(Okx v)               { this.okx = v; }
    public Scheduler getScheduler()          { return scheduler; }
    public void setScheduler(Scheduler v)    { this.scheduler = v; }
    public Signal getSignal()                { return signal; }
    public void setSignal(Signal v)          { this.signal = v; }
    public Email getEmail()                  { return email; }
    public void setEmail(Email v)            { this.email = v; }
    public StrategyParams getParams()        { return params; }
    public void setParams(StrategyParams v)  { this.params = v; }
    public Backtest getBacktest()            { return backtest; }
    public void setBacktest(Backtest v)      { this.backtest = v; }
    public Proxy getProxy()                  { return proxy; }
    public void setProxy(Proxy v)            { this.proxy = v; }
    private TradeApi tradeApi = new TradeApi();
    public TradeApi getTradeApi()            { return tradeApi; }
    public void setTradeApi(TradeApi v)      { this.tradeApi = v; }

    // ── OKX 连接配置 ──────────────────────────────────────────────────────
    public static class Okx {
        private String instId    = "BTC-USDT";
        private String timeframe = "1m";
        private int    limit     = 50;

        public String getInstId()         { return instId; }
        public void setInstId(String v)   { this.instId = v; }
        public String getTimeframe()      { return timeframe; }
        public void setTimeframe(String v){ this.timeframe = v; }
        public int getLimit()             { return limit; }
        public void setLimit(int v)       { this.limit = v; }
    }

    // ── 定时任务配置 ──────────────────────────────────────────────────────
    public static class Scheduler {
        private long intervalMs = 60000;

        public long getIntervalMs()       { return intervalMs; }
        public void setIntervalMs(long v) { this.intervalMs = v; }
    }

    // ── 信号输出配置 ──────────────────────────────────────────────────────
    public static class Signal {
        private String outputFile = "signal.json";

        public String getOutputFile()       { return outputFile; }
        public void setOutputFile(String v) { this.outputFile = v; }
    }

    // ── 邮件配置 ──────────────────────────────────────────────────────────
    public static class Email {
        private String  senderEmail   = "";
        private String  appPassword   = "";
        private String  receiverEmail = "";
        private boolean enabled       = false;
        private long    cooldownMs    = 300000;

        public String  getSenderEmail()          { return senderEmail; }
        public void    setSenderEmail(String v)  { this.senderEmail = v; }
        public String  getAppPassword()          { return appPassword; }
        public void    setAppPassword(String v)  { this.appPassword = v; }
        public String  getReceiverEmail()        { return receiverEmail; }
        public void    setReceiverEmail(String v){ this.receiverEmail = v; }
        public boolean isEnabled()               { return enabled; }
        public void    setEnabled(boolean v)     { this.enabled = v; }
        public long    getCooldownMs()           { return cooldownMs; }
        public void    setCooldownMs(long v)     { this.cooldownMs = v; }
    }

    // ── 策略参数（所有可调参数集中在此）──────────────────────────────────
    public static class StrategyParams {
        // MarketAnalyzer
        private int    rangeWindow           = 6;
        private double rangeThreshold        = 0.006;
        private int    breakoutWindowLong    = 14;
        private int    volWindow             = 20;
        private double volumeSpikeMultiplier = 1.5;
        private int    emaShort              = 5;
        private int    emaLong               = 20;
        private double trendThreshold        = 0.0002;
        // AggressiveStrategy
        private double aggRiskRewardRatio   = 2.0;
        private double aggMinSlPct          = 0.002;
        private double aggMidRangeLower     = 0.30;
        private double aggMidRangeUpper     = 0.70;
        private double aggSweepAmplitude    = 0.01;
        private double aggSweepBodyRatio    = 0.50;
        private double aggPositionStrong    = 0.30;
        private double aggPositionWeak      = 0.15;
        private long   aggCooldownMs        = 300000;
        // AggressiveStrategy 状态机参数
        private double aggConfirmAtrMult    = 1.5;    // 确认阈值 = ATR × 此值
        private double aggSlAtrMult         = 1.5;    // 止损距离 = ATR × 此值
        private double aggTpAtrMult         = 4.5;    // 止盈距离 = ATR × 此值
        private long   aggProbeTimeoutMs    = 10800000; // 试探仓超时（3小时）
        private double aggTrendAtrMult      = 0.5;    // 趋势判定：EMA偏离 > ATR × 此值
        // ConservativeStrategy
        private double conRiskRewardRatio   = 2.5;
        private double conMinSlPct          = 0.002;
        private double conPositionBase      = 0.20;
        private double conPositionBonus     = 0.25;
        private long   conCooldownMs        = 600000;
        // MeanReversionStrategy
        private double mrBuffer             = 0.005;  // 接近上下沿的缓冲区（0.5%）
        private double mrMaxAmplitude       = 0.02;   // 最大振幅过滤（2%），超过不做反转
        private double mrRiskRewardRatio    = 2.0;    // 风险回报比
        private double mrSlBuffer           = 0.3;    // 止损=区间边沿外 区间宽度×此值
        private double mrMaxSlPct           = 0.01;   // 最大止损距离上限（价格的1%）
        private double mrPosition           = 0.20;   // 仓位比例
        private long   mrCooldownMs         = 2700000; // 冷却期45分钟
        private double mrTrendStrengthLimit = 0.002;  // 趋势强度上限
        private long   mrProbeTimeoutMs     = 10800000; // 试探仓超时3小时
        private long   mrConfirmedTimeoutMs = 21600000; // CONFIRMED 超时兜底（6小时）
        private double mrEntryBufferPct     = 0.10;    // 接近边沿的缓冲区（箱体宽度的百分比）
        private double mrRangeHigh          = 0;      // 手动箱体上沿（0=使用动态箱体）
        private double mrRangeLow           = 0;      // 手动箱体下沿（0=使用动态箱体）
        private double boxMaxWidth          = 12000;  // 箱体最大宽度（美元）
        private double boxMinWidth          = 1000;   // 箱体最小宽度（美元）
        private double accountBalance       = 0;      // 账户余额（0=使用默认值200U）

        public int    getRangeWindow()              { return rangeWindow; }
        public void   setRangeWindow(int v)         { this.rangeWindow = v; }
        public double getRangeThreshold()           { return rangeThreshold; }
        public void   setRangeThreshold(double v)   { this.rangeThreshold = v; }
        public int    getBreakoutWindowLong()       { return breakoutWindowLong; }
        public void   setBreakoutWindowLong(int v)  { this.breakoutWindowLong = v; }
        public int    getVolWindow()                { return volWindow; }
        public void   setVolWindow(int v)           { this.volWindow = v; }
        public double getVolumeSpikeMultiplier()    { return volumeSpikeMultiplier; }
        public void   setVolumeSpikeMultiplier(double v){ this.volumeSpikeMultiplier = v; }
        public int    getEmaShort()                 { return emaShort; }
        public void   setEmaShort(int v)            { this.emaShort = v; }
        public int    getEmaLong()                  { return emaLong; }
        public void   setEmaLong(int v)             { this.emaLong = v; }
        public double getTrendThreshold()           { return trendThreshold; }
        public void   setTrendThreshold(double v)   { this.trendThreshold = v; }
        public double getAggRiskRewardRatio()       { return aggRiskRewardRatio; }
        public void   setAggRiskRewardRatio(double v){ this.aggRiskRewardRatio = v; }
        public double getAggMinSlPct()              { return aggMinSlPct; }
        public void   setAggMinSlPct(double v)      { this.aggMinSlPct = v; }
        public double getAggMidRangeLower()         { return aggMidRangeLower; }
        public void   setAggMidRangeLower(double v) { this.aggMidRangeLower = v; }
        public double getAggMidRangeUpper()         { return aggMidRangeUpper; }
        public void   setAggMidRangeUpper(double v) { this.aggMidRangeUpper = v; }
        public double getAggSweepAmplitude()        { return aggSweepAmplitude; }
        public void   setAggSweepAmplitude(double v){ this.aggSweepAmplitude = v; }
        public double getAggSweepBodyRatio()        { return aggSweepBodyRatio; }
        public void   setAggSweepBodyRatio(double v){ this.aggSweepBodyRatio = v; }
        public double getAggPositionStrong()        { return aggPositionStrong; }
        public void   setAggPositionStrong(double v){ this.aggPositionStrong = v; }
        public double getAggPositionWeak()          { return aggPositionWeak; }
        public void   setAggPositionWeak(double v)  { this.aggPositionWeak = v; }
        public long   getAggCooldownMs()            { return aggCooldownMs; }
        public void   setAggCooldownMs(long v)      { this.aggCooldownMs = v; }
        public double getAggConfirmAtrMult()        { return aggConfirmAtrMult; }
        public void   setAggConfirmAtrMult(double v){ this.aggConfirmAtrMult = v; }
        public double getAggSlAtrMult()             { return aggSlAtrMult; }
        public void   setAggSlAtrMult(double v)     { this.aggSlAtrMult = v; }
        public double getAggTpAtrMult()             { return aggTpAtrMult; }
        public void   setAggTpAtrMult(double v)     { this.aggTpAtrMult = v; }
        public long   getAggProbeTimeoutMs()        { return aggProbeTimeoutMs; }
        public void   setAggProbeTimeoutMs(long v)  { this.aggProbeTimeoutMs = v; }
        public double getAggTrendAtrMult()          { return aggTrendAtrMult; }
        public void   setAggTrendAtrMult(double v)  { this.aggTrendAtrMult = v; }
        public double getConRiskRewardRatio()       { return conRiskRewardRatio; }
        public void   setConRiskRewardRatio(double v){ this.conRiskRewardRatio = v; }
        public double getConMinSlPct()              { return conMinSlPct; }
        public void   setConMinSlPct(double v)      { this.conMinSlPct = v; }
        public double getConPositionBase()          { return conPositionBase; }
        public void   setConPositionBase(double v)  { this.conPositionBase = v; }
        public double getConPositionBonus()         { return conPositionBonus; }
        public void   setConPositionBonus(double v) { this.conPositionBonus = v; }
        public long   getConCooldownMs()            { return conCooldownMs; }
        public void   setConCooldownMs(long v)      { this.conCooldownMs = v; }
        public double getMrBuffer()                 { return mrBuffer; }
        public void   setMrBuffer(double v)         { this.mrBuffer = v; }
        public double getMrMaxAmplitude()            { return mrMaxAmplitude; }
        public void   setMrMaxAmplitude(double v)   { this.mrMaxAmplitude = v; }
        public double getMrRiskRewardRatio()         { return mrRiskRewardRatio; }
        public void   setMrRiskRewardRatio(double v) { this.mrRiskRewardRatio = v; }
        public double getMrSlBuffer()               { return mrSlBuffer; }
        public void   setMrSlBuffer(double v)       { this.mrSlBuffer = v; }
        public double getMrMaxSlPct()               { return mrMaxSlPct; }
        public void   setMrMaxSlPct(double v)       { this.mrMaxSlPct = v; }
        public double getMrPosition()               { return mrPosition; }
        public void   setMrPosition(double v)       { this.mrPosition = v; }
        public long   getMrCooldownMs()             { return mrCooldownMs; }
        public void   setMrCooldownMs(long v)       { this.mrCooldownMs = v; }
        public double getMrTrendStrengthLimit()      { return mrTrendStrengthLimit; }
        public void   setMrTrendStrengthLimit(double v) { this.mrTrendStrengthLimit = v; }
        public long   getMrProbeTimeoutMs()          { return mrProbeTimeoutMs; }
        public void   setMrProbeTimeoutMs(long v)    { this.mrProbeTimeoutMs = v; }
        public long   getMrConfirmedTimeoutMs()      { return mrConfirmedTimeoutMs; }
        public void   setMrConfirmedTimeoutMs(long v){ this.mrConfirmedTimeoutMs = v; }
        public double getMrEntryBufferPct()         { return mrEntryBufferPct; }
        public void   setMrEntryBufferPct(double v) { this.mrEntryBufferPct = v; }
        public double getMrRangeHigh()              { return mrRangeHigh; }
        public void   setMrRangeHigh(double v)      { this.mrRangeHigh = v; }
        public double getMrRangeLow()               { return mrRangeLow; }
        public void   setMrRangeLow(double v)       { this.mrRangeLow = v; }
        public double getBoxMaxWidth()              { return boxMaxWidth; }
        public void   setBoxMaxWidth(double v)      { this.boxMaxWidth = v; }
        public double getBoxMinWidth()              { return boxMinWidth; }
        public void   setBoxMinWidth(double v)      { this.boxMinWidth = v; }
        public double getAccountBalance()           { return accountBalance; }
        public void   setAccountBalance(double v)   { this.accountBalance = v; }
    }

    // ── 回测配置 ──────────────────────────────────────────────────────────
    public static class Backtest {
        private double initialCapital = 200.0;
        private int    leverage       = 20;
        private String dataDir        = "backtest-data";
        private String reportSubject  = "[月度回测报告] BTC交易策略";

        public double getInitialCapital()        { return initialCapital; }
        public void   setInitialCapital(double v){ this.initialCapital = v; }
        public int    getLeverage()              { return leverage; }
        public void   setLeverage(int v)         { this.leverage = v; }
        public String getDataDir()               { return dataDir; }
        public void   setDataDir(String v)       { this.dataDir = v; }
        public String getReportSubject()         { return reportSubject; }
        public void   setReportSubject(String v) { this.reportSubject = v; }
    }

    // ── 代理配置 ──────────────────────────────────────────────────────────
    public static class Proxy {
        private boolean enabled = false;
        private String  host    = "127.0.0.1";
        private int     port    = 7890;

        public boolean isEnabled()          { return enabled; }
        public void    setEnabled(boolean v){ this.enabled = v; }
        public String  getHost()            { return host; }
        public void    setHost(String v)    { this.host = v; }
        public int     getPort()            { return port; }
        public void    setPort(int v)       { this.port = v; }
    }

    public static class TradeApi {
        private boolean enabled    = false;
        private String  apiKey     = "";
        private String  secretKey  = "";
        private String  passphrase = "";
        private boolean simulated  = true;
        private int     leverage   = 20;

        public boolean isEnabled()            { return enabled; }
        public void    setEnabled(boolean v)  { this.enabled = v; }
        public String  getApiKey()            { return apiKey; }
        public void    setApiKey(String v)    { this.apiKey = v; }
        public String  getSecretKey()         { return secretKey; }
        public void    setSecretKey(String v) { this.secretKey = v; }
        public String  getPassphrase()        { return passphrase; }
        public void    setPassphrase(String v){ this.passphrase = v; }
        public boolean isSimulated()          { return simulated; }
        public void    setSimulated(boolean v){ this.simulated = v; }
        public int     getLeverage()          { return leverage; }
        public void    setLeverage(int v)     { this.leverage = v; }
    }
}
