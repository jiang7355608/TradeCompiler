package com.trading.signal.strategy;

import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.BoxRangeDetector;
import org.springframework.stereotype.Component;

/**
 * MeanReversionStrategy — 均值回归策略（箱体震荡交易）
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【核心理念】在箱体震荡中低买高卖，赚取价格回归中线的利润
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 策略逻辑：
 * 1. 使用 BoxRangeDetector 识别箱体上下沿（支撑位和压力位）
 * 2. 价格接近支撑位时做多，目标是箱体中线
 * 3. 价格接近压力位时做空，目标是箱体中线
 * 4. 箱体突破时止损退出
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【1】箱体识别
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 依赖 BoxRangeDetector 自动识别：
 * - rangeHigh：箱体上沿（压力位）
 * - rangeLow：箱体下沿（支撑位）
 * - 箱体宽度：1000-12000 美元
 * - 更新频率：每24小时
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【2】入场条件
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 做多条件：
 * - 箱体有效
 * - 当前价格 < rangeLow + 0.15×箱体宽度（接近支撑位）
 * - 价格在支撑位上方（未跌破）
 * → 做多，目标箱体中线
 * 
 * 做空条件：
 * - 箱体有效
 * - 当前价格 > rangeHigh - 0.15×箱体宽度（接近压力位）
 * - 价格在压力位下方（未突破）
 * → 做空，目标箱体中线
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【3】止损
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 做多：SL = rangeLow - 1×ATR（箱体下沿下方）
 * 做空：SL = rangeHigh + 1×ATR（箱体上沿上方）
 * 
 * 止损逻辑：箱体突破即止损，避免趋势启动时的大幅亏损
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【4】止盈
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * 目标：箱体中线
 * - 做多：TP = (rangeHigh + rangeLow) / 2
 * - 做空：TP = (rangeHigh + rangeLow) / 2
 * 
 * 盈亏比：通常在 1.5:1 到 3:1 之间（取决于入场位置）
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
 * - 最小仓位：5%（过小的仓位不值得交易）
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【6】风控
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * - 同一时间只允许一个持仓
 * - 同一方向连续失败2次 → 暂停该方向1小时
 * - 全局冷却：15分钟（避免频繁交易）
 * - 箱体失效时暂停交易
 * 
 * ═══════════════════════════════════════════════════════════════════════
 * 【7】适用场景
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * ✓ 适合：震荡市、横盘整理、箱体结构明显
 * ✗ 不适合：趋势市、单边行情、箱体宽度过大
 * 
 * @author Trading System
 * @version 1.0
 */
@Component
public class MeanReversionStrategy implements Strategy {

    private final BoxRangeDetector boxRangeDetector;
    
    // 风控状态
    private boolean inPosition = false;
    private long lastTradeTime;
    private long lastLongFailTime;
    private long lastShortFailTime;
    private int consecutiveLongFails;
    private int consecutiveShortFails;
    
    // 账户余额（实盘从交易所获取，回测使用固定值）
    private double accountBalance = 200.0;

    /**
     * 构造函数（Spring 注入）
     * 
     * @param boxRangeDetector 箱体识别器
     */
    public MeanReversionStrategy(BoxRangeDetector boxRangeDetector) {
        this.boxRangeDetector = boxRangeDetector;
    }

    @Override
    public String getName() {
        return "mean-reversion";
    }

    /**
     * 设置账户余额（实盘调用，从交易所获取）
     * 
     * @param balance 账户余额（USDT）
     */
    public void setAccountBalance(double balance) {
        this.accountBalance = balance;
    }

    /**
     * 获取当前账户余额
     * 
     * @return 账户余额（USDT）
     */
    public double getAccountBalance() {
        return this.accountBalance;
    }

    /**
     * 检查是否存在持仓（供 BoxRangeDetector 调用）
     * 
     * @return true 如果当前有持仓
     */
    public boolean hasPosition() {
        return inPosition;
    }

