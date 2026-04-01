package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AggressiveStrategy v5 — Pullback Continuation（回调续势）
 *
 * ═══ Edge ═══
 * BTC趋势确立后，回调到均线附近是低风险入场点。
 * 不预测方向，不追突破。等趋势成立 → 等回调 → 回调结束续势时入场。
 *
 * ═══ 与之前版本的根本区别 ═══
 * v1-v4: 试图在趋势启动时入场（breakout），大量假突破导致低胜率
 * v5:    等趋势已经走了一段，回调到支撑位再入场，胜率和盈亏比同时提升
 *
 * ═══ 入场条件（全部满足）═══
 * 1. EMA Alignment: EMA5 > EMA20 > EMA50（做多）或反向（做空）
 * 2. 价格回调到EMA20附近（距离 < 0.5×ATR）
 * 3. 回调结束信号：当前K线收盘重新朝趋势方向（反弹/回落确认）
 * 4. 结构完整：最近K线维持 Higher High/Higher Low（做多）或反向
 *
 * ═══ 出场 ═══
 * SL: 2×ATR（固定，入场时确定）
 * TP: 无固定止盈，回测引擎用 Trailing Stop（最高/低价 - 3×ATR）
 *     实盘由人管理
 *
 * ═══ 风控 ═══
 * 仓位 = 本金2%风险 / (2×ATR×杠杆)
 * 每天最多2-3笔（冷却期控制）
 * 无PROBE机制——条件满足直接入场，不满足不交易
 */
@Component
public class AggressiveStrategy implements Strategy {

    public enum State { IDLE, CONFIRMED }

    private final TradingProperties.StrategyParams p;

    private State  state = State.IDLE;
    private String direction;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private double entryAtr;
    private long   lastTradeTime;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int    dailyTradeCount;
    private long   currentDay;

    @Autowired
    public AggressiveStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    public AggressiveStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override public String getName() { return "aggressive"; }
    public State getState() { return state; }
    public double getStopLoss() { return stopLoss; }
    public double getTakeProfit() { return takeProfit; }

    @Override
    public TradeSignal generateSignal(MarketData data) {
        return switch (state) {
            case IDLE      -> evaluateEntry(data);
            case CONFIRMED -> evaluateExit(data);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDLE → 等待完美 setup：趋势确立 + 回调到均线 + 续势确认
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateEntry(MarketData data) {
        double atr   = data.getAtr();
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        if (atr <= 0) return noTrade("IDLE: ATR=0");

        // 每日交易次数限制
        long day = now / 86400000L;
        if (day != currentDay) { currentDay = day; dailyTradeCount = 0; }
        if (dailyTradeCount >= 3) return noTrade("IDLE: daily limit reached");

        // 冷却
        if (now - lastTradeTime < p.getAggCooldownMs()) {
            return noTrade("IDLE: cooldown");
        }

        // ═══ 核心门控：趋势必须已成立（4重确认全部通过）═══
        String established = data.getTrendEstablished();
        if ("none".equals(established)) {
            return noTrade("IDLE: trend not established");
        }

        double ema20 = data.getEma20();
        KLine last   = data.getLastKline();
        KLine prev   = data.getPrevKline();
        KLine prev2  = data.getPrev2Kline();

        // ── 回调到EMA20附近（距离 < 0.8×ATR）────────────────────────
        double distToEma20 = Math.abs(price - ema20);
        if (distToEma20 > atr * 0.8) {
            return noTrade(String.format("IDLE: price too far from EMA20 (dist=%.0f > 0.8×ATR=%.0f)",
                distToEma20, atr * 0.8));
        }

        // ── 回调结束确认：前一根逆势 + 当前顺势 ──────────────────────
        boolean pullbackBounce;
        if ("up".equals(established)) {
            pullbackBounce = prev.getClose() < prev2.getClose()
                          && last.getClose() > prev.getClose();
        } else {
            pullbackBounce = prev.getClose() > prev2.getClose()
                          && last.getClose() < prev.getClose();
        }

        if (!pullbackBounce) {
            return noTrade(String.format("IDLE: no pullback bounce (trend=%s)", established));
        }

        // ═══ 全部条件满足，入场 ═══
        direction = established;
        entryPrice = price;
        entryAtr   = atr;
        highestSinceEntry = price;
        lowestSinceEntry  = price;
        lastTradeTime = now;
        dailyTradeCount++;

        double slDist = atr * p.getAggSlAtrMult();
        stopLoss = "up".equals(direction) ? price - slDist : price + slDist;
        double tpDist = atr * p.getAggTpAtrMult();
        takeProfit = "up".equals(direction) ? price + tpDist : price - tpDist;

        // 仓位：单笔风险 ≤ 本金2%
        double riskPerUnit = slDist / price * 20;
        double position = Math.min(0.02 / riskPerUnit, 0.30);
        position = Math.max(position, 0.05);

        state = State.CONFIRMED;

        TradeSignal.Action action = "up".equals(direction)
            ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
        return new TradeSignal(action,
            String.format("ENTRY %s: trend-established + pullback-bounce ATR=%.0f pos=%.0f%% SL=%.2f TP=%.2f",
                direction.toUpperCase(), atr, position * 100, stopLoss, takeProfit),
            0.75, position, stopLoss, takeProfit);
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIRMED → 实盘reset（人管止盈止损），回测由引擎管
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateExit(MarketData data) {
        reset();
        return noTrade("CONFIRMED handed off — reset");
    }

    public void reset() {
        state = State.IDLE;
        direction = null;
        entryPrice = 0;
        stopLoss = 0;
        takeProfit = 0;
        entryAtr = 0;
        highestSinceEntry = 0;
        lowestSinceEntry = Double.MAX_VALUE;
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
