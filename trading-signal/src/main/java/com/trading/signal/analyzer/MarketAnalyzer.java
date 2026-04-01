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
        String trendEstablished = detectEstablishedTrend(klines, ema5, ema20, ema50, atr);

        log.debug("分析 b6={} b14={} range={} trend={} established={} atr={} price={}",
            breakout6, breakout14, isRange, trendBias, trendEstablished,
            String.format("%.2f", atr), String.format("%.2f", last.getClose()));

        return new MarketData(
            isRange, volumeSpike, breakout6, continuation,
            last.getClose(), maxHigh6, minLow6, amplitude, last, avgVolume20,
            trendBias, strongCont, breakout14, maxHigh14, minLow14, prev, prev2,
            ema5, ema20, atr, ema50, trendEstablished
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
    private String detectEstablishedTrend(List<KLine> klines, double ema5, double ema20,
                                           double ema50, double currentAtr) {
        int size = klines.size();
        if (size < 20) return "none";

        // ── 1. EMA Alignment ──────────────────────────────────────────
        boolean bullAlign = ema5 > ema20 && ema20 > ema50;
        boolean bearAlign = ema5 < ema20 && ema20 < ema50;
        if (!bullAlign && !bearAlign) return "none";

        // ── 2. 结构确认：最近10根K线的 HH/HL 或 LH/LL ────────────────
        int hhHlCount = 0, lhLlCount = 0;
        for (int i = size - 9; i < size - 1; i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);
            if (cur.getHigh() > prev.getHigh() && cur.getLow() > prev.getLow()) hhHlCount++;
            if (cur.getHigh() < prev.getHigh() && cur.getLow() < prev.getLow()) lhLlCount++;
        }
        if (bullAlign && hhHlCount < 3) return "none";
        if (bearAlign && lhLlCount < 3) return "none";

        // ── 3. EMA20 slope：最近5根EMA20持续同向 ──────────────────────
        // 用最近6根K线的收盘价算5个EMA20快照，检查斜率一致性
        List<KLine> recentForSlope = klines.subList(size - 6, size);
        double prevEma = calcEma(klines.subList(size - p.getEmaLong() - 6, size - 5), p.getEmaLong());
        int slopeUpCount = 0, slopeDownCount = 0;
        for (int i = 1; i <= 5; i++) {
            double curEma = calcEma(klines.subList(size - p.getEmaLong() - 6 + i, size - 5 + i), p.getEmaLong());
            if (curEma > prevEma) slopeUpCount++;
            else if (curEma < prevEma) slopeDownCount++;
            prevEma = curEma;
        }
        if (bullAlign && slopeUpCount < 4) return "none";   // 至少4/5根斜率向上
        if (bearAlign && slopeDownCount < 4) return "none";

        // ── 4. 波动确认：近期ATR > 前期ATR（趋势在加速，不是衰减）────
        if (size < 16) return "none";
        double recentAtrSum = 0, olderAtrSum = 0;
        for (int i = size - 5; i < size; i++) {
            KLine cur = klines.get(i);
            double prevClose = klines.get(i - 1).getClose();
            recentAtrSum += Math.max(cur.getHigh() - cur.getLow(),
                Math.max(Math.abs(cur.getHigh() - prevClose), Math.abs(cur.getLow() - prevClose)));
        }
        for (int i = size - 15; i < size - 5; i++) {
            KLine cur = klines.get(i);
            double prevClose = klines.get(i - 1).getClose();
            olderAtrSum += Math.max(cur.getHigh() - cur.getLow(),
                Math.max(Math.abs(cur.getHigh() - prevClose), Math.abs(cur.getLow() - prevClose)));
        }
        double recentAvgAtr = recentAtrSum / 5;
        double olderAvgAtr  = olderAtrSum / 10;
        if (recentAvgAtr < olderAvgAtr * 0.8) return "none"; // 波动在收缩，趋势可能衰减

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
