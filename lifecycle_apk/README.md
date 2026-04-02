# AATE - Autonomous AI Trading Engine

<div align="center">

```
    ╔═══════════════════════════════════════════════════════════════════╗
    ║                                                                   ║
    ║     █████╗  █████╗ ████████╗███████╗    ██╗   ██╗███████╗        ║
    ║    ██╔══██╗██╔══██╗╚══██╔══╝██╔════╝    ██║   ██║██╔════╝        ║
    ║    ███████║███████║   ██║   █████╗      ██║   ██║███████╗        ║
    ║    ██╔══██║██╔══██║   ██║   ██╔══╝      ╚██╗ ██╔╝╚════██║        ║
    ║    ██║  ██║██║  ██║   ██║   ███████╗     ╚████╔╝ ███████║        ║
    ║    ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚══════╝      ╚═══╝  ╚══════╝        ║
    ║                                                                   ║
    ║          AUTONOMOUS AI TRADING ENGINE FOR SOLANA                  ║
    ║                                                                   ║
    ╚═══════════════════════════════════════════════════════════════════╝
```

### **110,000+ Lines of Kotlin. 914+ Commits. 28 AI Layers. 1 Guy. 10 Days. 1 Phone.**

[![Build Status](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml/badge.svg)](https://github.com/shaunhayes333-stack/lifecycle-bot/actions)
[![Version](https://img.shields.io/badge/version-5.2.11-blue.svg)]()
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)]()
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.0-purple.svg)]()

</div>

---

## The Story

**Built entirely on a mobile phone.** No laptop. No desktop. No IDE. Just a phone, GitHub Actions for compilation, and an unholy amount of caffeine.

What started as a simple trading bot evolved into a **self-learning, multi-layered AI trading system** that watches, learns, and executes trades on the Solana blockchain with institutional-grade precision.

---

## What Is AATE?

AATE is a **native Android application** that runs a sophisticated autonomous trading engine directly on your phone. It's not a toy. It's not a prototype. It's a production-grade trading system with:

- **28 Specialized AI Layers** working in concert
- **4-Tier Trading Architecture** (Treasury → ShitCoin → Quality → BlueChip/Moonshot)
- **Fluid Learning System** that adapts in real-time
- **Paper Mode** for risk-free strategy development
- **Collective Intelligence** sync across devices
- **SAFE MODE** for capital protection

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         AATE V5.2 ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                     MARKET SCANNERS                              │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │   │
│  │  │ PumpFun  │ │ Raydium  │ │DexScreener│ │ Birdeye  │           │   │
│  │  │ Scanner  │ │ Scanner  │ │  Scanner  │ │  Charts  │           │   │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘           │   │
│  └───────┼────────────┼────────────┼────────────┼──────────────────┘   │
│          └────────────┴─────┬──────┴────────────┘                      │
│                             ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                   V3 DECISION ENGINE                             │   │
│  │  ┌────────────────────────────────────────────────────────────┐ │   │
│  │  │              28 AI SCORING LAYERS                          │ │   │
│  │  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │ │   │
│  │  │  │ FluidLearn  │ │ BehaviorAI  │ │ MetaCognit  │          │ │   │
│  │  │  │ 1,224 lines │ │  864 lines  │ │  739 lines  │          │ │   │
│  │  │  └─────────────┘ └─────────────┘ └─────────────┘          │ │   │
│  │  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │ │   │
│  │  │  │ CashGenAI   │ │ ShitCoinAI  │ │ QualityAI   │          │ │   │
│  │  │  │ 1,067 lines │ │ 1,058 lines │ │  417 lines  │ [NEW]    │ │   │
│  │  │  └─────────────┘ └─────────────┘ └─────────────┘          │ │   │
│  │  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │ │   │
│  │  │  │ BlueChipAI  │ │ MoonshotAI  │ │ RugDetector │          │ │   │
│  │  │  │  674 lines  │ │  829 lines  │ │  614 lines  │          │ │   │
│  │  │  └─────────────┘ └─────────────┘ └─────────────┘          │ │   │
│  │  │  + 19 more specialized AI layers...                        │ │   │
│  │  └────────────────────────────────────────────────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                             │                                          │
│                             ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                 4-TIER TRADING LAYERS                            │   │
│  │                                                                  │   │
│  │  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐  │   │
│  │  │ TREASURY │───▶│ SHITCOIN │───▶│ QUALITY  │───▶│BLUECHIP/ │  │   │
│  │  │  Layer   │    │  Layer   │    │  Layer   │    │ MOONSHOT │  │   │
│  │  │ Scalping │    │  Degen   │    │   Pro    │    │  Layer   │  │   │
│  │  │ 0.01 SOL │    │ 0.05 SOL │    │ 0.08 SOL │    │ 0.15 SOL │  │   │
│  │  │ TP: 3-8% │    │TP: 8-25% │    │TP: 15-50%│    │TP: 25%+  │  │   │
│  │  └──────────┘    └──────────┘    └──────────┘    └──────────┘  │   │
│  │       │               │               │               │         │   │
│  │       └───────────────┴───────────────┴───────────────┘         │   │
│  │                       PROMOTION SYSTEM                           │   │
│  │              (Tokens graduate up as they prove themselves)       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                             │                                          │
│                             ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    EXECUTION LAYER                               │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │   │
│  │  │ Position │ │ Exit     │ │ Risk     │ │ Trailing │           │   │
│  │  │  Sizing  │ │ Manager  │ │ Guards   │ │  Stops   │           │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                             │                                          │
│                             ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    SOLANA BLOCKCHAIN                             │   │
│  │              Jupiter Aggregator / Direct Swaps                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## The 28 AI Layers

