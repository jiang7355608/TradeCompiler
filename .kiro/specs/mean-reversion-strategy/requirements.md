# Requirements Document: Mean Reversion Trading Strategy

## Introduction

This document specifies the requirements for implementing a clean, professional mean reversion trading strategy for cryptocurrency futures trading. The strategy exploits price oscillations within a defined box range by entering positions when price touches boundaries and exiting at the box midline.

The strategy follows the "看大做小" (watch big timeframe, trade small timeframe) principle: box boundaries are identified on 4-hour charts, while trade execution occurs on 15-minute charts. This approach filters market noise while capturing meaningful reversions.

**Key Design Principles:**
- Pure mean reversion logic (no trend analysis or market condition filtering)
- Simple entry rules: Touch upper boundary → Short, Touch lower boundary → Long
- Box boundaries defined by multiple touches + failed breakouts (not just highest/lowest prices)
- Probe position mechanism for risk management (10% probe → 30% add-on)
- Clean, interview-ready code structure

## Glossary

- **System**: The mean reversion trading strategy implementation (MeanReversionStrategy class)
- **BoxRangeDetector**: Service that identifies valid box boundaries using touch + bounce verification algorithm
- **Box_Range**: A price range defined by upper boundary (resistance) and lower boundary (support)
- **Upper_Boundary**: The resistance level where price has touched and bounced down multiple times
- **Lower_Boundary**: The support level where price has touched and bounced up multiple times
- **Box_Midline**: The center price of the box range, calculated as (Upper_Boundary + Lower_Boundary) / 2
- **Touch**: When price comes within a threshold distance of a boundary (e.g., within 0.5% of Upper_Boundary)
- **Probe_Position**: Initial small position (10% of target size) to test the trade hypothesis
- **Add_On_Position**: Additional position (30% of target size) added when probe position shows profit
- **ATR**: Average True Range, a volatility indicator used for stop loss calculation
- **Trailing_Stop**: A dynamic stop loss that moves with favorable price movement to lock in profits
- **SignalService**: Service that executes the strategy on a 15-minute schedule
- **TradeExecutor**: Service that places orders with the exchange
- **MarketData**: Container for market information including K-lines, current price, and technical indicators

## Requirements

### Requirement 1: Box Range Detection

**User Story:** As a trader, I want the system to automatically identify valid box ranges, so that I can trade mean reversion opportunities without manual chart analysis.

#### Acceptance Criteria

1. WHEN the strategy is activated, THE BoxRangeDetector SHALL identify box boundaries using 4-hour K-line data
2. THE BoxRangeDetector SHALL use the touch + bounce verification algorithm to validate boundaries
3. THE BoxRangeDetector SHALL require at least 3 touches per boundary with a bounce success rate ≥ 60%
4. THE BoxRangeDetector SHALL update box boundaries every 24 hours
5. IF a position exists, THEN THE BoxRangeDetector SHALL skip the box update to maintain strategy consistency
6. THE System SHALL validate that box width is between 1000 and 12000 USD
7. IF box width exceeds 12000 USD, THEN THE System SHALL reject the box as "too volatile"
8. IF box width is less than 1000 USD, THEN THE System SHALL reject the box as "insufficient trading space"
9. WHEN box boundaries change, THE System SHALL send an email notification with old and new boundaries

### Requirement 2: Entry Signal Generation

**User Story:** As a trader, I want the system to generate entry signals when price touches box boundaries, so that I can capture mean reversion opportunities.

#### Acceptance Criteria

1. WHEN price touches Upper_Boundary on 15-minute timeframe, THE System SHALL generate a SHORT signal
2. WHEN price touches Lower_Boundary on 15-minute timeframe, THE System SHALL generate a LONG signal
3. THE System SHALL define "touch" as price coming within 0.5% of the boundary
4. THE System SHALL NOT analyze market conditions or trend direction before entry
5. THE System SHALL NOT wait for confirmation candles or reversal patterns
6. THE System SHALL generate signals only when a valid Box_Range exists
7. IF no valid Box_Range exists, THEN THE System SHALL output NO_TRADE with reason "No valid box range"
8. THE System SHALL enforce a 15-minute cooldown period between trades
9. IF a position already exists, THEN THE System SHALL NOT generate new entry signals

