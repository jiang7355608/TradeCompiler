# BTC 交易信号引擎

基于 OKX 15分钟K线的自动交易系统，内置三种策略，支持自动下单、止盈止损、邮件通知、手机远程控制。

## 核心特性

- ✅ 自动下单：对接 OKX 交易 API，支持模拟盘/实盘
- ✅ 智能箱体：多次触及法 + 密度聚类，自动识别支撑压力位
- ✅ 原子止损：下单和止盈止损同一请求，无风险窗口
- ✅ 置信度调仓：高质量信号自动放大仓位，低质量信号缩小
- ✅ 多重熔断：3连亏4小时冷却，亏损40%永久停止
- ✅ 邮件通知：开仓/加仓/平仓/告警全流程通知
- ✅ 手机控制：iPhone Shortcuts 一键启停策略
- ✅ Trailing Stop：实盘自动跟踪止损，持有趋势获取大额利润

## 策略总览

| 策略 | 类型 | 适用行情 | 切换命令 |
|------|------|----------|----------|
| Aggressive | 突破追踪 | 趋势启动（推荐） | `POST /api/strategy/aggressive` |
| Conservative | 箱体突破 | 横盘后突破 | `POST /api/strategy/conservative` |
| Mean Reversion | 均值回归 | 震荡区间 | `POST /api/strategy/mean-reversion` |

## Aggressive 策略（突破追踪 + Trailing Stop）— 详细说明

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
- 防重复开仓：每次开仓前查询交易所持仓
```

### Trailing Stop 监控服务

**TrailingStopMonitor**：
```
功能：
1. 每分钟检查持仓和价格
2. 更新 trailing stop（只能向有利方向移动）
3. 触发时自动平仓并发送邮件通知

触发条件：
- 仅对 AggressiveStrategy 的 BREAKOUT 信号启用
- 开仓成功后自动启动监控
- 平仓后自动停止监控

安全保护：
- 平仓失败时发送紧急邮件
- 异常时发送告警邮件
- 所有操作都有日志记录
- 平仓失败后清除状态，避免重复告警
```

### 实际案例

**场景：价格突破上沿**
```
1. 价格突破 72000（rangeHigh），动量 > 0.3×ATR，实体 > 0.8×ATR
   → 开仓做多，入场价 72000
   → 初始止损 71500（rangeHigh - 1×ATR）
   → 启动 Trailing Stop 监控

2. 价格涨到 73000
   → trailingStop 上移到 71750（73000 - ATR × 2.5）
   → 继续持有

3. 价格涨到 75000
   → trailingStop 上移到 73750（75000 - ATR × 2.5）
   → 最大浮盈 4.17%

4. 价格回调到 73750
   → 触及 trailingStop
   → 自动平仓，实际盈利 2.43%
   → 发送邮件：Trailing Stop 平仓 LONG | 最大浮盈: 4.17%
```

## 均值回归策略（Mean Reversion）— 详细说明

### 核心理念

在动态识别的箱体内，价格触及边沿后回归中轴。通过**试探仓 + 加仓**两阶段机制，在震荡市中捕获回归收益。

### 状态机设计

```
IDLE → PROBE → CONFIRMED → IDLE
```

- **IDLE**：等待入场机会
- **PROBE**：试探仓持仓中（最多3小时），观察是否满足加仓条件
- **CONFIRMED**：加仓信号已发出，等待下单确认后 reset

### 智能箱体识别（BoxRangeDetector v3.0）

**核心算法**：多次触及法 + 密度聚类

```
算法流程：
1. 数据窗口：最近7天的4小时K线（42根）
2. 动态容忍度：基于EMA ATR自适应波动
3. 密度聚类：每个价格点作为中心，统计覆盖范围内的点数
4. 边界选择：选择最高/最低的cluster（而非触及最多的中枢）
5. 质量评分：touchCount + clusterSize + recentTouch加权
6. 边界计算：小样本(<5)用极值，大样本用分位数（P90/P10）
```

**更新机制**：
```yaml
更新频率：每24小时（每天0点）
持仓保护：如果存在持仓，跳过本次更新（避免箱体变化影响策略一致性）
稳定性检查：连续2次识别出相似箱体才确认（变化<5%）
```

**箱体验证**：
```java
触及次数：压力位和支撑位各≥3次
箱体宽度：1000-12000美元（可配置）
质量评分：score > 5.0（touchCount*0.5 + size*0.3 + recent*0.2）
```

### 试探仓阶段（IDLE → PROBE）

**入场条件**：
```
1. 箱体有效（BoxRangeDetector.isValid()）
2. 冷却期检查（45分钟全局冷却）
3. 下沿做多：
   - price <= rangeLow + buffer（buffer = 箱体宽度 × 10%）
   - 最后一根K线收阳
   - 收盘价高于前一根
