# AATE - Autonomous Adaptive Trading Engine

## Technical Description

**Version**: 1.0.0-beta  
**Platform**: Native Android (Kotlin)  
**Target**: Solana Meme Coin Trading  
**Architecture**: 10-Layer AI Decision Engine with Self-Adjusting Feedback Loops

---

## Executive Summary

AATE (Autonomous Adaptive Trading Engine) is a sophisticated, native Android trading bot designed specifically for Solana meme coin markets. Unlike web-based or Python bots, AATE runs natively on Android devices, providing 24/7 autonomous operation with minimal battery impact. The bot features a multi-layered AI decision system, institutional-grade risk management, and a self-improving learning architecture that adapts to market conditions in real-time.

---

## Core Architecture

### Technology Stack

| Component | Technology |
|-----------|------------|
| **Language** | Kotlin 1.9+ |
| **Platform** | Android SDK 34 (min SDK 26) |
| **Concurrency** | Kotlin Coroutines |
| **Networking** | OkHttp 4.12 with DNS-over-HTTPS |
| **Serialization** | Kotlinx Serialization |
| **Charts** | MPAndroidChart |
| **Cryptography** | TweetNaCl (Ed25519), EncryptedSharedPreferences |
| **Image Loading** | Coil 2.6.0 |

### Codebase Statistics

- **Total Lines of Code**: ~41,000+ lines in engine alone
- **Core Engine Files**: 60+ Kotlin files
- **AI Layers**: 10+ specialized modules
- **API Integrations**: 8 external services

### Package Structure

```
com.lifecyclebot/
├── AATEApp.kt          # Application entry point
├── data/                        # Data models & configuration
│   ├── Models.kt               # Core data structures
│   ├── BotConfig.kt            # Configuration management
│   └── ConfigStore.kt          # Encrypted storage
├── engine/                      # Trading engine (41K+ lines)
│   ├── BotService.kt           # Main bot loop (2,300+ lines)
│   ├── Executor.kt             # Trade execution (4,100+ lines)
│   ├── FinalDecisionGate.kt    # Multi-gate approval system (2,100+ lines)
│   ├── BotBrain.kt             # Adaptive learning core
│   ├── Strategy.kt             # Entry/exit signal generation
│   └── [50+ AI/utility modules]
├── network/                     # Blockchain & API integrations
│   ├── SolanaWallet.kt         # Wallet management & signing
│   ├── JupiterApi.kt           # DEX aggregator integration
│   └── DexscreenerApi.kt       # Market data
└── ui/                          # Android UI components
    ├── MainActivity.kt
    └── WalletActivity.kt
```

---

## Trading Pipeline

### State Machine Flow

```
DISCOVERED → ELIGIBLE → WATCHLISTED → CANDIDATE → PROPOSED → FDG_APPROVED → SIZED → EXECUTED → MONITORING → CLOSED → CLASSIFIED
     ↓           ↓           ↓            ↓           ↓
  REJECTED   INELIGIBLE   FILTERED   NO_SIGNAL   FDG_BLOCKED
```

### Entry Flow (Buy Path)

1. **Scanner Discovery**: Multi-source scanner polls DexScreener, Pump.fun, Raydium for new tokens
2. **Eligibility Filter**: Minimum liquidity ($500 paper / $3K live), score threshold, blacklist check
3. **Watchlist Admission**: Capacity check, token added to active monitoring
4. **Strategy Evaluation**: 5-candle momentum analysis, EMA fan alignment, volume scoring
5. **Candidate Generation**: BUY signal with quality grade (A+, A, B, C)
6. **Dedupe Check**: Prevents proposal spam (60s cooldown, max 3/5min)
7. **Final Decision Gate**: 10-gate approval system (see below)
8. **Sizing**: SmartSizer calculates position based on treasury tier, confidence, quality
9. **Execution**: Paper simulation or live swap via Jupiter Ultra API

### Exit Flow (Sell Path)

1. **V8 Quick Exit**: Immediate stop-loss check (< 100ms)
2. **Risk Check**: Trailing stops, distribution detection, whale selling
3. **Partial Sell**: Lock profits at milestones (2x, 5x, 10x)
4. **Signal Exit**: Strategy-generated SELL signal
5. **Mode Exit**: Auto-mode max hold time enforcement
6. **Execution**: On-chain balance verification, 3-retry quote loop, MEV protection

---

## Final Decision Gate (FDG)

The FDG is a 10-gate approval system that every trade must pass:

