# AATE™ — Autonomous Algorithmic Trading Engine

> **The World's Most Advanced Open-Source Solana Trading Bot** — Built in under a week with 63,000+ lines of production Kotlin code

[![Build Status](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml/badge.svg)](https://github.com/shaunhayes333-stack/lifecycle-bot/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

⚠️ **RISK WARNING:** Cryptocurrency trading involves substantial risk of loss. This software is NOT financial advice. See [LEGAL.md](LEGAL.md) for full disclaimers.

---

## Executive Summary

AATE is a native Android trading bot featuring a **12-layer AI consensus mechanism** that autonomously trades Solana meme coins. It learns from every trade, adapts to market conditions in real-time, and now shares learnings across all instances via **Turso Collective Intelligence**.

**Key Stats:**
- 63,000+ lines of production Kotlin code
- 12 AI layers working in consensus
- 18 specialized trading modes
- 6 learning systems that improve with every trade
- Built from scratch in under 7 days

---

## Architecture Overview

### The 12-Layer AI Consensus System

```
┌─────────────────────────────────────────────────────────────────────┐
│                     FINAL DECISION GATE (Layer 12)                  │
│              All 11 layers must agree for trade execution           │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 11: AICrossTalk          │ Inter-layer signal arbitration     │
│ Layer 10: LiquidityDepthAI     │ Real-time LP monitoring            │
│ Layer  9: TimeOptimizationAI   │ Optimal trading hours              │
│ Layer  8: NarrativeDetectorAI  │ Trending theme detection           │
│ Layer  7: MomentumPredictorAI  │ Pump probability scoring           │
│ Layer  6: MarketRegimeAI       │ Bull/Bear/Crab detection           │
│ Layer  5: WhaleTrackerAI       │ Smart money flow analysis          │
│ Layer  4: ExitIntelligence     │ Optimal exit timing                │
│ Layer  3: EntryIntelligence    │ Entry pattern recognition          │
│ Layer  2: BehaviorLearning     │ Pattern outcome memory             │
│ Layer  1: EdgeLearning         │ Dynamic threshold adjustment       │
├─────────────────────────────────────────────────────────────────────┤
│                    STATISTICAL FOUNDATION                           │
│     EMA Fan · Volume Profile · Buy Pressure · RSI · MACD            │
└─────────────────────────────────────────────────────────────────────┘
```

### 18 Trading Modes

| Mode | Strategy | Best For |
|------|----------|----------|
| PUMP_SNIPER | Ultra-fast entry on new launches | Fresh pump.fun tokens |
| MOMENTUM_RIDE | Trend following with trailing stops | Confirmed pumps |
| WHALE_FOLLOW | Copy smart money movements | Whale-detected tokens |
| SCALP_QUICK | Fast in-out on micro-moves | High-frequency action |
| RANGE_BOUND | Buy support, sell resistance | Consolidating tokens |
| RECOVERY_MODE | Average down on quality dips | Oversold conditions |
| COPY_TRADE | Mirror tracked wallets | Following alpha wallets |
| DIAMOND_HANDS | Extended holds on conviction | High-belief tokens |
| SNIPE_GRADUATE | Catch pump→Raydium migrations | Bonding curve graduates |
| NARRATIVE_PLAY | Theme-based entries | AI, DeSci, GameFi trends |
| BLUE_CHIP | Conservative, larger caps | Lower-risk tokens |
| SCALP_MICRO | Tiny position rapid trades | Learning mode |
| DIP_HUNTER | Catch oversold bounces | Panic sell reversals |
| BREAKOUT | Entry on range breaks | Technical breakouts |
| MEAN_REVERT | Fade extreme moves | Overextended tokens |
| NEWS_TRADE | React to sentiment spikes | Social catalysts |
| GRID_TRADE | Automated grid orders | Ranging markets |
| DEFENSIVE | Capital preservation | Losing streaks |

---

## Self-Learning Systems

### 1. EdgeLearning
Dynamically adjusts entry/exit thresholds based on win rate. If the bot is winning 70%+, it loosens thresholds to take more trades. If dropping below 40%, it tightens to protect capital.

### 2. BehaviorLearning
Remembers every pattern that led to a win or loss. Patterns include EMA alignment, volume profile, liquidity depth, and time of day. Over time, builds a statistical edge.

### 3. EntryIntelligence
Neural-inspired entry scoring. Learns which combinations of indicators produce winning entries and adjusts weights accordingly.

### 4. ExitIntelligence
Learns optimal hold times and exit triggers. Tracks profitability by exit reason (trailing stop, target hit, time exit, etc.).

### 5. ModeLearning
Tracks performance by trading mode. Automatically suggests the best mode for current market conditions.

### 6. CollectiveLearning (NEW)
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
