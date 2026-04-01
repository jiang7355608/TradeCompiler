package com.trading.signal.service;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmailService 单元测试
 *
 * 覆盖：
 * 1. 邮件标题分类（PROBE/CONFIRM/CANCEL/SIGNAL）
 * 2. 邮件正文字段完整性
 * 3. 过滤逻辑（NO_TRADE/低置信度/冷却期）
 * 4. 作废通知
 * 5. 冷却期时序测试
 * 6. 100条批量信号分类
 */
class EmailServiceTest {

    private TradingProperties properties;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        properties = new TradingProperties();
        TradingProperties.Email email = new TradingProperties.Email();
        email.setEnabled(false);
        email.setMinConfidence(0.6);
        email.setCooldownMs(300000); // 5分钟冷却
        email.setSenderEmail("test@test.com");
        email.setReceiverEmail("test@test.com");
        email.setAppPassword("test");
        properties.setEmail(email);

        TradingProperties.Okx okx = new TradingProperties.Okx();
        okx.setInstId("BTC-USDT");
        properties.setOkx(okx);

        emailService = new EmailService(properties);
    }

    // ═══ 邮件标题分类 ═══════════════════════════════════════════════════

    @Test
    void subject_probeSignal_containsProbeTag() throws Exception {
        TradeSignal signal = longSignal("PROBE UP: trend=up", 0.50);
        assertTrue(invokeSubject(signal).contains("[PROBE]"));
    }

    @Test
    void subject_mrProbeSignal_containsProbeTag() throws Exception {
        TradeSignal signal = longSignal("MR-PROBE LONG: price=66500 near lower", 0.60);
        assertTrue(invokeSubject(signal).contains("[PROBE]"));
    }

    @Test
    void subject_confirmSignal_containsConfirmTag() throws Exception {
        TradeSignal signal = longSignal("ADD UP: pnl=+2000 confirmed", 0.80);
        assertTrue(invokeSubject(signal).contains("[CONFIRM]"));
    }

    @Test
    void subject_mrAddSignal_containsConfirmTag() throws Exception {
        TradeSignal signal = shortSignal("MR-ADD DOWN: pnl=+1625 confirmed", 0.85);
        assertTrue(invokeSubject(signal).contains("[CONFIRM]"));
    }

    @Test
    void subject_probeTimeout_containsCancelTag() throws Exception {
        TradeSignal signal = longSignal("PROBE timeout — closing", 0.50);
        assertTrue(invokeSubject(signal).contains("[CANCEL]"));
    }

    @Test
    void subject_probeHitSl_containsCancelTag() throws Exception {
        TradeSignal signal = shortSignal("PROBE hit stop loss — closing", 0.50);
        assertTrue(invokeSubject(signal).contains("[CANCEL]"));
    }

    @Test
    void subject_mrProbeTimeout_containsCancelTag() throws Exception {
        TradeSignal signal = longSignal("MR-PROBE timeout 24h — closing", 0.50);
        assertTrue(invokeSubject(signal).contains("[CANCEL]"));
    }

    @Test
    void subject_mrProbeHitSl_containsCancelTag() throws Exception {
        TradeSignal signal = shortSignal("MR-PROBE hit stop loss — closing", 0.50);
        assertTrue(invokeSubject(signal).contains("[CANCEL]"));
    }

    @Test
    void subject_regularSignal_containsSignalTag() throws Exception {
        TradeSignal signal = shortSignal("MR-WIDE: rejection at upper 73000", 0.70);
        assertTrue(invokeSubject(signal).contains("[SIGNAL]"));
    }

    @Test
    void subject_containsInstId() throws Exception {
        TradeSignal signal = longSignal("PROBE UP: test", 0.50);
        assertTrue(invokeSubject(signal).contains("BTC-USDT"));
    }

    @Test
    void subject_containsDirection() throws Exception {
        TradeSignal signal = shortSignal("MR-ADD DOWN: confirmed", 0.85);
        assertTrue(invokeSubject(signal).contains("SHORT"));
    }

    // ═══ 邮件正文 ═══════════════════════════════════════════════════════

    @Test
    void body_containsAllFields() throws Exception {
        TradeSignal signal = new TradeSignal(TradeSignal.Action.LONG,
            "MR-PROBE LONG: price=66500", 0.65, 0.10, 64500, 69750);
        String body = invokeBody(signal, 66500);
        assertAll(
            () -> assertTrue(body.contains("LONG"), "方向"),
            () -> assertTrue(body.contains("66500"), "价格"),
            () -> assertTrue(body.contains("64500"), "止损"),
            () -> assertTrue(body.contains("69750"), "止盈"),
            () -> assertTrue(body.contains("0.65"), "置信度"),
            () -> assertTrue(body.contains("10%"), "仓位")
        );
    }

    @Test
    void body_shortSignal_containsShort() throws Exception {
        TradeSignal signal = new TradeSignal(TradeSignal.Action.SHORT,
            "MR-PROBE SHORT: price=73000", 0.60, 0.10, 75000, 69750);
        String body = invokeBody(signal, 73000);
        assertTrue(body.contains("SHORT"));
        assertTrue(body.contains("75000"));
    }

    @Test
    void body_highPosition_showsCorrectly() throws Exception {
        TradeSignal signal = new TradeSignal(TradeSignal.Action.LONG,
            "MR-ADD UP: confirmed", 0.85, 0.30, 66000, 69750);
        String body = invokeBody(signal, 67000);
        assertTrue(body.contains("30%"));
    }

    // ═══ 过滤逻辑 ═══════════════════════════════════════════════════════

    @Test
    void filter_noTrade_doesNotThrow() {
        TradeSignal noTrade = new TradeSignal(TradeSignal.Action.NO_TRADE, "no signal");
        assertDoesNotThrow(() -> emailService.sendSignalEmail(noTrade, 70000));
    }

    @Test
    void filter_lowConfidence_doesNotThrow() {
        // minConfidence=0.6, signal=0.3 → 不发送
        TradeSignal low = longSignal("test", 0.3);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(low, 70000));
    }

    @Test
    void filter_exactMinConfidence_doesNotThrow() {
        // 刚好等于阈值，应该不发（< 不是 <=）
        TradeSignal exact = longSignal("test", 0.6);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(exact, 70000));
    }

    @Test
    void filter_highConfidence_doesNotThrow() {
        TradeSignal high = longSignal("test", 0.8);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(high, 70000));
    }

    // ═══ 冷却期测试 ═══════════════════════════════════════════════════

    @Test
    void cooldown_sameDirection_withinCooldown_doesNotThrow() {
        // 第一次发送
        TradeSignal first = longSignal("MR-PROBE LONG: first", 0.70);
        emailService.sendSignalEmail(first, 66500);
        // 立即再发同方向（在冷却期内）
        TradeSignal second = longSignal("MR-PROBE LONG: second", 0.70);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(second, 66600));
    }

    @Test
    void cooldown_differentDirection_doesNotThrow() {
        // 先做多
        TradeSignal longSig = longSignal("MR-PROBE LONG: test", 0.70);
        emailService.sendSignalEmail(longSig, 66500);
        // 立即做空（不同方向，不受冷却限制）
        TradeSignal shortSig = shortSignal("MR-PROBE SHORT: test", 0.70);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(shortSig, 73000));
    }

    // ═══ 作废通知 ═══════════════════════════════════════════════════════

    @Test
    void cancelEmail_timeout_doesNotThrow() {
        assertDoesNotThrow(() ->
            emailService.sendCancelEmail("MR-PROBE timeout 24h — closing", 67000));
    }

    @Test
    void cancelEmail_stopLoss_doesNotThrow() {
        assertDoesNotThrow(() ->
            emailService.sendCancelEmail("MR-PROBE hit stop loss — closing", 64000));
    }

    @Test
    void cancelEmail_probeTimeout_doesNotThrow() {
        assertDoesNotThrow(() ->
            emailService.sendCancelEmail("PROBE timeout — closing", 73000));
    }

    // ═══ 100条批量信号分类 ═══════════════════════════════════════════════

    @Test
    void batch100_allTypesClassified() throws Exception {
        int probe = 0, confirm = 0, cancel = 0, signal = 0;

        // 构造10种不同类型的reason，每种10条，时间间隔足够大避免冷却
        String[] reasons = {
            "PROBE UP: trend=up momentum confirmed",
            "PROBE DOWN: trend=down momentum confirmed",
            "MR-PROBE LONG: price=66500 near lower 66500",
            "MR-PROBE SHORT: price=73000 near upper 73000",
            "ADD UP: pnl=+2000 confirmed",
            "MR-ADD DOWN: pnl=+1625 confirmed",
            "PROBE timeout — closing",
            "MR-PROBE hit stop loss — closing",
            "MR-WIDE: bounce at lower 66500",
            "ENTRY UP: pullback+bounce EMA-aligned",
        };

        for (int i = 0; i < 100; i++) {
            String reason = reasons[i % reasons.length];
            TradeSignal.Action action = reason.contains("SHORT") || reason.contains("DOWN")
                ? TradeSignal.Action.SHORT : TradeSignal.Action.LONG;
            TradeSignal sig = new TradeSignal(action, reason, 0.70, 0.10, 65000, 70000);

            String subject = invokeSubject(sig);
            if (subject.contains("[PROBE]")) probe++;
            else if (subject.contains("[CONFIRM]")) confirm++;
            else if (subject.contains("[CANCEL]")) cancel++;
            else if (subject.contains("[SIGNAL]")) signal++;
            else fail("未分类的信号: " + subject + " reason=" + reason);
        }

        int finalConfirm = confirm;
        int finalCancel = cancel;
        int finalProbe = probe;
        int finalSignal = signal;
        assertAll(
            () -> assertTrue(finalProbe > 0, "应有 PROBE 类型"),
            () -> assertTrue(finalConfirm > 0, "应有 CONFIRM 类型"),
            () -> assertTrue(finalCancel > 0, "应有 CANCEL 类型"),
            () -> assertTrue(finalSignal > 0, "应有 SIGNAL 类型"),
            () -> assertEquals(100, finalProbe + finalConfirm + finalCancel + finalSignal, "全部分类")
        );

        System.out.printf("100条分类: PROBE=%d CONFIRM=%d CANCEL=%d SIGNAL=%d%n",
            probe, confirm, cancel, signal);
    }

    // ═══ 边界条件 ═══════════════════════════════════════════════════════

    @Test
    void signal_zeroConfidence_doesNotThrow() {
        TradeSignal zero = new TradeSignal(TradeSignal.Action.NO_TRADE, "no signal");
        assertDoesNotThrow(() -> emailService.sendSignalEmail(zero, 70000));
    }

    @Test
    void signal_maxConfidence_doesNotThrow() {
        TradeSignal max = new TradeSignal(TradeSignal.Action.LONG,
            "test", 1.0, 0.30, 65000, 70000);
        assertDoesNotThrow(() -> emailService.sendSignalEmail(max, 70000));
    }

    @Test
    void cancelEmail_emptyReason_doesNotThrow() {
        assertDoesNotThrow(() -> emailService.sendCancelEmail("", 70000));
    }

    // ═══ 辅助方法 ═══════════════════════════════════════════════════════

    private TradeSignal longSignal(String reason, double confidence) {
        return new TradeSignal(TradeSignal.Action.LONG, reason, confidence, 0.10, 65000, 70000);
    }

    private TradeSignal shortSignal(String reason, double confidence) {
        return new TradeSignal(TradeSignal.Action.SHORT, reason, confidence, 0.10, 75000, 70000);
    }

    private String invokeSubject(TradeSignal signal) throws Exception {
        Method m = EmailService.class.getDeclaredMethod("buildSubject", TradeSignal.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(emailService, signal, "BTC-USDT");
    }

    private String invokeBody(TradeSignal signal, double price) throws Exception {
        Method m = EmailService.class.getDeclaredMethod("buildBody",
            TradeSignal.class, double.class, double.class, double.class);
        m.setAccessible(true);
        return (String) m.invoke(emailService, signal, price, signal.getStopLoss(), signal.getTakeProfit());
    }
}
