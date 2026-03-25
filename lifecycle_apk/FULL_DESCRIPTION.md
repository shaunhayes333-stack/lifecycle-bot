# Lifecycle Bot - AI-Powered Solana Meme Coin Trading Bot

## Overview

Lifecycle Bot is a sophisticated Android-native trading bot that autonomously discovers, analyzes, and trades Solana meme coins using a **10-layer AI decision engine**. Built in Kotlin with real-time blockchain integration, it combines machine learning, quantitative analysis, LLM reasoning, and adaptive risk management to identify profitable trading opportunities.

**NEW: Gemini AI Co-pilot** - Full AI assistance layer providing narrative analysis, trade reasoning, exit advice, and risk assessment powered by Google's Gemini 2.0 Flash.

---

## Core Architecture

### 10-Layer AI Decision Engine

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
| 10 | **GeminiCopilot** | LLM-powered narrative/scam detection, trade reasoning, exit advice |

### GeminiCopilot - AI Co-pilot (NEW)

Full AI assistance layer powered by Google Gemini 2.0 Flash:

| Function | Description |
|----------|-------------|
| **Narrative/Scam Detection** | Deep analysis of token names, descriptions for pump & dump patterns |
| **Trade Reasoning** | Human-readable explanations for why trades are taken/skipped |
| **Smart Exit Advisor** | AI-suggested exit timing with urgency levels (IMMEDIATE/SOON/HOLD/RIDE) |
| **Risk Assessment** | Multi-factor risk scoring (liquidity, volatility, rug pull, market) |
| **Quick Scam Check** | Instant pattern-based detection without API call |

### FinalDecisionGate (FDG) - The Arbiter

All AI signals flow into the FDG, which makes the final trade decision through multiple gates:

- **Gate 1**: Hard Blocks - Zero liquidity, rugcheck failures, extreme sell pressure
- **Gate 1.5**: Early Snipe Mode - Fast-track for fresh launches
- **Gate 2a**: Edge Veto - Pattern-based entry timing
- **Gate 2b**: Groq Narrative Detection - LLM scam identification
- **Gate 2c**: Gemini AI Analysis - Deep narrative + viral potential scoring
- **Gate 3**: Confidence Threshold - Adaptive requirements
- **Gate 4**: Mode-Specific Rules - Paper vs Live strictness
- **Gate 4.5**: Liquidity Depth AI Check
- **Gate 4.75**: EV Validation - Positive expected value required
- **Gate 5**: Size Validation - Kelly-optimized positioning

---

## Auto-Learning System

### Adaptive Thresholds

FDG automatically adjusts strictness based on learning progress:

| Phase | Trades | Behavior |
|-------|--------|----------|
| **BOOTSTRAP** | 0-50 | Very loose - maximum exploration |
| **LEARNING** | 51-500 | Gradually tightening |
| **MATURE** | 500+ | Strict - EV gating fully enabled |

### Shadow Paper Trading (NEW)

Accelerated learning mode that runs background paper trades during live mode:

- Tracks up to 20 "shadow" positions in parallel
- Records wins/losses to BotBrain without risking capital
- Triggers on blocked trades AND parallel to live trades
- Exits at stop-loss, +50% take-profit, or 30-minute timeout
- Feeds all outcomes to AI learning systems

### Threshold Progression (Bootstrap → Mature)

| Setting | Start | End |
|---------|-------|-----|
| Rugcheck minimum | 5 | 15 |
| Buy pressure minimum | 10% | 25% |
| Top holder maximum | 85% | 55% |
| Confidence threshold | 0% | 20% |
| Edge override buy% | 38% | 55% |
| Edge override liquidity | $1,500 | $5,000 |
| Max rug probability | 35% | 10% |

### Learning Feedback Loop

Every trade outcome feeds back into all AI layers:

```
Trade Executed → Outcome Recorded → All AI Layers Learn → Thresholds Adjust
     ↑                                                         ↓
     └──────────── Shadow Trades Feed Learning ────────────────┘
```

