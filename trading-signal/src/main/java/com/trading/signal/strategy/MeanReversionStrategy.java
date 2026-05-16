package com.trading.signal.strategy;

import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.BoxRangeDetector;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MeanReversionStrategy — 箱体震荡策略（试探仓 → 加仓 → 整仓 SL/TP）
 *
 * 状态机：IDLE → PROBE → CONFIRMED → IDLE
 *
 * ── 状态流转 ──────────────────────────────────────────────────────────
 * IDLE
 *   价格触及箱体边缘 + K线方向确认 → 发出 MR-PROBE 信号
 *   信号特征：positionSize=0.10, confidence=0.60, stopLoss=0, takeProfit=0
 *   TradeExecutor 识别为试探仓：不带交易所 SL/TP，由策略监控
 *   进入 PROBE
 *
 * PROBE
 *   情况1：浮盈 ≥ 箱体宽度×25% 且 持仓 ≥ 45min
 *          → 发出 MR-ADD 信号
 *          → 信号特征：positionSize=0.30, confidence=0.85, stopLoss=保护位, takeProfit=箱体中线
 *          → TradeExecutor 识别为加仓：attachAlgoOrds 原子绑定整仓 SL/TP
 *          → 进入 CONFIRMED
 *   情况2：价格触及虚拟止损（入场 ± 箱体宽度×15%）
 *          → 内部 reset（试探仓裸奔，仓位小可接受；或外部检测后调用 closeProbe）
 *          → 回到 IDLE
 *   情况3：持仓超过 3 小时仍未达加仓条件
 *          → reset → IDLE
 *
 * CONFIRMED
 *   仓位已绑定整仓 SL/TP，交易所自动处理。
 *   等待 confirmHandedOff() 或 onPositionClosed() 后回到 IDLE。
 *
 * ── 与 TradeExecutor#execute 的契约 ────────────────────────────────────
 *   - reason 含 "PROBE" 且不含 "ADD"  → 试探仓开仓（stopLoss 必须为 0）
 *   - reason 含 "ADD"                 → 加仓开仓（stopLoss > 0, takeProfit > 0）
 *   - confidence 用于 TradeExecutor 内部仓位调整：actual = positionSize × confidence
 *
 * ── 风控 ────────────────────────────────────────────────────────────
 *   - 全局冷却 15min（避免连续开仓）
 *   - 单方向连亏 2 次 → 该方向暂停 1h
 *   - 箱体失效时暂停交易
 *   - 永久熔断由 TradeExecutor 兜底
 */
@Component
public class MeanReversionStrategy implements Strategy {

    /** 策略状态机 */
    public enum State { IDLE, PROBE, CONFIRMED }

    // ── 参数（硬编码，避免和 TradingProperties 耦合）────────────────────
    private static final double PROBE_POSITION_SIZE   = 0.10;   // 试探仓基础仓位
    private static final double PROBE_CONFIDENCE      = 0.60;   // 试探仓置信度
    private static final double ADD_POSITION_SIZE     = 0.30;   // 加仓基础仓位
    private static final double ADD_CONFIDENCE        = 0.85;   // 加仓置信度

    private static final double ENTRY_BUFFER_RATIO    = 0.15;   // 入场区：箱体宽度的 15%
    private static final double CONFIRM_PROFIT_RATIO  = 0.25;   // 加仓阈值：箱体宽度的 25%
    private static final double PROTECT_STOP_RATIO    = 0.05;   // 加仓保护止损：箱体宽度的 5%
    private static final double VIRTUAL_STOP_RATIO    = 0.15;   // 试探仓虚拟止损：箱体宽度的 15%

    private static final long   GLOBAL_COOLDOWN_MS    = 15 * 60 * 1000L;       // 15min
    private static final long   PROBE_HOLD_MIN_MS     = 45 * 60 * 1000L;       // 加仓最小持仓 45min
    private static final long   PROBE_TIMEOUT_MS      = 3  * 60 * 60 * 1000L;  // 试探仓超时 3h
    private static final long   DIR_PAUSE_MS          = 60 * 60 * 1000L;       // 方向暂停 1h

    private final BoxRangeDetector boxRangeDetector;

