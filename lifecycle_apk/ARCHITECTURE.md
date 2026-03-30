# AATE V4.1 - Technical Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AATE ARCHITECTURE                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │
│  │  PRICE      │    │   WALLET    │    │  COLLECTIVE │            │
│  │  FEEDS      │    │   MANAGER   │    │  LEARNING   │            │
│  │             │    │             │    │  (HIVE)     │            │
│  │ DexScreener │    │ Solana RPC  │    │  Turso DB   │            │
│  │ Birdeye     │    │ Jupiter     │    │             │            │
│  │ Pump.fun    │    │             │    │             │            │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘            │
│         │                  │                  │                    │
│         └────────────┬─────┴─────────────────┘                    │
│                      │                                             │
│                      ▼                                             │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │                    BOT SERVICE                             │    │
│  │  ┌─────────────────────────────────────────────────────┐  │    │
│  │  │                   botLoop()                          │  │    │
│  │  │  - Polls every 8 seconds                            │  │    │
│  │  │  - Manages watchlist                                │  │    │
│  │  │  - Coordinates all layers                           │  │    │
│  │  │  - ~825 lines (optimized for compiler)              │  │    │
│  │  └─────────────────────────────────────────────────────┘  │    │
│  │                          │                                 │    │
│  │                          ▼                                 │    │
│  │  ┌─────────────────────────────────────────────────────┐  │    │
│  │  │              processTokenCycle()                     │  │    │
│  │  │  - Processes single token                           │  │    │
│  │  │  - Runs 25 AI evaluations                           │  │    │
│  │  │  - Makes trade decisions                            │  │    │
│  │  │  - ~1960 lines (non-suspend, regular function)      │  │    │
│  │  └─────────────────────────────────────────────────────┘  │    │
│  └───────────────────────────────────────────────────────────┘    │
│                      │                                             │
│                      ▼                                             │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │                 TRADING LAYERS                             │    │
│  │                                                            │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │    │
│  │  │ShitCoin  │ │ShitCoin  │ │V3 Quality│ │Blue Chip │     │    │
│  │  │Trader    │ │Express   │ │Layer     │ │Trader    │     │    │
│  │  │<$30K     │ │<$30K     │ │$30K-$1M  │ │>$1M      │     │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │    │
│  │                                                            │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                  │    │
│  │  │DipHunter │ │Treasury  │ │Solana    │                  │    │
│  │  │AI        │ │Mode      │ │Arb AI    │                  │    │
│  │  └──────────┘ └──────────┘ └──────────┘                  │    │
│  └───────────────────────────────────────────────────────────┘    │
│                      │                                             │
│                      ▼                                             │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │                    EXECUTOR                                │    │
│  │  - Executes buy/sell orders                               │    │
│  │  - Paper trading support                                   │    │
│  │  - Real trading via Jupiter/Raydium                       │    │
│  └───────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## File Structure

```
lifecycle_apk/
├── app/src/main/kotlin/com/lifecyclebot/
│   │
│   ├── engine/                          # Core bot logic
│   │   ├── BotService.kt               # Main service (4600 lines)
│   │   │   ├── botLoop()               # 825 lines - main loop
│   │   │   ├── processTokenCycle()     # 1960 lines - token processing
│   │   │   ├── initTradingModes()      # Layer initialization
│   │   │   └── tryFallbackPriceData()  # Birdeye/pump.fun fallback
│   │   │
│   │   ├── Executor.kt                 # Trade execution
│   │   ├── ReentryRecoveryMode.kt      # Smart re-entry logic
│   │   ├── FinalDecisionGate.kt        # Last-chance veto
│   │   ├── DistributionFadeAvoider.kt  # Whale dump detection
│   │   ├── ExitIntelligence.kt         # Exit timing
│   │   └── BehaviorLearning.kt         # Tilt/discipline tracking
│   │
│   ├── v3/                              # V3 Engine
│   │   ├── V3EngineManager.kt          # Central coordinator
│   │   └── scoring/                     # AI Scoring Layers
│   │       ├── FluidLearningAI.kt      # Adaptive thresholds
│   │       ├── ShitCoinTraderAI.kt     # <$30K mcap trading
│   │       ├── ShitCoinExpress.kt      # Momentum plays
│   │       ├── BlueChipTraderAI.kt     # >$1M mcap trading
│   │       ├── DipHunterAI.kt          # Dip buying
│   │       ├── CashGenerationAI.kt     # Treasury mode
│   │       ├── SolanaArbAI.kt          # Arbitrage
│   │       ├── LayerTransitionManager.kt # Token graduation
│   │       └── AdvancedExitManager.kt  # Exit coordination
│   │
│   ├── collective/                      # Hive Mind
│   │   ├── CollectiveLearning.kt       # Shared intelligence
│   │   └── TursoClient.kt              # Remote database
│   │
│   ├── data/                            # Data models
│   │   ├── Models.kt                   # Core data classes
│   │   └── BotConfig.kt                # Configuration
│   │
│   ├── network/                         # API clients
│   │   ├── DexscreenerApi.kt           # Price data
│   │   ├── BirdeyeApi.kt               # Fallback prices
│   │   └── SolanaWallet.kt             # Wallet operations
│   │
│   └── ui/                              # Android UI
│       └── MainActivity.kt              # Main dashboard
│
├── app/src/main/res/layout/
│   └── activity_main.xml               # UI layout (2500+ lines)
│
└── FEATURES.md                          # This documentation
```

