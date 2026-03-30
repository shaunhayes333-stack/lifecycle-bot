# AATE V4.1 - Autonomous AI Trading Engine

## What is AATE?

AATE is a **Native Android trading bot** for Solana that uses **25 parallel AI scoring layers** to make autonomous trading decisions. It's designed to trade memecoins, micro-caps, and established tokens 24/7 without human intervention.

---

# TRADING LAYERS

## Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    MARKET CAP ZONES                         │
├─────────────────────────────────────────────────────────────┤
│  $0 ──────── $30K ──────── $1M ──────── $10M+ ────────→    │
│     │                │              │                       │
│     ▼                ▼              ▼                       │
│  ┌──────┐      ┌──────────┐    ┌──────────┐                │
│  │SHIT  │  →   │V3 QUALITY│ →  │BLUE CHIP │                │
│  │COIN  │      │  LAYER   │    │  LAYER   │                │
│  └──────┘      └──────────┘    └──────────┘                │
│     │                                                       │
│     ▼                                                       │
│  ┌──────────┐                                              │
│  │SHITCOIN  │  (Momentum plays on micro-caps)              │
│  │EXPRESS   │                                              │
│  └──────────┘                                              │
│                                                             │
│  ┌──────────┐  (Cross-layer: buys quality dips)            │
│  │DIP HUNTER│                                              │
│  └──────────┘                                              │
│                                                             │
│  ┌──────────┐  (Quick 4% scalps for consistent profit)     │
│  │TREASURY  │                                              │
│  └──────────┘                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. ShitCoin Layer
**Target:** Ultra micro-caps < $30K market cap

| Setting | Value |
|---------|-------|
| Max Market Cap | $30,000 |
| Token Age | < 6 hours |
| Take Profit | 25-100% |
| Stop Loss | -10% |
| Max Hold | 15 minutes |
| Daily Loss Limit | 0.5 SOL |

**What it trades:** Brand new pump.fun launches, fresh Raydium pairs, early memecoins

---

## 2. ShitCoin Express
**Target:** Momentum plays on micro-caps already pumping

| Setting | Value |
|---------|-------|
| Max Market Cap | $30,000 |
| Entry Requirement | Already +5% and climbing |
| Buy Pressure | Must be ≥60% |
| Take Profit | 30-100% |
| Stop Loss | -8% |
| Max Hold | 10 minutes |

**Strategy:** Jump on rockets already in motion, ride for quick 30%+ gains

---

## 3. V3 Quality Layer
**Target:** Established low-caps $30K - $1M

| Setting | Value |
|---------|-------|
| Min Market Cap | $30,000 |
| Max Market Cap | $1,000,000 |
| Take Profit | 35% |
| Stop Loss | -12% |
| Max Hold | 60 minutes |

**What it trades:** Tokens that survived initial volatility, have real liquidity, showing quality setups

---

## 4. Blue Chip Layer
**Target:** Established tokens > $1M market cap

| Setting | Value |
|---------|-------|
| Min Market Cap | $1,000,000 |
| Min Liquidity | $50,000+ |
| Take Profit | 10-25% |
| Stop Loss | -5% |
| Max Hold | 30 minutes |

**What it trades:** Proven tokens with deep liquidity, lower risk setups

---

## 5. DipHunter AI
**Target:** Quality dips on established tokens

| Setting | Value |
|---------|-------|
| Dip Range | 15-55% from high |
| Ideal Dip | 25-40% |
| Danger Zone | >60% (rejected) |
| Target Recovery | +20% |
| Stop Loss | -15% |
| Daily Loss Limit | 0.2 SOL |

**Strategy:** Buy the dip on quality tokens, avoid falling knives

---

## 6. Treasury Mode
**Target:** Quick scalps for consistent daily profit

| Setting | Value |
|---------|-------|
| Take Profit | 4% |
| Min Profit | 3.5% |
| Max Profit | 8% |
| Stop Loss | -2% |
| Max Hold | 8 minutes |

**Strategy:** High-frequency small wins that compound into treasury growth

---

# LAYER TRANSITION SYSTEM

Tokens automatically graduate between layers as they grow:

```
Token launches at $5K
        │
        ▼
┌───────────────┐
│  SHITCOIN     │  Trades it with 25-100% targets
│  LAYER        │  
└───────┬───────┘
        │ Pumps to $35K
        ▼
┌───────────────┐
│  V3 QUALITY   │  Position handed off (not closed!)
│  LAYER        │  Now trades with 35% targets
└───────┬───────┘
        │ Pumps to $1.2M
        ▼
┌───────────────┐
│  BLUE CHIP    │  Position handed off again
│  LAYER        │  Now trades with tighter 10-25% targets
└───────────────┘
```

