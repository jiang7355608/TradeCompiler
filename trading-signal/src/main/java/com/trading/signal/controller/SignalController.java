package com.trading.signal.controller;

import com.trading.signal.backtest.BacktestScheduler;
import com.trading.signal.backtest.ParameterOptimizer;
import com.trading.signal.model.TradeSignal;
import com.trading.signal.service.SignalService;
import com.trading.signal.strategy.StrategyRouter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * REST 接口
 *
 * GET  /api/signal/run       手动触发一次分析，返回最新信号
 * GET  /api/strategy         查询当前策略及所有可用策略
 * POST /api/strategy/{name}  热切换策略（无需重启）
 * POST /api/backtest/run     手动触发月度回测（不等每月1号）
 */
@RestController
@RequestMapping("/api")
public class SignalController {

    private final SignalService      signalService;
    private final StrategyRouter     strategyRouter;
    private final BacktestScheduler  backtestScheduler;
    private final ParameterOptimizer parameterOptimizer;

    public SignalController(SignalService signalService, StrategyRouter strategyRouter,
                            BacktestScheduler backtestScheduler,
                            ParameterOptimizer parameterOptimizer) {
        this.signalService      = signalService;
        this.strategyRouter     = strategyRouter;
        this.backtestScheduler  = backtestScheduler;
        this.parameterOptimizer = parameterOptimizer;
    }

    @GetMapping("/signal/run")
    public ResponseEntity<?> runOnce() {
        try {
            TradeSignal signal = signalService.runOnce();
            return ResponseEntity.ok(Map.of(
                "action",     signal.getAction().getValue(),
                "confidence", signal.getConfidence(),
                "reason",     signal.getReason()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/strategy")
    public ResponseEntity<?> getStrategy() {
        return ResponseEntity.ok(Map.of(
            "current",   strategyRouter.current().getName(),
            "available", strategyRouter.availableStrategies()
        ));
    }

    @PostMapping("/strategy/{name}")
    public ResponseEntity<?> switchStrategy(@PathVariable String name) {
        try {
            strategyRouter.switchStrategy(name);
            return ResponseEntity.ok(Map.of("message", "策略已切换为: " + name, "current", name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 手动触发月度回测，异步执行，立即返回 */
    @PostMapping("/backtest/run")
    public ResponseEntity<?> triggerBacktest() {
        new Thread(() -> backtestScheduler.runBacktest()).start();
        return ResponseEntity.ok(Map.of(
            "message", "回测任务已启动，完成后将发送邮件报告"
        ));
    }

    /**
     * 参数优化：网格搜索最优参数组合
     * GET /api/optimize?top=5
     * 返回两个策略各自的 Top N 参数组合及评分
     */
    @GetMapping("/optimize")
    public ResponseEntity<?> optimizeParams(@RequestParam(defaultValue = "5") int top) {
        try {
            Map<String, Object> result = parameterOptimizer.optimize(top);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