### Requirement 3: Stop Loss Management

**User Story:** As a trader, I want automatic stop loss protection, so that I can limit losses when the box breaks down.

#### Acceptance Criteria

1. WHEN entering a LONG position, THE System SHALL set stop loss at Lower_Boundary - 1.0 × ATR
2. WHEN entering a SHORT position, THE System SHALL set stop loss at Upper_Boundary + 1.0 × ATR
3. THE System SHALL validate that stop loss distance is at least 0.2% of entry price
4. IF stop loss distance is less than 0.2%, THEN THE System SHALL reject the trade with reason "Stop loss too tight"
5. THE System SHALL send stop loss orders to TradeExecutor immediately after position entry
6. THE System SHALL NOT move stop loss further away from entry price after position is opened
7. WHEN stop loss is hit, THE System SHALL close the entire position (probe + add-on if exists)

### Requirement 4: Take Profit Management

**User Story:** As a trader, I want the system to take profit at the box midline, so that I can capture the mean reversion move.

#### Acceptance Criteria

1. WHEN entering a position, THE System SHALL calculate take profit target as Box_Midline
2. FOR ALL LONG positions, take profit SHALL be set at (Upper_Boundary + Lower_Boundary) / 2
3. FOR ALL SHORT positions, take profit SHALL be set at (Upper_Boundary + Lower_Boundary) / 2
4. THE System SHALL send take profit orders to TradeExecutor immediately after position entry
5. WHEN take profit is reached, THE System SHALL close the entire position (probe + add-on if exists)
6. THE System SHALL NOT use trailing stop for mean reversion strategy
7. THE System SHALL NOT adjust take profit target after position is opened

### Requirement 5: Position Sizing and Risk Management

**User Story:** As a trader, I want consistent position sizing based on account risk, so that I can manage my capital effectively.

#### Acceptance Criteria

1. THE System SHALL risk 2% of account balance per trade
2. THE System SHALL calculate position size using formula: position = (riskAmount / stopLossDistance) / entryPrice
3. THE System SHALL apply 20x leverage to calculate margin requirement
4. THE System SHALL enforce maximum margin usage of 30% of account balance
5. IF calculated margin exceeds 30%, THEN THE System SHALL reduce position size to meet the limit
6. THE System SHALL validate that actual risk does not exceed 2.2% of account balance (10% tolerance)
7. IF actual risk exceeds 2.2%, THEN THE System SHALL reject the trade with reason "Risk exceeds limit"
8. THE System SHALL retrieve account balance from the exchange API before each trade
9. WHERE backtest mode is enabled, THE System SHALL use configured initial capital as account balance

### Requirement 6: Probe Position Mechanism

**User Story:** As a trader, I want to start with a small probe position and add on when profitable, so that I can reduce risk on false signals.

#### Acceptance Criteria

1. WHEN entering a new trade, THE System SHALL open a probe position at 10% of calculated position size
2. THE System SHALL monitor probe position for add-on conditions every 15 minutes
3. WHEN probe position shows unrealized profit ≥ 0.3% AND position has been open for ≥ 30 minutes, THE System SHALL add 30% of calculated position size
4. THE System SHALL add the additional position only once per trade
5. THE System SHALL use the same entry price for add-on position as the probe position
6. THE System SHALL apply the same stop loss and take profit to the add-on position
7. IF probe position is stopped out before add-on conditions are met, THEN THE System SHALL NOT open add-on position
8. THE System SHALL track probe and add-on positions separately for accounting purposes
9. WHEN closing position, THE System SHALL close both probe and add-on positions simultaneously

### Requirement 7: Box Range Validation

**User Story:** As a trader, I want the system to validate box quality before trading, so that I only trade in well-defined ranges.

#### Acceptance Criteria

