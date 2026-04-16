package com.trading.signal.analyzer;

import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.HtfRange;
import com.trading.signal.model.KLine;
import com.trading.signal.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 市场结构分析器
 * 所有参数从 application.yml → trading.params 读取，不再写死。
 */
@Service
public class MarketAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalyzer.class);

    private final TradingProperties.StrategyParams p;

    @Autowired
    public MarketAnalyzer(TradingProperties props) {
        this.p = props.getParams();
    }

    /** 回测引擎专用：直接传入参数对象，不依赖 Spring 上下文 */
    public MarketAnalyzer(TradingProperties.StrategyParams params) {
        this.p = params;
    }

    public MarketData analyze(List<KLine> klines) {
        int minRequired = Math.max(p.getVolWindow(), p.getEmaLong()) + 3;
        if (klines == null || klines.size() < minRequired) {
            throw new IllegalArgumentException(
                String.format("K线数量不足，至少需要 %d 根，当前: %d",
                    minRequired, klines == null ? 0 : klines.size()));
        }

        int   size  = klines.size();
        KLine last  = klines.get(size - 1);
        KLine prev  = klines.get(size - 2);
        KLine prev2 = klines.get(size - 3);

        // ── 短期区间窗口（横盘判断 + 短期突破）──────────────────────────
        List<KLine> win6 = klines.subList(size - p.getRangeWindow() - 1, size - 1);
        double maxHigh6  = win6.stream().mapToDouble(KLine::getHigh).max().orElseThrow();
        double minLow6   = win6.stream().mapToDouble(KLine::getLow).min().orElseThrow();
        boolean isRange  = (maxHigh6 - minLow6) / minLow6 < p.getRangeThreshold();

        String breakout6;
        if      (last.getClose() > maxHigh6) breakout6 = "up";
        else if (last.getClose() < minLow6)  breakout6 = "down";
        else                                 breakout6 = "none";

        // ── 长期区间窗口（双窗口突破验证）────────────────────────────────
        List<KLine> win14 = klines.subList(size - p.getBreakoutWindowLong() - 1, size - 1);
        double maxHigh14  = win14.stream().mapToDouble(KLine::getHigh).max().orElseThrow();
        double minLow14   = win14.stream().mapToDouble(KLine::getLow).min().orElseThrow();

        String breakout14;
        if      (last.getClose() > maxHigh14) breakout14 = "up";
        else if (last.getClose() < minLow14)  breakout14 = "down";
        else                                  breakout14 = "none";

        // ── 成交量（20根均量基准）────────────────────────────────────────
        List<KLine> volWin  = klines.subList(size - p.getVolWindow() - 1, size - 1);
        double avgVolume20  = volWin.stream().mapToDouble(KLine::getVolume).average().orElse(0.0);
        boolean volumeSpike = last.getVolume() > avgVolume20 * p.getVolumeSpikeMultiplier();

        // ── 延续性 ────────────────────────────────────────────────────────
        boolean continuation = last.getClose() > prev.getClose();
        boolean risingTwo    = last.getClose() > prev.getClose() && prev.getClose() > prev2.getClose();
        boolean fallingTwo   = last.getClose() < prev.getClose() && prev.getClose() < prev2.getClose();
        boolean strongCont   = risingTwo || fallingTwo;

        // ── EMA 趋势方向 ──────────────────────────────────────────────────
        List<KLine> emaWin = klines.subList(size - p.getEmaLong() - 1, size - 1);
        double ema20 = calcEma(emaWin, p.getEmaLong());
        double ema5  = calcEma(emaWin.subList(emaWin.size() - p.getEmaShort(), emaWin.size()), p.getEmaShort());

        String trendBias;
        if      (ema5 > ema20 * (1 + p.getTrendThreshold())) trendBias = "up";
        else if (ema5 < ema20 * (1 - p.getTrendThreshold())) trendBias = "down";
        else                                                  trendBias = "neutral";

        double amplitude = (last.getHigh() - last.getLow()) / last.getClose();

        // ── ATR（14周期平均真实波幅）────────────────────────────────────
        double atr = calcAtr(klines, 14);

        // ── EMA50（中期趋势）──────────────────────────────────────────
        double ema50 = size > 50 ? calcEma(klines.subList(size - 51, size - 1), 50) : ema20;

        // ── 趋势成立检测（4重确认）──────────────────────────────────────
        String trendEstablished = detectEstablishedTrend(klines, ema5, ema20, ema50);

        log.debug("分析 b6={} b14={} range={} trend={} established={} atr={} price={}",
            breakout6, breakout14, isRange, trendBias, trendEstablished,
            String.format("%.2f", atr), String.format("%.2f", last.getClose()));

        return new MarketData(
            isRange, volumeSpike, breakout6, continuation,
            last.getClose(), maxHigh6, minLow6, amplitude, last, avgVolume20,
            trendBias, strongCont, breakout14, maxHigh14, minLow14, prev, prev2,
            ema5, ema20, atr, ema50, trendEstablished, klines
        );
    }

    private double calcEma(List<KLine> klines, int period) {
        if (klines.isEmpty()) return 0.0;
        double k   = 2.0 / (period + 1);
        double ema = klines.get(0).getClose();
        for (int i = 1; i < klines.size(); i++) {
            ema = klines.get(i).getClose() * k + ema * (1 - k);
        }
        return ema;
    }

    /**
     * 计算 ATR（Average True Range）
     * TR = max(high-low, |high-prevClose|, |low-prevClose|)
     * ATR = SMA of TR over period
     */
    private double calcAtr(List<KLine> klines, int period) {
        int size = klines.size();
        if (size < period + 1) return 0.0;
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            KLine cur = klines.get(i);
            double prevClose = klines.get(i - 1).getClose();
            double tr = Math.max(cur.getHigh() - cur.getLow(),
                        Math.max(Math.abs(cur.getHigh() - prevClose),
                                 Math.abs(cur.getLow() - prevClose)));
            sum += tr;
        }
        return sum / period;
    }

    /**
     * 趋势成立检测（4重确认，全部满足才返回方向）
     *
     * 1. 结构确认：最近10根K线中至少有3组 HH+HL（做多）或 LH+LL（做空）
     * 2. 时间确认：EMA排列持续至少10根K线
     * 3. EMA slope：EMA20 斜率持续为正/负（最近5根EMA20都在上升/下降）
     * 4. 波动确认：最近5根ATR的均值 > 前10根ATR的均值（波动在扩张）
     */
    /**
     * 检测成熟趋势（Established Trend）
     * 
     * 目标：识别已经形成且正在延续的趋势，过滤掉假突破和趋势衰减阶段
     * 
     * 四重验证机制：
     *   1. EMA 排列：多头 EMA5 > EMA20 > EMA50，空头相反
     *   2. 价格结构：最近10根K线中至少3组 HH+HL（多头）或 LH+LL（空头）
     *   3. EMA20 斜率：最近5根K线的 EMA20 至少4次同向移动
     *   4. 波动确认：近期波动 ≥ 前期波动×0.8（趋势在加速或维持，不是衰减）
     * 
     * @param klines K线列表
     * @param ema5   当前 EMA5 值
     * @param ema20  当前 EMA20 值
     * @param ema50  当前 EMA50 值
     * @return "up"（多头趋势）/ "down"（空头趋势）/ "none"（无成熟趋势）
     */
    private String detectEstablishedTrend(List<KLine> klines, double ema5, double ema20, double ema50) {
        int size = klines.size();
        if (size < 20) return "none";

        // ── 1. EMA 排列检查 ────────────────────────────────────────────
        // 多头排列：短期 > 中期 > 长期（价格在加速上涨）
        // 空头排列：短期 < 中期 < 长期（价格在加速下跌）
        boolean bullAlign = ema5 > ema20 && ema20 > ema50;
        boolean bearAlign = ema5 < ema20 && ema20 < ema50;
        if (!bullAlign && !bearAlign) return "none";

        // ── 2. 价格结构确认：HH+HL（Higher High + Higher Low）────────────
        // 
        // 什么是 HH+HL？
        //   - HH（Higher High）：当前K线最高价 > 前一根K线最高价
        //   - HL（Higher Low）：当前K线最低价 > 前一根K线最低价
        //   - 同时满足 HH+HL：说明价格在"抬高底部的同时突破前高"（典型上涨结构）
        // 
        // 什么是 LH+LL？
        //   - LH（Lower High）：当前K线最高价 < 前一根K线最高价
        //   - LL（Lower Low）：当前K线最低价 < 前一根K线最低价
        //   - 同时满足 LH+LL：说明价格在"跌破前低的同时无法突破前高"（典型下跌结构）
        // 
        // 为什么要至少3组？
        //   - 1-2组可能是噪音或假突破
        //   - 3组以上说明趋势结构已经形成，不是偶然波动
        // 
        // 示例（多头 HH+HL）：
        //   K1: high=67000, low=66500
        //   K2: high=67200, low=66700  ✓ HH+HL（高点和低点都抬高）
        //   K3: high=67100, low=66600  ✗ 不满足（高点没突破）
        //   K4: high=67400, low=66900  ✓ HH+HL
        //   K5: high=67600, low=67100  ✓ HH+HL
        //   → 10根K线中有3组 HH+HL，确认多头结构
        int hhHlCount = 0, lhLlCount = 0;
        for (int i = size - 9; i < size - 1; i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);
            // 多头结构：高点和低点都在抬高
            if (cur.getHigh() > prev.getHigh() && cur.getLow() > prev.getLow()) hhHlCount++;
            // 空头结构：高点和低点都在降低
            if (cur.getHigh() < prev.getHigh() && cur.getLow() < prev.getLow()) lhLlCount++;
        }
        if (bullAlign && hhHlCount < 3) return "none";  // 多头排列但结构不足，可能是假突破
        if (bearAlign && lhLlCount < 3) return "none";  // 空头排列但结构不足

        // ── 3. EMA20 斜率一致性检查 ────────────────────────────────────
        // 目标：确认趋势方向稳定，不是来回震荡
        // 方法：计算最近5根K线的 EMA20 值，检查至少4次同向移动
        // 
        // 为什么用 EMA20 而不是 EMA5？
        //   - EMA5 太敏感，容易被短期波动干扰
        //   - EMA20 更平滑，能反映中期趋势方向
        // 
        // 示例（多头）：
        //   K1: EMA20=66800
        //   K2: EMA20=66850  ✓ 上升
        //   K3: EMA20=66900  ✓ 上升
        //   K4: EMA20=66880  ✗ 下降
        //   K5: EMA20=66950  ✓ 上升
        //   K6: EMA20=67000  ✓ 上升
        //   → 5次移动中4次向上，斜率一致性通过
        double prevEma = calcEma(klines.subList(size - p.getEmaLong() - 6, size - 5), p.getEmaLong());
        int slopeUpCount = 0, slopeDownCount = 0;
        for (int i = 1; i <= 5; i++) {
            double curEma = calcEma(klines.subList(size - p.getEmaLong() - 6 + i, size - 5 + i), p.getEmaLong());
            if (curEma > prevEma) slopeUpCount++;
            else if (curEma < prevEma) slopeDownCount++;
            prevEma = curEma;
        }
        if (bullAlign && slopeUpCount < 4) return "none";   // 多头但斜率不稳定，趋势不成熟
        if (bearAlign && slopeDownCount < 4) return "none"; // 空头但斜率不稳定

        // ── 4. 波动确认：趋势在加速或维持，不是衰减 ──────────────────
        // 目标：过滤掉趋势末期（波动收缩，即将反转）
        // 方法：对比最近5根K线的平均波动 vs 前10根K线的平均波动
        // 
        // 为什么波动重要？
        //   - 健康趋势：波动维持或放大（资金持续涌入）
        //   - 衰减趋势：波动收缩（资金撤离，即将反转）
        // 
        // 示例：
        //   前10根平均波动：300美元
        //   最近5根平均波动：250美元
        //   250 / 300 = 0.83 > 0.8  ✓ 波动维持，趋势健康
        // 
        //   最近5根平均波动：200美元
        //   200 / 300 = 0.67 < 0.8  ✗ 波动收缩，趋势可能衰减
        if (size < 16) return "none";
        double recentAtrSum = 0, olderAtrSum = 0;
        // 计算最近5根的平均真实波动
        for (int i = size - 5; i < size; i++) {
            KLine cur = klines.get(i);
            double prevClose = klines.get(i - 1).getClose();
            recentAtrSum += Math.max(cur.getHigh() - cur.getLow(),
                Math.max(Math.abs(cur.getHigh() - prevClose), Math.abs(cur.getLow() - prevClose)));
        }
        // 计算前10根的平均真实波动
        for (int i = size - 15; i < size - 5; i++) {
            KLine cur = klines.get(i);
            double prevClose = klines.get(i - 1).getClose();
            olderAtrSum += Math.max(cur.getHigh() - cur.getLow(),
                Math.max(Math.abs(cur.getHigh() - prevClose), Math.abs(cur.getLow() - prevClose)));
        }
        double recentAvgAtr = recentAtrSum / 5;
        double olderAvgAtr  = olderAtrSum / 10;
        if (recentAvgAtr < olderAvgAtr * 0.8) return "none"; // 波动收缩超过20%，趋势可能衰减

        return bullAlign ? "up" : "down";
    }

    /**
     * 分析高时间框架（4小时）箱体
     * 用于均值回归策略的大箱体判断
     *
     * @param klines4h 4小时K线列表（至少需要 rangeWindow+emaLong 根）
     */
    public HtfRange analyzeHtf(List<KLine> klines4h) {
        int minRequired = Math.max(p.getRangeWindow(), p.getEmaLong()) + 1;
        if (klines4h == null || klines4h.size() < minRequired) {
            // 数据不足，返回非区间
            return new HtfRange(false, 0, 0, "neutral");
        }

        int size = klines4h.size();

        // 箱体：用 rangeWindow 根4h K线的高低点
        List<KLine> win = klines4h.subList(size - p.getRangeWindow() - 1, size - 1);
        double maxHigh = win.stream().mapToDouble(KLine::getHigh).max().orElse(0);
        double minLow  = win.stream().mapToDouble(KLine::getLow).min().orElse(0);

        // 4h级别用更宽松的横盘阈值（4h波动天然更大，6根=24小时）
        // BTC日内波动2-3%是常态，需要放宽到8%左右
        boolean isRange = minLow > 0 && (maxHigh - minLow) / minLow < p.getRangeThreshold() * 12;

        // EMA趋势
        List<KLine> emaWin = klines4h.subList(size - p.getEmaLong() - 1, size - 1);
        double ema20 = calcEma(emaWin, p.getEmaLong());
        double ema5  = calcEma(emaWin.subList(emaWin.size() - p.getEmaShort(), emaWin.size()), p.getEmaShort());

        String trendBias;
        if      (ema5 > ema20 * (1 + p.getTrendThreshold())) trendBias = "up";
        else if (ema5 < ema20 * (1 - p.getTrendThreshold())) trendBias = "down";
        else                                                  trendBias = "neutral";

        return new HtfRange(isRange, maxHigh, minLow, trendBias);
    }
}