**Key Feature:** Positions transfer between layers WITHOUT closing, maintaining continuous P&L tracking.

---

# 25 AI SCORING LAYERS

Every trade decision passes through 25 independent AI modules:

| # | AI Layer | What It Does |
|---|----------|--------------|
| 1 | V3 Engine Manager | Central orchestration |
| 2 | Fluid Learning AI | Adaptive thresholds from history |
| 3 | Treasury Mode | Quick scalp detection |
| 4 | Blue Chip Trader | Quality large-cap analysis |
| 5 | ShitCoin Trader | Micro-cap degen plays |
| 6 | ShitCoin Express | Momentum detection |
| 7 | DipHunter AI | Dip quality analysis |
| 8 | Solana Arb AI | Cross-exchange arbitrage |
| 9 | Exit Intelligence | Optimal exit timing |
| 10 | Advanced Exit Manager | Multi-signal exits |
| 11 | Final Decision Gate | Last-chance quality veto |
| 12 | Distribution Fade Avoider | Whale/dev sell detection |
| 13 | Behavior Learning | Tilt & discipline tracking |
| 14 | Re-entry Recovery | Smart second-chance entries |
| 15 | Holding Logic | Position management |
| 16 | Layer Transition Manager | Token graduation |
| 17 | Sentiment Engine | Market mood |
| 18 | Safety Checker | Rug/scam detection |
| 19 | EMA Fan Analyzer | Trend strength |
| 20 | Volume Profile | Volume-price analysis |
| 21 | Pressure Score | Buy vs sell pressure |
| 22 | Exhaustion Detector | Overbought/oversold |
| 23 | Market Regime | Bull/bear/range detection |
| 24 | Collective Learning | Hive mind intelligence |
| 25 | Shadow Paper Trading | Background learning |

---

# RISK MANAGEMENT

## Hard Limits (Cannot Be Bypassed)

| Protection | Value |
|------------|-------|
| Hard Floor Stop | -15% absolute max loss |
| Velocity Exit | Exit if dropping >10% in 3 candles |
| Entry Block | Won't enter during rapid dumps |

## Trailing Stops (Lock In Profits)

| Peak Profit Seen | Minimum Locked In |
|------------------|-------------------|
| 8%+ | 2% profit guaranteed |
| 15%+ | 5% profit guaranteed |
| 25%+ | 10% profit guaranteed |

## Re-entry Protection

| Setting | Value |
|---------|-------|
| Cooldown after failure | 2 minutes |
| Score threshold | 65% |
| Max attempts | 2 |
| Penalty per attempt | -5 score |
| Position size | 1st: 50%, 2nd: 30% |

---

# FLUID LEARNING AI

The bot starts strict and loosens as it learns:

| Phase | Trades | Behavior |
|-------|--------|----------|
| Bootstrap | 0-100 | Ultra-tight limits, tiny positions |
| Learning | 100-500 | Gradually loosening |
| Mature | 500-1000 | Optimized from history |
| Expert | 1000+ | Full autonomy |

Each layer learns independently based on its own win rates and performance.

---

# COLLECTIVE HIVE MIND

All AATE instances share intelligence:

- Trades uploaded to shared database
- Rug pull warnings shared instantly
- Network-wide pattern detection
- Learn from others' successes and failures

---

# SECURITY

- Biometric/PIN authentication required
- 3 failed attempts = app closes
- PIN hashed with SHA-256
- No bypass possible

---

# TECHNICAL SPECS

| Spec | Value |
|------|-------|
| Platform | Native Android (Kotlin) |
| Min Android | 8.0 (SDK 26) |
| Target Android | 14 (SDK 34) |
| Build System | GitHub Actions CI |
| Local DB | SharedPreferences |
| Remote DB | Turso LibSQL |
| Price APIs | DexScreener, Birdeye, Pump.fun |

---

# V4.1 CHANGELOG

## Build Stability
- Extracted `processTokenCycle()` to fix compiler StackOverflow
- botLoop reduced from 2600 to 825 lines
- CI now builds reliably

## Trading Improvements
- Layer ranges adjusted: ShitCoin <$30K, V3 $30K-$1M
- Treasury target lowered to 4% (catch more quick trades)
- Velocity detection prevents 30%+ losses
- Smarter trailing stops lock in profits

## Re-entry System
- 2 minute cooldown (balanced, not too strict)
- 65% score threshold
- -5 penalty per previous attempt
- Max 2 attempts allowed

---

*AATE V4.1 - Built for Solana Traders*