    /**
     * 生成交易信号
     * 
     * @param data 市场数据，包含K线列表和技术指标
     * @return 交易信号，包含完整的交易参数
     */
    @Override
    public TradeSignal generateSignal(MarketData data) {
        double atr = data.getAtr();
        double price = data.getCurrentPrice();
        long now = data.getLastKline().getTimestamp();

        if (atr <= 0) {
            return noTrade("ATR=0");
        }

        // ── 1. 检查箱体是否有效 ──────────────────────────────────────
        if (!boxRangeDetector.isValid()) {
            return noTrade("Box range not valid");
        }

        double rangeHigh = boxRangeDetector.getCurrentRangeHigh();
        double rangeLow = boxRangeDetector.getCurrentRangeLow();
        double boxWidth = rangeHigh - rangeLow;
        double midLine = (rangeHigh + rangeLow) / 2;

        // ── 2. 全局冷却期：15分钟 ──────────────────────────────────────
        if (now - lastTradeTime < 15 * 60 * 1000L) {
            return noTrade("Global cooldown (15min)");
        }

        // ── 3. 检查是否已有持仓 ────────────────────────────────────────
        if (inPosition) {
            return noTrade("Already in position");
        }

        // ── 4. 做多信号检测（接近支撑位）──────────────────────────────
        double longEntryZone = rangeLow + boxWidth * 0.15;  // 支撑位上方15%区域
        
        if (price <= longEntryZone && price > rangeLow) {
            // 检查做多方向是否被暂停
            if (consecutiveLongFails >= 2 && now - lastLongFailTime < 60 * 60 * 1000L) {
                return noTrade("LONG direction paused (2 consecutive fails, wait 1h)");
            }

            double entryPrice = price;
            double stopLoss = rangeLow - atr * 1.0;
            double takeProfit = midLine;

            // 止损距离保护
            double slDist = entryPrice - stopLoss;
            if (slDist <= 0) {
                return noTrade(String.format("Invalid SL distance (%.2f <= 0)", slDist));
            }

            // 仓位计算
            double riskPerTrade = 0.02;  // 单笔风险 2%
            double riskAmount = accountBalance * riskPerTrade;
            int leverage = 20;

            double priceRiskRatio = slDist / entryPrice;
            if (priceRiskRatio < 0.002) {
                return noTrade(String.format("Stop loss too tight (%.3f%% < 0.2%%)", priceRiskRatio * 100));
            }

            double positionNotional = riskAmount / priceRiskRatio;
            double margin = positionNotional / leverage;
            double maxMargin = accountBalance * 0.30;
            double minMargin = accountBalance * 0.05;

            if (margin > maxMargin) {
                margin = maxMargin;
                positionNotional = margin * leverage;
            }

            if (margin < minMargin) {
                return noTrade(String.format("Position too small (%.2f%% < 5%%)", margin / accountBalance * 100));
            }

            double position = margin / accountBalance;
            double actualRisk = positionNotional * priceRiskRatio;

            // 风险校验（允许10%误差）
            if (actualRisk > riskAmount * 1.1) {
                return noTrade(String.format("Risk exceeds limit (%.2fU > %.2fU)", actualRisk, riskAmount * 1.1));
            }

            lastTradeTime = now;
            inPosition = true;

            return new TradeSignal(TradeSignal.Action.LONG,
                String.format("MEAN REVERSION LONG: entry=%.2f SL=%.2f TP=%.2f (midline) | " +
                              "Box: %.0f-%.0f (width=%.0f) price=%.0f (%.1f%% from support) | " +
                              "Risk: %.2fU(%.1f%%) slDist=%.2f(%.2f%%) position=%.1f%% actualRisk=%.2fU",
                    entryPrice, stopLoss, takeProfit,
                    rangeLow, rangeHigh, boxWidth, price, (price - rangeLow) / boxWidth * 100,
                    riskAmount, riskPerTrade * 100, slDist, priceRiskRatio * 100, position * 100, actualRisk),
                0.70, position, stopLoss, takeProfit);
        }

        // ── 5. 做空信号检测（接近压力位）──────────────────────────────
        double shortEntryZone = rangeHigh - boxWidth * 0.15;  // 压力位下方15%区域

        if (price >= shortEntryZone && price < rangeHigh) {
            // 检查做空方向是否被暂停
            if (consecutiveShortFails >= 2 && now - lastShortFailTime < 60 * 60 * 1000L) {
                return noTrade("SHORT direction paused (2 consecutive fails, wait 1h)");
            }

            double entryPrice = price;
            double stopLoss = rangeHigh + atr * 1.0;
            double takeProfit = midLine;

            // 止损距离保护
            double slDist = stopLoss - entryPrice;
            if (slDist <= 0) {
                return noTrade(String.format("Invalid SL distance (%.2f <= 0)", slDist));
            }

            // 仓位计算
            double riskPerTrade = 0.02;  // 单笔风险 2%
            double riskAmount = accountBalance * riskPerTrade;
            int leverage = 20;

            double priceRiskRatio = slDist / entryPrice;
            if (priceRiskRatio < 0.002) {
                return noTrade(String.format("Stop loss too tight (%.3f%% < 0.2%%)", priceRiskRatio * 100));
            }

            double positionNotional = riskAmount / priceRiskRatio;
            double margin = positionNotional / leverage;
            double maxMargin = accountBalance * 0.30;
            double minMargin = accountBalance * 0.05;

            if (margin > maxMargin) {
                margin = maxMargin;
                positionNotional = margin * leverage;
            }

            if (margin < minMargin) {
                return noTrade(String.format("Position too small (%.2f%% < 5%%)", margin / accountBalance * 100));
            }

            double position = margin / accountBalance;
            double actualRisk = positionNotional * priceRiskRatio;

            // 风险校验（允许10%误差）
            if (actualRisk > riskAmount * 1.1) {
                return noTrade(String.format("Risk exceeds limit (%.2fU > %.2fU)", actualRisk, riskAmount * 1.1));
            }

            lastTradeTime = now;
            inPosition = true;

            return new TradeSignal(TradeSignal.Action.SHORT,
                String.format("MEAN REVERSION SHORT: entry=%.2f SL=%.2f TP=%.2f (midline) | " +
                              "Box: %.0f-%.0f (width=%.0f) price=%.0f (%.1f%% from resistance) | " +
                              "Risk: %.2fU(%.1f%%) slDist=%.2f(%.2f%%) position=%.1f%% actualRisk=%.2fU",
                    entryPrice, stopLoss, takeProfit,
                    rangeLow, rangeHigh, boxWidth, price, (rangeHigh - price) / boxWidth * 100,
                    riskAmount, riskPerTrade * 100, slDist, priceRiskRatio * 100, position * 100, actualRisk),
                0.70, position, stopLoss, takeProfit);
        }

        return noTrade(String.format("Price not in entry zone (price=%.0f, box: %.0f-%.0f, longZone<=%.0f, shortZone>=%.0f)",
            price, rangeLow, rangeHigh, longEntryZone, shortEntryZone));
    }

