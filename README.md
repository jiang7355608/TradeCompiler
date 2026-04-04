# BTC 交易信号引擎

基于 OKX K线数据的智能交易系统，集成信号生成、自动交易、回测验证、参数优化于一体。支持三种策略（突破/均值回归），可配置邮件通知和模拟盘/实盘自动下单。

## 自动交易功能

系统支持通过OKX API自动执行交易，包括：

1. 自动设置杠杆倍数
2. 根据信号自动开仓（市价单）
3. 同时设置止损和止盈订单
4. 查询账户余额

### 配置步骤

1. 在OKX创建API Key（需要交易权限）
2. 在 `application.yml` 中配置：
```yaml
trading:
  trade-api:
    enabled: true
    api-key: "your-api-key"
    secret-key: "your-secret-key"
    passphrase: "your-passphrase"
    simulated: true  # 建议先用模拟盘测试
    leverage: 20
```

3. 重启服务

### 安全提示
- 建议先在模拟盘测试（`simulated: true`）
- 实盘前务必充分回测验证策略
- 设置合理的仓位大小和止损
- 定期检查账户状态

## 邮件通知

当生成交易信号且置信度 ≥ `min-confidence` 时，自动发送邮件：

### 邮件内容
- 信号方向（LONG/SHORT）
- 入场价格
- 止损价格
- 止盈价格
- 置信度
- 建议仓位
- 市场分析（横盘状态、趋势方向、成交量等）

### Gmail配置
1. 开启Gmail两步验证
2. 生成应用专用密码：https://myaccount.google.com/apppasswords
3. 在 `application.yml` 中配置应用专用密码（非Gmail登录密码）

## 策略总览

系统包含两类策略，适应不同市场状态：

| 策略 | 类型 | 适用行情 | 切换命令 |
|------|------|----------|----------|
| Aggressive | 箱体突破 | 横盘后的趋势启动 | `POST /api/strategy/aggressive` |
| Conservative | 箱体突破 | 同上，条件更严 | `POST /api/strategy/conservative` |
| Mean Reversion | 均值回归 | 震荡区间 | `POST /api/strategy/mean-reversion` |

## 市场结构分析

每15分钟K线收盘后自动分析：

- 箱体识别：最近N根K线高低点价差是否在阈值内
- 双窗口突破：短窗口（6根=1.5h）+ 长窗口（14根=3.5h）同时突破才算有效
- 放量验证：当前量 > 近20根均量 × 倍数
- EMA趋势：EMA5 vs EMA20 判断趋势方向和强度
- 延续确认：突破后价格是否继续朝突破方向运动

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

## 均值回归策略（Mean Reversion）

核心逻辑：在震荡区间的上下沿做反转交易

```
收到新K线
├─ 必须处于区间内（isRange=true）
├─ 趋势入侵检测（EMA偏离度 → 软惩罚）
├─ 趋势萌芽检测（方向性，拆分上下：只禁止逆势方向）
├─ 长窗口突破 → 硬禁止 / 短窗口突破 → 软惩罚
├─ 极端振幅 → 硬禁止 / 普通高振幅 → 软惩罚
│
├─ 接近下沿 → 做多
│   ├─ 强反弹（先跌再弹）→ 高置信度 0.70
│   └─ 弱反弹（单根反弹）→ 低置信度 0.55
│
├─ 接近上沿 → 做空
│   ├─ 强回落（先涨再落）→ 高置信度 0.70
│   └─ 弱回落（单根转弱）→ 低置信度 0.55
│
└─ 置信度惩罚叠加（下限0.30）：
    短窗口突破 -0.10 / 高振幅 -0.05 / EMA入侵 -0.15
    逆向动量 -0.10 / 趋势偏反向 -0.15
```

动态止损止盈：
- 止损 = 区间边沿外 20%区间宽度（上限：价格的0.5%）
- 止盈 = 真实箱体止损距离 × 3.0倍风险回报比（不超过箱体内侧90%）
- 区间宽时止损远止盈大，区间窄时止损紧止盈小

关键防护：
- 趋势萌芽拆分方向：强下跌趋势禁止做多（接飞刀），但允许强上涨趋势回调做多（顺势）
- 止盈用真实箱体距离计算，不受最大止损截断影响
- 止盈越界保护：锁定在箱体内部