4. 上沿做空：
   - price >= rangeHigh - buffer
   - 最后一根K线收阴
   - 收盘价低于前一根
```

**动态置信度**：
```java
// 试探仓置信度（0.50-0.80）
confidence = 0.50  // 基础
  + 距离边沿因子（0-0.15）  // 越接近边沿越高
  + K线实体因子（0-0.10）   // 实体越大越高
  + 双K加速因子（0-0.05）   // 连续同向加速
```

**下单参数**：
- 动态置信度：0.50-0.80
- 仓位比例：0.10
- 实际仓位：0.10 × confidence（例如 6.5%本金）
- **不带交易所止损**：策略监控保护（每1分钟检查）

### 加仓阶段（PROBE → CONFIRMED）

**触发条件**：
```
1. 浮盈检查：pnl > rangeSpan × 0.15（箱体宽度的15%）
2. 持仓时间：≥ 45分钟
3. 同时满足
```

**动态置信度**：
```java
// 加仓置信度（0.70-0.95）
confidence = 0.70  // 基础
  + 浮盈超额因子（0-0.15）  // 超过阈值越多越高
  + 持仓时间因子（0-0.10）  // 持仓越久越稳定
```

**下单参数**：
- 动态置信度：0.70-0.95
- 仓位比例：0.30
- 实际仓位：0.30 × confidence（例如 25.5%本金）
- **原子绑定整仓止盈止损**：attachAlgoOrds 一个请求完成

### 风控机制

**1. 全局冷却期（45分钟）**
```java
每次开仓后45分钟内不再开新仓
```

**2. 熔断机制（3连亏 → 4小时冷却）**
```java
连续3次亏损 → 强制休息4小时
```

**3. 账户级永久熔断（亏损40%）**
```java
currentBalance <= initialBalance × 0.60
→ 永久停止交易，必须重启程序才能恢复
```

**4. 持仓校验（以交易所为唯一真相源）**
```java
试探仓：交易所必须无持仓
加仓：交易所必须已有持仓
BREAKOUT：交易所必须无持仓
每次下单前查询交易所实际持仓
```

**5. 超时兜底**
```java
试探仓：3小时超时自动平仓
CONFIRMED：6小时超时强制平仓（fail-safe）
```

## 手机远程控制

通过 REST API + iPhone Shortcuts 实现一键启停策略。

### API 接口

| 方法 | 路径 | 说明 | Header |
|------|------|------|--------|
| POST | `/api/control/start` | 启动策略 | `X-Auth-Token: jiangyuxuanGGbond339@!` |
| POST | `/api/control/stop` | 停止策略 | `X-Auth-Token: jiangyuxuanGGbond339@!` |
| GET | `/api/control/status` | 查询状态 | `X-Auth-Token: jiangyuxuanGGbond339@!` |
| POST | `/api/control/emergency-close` | 紧急平仓 | `X-Auth-Token: jiangyuxuanGGbond339@!` |

### 安全设计

```yaml
端口：10086（避免扫描）
认证：Header 秘钥验证
秘钥：jiangyuxuanGGbond339@!
```

### iPhone Shortcuts 配置

1. 打开 Shortcuts App
2. 创建新快捷指令
3. 添加"获取 URL 内容"操作
4. 配置：
   ```
   URL: http://your-server-ip:10086/api/control/start
   方法: POST
   Headers:
     X-Auth-Token: jiangyuxuanGGbond339@!
   ```
5. 添加到主屏幕，一键启动

## 技术栈

- Java 17 + Spring Boot 3
- OKX 交易 API（支持模拟盘/实盘）
- Gmail SMTP 邮件通知
- 定时任务：
  - 主策略分析：每15分钟（对齐K线收盘）
  - 止损监控：每1分钟（试探仓保护）
  - Trailing Stop 监控：每1分钟（AggressiveStrategy）
  - 箱体识别：每24小时（每天0点，持仓时跳过）
- 月度自动回测 + 邮件报告（每月1号凌晨2点）

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/signal/run` | 手动触发一次分析 |
| GET | `/api/strategy` | 查看当前策略 |
| POST | `/api/strategy/{name}` | 切换策略 |
| POST | `/api/backtest/run` | 触发月度回测 |
| GET | `/api/optimize?top=5` | 参数优化，返回 Top N 推荐 |