| Gate | Name | Function |
|------|------|----------|
| **0** | ReentryGuard | Hard block for collapsed/rugged tokens |
| **0.5** | Proposal Dedupe | Prevents spam (moved to early check) |
| **1** | Hard Blocks | Zero liquidity, critical rugcheck, position limits |
| **1h** | Distribution Block | Whale selling detection |
| **1.5** | Early Snipe Mode | Fast-track for ultra-early tokens |
| **1i** | Sticky Edge Veto | Blocks unknown edge phases |
| **1j** | Quality Filter | Paper mode learning filter |
| **2** | Edge Veto | Phase-based confidence requirements |
| **2b** | Narrative/Scam AI | Groq LLM scam detection |
| **2c** | Gemini Copilot | Advanced AI analysis |
| **2d** | Orthogonal Signals | Multi-signal aggregation |
| **3** | Adaptive Confidence | Learning-phase adjusted thresholds |
| **4** | Mode Rules | Auto-mode specific constraints |
| **4.5** | Liquidity Depth AI | Slippage and depth analysis |
| **4.75** | EV Validation | Expected value calculation |
| **5** | Sizing Validation | Final size sanity checks |

---

## AI Layers

### 1. BotBrain (Adaptive Learning Core)
- **14 metrics** tracked per trade (entry score, phase, quality, exit reason, etc.)
- **Rolling memory** with decay buckets (7 time-based segments)
- **Quality-weighted learning**: LIVE (3x), BENCHMARK (1x), EXPLORATION (0.3x)
- **Threshold auto-tuning** based on accumulated trade data

### 2. EntryIntelligence
- **1,967+ trades** in persistent storage
- Scores entry opportunities (0-100)
- Risk classification (LOW, MEDIUM, HIGH)
- Hour-of-day performance patterns

### 3. ExitIntelligence
- **1,903+ exits** analyzed
- Optimal exit timing suggestions
- Hold duration vs. profitability correlation

### 4. EdgeLearning
- Phase-based win rate tracking
- EMA fan alignment performance
- Source reliability scoring (Pump.fun, Raydium, etc.)

### 5. WhaleTrackerAI
- Large holder monitoring
- Distribution pattern detection
- Accumulation vs. selling classification

### 6. LiquidityDepthAI
- Slippage prediction
- Depth analysis at various price levels
- Liquidity collapse early warning

### 7. NarrativeDetectorAI (Groq LLM)
- Scam pattern recognition
- Narrative classification (MEME, UTILITY, CELEBRITY, etc.)
- Red flag / green flag identification

### 8. GeminiCopilot (Gemini 2.0 Flash)
- Deep narrative analysis
- Trade reasoning explanations
- Smart exit recommendations
- Multi-factor risk scoring

### 9. TimeOptimizationAI
- Best trading hours identification
- Session-based performance (Asia, Europe, US)
- Volume pattern correlation

### 10. TokenWinMemory
- **17 winning token patterns** tracked
- Symbol/mint success correlation
- Source-quality mapping

---

## Self-Adjusting Feedback System

### Closed-Loop Confidence Governor

The bot's confidence thresholds automatically adjust based on lifetime performance:

```kotlin
// Wallet win rate influences thresholds
val winRateInfluence = (walletWinRate - 50.0) / 100.0  // -50% to +50%
val dampedInfluence = EMA(winRateInfluence, α=0.15)    // Smooth changes
val cappedInfluence = clamp(dampedInfluence, -15%, +15%)  // Limit swing
finalThreshold = baseThreshold * (1 + cappedInfluence)
```

### Learning Phases

| Phase | Trades | Behavior |
|-------|--------|----------|
| **BOOTSTRAP** | 0-50 | Loose thresholds, maximum learning |
| **LEARNING** | 51-500 | Gradual tightening, pattern refinement |
| **MATURE** | 500+ | Stable thresholds, exploitation mode |

---

## Risk Management

### Capital Protection

1. **Treasury Locking**: Realized profits locked to separate treasury
2. **Dynamic Profit Lock**: 
   - Recover capital at ~2x
   - Lock 50% at ~5x
   - Let remainder ride with loose stops
3. **Position Limits**: Max 5 open positions (configurable)
4. **Exposure Caps**: Max 70% portfolio exposure
5. **Per-Trade Limits**: Max 20% per position

### Circuit Breaker System

- **Halt Triggers**: 3 consecutive losses, daily loss limit, critical errors
- **Pause Mode**: Blocks new entries, monitors existing positions
- **Position Health Monitor**: Every 5 minutes reconciles on-chain balances

### ReentryGuard

Hard blocks tokens that have triggered:
- Liquidity collapse (>50% drop)
- Distribution exit (whale selling)
- Stop-loss exit
- Bad memory pattern

---

## Position Sizing (SmartSizer)

### Treasury Tiers