## 参数优化

内置网格搜索优化器，按策略类型搜索不同参数空间：

- 突破策略：rangeThreshold × volumeMultiplier × riskRewardRatio × minSlPct（400组）
- 均值回归：mrBuffer × mrSlBuffer × mrMaxSlPct × mrRiskRewardRatio（256组）

评分公式：`score = 期望值 × √交易次数 × (1 - 最大回撤×2) × 利润因子`

优化器只输出推荐参数，不自动应用，由人工决定是否采纳。

使用方式：
```bash
# 通过API触发优化
curl http://localhost:8080/api/optimize?top=5

# 或在代码中调用 ParameterOptimizer
```

## 回测系统

### 自动月度回测
- 每月1号凌晨2点自动运行
- 使用最近一个月的历史数据
- 对比当前策略与其他策略的表现
- 生成详细报告并发送邮件

### 手动回测
```bash
cd trading-signal
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

### 回测数据格式
CSV文件，存放在 `backtest-data/` 目录：
```
timestamp,open,high,low,close,volume
1709251200000,67890.5,68123.4,67654.3,67980.2,1234.56
```

### 回测指标
- 总收益率
- 胜率
- 平均盈亏比
- 最大回撤
- 利润因子
- 交易次数
- 期望值

## 核心功能

1. 实时信号生成：定时拉取OKX K线，分析市场结构，生成交易信号
2. 自动交易执行：支持OKX模拟盘/实盘API，自动下单、设置止损止盈
3. 邮件通知：Gmail SMTP推送信号详情（入场价、止损、止盈、置信度）
4. 历史回测：基于CSV数据回测策略表现，计算收益率、胜率、最大回撤等指标
5. 参数优化：网格搜索最优参数组合，输出Top N推荐配置
6. 月度报告：每月自动回测并发送邮件报告

## 技术栈

- Java 17 + Spring Boot 3.2.3
- OKX REST API（公共数据 + 交易API）
- Jakarta Mail（Gmail SMTP）
- Jackson（JSON解析）
- Maven（依赖管理）
- 定时任务：
  - 信号生成：cron `10 0/15 * * * *`（每15分钟，整点后10秒）
  - 月度回测：cron `0 0 2 1 * ?`（每月1号凌晨2点）

## API 接口

服务默认运行在 `http://localhost:8080`

### 信号管理

| 方法 | 路径 | 说明 | 示例 |
|------|------|------|------|
| GET | `/api/signal/run` | 手动触发一次信号分析 | `curl http://localhost:8080/api/signal/run` |

### 策略切换

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/strategy` | 查看当前激活的策略 |
| POST | `/api/strategy/aggressive` | 切换到激进突破策略 |
| POST | `/api/strategy/conservative` | 切换到保守突破策略 |
| POST | `/api/strategy/mean-reversion` | 切换到均值回归策略 |

### 回测与优化

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/backtest/run` | 手动触发月度回测 |
| GET | `/api/optimize?top=5` | 参数优化，返回Top N推荐配置 |

示例：
```bash
# 切换策略
curl -X POST http://localhost:8080/api/strategy/aggressive

# 触发回测
curl -X POST http://localhost:8080/api/backtest/run

# 参数优化（返回前5个最优配置）
curl http://localhost:8080/api/optimize?top=5
```

## 项目结构

```
trading-signal/
├── src/main/java/com/trading/signal/
│   ├── analyzer/          # 市场分析（横盘、突破、EMA趋势）
│   ├── backtest/          # 回测引擎、数据获取、参数优化
│   ├── client/            # OKX API客户端（行情+交易）
│   ├── config/            # 配置类（application.yml绑定）
│   ├── controller/        # REST API控制器
│   ├── model/             # 数据模型（K线、信号、市场数据）
│   ├── service/           # 核心服务（信号生成、邮件、交易执行）
│   ├── strategy/          # 三种策略实现 + 路由
│   └── TradingSignalApplication.java  # 启动类
├── src/main/resources/
│   └── application.yml    # 全局配置文件
└── pom.xml                # Maven依赖
```

## 配置说明

所有参数在 `application.yml` 中配置，修改后重启生效。主要配置项：