## 配置

所有参数在 `trading-signal/src/main/resources/application.yml`，修改后重启生效。

关键配置：
```yaml
server:
  port: 10086  # 避免扫描

trade-api:
  enabled: true          # 启用自动下单
  simulated: true        # 模拟盘（改为false使用实盘）
  api-key: "..."         # OKX API Key
  secret-key: "..."      # OKX Secret Key
  passphrase: "..."      # OKX Passphrase
  leverage: 20           # 杠杆倍数

email:
  enabled: true          # 启用邮件通知
  sender-email: "..."    # Gmail 发件地址
  app-password: "..."    # Gmail 应用专用密码
  receiver-email: "..."  # 收件地址

params:
  # 箱体识别配置
  box-max-width: 12000   # 箱体最大宽度（美元）
  box-min-width: 1000    # 箱体最小宽度（美元）
  mr-entry-buffer-pct: 0.10  # 入场缓冲区（箱体宽度的百分比）
  
  # 手动箱体（可选，设置为0时使用自动识别）
  mr-range-high: 0       # 手动箱体上沿
  mr-range-low: 0        # 手动箱体下沿
```

## 运行

```bash
cd trading-signal
mvn spring-boot:run
```

回测：
```bash
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

## 风险提示

1. **AggressiveStrategy**：
   - 使用 Trailing Stop，无固定止盈
   - 每分钟检查一次，存在1分钟风险窗口
   - 建议先用模拟盘测试

2. **Mean Reversion**：
   - 试探仓期间（最多3小时）没有交易所止损，依赖策略监控
   - 策略每1分钟检查止损，存在1分钟的风险窗口
   - 如果程序崩溃，试探仓会裸奔（建议小仓位测试）
   - 加仓后有交易所止损保护，风险可控

3. **通用风险**：
   - 建议先用模拟盘测试，确认逻辑无误后再上实盘
   - 永久熔断触发后必须重启程序才能恢复交易
   - 手机控制接口需要妥善保管秘钥

## 项目评价

这是一个完整的量化交易系统，具备以下特点：

**优势**：
- ✅ 完整的风控体系（多重熔断、持仓校验、超时保护）
- ✅ 灵活的策略切换（三种策略适应不同行情）
- ✅ 智能的仓位管理（置信度动态调整）
- ✅ 原子性保证（下单和止损同一请求）
- ✅ 实时监控（Trailing Stop、止损监控）
- ✅ 便捷的远程控制（手机一键启停）
- ✅ 完善的通知机制（邮件全流程通知）

**适用场景**：
- 趋势行情：AggressiveStrategy（Trailing Stop 持有趋势）
- 震荡行情：Mean Reversion（箱体回归）
- 保守交易：Conservative（严格突破条件）

**技术亮点**：
- 智能箱体识别（密度聚类 + 动态容忍度）
- Trailing Stop 实现（只能向有利方向移动）
- 防重复开仓保护（以交易所为唯一真相源）
- 动态置信度调仓（高质量信号放大仓位）
- 账户级永久熔断（保护本金）

**建议**：
- 先用模拟盘测试至少1个月
- 观察回测数据和实盘表现
- 根据市场情况灵活切换策略
- 定期检查邮件通知和日志
- 妥善保管 API 密钥和控制秘钥

### 核心理念

在动态识别的箱体内，价格触及边沿后回归中轴。通过**试探仓 + 加仓**两阶段机制，在震荡市中捕获回归收益。

### 状态机设计

```
IDLE → PROBE → CONFIRMED → IDLE
```

- **IDLE**：等待入场机会
- **PROBE**：试探仓持仓中（最多3小时），观察是否满足加仓条件
- **CONFIRMED**：加仓信号已发出，等待下单确认后 reset

### 智能箱体识别（BoxRangeDetector v3.0）

**核心算法**：多次触及法 + 密度聚类

```
算法流程：
1. 数据窗口：最近7天的4小时K线（42根）
2. 动态容忍度：基于EMA ATR自适应波动
3. 密度聚类：每个价格点作为中心，统计覆盖范围内的点数
4. 边界选择：选择最高/最低的cluster（而非触及最多的中枢）
5. 质量评分：touchCount + clusterSize + recentTouch加权
6. 边界计算：小样本(<5)用极值，大样本用分位数（P90/P10）
```

**更新机制**：
```yaml
更新频率：每24小时（每天0点）
持仓保护：如果存在持仓，跳过本次更新（避免箱体变化影响策略一致性）
稳定性检查：连续2次识别出相似箱体才确认（变化<5%）
```

**箱体验证**：
```java
触及次数：压力位和支撑位各≥3次
箱体宽度：1000-12000美元（可配置）
质量评分：score > 5.0（touchCount*0.5 + size*0.3 + recent*0.2）
```

**日志输出示例**：
```
动态容忍度计算: ATR=450, 平均价格=74973, tolerance=0.90%
高点clusters（前3个）:
  center=72350, touchCount=34, size=28, score=21.5, range=[71800-72900]
  center=70500, touchCount=18, size=15, score=12.3, range=[70000-71000]