1. THE System SHALL verify that Box_Range has been confirmed by BoxRangeDetector before generating signals
2. THE System SHALL check that box width is between 1000 and 12000 USD
3. THE System SHALL verify that Upper_Boundary > Lower_Boundary
4. IF Box_Range is invalid, THEN THE System SHALL output NO_TRADE with reason "Invalid box range"
5. THE System SHALL recalculate Box_Midline whenever box boundaries are updated
6. THE System SHALL validate that current price is within the box range (Lower_Boundary ≤ price ≤ Upper_Boundary)
7. IF price is outside the box range, THEN THE System SHALL output NO_TRADE with reason "Price outside box range"

### Requirement 8: Integration with Existing Infrastructure

**User Story:** As a developer, I want the mean reversion strategy to integrate seamlessly with existing services, so that I can reuse proven components.

#### Acceptance Criteria

1. THE System SHALL implement the Strategy interface with methods generateSignal(MarketData) and getName()
2. THE System SHALL return strategy name "mean-reversion" from getName() method
3. THE System SHALL register as a Spring Bean with @Component annotation
4. THE System SHALL inject BoxRangeDetector, TradingProperties, and other required services via constructor
5. THE System SHALL use SignalService for 15-minute scheduled execution
6. THE System SHALL use TradeExecutor for order placement and position management
7. THE System SHALL use EmailService for trade notifications
8. THE System SHALL return TradeSignal objects with action, reason, confidence, positionSize, stopLoss, and takeProfit
9. THE System SHALL set confidence to 0.75 for all valid signals (consistent with AggressiveStrategy)

### Requirement 9: Configuration Management

**User Story:** As a trader, I want to configure strategy parameters without code changes, so that I can tune the strategy for different market conditions.

#### Acceptance Criteria

1. THE System SHALL read all parameters from TradingProperties.StrategyParams
2. THE System SHALL support configuration of touch threshold (default 0.5%)
3. THE System SHALL support configuration of stop loss ATR multiplier (default 1.0)
4. THE System SHALL support configuration of probe position percentage (default 10%)
5. THE System SHALL support configuration of add-on position percentage (default 30%)
6. THE System SHALL support configuration of add-on profit threshold (default 0.3%)
7. THE System SHALL support configuration of add-on time threshold (default 30 minutes)
8. THE System SHALL support configuration of cooldown period (default 15 minutes)
9. THE System SHALL support configuration of risk per trade (default 2%)
10. THE System SHALL support configuration of maximum margin usage (default 30%)
11. WHERE manual box configuration is provided (mrRangeHigh > 0 and mrRangeLow > 0), THE System SHALL use manual values instead of BoxRangeDetector

### Requirement 10: Trade Execution and Monitoring

**User Story:** As a trader, I want the system to execute trades automatically and monitor positions, so that I don't need to watch the market constantly.

#### Acceptance Criteria

1. WHEN a valid signal is generated, THE System SHALL send the signal to TradeExecutor
2. THE TradeExecutor SHALL place market orders for immediate execution
3. THE TradeExecutor SHALL place stop loss orders as stop-market orders
4. THE TradeExecutor SHALL place take profit orders as limit orders
5. THE System SHALL monitor open positions every 15 minutes for add-on conditions
6. THE System SHALL monitor open positions for stop loss and take profit hits
7. WHEN a position is closed, THE System SHALL record the trade result (win/loss, profit/loss, duration)
8. THE System SHALL send email notifications for: position opened, add-on executed, position closed
9. THE System SHALL include trade details in notifications: entry price, stop loss, take profit, position size, reason

### Requirement 11: Error Handling and Edge Cases

**User Story:** As a developer, I want robust error handling, so that the system degrades gracefully when issues occur.

#### Acceptance Criteria

1. IF BoxRangeDetector fails to identify a valid box, THEN THE System SHALL output NO_TRADE and log the reason
2. IF account balance cannot be retrieved, THEN THE System SHALL use the last known balance and log a warning
3. IF ATR is zero or negative, THEN THE System SHALL output NO_TRADE with reason "Invalid ATR"
4. IF K-line data is insufficient (< 20 bars), THEN THE System SHALL output NO_TRADE with reason "Insufficient data"
5. IF order placement fails, THEN THE System SHALL log the error and send an email alert
6. IF stop loss calculation results in negative distance, THEN THE System SHALL reject the trade with reason "Invalid stop loss"
7. IF take profit calculation results in invalid price, THEN THE System SHALL reject the trade with reason "Invalid take profit"
8. THE System SHALL handle network errors gracefully and retry failed API calls up to 3 times
9. THE System SHALL log all errors with sufficient context for debugging