    // ── 状态（synchronized 保护）────────────────────────────────────────
    private State  state           = State.IDLE;
    private String direction;                       // "long" / "short"
    private double probeEntryPrice;
    private long   probeEntryTime;
    private double virtualStopLoss;                 // PROBE 状态下的虚拟止损（不下到交易所）
    private double addStopLoss;                     // ADD 信号附带的整仓止损
    private double addTakeProfit;                   // ADD 信号附带的整仓止盈
    private long   confirmedTime;                   // 进入 CONFIRMED 的时间

    // 风控
    private long   lastTradeTime;
    private long   lastLongFailTime;
    private long   lastShortFailTime;
    private int    consecutiveLongFails;
    private int    consecutiveShortFails;

    // 账户余额（实盘由 SignalService 注入，回测使用固定值）
    private double accountBalance = 200.0;

    public MeanReversionStrategy(BoxRangeDetector boxRangeDetector) {
        this.boxRangeDetector = boxRangeDetector;
    }

    @Override
    public String getName() {
        return "mean-reversion";
    }

    // ── 外部访问 ────────────────────────────────────────────────────────

    public void setAccountBalance(double balance) { this.accountBalance = balance; }
    public double getAccountBalance()             { return accountBalance; }

    public synchronized State   getState()       { return state; }
    public synchronized String  getDirection()   { return direction; }
    public synchronized double  getStopLoss()    { return state == State.PROBE ? virtualStopLoss : addStopLoss; }
    public synchronized double  getTakeProfit()  { return addTakeProfit; }
    public synchronized boolean hasPosition()    { return state != State.IDLE; }

    /** TradeExecutor 加仓成功后调用，确认 CONFIRMED 已交付给交易所 */
    public synchronized void confirmHandedOff() {
        if (state == State.CONFIRMED) {
            reset();
        }
    }

    /** 交易所已无持仓（被 SL/TP 触发或手动平仓），重置策略状态 */
    public synchronized void onPositionClosed() {
        reset();
    }

    /**
     * 记录交易结果（用于方向暂停逻辑）
     * @param dir       "long" / "short"
     * @param isWin     是否盈利
     * @param timestamp K线时间戳
     */
    public synchronized void recordTradeResult(String dir, boolean isWin, long timestamp) {
        if ("long".equals(dir)) {
            if (isWin) consecutiveLongFails = 0;
            else { consecutiveLongFails++; lastLongFailTime = timestamp; }
        } else if ("short".equals(dir)) {
            if (isWin) consecutiveShortFails = 0;
            else { consecutiveShortFails++; lastShortFailTime = timestamp; }
        }
        reset();
    }

    /** 重置状态（外部回测使用 / 策略切换使用） */
    public synchronized void reset() {
        state            = State.IDLE;
        direction        = null;
        probeEntryPrice  = 0;
        probeEntryTime   = 0;
        virtualStopLoss  = 0;
        addStopLoss      = 0;
        addTakeProfit    = 0;
        confirmedTime    = 0;
    }

    /** 全量重置（包含冷却和失败计数） */
    public synchronized void hardReset() {
        reset();
        lastTradeTime         = 0;
        lastLongFailTime      = 0;
        lastShortFailTime     = 0;
        consecutiveLongFails  = 0;
        consecutiveShortFails = 0;
    }

    // ── 主入口 ─────────────────────────────────────────────────────────