低点clusters（前3个）:
  center=71050, touchCount=29, size=24, score=19.2, range=[70500-71600]
最终选中边界: 压力=72350 (touch=34, score=21.5), 支撑=71050 (touch=29, score=19.2)
箱体宽度=1300 (1.8%), 当前价格=71500
```

**优势**：
- ✅ 识别真实边界（而非价格中枢）
- ✅ 自适应市场波动（ATR动态容忍度）
- ✅ 避免箱体滞后（只用最近7天数据）
- ✅ 持仓期间稳定（不更新箱体）

### 动态箱体（已废弃，保留用于回测）

```yaml
# 旧版简单算法（仅用于回测对比）
箱体计算：
  1. 取最近 6 根 4h K线（24小时）
  2. 计算最高价和最低价作为箱体上下沿
  3. 如果 (maxHigh - minLow) / minLow < 阈值，判定为箱体震荡

箱体失效：
  - isRange=false 时自动停止交易
  - 箱体宽度 < 价格1% 时拒绝交易（避免噪音）
```

### 入场缓冲区（动态计算）

**旧版**：固定500美元
```yaml
mr-entry-buffer: 500  # 问题：窄箱体会重叠，宽箱体太小
```

**新版**：箱体宽度的百分比
```yaml
mr-entry-buffer-pct: 0.10  # 10%
# 箱体1075美元 → 缓冲区107.5美元
# 箱体10000美元 → 缓冲区1000美元
```

### 试探仓阶段（IDLE → PROBE）

**入场条件**：
```
1. 箱体有效（BoxRangeDetector.isValid()）
2. 冷却期检查（45分钟全局冷却）
3. 下沿做多：
   - price <= rangeLow + buffer（buffer = 箱体宽度 × 10%）
   - 最后一根K线收阳
   - 收盘价高于前一根