### Requirement 12: Backtesting Support

**User Story:** As a trader, I want to backtest the mean reversion strategy on historical data, so that I can validate its performance before live trading.

#### Acceptance Criteria

1. THE System SHALL support backtesting through a dedicated MeanReversionBacktestEngine
2. THE MeanReversionBacktestEngine SHALL use the same MeanReversionStrategy class as live trading
3. WHERE backtest mode is enabled, THE System SHALL use manually configured box boundaries (mrRangeHigh, mrRangeLow)
4. THE MeanReversionBacktestEngine SHALL simulate probe position and add-on logic
5. THE MeanReversionBacktestEngine SHALL track unrealized profit for add-on condition checking
6. THE MeanReversionBacktestEngine SHALL simulate stop loss and take profit execution
7. THE MeanReversionBacktestEngine SHALL calculate performance metrics: total return, win rate, profit factor, max drawdown
8. THE MeanReversionBacktestEngine SHALL generate a detailed backtest report with trade-by-trade breakdown
9. THE MeanReversionBacktestEngine SHALL support multiple backtest runs with different parameter sets for optimization

### Requirement 13: State Management

**User Story:** As a developer, I want clear state management, so that the strategy behavior is predictable and testable.

#### Acceptance Criteria

1. THE System SHALL maintain state for: current position (none/probe/confirmed), entry price, entry time, stop loss, take profit
2. THE System SHALL reset state to "no position" when a position is fully closed
3. THE System SHALL persist state across application restarts using a state file
4. THE System SHALL validate state consistency on startup
5. IF state file is corrupted, THEN THE System SHALL reset to "no position" and log a warning
6. THE System SHALL provide a method hasPosition() that returns true if any position exists
7. THE System SHALL provide a method getCurrentPosition() that returns position details
8. THE System SHALL provide a method reset() that clears all state (for testing and manual intervention)

### Requirement 14: Logging and Observability

**User Story:** As a developer, I want comprehensive logging, so that I can debug issues and understand strategy behavior.

#### Acceptance Criteria

1. THE System SHALL log all signal generation decisions with reason
2. THE System SHALL log box range updates with old and new boundaries
3. THE System SHALL log position entries with: direction, entry price, stop loss, take profit, position size
4. THE System SHALL log add-on executions with: add-on price, additional size, total position size
5. THE System SHALL log position exits with: exit price, profit/loss, duration, exit reason
6. THE System SHALL log all NO_TRADE decisions with specific reasons
7. THE System SHALL log all configuration parameter values on startup
8. THE System SHALL use structured logging with consistent format for easy parsing
9. THE System SHALL log at appropriate levels: DEBUG for detailed flow, INFO for important events, WARN for recoverable issues, ERROR for failures

### Requirement 15: Code Quality and Maintainability

**User Story:** As a developer, I want clean, well-documented code, so that the strategy is easy to understand and maintain.

#### Acceptance Criteria

1. THE System SHALL follow Java naming conventions and code style consistent with AggressiveStrategy
2. THE System SHALL include comprehensive Javadoc comments for all public methods
3. THE System SHALL include inline comments explaining complex logic (e.g., position sizing calculations)
4. THE System SHALL organize code into logical sections with clear separators
5. THE System SHALL avoid code duplication by extracting common logic into private methods
6. THE System SHALL use meaningful variable names that reflect business concepts
7. THE System SHALL limit method length to 50 lines where possible
8. THE System SHALL limit class length to 500 lines where possible
9. THE System SHALL include unit tests for core logic (signal generation, position sizing, stop loss calculation)
10. THE System SHALL achieve at least 80% code coverage in unit tests
