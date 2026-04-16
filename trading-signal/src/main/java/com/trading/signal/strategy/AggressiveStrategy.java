package com.trading.signal.strategy;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AggressiveStrategy v7 — Simple Breakout (追突破)
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【核心理念】抓住趋势启动，承受假突破
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 不等回踩 → 突破确认 → 立即入场
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【1】区间定义
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 使用最近 N=40 根K线（不包含当前K线）
 * rangeHigh = 最高价
 * rangeLow = 最低价
 * 
 * 过滤条件：
 * - 区间宽度 > 3%（过滤无波动市场）
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【2】突破入场
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 做多条件：
 * - 当前K线收盘价 > rangeHigh
 * - (close - rangeHigh) > 0.3×ATR（动量确认）
 * → 立即做多
 * 
 * 做空条件：
 * - 当前K线收盘价 < rangeLow
 * - (rangeLow - close) > 0.3×ATR（动量确认）
 * → 立即做空
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【3】止损
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 做多：SL = rangeHigh - 1×ATR
 * 做空：SL = rangeLow + 1×ATR
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【4】止盈
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 固定盈亏比：RR = 2:1
 * TP = entryPrice + 2 × (entryPrice - stopLoss)
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【5】仓位管理
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 单笔风险：2%账户资金
 * 仓位 = riskAmount / (止损距离 / 价格)
 * 
 * 约束：
 * - 最大仓位：30%
 * - 无最小仓位限制
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【6】防止过度交易
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * - 同一方向连续失败2次 → 暂停该方向1小时
 * - 全局冷却：15分钟
 */
@Component
public class AggressiveStrategy implements Strategy {

    private final TradingProperties.StrategyParams p;

    // 风控
    private long lastTradeTime;
    private long lastLongFailTime;
    private long lastShortFailTime;
    private int consecutiveLongFails;
    private int consecutiveShortFails;
    
    // 账户余额（实盘从交易所获取，回测使用固定值）
    private double accountBalance = 200.0;

    @Autowired
    public AggressiveStrategy(TradingProperties props) {
        this.p = props.getParams();
    }

