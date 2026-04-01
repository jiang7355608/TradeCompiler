package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MeanReversionStrategy 单元测试
 *
 * 覆盖：状态机流转、冷却期时序、熔断、边界条件
 */
class MeanReversionStrategyTest {

    private TradingProperties.StrategyParams params;
    private MeanReversionStrategy strategy;

    // 基准时间：2026-03-15 00:00 UTC
    private static final long BASE_TS = 1773792000000L;
    private static final long MIN_15 = 900000L;

    @BeforeEach
    void setUp() {
        params = new TradingProperties.StrategyParams();
        params.setMrRangeHigh(73000);
        params.setMrRangeLow(66500);
        params.setMrEntryBuffer(500);
        params.setMrCooldownMs(2700000); // 45分钟
        strategy = new MeanReversionStrategy(params);
    }

    // ═══ 状态机：IDLE → PROBE ═══════════════════════════════════════════

    @Test
    void idle_priceAtLowerBound_withBounce_triggersProbe() {
        MarketData data = buildData(66800, 66700, 66600, BASE_TS);
        // last收阳(66800>open) + last.close>prev.close
        TradeSignal signal = strategy.generateSignal(data);
        assertEquals(TradeSignal.Action.LONG, signal.getAction());
        assertTrue(signal.getReason().contains("MR-PROBE"));
        assertEquals(0.10, signal.getPositionSize());
        assertEquals(MeanReversionStrategy.State.PROBE, strategy.getState());
    }

    @Test
    void idle_priceAtUpperBound_withRejection_triggersProbe() {
        // 上沿做空：收阴 + close < prev.close
        MarketData data = buildShortData(72800, 72900, 73000, BASE_TS);
        TradeSignal signal = strategy.generateSignal(data);
        assertEquals(TradeSignal.Action.SHORT, signal.getAction());
        assertTrue(signal.getReason().contains("MR-PROBE"));
        assertEquals(MeanReversionStrategy.State.PROBE, strategy.getState());
    }

    @Test
    void idle_priceInMidRange_noSignal() {
        MarketData data = buildData(70000, 69900, 69800, BASE_TS);
        TradeSignal signal = strategy.generateSignal(data);
        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertEquals(MeanReversionStrategy.State.IDLE, strategy.getState());
    }

    @Test
    void idle_priceAtLowerBound_noBounce_noSignal() {
        // 价格在下沿但没有反弹（收阴）
        MarketData data = buildBearishData(66800, 66900, 67000, BASE_TS);
        TradeSignal signal = strategy.generateSignal(data);
        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
    }

    // ═══ 状态机：PROBE → CONFIRMED ══════════════════════════════════════

    @Test
    void probe_profitExceedsThreshold_afterTime_triggersAdd() {
        // 先触发试探仓
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);
        assertEquals(MeanReversionStrategy.State.PROBE, strategy.getState());

        // 价格涨到68500（浮盈1700 > 6500*0.25=1625），时间过了1小时
        long confirmTs = BASE_TS + 3600000L; // +1小时 > 45分钟
        MarketData confirm = buildData(68500, 68400, 68300, confirmTs);
        TradeSignal signal = strategy.generateSignal(confirm);