AATE doesn't use one AI model. It orchestrates **28 specialized AI layers**, each with a specific responsibility:

### Scoring & Analysis
| Layer | Lines | Purpose |
|-------|-------|---------|
| `FluidLearningAI` | 1,224 | Adaptive thresholds that evolve with experience |
| `CashGenerationAI` | 1,067 | Treasury scalping for self-funding operations |
| `ShitCoinTraderAI` | 1,058 | Meme/degen token specialist |
| `CollectiveIntelligenceAI` | 914 | Cross-device learning sync |
| `EducationSubLayerAI` | 885 | Feeds trade outcomes to Harvard Brain |
| `BehaviorAI` | 864 | Tracks and corrects "bad behavior" patterns |
| `MoonshotTraderAI` | 829 | 100%+ gain hunter |
| `MetaCognitionAI` | 739 | AI that watches the other AIs |
| `RegimeTransitionAI` | 686 | Market regime detection |
| `BlueChipTraderAI` | 674 | $1M+ mcap professional trading |
| `DipHunterAI` | 673 | Oversold bounce detector |
| `ShitCoinExpress` | 627 | Fast-track meme entries |
| `UltraFastRugDetectorAI` | 614 | Sub-second rug pull detection |
| `HoldTimeOptimizerAI` | 582 | Dynamic hold duration |
| `SellOptimizationAI` | 540 | Exit timing optimization |
| `SolanaArbAI` | 533 | Cross-venue arbitrage |
| `LiquidityCycleAI` | 485 | Liquidity pattern recognition |
| `AdvancedExitManager` | 478 | Multi-factor exit decisions |
| `OrderFlowImbalanceAI` | 472 | Buy/sell pressure analysis |
| `QualityTraderAI` | 417 | Professional mid-cap trading |
| `SmartMoneyDivergenceAI` | - | Whale movement tracking |
| `FearGreedAI` | - | Market sentiment gauge |
| `SocialVelocityAI` | - | Social momentum detection |
| `LayerTransitionManager` | - | Token promotion/demotion |
| `CrossTalkAI` | 924 | Inter-layer communication |
| `UnifiedScorer` | - | Final score aggregation |
| `ScoreCard` | - | Trade grading system |
| `NarrativeDetectorAI` | - | Trending narrative identification |

### Total AI Scoring Code: **16,766 lines** (just the scoring layers!)

---

## The 4-Tier Trading System

AATE doesn't treat all tokens the same. It operates a sophisticated **promotion/demotion system**:

### Tier 1: Treasury (CashGenerationAI)
- **Purpose**: Self-funding scalping machine
- **Position Size**: 0.01 SOL
- **Targets**: 3-8% quick profits
- **Hold Time**: 1-15 minutes
- **Philosophy**: "Pay for the bot's operations"

### Tier 2: ShitCoin (ShitCoinTraderAI)
- **Purpose**: Degen meme coin plays
- **Position Size**: 0.05 SOL
- **Targets**: 8-25% gains
- **Hold Time**: 5-30 minutes
- **Philosophy**: "High risk, high reward memes"

### Tier 3: Quality (QualityTraderAI) [NEW in V5.2]
- **Purpose**: Professional Solana trading
- **Position Size**: 0.08 SOL
- **Targets**: 15-50% gains
- **Hold Time**: 15-60 minutes
- **Philosophy**: "Not memes - real projects"
- **Market Cap**: $100K - $1M

