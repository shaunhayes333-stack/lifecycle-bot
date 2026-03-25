# Lifecycle Bot - AI-Powered Solana Meme Coin Trading Bot

## Overview

Lifecycle Bot is a sophisticated Android-native trading bot that autonomously discovers, analyzes, and trades Solana meme coins using a 9-layer AI decision engine. Built in Kotlin with real-time blockchain integration, it combines machine learning, quantitative analysis, and adaptive risk management to identify profitable trading opportunities.

---

## Core Architecture

### 9-Layer AI Decision Engine

| Layer | Name | Function |
|-------|------|----------|
| 1 | **MarketRegimeAI** | Detects global market state (BULL/BEAR/NEUTRAL) to adjust strategy aggressiveness |
| 2 | **WhaleTrackerAI** | Monitors large wallet movements for accumulation/distribution signals |
| 3 | **MomentumPredictorAI** | Predicts short-term price momentum using technical indicators |
| 4 | **NarrativeDetectorAI** | Identifies hot narratives (AI, PEPE, political themes) using Groq LLM |
| 5 | **TimeOptimizationAI** | Learns optimal trading hours and avoids historically bad periods |
| 6 | **LiquidityDepthAI** | Monitors liquidity health, detects collapse/growth patterns |
| 7 | **AICrossTalk** | Cross-correlates signals across layers for "Smart Money Pump" detection |
| 8 | **TokenWinMemory** | Pattern-matches against 500+ past winning tokens |
| 9 | **EVCalculator** | Expected Value modeling with Kelly Criterion position sizing |

### FinalDecisionGate (FDG) - The Arbiter

All AI signals flow into the FDG, which makes the final trade decision through multiple gates:

- **Hard Blocks**: Zero liquidity, rugcheck failures, extreme sell pressure
- **Phase Filter**: Identifies token lifecycle phase (launch, pump, distribution)
- **Edge Veto**: Pattern-based entry timing optimization
- **Narrative Scam Detection**: LLM-powered scam identification
- **Confidence Threshold**: Adaptive confidence requirements
- **EV Validation**: Only executes positive expected value trades
- **Size Validation**: Kelly-optimized position sizing

---

## Auto-Learning System

### Adaptive Thresholds

FDG automatically adjusts strictness based on learning progress:

| Phase | Trades | Behavior |
|-------|--------|----------|
| **BOOTSTRAP** | 0-10 | Very loose - maximum exploration |
| **LEARNING** | 11-50 | Gradually tightening |
| **MATURE** | 50+ | Strict - EV gating enabled |

### Threshold Progression (Bootstrap → Mature)

| Setting | Start | End |
|---------|-------|-----|
| Rugcheck minimum | 5 | 12 |
| Buy pressure minimum | 10% | 20% |
| Top holder maximum | 85% | 60% |
| Confidence threshold | 0% | 15% |
| Edge override buy% | 38% | 52% |
| Edge override liquidity | $1,500 | $4,000 |
| Max rug probability | 35% | 12% |

### Learning Feedback Loop

Every trade outcome feeds back into all 9 AI layers:

```
Trade Executed → Outcome Recorded → All AI Layers Learn → Thresholds Adjust
```

Memory systems:
- **Rolling Memory**: 200 recent + 2,000 global trades
- **TokenWinMemory**: 500 winning token patterns
- **PersistentLearning**: Survives app reinstall
- **TradeDatabase**: SQLite analytics

---

## Trading Features

### EARLY_SNIPE Mode

For newly discovered tokens, bypasses heavy checks for fast entry:

- Token age < 20 minutes
- Score >= 45
- Liquidity >= $1,500
- Half-size positions for risk management

### Dynamic Profit Lock System

Prevents round-tripping gains:

| Gain | Action |
|------|--------|
| 2x | Recover initial capital |
| 5x | Lock 50% profits |
| 10x+ | Let remainder ride |

### Treasury-Adaptive Sizing

Position sizes scale with portfolio tier:

| Tier | Balance | Max Position |
|------|---------|--------------|
| Micro | <$50 | 3% |
| Small | $50-200 | 5% |
| Medium | $200-500 | 7% |
| Large | $500+ | 10% |

### MEV Protection

- **Jupiter Ultra**: Built-in Beam MEV protection (priority)
- **Jito Bundles**: Fallback MEV protection
- **Slippage Guard**: Dual-quote validation on buys

