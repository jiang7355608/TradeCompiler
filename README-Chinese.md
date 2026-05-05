# BTC 交易信号引擎

基于 OKX 15分钟K线的自动交易系统，内置两种策略（突破追踪 + 均值回归），支持自动下单、止盈止损、邮件通知、手机远程控制。

## 核心特性

- ✅ 自动下单：对接 OKX 交易 API，支持模拟盘/实盘
- ✅ 智能箱体：多次触及法 + 密度聚类，自动识别支撑压力位
- ✅ 原子止损：下单和止盈止损同一请求，无风险窗口
- ✅ Trailing Stop：实盘自动跟踪止损，持有趋势获取大额利润
- ✅ 多重熔断：2连亏1小时冷却，亏损40%永久停止
- ✅ 邮件通知：开仓/平仓/告警全流程通知
- ✅ 手机控制：iPhone Shortcuts 一键启停策略
- ✅ 月度回测：自动回测并发送邮件报告

## 策略总览

| 策略 | 类型 | 适用行情 | 状态 |
|------|------|----------|------|
| Aggressive | 突破追踪 + Trailing Stop | 趋势启动 | ✅ 生产就绪 |
| Mean Reversion | 均值回归 | 震荡区间 | ✅ 生产就绪 |

配置切换：修改 `application.yml` 中的 `trading.strategy: aggressive` 或 `trading.strategy: mean-reversion`

---

## Aggressive 策略（突破追踪 + Trailing Stop）

### 核心理念

抓住趋势启动，通过 Trailing Stop 持有趋势，获取大额利润。不等回踩，突破确认后立即入场。

### 策略逻辑

**1. 区间定义**
```
使用最近 40 根 K 线（不包含当前K线）
rangeHigh = 最高价
rangeLow = 最低价
过滤条件：区间宽度 > 3%（过滤无波动市场）
```

**2. 突破入场**
```
做多条件：
- 当前K线收盘价 > rangeHigh
- (close - rangeHigh) > 0.3×ATR（动量确认）
- K线实体 > 0.8×ATR（实体过滤，减少假突破）
→ 立即做多

做空条件：
- 当前K线收盘价 < rangeLow
- (rangeLow - close) > 0.3×ATR（动量确认）
- K线实体 > 0.8×ATR（实体过滤）
→ 立即做空
```

**3. 止损**
```
做多：SL = rangeHigh - 1×ATR
做空：SL = rangeLow + 1×ATR
```

**4. Trailing Stop（核心）**
```
不使用固定止盈，改为动态 Trailing Stop：

做多：
- 初始 trailingStop = stopLoss
- 每分钟更新：trailingStop = max(trailingStop, currentPrice - ATR × 2.5)
- 平仓条件：price <= trailingStop

做空：
- 初始 trailingStop = stopLoss
- 每分钟更新：trailingStop = min(trailingStop, currentPrice + ATR × 2.5)
- 平仓条件：price >= trailingStop

关键特性：
✅ trailingStop 只能向有利方向移动
✅ ATR × 2.5 给趋势足够空间
✅ 记录最大浮盈，平仓时邮件通知
```

**5. 仓位管理**
```
单笔风险：3% 账户资金
仓位 = riskAmount / (止损距离 / 价格)
约束：
- 最大仓位：30%
- 无最小仓位限制
```

**6. 防止过度交易**
```
- 同一方向连续失败2次 → 暂停该方向1小时
- 全局冷却：15分钟
```

---

## 均值回归策略（Mean Reversion）

### 核心理念

在箱体震荡中低买高卖，赚取价格回归中线的利润。

### 箱体识别（BoxRangeDetector）

**算法**：
```
1. 数据窗口：最近7天的4小时K线（42根）
2. 动态容忍度：基于EMA ATR自适应波动
3. 密度聚类：每个价格点作为中心，统计覆盖范围内的点数
4. 边界选择：选择最高/最低的cluster（真实边界，而非价格中枢）
5. 质量评分：touchCount + clusterSize + recentTouch加权
6. 边界计算：小样本(<5)用极值，大样本用分位数（P90/P10）
```

**更新机制**：
```
更新频率：每24小时（每天0点）
持仓保护：如果存在持仓，跳过本次更新（避免箱体变化影响策略一致性）
稳定性检查：连续2次识别出相似箱体才确认（变化<5%）
箱体验证：触及次数≥3次，宽度1000-12000美元
```

### 入场逻辑

**做多（接近支撑位）**：
```
条件：
- 价格 < rangeLow + 15% 箱体宽度
- 价格 > rangeLow（未跌破）
→ 做多，目标箱体中线
```

**做空（接近压力位）**：
```
条件：
- 价格 > rangeHigh - 15% 箱体宽度
- 价格 < rangeHigh（未突破）
→ 做空，目标箱体中线
```

### 止损止盈

```
做多：
- 止损：SL = rangeLow - 1×ATR
- 止盈：TP = (rangeHigh + rangeLow) / 2

做空：
- 止损：SL = rangeHigh + 1×ATR
- 止盈：TP = (rangeHigh + rangeLow) / 2
```

### 仓位管理

```
单笔风险：2% 账户资金
最大仓位：30%
最小仓位：5%（过小的仓位不值得交易）
冷却期：15分钟全局，1小时方向冷却（2连亏后）
```

### 回测使用

```java
// 使用 MockBoxRangeDetector 进行回测（手动指定箱体）
MockBoxRangeDetector mockBox = new MockBoxRangeDetector(85000, 80000);
MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();
BacktestResult result = engine.run(csvPath, strategy, 200.0, 20);
```

---

## 风控体系（5层）

