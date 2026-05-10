# BTC Trading Signal Engine

Production-grade algorithmic trading system for BTC-USDT perpetual futures. Runs on OKX with two adaptive strategies, live execution, trailing stops, and mobile remote control.

## Core Features

- Auto‑execution via OKX API (simulated/live)
- Dynamic range detection using density clustering with multi‑touch validation
- Atomic stop‑loss: order and SL/TP in a single request
- Confidence‑based position sizing
- Trailing stop for trend‑following (Aggressive strategy only)
- Multi‑layer circuit breakers (2‑loss cooldown, 40% drawdown kill switch)
- Email notifications and remote control via REST API
- Monthly backtesting with automated reports
- **AI Agent for strategy switching** – LLM‑driven market regime detection that decides when to switch between aggressive and mean‑reversion strategies

## AI Agent – Market Regime Detection

The system includes an LLM‑driven decision layer that automatically detects market regime changes and decides when to switch between aggressive and mean‑reversion strategies.

**Components**
- `MarketRegimeAgent` – Scheduled every 8 hours, pulls candles, computes indicators, calls LLM
- `MarketRegimeAnalyzer` – Technical indicator calculator (EMA slope, ATR ratio, volatility, breakout success rate)
- `OpenRouterClient` – HTTP client for OpenRouter API with OpenAI‑compatible function calling
- `ToolDefinitionBuilder` – Converts the `switchStrategy` tool into OpenAI function schema
- `SwitchStrategyTool` – Validates and executes strategy switches with the same 5‑layer guardrails used for manual trading

**Decision Flow**
1. Fetch last 100 candles (BTC‑USDT, 15m)
2. Compute trend strength, volatility, ATR expansion ratio, breakout success rate
3. Format indicators into a prompt and send to LLM via OpenRouter
4. LLM may call `switchStrategy` tool if it believes market regime has changed
5. Tool validates position, cooldown, and equity before applying the switch

**Configuration**
```yaml
trading:
  agent:
    enabled: false
    api-key: "sk-or-..."
    model: "anthropic/claude-sonnet-4-5"
    temperature: 0.3
    max-tokens: 2048
```

## Strategies

| Strategy | Type | Market | Status |
|----------|------|--------|--------|
| Aggressive | Breakout + Trailing Stop | Trending | ✅ Production Ready |
| Mean Reversion | Range-bound Trading | Sideways | ✅ Production Ready |

## Aggressive Strategy (Breakout + Trailing Stop)

Catches trend initiation, holds via trailing stop for larger gains.

**Entry Logic**
```
Range: Last 40 bars (excluding current)
Filter: Range width > 3%

Long Entry:
- Close > rangeHigh
- Momentum > 0.3×ATR
- Body size > 0.8×ATR
→ Enter immediately

Short Entry: Symmetric
```

**Trailing Stop**
```
Long:
- Initial: trailingStop = stopLoss
- Update (every 1min): trailingStop = max(trailingStop, price - ATR×2.5)
- Exit: price <= trailingStop

Short: Symmetric

Key: Stop only moves in favorable direction
```

**Position Sizing**
- Risk per trade: 3% of account
- Max position: 30%
- Cooldown: 15min global, 1hr per direction after 2 consecutive losses

## Mean Reversion Strategy

Box range trading: buy low near support, sell high near resistance.

**Range Detection (BoxRangeDetector)**
- Data: Last 7 days of 4H candles (42 bars)
- Algorithm: Density clustering with dynamic tolerance (ATR-based)
- Validation: ≥3 touches per boundary, width 1000-12000 USD
- Update: Daily at 00:00 (skipped if position open)

**Entry Logic**
```
Long Entry (near support):
- Price < rangeLow + 15% of box width
- Price > rangeLow (not broken)
→ Enter long, target midline

Short Entry (near resistance):
- Price > rangeHigh - 15% of box width
- Price < rangeHigh (not broken)
→ Enter short, target midline
```

**Stop Loss & Take Profit**
```
Long:
- SL = rangeLow - 1×ATR
- TP = (rangeHigh + rangeLow) / 2

Short:
- SL = rangeHigh + 1×ATR
- TP = (rangeHigh + rangeLow) / 2
```

**Position Sizing**
- Risk per trade: 2% of account
- Max position: 30%
- Min position: 5% (too small positions are rejected)
- Cooldown: 15min global, 1hr per direction after 2 consecutive losses

**Backtesting**
```java
// Use MockBoxRangeDetector for backtesting with manual box range
MockBoxRangeDetector mockBox = new MockBoxRangeDetector(85000, 80000);
MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();
BacktestResult result = engine.run(csvPath, strategy, 200.0, 20);
```

## Remote Control

REST API with token auth for mobile control (iPhone Shortcuts).

**Endpoints**
```
POST /api/control/start          # Start strategy
POST /api/control/stop           # Stop strategy
GET  /api/control/status         # Query status
POST /api/control/emergency-close # Emergency close

Header: X-Auth-Token: <your-token>
Port: 10086
```

**iPhone Shortcuts Setup**
1. Create new shortcut
2. Add "Get Contents of URL"
3. Configure:
   - URL: `http://your-server:10086/api/control/start`
   - Method: POST
   - Headers: `X-Auth-Token: <your-token>`
4. Add to home screen

## Tech Stack

- Java 17 + Spring Boot 3
- OKX REST API (paper/live trading)
- Gmail SMTP for notifications
- Scheduled tasks:
  - Strategy analysis: Every 15min (aligned to candle close)
  - Trailing stop monitor: Every 1min (Aggressive strategy)
  - Range detection: Daily at 00:00 (skipped if position open)
  - Monthly backtest: 1st of month at 02:00