---

## Execution Engine

### Jupiter Ultra API Integration

- Primary execution via Jupiter Ultra for best rates
- Automatic retry logic (3 attempts)
- 2x slippage tolerance on sells
- On-chain balance verification before sells

### Safety Systems

- **SecurityGuard**: Rate limiting, price anomaly detection
- **CircuitBreaker**: Auto-halt on repeated failures
- **RemoteKillSwitch**: Emergency stop capability
- **Keypair Integrity**: Wallet verification on every trade

---

## Data Sources

| Source | Data |
|--------|------|
| **Helius RPC** | Blockchain data, transaction monitoring |
| **Birdeye** | Token metadata, price feeds |
| **DexScreener** | New pool detection, liquidity tracking |
| **Pump.fun** | Fresh launch discovery |
| **RugCheck.xyz** | Safety scoring |
| **CoinGecko/Binance** | SOL price feed |
| **Groq LLM** | Narrative/scam analysis |

---

## Scanner Sources

Continuous multi-source token discovery:

1. **Pump.fun Direct** - Freshest launches
2. **Pump.fun Graduates** - Tokens that graduated to Raydium
3. **Raydium New Pools** - Direct pool monitoring
4. **DexScreener Boosted** - Promoted tokens
5. **DexScreener Gainers** - Top performers

---

## Mobile UI Features

- Real-time P&L dashboard
- Trade journal with export
- Position monitoring
- AI layer status indicators
- Notification/sound toggles
- Advanced settings panel

---

## Web Dashboard (NEW)

React + FastAPI dashboard for desktop monitoring:

- Real-time stats sync
- Position visualization
- Configuration management
- Trade history charts

---

## Technical Specifications

| Component | Technology |
|-----------|------------|
| Platform | Android (Kotlin) |
| Architecture | MVVM + Coroutines |
| Database | SQLite + SharedPreferences |
| Network | OkHttp + Cloudflare DoH |
| Blockchain | Solana (ed25519 signing) |
| Backend API | FastAPI (Python) |
| Frontend | React |

---

## Risk Management

### Hard Limits

- Maximum 60% portfolio exposure
- 5% reserve always maintained
- Automatic stop-loss at configurable %
- Trailing stop with momentum adjustment

### Quality Filters

- Minimum liquidity requirements
- Rugcheck score thresholds
- Buy pressure minimums
- Top holder concentration limits

---

## Performance Tracking

### Quantitative Metrics

- Sharpe Ratio
- Sortino Ratio
- Win Rate (rolling 10/50/all)
- Average Win/Loss
- Max Drawdown
- Recovery Factor

### Per-Trade Analytics

- Entry/exit timing analysis
- Phase accuracy tracking
- Source performance ranking
- Pattern success rates

---

## Configuration Options

| Setting | Description |
|---------|-------------|
| Paper Mode | Simulated trading for testing |
| Auto Trade | Enable/disable automatic execution |
| Slippage BPS | Base slippage tolerance |
| Stop Loss % | Automatic exit threshold |
| Trail % | Trailing stop distance |
| Min Liquidity | Minimum pool size |
| Jito Enabled | MEV protection toggle |

---

## What Makes It Different

1. **Truly Adaptive**: Starts loose, learns, gets stricter automatically
2. **9-Layer AI**: Not just one model - multiple specialized AI systems
3. **On-Chain Verified**: Verifies actual token balance before every sell
4. **MEV Protected**: Jupiter Ultra + Jito bundle support
5. **Memory Systems**: Learns from 500+ winning patterns
6. **Mobile Native**: Runs autonomously on Android
7. **Expected Value**: Mathematical probability-weighted decisions
8. **Kelly Sizing**: Optimal position sizing based on edge

---

## Current Status

- Live trading operational
- 9 AI layers integrated and learning
- Auto-adjusting thresholds active
- Sell path verified with on-chain balance checks
- Web dashboard available

---

## Roadmap

### In Progress
- Backtesting UI for web dashboard
- Volume profile analysis

### Planned
- Social sentiment integration (Twitter)
- Supabase collective learning
- Multi-wallet support
- Advanced charting

---

*Built for the fast-moving world of Solana meme coins. Trade smart. Trade adaptive.*