### 基础配置
- `trading.strategy`：当前激活策略（aggressive/conservative/mean-reversion）
- `trading.okx.inst-id`：交易对（默认BTC-USDT）
- `trading.okx.timeframe`：K线周期（15m/1m等）
- `trading.scheduler.interval-ms`：信号生成间隔（毫秒）

### 代理配置
- `trading.proxy.enabled`：是否启用代理
- `trading.proxy.host/port`：代理地址和端口

### 邮件配置
- `trading.email.enabled`：是否启用邮件通知
- `trading.email.sender-email`：Gmail发件地址
- `trading.email.app-password`：Gmail应用专用密码（非登录密码）
- `trading.email.min-confidence`：触发邮件的最低置信度（0.0~1.0）
- `trading.email.cooldown-ms`：同方向信号冷却时间

### 交易API配置
- `trading.trade-api.enabled`：是否启用自动交易
- `trading.trade-api.api-key/secret-key/passphrase`：OKX API凭证
- `trading.trade-api.simulated`：true=模拟盘，false=实盘
- `trading.trade-api.leverage`：杠杆倍数

### 策略参数
详见 `application.yml` 中的 `trading.params` 部分，包含：
- 市场分析参数（横盘阈值、突破窗口、成交量倍数、EMA周期等）
- 各策略专属参数（风险回报比、止损比例、仓位大小、冷却时间等）
- 均值回归策略的区间上下沿定义

### 回测配置
- `trading.backtest.initial-capital`：回测初始本金
- `trading.backtest.leverage`：回测杠杆倍数
- `trading.backtest.data-dir`：回测数据目录

## 快速开始

### 1. 环境要求

- Java 17+
- Maven 3.6+
- （可选）代理工具（访问OKX API）

### 2. 配置

编辑 `trading-signal/src/main/resources/application.yml`：

```yaml
trading:
  strategy: mean-reversion  # 策略选择：aggressive | conservative | mean-reversion
  
  # 代理配置（本地测试时开启）
  proxy:
    enabled: true
    host: 127.0.0.1
    port: 7897
  
  # 邮件通知
  email:
    enabled: true
    sender-email: your-email@gmail.com
    app-password: your-app-password  # Gmail应用专用密码
    receiver-email: receiver@gmail.com
    min-confidence: 0.6
  
  # 自动交易（可选）
  trade-api:
    enabled: false  # true=启用自动下单
    api-key: ""
    secret-key: ""
    passphrase: ""
    simulated: true  # true=模拟盘，false=实盘
    leverage: 20
```

### 3. 运行

启动信号引擎：
```bash
cd trading-signal
mvn spring-boot:run
```

服务启动后：
- 自动定时拉取K线并生成信号
- 信号写入 `signal.json`
- 满足条件时发送邮件通知
- 如启用自动交易，会自动下单

### 4. 手动回测

```bash
cd trading-signal
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

回测数据存放在 `backtest-data/` 目录，格式为CSV。

## 常见问题

### 1. 无法连接OKX API
- 检查代理配置是否正确
- 确认代理工具正在运行
- 尝试在浏览器访问 https://www.okx.com 测试网络

### 2. 邮件发送失败
- 确认使用的是Gmail应用专用密码，不是登录密码
- 检查Gmail账户是否开启了两步验证
- 查看日志中的详细错误信息

### 3. 自动交易不执行
- 检查 `trade-api.enabled` 是否为 `true`
- 确认API Key权限包含交易权限
- 查看日志中是否有API调用错误

### 4. 回测数据从哪里获取
- 使用 `DataFetcher` 类从OKX拉取历史数据
- 或手动准备CSV格式的K线数据
- 数据格式：timestamp,open,high,low,close,volume

### 5. 如何调整策略参数
- 编辑 `application.yml` 中的 `trading.params` 部分
- 使用参数优化器找到最优配置
- 通过回测验证参数效果

## 开发计划

- [ ] 支持更多交易对
- [ ] 增加更多技术指标
- [ ] Web界面监控
- [ ] 实时性能监控
- [ ] 多策略组合

## 许可证

MIT License

## 免责声明

本项目仅供学习研究使用，不构成投资建议。使用本系统进行实盘交易的风险由使用者自行承担。加密货币交易存在高风险，可能导致本金损失。