1. **全局冷却期（15分钟）** — 每次开仓后15分钟内不再开新仓
2. **方向暂停（2连亏 → 1小时）** — 同一方向连续亏损2次，暂停该方向1小时
3. **持仓校验** — 每次下单前查询交易所实际持仓（以交易所为唯一真相源）
4. **账户级永久熔断（亏损40%）** — 余额跌至初始余额60%时触发，程序生命周期内永久停止，必须重启程序恢复
5. **Trailing Stop 监控** — 仅 AggressiveStrategy，每分钟检查，止损线只能向有利方向移动

---

## 手机远程控制

通过 REST API + iPhone Shortcuts 实现一键启停策略。

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/control/start` | 启动策略 |
| POST | `/api/control/stop` | 停止策略 |
| GET  | `/api/control/status` | 查询状态 |
| POST | `/api/control/emergency-close` | 紧急平仓 |

Header：`X-Auth-Token: <your-token>`

### iPhone Shortcuts 配置

1. 打开 Shortcuts App
2. 创建新快捷指令
3. 添加"获取 URL 内容"操作
4. 配置：
   ```
   URL: http://your-server-ip:10086/api/control/start
   方法: POST
   Headers:
     X-Auth-Token: <your-token>
   ```
5. 添加到主屏幕，一键启动

---

## 技术栈

- Java 17 + Spring Boot 3
- OKX 交易 API（支持模拟盘/实盘）
- Gmail SMTP 邮件通知
- 定时任务：
  - 主策略分析：每15分钟（对齐K线收盘）
  - Trailing Stop 监控：每1分钟（AggressiveStrategy）
  - 箱体识别：每24小时（每天0点，持仓时跳过）
  - 月度回测：每月1号凌晨2点

## 配置

所有参数在 `trading-signal/src/main/resources/application.yml`，修改后重启生效。

```yaml
server:
  port: 10086

trading:
  strategy: aggressive  # 或 mean-reversion

  trade-api:
    enabled: false      # true 启用自动下单
    simulated: true     # false 使用实盘
    api-key: "..."
    secret-key: "..."
    passphrase: "..."
    leverage: 20

  email:
    enabled: false
    sender-email: "..."
    app-password: "..."  # Gmail 应用专用密码
    receiver-email: "..."

  backtest:
    initial-capital: 200.0
    leverage: 20
    data-dir: backtest-data
```

## 运行

```bash
cd trading-signal
mvn spring-boot:run
```

回测（Aggressive）：
```bash
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

回测（Mean Reversion）：
```java
// 修改 BacktestRunner.java 使用 MeanReversionBacktestEngine
MockBoxRangeDetector mockBox = new MockBoxRangeDetector(85000, 80000);
MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();
BacktestResult result = engine.run(CSV_FILE, strategy, INITIAL_CAPITAL, LEVERAGE);
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/signal/run` | 手动触发一次分析 |
| GET | `/api/strategy` | 查看当前策略 |
| POST | `/api/backtest/run` | 触发月度回测 |
| GET | `/api/backtest/range?start=2026-03-01&end=2026-03-31` | 分段回测 |

## 项目结构

```
trading-signal/
├── src/main/java/com/trading/signal/
│   ├── strategy/          # 交易策略
│   │   ├── Strategy.java                # 策略接口
│   │   ├── AggressiveStrategy.java      # 突破策略
│   │   ├── MeanReversionStrategy.java   # 均值回归策略
│   │   └── StrategyRouter.java          # 策略路由器（配置驱动）
│   ├── backtest/          # 回测引擎
│   │   ├── AggressiveBacktestEngine.java
│   │   ├── MeanReversionBacktestEngine.java
│   │   ├── BacktestResult.java
│   │   ├── BacktestRunner.java
│   │   ├── BacktestScheduler.java
│   │   └── DataFetcher.java
│   ├── service/           # 核心服务
│   │   ├── SignalService.java           # 主调度（每15分钟）
│   │   ├── TradeExecutor.java           # 下单执行 + 熔断保护
│   │   ├── BoxRangeDetector.java        # 密度聚类箱体识别
│   │   ├── TrailingStopMonitor.java     # Trailing Stop 监控
│   │   ├── EmailService.java            # Gmail SMTP 通知
│   │   └── SignalWriter.java            # 写入 signal.json
│   ├── client/            # OKX API 客户端
│   │   ├── OkxClient.java               # 行情数据（K线）
│   │   └── OkxTradeClient.java          # 下单/持仓/余额
│   ├── controller/        # REST 接口
│   │   ├── ControlController.java       # 启停/急停（手机控制）
│   │   └── SignalController.java        # 手动触发/回测
│   ├── model/             # 数据模型
│   │   ├── KLine.java
│   │   ├── MarketData.java
│   │   └── TradeSignal.java
│   ├── analyzer/
│   │   └── MarketAnalyzer.java          # ATR + 技术指标计算
│   └── config/
│       └── TradingProperties.java       # application.yml 配置绑定
└── src/main/resources/
    └── application.yml    # 配置文件
```

## 风险提示

**Aggressive 策略**：
- 使用 Trailing Stop，无固定止盈
- 每分钟检查一次，存在1分钟风险窗口
- 建议先用模拟盘测试

**Mean Reversion 策略**：
- 使用固定止盈止损，无 Trailing Stop
- 需要有效箱体（宽度1000-12000美元）
- 箱体识别每天更新，可能滞后于市场变化
- 建议先用模拟盘测试

**通用风险**：
- 建议先用模拟盘测试，确认逻辑无误后再上实盘
- 永久熔断触发后必须重启程序才能恢复交易
- 手机控制接口需要妥善保管秘钥

## License

MIT
