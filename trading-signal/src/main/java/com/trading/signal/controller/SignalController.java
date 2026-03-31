package com.trading.signal.controller;

import com.trading.signal.backtest.BacktestEngine;
import com.trading.signal.backtest.BacktestResult;
import com.trading.signal.backtest.BacktestScheduler;
import com.trading.signal.backtest.ParameterOptimizer;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.SignalService;
import com.trading.signal.strategy.Strategy;
import com.trading.signal.strategy.StrategyRouter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST 接口
 *
 * GET  /api/signal/run       手动触发一次分析，返回最新信号
 * GET  /api/strategy         查询当前策略及所有可用策略
 * POST /api/strategy/{name}  热切换策略（无需重启）
 * POST /api/backtest/run     手动触发月度回测（不等每月1号）
 */
@RestController
@RequestMapping("/api")
public class SignalController {

    private final SignalService      signalService;
    private final StrategyRouter     strategyRouter;
    private final BacktestScheduler  backtestScheduler;
    private final ParameterOptimizer parameterOptimizer;
    private final TradingProperties  properties;

    public SignalController(SignalService signalService, StrategyRouter strategyRouter,
                            BacktestScheduler backtestScheduler,
                            ParameterOptimizer parameterOptimizer,
                            TradingProperties properties) {
        this.signalService      = signalService;
        this.strategyRouter     = strategyRouter;
        this.backtestScheduler  = backtestScheduler;
        this.parameterOptimizer = parameterOptimizer;
        this.properties         = properties;
    }