---

## Key Design Decisions

### 1. Why Extract processTokenCycle()?

**Problem:** Kotlin compiler crashed with StackOverflowError when compiling `botLoop()` because it was 2600+ lines with a massive suspend lambda.

**Solution:** Extracted token processing into a separate non-suspend function:
- `botLoop()`: Now 825 lines (small suspend function)
- `processTokenCycle()`: 1960 lines (regular function, not suspend)

The compiler's coroutine transformer only processes suspend functions, so making `processTokenCycle()` non-suspend bypasses the issue.

### 2. Why Multiple Trading Layers?

Different market cap zones require different strategies:
- **Micro-caps (<$30K):** High volatility, need quick entries/exits, high risk/reward
- **Low-caps ($30K-$1M):** More established, can hold longer, moderate risk
- **Blue chips (>$1M):** Deep liquidity, tighter targets, lower risk

### 3. Why Layer Transition System?

Tokens can pump from $5K to $5M. Instead of closing positions and re-entering:
- Position is handed off between layers
- Continuous P&L tracking
- No slippage from closing/reopening
- Targets adjust automatically for new market cap zone

### 4. Why Fluid Learning?

Fixed thresholds don't work because:
- New bot has no history → needs strict limits
- Experienced bot has data → can trust its patterns

FluidLearningAI starts at "bootstrap" (strict) and loosens as it accumulates successful trades.

---

## Data Flow

```
1. PRICE FETCH
   DexScreener → getBestPair(mint)
   └── Fallback: Birdeye → pump.fun API

2. TOKEN STATE UPDATE
   TokenState updated with:
   - Price, mcap, liquidity
   - Volume, buys/sells
   - History candles

3. AI EVALUATION (25 layers)
   Each layer scores the setup:
   - Entry score (should we buy?)
   - Exit score (should we sell?)
   - Quality score (how good is it?)

4. DECISION GATE
   FinalDecisionGate applies:
   - Last-chance veto
   - Quality threshold check
   - Risk assessment

5. EXECUTION
   Executor handles:
   - Paper trades (simulation)
   - Real trades (Jupiter/Raydium)
   - Position tracking

6. LEARNING
   Results fed back to:
   - FluidLearningAI (threshold adjustment)
   - BehaviorLearning (pattern tracking)
   - CollectiveLearning (hive mind)
```

---

## Configuration

### Environment Variables
```
TURSO_DB_URL=libsql://your-db.turso.io
TURSO_AUTH_TOKEN=your-token
BIRDEYE_API_KEY=your-key (optional)
TELEGRAM_BOT_TOKEN=your-token (optional)
```

### Key Settings (BotConfig)
```kotlin
paperMode: Boolean = true      // Start in paper trading!
pollSeconds: Int = 8           // Price check interval
maxOpenPositions: Int = 10     // Concurrent positions
closePositionsOnStop: Boolean = true
```

---

## Build & Deploy

### GitHub Actions CI
```yaml
# Triggered on push to main
- Checkout code
- Setup JDK 17
- Setup Android SDK
- Build APK
- Upload artifact
```

### Local Development
No local compiler available - all builds via GitHub Actions.

### Testing
- Paper trading mode for risk-free testing
- Shadow trading runs alongside live
- Collective learning from all instances

---

## Performance Optimizations

1. **Parallel Token Processing**
   - Each token processed in separate coroutine
   - Reduces cycle time from N×50ms to ~50ms total

2. **Price Fallback Chain**
   - Primary: DexScreener (fastest)
   - Fallback 1: Birdeye (more coverage)
   - Fallback 2: pump.fun API (bonding curve tokens)

3. **Efficient State Management**
   - TokenState uses synchronized blocks
   - History limited to 300 candles
   - Automatic cleanup of stale tokens

---

*AATE V4.1 Technical Architecture - December 2025*