Memory systems:
- **Rolling Memory**: 200 recent + 2,000 global trades
- **TokenWinMemory**: 500 winning token patterns
- **PersistentLearning**: Survives app reinstall
- **TradeDatabase**: SQLite analytics
- **Shadow Learning**: Background paper trade outcomes

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
| 10x+ | Let remainder ride with loose trail |

### Treasury-Adaptive Sizing

Position sizes scale with portfolio tier:

| Tier | Balance | Max Position |
|------|---------|--------------|
| Micro | <$50 | 3% |
| Small | $50-200 | 5% |
| Medium | $200-500 | 7% |
| Large | $500-2000 | 10% |
| Institutional | $2000+ | 12% |

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
- Actual token decimals fetched from blockchain

### Safety Systems

- **SecurityGuard**: Rate limiting, price anomaly detection
- **CircuitBreaker**: Auto-halt on repeated failures
- **RemoteKillSwitch**: Emergency stop capability
- **Keypair Integrity**: Wallet verification on every trade
- **GeminiCopilot**: AI-powered scam detection layer

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
| **Gemini 2.0 Flash** | AI co-pilot reasoning |

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
- Gemini reasoning logs
- Notification/sound toggles
- Advanced settings panel

---

## Web Dashboard

React + FastAPI dashboard for desktop monitoring:

- Real-time stats sync
- Position visualization
- Configuration management
- Trade history charts
- Decision log with AI reasoning

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
| AI/LLM | Groq (Llama), Gemini 2.0 Flash |

---

## Risk Management

### Hard Limits

- Maximum 70% portfolio exposure
- 5% reserve always maintained
- Automatic stop-loss at configurable %
- Trailing stop with momentum adjustment
- Gemini exit advisor for positions 15%+ gain

### Quality Filters

- Minimum liquidity requirements
- Rugcheck score thresholds
- Buy pressure minimums
- Top holder concentration limits
- Gemini viral potential scoring
- Quick scam pattern detection

---

## Performance Tracking

### Quantitative Metrics

- Sharpe Ratio
- Sortino Ratio
- Win Rate (rolling 10/50/all)
- Average Win/Loss
- Max Drawdown
- Recovery Factor
- EV per trade

### Per-Trade Analytics

- Entry/exit timing analysis
- Phase accuracy tracking
- Source performance ranking
- Pattern success rates
- Gemini reasoning accuracy

---

## Configuration Options

| Setting | Description |
|---------|-------------|
| Paper Mode | Simulated trading for testing |
| Shadow Paper | Background learning during live mode |
| Auto Trade | Enable/disable automatic execution |
| Gemini Enabled | AI co-pilot toggle |
| Slippage BPS | Base slippage tolerance |
| Stop Loss % | Automatic exit threshold |
| Trail % | Trailing stop distance |
| Min Liquidity | Minimum pool size |
| Jito Enabled | MEV protection toggle |

---

## What Makes It Different

1. **Truly Adaptive**: Starts loose, learns, gets stricter automatically
2. **10-Layer AI**: Multiple specialized AI systems including LLM reasoning
3. **Gemini Co-pilot**: Human-readable trade explanations and exit advice
4. **Shadow Learning**: Background paper trades accelerate AI learning 10x
5. **On-Chain Verified**: Verifies actual token balance before every sell
6. **MEV Protected**: Jupiter Ultra + Jito bundle support
7. **Memory Systems**: Learns from 500+ winning patterns
8. **Mobile Native**: Runs autonomously on Android
9. **Expected Value**: Mathematical probability-weighted decisions
10. **Kelly Sizing**: Optimal position sizing based on edge

---

## Current Status

- Live trading operational
- 10 AI layers integrated and learning
- Gemini Co-pilot active (narrative, exit advice, reasoning)
- Shadow Paper Trading enabled
- Auto-adjusting thresholds active
- Sell path verified with on-chain balance checks
- Web dashboard available

---

## Roadmap

### In Progress
- Backtesting UI for web dashboard
- Volume profile analysis
- Gemini insights panel

### Planned
- Social sentiment integration (Twitter)
- Supabase collective learning
- Multi-wallet support
- Advanced charting

---

*Built for the fast-moving world of Solana meme coins. Trade smart. Trade adaptive. Trade with AI.*