    @GetMapping("/signal/run")
    public ResponseEntity<?> runOnce() {
        try {
            TradeSignal signal = signalService.runOnce();
            return ResponseEntity.ok(Map.of(
                "action",     signal.getAction().getValue(),
                "confidence", signal.getConfidence(),
                "reason",     signal.getReason()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/strategy")
    public ResponseEntity<?> getStrategy() {
        return ResponseEntity.ok(Map.of(
            "current",   strategyRouter.current().getName(),
            "available", strategyRouter.availableStrategies()
        ));
    }

    @PostMapping("/strategy/{name}")
    public ResponseEntity<?> switchStrategy(@PathVariable String name) {
        try {
            strategyRouter.switchStrategy(name);
            return ResponseEntity.ok(Map.of("message", "策略已切换为: " + name, "current", name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 手动触发月度回测，异步执行，立即返回 */
    @PostMapping("/backtest/run")
    public ResponseEntity<?> triggerBacktest() {
        new Thread(() -> backtestScheduler.runBacktest()).start();
        return ResponseEntity.ok(Map.of(
            "message", "回测任务已启动，完成后将发送邮件报告"
        ));
    }

    /**
     * 参数优化：网格搜索最优参数组合
     * GET /api/optimize?top=5
     * 返回两个策略各自的 Top N 参数组合及评分
     */
    @GetMapping("/optimize")
    public ResponseEntity<?> optimizeParams(@RequestParam(defaultValue = "5") int top) {
        try {
            Map<String, Object> result = parameterOptimizer.optimize(top);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 分段回测：指定时间范围和策略
     * GET /api/backtest/range?start=2026-03-01&end=2026-03-31&strategy=mean-reversion
     *
     * start/end 格式：yyyy-MM-dd
     * strategy 可选：aggressive / conservative / mean-reversion（默认当前策略）
     */
    @GetMapping("/backtest/range")
    public ResponseEntity<?> backtestRange(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(required = false) String strategy) {
        // 异步执行，立即返回
        new Thread(() -> {
            try {
                runRangeBacktest(start, end, strategy);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        return ResponseEntity.ok(Map.of(
            "message", String.format("分段回测已启动 (%s → %s)，完成后发送邮件", start, end)));
    }

    private void runRangeBacktest(String start, String end, String strategy) throws Exception {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate startDate = LocalDate.parse(start, fmt);
        LocalDate endDate   = LocalDate.parse(end, fmt);
        long startMs = startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long endMs   = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

        String csvFile = ensureCsvData(startDate, endDate, startMs, endMs);
        BacktestEngine engine = new BacktestEngine(properties);
        List<KLine> allKlines = engine.loadCsv(csvFile);

        String strategyName = strategy != null ? strategy : properties.getStrategy();
        Strategy strat = strategyRouter.getStrategy(strategyName);
        BacktestResult result = engine.run(allKlines, strat, startMs, endMs);

        // 构建邮件
        StringBuilder body = new StringBuilder();
        body.append(String.format("分段回测报告: %s → %s\n", start, end));
        body.append(String.format("策略: %s\n", strategyName));
        body.append(String.format("数据: %s\n\n", csvFile));

        // 汇总统计
        int total = result.getTrades().size();
        if (total == 0) {
            body.append("本期无交易信号产生\n");
        } else {
            double winRate  = (double) result.getWins() / total * 100;
            double totalPnl = result.getCurrentCapital() - result.getInitialCapital();
            double avgWin   = result.getWins() > 0
                ? result.getTrades().stream().filter(t -> t.pnlU > 0).mapToDouble(t -> t.pnlU).average().orElse(0) : 0;
            double avgLoss  = result.getLosses() > 0
                ? result.getTrades().stream().filter(t -> t.pnlU <= 0).mapToDouble(t -> Math.abs(t.pnlU)).average().orElse(0) : 0;
            long slCount = result.getTrades().stream().filter(t -> "sl".equals(t.exitReason)).count();
            long tpCount = result.getTrades().stream().filter(t -> "tp".equals(t.exitReason)).count();

            body.append("═══ 汇总 ═══════════════════════════════════\n");
            body.append(String.format("  总盈亏: %+.2fU (%+.2f%%)\n", totalPnl, totalPnl / result.getInitialCapital() * 100));
            body.append(String.format("  交易: %d笔 (盈%d / 亏%d)  胜率: %.1f%%\n", total, result.getWins(), result.getLosses(), winRate));
            body.append(String.format("  平均盈利: +%.2fU  平均亏损: -%.2fU  盈亏比: %.2f\n", avgWin, avgLoss, avgLoss > 0 ? avgWin / avgLoss : 0));
            body.append(String.format("  止盈: %d次  止损: %d次 (止损率%.1f%%)\n", tpCount, slCount, (double) slCount / total * 100));
            body.append(String.format("  最大连续亏损: %d次  最大回撤: %.1f%%\n\n", result.getMaxConsecLosses(), result.getMaxDrawdown() * 100));

            // 每笔交易明细
            body.append("═══ 交易明细 ═══════════════════════════════\n");
            body.append(String.format("%-4s %-20s %-6s %-10s %-10s %-10s %-5s %-6s %s\n",
                "#", "时间", "方向", "入场价", "止损", "止盈", "仓位", "结果", "盈亏"));
            body.append("─".repeat(90)).append("\n");

            java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter
                .ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.of("UTC"));
            int idx = 1;
            for (BacktestResult.Trade t : result.getTrades()) {
                String time = timeFmt.format(java.time.Instant.ofEpochMilli(t.entryTime));
                body.append(String.format("%-4d %-20s %-6s %-10.2f %-10.2f %-10.2f %-5.0f%% %-6s %+.2fU\n",
                    idx++, time, t.direction.toUpperCase(),
                    t.entryPrice, t.stopLoss, t.takeProfit,
                    t.positionSize * 100, t.exitReason.toUpperCase(), t.pnlU));
            }

            // 拒绝原因统计（排查为什么不触发某方向信号）
            if (!result.getRejectReasons().isEmpty()) {
                body.append("\n═══ 信号拒绝原因统计（Top 15）═══════════════\n");
                result.getRejectReasons().entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> body.append(String.format("  %5d次  %s\n", e.getValue(), e.getKey())));
            }
        }

        // 发邮件
        TradingProperties.Email emailCfg = properties.getEmail();
        if (emailCfg.isEnabled()) {
            sendBacktestEmail(emailCfg,
                String.format("[分段回测] %s %s→%s", strategyName, start, end),
                body.toString());
        }
    }

    private void sendBacktestEmail(TradingProperties.Email cfg, String subject, String body) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");
            props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
            jakarta.mail.Session session = jakarta.mail.Session.getInstance(props,
                new jakarta.mail.Authenticator() {
                    protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new jakarta.mail.PasswordAuthentication(cfg.getSenderEmail(), cfg.getAppPassword());
                    }
                });
            jakarta.mail.Message msg = new jakarta.mail.internet.MimeMessage(session);
            msg.setFrom(new jakarta.mail.internet.InternetAddress(cfg.getSenderEmail()));
            msg.setRecipients(jakarta.mail.Message.RecipientType.TO,
                jakarta.mail.internet.InternetAddress.parse(cfg.getReceiverEmail()));
            msg.setSubject(subject);
            msg.setText(body);
            jakarta.mail.Transport.send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 确保有覆盖指定时间范围的CSV数据，没有则自动抓取
     */
    private String ensureCsvData(LocalDate startDate, LocalDate endDate, long startMs, long endMs)
            throws Exception {
        String dataDir = properties.getBacktest().getDataDir();
        new java.io.File(dataDir).mkdirs();

        DateTimeFormatter fileFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String csvFile = dataDir + "/btc_15m_" + startDate.format(fileFmt)
            + "_to_" + endDate.format(fileFmt) + ".csv";

        if (new java.io.File(csvFile).exists()) {
            return csvFile;
        }

        // 自动抓取数据
        TradingProperties.Proxy proxyCfg = properties.getProxy();
        com.trading.signal.backtest.DataFetcher fetcher = proxyCfg.isEnabled()
            ? new com.trading.signal.backtest.DataFetcher(proxyCfg.getHost(), proxyCfg.getPort())
            : new com.trading.signal.backtest.DataFetcher();
        fetcher.fetch(properties.getOkx().getInstId(), properties.getOkx().getTimeframe(),
            startMs, endMs, csvFile);
        return csvFile;
    }

}