## Configuration

Edit `trading-signal/src/main/resources/application.yml`:

```yaml
server:
  port: 10086

trading:
  strategy: aggressive  # or mean-reversion

  trade-api:
    enabled: false      # true to enable auto-trading
    simulated: true     # false for live trading
    api-key: "..."
    secret-key: "..."
    passphrase: "..."
    leverage: 20

  email:
    enabled: false
    sender-email: "..."
    app-password: "..."  # Gmail app password
    receiver-email: "..."

  backtest:
    initial-capital: 200.0
    leverage: 20
    data-dir: backtest-data
```

## Run

```bash
cd trading-signal
mvn spring-boot:run
```

Backtest (Aggressive):
```bash
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

Backtest (Mean Reversion):
```java
// Modify BacktestRunner.java to use MeanReversionBacktestEngine
MockBoxRangeDetector mockBox = new MockBoxRangeDetector(85000, 80000);
MeanReversionStrategy strategy = new MeanReversionStrategy(mockBox);
MeanReversionBacktestEngine engine = new MeanReversionBacktestEngine();
BacktestResult result = engine.run(CSV_FILE, strategy, INITIAL_CAPITAL, LEVERAGE);
```

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/signal/run` | Trigger analysis |
| GET | `/api/strategy` | Current strategy |
| POST | `/api/backtest/run` | Run backtest |
| GET | `/api/backtest/range?start=2026-03-01&end=2026-03-31` | Range backtest |

## Risk Warnings

**Aggressive Strategy**
- Trailing stop only, no fixed TP
- 1min check interval = 1min risk window
- Test on paper trading first

**Mean Reversion**
- Fixed SL/TP, no trailing stop
- Requires valid box range (1000-12000 USD width)
- Box detection runs daily, may lag market changes
- Test on paper trading first

**General**
- Test on paper trading before going live
- Kill switch requires process restart to recover
- Keep API keys and auth token secure

## Project Structure

```
trading-signal/
├── src/main/java/com/trading/signal/
│   ├── strategy/          # Trading strategies
│   │   ├── Strategy.java                # Interface
│   │   ├── AggressiveStrategy.java      # Breakout + Trailing Stop
│   │   ├── MeanReversionStrategy.java   # Box range trading
│   │   └── StrategyRouter.java          # Config-driven strategy selector
│   ├── backtest/          # Backtesting engines
│   │   ├── AggressiveBacktestEngine.java
│   │   ├── MeanReversionBacktestEngine.java
│   │   ├── BacktestResult.java
│   │   ├── BacktestRunner.java
│   │   ├── BacktestScheduler.java
│   │   └── DataFetcher.java
│   ├── service/           # Core services
│   │   ├── SignalService.java           # Main scheduler (every 15min)
│   │   ├── TradeExecutor.java           # Order execution + kill switch
│   │   ├── BoxRangeDetector.java        # Density clustering box detection
│   │   ├── TrailingStopMonitor.java     # 1min trailing stop monitor
│   │   ├── EmailService.java            # Gmail SMTP notifications
│   │   └── SignalWriter.java            # Write signal.json
│   ├── client/            # OKX API clients
│   │   ├── OkxClient.java               # Market data (candles)
│   │   └── OkxTradeClient.java          # Orders, positions, balance
│   ├── controller/        # REST endpoints
│   │   ├── ControlController.java       # Start/stop/status/emergency
│   │   └── SignalController.java        # Manual trigger, backtest
│   ├── model/             # Data models
│   │   ├── KLine.java
│   │   ├── MarketData.java
│   │   └── TradeSignal.java
│   ├── analyzer/
│   │   └── MarketAnalyzer.java          # ATR + indicator calculation
│   └── config/
│       └── TradingProperties.java       # application.yml binding
└── src/main/resources/
    └── application.yml    # Configuration
```

## Risk Management (5 Layers)

1. **Global cooldown** — 15min between trades
2. **Direction pause** — 2 consecutive losses in same direction → pause 1hr
3. **Position validation** — Query exchange before every order (exchange as single source of truth)
4. **Equity Kill Switch** — Balance drops to 60% of initial → permanent halt (restart to recover)
5. **Trailing Stop** — Aggressive strategy only, 1min monitor, moves only in favorable direction

## AI Agent Layer (Market Regime Detection)

An LLM-driven agent that analyzes market data and decides when to switch between Aggressive and Mean Reversion strategies.

**Components**
- `MarketRegimeAgent` – Scheduled every 8 hours, pulls candles, computes indicators, calls LLM
- `MarketRegimeAnalyzer` – Technical indicator calculator (EMA slope, ATR ratio, volatility, breakout success rate)
- `OpenRouterClient` – HTTP client for OpenRouter API with OpenAI‑compatible function calling
- `ToolDefinitionBuilder` – Converts `switchStrategy` tool into OpenAI function schema
- `SwitchStrategyTool` – Validates and executes strategy switches with 5‑layer guardrails

**Decision Flow**
1. Fetch last 100 candles (BTC‑USDT, 15m)
2. Compute trend strength, volatility, ATR expansion ratio, breakout success rate
3. Format indicators into a prompt and send to LLM via OpenRouter
4. LLM may call `switchStrategy` tool if it believes market regime has changed
5. Tool validates position, cooldown, and equity before applying the switch

**Configuration**
```yaml
trading:
  agent:
    enabled: false
    api-key: "sk-or-..."
    model: "anthropic/claude-sonnet-4-5"
    temperature: 0.3
    max-tokens: 2048
```

## License

MIT
