package com.trading.signal.service;

import com.trading.signal.client.OkxClient;
import com.trading.signal.config.TradingProperties;
import com.trading.signal.model.KLine;
import com.trading.signal.strategy.MeanReversionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 箱体上下沿自动识别服务
 * 
 * 功能：
 * - 每24小时分析最近7天的4h K线（42根）
 * - 使用多次触及法识别支撑和压力位
 * - 箱体宽度限制：可配置的最小/最大宽度
 * - 箱体变更时发送邮件通知
 * - 持仓期间跳过箱体更新（避免策略不一致）
 * 
 * 算法优化（v3.0 - 专业版）：
 * 1. 边界选择：选择最高/最低的cluster（而非触及最多的中枢）
 * 2. 聚类算法：密度覆盖法（而非贪心排序），避免误合并区间
 * 3. ATR计算：EMA ATR（period=14），对波动变化更敏感
 * 4. 动态容忍度：基于平均价格（而非单点currentPrice），更稳定
 * 5. 质量评分：touchCount + clusterSize + recentTouch加权
 * 6. 边界计算：小样本(<5)用极值，大样本用分位数（P90/P10）
 * 7. 输出增强：详细日志输出所有cluster和评分
 */
@Service
public class BoxRangeDetector {
    
    private static final Logger log = LoggerFactory.getLogger(BoxRangeDetector.class);
    
    private final OkxClient okxClient;
    private final EmailService emailService;
    private final TradingProperties properties;
    private final ApplicationContext applicationContext;  // 用于延迟获取 MeanReversionStrategy
    
    // 当前箱体（内存缓存）
    private volatile double currentRangeHigh = 0;
    private volatile double currentRangeLow = 0;
    private volatile boolean isValid = false;
    private volatile long lastUpdateTime = 0;
    
    // 箱体稳定性检查（连续2次识别出相似箱体才确认）
    private double pendingRangeHigh = 0;
    private double pendingRangeLow = 0;
    private int consecutiveDetections = 0;
    
    public BoxRangeDetector(OkxClient okxClient, EmailService emailService, 
                           TradingProperties properties, ApplicationContext applicationContext) {
        this.okxClient = okxClient;
        this.emailService = emailService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }
    