4. 上沿做空：
   - price >= rangeHigh - buffer
   - 最后一根K线收阴
   - 收盘价低于前一根
```

**动态置信度**：
```java
// 试探仓置信度（0.50-0.80）
confidence = 0.50  // 基础
  + 距离边沿因子（0-0.15）  // 越接近边沿越高
  + K线实体因子（0-0.10）   // 实体越大越高
  + 双K加速因子（0-0.05）   // 连续同向加速
```

**止损计算**：
```java
slDist = max(rangeSpan × 0.15, atr × 2)
stopLoss = entryPrice ± slDist
```

**下单参数**：
- 动态置信度：0.50-0.80（基于距离边沿、K线实体、双K加速）
- 仓位比例：0.10
- 实际仓位：0.10 × confidence（例如0.10 × 0.65 = 0.065，6.5%本金）
- **不带交易所止损**：策略监控保护（每1分钟检查）

**风险保护**：
- 策略监控止损：每1分钟检查价格是否触及止损线
- 超时平仓：持仓3小时未触发加仓条件，自动平仓
- 箱体失效：价格强势突破箱体，自动平仓（当前已注释）

### 加仓阶段（PROBE → CONFIRMED）

**触发条件**：
```
1. 浮盈检查：pnl > rangeSpan × 0.15（箱体宽度的15%，从25%降低）
2. 持仓时间：≥ 45分钟
3. 同时满足
```

**动态置信度**：
```java
// 加仓置信度（0.70-0.95）
confidence = 0.70  // 基础
  + 浮盈超额因子（0-0.15）  // 超过阈值越多越高
  + 持仓时间因子（0-0.10）  // 持仓越久越稳定
```

**加仓止损（保本止损）**：
```java
protectDist = max(rangeSpan × 0.05, atr × 1.0)
stopLoss = entryPrice + protectDist  // 多头止损上移到入场价上方
stopLoss = max(newSl, oldSl)  // 防止止损倒退
```

**下单参数**：
- 动态置信度：0.70-0.95（基于浮盈超额、持仓时间）
- 仓位比例：0.30
- 实际仓位：0.30 × confidence（例如0.30 × 0.85 = 0.255，25.5%本金）
- **原子绑定整仓止盈止损**：attachAlgoOrds 一个请求完成

**原子性保证**：
```
加仓下单 + 整仓止盈止损 = 一个 API 请求
→ 要么全成功，要么全失败
→ 不存在"加仓成功但止损未设置"的窗口期
```

### 风控机制

**1. 全局冷却期（45分钟）**
```java
每次开仓后45分钟内不再开新仓
```

**2. 熔断机制（3连亏 → 4小时冷却）**
```java
连续3次亏损 → 强制休息4小时
```

**3. 账户级永久熔断（亏损40%）**
```java
currentBalance <= initialBalance × 0.60
→ 永久停止交易，必须重启程序才能恢复
```

**4. 持仓校验（以交易所为唯一真相源）**
```java
试探仓：交易所必须无持仓
加仓：交易所必须已有持仓
每次下单前查询交易所实际持仓
```

**5. 超时兜底**
```java
试探仓：3小时超时自动平仓
CONFIRMED：6小时超时强制平仓（fail-safe）
```

### 邮件通知

**试探仓开仓**：
```
主题：[试探仓开仓] BTC-USDT
内容：方向、价格、仓位、无交易所止损、3小时超时
```

**加仓成功**：
```
主题：[加仓成功] BTC-USDT
内容：方向、价格、仓位、整仓止损、整仓止盈
```

**试探仓超时平仓**：
```
主题：[试探仓超时平仓] BTC-USDT
内容：当前价格、原因分析、操作指引
```

**试探仓止损平仓**：
```
主题：[试探仓止损平仓] BTC-USDT
内容：当前价格、策略监控止损说明
```

### 实际案例

**场景：价格跌到下沿**
```
1. 价格67200，触发试探仓
   → 下单1张（6%本金），不带止损
   → 策略监控：止损66500，每1分钟检查

