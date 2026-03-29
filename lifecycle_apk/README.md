# AATE™ — Autonomous Algorithmic Trading Engine

> **The World's Most Advanced Open-Source Solana Trading Bot** — Native Android with 24 AI Layers, Fluid Learning, and Sentient Trading Architecture

[![Build Status](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml/badge.svg)](https://github.com/shaunhayes333-stack/lifecycle-bot/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

⚠️ **RISK WARNING:** Cryptocurrency trading involves substantial risk of loss. This software is NOT financial advice. See [LEGAL.md](LEGAL.md) for full disclaimers.

---

## Executive Summary

AATE is a native Android trading bot featuring a **24-layer AI consensus mechanism** that autonomously trades Solana meme coins. It learns from every trade, adapts to market conditions in real-time, and shares learnings across all instances via **Turso Collective Intelligence**.

**Key Stats:**
- 181 Kotlin source files (90,000+ lines of production code)
- **24 AI layers** working in parallel consensus
- **19 specialized trading modes** + Treasury Mode
- **Fluid Learning** - thresholds adapt as bot gains experience
- **Sentient Architecture** - AI that monitors and improves itself

---

## What's New in V3.3 (Sentient Trading)

### FluidLearningAI (Layer 23) - The Training Wheels
Fresh installs start with relaxed thresholds (30% confidence) so you can trade from Day 1. As the bot accumulates trades and learns, thresholds automatically tighten to 75-80%. No more waiting days before first trade.

### SellOptimizationAI (Layer 24) - Intelligent Exits
Chunk selling at profit milestones (25%, 50%, 75%), trailing stop locks, and exit urgency classification. The bot now knows *when* to take profits and *how much* to take.

### Persistent Journal & True Win Rates
Trade history persists until manually cleared. Win rate displayed on main screen now reflects actual historical performance, not ephemeral in-memory data.

---

## Architecture Overview

### The 24-Layer AI Consensus System

```
┌─────────────────────────────────────────────────────────────────────┐
│                     SENTIENT TRADING ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 24: SellOptimizationAI   │ Intelligent exit strategies        │
│ Layer 23: FluidLearningAI      │ Adaptive threshold controller      │
│ Layer 22: MetaCognitionAI      │ Self-aware executive function      │
│ Layer 21: CollectiveIntelAI    │ Hive mind synthesis                │
│ Layer 20: LiquidityCycleAI     │ Market-wide liquidity tracking     │
│ Layer 19: HoldTimeOptimizerAI  │ Optimal hold duration              │
│ Layer 18: SmartMoneyDivergence │ Whale behavior vs price            │
│ Layer 17: OrderFlowImbalanceAI │ Buy/sell pressure detection        │
│ Layer 16: VolatilityRegimeAI   │ Volatility & squeeze detection     │
│ Layer 15: SocialVelocityAI     │ Social momentum tracking           │
│ Layer 14: FearGreedAI          │ Market sentiment analysis          │
│ Layer 13: SuppressionAI        │ Manipulation detection             │
│ Layer 12: CopyTradeAI          │ Whale signal processing            │
│ Layer 11: TimeOptimizationAI   │ Best trading hours                 │
│ Layer 10: MarketRegimeAI       │ Bull/Bear/Crab detection           │
│ Layer  9: TokenWinMemoryAI     │ Historical performance             │
│ Layer  8: NarrativeDetectorAI  │ Trending theme detection           │
│ Layer  7: HolderSafetyAI       │ Rug pull detection (6 checks)      │
│ Layer  6: VolumeProfileAI      │ Volume pattern analysis            │
│ Layer  5: LiquidityDepthAI     │ Real-time LP monitoring            │
│ Layer  4: MomentumPredictorAI  │ Price trajectory analysis          │
│ Layer  3: EntryTimingAI        │ Perfect entry detection            │
│ Layer  2: SourceReliabilityAI  │ Signal source quality              │
│ Layer  1: StatisticalBase      │ EMA · Volume · RSI · MACD          │
└─────────────────────────────────────────────────────────────────────┘
```

### 19 Trading Modes + Treasury

| Mode | Strategy | Best For |
|------|----------|----------|
| STANDARD | Balanced quality gates | Default trading |
| MOONSHOT | Early high-potential detection | Fresh launches |
| PUMP_SNIPER | Ultra-fast entry on viral pumps | pump.fun tokens |
| MOMENTUM_SWING | Strong trend following | Confirmed pumps |
| WHALE_FOLLOW | Copy smart money movements | Whale-detected tokens |
| COPY_TRADE | Mirror tracked wallets | Following alpha |
| LONG_HOLD | Diamond hands - extended holds | High conviction |
| BLUE_CHIP | Conservative, larger caps | Lower-risk tokens |
| CYCLIC | Pattern-based trading | Repeating patterns |
| SLEEPER | Dormant token revivals | Sleeping giants |
| NICHE | Low supply opportunities | Micro-cap gems |
| PRESALE_SNIPE | First-block entry | Token launches |
| ARBITRAGE | Cross-DEX spreads | Price discrepancies |
| MICRO_CAP | Ultra-small mcap plays | High risk/reward |
| REVIVAL | Phoenix - crashed recovery | Oversold bounces |
| PUMP_DUMP | Aggressive accumulation | Pump detection |
| MARKET_MAKER | Spread profit capture | Ranging markets |
| LIQUIDATION_HUNTER | Distressed selling | Panic sells |
| **TREASURY** | Conservative cash generation | Daily income |

---

## Self-Learning Systems

### 1. FluidLearningAI (Layer 23) - NEW
Centralized adaptive threshold controller. Day 1 installs start at 30% confidence thresholds; as the bot learns from trades, thresholds tighten to 75-80%. Eliminates the "cold start" problem.

### 2. SellOptimizationAI (Layer 24) - NEW
Intelligent exit strategy layer. Calculates chunk sell milestones (25%/50%/75%), trailing stop locks, and exit urgency. Turns profit-taking from guesswork into science.

### 3. EdgeLearning
Dynamically adjusts entry/exit thresholds based on win rate. If the bot is winning 70%+, it loosens thresholds to take more trades. If dropping below 40%, it tightens to protect capital.

### 4. BehaviorLearning
Remembers every pattern that led to a win or loss. Patterns include EMA alignment, volume profile, liquidity depth, and time of day. Over time, builds a statistical edge.

### 5. MetaCognitionAI (Layer 22)
Self-aware "prefrontal cortex" that monitors all other AI layers. Tracks accuracy of each layer, adjusts trust dynamically, and can veto trades when reliable AIs disagree.

### 6. CollectiveLearning (Layer 21)
**Turso-powered shared knowledge base.** All AATE instances anonymously share:
- Pattern outcomes (win/loss aggregates)
- Token blacklist (confirmed rugs, honeypots)
- Mode performance by market condition
- Whale wallet effectiveness ratings

Privacy-preserving: No wallet addresses, trade sizes, or personal data is shared.

---

## Security Features

| Layer | Protection |
|-------|------------|
| Wallet Keys | AES-256 in Android EncryptedSharedPreferences |
| API Keys | Hardware-backed Keystore |
| Network | DNS-over-HTTPS for Jupiter APIs |
| Transactions | Jito MEV bundle protection |
| Runtime | Circuit breakers, kill switches |

### Anti-Rug Protection
- RugCheck.xyz integration (score-based blocking)
- Dev wallet sell detection (auto-exit on dev dumps)
- Liquidity depth monitoring (LP removal alerts)
- Top holder concentration analysis
- Freeze authority detection

### Risk Management
- **Circuit Breaker**: Pauses after X consecutive losses
- **Kill Switch**: Emergency halt on catastrophic drawdown
- **Wallet Reserve**: Never trades below minimum balance
- **Position Limits**: Max exposure as % of wallet
- **Daily Loss Cap**: Hard stop on daily losses

---

## Quick Start

### 1. Clone & Build
```bash
git clone https://github.com/shaunhayes333-stack/lifecycle-bot.git
cd lifecycle-bot
./gradlew assembleDebug
```

### 2. Install APK
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure (In App)
1. **Connect Wallet** — Import your Solana private key (stored encrypted)
2. **Add API Keys** — Jupiter, Helius (optional), Birdeye (optional)
3. **Enable Paper Mode** — Always start in paper mode!
4. **Let It Learn** — Run 24-48 hours before going live

---

## Configuration

### Required
- **Wallet Private Key** — Solana wallet with SOL for trading

### Recommended
- **Jupiter API Key** — Required for Ultra API (fast swaps)
- **Helius API Key** — Faster RPC, better reliability

### Optional
- **Telegram Bot** — Trade alerts and remote commands
- **Birdeye API** — Enhanced chart data
- **Turso DB** — Collective learning sync

### Telegram Commands
| Command | Action |
|---------|--------|
| `/status` | Current positions and P&L |
| `/pause` | Pause auto-trading |
| `/resume` | Resume auto-trading |
| `/kill` | Emergency stop |
| `/pnl` | Today's P&L summary |
| `/positions` | List open positions |
| `/treasury` | Treasury status |

---

## Technical Specifications

### Performance
- **Latency**: Sub-100ms decision loop
- **Memory**: ~80MB typical usage
- **Battery**: Optimized with partial wake locks
- **Reliability**: WorkManager + AlarmManager failsafes

### APIs Used
- Jupiter Ultra API (swaps)
- DexScreener API (price data)
- Birdeye API (OHLCV candles)
- Helius RPC (transactions)
- Rugcheck.xyz (safety scores)
- Turso/LibSQL (collective sync)

### Data Storage
- **Local**: SharedPreferences (encrypted)
- **Persistent**: External storage (survives reinstall)
- **Remote**: Turso edge database (collective learning)

---

## File Structure

```
app/src/main/kotlin/com/lifecyclebot/
├── collective/          # Turso collective learning
│   ├── CollectiveLearning.kt
│   ├── CollectiveSchema.kt
│   └── TursoClient.kt
├── data/                # Data models & config
│   ├── BotConfig.kt     # All configuration
│   ├── Models.kt        # Trade, Position, TokenState
│   └── BotStatus.kt     # Runtime state
├── engine/              # Core trading engine
│   ├── BotService.kt    # Main service (2700+ lines)
│   ├── Executor.kt      # Trade execution
│   ├── ModeRouter.kt    # 18-mode orchestration
│   ├── FinalDecisionGate.kt  # 12-layer consensus
│   ├── EntryIntelligence.kt  # Entry AI
│   ├── ExitIntelligence.kt   # Exit AI
│   └── [60+ more files]
├── network/             # External APIs
│   ├── JupiterApi.kt
│   ├── SolanaWallet.kt
│   └── DexscreenerApi.kt
└── ui/                  # Android UI
    ├── MainActivity.kt
    ├── WalletActivity.kt
    └── [8 more activities]
```

---

## Disclaimer

**CRYPTOCURRENCY TRADING INVOLVES SUBSTANTIAL RISK OF LOSS.**

- Past performance is NOT indicative of future results
- You may lose some or ALL of your invested capital
- Never trade with money you cannot afford to lose
- This software is provided "AS IS" without warranty
- The developers are NOT responsible for any financial losses
- This is NOT financial advice

**ALWAYS START IN PAPER MODE.** Let the bot learn for 24-48 hours before enabling live trading.

---

## License

MIT License — See [LICENSE](LICENSE) for details.

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

All contributions welcome!

---

## Support

- **Issues**: GitHub Issues
- **Discord**: Coming soon
- **Email**: Contact via GitHub

---

## Legal

**AATE™** is a trademark. The source code is MIT licensed, but the AATE name, logo, and branding are protected. See [LEGAL.md](LEGAL.md) for details.

### Risk Disclaimer

```
⚠️ CRYPTOCURRENCY TRADING INVOLVES SUBSTANTIAL RISK OF LOSS ⚠️

- This software is NOT financial advice
- Past performance is NOT indicative of future results  
- You may lose some or ALL of your invested capital
- The developers are NOT responsible for any financial losses
- ALWAYS use paper/demo mode before live trading

TRADE AT YOUR OWN RISK.
```

---

© 2025 AATE Project. All rights reserved.

*Built with obsessive attention to detail. Every line of code reviewed. Every edge case considered. This is what trading automation should look like.*