        assertEquals(TradeSignal.Action.LONG, signal.getAction());
        assertTrue(signal.getReason().contains("MR-ADD"));
        assertEquals(0.30, signal.getPositionSize());
        assertEquals(MeanReversionStrategy.State.CONFIRMED, strategy.getState());
    }

    @Test
    void probe_profitOk_butTimeTooShort_noAdd() {
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);

        // 浮盈够但时间不够（只过了15分钟 < 45分钟）
        long earlyTs = BASE_TS + MIN_15;
        MarketData early = buildData(68500, 68400, 68300, earlyTs);
        TradeSignal signal = strategy.generateSignal(early);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertEquals(MeanReversionStrategy.State.PROBE, strategy.getState());
    }

    // ═══ 状态机：PROBE → 止损 ═══════════════════════════════════════════

    @Test
    void probe_hitStopLoss_resetsToIdle() {
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);

        // 价格跌破止损
        long slTs = BASE_TS + MIN_15;
        MarketData sl = buildData(64000, 64100, 64200, slTs);
        TradeSignal signal = strategy.generateSignal(sl);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertTrue(signal.getReason().contains("stop loss"));
        assertEquals(MeanReversionStrategy.State.IDLE, strategy.getState());
    }

    // ═══ 状态机：PROBE → 超时 ═══════════════════════════════════════════

    @Test
    void probe_timeout_resetsToIdle() {
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);

        // 24小时后
        long timeoutTs = BASE_TS + 86400000L + MIN_15;
        MarketData timeout = buildData(67000, 66900, 66800, timeoutTs);
        TradeSignal signal = strategy.generateSignal(timeout);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertTrue(signal.getReason().contains("timeout"));
        assertEquals(MeanReversionStrategy.State.IDLE, strategy.getState());
    }

    // ═══ 冷却期 ═══════════════════════════════════════════════════════

    @Test
    void cooldown_withinPeriod_noSignal() {
        // 第一次入场
        MarketData first = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(first);
        strategy.reset(); // 模拟平仓

        // 冷却期内（+15分钟 < 45分钟冷却）再次尝试
        long coolTs = BASE_TS + MIN_15;
        MarketData second = buildData(66800, 66700, 66600, coolTs);
        TradeSignal signal = strategy.generateSignal(second);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertTrue(signal.getReason().contains("Cooldown"));
    }

    @Test
    void cooldown_afterPeriod_signalAllowed() {
        MarketData first = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(first);
        strategy.reset();

        // 冷却期后（+1小时 > 45分钟）
        long afterTs = BASE_TS + 3600000L;
        MarketData second = buildData(66800, 66700, 66600, afterTs);
        TradeSignal signal = strategy.generateSignal(second);

        assertEquals(TradeSignal.Action.LONG, signal.getAction());
    }

    // ═══ 熔断 ═══════════════════════════════════════════════════════════

    @Test
    void circuitBreaker_after3Losses_blocksTrading() {
        long ts = BASE_TS;
        strategy.recordTradeResult(false, ts);
        strategy.recordTradeResult(false, ts + 1000);
        strategy.recordTradeResult(false, ts + 2000); // 第3次触发熔断

        // 熔断期内（4小时）
        long blockedTs = ts + 3600000L; // +1小时
        MarketData data = buildData(66800, 66700, 66600, blockedTs);
        TradeSignal signal = strategy.generateSignal(data);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertTrue(signal.getReason().contains("Circuit breaker"));
    }

    @Test
    void circuitBreaker_afterExpiry_allowsTrading() {
        long ts = BASE_TS;
        strategy.recordTradeResult(false, ts);
        strategy.recordTradeResult(false, ts + 1000);
        strategy.recordTradeResult(false, ts + 2000);

        // 熔断期后（+5小时 > 4小时熔断）+ 冷却期后
        long afterTs = ts + 18000000L;
        MarketData data = buildData(66800, 66700, 66600, afterTs);
        TradeSignal signal = strategy.generateSignal(data);

        assertEquals(TradeSignal.Action.LONG, signal.getAction());
    }

    @Test
    void circuitBreaker_winResetsCounter() {
        long ts = BASE_TS;
        strategy.recordTradeResult(false, ts);
        strategy.recordTradeResult(false, ts + 1000);
        strategy.recordTradeResult(true, ts + 2000); // 赢了，重置计数
        strategy.recordTradeResult(false, ts + 3000); // 重新开始计数

        // 只亏了1次（重置后），不应触发熔断
        long checkTs = ts + 3600000L;
        MarketData data = buildData(66800, 66700, 66600, checkTs);
        TradeSignal signal = strategy.generateSignal(data);

        assertEquals(TradeSignal.Action.LONG, signal.getAction());
    }

    // ═══ CONFIRMED 状态 ═════════════════════════════════════════════════

    @Test
    void confirmed_doesNotAutoReset() {
        // 走完 IDLE → PROBE → CONFIRMED
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);

        long confirmTs = BASE_TS + 3600000L;
        MarketData confirm = buildData(68500, 68400, 68300, confirmTs);
        strategy.generateSignal(confirm);
        assertEquals(MeanReversionStrategy.State.CONFIRMED, strategy.getState());

        // 再调一次，应该还是 CONFIRMED（不自动reset）
        long nextTs = confirmTs + MIN_15;
        MarketData next = buildData(68600, 68500, 68400, nextTs);
        TradeSignal signal = strategy.generateSignal(next);

        assertEquals(TradeSignal.Action.NO_TRADE, signal.getAction());
        assertTrue(signal.getReason().contains("CONFIRMED"));
        assertEquals(MeanReversionStrategy.State.CONFIRMED, strategy.getState());
    }

    @Test
    void confirmHandedOff_resetsToIdle() {
        MarketData entry = buildData(66800, 66700, 66600, BASE_TS);
        strategy.generateSignal(entry);
        long confirmTs = BASE_TS + 3600000L;
        strategy.generateSignal(buildData(68500, 68400, 68300, confirmTs));

        strategy.confirmHandedOff();
        assertEquals(MeanReversionStrategy.State.IDLE, strategy.getState());
    }

    // ═══ 线程安全 ═══════════════════════════════════════════════════════

    @Test
    void concurrentAccess_doesNotThrow() throws Exception {
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final long ts = BASE_TS + i * 3600000L;
            threads[i] = new Thread(() -> {
                MarketData data = buildData(66800, 66700, 66600, ts);
                strategy.generateSignal(data);
                strategy.reset();
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(5000);
        // 不抛异常就算通过
    }

    // ═══ 辅助方法 ═══════════════════════════════════════════════════════

    /** 构造做多场景的MarketData：收阳 + close > prev.close */
    private MarketData buildData(double close, double prevClose, double prev2Close, long ts) {
        KLine last  = new KLine(ts, close - 100, close + 50, close - 150, close, 100);
        KLine prev  = new KLine(ts - MIN_15, prevClose - 50, prevClose + 50, prevClose - 100, prevClose, 80);
        KLine prev2 = new KLine(ts - MIN_15 * 2, prev2Close - 50, prev2Close + 50, prev2Close - 100, prev2Close, 70);
        return new MarketData(
            true, false, "none", true, close,
            67000, 66500, 0.003, last, 90,
            "neutral", false, "none", 67500, 66000,
            prev, prev2, 67000, 66800, 300, 66900, "none");
    }

    /** 构造做空场景：收阴 + close < prev.close */
    private MarketData buildShortData(double close, double prevClose, double prev2Close, long ts) {
        KLine last  = new KLine(ts, close + 100, close + 150, close - 50, close, 100);
        KLine prev  = new KLine(ts - MIN_15, prevClose, prevClose + 50, prevClose - 50, prevClose, 80);
        KLine prev2 = new KLine(ts - MIN_15 * 2, prev2Close, prev2Close + 50, prev2Close - 50, prev2Close, 70);
        return new MarketData(
            true, false, "none", false, close,
            73000, 72500, 0.003, last, 90,
            "neutral", false, "none", 73500, 72000,
            prev, prev2, 72800, 72600, 300, 72700, "none");
    }

    /** 构造下沿但收阴（无反弹）*/
    private MarketData buildBearishData(double close, double prevClose, double prev2Close, long ts) {
        KLine last  = new KLine(ts, close + 100, close + 150, close - 50, close, 100);
        KLine prev  = new KLine(ts - MIN_15, prevClose, prevClose + 50, prevClose - 50, prevClose, 80);
        KLine prev2 = new KLine(ts - MIN_15 * 2, prev2Close, prev2Close + 50, prev2Close - 50, prev2Close, 70);
        return new MarketData(
            true, false, "none", false, close,
            67000, 66500, 0.003, last, 90,
            "neutral", false, "none", 67500, 66000,
            prev, prev2, 66800, 66600, 300, 66700, "none");
    }
}