2. 价格涨到67900，浮盈700，持仓45分钟
   → 触发加仓
   → 下单1张（25.5%本金）
   → 同时设置整仓止损67550、止盈68200

3. 交易所自动执行止盈止损
   → 价格到68200，止盈平仓
   → 或价格到67550，止损平仓（保本）
```

## 突破策略（Aggressive / Conservative）

核心逻辑：横盘蓄力 → 放量突破 → 趋势确认 → 延续验证

```
收到新K线
├─ 横盘蓄力（激进策略必须，保守策略可选加分）
├─ 成交量放大
├─ 短窗口 + 长窗口突破方向一致
├─ 不逆势（激进允许中性，保守要求明确顺势）
├─ 延续确认（激进单根即可，保守必须连续两根）
├─ 过滤扫单K线 + 位置过滤 + 冷却期
└─ ✅ 发出信号
```

动态止损止盈：
- 止损 = 前一根K线中点（跌回前K线中间说明突破失败）
- 止盈 = 止损距离 × 风险回报比（激进3.0倍，保守1.5倍）

## 技术栈

- Java 17 + Spring Boot 3
- OKX 交易 API（支持模拟盘/实盘）
- Gmail SMTP 邮件通知
- 定时任务：
  - 主策略分析：每15分钟（对齐K线收盘）
  - 止损监控：每1分钟（试探仓保护）
  - 箱体识别：每24小时（每天0点，持仓时跳过）
- 月度自动回测 + 邮件报告（每月1号凌晨2点）

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/signal/run` | 手动触发一次分析 |
| GET | `/api/strategy` | 查看当前策略 |
| POST | `/api/strategy/{name}` | 切换策略 |
| POST | `/api/backtest/run` | 触发月度回测 |
| GET | `/api/optimize?top=5` | 参数优化，返回 Top N 推荐 |

## 配置

所有参数在 `trading-signal/src/main/resources/application.yml`，修改后重启生效。

关键配置：
```yaml
trade-api:
  enabled: true          # 启用自动下单
  simulated: true        # 模拟盘（改为false使用实盘）
  api-key: "..."         # OKX API Key
  secret-key: "..."      # OKX Secret Key
  passphrase: "..."      # OKX Passphrase
  leverage: 20           # 杠杆倍数

email:
  enabled: true          # 启用邮件通知
  sender-email: "..."    # Gmail 发件地址
  app-password: "..."    # Gmail 应用专用密码
  receiver-email: "..."  # 收件地址

params:
  # 箱体识别配置
  box-max-width: 12000   # 箱体最大宽度（美元）
  box-min-width: 1000    # 箱体最小宽度（美元）
  mr-entry-buffer-pct: 0.10  # 入场缓冲区（箱体宽度的百分比）
  
  # 手动箱体（可选，设置为0时使用自动识别）
  mr-range-high: 0       # 手动箱体上沿
  mr-range-low: 0        # 手动箱体下沿
```

## 运行

```bash
cd trading-signal
mvn spring-boot:run
```

回测：
```bash
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

## 风险提示

1. 试探仓期间（最多3小时）没有交易所止损，依赖策略监控
2. 策略每1分钟检查止损，存在1分钟的风险窗口
3. 如果程序崩溃，试探仓会裸奔（建议小仓位测试）
4. 加仓后有交易所止损保护，风险可控
5. 建议先用模拟盘测试，确认逻辑无误后再上实盘
