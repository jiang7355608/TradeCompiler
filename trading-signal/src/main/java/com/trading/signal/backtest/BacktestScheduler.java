package com.trading.signal.backtest;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.service.EmailService;
import com.trading.signal.strategy.AggressiveStrategy;
import com.trading.signal.strategy.ConservativeStrategy;
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
 *   1. 抓取过去3个月的 BTC 1分钟K线数据
 *   2. 对 AggressiveStrategy 和 ConservativeStrategy 分别回测
 *   3. 生成报告并通过邮件发送
 *   4. 报告中包含参数调整建议
 *
 * Cron 表达式：0 0 2 1 * ?
 *   秒=0, 分=0, 时=2, 日=1, 月=*, 周=?
 *   即每月1号凌晨2:00执行
 */
@Component
public class BacktestScheduler {

    private static final Logger log = LoggerFactory.getLogger(BacktestScheduler.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradingProperties properties;

    public BacktestScheduler(TradingProperties properties) {
        this.properties = properties;
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
        TradingProperties.Backtest btCfg  = properties.getBacktest();
        TradingProperties.Email    emailCfg = properties.getEmail();

        // 确保数据目录存在
        File dataDir = new File(btCfg.getDataDir());
        if (!dataDir.exists()) dataDir.mkdirs();

        // 时间范围：过去3个月
        LocalDate today    = LocalDate.now();
        LocalDate threeMonthsAgo = today.minusMonths(3);
        long endMs   = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long startMs = threeMonthsAgo.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

        String csvFile = btCfg.getDataDir() + "/btc_15m_"
            + threeMonthsAgo.format(DATE_FMT) + "_to_" + today.format(DATE_FMT) + ".csv";

        StringBuilder report = new StringBuilder();
        report.append("═══════════════════════════════════════════\n");
        report.append("  BTC 交易策略月度回测报告\n");
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
            sendEmail(emailCfg, buildSubject(today), report.toString());
            return;
        }

        // Step 2: 回测两个策略
        BacktestEngine engine = new BacktestEngine(properties);
        TradingProperties.StrategyParams params = properties.getParams();

        try {
            BacktestResult aggResult = engine.run(csvFile, new AggressiveStrategy(params));
            report.append(aggResult.buildEmailReport("激进策略 (Aggressive)", params, "aggressive"));
        } catch (Exception e) {
            log.error("激进策略回测失败: {}", e.getMessage(), e);
            report.append("⚠ 激进策略回测失败: ").append(e.getMessage()).append("\n\n");
        }

        try {
            BacktestResult conResult = engine.run(csvFile, new ConservativeStrategy(params));
            report.append(conResult.buildEmailReport("保守策略 (Conservative)", params, "conservative"));
        } catch (Exception e) {
            log.error("保守策略回测失败: {}", e.getMessage(), e);
            report.append("⚠ 保守策略回测失败: ").append(e.getMessage()).append("\n\n");
        }

        try {
            BacktestResult mrResult = engine.run(csvFile, new MeanReversionStrategy(params));
            report.append(mrResult.buildEmailReport("均值回归策略 (Mean Reversion)", params, "mean-reversion"));
        } catch (Exception e) {
            log.error("均值回归策略回测失败: {}", e.getMessage(), e);
            report.append("⚠ 均值回归策略回测失败: ").append(e.getMessage()).append("\n\n");
        }

        // Step 3: 附上当前参数供参考
        report.append(buildParamsSummary(params));

        // Step 4: 发送邮件
        String subject = buildSubject(today);
        sendEmail(emailCfg, subject, report.toString());
        log.info("月度回测报告已发送: {}", subject);
    }

    /**
     * 生成当前参数摘要，方便对照回测结果决定是否调整
     */
    private String buildParamsSummary(TradingProperties.StrategyParams p) {
        return String.format(
            "═══════════════════════════════════════════\n" +
            "  当前策略参数（供参考，如需调整请修改 application.yml）\n" +
            "───────────────────────────────────────────\n" +
            "  [分析器]\n" +
            "  横盘窗口: %d根  横盘阈值: %.3f%%\n" +
            "  突破验证窗口: %d根  均量窗口: %d根\n" +
            "  放量倍数: %.1fx  EMA: %d/%d  趋势阈值: %.4f\n" +
            "───────────────────────────────────────────\n" +
            "  [激进策略]\n" +
            "  风险回报比: %.1f  最小止损兜底: %.2f%%\n" +
            "  强信号仓位: %.0f%%  弱信号仓位: %.0f%%\n" +
            "  冷却: %d分钟\n" +
            "───────────────────────────────────────────\n" +
            "  [保守策略]\n" +
            "  风险回报比: %.1f  最小止损兜底: %.2f%%\n" +
            "  标准仓位: %.0f%%  加强仓位: %.0f%%\n" +
            "  冷却: %d分钟\n" +
            "═══════════════════════════════════════════\n",
            p.getRangeWindow(), p.getRangeThreshold() * 100,
            p.getBreakoutWindowLong(), p.getVolWindow(),
            p.getVolumeSpikeMultiplier(), p.getEmaShort(), p.getEmaLong(), p.getTrendThreshold(),
            p.getAggRiskRewardRatio(), p.getAggMinSlPct() * 100,
            p.getAggPositionStrong() * 100, p.getAggPositionWeak() * 100,
            p.getAggCooldownMs() / 60000,
            p.getConRiskRewardRatio(), p.getConMinSlPct() * 100,
            p.getConPositionBase() * 100, p.getConPositionBonus() * 100,
            p.getConCooldownMs() / 60000
        );
    }

    private String buildSubject(LocalDate date) {
        return properties.getBacktest().getReportSubject()
            + " " + date.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
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
            props.put("mail.smtp.auth",            "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host",            "smtp.gmail.com");
            props.put("mail.smtp.port",            "587");
            props.put("mail.smtp.ssl.trust",       "smtp.gmail.com");

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
