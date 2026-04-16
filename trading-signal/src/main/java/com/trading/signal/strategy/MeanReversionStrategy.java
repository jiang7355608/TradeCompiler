package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.BoxRangeDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * MeanReversionStrategy — 人工大箱体 + 试探加仓
 *
 * 修复清单：
 *   #1 线程安全：所有状态操作加 synchronized
 *   #2 冷却期：改为全局冷却（不分方向）
 *   #3 日志：确认阈值动态显示
 *   #4 加仓止损：动态计算（箱体宽度×5%）
 *   #5 CONFIRMED状态：不立即reset，保留持仓信息供API查询
 *   #6 冷却方向：用实际开仓方向，不用K线方向
 */
@Component
public class MeanReversionStrategy implements Strategy {

    public enum State { IDLE, PROBE, CONFIRMED }

    private final TradingProperties.StrategyParams p;
    private final BoxRangeDetector boxRangeDetector;  // 自动箱体识别服务

    // ── 状态（全部通过 synchronized 保护）──────────────────────────────
    private State  state = State.IDLE;
    private String direction;
    private double probeEntryPrice;
    private long   probeEntryTime;
    private double stopLoss;
    private double takeProfit;
    private long   confirmedTime = 0;  // 进入 CONFIRMED 状态的时间戳（用于超时兜底）
    private int    consecutiveLosses = 0;
    private long   circuitBreakerUntil = 0;
    private long   lastTradeTime = 0;  // 全局冷却（不分方向）

    @Autowired
    public MeanReversionStrategy(TradingProperties props, BoxRangeDetector boxRangeDetector) {
        this.p = props.getParams();
        this.boxRangeDetector = boxRangeDetector;
    }