| Tier | Treasury Value | Base Size | Max Size |
|------|----------------|-----------|----------|
| MICRO | <$100 | 0.02 SOL | 0.1 SOL |
| SMALL | $100-500 | 0.05 SOL | 0.25 SOL |
| MEDIUM | $500-2K | 0.1 SOL | 0.5 SOL |
| LARGE | $2K-10K | 0.2 SOL | 1.0 SOL |
| WHALE | $10K-50K | 0.5 SOL | 2.5 SOL |
| INSTITUTIONAL | $50K+ | 1.0 SOL | 5.0 SOL |

### Sizing Multipliers

```
finalSize = baseSize 
  × scoreMultiplier (0.5-1.5)
  × brainMultiplier (0.5-1.5)
  × memoryMultiplier (0.8-1.2)
  × liquidityMultiplier (0.5-1.0)
  × confidenceMultiplier (0.7-1.3)
  × treasuryMultiplier (0.5-1.5)
  × houseMoneyMultiplier (1.0-2.0)
```

---

## External Integrations

### Trading & Data

| Service | Purpose |
|---------|---------|
| **Jupiter Ultra API** | DEX aggregation, swap execution, MEV protection |
| **Helius RPC** | Solana RPC, transaction confirmation |
| **DexScreener API** | Token profiles, price data, logos |
| **Pump.fun API** | New token discovery, bonding curves |
| **RugCheck.xyz** | Token safety scoring |
| **Birdeye** | Additional market data |
| **Jito** | MEV protection bundles |

### AI Services

| Service | Model | Purpose |
|---------|-------|---------|
| **Groq** | Mixtral/Llama | Narrative scam detection |
| **Google Gemini** | Gemini 2.0 Flash | Deep analysis, copilot |

---

## Security Architecture

### Key Management
- **EncryptedSharedPreferences**: AES-256 encryption for API keys
- **No Hardcoded Keys**: All secrets loaded from secure storage
- **Keypair Integrity**: Verification before every transaction

### Transaction Security
- **Slippage Guards**: Max slippage enforcement
- **Quote Validation**: Price impact limits
- **Sign Delay**: 200ms minimum between signatures
- **MEV Protection**: Jito bundles or Jupiter Ultra Beam

### Position Protection
- **On-Chain Verification**: Balance check before sells
- **3-Retry Loop**: Quote failures with exponential backoff
- **Startup Reconciliation**: Orphaned token detection on restart

---

## Performance Metrics (Quant Analytics)

### Tracked Metrics

- **Sharpe Ratio**: Risk-adjusted returns
- **Sortino Ratio**: Downside deviation focus
- **Max Drawdown**: Peak-to-trough loss
- **Win Rate**: % profitable trades
- **Profit Factor**: Gross profit / Gross loss
- **Average Hold Time**: Per trade duration
- **VaR (Value at Risk)**: 95% confidence loss estimate

---

## Paper Trading Mode

### Realistic Simulation
- **Slippage Simulation**: 0.4%-4% based on liquidity
- **Fee Simulation**: 0.5% transaction fee
- **Fluid Learning**: Simulated balance tracking
- **Shadow Trading**: Parallel paper trades during live mode

### Learning Acceleration
- **Max 100 watchlist tokens** (vs 50 live)
- **Lower thresholds** for more trade data
- **Blacklist bypass** for pattern learning

---

## Deployment

### Build Requirements
- Android Studio Hedgehog+
- JDK 17
- Gradle 8.x
- GitHub Actions CI/CD

### APK Generation
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Permissions Required
- INTERNET
- FOREGROUND_SERVICE
- POST_NOTIFICATIONS
- VIBRATE
- WAKE_LOCK

---

## Future Roadmap

### Planned Features
- [ ] Social sentiment integration (Twitter/X, Telegram)
- [ ] Supabase collective learning (cross-device intelligence)
- [ ] Backtesting UI in web dashboard
- [ ] Volume profile analysis
- [ ] Multi-wallet management

### Architecture Refactoring
- [ ] Modularize BotService.kt (2,300+ lines)
- [ ] Modularize Executor.kt (4,100+ lines)
- [ ] Extract AI layers to separate package

---

## Summary

AATE represents a new paradigm in mobile trading bots: a fully autonomous, self-learning trading engine that runs natively on Android. With 10+ AI layers, institutional-grade risk management, and a closed-loop feedback system, AATE continuously improves its performance based on real trading outcomes. The architecture prioritizes capital protection while maximizing exposure to high-quality moonshot opportunities.

**Key Differentiators:**
- Native Android (not web-based)
- Multi-layer AI decision system
- Self-adjusting confidence thresholds
- Quality-weighted learning (prevents data pollution)
- Treasury-adaptive position sizing
- MEV-protected execution

---

*Document generated: March 2026*
*Codebase version: Build #474*
