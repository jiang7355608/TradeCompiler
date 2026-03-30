package com.trading.signal.analyzer;

import com.trading.signal.config.TradingProperties;
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

        log.debug("分析 b6={} b14={} range={} volSpike={} cont={} strongCont={} trend={} price={}",
            breakout6, breakout14, isRange, volumeSpike, continuation, strongCont, trendBias,
            String.format("%.2f", last.getClose()));

        return new MarketData(
            isRange, volumeSpike, breakout6, continuation,
            last.getClose(), maxHigh6, minLow6, amplitude, last, avgVolume20,
            trendBias, strongCont, breakout14, maxHigh14, minLow14, prev
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
}
