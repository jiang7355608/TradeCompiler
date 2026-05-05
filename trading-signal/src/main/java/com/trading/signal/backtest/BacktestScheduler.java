package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.service.BoxRangeDetector;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.MeanReversionStrategy;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 月度回测定时任务
 *
 * 每月1号凌晨2点自动执行：
 *   1. 抓取过去3个月的 BTC 15分钟K线数据
 *   2. 根据当前 trading.strategy 配置运行对应的回测引擎：
 *      - aggressive     → AggressiveBacktestEngine
 *      - mean-reversion → MeanReversionBacktestEngine（使用 BoxRangeDetector 当前箱体）
 *   3. 生成报告并通过邮件发送
 *
 * Cron 表达式：0 0 2 1 * ?
 *   即每月1号凌晨2:00执行
 */
@Component
public class BacktestScheduler {

    private static final Logger log = LoggerFactory.getLogger(BacktestScheduler.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradingProperties properties;
    private final BoxRangeDetector boxRangeDetector;

    public BacktestScheduler(TradingProperties properties, BoxRangeDetector boxRangeDetector) {
        this.properties = properties;
        this.boxRangeDetector = boxRangeDetector;
    }

    /**
     * 每月1号凌晨2点执行月度回测
     * 如需手动触发，可调用 runBacktest() 方法
     */
    @Scheduled(cron = "0 0 2 1 * ?")
    public void scheduledBacktest() {
        log.info("月度回测任务启动");
        runBacktest();
    }

    /**
     * 执行完整回测流程并发送邮件报告
     * 可被 REST 接口手动触发
     */
    public void runBacktest() {
        TradingProperties.Backtest btCfg = properties.getBacktest();
        TradingProperties.Email emailCfg = properties.getEmail();
        String strategy = properties.getStrategy();

        // 确保数据目录存在
        File dataDir = new File(btCfg.getDataDir());
        if (!dataDir.exists()) dataDir.mkdirs();

        // 时间范围：过去3个月
        LocalDate today = LocalDate.now();
        LocalDate threeMonthsAgo = today.minusMonths(3);
        long endMs = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long startMs = threeMonthsAgo.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

        String csvFile = btCfg.getDataDir() + "/btc_15m_"
            + threeMonthsAgo.format(DATE_FMT) + "_to_" + today.format(DATE_FMT) + ".csv";

        StringBuilder report = new StringBuilder();
        report.append("═══════════════════════════════════════════\n");
        report.append("  BTC 月度回测报告\n");
        report.append("  策略: ").append(strategy).append("\n");
        report.append("  回测区间: ").append(threeMonthsAgo.format(DATE_FMT))
              .append(" → ").append(today.format(DATE_FMT)).append("\n");
        report.append("  本金: ").append(btCfg.getInitialCapital())
              .append("U  杠杆: ").append(btCfg.getLeverage()).append("x\n");
        report.append("═══════════════════════════════════════════\n\n");

        try {
            // Step 1: 抓取数据
            log.info("开始抓取历史K线: {} → {}", threeMonthsAgo, today);
            TradingProperties.Proxy proxyCfg = properties.getProxy();
            DataFetcher fetcher = proxyCfg.isEnabled()
                ? new DataFetcher(proxyCfg.getHost(), proxyCfg.getPort())
                : new DataFetcher();
            fetcher.fetch(
                properties.getOkx().getInstId(),
                properties.getOkx().getTimeframe(),
                startMs, endMs, csvFile
            );
            report.append("数据抓取完成: ").append(csvFile).append("\n\n");

        } catch (Exception e) {
            log.error("数据抓取失败: {}", e.getMessage(), e);
            report.append("⚠ 数据抓取失败: ").append(e.getMessage()).append("\n");
            report.append("回测中止，请检查网络连接后重试\n");
            sendEmail(emailCfg, buildSubject(today, strategy), report.toString());
            return;
        }

        // Step 2: 根据策略执行回测
        if ("mean-reversion".equals(strategy)) {
            runMeanReversionBacktest(csvFile, btCfg, report);
        } else {
            runAggressiveBacktest(csvFile, btCfg, report);
        }

        // Step 3: 发送邮件
        String subject = buildSubject(today, strategy);
        sendEmail(emailCfg, subject, report.toString());
        log.info("月度回测报告已发送: {}", subject);
    }

    /**
     * 运行 AggressiveStrategy 回测
     */
    private void runAggressiveBacktest(String csvFile, TradingProperties.Backtest btCfg,
                                        StringBuilder report) {
        try {
            log.info("运行突破策略回测 (Aggressive)");
            AggressiveBacktestEngine engine = new AggressiveBacktestEngine();
            AggressiveStrategy strategy = new AggressiveStrategy(btCfg.getInitialCapital());

            BacktestResult result = engine.run(csvFile, strategy,
                btCfg.getInitialCapital(), btCfg.getLeverage());

            report.append(result.buildEmailReport("突破策略 (Aggressive)"));

        } catch (Exception e) {
            log.error("突破策略回测失败: {}", e.getMessage(), e);
            report.append("⚠ 突破策略回测失败: ").append(e.getMessage()).append("\n\n");
        }
    }

    /**
     * 运行 MeanReversionStrategy 回测
     * 使用 BoxRangeDetector 中当前识别到的箱体上下沿作为回测箱体
     */
    private void runMeanReversionBacktest(String csvFile, TradingProperties.Backtest btCfg,
                                           StringBuilder report) {
        try {
            log.info("运行均值回归策略回测 (MeanReversion)");

            // 获取箱体范围（优先使用 BoxRangeDetector 当前值）
            double rangeHigh;
            double rangeLow;

            if (boxRangeDetector.isValid()) {
                rangeHigh = boxRangeDetector.getCurrentRangeHigh();
                rangeLow  = boxRangeDetector.getCurrentRangeLow();
                log.info("使用 BoxRangeDetector 当前箱体: [{} - {}]",
                    String.format("%.0f", rangeLow), String.format("%.0f", rangeHigh));
            } else {
                log.warn("BoxRangeDetector 箱体无效，跳过 MeanReversion 回测");
                report.append("⚠ 均值回归策略回测跳过：当前箱体无效（BoxRangeDetector 未识别到有效箱体）\n");
                report.append("  建议：等待箱体识别完成后重新触发回测\n\n");
                return;
            }

            report.append(String.format("箱体配置: %.0f - %.0f (宽度 %.0f)\n\n",
                rangeLow, rangeHigh, rangeHigh - rangeLow));

            MeanReversionBacktestEngine.MockBoxRangeDetector mockBox =
                new MeanReversionBacktestEngine.MockBoxRangeDetector(rangeHigh, rangeLow);
            MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
            MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();

            BacktestResult result = engine.run(csvFile, strategy,
                btCfg.getInitialCapital(), btCfg.getLeverage());

            report.append(result.buildEmailReport("均值回归策略 (MeanReversion)"));

        } catch (Exception e) {
            log.error("均值回归策略回测失败: {}", e.getMessage(), e);
            report.append("⚠ 均值回归策略回测失败: ").append(e.getMessage()).append("\n\n");
        }
    }

    private String buildSubject(LocalDate date, String strategy) {
        String strategyLabel = "mean-reversion".equals(strategy) ? "均值回归" : "突破策略";
        return "[月度回测报告] BTC " + strategyLabel + " " + date.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
    }

    /**
     * 发送邮件（复用 EmailService 的 SMTP 配置，但独立实现避免循环依赖）
     */
    private void sendEmail(TradingProperties.Email cfg, String subject, String body) {
        if (!cfg.isEnabled()) {
            log.info("邮件未启用，回测报告仅打印到日志:\n{}", body);
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");
            props.put("mail.smtp.ssl.trust", "smtp.gmail.com");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(cfg.getSenderEmail(), cfg.getAppPassword());
                }
            });

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(cfg.getSenderEmail()));
            message.setRecipients(Message.RecipientType.TO,
                InternetAddress.parse(cfg.getReceiverEmail()));
            message.setSubject(subject);
            message.setText(body);
            Transport.send(message);

            log.info("回测报告邮件发送成功 → {}", cfg.getReceiverEmail());
        } catch (Exception e) {
            log.error("回测报告邮件发送失败: {}", e.getMessage(), e);
        }
    }
}
