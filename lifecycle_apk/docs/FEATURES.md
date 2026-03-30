# LIFECYCLE BOT V5.2
## Complete Feature Documentation

---

# Table of Contents

1. [Trading Layers](#trading-layers)
2. [AI Components](#ai-components)
3. [Risk Management](#risk-management)
4. [Learning Systems](#learning-systems)
5. [User Interface](#user-interface)
6. [Configuration](#configuration)
7. [Data Sources](#data-sources)

---

# Trading Layers

## Overview

Lifecycle Bot operates 5 specialized trading layers, each targeting different market segments and risk profiles.

## V3 Core Engine

**Purpose**: Quality-focused trading with balanced risk/reward

**Target Market Cap**: $30,000 - $1,000,000

**Strategy**:
- Multi-factor scoring using 25+ AI components
- Quality grades (A, B, C, D) based on token characteristics
- Confidence thresholds that adapt based on learning progress
- Shadow tracking for trades that don't meet threshold

**Key Parameters**:
| Parameter | Value |
|-----------|-------|
| Min Confidence | 19-25% (fluid) |
| Min Quality | C+ |
| Max Hold Time | 60 min |
| Default TP | 35% |
| Default SL | 12% |

**Scoring Components**:
- Source score (+4 for DEX_BOOSTED)
- Entry score (from EntryAI)
- Momentum score (from MomentumAI)
- Liquidity score (from LiquidityAI)
- Volume score (from VolumeProfileAI)
- Holder score (concentration penalty)
- Narrative score (from NarrativeAI)
- Regime score (from RegimeAI)
- Time score (from TimeAI)
- Behavior score (from BehaviorAI)
- +10 more components

---

## Treasury Mode (Cash Generation AI)

**Purpose**: Conservative scalping for consistent small gains

**Target Market Cap**: All caps (liquidity focused)

**Strategy**:
- Quick entries and exits
- Tight take-profit targets
- Strict stop-losses
- Auto-compounding of profits

**Key Parameters**:
| Parameter | Value |
|-----------|-------|
| Min Score | 18 |
| Min Confidence | 33% |
| Min Liquidity | $4,500 |
| Default TP | 4-15% |
| Default SL | 2% |
| Max Hold | 30 min |
| Max Position | 0.375 SOL |

**Modes**:
- AGGRESSIVE: Higher targets, more risk
- CONSERVATIVE: Tighter stops, smaller gains
- ADAPTIVE: Adjusts based on market conditions

---

## BlueChip Mode

**Purpose**: Trading established, higher market cap tokens

**Target Market Cap**: $1,000,000+

**Strategy**:
- Longer hold times
- Momentum-based entries
- Wider stops to handle volatility
- Quality over quantity

**Key Parameters**:
| Parameter | Value |
|-----------|-------|
| Min Market Cap | $1,000,000 |
| Default TP | 40% |
| Default SL | 15% |
| Max Hold | 120 min |
| Max Position | 0.50 SOL |

---

## ShitCoin Mode

**Purpose**: High-risk, high-reward degen plays on micro-caps

**Target Market Cap**: < $30,000

**Strategy**:
- Fast entries on pump.fun launches
- Aggressive take-profits
- Quick exits on signs of trouble
- Multiple small positions

**Key Parameters**:
| Parameter | Value |
|-----------|-------|
| Max Market Cap | $30,000 |
| Min Liquidity | $1,000 |
| Default TP | 25-30% |
| Default SL | 8-10% |
| Max Hold | 15 min |
| Max Position | 0.20 SOL |
| Max Concurrent | 5 |

**Risk Levels**:
- LOW: Established micro-cap, good liquidity
- MEDIUM: New launch, moderate liquidity
- HIGH: Very new, low liquidity
- EXTREME: Ultra micro-cap, bootstrap mode only

---

## Moonshot Mode (NEW V5.2)

**Purpose**: Asymmetric bets for 10x-1000x opportunities

**Target Market Cap**: $100,000 - $50,000,000

**Strategy**:
- Let winners ride with trailing stops
- Space-themed mode progression
- Cross-trading promotion pathway
- Collective intelligence integration

### Space Modes

| Mode | Emoji | Market Cap | Base TP | Base SL | Max Hold |
|------|-------|-----------|---------|---------|----------|
| ORBITAL | 🛸 | $100K-$500K | 100% | -10% | 45 min |
| LUNAR | 🌙 | $500K-$2M | 200% | -12% | 60 min |
| MARS | 🔴 | $2M-$5M | 500% | -15% | 120 min |
| JUPITER | 🪐 | $5M-$50M | 1000% | -20% | 240 min |

### Dynamic Trailing Stops

As gains increase, trailing stop percentage tightens:

| P&L | Trail % |
|-----|---------|
| +30% | Mode default (10-15%) |
| +100% | 10% |
| +200% | 8% |
| +500% | 6% |
| +1000% | 5% |

### Cross-Trading Promotion

When a position in another layer hits +200%:
1. `shouldPromoteToMoonshot()` checks eligibility
2. If market cap in range and slots available
3. Original layer closes position
4. Moonshot opens new position at current price
5. Trailing stops take over

### Collective Intelligence

Network-wide 10x+ winners are tracked:
- Tokens that hit 10x+ across multiple traders get flagged
- JUPITER mode prioritizes collective winners
- Up to +20 bonus score for collective tokens

---

# AI Components

## Entry AI

**Purpose**: Determine optimal entry timing

**Inputs**:
- Buy pressure percentage
- RSI (Relative Strength Index)
- Volume profile position (VAL/VAH)
- Historical hour performance
- Liquidity depth

**Output**: Entry score 0-100
- 80+ = STRONG_BUY
- 70-80 = BUY
- 50-70 = WAIT
- <50 = SKIP

**Learning**: Supervised learning from trade outcomes

---

## Exit AI

**Purpose**: Optimize exit timing and method

**Inputs**:
- Current P&L
- Hold time
- Price momentum
- Volatility
- High water mark

**Signals**:
- HOLD: Continue holding
- TAKE_PROFIT: Target reached
- STOP_LOSS: Loss limit hit
- TRAILING_STOP: Trailing stop triggered
- TIMEOUT: Max hold time exceeded
- RUG_DETECTED: Emergency exit

**Learning**: Reinforcement learning from outcomes

---

## Momentum AI

**Purpose**: Detect pumps, dumps, and trend changes

**Detection Types**:
- PUMP_BUILDING: Early pump detection
- STRONG_PUMP: Confirmed pump
- DISTRIBUTION: Selling pressure
- WEAK_MOMENTUM: Fading trend
- NEUTRAL: No clear direction

**Signals**: Adjusts entry/exit scores based on momentum state

---

## Liquidity AI

**Purpose**: Monitor pool health and detect issues

**Metrics**:
- Liquidity depth (DEEP/FAIR/SHALLOW/DANGEROUS)
- Growth rate (% change over time)
- Drain detection (rapid liquidity removal)

**Alerts**:
- STRONG_GROWTH: Healthy increasing liquidity
- DRAINING: Liquidity being removed
- COLLAPSE: Severe liquidity crisis

---

## Behavior AI

**Purpose**: Prevent emotional trading and enforce discipline

**Tracking**:
- Consecutive wins/losses
- Tilt level (0-200%)
- Discipline score (0-100%)
- Sentiment (EUPHORIA to EXTREME_FEAR)

**Actions**:
- Tilt protection (forced cooldown)
- Size reduction after losses
- Behavior score adjustment to V3

**V5.2 Enhancement**: Bootstrap-aware thresholds

| Learning | Loss Threshold | Tilt Threshold | Cooldown |
|----------|----------------|----------------|----------|
| 0-10% | 15 | 150% | 30s |
| 10-20% | 12 | 120% | 30s |
| 20-40% | 10 | 100% | 45s |
| 40-60% | 8 | 90% | 45s |
| 60%+ | 5 | 80% | 60s |

---

## Edge AI

**Purpose**: Learn and adjust thresholds based on outcomes

**Tracked Thresholds**:
- Buy pressure % minimum
- Volume threshold
- Entry score minimum
- Confidence floor

**Learning**: Online learning - tightens thresholds after losses, loosens after wins

---

## Volume Profile AI

**Purpose**: Identify accumulation and distribution zones

**Zones**:
- VAL (Value Area Low): Potential accumulation
- VAH (Value Area High): Potential distribution
- POC (Point of Control): High volume price level

**Signals**: Entry/exit score adjustments based on zone

---

## Whale Tracker AI

**Purpose**: Detect smart money movements

**Detection**:
- Large buys/sells
- Wallet clustering
- Flow divergence (whale vs retail)

**Signals**: SMART_MONEY_PUMP, COORDINATED_DUMP

---

## Narrative AI

**Purpose**: Identify trending themes and narratives

**Categories**:
- AI/ML tokens
- Gaming
- DeFi
- Memecoins
- Infrastructure

**Scoring**: Hot narratives get bonus points

---

## Time Optimization AI

**Purpose**: Identify optimal trading hours

**Analysis**:
- Hour-by-hour win rate
- Day-of-week patterns
- Golden hours detection

**Application**: Time score in V3 engine

---

## Regime AI

**Purpose**: Adapt to overall market conditions

**States**:
- BULL: Risk-on, wider targets
- BEAR: Risk-off, tighter stops
- NEUTRAL: Default parameters
- VOLATILE: Reduced size

---

## MetaCognition AI

**Purpose**: Cross-layer pattern recognition

**Function**: Analyzes correlations between all AI signals to detect higher-order patterns

---

# Risk Management

## Position Limits

| Layer | Max Concurrent | Max Position Size |
|-------|---------------|-------------------|
| V3 Core | 3 | 0.30 SOL |
| Treasury | 5 | 0.375 SOL |
| BlueChip | 2 | 0.50 SOL |
| ShitCoin | 5 | 0.20 SOL |
| Moonshot | 6 | 0.40 SOL |

## Daily Loss Limits

- Treasury: ~$50/day max loss
- ShitCoin: ~$30/day max loss
- All layers: Circuit breakers on catastrophic losses

## Circuit Breakers

Automatic layer freezes on severe drawdowns:
- 3 consecutive rugs: Layer freeze 1 hour
- 50%+ single loss: Layer freeze 12 hours
- Daily loss limit hit: Layer paused until next day

## Stop Loss Types

1. **Hard Stop**: Fixed percentage from entry
2. **Trailing Stop**: Follows price up, locks in gains
3. **Rapid Stop**: Emergency exit on 40%+ drop
4. **Time Stop**: Exit on max hold time

---

# Learning Systems

## Fluid Learning AI

**Purpose**: Adaptive confidence thresholds based on performance

**Progress**: 0-100% based on trade count and outcomes

**Effects**:
- Lower progress = more permissive thresholds (learning mode)
- Higher progress = stricter thresholds (mature mode)

## Collective Learning

**Purpose**: Network-wide intelligence sharing

**Mechanism**:
- Trade outcomes uploaded (anonymized)
- 10x+ winners flagged
- Network patterns detected
- Shared back to all bots

---

# User Interface

## Dashboard Tiles

### V3 Core (🎯)
- Win/Loss ratio
- Learning progress

### Treasury (💰)
- Daily P&L
- Win rate

### BlueChip (🔵)
- Active positions
- Performance

### ShitCoin (💩)
- Trade count
- Win rate

### Moonshot (🚀)
- Space mode distribution
- 10x/100x counters

## Dialogs

Each tile opens detailed stats dialog with:
- Current mode/status
- Daily performance
- Historical metrics
- Configuration options

---

# Configuration

## API Keys Required

| Service | Purpose | Free Tier |
|---------|---------|-----------|
| Helius | RPC + transactions | Yes |
| Birdeye | Market data | Yes |
| Rugcheck | Safety scoring | Yes |

## Risk Parameters

Configurable per layer:
- Position size
- Take profit %
- Stop loss %
- Max concurrent
- Daily loss limit

## Paper/Live Toggle

- Paper Mode: Simulated trading, no real transactions
- Live Mode: Real SOL transactions

---

# Data Sources

## Token Discovery

- DexScreener API (boosted tokens, trending)
- pump.fun API (new launches)
- Raydium API (new pools)

## Market Data

- Birdeye (prices, volume, trades)
- DexScreener (liquidity, market cap)
- On-chain data via Helius

## Safety

- Rugcheck.xyz (contract analysis)
- On-chain holder analysis
- Liquidity lock detection

---

*Feature Documentation v5.2*
*Last Updated: March 2026*
