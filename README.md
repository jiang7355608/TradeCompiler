# BTC Trading Signal Engine

Automated trading system for BTC-USDT perpetual futures on OKX. Three strategies, live execution, trailing stops, remote control via iPhone.

## Features

- Auto-execution via OKX API (paper/live trading)
- Dynamic range detection using density clustering + multi-touch validation
- Atomic stop-loss: order + SL/TP in single request
- Confidence-based position sizing
- Trailing stop for trend-following (Aggressive strategy)
- Multi-layer circuit breakers (3-loss cooldown, 40% drawdown kill switch)
- Email notifications + remote control via REST API
- Monthly backtesting with automated reports

## Strategies

| Strategy | Type | Market | Command |
|----------|------|--------|---------|
| Aggressive | Breakout + Trailing Stop | Trending | `POST /api/strategy/aggressive` |
| Conservative | Breakout | Post-consolidation | `POST /api/strategy/conservative` |
| Mean Reversion | Range-bound | Sideways | `POST /api/strategy/mean-reversion` |

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

Two-phase execution: probe position → add position.

**Range Detection (BoxRangeDetector v3.0)**
- Data: Last 7 days of 4H candles
- Algorithm: Density clustering with dynamic tolerance (ATR-based)
- Validation: ≥3 touches per boundary, width 1000-12000 USD
- Update: Daily at 00:00 (skipped if position open)

**Probe Phase (IDLE → PROBE)**
```
Entry:
- Price near boundary (within 10% of range width)
- Bullish/bearish candle confirmation
- Confidence: 0.50-0.80 (distance + body size + acceleration)
- Position: 10% × confidence
- No exchange SL (strategy monitors every 1min)
- Timeout: 3 hours
```

**Add Phase (PROBE → CONFIRMED)**
```
Trigger:
- Profit > 15% of range width
- Hold time ≥ 45min

Execution:
- Confidence: 0.70-0.95 (profit excess + hold time)
- Position: 30% × confidence
- Atomic SL/TP for entire position (attachAlgoOrds)
```

**Risk Controls**
- Global cooldown: 45min
- 3-loss circuit breaker: 4hr pause
- Account kill switch: 40% drawdown → permanent stop
- Position validation: Query exchange before every order
- Timeout failsafe: 3hr probe, 6hr confirmed

## Remote Control

REST API with token auth for mobile control (iPhone Shortcuts).

**Endpoints**
```
POST /api/control/start          # Start strategy
POST /api/control/stop           # Stop strategy
GET  /api/control/status         # Query status
POST /api/control/emergency-close # Emergency close

Header: X-Auth-Token: jiangyuxuanGGbond339@!
Port: 10086
```

**iPhone Shortcuts Setup**
1. Create new shortcut
2. Add "Get Contents of URL"
3. Configure:
   - URL: `http://your-server:10086/api/control/start`
   - Method: POST
   - Headers: `X-Auth-Token: jiangyuxuanGGbond339@!`
4. Add to home screen

## Tech Stack

- Java 17 + Spring Boot 3
- OKX REST API (paper/live trading)
- Gmail SMTP for notifications
- Scheduled tasks:
  - Strategy analysis: Every 15min (aligned to candle close)
  - Stop-loss monitor: Every 1min (probe positions)
  - Trailing stop monitor: Every 1min (Aggressive strategy)
  - Range detection: Daily at 00:00 (skipped if position open)
  - Monthly backtest: 1st of month at 02:00

## Configuration

Edit `trading-signal/src/main/resources/application.yml`:

```yaml
server:
  port: 10086

trade-api:
  enabled: true
  simulated: true  # false for live trading
  api-key: "..."
  secret-key: "..."
  passphrase: "..."
  leverage: 20

email:
  enabled: true
  sender-email: "..."
  app-password: "..."  # Gmail app password
  receiver-email: "..."

params:
  box-max-width: 12000
  box-min-width: 1000
  mr-entry-buffer-pct: 0.10
```

## Run

```bash
cd trading-signal
mvn spring-boot:run
```

Backtest:
```bash
mvn compile exec:java -Dexec.mainClass="com.trading.signal.backtest.BacktestRunner"
```

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/signal/run` | Trigger analysis |
| GET | `/api/strategy` | Current strategy |
| POST | `/api/strategy/{name}` | Switch strategy |
| POST | `/api/backtest/run` | Run backtest |
| GET | `/api/optimize?top=5` | Parameter optimization |

## Risk Warnings

**Aggressive Strategy**
- Trailing stop only, no fixed TP
- 1min check interval = 1min risk window
- Test on paper trading first

**Mean Reversion**
- Probe phase has no exchange SL (strategy monitors every 1min)
- If process crashes, probe position is unprotected
- Add phase has exchange SL/TP (safe)

**General**
- Test on paper trading before going live
- Kill switch requires process restart to recover
- Keep API keys and auth token secure

## Project Structure

```
trading-signal/
├── src/main/java/com/trading/signal/
│   ├── strategy/          # Trading strategies
│   ├── backtest/          # Backtesting engine
│   ├── service/           # Core services
│   ├── client/            # OKX API clients
│   ├── controller/        # REST endpoints
│   └── model/             # Data models
└── src/main/resources/
    └── application.yml    # Configuration
```

## License

MIT