    public MeanReversionStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
        this.boxRangeDetector = null;  // 回测时不使用自动识别
    }

    @Override public String getName() { return "mean-reversion"; }

    // ── 线程安全的状态访问 ─────────────────────────────────────────────
    public synchronized State  getState()      { return state; }
    public synchronized double getStopLoss()   { return stopLoss; }
    public synchronized double getTakeProfit()  { return takeProfit; }
    public synchronized String getDirection()   { return direction; }
    public synchronized boolean hasPosition()   { return state != State.IDLE; }
    public TradingProperties.StrategyParams getParams() { return p; }
    
    /**
     * 更新止盈价格（箱体变化时调用）
     * 注意：只在持仓状态下更新，避免影响未开仓的逻辑
     */
    public synchronized void updateTakeProfitIfNeeded(double newTakeProfit) {
        if (state != State.IDLE && newTakeProfit > 0) {
            this.takeProfit = newTakeProfit;
        }
    }

    @Override
    public synchronized TradeSignal generateSignal(MarketData data) {
        // 不应该直接调用此方法，均值回归策略必须传入 HtfRange
        return noTrade("Missing HtfRange — mean reversion requires dynamic box analysis");
    }

    @Override
    public synchronized TradeSignal generateSignal(MarketData data, com.trading.signal.model.HtfRange htfRange) {
        // 箱体优先级：
        // 1. 手动配置箱体（application.yml）
        // 2. 传入的 htfRange 参数（回测时使用）
        // 3. 自动识别箱体（BoxRangeDetector，实盘时使用）
        
        double rangeHigh, rangeLow;
        String source;
        
        // 优先级1：手动配置箱体
        if (p.getMrRangeHigh() > 0 && p.getMrRangeLow() > 0) {
            rangeHigh = p.getMrRangeHigh();
            rangeLow = p.getMrRangeLow();
            source = "manual-config";
        }
        // 优先级2：传入的 htfRange 参数（回测时使用）
        else if (htfRange != null && htfRange.isRange()) {
            rangeHigh = htfRange.getRangeHigh();
            rangeLow = htfRange.getRangeLow();
            source = "htfRange-param";
        }
        // 优先级3：自动识别箱体（实盘时使用）
        else if (boxRangeDetector != null && boxRangeDetector.isValid()) {
            rangeHigh = boxRangeDetector.getCurrentRangeHigh();
            rangeLow = boxRangeDetector.getCurrentRangeLow();
            source = "auto-detected";
        }
        // 无有效箱体
        else {
            return noTrade("No valid box range available (manual=0, htfRange=invalid, auto=invalid)");
        }
        
        double rangeSpan = rangeHigh - rangeLow;
        double rangeMid = (rangeHigh + rangeLow) / 2.0;

        // 箱体宽度检查：太窄的箱体不适合做均值回归（容易被噪音触发）
        // 最小宽度：当前价格的1%（BTC 67000时至少670美元）
        double minSpan = data.getCurrentPrice() * 0.01;
        if (rangeSpan < minSpan) {
            return noTrade(String.format("HTF range too narrow: %.0f < %.0f (1%% of price) — risk of noise",
                rangeSpan, minSpan));
        }

        // 🔥 关键修复：箱体变化时，同步更新止盈价格
        // 如果当前有持仓，且箱体中线变化了，更新止盈
        if (state != State.IDLE && Math.abs(takeProfit - rangeMid) > 1.0) {
            takeProfit = rangeMid;
        }

        return switch (state) {
            case IDLE      -> evaluateEntry(data, rangeHigh, rangeLow, rangeSpan, rangeMid);
            case PROBE     -> evaluateConfirmation(data, rangeHigh, rangeLow, rangeSpan, rangeMid);
            case CONFIRMED -> evaluateExit(data);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDLE → 寻找上下沿入场机会
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateEntry(MarketData data, double rangeHigh, double rangeLow, double rangeSpan, double rangeMid) {
        long currentTs = data.getLastKline().getTimestamp();

        // 熔断
        if (currentTs < circuitBreakerUntil) return noTrade("Circuit breaker");

        // 全局冷却（不分方向，防止两头开仓）
        if (currentTs - lastTradeTime < p.getMrCooldownMs()) return noTrade("Cooldown");

        double price     = data.getCurrentPrice();
        double atr       = data.getAtr();
        double buffer    = rangeSpan * p.getMrEntryBufferPct();  // 缓冲区 = 箱体宽度 × 百分比

        if (atr <= 0 || rangeSpan <= 0) return noTrade("Invalid ATR or range");

        // NEW: breakout confirmation — 双K线确认真突破才禁止开仓（暂时注释，排查盈利下降原因）
        // 单根K线收盘超出箱体（弱突破）：可能是假突破，不阻止开仓
        // 连续两根K线收盘均超出（强突破）：箱体失效，禁止开仓
        // 阈值统一用 0.3ATR，与 evaluateConfirmation 保持一致
        KLine last = data.getLastKline();
        KLine prev = data.getPrevKline();
        KLine prev2 = data.getPrev2Kline();  // 用于置信度计算（双K加速确认）
        /*
        double breakoutThreshold = atr * 0.3;
        boolean strongBreakoutUp = last.getClose() > rangeHigh + breakoutThreshold
                                && prev.getClose() > rangeHigh + breakoutThreshold;
        boolean strongBreakoutDn = last.getClose() < rangeLow  - breakoutThreshold
                                && prev.getClose() < rangeLow  - breakoutThreshold;

        if (strongBreakoutUp || strongBreakoutDn) {
            return noTrade(String.format("Range invalidated (strong breakout 2 bars): price=%.0f [%.0f, %.0f]",
                price, rangeLow, rangeHigh));
        }
        */

        // 止损距离：基于入场价的动态模型
        // - rangeSpan * 0.08：与箱体规模挂钩，箱体越大止损空间越大
        // - 1.5 * ATR：保证止损在正常波动范围之外，不被噪音打掉
        // - 取两者较大值：在低波动窄箱体时用 ATR 兜底，高波动宽箱体时用比例控制
        double slDist = Math.max(rangeSpan * 0.08, atr * 1.5);

        // 下沿做多
        boolean inLowerZone = price <= rangeLow + buffer;
        boolean isGreenCandle = last.getClose() > last.getOpen();
        boolean isRising = last.getClose() > prev.getClose();
        
        if (inLowerZone && isGreenCandle && isRising) {
            direction       = "up";
            probeEntryPrice = price;
            probeEntryTime  = currentTs;
            stopLoss        = price - slDist;
            // 止盈目标：箱体中线
            takeProfit      = rangeMid;
            state           = State.PROBE;
            lastTradeTime   = currentTs;

            // 动态置信度计算
            double confidence = calculateProbeConfidence(price, rangeLow, buffer, last, prev, prev2, true);

            // 试探仓不带止盈止损（策略监控保护，1.5小时超时自动平仓）
            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("MR-PROBE LONG: price=%.0f near lower %.0f conf=%.2f",
                    price, rangeLow, confidence),
                confidence, 0.10, 0, 0);  // stopLoss=0, takeProfit=0 表示不带止盈止损
        }
        
        // DEBUG: 打印为什么不入场（每100根K线打印一次）
        if (inLowerZone && currentTs % 600000 == 0) {  // 每10分钟打印一次
            System.out.printf("  [DEBUG-LONG] price=%.0f, rangeLow=%.0f, buffer=%.0f, 阳线=%b, 上涨=%b%n",
                price, rangeLow, buffer, isGreenCandle, isRising);
        }

        // 上沿做空
        boolean inUpperZone = price >= rangeHigh - buffer;
        boolean isRedCandle = last.getClose() < last.getOpen();
        boolean isFalling = last.getClose() < prev.getClose();
        
        if (inUpperZone && isRedCandle && isFalling) {
            direction       = "down";
            probeEntryPrice = price;
            probeEntryTime  = currentTs;
            stopLoss        = price + slDist;
            // 止盈目标：箱体中线
            takeProfit      = rangeMid;
            state           = State.PROBE;
            lastTradeTime   = currentTs;

            // 动态置信度计算
            double confidence = calculateProbeConfidence(price, rangeHigh, buffer, last, prev, prev2, false);

            // 试探仓不带止盈止损（策略监控保护，1.5小时超时自动平仓）
            return new TradeSignal(TradeSignal.Action.SHORT,
                String.format("MR-PROBE SHORT: price=%.0f near upper %.0f conf=%.2f",
                    price, rangeHigh, confidence),
                confidence, 0.10, 0, 0);  // stopLoss=0, takeProfit=0 表示不带止盈止损
        }
        
        // DEBUG: 打印为什么不入场（每100根K线打印一次）
        if (inUpperZone && currentTs % 600000 == 0) {  // 每10分钟打印一次
            System.out.printf("  [DEBUG-SHORT] price=%.0f, rangeHigh=%.0f, buffer=%.0f, 阴线=%b, 下跌=%b%n",
                price, rangeHigh, buffer, isRedCandle, isFalling);
        }

        return noTrade(String.format("Price %.0f in mid-range (%.0f - %.0f)", price, rangeLow, rangeHigh));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROBE → 确认加仓 or 止损/超时
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateConfirmation(MarketData data, double rangeHigh, double rangeLow, double rangeSpan, double rangeMid) {
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();
        double atr   = data.getAtr();

        // NEW: breakout confirmation — 双K线确认真突破才 reset 平仓（暂时注释，排查盈利下降原因）
        // 弱突破（单根K线）：仅记录日志，不影响加仓逻辑
        // 强突破（连续两根K线）：箱体失效，reset 并触发平仓
        KLine last = data.getLastKline();
        KLine prev = data.getPrevKline();
        /*
        double breakoutThreshold = atr * 0.3;
        boolean weakBreakoutUp   = last.getClose() > rangeHigh + breakoutThreshold;
        boolean strongBreakoutUp = weakBreakoutUp && prev.getClose() > rangeHigh + breakoutThreshold;
        boolean weakBreakoutDn   = last.getClose() < rangeLow  - breakoutThreshold;
        boolean strongBreakoutDn = weakBreakoutDn && prev.getClose() < rangeLow  - breakoutThreshold;

        if (strongBreakoutUp || strongBreakoutDn) {
            reset();
            return noTrade(String.format(
                "MR-PROBE range invalidated (strong breakout): price=%.0f [%.0f, %.0f] confirmed 2 bars — closing",
                price, rangeLow, rangeHigh));
        }
        */
        boolean weakBreakout = false; // 注释期间固定为 false，不影响日志输出

        double pnl = "up".equals(direction)
            ? price - probeEntryPrice : probeEntryPrice - price;

        // 动态确认阈值：箱体宽度×15%
        double confirmThreshold = rangeSpan * 0.15;

        // 浮盈超过阈值 + 持仓至少45分钟
        boolean pnlOk  = pnl > confirmThreshold;
        boolean timeOk = (now - probeEntryTime) >= 2700000L;

        if (pnlOk && timeOk) {
            state         = State.CONFIRMED;
            confirmedTime = now;  // 记录确认时间，供超时兜底使用

            // FIX: 双因子保本止损模型
            // 原逻辑 rangeSpan * 0.05 在 BTC 15m 高波动场景下经常小于单根 K 线振幅，
            // 导致加仓后被正常回撤直接打掉（假洗）。
            // 新逻辑取 rangeSpan*0.05 和 ATR*1.0 的较大值：
            //   - rangeSpan*0.05 保证止损与箱体规模挂钩（最低保护距离）
            //   - ATR*1.0 保证止损 ≥ 当前市场真实波动，不被噪音触发
            double protectDist = Math.max(rangeSpan * 0.05, atr * 1.0);

            double newSl = "up".equals(direction)
                ? probeEntryPrice + protectDist   // 多头：止损在入场价上方（保本）
                : probeEntryPrice - protectDist;  // 空头：止损在入场价下方（保本）

            // FIX: 防止止损倒退（只允许止损向有利方向移动）
            // 多头：新止损必须 >= 旧止损，否则保留旧止损（不扩大风险）
            // 空头：新止损必须 <= 旧止损，否则保留旧止损（不扩大风险）
            if ("up".equals(direction)) {
                stopLoss = Math.max(newSl, stopLoss);
            } else {
                stopLoss = Math.min(newSl, stopLoss);
            }

            // 🔥 关键修复：加仓时使用当前箱体的中线作为止盈
            double oldTakeProfit = takeProfit;
            takeProfit = rangeMid;
            
            // DEBUG: 打印止盈更新
            System.out.printf("  [DEBUG] 止盈更新: %.0f → %.0f (箱体中线=%.0f)%n", 
                oldTakeProfit, takeProfit, rangeMid);

            TradeSignal.Action action = "up".equals(direction)
                ? TradeSignal.Action.LONG : TradeSignal.Action.SHORT;
            
            // 动态置信度计算
            double confidence = calculateAddConfidence(pnl, confirmThreshold, now - probeEntryTime);
            
            // 加仓带止盈止损（attachAlgoOrds 原子绑定整仓）
            return new TradeSignal(action,
                String.format("MR-ADD %s: pnl=+%.0f > %.0f(15%%span) held>45min conf=%.2f — whole position SL=%.0f TP=%.0f",
                    direction.toUpperCase(), pnl, confirmThreshold, confidence, stopLoss, takeProfit),
                confidence, 0.30, stopLoss, takeProfit);
        }

        // 超时（3小时）→ 触发平仓
        if (now - probeEntryTime > p.getMrProbeTimeoutMs()) {
            reset();
            return noTrade(String.format("MR-PROBE timeout %.1fh — closing",
                p.getMrProbeTimeoutMs() / 3600000.0));
        }

        // 止损
        if (("up".equals(direction) && price <= stopLoss)
                || ("down".equals(direction) && price >= stopLoss)) {
            reset();
            return noTrade("MR-PROBE hit stop loss — closing");
        }

        return noTrade(String.format("MR-PROBE %s: pnl=%.0f waiting for +%.0f(15%%span)%s",
            direction, pnl, confirmThreshold, weakBreakout ? " [weak breakout — add blocked]" : ""));
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIRMED → 等待 confirmHandedOff() 被调用后 reset
    //
    // 正常路径：加仓下单成功 → SignalService 调用 confirmHandedOff() → IDLE
    //
    // 兜底路径（fail-safe）：
    //   如果 API 失败、服务异常、confirmHandedOff 未被调用，
    //   超过 mrConfirmedTimeoutMs（默认6小时）后自动触发平仓信号。
    //   这是最后一道防线，防止加仓仓位在无止损状态下长期裸奔。
    // ══════════════════════════════════════════════════════════════════════
    private TradeSignal evaluateExit(MarketData data) {
        long now = data.getLastKline().getTimestamp();

        // ── fail-safe：CONFIRMED 超时兜底 ────────────────────────────────
        // 正常情况下 confirmHandedOff() 会在下单成功后立即调用，不会走到这里。
        // 只有 API 失败或系统异常时才会超时触发，此时强制 reset 并发出平仓信号。
        if (confirmedTime > 0 && (now - confirmedTime) > p.getMrConfirmedTimeoutMs()) {
            reset();
            return noTrade(String.format(
                "MR-CONFIRMED timeout %.0fh — force closing to prevent unprotected position",
                p.getMrConfirmedTimeoutMs() / 3600000.0));
        }

        // 正常等待：加仓下单尚未确认，保持 CONFIRMED 状态
        return noTrade(String.format("CONFIRMED %s: SL=%.0f TP=%.0f — awaiting add order confirmation",
            direction, stopLoss, takeProfit));
    }

    /** API下单完成后调用，确认持仓已交接 */
    public synchronized void confirmHandedOff() {
        if (state == State.CONFIRMED) {
            reset();
        }
    }

    public synchronized void reset() {
        state           = State.IDLE;
        direction       = null;
        probeEntryPrice = 0;
        probeEntryTime  = 0;
        confirmedTime   = 0;
        stopLoss        = 0;
        takeProfit      = 0;
    }

    public synchronized void recordTradeResult(boolean isWin, long currentTs) {
        if (isWin) {
            consecutiveLosses = 0;
        } else {
            consecutiveLosses++;
            if (consecutiveLosses >= 3) {
                circuitBreakerUntil = currentTs + 14400000L;
                consecutiveLosses = 0;
            }
        }
    }

    /**
     * 计算试探仓置信度（基础0.50，最高0.80）
     * 
     * 因子：
     * 1. 距离边沿：越接近边沿置信度越高（+0.15）
     * 2. K线实体：实体越大说明方向越明确（+0.10）
     * 3. 双K加速：连续两根K线同向且加速（+0.05）
     */
    private double calculateProbeConfidence(double price, double edge, double buffer,
                                           KLine last, KLine prev, KLine prev2, boolean isLong) {
        double confidence = 0.50;  // 基础置信度
        
        // 因子1：距离边沿（越接近边沿越好）
        // 在 buffer 范围内，越接近边沿 +0.15
        double distToEdge = isLong ? (price - edge) : (edge - price);
        if (distToEdge <= buffer * 0.3) {
            confidence += 0.15;  // 非常接近边沿（30%以内）
        } else if (distToEdge <= buffer * 0.6) {
            confidence += 0.10;  // 比较接近（60%以内）
        } else {
            confidence += 0.05;  // 在buffer内但不够接近
        }
        
        // 因子2：K线实体大小（实体越大方向越明确）
        // 实体占K线总长度的比例
        double body = Math.abs(last.getClose() - last.getOpen());
        double range = last.getHigh() - last.getLow();
        if (range > 0) {
            double bodyRatio = body / range;
            if (bodyRatio > 0.60) {
                confidence += 0.10;  // 实体大（>60%）
            } else if (bodyRatio > 0.40) {
                confidence += 0.05;  // 实体中等（>40%）
            }
        }
        
        // 因子3：双K加速确认（连续两根同向且加速）
        // 做多：last > prev > prev2 且涨幅递增
        // 做空：last < prev < prev2 且跌幅递增
        if (isLong) {
            boolean rising = last.getClose() > prev.getClose() && prev.getClose() > prev2.getClose();
            double gain1 = prev.getClose() - prev2.getClose();
            double gain2 = last.getClose() - prev.getClose();
            if (rising && gain2 > gain1 * 1.1) {  // 加速上涨（涨幅增加10%以上）
                confidence += 0.05;
            }
        } else {
            boolean falling = last.getClose() < prev.getClose() && prev.getClose() < prev2.getClose();
            double loss1 = prev2.getClose() - prev.getClose();
            double loss2 = prev.getClose() - last.getClose();
            if (falling && loss2 > loss1 * 1.1) {  // 加速下跌
                confidence += 0.05;
            }
        }
        
        return Math.min(confidence, 0.80);  // 上限0.80
    }
    
    /**
     * 计算加仓置信度（基础0.70，最高0.95）
     * 
     * 因子：
     * 1. 浮盈超额：超过阈值越多置信度越高（+0.15）
     * 2. 持仓时间：持仓越久说明趋势越稳定（+0.10）
     */
    private double calculateAddConfidence(double pnl, double threshold, long holdTimeMs) {
        double confidence = 0.70;  // 基础置信度
        
        // 因子1：浮盈超额比例
        double excessRatio = (pnl - threshold) / threshold;
        if (excessRatio > 0.50) {
            confidence += 0.15;  // 浮盈超过阈值50%以上
        } else if (excessRatio > 0.25) {
            confidence += 0.10;  // 超过25%以上
        } else {
            confidence += 0.05;  // 刚达到阈值
        }
        
        // 因子2：持仓时间（越久越稳定）
        long holdHours = holdTimeMs / 3600000L;
        if (holdHours >= 2) {
            confidence += 0.10;  // 持仓2小时以上
        } else if (holdHours >= 1) {
            confidence += 0.05;  // 持仓1-2小时
        }
        
        return Math.min(confidence, 0.95);  // 上限0.95
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