    /**
     * 程序启动时立即执行一次箱体识别（同步执行，阻塞启动）
     * 只有当前策略为 mean-reversion 时才执行
     */
    @jakarta.annotation.PostConstruct
    public void initDetection() {
        // 检查当前策略
        String currentStrategy = properties.getStrategy();
        if (!"mean-reversion".equals(currentStrategy)) {
            log.info("当前策略为 {}，跳过箱体识别", currentStrategy);
            return;
        }
        
        log.info("程序启动，立即执行首次箱体识别（同步）...");
        try {
            detectBoxRange();
            if (isValid) {
                log.info("箱体识别成功: [{} - {}]，程序可以开始运行",
                    String.format("%.0f", currentRangeLow),
                    String.format("%.0f", currentRangeHigh));
            } else {
                log.warn("箱体识别失败，策略将暂停交易，等待下次识别（24小时后）");
            }
        } catch (Exception e) {
            log.error("启动时箱体识别失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 定时任务：每24小时执行一次
     * cron: 0 0 0 * * * （每天0点执行）
     * 
     * 保护逻辑：如果当前存在持仓 → 跳过本次箱体更新
     * 目标：避免箱体在持仓期间发生变化，影响策略一致性
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void detectBoxRange() {
        try {
            // 检查当前策略
            String currentStrategy = properties.getStrategy();
            if (!"mean-reversion".equals(currentStrategy)) {
                log.debug("当前策略为 {}，跳过箱体识别", currentStrategy);
                return;
            }
            
            // 检查是否存在持仓（延迟获取 bean，避免循环依赖）
            try {
                MeanReversionStrategy strategy = applicationContext.getBean(MeanReversionStrategy.class);
                if (strategy.hasPosition()) {
                    log.info("跳过箱体更新（存在持仓）");
                    return;
                }
            } catch (Exception e) {
                log.warn("无法获取 MeanReversionStrategy，跳过持仓检查: {}", e.getMessage());
            }
            
            log.info("执行箱体更新（无持仓）");
            log.info("开始箱体识别...");
            
            // 1. 拉取最近14天的4h K线（84根）
            // 虽然只用最近7天（42根），但多拉一些数据作为备份
            List<KLine> klines4h = okxClient.fetchCandles(
                properties.getOkx().getInstId(), "4H", 84);
            
            if (klines4h.size() < 42) {
                log.warn("K线数量不足: {} < 42，跳过本次识别", klines4h.size());
                return;
            }
            
            // 2. 识别箱体
            BoxRange newRange = detectRange(klines4h);
            
            // 2.5 调试日志：打印聚类结果
            log.debug("箱体识别结果: valid={}, high={}, low={}, span={}%, reason={}",
                newRange.isValid,
                newRange.isValid ? String.format("%.0f", newRange.rangeHigh) : "N/A",
                newRange.isValid ? String.format("%.0f", newRange.rangeLow) : "N/A",
                newRange.isValid ? String.format("%.1f", newRange.span * 100) : "0.0",
                newRange.reason);
            
            // 3. 检查是否有效
            if (!newRange.isValid) {
                log.info("未识别到有效箱体: {}", newRange.reason);
                consecutiveDetections = 0;  // 重置连续检测计数
                if (isValid) {
                    // 之前有效，现在失效 → 发邮件通知
                    sendRangeInvalidEmail(newRange.reason);
                    isValid = false;
                }
                return;
            }
            
            // 4. 箱体稳定性检查（连续2次识别出相似箱体才确认）
            // 特殊情况：首次启动时直接确认，不需要等待第二次
            boolean isFirstDetection = (pendingRangeHigh == 0 && pendingRangeLow == 0);
            
            if (isFirstDetection) {
                // 首次检测，直接确认
                pendingRangeHigh = newRange.rangeHigh;
                pendingRangeLow = newRange.rangeLow;
                consecutiveDetections = 2;  // 直接设为2，跳过稳定性检查
                log.info("首次箱体识别，直接确认: [{} - {}]",
                    String.format("%.0f", newRange.rangeLow),
                    String.format("%.0f", newRange.rangeHigh));
            } else if (isSimilarRange(newRange.rangeHigh, newRange.rangeLow, pendingRangeHigh, pendingRangeLow)) {
                consecutiveDetections++;
                log.debug("箱体稳定性检查: 连续{}次识别出相似箱体", consecutiveDetections);
            } else {
                // 箱体变化，重置计数
                pendingRangeHigh = newRange.rangeHigh;
                pendingRangeLow = newRange.rangeLow;
                consecutiveDetections = 1;
                log.debug("箱体变化，重置稳定性计数");
                return;  // 等待下次确认
            }
            
            // 5. 连续2次确认后才更新
            if (consecutiveDetections < 2) {
                log.info("箱体待确认: [{} - {}]（需连续2次识别）",
                    String.format("%.0f", newRange.rangeLow),
                    String.format("%.0f", newRange.rangeHigh));
                return;
            }
            
            // 6. 检查箱体是否变更
            boolean isFirstTime = currentRangeHigh == 0;
            boolean hasChanged = hasSignificantChange(newRange.rangeHigh, newRange.rangeLow);
            
            if (isFirstTime || hasChanged) {
                double oldHigh = currentRangeHigh;
                double oldLow = currentRangeLow;
                
                // 更新内存缓存
                currentRangeHigh = newRange.rangeHigh;
                currentRangeLow = newRange.rangeLow;
                isValid = true;
                lastUpdateTime = System.currentTimeMillis();
                
                log.info("箱体更新: [{} - {}] → [{} - {}]",
                    String.format("%.0f", oldLow), String.format("%.0f", oldHigh),
                    String.format("%.0f", newRange.rangeLow), String.format("%.0f", newRange.rangeHigh));
                
                // 发送邮件通知
                if (isFirstTime) {
                    sendRangeDetectedEmail(newRange);
                } else {
                    sendRangeChangedEmail(oldHigh, oldLow, newRange);
                }
            } else {
                log.info("箱体未变更: [{} - {}]",
                    String.format("%.0f", currentRangeLow), String.format("%.0f", currentRangeHigh));
            }
            
        } catch (Exception e) {
            log.error("箱体识别失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取当前箱体上沿（供策略调用）
     */
    public double getCurrentRangeHigh() {
        return currentRangeHigh;
    }
    
    /**
     * 获取当前箱体下沿（供策略调用）
     */
    public double getCurrentRangeLow() {
        return currentRangeLow;
    }
    
    /**
     * 当前箱体是否有效
     */
    public boolean isValid() {
        return isValid;
    }
    
    /**
     * 箱体识别核心算法（多次触及法 + 密度聚类 + 质量评分）
     * 
     * 优化v3.0：
     * 1. 边界选择：选择最高/最低的cluster（而非触及最多的中枢）
     * 2. 聚类算法：密度覆盖法（而非贪心排序）
     * 3. ATR计算：EMA ATR（而非简单平均）
     * 4. 动态容忍度：基于平均价格（而非单点currentPrice）
     * 5. 质量评分：touchCount + clusterSize + recentTouch加权
     * 6. 边界计算：小样本用极值，大样本用分位数
     */
    private BoxRange detectRange(List<KLine> klines4h) {
        // 1. 只使用最近7天数据（42根4H K线），避免箱体滞后
        int recentBars = Math.min(42, klines4h.size());
        List<KLine> recentKlines = klines4h.subList(klines4h.size() - recentBars, klines4h.size());
        
        // 2. 计算EMA ATR（用于动态容忍度）
        double atr = calculateATR_EMA(recentKlines, 14);
        
        // 3. 计算平均价格（用于归一化，比单点currentPrice更稳定）
        double avgPrice = recentKlines.stream()
            .mapToDouble(KLine::getClose)
            .average()
            .orElse(recentKlines.get(recentKlines.size() - 1).getClose());
        
        double currentPrice = recentKlines.get(recentKlines.size() - 1).getClose();
        
        // 4. 动态聚类容忍度：基于平均价格而非单点
        // tolerance = clamp(ATR * 1.5 / avgPrice, min=0.01, max=0.03)
        double tolerance = Math.max(0.01, Math.min(0.03, (atr * 1.5) / avgPrice));
        
        log.info("动态容忍度计算: ATR={}, 平均价格={}, tolerance={}%",
            String.format("%.0f", atr),
            String.format("%.0f", avgPrice),
            String.format("%.2f", tolerance * 100));
        
        // 5. 收集所有高点和低点
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        
        for (KLine k : recentKlines) {
            highs.add(k.getHigh());
            lows.add(k.getLow());
        }
        
        // 6. 使用密度聚类算法（覆盖法）找到所有cluster
        List<PriceCluster> highClusters = findClustersByDensity(highs, tolerance, recentKlines);
        List<PriceCluster> lowClusters = findClustersByDensity(lows, tolerance, recentKlines);
        
        // 7. 输出所有cluster（调试用）
        log.info("高点clusters（前3个）:");
        highClusters.stream().limit(3).forEach(c -> 
            log.info("  center={}, touchCount={}, size={}, score={}, range=[{}-{}]",
                String.format("%.0f", c.center), c.touchCount, c.prices.size(), 
                String.format("%.2f", c.score), String.format("%.0f", c.getMin()), String.format("%.0f", c.getMax())));
        
        log.info("低点clusters（前3个）:");
        lowClusters.stream().limit(3).forEach(c -> 
            log.info("  center={}, touchCount={}, size={}, score={}, range=[{}-{}]",
                String.format("%.0f", c.center), c.touchCount, c.prices.size(), 
                String.format("%.2f", c.score), String.format("%.0f", c.getMin()), String.format("%.0f", c.getMax())));
        
        // 8. 【核心优化】选择边界：最高/最低的cluster（而非触及最多的中枢）
        // 过滤：touchCount >= 3 且 score > 5.0
        PriceCluster resistanceCluster = highClusters.stream()
            .filter(c -> c.touchCount >= 3 && c.score > 5.0)
            .max(Comparator.comparingDouble(c -> c.center))
            .orElse(null);
        
        PriceCluster supportCluster = lowClusters.stream()
            .filter(c -> c.touchCount >= 3 && c.score > 5.0)
            .min(Comparator.comparingDouble(c -> c.center))
            .orElse(null);
        
        if (resistanceCluster == null) {
            return BoxRange.invalid("无法识别压力位（高点分散或质量不足）");
        }
        
        if (supportCluster == null) {
            return BoxRange.invalid("无法识别支撑位（低点分散或质量不足）");
        }
        
        // 9. 边界价格计算：小样本用极值，大样本用分位数
        double rangeHigh = resistanceCluster.prices.size() < 5 
            ? resistanceCluster.getMax() 
            : resistanceCluster.getPercentile(0.90);
        
        double rangeLow = supportCluster.prices.size() < 5 
            ? supportCluster.getMin() 
            : supportCluster.getPercentile(0.10);
        
        double boxWidth = rangeHigh - rangeLow;
        double span = boxWidth / rangeLow;
        
        log.info("最终选中边界: 压力={} (center={}, touch={}, score={}), 支撑={} (center={}, touch={}, score={})",
            String.format("%.0f", rangeHigh), String.format("%.0f", resistanceCluster.center), 
            resistanceCluster.touchCount, String.format("%.2f", resistanceCluster.score),
            String.format("%.0f", rangeLow), String.format("%.0f", supportCluster.center), 
            supportCluster.touchCount, String.format("%.2f", supportCluster.score));
        
        log.info("箱体宽度={} ({}%), 当前价格={}",
            String.format("%.0f", boxWidth), String.format("%.1f", span * 100), String.format("%.0f", currentPrice));
        
        // 10. 箱体宽度检查
        double maxWidth = properties.getParams().getBoxMaxWidth();
        double minWidth = properties.getParams().getBoxMinWidth();
        
        if (boxWidth > maxWidth) {
            return BoxRange.invalid(String.format(
                "箱体宽度过大: %.0f > %.0f — 震荡过于剧烈，可能在突破",
                boxWidth, maxWidth));
        }
        
        if (boxWidth < minWidth) {
            return BoxRange.invalid(String.format(
                "箱体宽度过小: %.0f < %.0f — 操作空间不足",
                boxWidth, minWidth));
        }
        
        return BoxRange.valid(rangeHigh, rangeLow, span);
    }
    
    /**
     * 计算EMA ATR（指数移动平均）
     * 
     * 优势：对波动变化更敏感，比简单平均更适合动态市场
     * 
     * @param klines K线列表
     * @param period EMA周期（建议14）
     * @return EMA ATR值
     */
    private double calculateATR_EMA(List<KLine> klines, int period) {
        if (klines.size() < 2) return 0;
        
        List<Double> trueRanges = new ArrayList<>();
        
        // 1. 计算所有TR
        for (int i = 1; i < klines.size(); i++) {
            KLine current = klines.get(i);
            KLine previous = klines.get(i - 1);
            
            // True Range = max(high-low, |high-prevClose|, |low-prevClose|)
            double tr1 = current.getHigh() - current.getLow();
            double tr2 = Math.abs(current.getHigh() - previous.getClose());
            double tr3 = Math.abs(current.getLow() - previous.getClose());
            
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            trueRanges.add(tr);
        }
        
        if (trueRanges.isEmpty()) return 0;
        
        // 2. 计算EMA ATR
        double multiplier = 2.0 / (period + 1);
        
        // 初始ATR = 前period个TR的简单平均
        int initialPeriod = Math.min(period, trueRanges.size());
        double atr = trueRanges.subList(0, initialPeriod).stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
        
        // 后续使用EMA公式：ATR = (TR * multiplier) + (prevATR * (1 - multiplier))
        for (int i = initialPeriod; i < trueRanges.size(); i++) {
            double tr = trueRanges.get(i);
            atr = (tr * multiplier) + (atr * (1 - multiplier));
        }
        
        return atr;
    }
    
    /**
     * 密度聚类算法（覆盖法）
     * 
     * 原理：每个价格点作为中心，统计覆盖范围内的点数
     * 优势：不依赖排序，避免贪心算法误合并区间
     * 
     * @param prices 价格列表
     * @param tolerance 容忍度（例如0.02 = 2%）
     * @param klines K线列表（用于计算recentTouch权重）
     * @return 按质量评分排序的cluster列表
     */
    private List<PriceCluster> findClustersByDensity(List<Double> prices, double tolerance, List<KLine> klines) {
        if (prices.isEmpty()) return new ArrayList<>();
        
        // 1. 对每个价格点，统计覆盖范围内的点数
        List<PriceCluster> allClusters = new ArrayList<>();
        
        for (int i = 0; i < prices.size(); i++) {
            double centerPrice = prices.get(i);
            double lowerBound = centerPrice * (1 - tolerance);
            double upperBound = centerPrice * (1 + tolerance);
            
            PriceCluster cluster = new PriceCluster(centerPrice);
            
            // 统计覆盖范围内的所有点
            for (int j = 0; j < prices.size(); j++) {
                double price = prices.get(j);
                if (price >= lowerBound && price <= upperBound) {
                    if (j != i) {  // 避免重复添加中心点
                        cluster.addPrice(price);
                    }
                    
                    // 记录是否为最近20根K线（用于recentTouch权重）
                    if (j >= prices.size() - 20) {
                        cluster.recentTouchCount++;
                    }
                }
            }
            
            allClusters.add(cluster);
        }
        
        // 2. 去重：合并中心点接近的cluster（保留touchCount更大的）
        List<PriceCluster> uniqueClusters = new ArrayList<>();
        allClusters.sort(Comparator.comparingDouble(c -> -c.touchCount));  // 按touchCount降序
        
        for (PriceCluster cluster : allClusters) {
            boolean isDuplicate = false;
            for (PriceCluster existing : uniqueClusters) {
                double deviation = Math.abs(cluster.center - existing.center) / existing.center;
                if (deviation < tolerance) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                uniqueClusters.add(cluster);
            }
        }
        
        // 3. 计算质量评分
        for (PriceCluster cluster : uniqueClusters) {
            cluster.score = calculateClusterScore(cluster);
        }
        
        // 4. 按评分排序，返回top N
        uniqueClusters.sort(Comparator.comparingDouble(c -> -c.score));
        
        return uniqueClusters;
    }
    
    /**
     * 计算cluster质量评分
     * 
     * score = touchCount * 0.5 + clusterSize * 0.3 + recentTouchWeight * 0.2
     * 
     * @param cluster 价格簇
     * @return 质量评分
     */
    private double calculateClusterScore(PriceCluster cluster) {
        double touchScore = cluster.touchCount * 0.5;
        double sizeScore = cluster.prices.size() * 0.3;
        double recentScore = cluster.recentTouchCount * 1.5 * 0.2;  // 最近触及加权1.5倍
        
        return touchScore + sizeScore + recentScore;
    }
    
    /**
     * 检查两个箱体是否相似（变化小于5%）
     * 7天更新一次，容忍度可以放宽一些
     */
    private boolean isSimilarRange(double high1, double low1, double high2, double low2) {
        if (high2 == 0 || low2 == 0) return false;
        
        double highChange = Math.abs(high1 - high2) / high2;
        double lowChange = Math.abs(low1 - low2) / low2;
        
        return highChange < 0.05 && lowChange < 0.05;  // 变化小于5%
    }
    
    /**
     * 检查箱体是否有显著变化（超过2%）
     */
    private boolean hasSignificantChange(double newHigh, double newLow) {
        if (currentRangeHigh == 0) return true;
        
        double highChange = Math.abs(newHigh - currentRangeHigh) / currentRangeHigh;
        double lowChange = Math.abs(newLow - currentRangeLow) / currentRangeLow;
        
        return highChange > 0.02 || lowChange > 0.02;  // 变化超过2%
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 邮件通知
    // ═══════════════════════════════════════════════════════════════════
    
    private void sendRangeDetectedEmail(BoxRange range) {
        String subject = String.format("[箱体识别] %s 新箱体检测",
            properties.getOkx().getInstId().toUpperCase());
        
        String body = String.format(
            "===== 箱体自动识别 v3.0 =====\n\n" +
            "压力位  : %.0f（触及%d次，质量评分%.1f）\n" +
            "支撑位  : %.0f（触及%d次，质量评分%.1f）\n" +
            "宽度    : %.0f (%.1f%%)\n" +
            "中线    : %.0f\n\n" +
            "【算法优化v3.0】\n" +
            "- 边界选择：最高/最低cluster（非中枢）\n" +
            "- 聚类算法：密度覆盖法（非贪心排序）\n" +
            "- ATR计算：EMA ATR（更敏感）\n" +
            "- 动态容忍度：基于平均价格（更稳定）\n" +
            "- 质量评分：touch+size+recent加权\n\n" +
            "【说明】\n" +
            "使用多次触及法识别真实边界。\n" +
            "触及次数越多，支撑/压力越可靠。\n\n" +
            "【建议】\n" +
            "- 在支撑位附近寻找做多机会\n" +
            "- 在压力位附近寻找做空机会\n" +
            "- 止盈目标：箱体中线\n" +
            "- 下次更新：24小时后\n\n" +
            "========================",
            range.rangeHigh, 0, 0.0,  // TODO: 传入触及次数和评分
            range.rangeLow, 0, 0.0,   // TODO: 传入触及次数和评分
            range.rangeHigh - range.rangeLow, range.span * 100,
            (range.rangeHigh + range.rangeLow) / 2);
        
        emailService.sendRawEmail(subject, body);
    }
    
    private void sendRangeChangedEmail(double oldHigh, double oldLow, BoxRange newRange) {
        String subject = String.format("[箱体变更] %s 箱体已更新",
            properties.getOkx().getInstId().toUpperCase());
        
        double highChange = ((newRange.rangeHigh - oldHigh) / oldHigh) * 100;
        double lowChange = ((newRange.rangeLow - oldLow) / oldLow) * 100;
        
        String body = String.format(
            "===== 箱体变更通知 =====\n\n" +
            "旧箱体:\n" +
            "  上沿: %.0f\n" +
            "  下沿: %.0f\n\n" +
            "新箱体:\n" +
            "  上沿: %.0f (%+.1f%%)\n" +
            "  下沿: %.0f (%+.1f%%)\n" +
            "  宽度: %.0f (%.1f%%)\n\n" +
            "【说明】\n" +
            "市场结构发生变化，箱体上下沿已更新。\n" +
            "策略将使用新的箱体进行交易。\n\n" +
            "【操作】\n" +
            "- 检查当前持仓是否需要调整\n" +
            "- 新的止盈目标：%.0f（新箱体中线）\n\n" +
            "========================",
            oldHigh, oldLow,
            newRange.rangeHigh, highChange,
            newRange.rangeLow, lowChange,
            newRange.rangeHigh - newRange.rangeLow, newRange.span * 100,
            (newRange.rangeHigh + newRange.rangeLow) / 2);
        
        emailService.sendRawEmail(subject, body);
    }
    
    private void sendRangeInvalidEmail(String reason) {
        String subject = String.format("[箱体失效] %s 箱体结构失效",
            properties.getOkx().getInstId().toUpperCase());
        
        String body = String.format(
            "===== 箱体失效通知 =====\n\n" +
            "原因: %s\n\n" +
            "【说明】\n" +
            "当前市场不再满足箱体震荡条件，\n" +
            "可能箱体宽度超过12000美元（震荡过于剧烈）\n" +
            "或宽度小于1000美元（操作空间不足）。\n\n" +
            "【操作】\n" +
            "- 均值回归策略将暂停交易\n" +
            "- 等待新的箱体形成（24小时后重新识别）\n" +
            "- 如有持仓，建议手动检查\n\n" +
            "========================",
            reason);
        
        emailService.sendRawEmail(subject, body);
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // 内部类
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * 价格簇（多次触及的价格水平）
     */
    private static class PriceCluster {
        double center;              // 簇中心（平均价格）
        int touchCount;             // 触及次数
        int recentTouchCount;       // 最近20根K线的触及次数
        double score;               // 质量评分
        List<Double> prices;        // 簇内所有价格
        
        public PriceCluster(double initialPrice) {
            this.center = initialPrice;
            this.touchCount = 1;
            this.recentTouchCount = 0;
            this.score = 0;
            this.prices = new ArrayList<>();
            this.prices.add(initialPrice);
        }
        
        public void addPrice(double price) {
            prices.add(price);
            touchCount++;
            // 更新簇中心为平均值
            center = prices.stream().mapToDouble(Double::doubleValue).average().orElse(center);
        }
        
        /**
         * 获取簇内最大值
         */
        public double getMax() {
            return prices.stream().mapToDouble(Double::doubleValue).max().orElse(center);
        }
        
        /**
         * 获取簇内最小值
         */
        public double getMin() {
            return prices.stream().mapToDouble(Double::doubleValue).min().orElse(center);
        }
        
        /**
         * 获取指定分位数的值
         * 
         * @param percentile 0.0-1.0，例如0.90表示90%分位数（P90）
         * @return 分位数对应的价格
         */
        public double getPercentile(double percentile) {
            if (prices.isEmpty()) return center;
            
            List<Double> sorted = new ArrayList<>(prices);
            Collections.sort(sorted);
            
            int index = (int) Math.ceil(sorted.size() * percentile) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            
            return sorted.get(index);
        }
    }
    
    /**
     * 箱体识别结果
     */
    private static class BoxRange {
        final boolean isValid;
        final double rangeHigh;
        final double rangeLow;
        final double span;
        final String reason;
        
        private BoxRange(boolean isValid, double rangeHigh, double rangeLow, double span, String reason) {
            this.isValid = isValid;
            this.rangeHigh = rangeHigh;
            this.rangeLow = rangeLow;
            this.span = span;
            this.reason = reason;
        }
        
        static BoxRange valid(double rangeHigh, double rangeLow, double span) {
            return new BoxRange(true, rangeHigh, rangeLow, span, null);
        }
        
        static BoxRange invalid(String reason) {
            return new BoxRange(false, 0, 0, 0, reason);
        }
    }
}