    public AggressiveStrategy(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    @Override public String getName() { return "aggressive"; }
    
    /**
     * 设置账户余额（实盘调用，从交易所获取）
     */
    public void setAccountBalance(double balance) {
        this.accountBalance = balance;
    }
    
    /**
     * 获取当前账户余额
     */
    public double getAccountBalance() {
        return this.accountBalance;
    }

    @Override
    public TradeSignal generateSignal(MarketData data) {
        double atr   = data.getAtr();
        double price = data.getCurrentPrice();
        long   now   = data.getLastKline().getTimestamp();

        if (atr <= 0) return noTrade("ATR=0");

        // ── 全局冷却期：15分钟 ──────────────────────────────────────
        if (now - lastTradeTime < 15 * 60 * 1000L) {
            return noTrade("Global cooldown (15min)");
        }

        // ── 1. 定义区间（最近40根K线，排除当前K线）─────────────────
        List<KLine> klines = data.getKlines();
        int lookback = Math.min(40, klines.size() - 1);
        if (lookback < 20) {
            return noTrade("Not enough data (need 40 bars)");
        }
        
        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        for (int i = klines.size() - lookback - 1; i < klines.size() - 1; i++) {
            KLine k = klines.get(i);
            rangeHigh = Math.max(rangeHigh, k.getHigh());
            rangeLow = Math.min(rangeLow, k.getLow());
        }
        
        // ── 2. 过滤：区间宽度 > 3% ────────────────────────────────────
        double rangeWidth = (rangeHigh - rangeLow) / rangeLow;
        if (rangeWidth < 0.03) {
            return noTrade(String.format("Range too narrow: %.2f%% < 3%% (range: %.0f-%.0f)", 
                rangeWidth * 100, rangeLow, rangeHigh));
        }

        KLine last = data.getLastKline();
        
        // 计算K线实体大小
        double bodySize = Math.abs(last.getClose() - last.getOpen());
        
        // ── 3. 做多突破检测 ────────────────────────────────────────────
        boolean longBreakout = last.getClose() > rangeHigh 
                            && (last.getClose() - rangeHigh) > atr * 0.3
                            && bodySize > atr * 0.8;  // 新增：实体过滤
        
        if (longBreakout) {
            // 检查做多方向是否被暂停
            if (consecutiveLongFails >= 2 && now - lastLongFailTime < 60 * 60 * 1000L) {
                return noTrade(String.format("LONG direction paused (2 consecutive fails, wait 1h)"));
            }
            
            // 修复1：入场价格必须使用 last.getClose()，与信号判断一致
            double entryPrice = last.getClose();
            double stopLoss = rangeHigh - atr * 1.0;
            
            // 修复3：止损距离保护
            double slDist = entryPrice - stopLoss;
            if (slDist <= 0) {
                return noTrade(String.format("Invalid SL distance (%.2f <= 0)", slDist));
            }
            
            // v8 修改：删除固定止盈，使用 trailing stop
            // double takeProfit = entryPrice + slDist * 2.0;
            double takeProfit = 0;  // 不使用固定止盈
            
            // 修复2：使用实例变量 accountBalance（实盘从交易所获取，回测使用固定值）
            double riskPerTrade = 0.03;  // 单笔风险 3%（从 2% 提升）
            double riskAmount = accountBalance * riskPerTrade;
            int leverage = 20;
            
            double priceRiskRatio = slDist / entryPrice;
            if (priceRiskRatio < 0.002) {
                return noTrade(String.format("Stop loss too tight (%.3f%% < 0.2%%)", priceRiskRatio * 100));
            }
            
            double positionNotional = riskAmount / priceRiskRatio;
            double margin = positionNotional / leverage;
            double maxMargin = accountBalance * 0.30;
            
            if (margin > maxMargin) {
                margin = maxMargin;
                positionNotional = margin * leverage;
            }
            
            double position = margin / accountBalance;
            double actualRisk = positionNotional * priceRiskRatio;
            
            // 修复5：风险校验（允许10%误差）
            if (actualRisk > riskAmount * 1.1) {
                return noTrade(String.format("Risk exceeds limit (%.2fU > %.2fU)", actualRisk, riskAmount * 1.1));
            }
            
            lastTradeTime = now;
            
            // 修复6：完整的调试输出
            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("BREAKOUT LONG (Trailing Stop): entry=%.2f SL=%.2f (no fixed TP, using trailing) | " +
                              "Range: %.0f-%.0f (%.1f%%) ATR=%.0f momentum=%.0f | " +
                              "Risk Model: balance=%.0fU riskAmount=%.2fU(%.1f%%) slDist=%.2f(%.2f%%) | " +
                              "Position: notional=%.0fU margin=%.2fU(%.1f%%) actualRisk=%.2fU | " +
                              "Validation: actualRisk/riskAmount=%.2f%%",
                    entryPrice, stopLoss,
                    rangeLow, rangeHigh, rangeWidth * 100, atr, last.getClose() - rangeHigh,
                    accountBalance, riskAmount, riskPerTrade * 100, slDist, priceRiskRatio * 100,
                    positionNotional, margin, position * 100, actualRisk,
                    actualRisk / riskAmount * 100),
                0.75, position, stopLoss, takeProfit);
        }
        
        // ── 4. 做空突破检测 ────────────────────────────────────────────
        boolean shortBreakout = last.getClose() < rangeLow
                             && (rangeLow - last.getClose()) > atr * 0.3
                             && bodySize > atr * 0.8;  // 新增：实体过滤
        
        if (shortBreakout) {
            // 检查做空方向是否被暂停
            if (consecutiveShortFails >= 2 && now - lastShortFailTime < 60 * 60 * 1000L) {
                return noTrade(String.format("SHORT direction paused (2 consecutive fails, wait 1h)"));
            }
            
            // 修复1：入场价格必须使用 last.getClose()，与信号判断一致
            double entryPrice = last.getClose();
            double stopLoss = rangeLow + atr * 1.0;
            
            // 修复3：止损距离保护
            double slDist = stopLoss - entryPrice;
            if (slDist <= 0) {
                return noTrade(String.format("Invalid SL distance (%.2f <= 0)", slDist));
            }
            
            // v8 修改：删除固定止盈，使用 trailing stop
            // double takeProfit = entryPrice - slDist * 2.0;
            double takeProfit = 0;  // 不使用固定止盈
            
            // 修复2：使用实例变量 accountBalance（实盘从交易所获取，回测使用固定值）
            double riskPerTrade = 0.03;  // 单笔风险 3%（从 2% 提升）
            double riskAmount = accountBalance * riskPerTrade;
            int leverage = 20;
            
            double priceRiskRatio = slDist / entryPrice;
            if (priceRiskRatio < 0.002) {
                return noTrade(String.format("Stop loss too tight (%.3f%% < 0.2%%)", priceRiskRatio * 100));
            }
            
            double positionNotional = riskAmount / priceRiskRatio;
            double margin = positionNotional / leverage;
            double maxMargin = accountBalance * 0.30;
            
            if (margin > maxMargin) {
                margin = maxMargin;
                positionNotional = margin * leverage;
            }
            
            double position = margin / accountBalance;
            double actualRisk = positionNotional * priceRiskRatio;
            
            // 修复5：风险校验（允许10%误差）
            if (actualRisk > riskAmount * 1.1) {
                return noTrade(String.format("Risk exceeds limit (%.2fU > %.2fU)", actualRisk, riskAmount * 1.1));
            }
            
            lastTradeTime = now;
            
            // 修复6：完整的调试输出
            return new TradeSignal(TradeSignal.Action.SHORT,
                String.format("BREAKOUT SHORT (Trailing Stop): entry=%.2f SL=%.2f (no fixed TP, using trailing) | " +
                              "Range: %.0f-%.0f (%.1f%%) ATR=%.0f momentum=%.0f | " +
                              "Risk Model: balance=%.0fU riskAmount=%.2fU(%.1f%%) slDist=%.2f(%.2f%%) | " +
                              "Position: notional=%.0fU margin=%.2fU(%.1f%%) actualRisk=%.2fU | " +
                              "Validation: actualRisk/riskAmount=%.2f%%",
                    entryPrice, stopLoss,
                    rangeLow, rangeHigh, rangeWidth * 100, atr, rangeLow - last.getClose(),
                    accountBalance, riskAmount, riskPerTrade * 100, slDist, priceRiskRatio * 100,
                    positionNotional, margin, position * 100, actualRisk,
                    actualRisk / riskAmount * 100),
                0.75, position, stopLoss, takeProfit);
        }
        
        return noTrade(String.format("No breakout (price=%.0f, range: %.0f-%.0f, ATR=%.0f)", 
            price, rangeLow, rangeHigh, atr));
    }

    /**
     * 记录交易结果（用于方向暂停逻辑）
     * 回测引擎应该在平仓后调用此方法
     * 
     * @param direction "long" or "short"
     * @param isWin 是否盈利
     * @param timestamp 交易时间（K线时间，不是系统时间）
     */
    public void recordTradeResult(String direction, boolean isWin, long timestamp) {
        if ("long".equals(direction)) {
            if (isWin) {
                consecutiveLongFails = 0;
            } else {
                consecutiveLongFails++;
                lastLongFailTime = timestamp;  // 修复4：使用K线时间
            }
        } else if ("short".equals(direction)) {
            if (isWin) {
                consecutiveShortFails = 0;
            } else {
                consecutiveShortFails++;
                lastShortFailTime = timestamp;  // 修复4：使用K线时间
            }
        }
    }

    public void reset() {
        lastTradeTime = 0;
        lastLongFailTime = 0;
        lastShortFailTime = 0;
        consecutiveLongFails = 0;
        consecutiveShortFails = 0;
    }

    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