    /**
     * 记录交易结果（用于方向暂停逻辑）
     * 
     * @param direction "long" or "short"
     * @param isWin 是否盈利
     * @param timestamp 交易时间（K线时间，不是系统时间）
     */
    public void recordTradeResult(String direction, boolean isWin, long timestamp) {
        inPosition = false;  // 平仓后重置持仓状态

        if ("long".equals(direction)) {
            if (isWin) {
                consecutiveLongFails = 0;
            } else {
                consecutiveLongFails++;
                lastLongFailTime = timestamp;
            }
        } else if ("short".equals(direction)) {
            if (isWin) {
                consecutiveShortFails = 0;
            } else {
                consecutiveShortFails++;
                lastShortFailTime = timestamp;
            }
        }
    }

    /**
     * 重置策略状态（回测时使用）
     */
    public void reset() {
        inPosition = false;
        lastTradeTime = 0;
        lastLongFailTime = 0;
        lastShortFailTime = 0;
        consecutiveLongFails = 0;
        consecutiveShortFails = 0;
    }

    /**
     * 创建不交易信号
     * 
     * @param reason 不交易的原因
     * @return 不交易信号
     */
    private TradeSignal noTrade(String reason) {
        return new TradeSignal(TradeSignal.Action.NO_TRADE, reason);
    }
}