    @Override
    public synchronized TradeSignal generateSignal(MarketData data) {
        return switch (state) {
            case IDLE      -> evaluateEntry(data);
            case PROBE     -> evaluateAddOrExit(data);
            case CONFIRMED -> evaluateConfirmed(data);
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // IDLE → 寻找试探仓入场
    // ═══════════════════════════════════════════════════════════════════
    private TradeSignal evaluateEntry(MarketData data) {
        double atr   = data.getAtr();
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        if (atr <= 0) return noTrade("ATR=0");
        if (!boxRangeDetector.isValid()) return noTrade("Box range not valid");

        // 全局冷却
        if (now - lastTradeTime < GLOBAL_COOLDOWN_MS) {
            return noTrade(String.format("Global cooldown (%.0fmin remaining)",
                    (GLOBAL_COOLDOWN_MS - (now - lastTradeTime)) / 60000.0));
        }

        double rangeHigh = boxRangeDetector.getCurrentRangeHigh();
        double rangeLow  = boxRangeDetector.getCurrentRangeLow();
        double boxWidth  = rangeHigh - rangeLow;
        if (boxWidth <= 0) return noTrade("Invalid box width");

        double buffer    = boxWidth * ENTRY_BUFFER_RATIO;
        double virtStop  = boxWidth * VIRTUAL_STOP_RATIO;

        KLine last = data.getLastKline();
        KLine prev = getPrevKline(data);

        // ── 下沿试探：做多 ─────────────────────────────────────────────
        if (price <= rangeLow + buffer && price > rangeLow) {
            // 方向暂停检查
            if (consecutiveLongFails >= 2 && now - lastLongFailTime < DIR_PAUSE_MS) {
                return noTrade("LONG direction paused (2 fails, wait 1h)");
            }
            // K线方向确认：收阳 + 高于前一根（避免下跌途中的虚假触及）
            if (prev != null && !(last.getClose() > last.getOpen()
                                 && last.getClose() > prev.getClose())) {
                return noTrade(String.format(
                        "Near support %.0f but no bullish confirmation (close=%.0f open=%.0f prev=%.0f)",
                        rangeLow, last.getClose(), last.getOpen(), prev.getClose()));
            }

            return openProbe("long", price, now, price - virtStop, rangeHigh, rangeLow, boxWidth);
        }

        // ── 上沿试探：做空 ─────────────────────────────────────────────
        if (price >= rangeHigh - buffer && price < rangeHigh) {
            if (consecutiveShortFails >= 2 && now - lastShortFailTime < DIR_PAUSE_MS) {
                return noTrade("SHORT direction paused (2 fails, wait 1h)");
            }
            if (prev != null && !(last.getClose() < last.getOpen()
                                 && last.getClose() < prev.getClose())) {
                return noTrade(String.format(
                        "Near resistance %.0f but no bearish confirmation (close=%.0f open=%.0f prev=%.0f)",
                        rangeHigh, last.getClose(), last.getOpen(), prev.getClose()));
            }

            return openProbe("short", price, now, price + virtStop, rangeHigh, rangeLow, boxWidth);
        }

        return noTrade(String.format("Price %.0f in mid-range [%.0f - %.0f]", price, rangeLow, rangeHigh));
    }

    /** 构造试探仓信号（stopLoss=0、takeProfit=0：交易所不挂单，由策略监控） */
    private TradeSignal openProbe(String dir, double price, long now,
                                   double virtStop, double rangeHigh, double rangeLow, double boxWidth) {
        direction        = dir;
        probeEntryPrice  = price;
        probeEntryTime   = now;
        virtualStopLoss  = virtStop;
        state            = State.PROBE;
        lastTradeTime    = now;

        TradeSignal.Action action = "long".equals(dir) ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
        String reason = String.format(
                "MR-PROBE %s: entry=%.0f virtStop=%.0f box=[%.0f-%.0f] width=%.0f (small position, no exchange SL)",
                dir.toUpperCase(), price, virtStop, rangeLow, rangeHigh, boxWidth);

        // 试探仓：stopLoss=0 让 TradeExecutor 跳过 attachAlgoOrds
        return new TradeSignal(action, reason, PROBE_CONFIDENCE, PROBE_POSITION_SIZE, 0.0, 0.0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROBE → 加仓 / 止损退出 / 超时退出
    // ═══════════════════════════════════════════════════════════════════
    private TradeSignal evaluateAddOrExit(MarketData data) {
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        // 1. 虚拟止损被击穿：reset 到 IDLE（试探仓裸奔在交易所，由 BoxRangeDetector 失效保护 + 永久熔断兜底）
        boolean stopHit = ("long".equals(direction) && price <= virtualStopLoss)
                       || ("short".equals(direction) && price >= virtualStopLoss);
        if (stopHit) {
            String dir = direction;
            reset();
            return noTrade(String.format(
                    "MR-PROBE %s hit virtual stop %.0f at price %.0f - reset (probe position remains on exchange, manual check)",
                    dir.toUpperCase(), virtualStopLoss, price));
        }

        // 2. 试探仓超时：3h 仍未达加仓条件
        if (now - probeEntryTime > PROBE_TIMEOUT_MS) {
            String dir = direction;
            reset();
            return noTrade(String.format(
                    "MR-PROBE %s timeout 3h - reset (probe position remains on exchange, manual check)",
                    dir.toUpperCase()));
        }

        // 3. 加仓条件评估
        if (!boxRangeDetector.isValid()) {
            return noTrade("Box range invalidated during PROBE, holding");
        }

        double rangeHigh = boxRangeDetector.getCurrentRangeHigh();
        double rangeLow  = boxRangeDetector.getCurrentRangeLow();
        double boxWidth  = rangeHigh - rangeLow;
        double rangeMid  = (rangeHigh + rangeLow) / 2.0;

        double pnl = "long".equals(direction)
                ? price - probeEntryPrice
                : probeEntryPrice - price;

        double confirmThreshold = boxWidth * CONFIRM_PROFIT_RATIO;
        boolean pnlOk  = pnl >= confirmThreshold;
        boolean timeOk = (now - probeEntryTime) >= PROBE_HOLD_MIN_MS;

        if (!(pnlOk && timeOk)) {
            return noTrade(String.format(
                    "MR-PROBE %s: pnl=%.0f vs %.0f(25%% width), held=%.0fmin vs 45min - waiting",
                    direction, pnl, confirmThreshold, (now - probeEntryTime) / 60000.0));
        }

        // 4. 触发加仓：整仓 SL/TP 通过 attachAlgoOrds 原子绑定
        double protect = boxWidth * PROTECT_STOP_RATIO;
        double sl, tp;
        if ("long".equals(direction)) {
            // 保本位：入场上方一段（确保不止损在入场下方导致亏损）
            sl = probeEntryPrice + protect;
            // 但不能高于当前价（否则交易所会立即触发）
            sl = Math.min(sl, price - protect);
            sl = Math.max(sl, probeEntryPrice);
            tp = rangeMid;
        } else {
            sl = probeEntryPrice - protect;
            sl = Math.max(sl, price + protect);
            sl = Math.min(sl, probeEntryPrice);
            tp = rangeMid;
        }

        addStopLoss   = sl;
        addTakeProfit = tp;
        confirmedTime = now;
        state         = State.CONFIRMED;
        lastTradeTime = now;

        TradeSignal.Action action = "long".equals(direction) ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
        String reason = String.format(
                "MR-ADD %s: probeEntry=%.0f price=%.0f pnl=+%.0f(%.1f%% width) held=%.0fmin -> SL=%.0f TP=%.0f (whole-position)",
                direction.toUpperCase(), probeEntryPrice, price, pnl,
                pnl / boxWidth * 100, (now - probeEntryTime) / 60000.0, sl, tp);

        return new TradeSignal(action, reason, ADD_CONFIDENCE, ADD_POSITION_SIZE, sl, tp);
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIRMED → 等待交易所 SL/TP 触发或外部 handoff
    // ═══════════════════════════════════════════════════════════════════
    private TradeSignal evaluateConfirmed(MarketData data) {
        double price = data.getCurrentPrice();

        // 加仓已通过 attachAlgoOrds 绑定整仓 SL/TP，交易所自动处理。
        // 这里只做被动监控：如果价格触及 SL/TP，认为交易所已处理，reset 状态。
        boolean slHit = ("long".equals(direction) && price <= addStopLoss)
                     || ("short".equals(direction) && price >= addStopLoss);
        boolean tpHit = ("long".equals(direction) && price >= addTakeProfit)
                     || ("short".equals(direction) && price <= addTakeProfit);

        if (slHit) {
            long ts = data.getLastKline().getTimestamp();
            recordTradeResult(direction, false, ts);
            return noTrade(String.format("MR-CONFIRMED %s stop loss touched at %.0f - position closed by exchange",
                    direction.toUpperCase(), price));
        }
        if (tpHit) {
            long ts = data.getLastKline().getTimestamp();
            recordTradeResult(direction, true, ts);
            return noTrade(String.format("MR-CONFIRMED %s take profit touched at %.0f - position closed by exchange",
                    direction.toUpperCase(), price));
        }

        return noTrade(String.format("MR-CONFIRMED %s holding: price=%.0f SL=%.0f TP=%.0f",
                direction.toUpperCase(), price, addStopLoss, addTakeProfit));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════════

    private KLine getPrevKline(MarketData data) {
        List<KLine> ks = data.getKlines();
        return ks.size() >= 2 ? ks.get(ks.size() - 2) : null;
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