### Tier 4: BlueChip / Moonshot
- **Purpose**: Large cap / moon shot plays
- **Position Size**: 0.15 SOL
- **Targets**: 25-200%+ gains
- **Hold Time**: Hours to days
- **Philosophy**: "Let winners run"

**Tokens promote upward** as they prove themselves. A ShitCoin that hits $1M mcap becomes a BlueChip. A Quality trade that gains 100%+ becomes a Moonshot.

---

## Key Features

### Fluid Learning System
The bot doesn't use fixed thresholds. **Everything adapts:**
- Take profit targets start conservative, expand with experience
- Stop losses start tight, loosen as the bot learns what works
- Position sizes scale with confidence
- The bot literally gets better every day

### Paper Mode
**Risk-free learning** with a virtual wallet:
- Same market data, same AI decisions
- Perfect for testing strategies
- Tracks virtual P&L accurately
- All learning transfers to live mode

### SAFE MODE
When things go wrong:
- Automatic position reduction
- Tighter risk controls
- Reduced position sizing
- "Live to trade another day"

### Collective Intelligence
Multiple AATE instances **share learning**:
- Trade outcomes sync to cloud
- Bad patterns identified across fleet
- Good setups propagate automatically
- Distributed intelligence network

### Harvard Brain Education
Every trade teaches:
- Win patterns get reinforced
- Loss patterns get flagged
- The system builds a "trading memory"
- Bad behaviors get tracked and corrected

---

## Technical Stats

```
┌────────────────────────────────────────┐
│          AATE BY THE NUMBERS           │
├────────────────────────────────────────┤
│  Total Kotlin Files:       209         │
│  Total Lines of Code:      110,444     │
│  Git Commits:              914+        │
│  AI Scoring Layers:        28          │
│  Engine Files:             50+         │
│  V3 Module Files:          40+         │
│  Development Time:         ~10 days    │
│  Development Device:       1 Phone     │
│  Developers:               1           │
├────────────────────────────────────────┤
│  Largest Files:                        │
│  ├─ Executor.kt            6,197 lines │
│  ├─ BotService.kt          6,089 lines │
│  ├─ LifecycleStrategy.kt   3,601 lines │
│  ├─ FinalDecisionGate.kt   3,377 lines │
│  └─ SolanaMarketScanner.kt 2,860 lines │
└────────────────────────────────────────┘
```

---

## Installation

### From GitHub Releases
1. Download the latest APK from [Releases](https://github.com/shaunhayes333-stack/lifecycle-bot/releases)
2. Enable "Install from Unknown Sources" on your Android device
3. Install the APK
4. Configure your wallet and start trading

### Build from Source
```bash
# Clone the repository
git clone https://github.com/shaunhayes333-stack/lifecycle-bot.git
cd lifecycle-bot/lifecycle_apk

# Build with Gradle
./gradlew assembleRelease

# APK will be in app/build/outputs/apk/release/
```

---

## Configuration

AATE is designed to work out of the box, but power users can configure:

- **Mode Selection**: Paper / Live / SAFE MODE
- **Position Sizes**: Per-layer customization
- **Risk Limits**: Max exposure, max positions
- **Layer Enables**: Turn individual AI layers on/off
- **Notification Settings**: Telegram alerts, sounds
- **Scanner Filters**: Market cap, liquidity, age

---

## Safety Features

- **Rug Detection**: Sub-second rug pull identification
- **Liquidity Checks**: Won't enter illiquid tokens
- **Position Limits**: Hard caps on exposure
- **Stop Losses**: Adaptive protective stops
- **Rate Limiting**: API protection
- **Error Recovery**: Automatic restart on failures

---

## Roadmap

- [ ] GitHub Releases automation for APK distribution
- [ ] UI improvements for SHADOW_ONLY tokens
- [ ] iOS port (maybe)
- [ ] Web dashboard for monitoring
- [ ] Advanced analytics export

---

## Disclaimer

**AATE is experimental software for educational purposes.**

Trading cryptocurrencies involves significant risk. You can lose all your money. Past performance does not guarantee future results. Only trade with funds you can afford to lose.

The developers are not responsible for any financial losses incurred while using this software.

---

## License

Proprietary. All rights reserved.

---

<div align="center">

### Built with obsession by one developer, one phone, ten days of madness.

**AATE V5.2.11** | March 2026

</div>
