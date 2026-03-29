# AATE V4.1 - Full Feature List

## Autonomous AI Trading Engine for Solana

**AATE** (Autonomous AI Trading Engine) is a Native Kotlin Android trading bot that uses 25 parallel AI scoring layers, fluid machine learning, and intelligent multi-layer architecture to trade Solana tokens automatically.

---

# CORE ARCHITECTURE

## 25 Parallel AI Scoring Layers
Every trade decision passes through 25 independent AI modules that vote on entry/exit:

| # | AI Layer | Purpose |
|---|----------|---------|
| 1 | V3 Engine Manager | Central orchestration & candidate scoring |
| 2 | Fluid Learning AI | Adaptive thresholds based on trade history |
| 3 | Treasury Mode (Cash Gen) | Quick 4% scalps for consistent profit |
| 4 | Blue Chip Trader | Quality trades on >$1M mcap tokens |
| 5 | ShitCoin Trader | Micro-cap (<$30K) degen plays |
| 6 | ShitCoin Express | Ultra-aggressive 30%+ momentum rides |
| 7 | DipHunter AI | Buy quality dips, avoid falling knives |
| 8 | Solana Arb AI | Cross-exchange SOL arbitrage |
| 9 | Exit Intelligence | Smart exit timing & trailing stops |
| 10 | Advanced Exit Manager | Multi-signal exit coordination |
| 11 | Final Decision Gate | Last-chance veto with quality checks |
| 12 | Distribution Fade Avoider | Detects whale/dev selling |
| 13 | Behavior Learning | Tracks tilt, discipline, patterns |
| 14 | Re-entry Recovery Mode | Smart second-chance entries |
| 15 | Holding Logic Layer | Position management & mode switching |
| 16 | Layer Transition Manager | Token graduation between layers |
| 17 | Sentiment Engine | Market mood analysis |
| 18 | Safety Checker | Rug pull & scam detection |
| 19 | EMA Fan Analyzer | Trend strength via EMA alignment |
| 20 | Volume Profile | Volume-price relationship |
| 21 | Pressure Score | Buy vs sell pressure |
| 22 | Exhaustion Detector | Overbought/oversold signals |
| 23 | Market Regime Detector | Bull/bear/ranging classification |
| 24 | Collective Learning | Hive mind shared intelligence |
| 25 | Shadow Paper Trading | Background learning without risk |

---

# TRADING LAYERS

## 1. Treasury Mode (Cash Generation AI)
**Purpose:** Consistent daily profit machine feeding back into capital

| Feature | Value |
|---------|-------|
| Target | 4% quick scalps |
| Min Profit | 3.5% (covers fees) |
| Max Profit | 8% (exit ceiling) |
| Stop Loss | -2% (ultra-tight) |
| Max Hold | 8 minutes |
| Max Positions | 4 concurrent |

**Modes:** DEFENSIVE → CRUISE → AGGRESSIVE (based on daily P&L)

---

## 2. Blue Chip Trader
**Purpose:** Quality trades on established tokens (>$1M mcap)

| Feature | Value |
|---------|-------|
| Min Market Cap | $1,000,000 |
| Min Liquidity | $50K-$75K |
| Take Profit | 10-25% |
| Stop Loss | -5% |
| Max Hold | 30 minutes |
| Max Positions | 3 concurrent |

---

## 3. ShitCoin Trader (Degen Mode)
**Purpose:** High-risk true micro-cap memecoins (<$30K mcap)

| Feature | Value |
|---------|-------|
| Max Market Cap | $30,000 |
| Token Age | < 6 hours |
| Take Profit | 25-100% (fluid) |
| Stop Loss | -10% |
| Max Hold | 15 minutes |
| Max Positions | 5 concurrent |
| Daily Max Loss | 0.5 SOL |

**Platform Detection:** Pump.fun, Raydium, Moonshot, BonkBot

---

## 4. ShitCoin Express
**Purpose:** Ultra-aggressive momentum hunting for 30%+ moves on micro-caps

| Feature | Value |
|---------|-------|
| Max Market Cap | $30,000 |
| Entry Requirement | Already pumping 5%+ |
| Buy Pressure Required | >= 60% |
| Stop Loss | -8% (ultra-tight) |
| Trailing Stop | -5% |
| Max Hold | 10 minutes |
| Daily Max Rides | 20 |

**Ride Types:** QUICK_FLIP (30%) → MOMENTUM_RIDE (50%) → MOONSHOT_EXPRESS (100%)

---

## 5. DipHunter AI
**Purpose:** Buy quality dips on established tokens, avoid falling knives

| Feature | Value |
|---------|-------|
| Target Dip Range | 15-55% from high |
| Ideal Dip | 25-40% |
| Danger Zone | >60% (auto-reject) |
| Target Recovery | +20% |
| Stop Loss | -15% |
| Max Hold | 6 hours |
| Daily Max Loss | 0.2 SOL |

**Quality Classification:** GOLDEN_DIP → QUALITY_DIP → RISKY_DIP → FALLING_KNIFE

---

## 6. Solana Arbitrage AI
**Purpose:** Cross-exchange SOL price arbitrage

| Feature | Value |
|---------|-------|
| Min Treasury | $500 |
| Min Spread | 15 bps (0.15%) |
| Max Hold | 60 seconds |
| Daily Max Loss | $50 |

**Exchanges:** Jupiter, Raydium, Orca, Binance, Coinbase, Kraken

---

# LAYER TRANSITION SYSTEM

Tokens automatically graduate between layers as they grow:

```
ShitCoin (<$30K) → V3 Quality ($30K-$1M) → Blue Chip (>$1M)
```

**Features:**
- Position handoff without closing
- Continuous P&L tracking across layers
- Automatic threshold adjustment

---

# FLUID LEARNING AI

**Adaptive thresholds** that start strict (bootstrap) and loosen as the bot learns:

| Phase | Trades | Behavior |
|-------|--------|----------|
| Bootstrap | 0-100 | Strict thresholds, small positions |
| Learning | 100-500 | Gradually loosening |
| Mature | 500-1000 | Optimized from history |
| Expert | 1000+ | Full autonomy |

**Per-Layer Adaptation:**
- Each layer learns independently
- Win rates, avg profit, drawdown tracked
- Thresholds self-tune based on performance

---

# RISK MANAGEMENT

## Hard Limits
| Protection | Value |
|------------|-------|
| Hard Floor Stop | -15% (NEVER exceeded) |
| Daily Loss Limit | Per-layer caps |
| Max Position Size | Layer-specific |
| Max Concurrent | Layer-specific |

## Velocity Detection (V4.1 NEW)
- Exit if price dropping >10% in 3 candles
- Exit if at -10% loss AND still accelerating down
- Block entry during rapid dumps (>5% drop in 3 candles)

## Trailing Stops (V4.1 IMPROVED)
| Peak Profit | Minimum Locked |
|-------------|----------------|
| 8%+ | 2% profit minimum |
| 15%+ | 5% profit minimum |
| 25%+ | 10% profit minimum |

---

# SMART RE-ENTRY (V4.1)

**Prevents losing money on repeated entries:**

| Setting | Value |
|---------|-------|
| Cooldown | 10 minutes (was 2) |
| Score Threshold | 80% (was 60%) |
| Max Attempts | 1 (was 2) |
| Position Size | 40% of normal |
| Max Hold | 30 minutes |

**Auto-Block if:**
- Token collapsed >20% since failure
- No sustained buy pressure (3+ candles)
- Liquidity < $5000

---

# SECURITY

## Biometric/PIN Authentication
- First launch: Set 4-6 digit PIN
- Subsequent: Fingerprint OR PIN
- 3 failed attempts = App closes
- PIN hashed with SHA-256
- No bypass via back button

---

# COLLECTIVE LEARNING (HIVE MIND)

**Shared intelligence across all AATE instances:**

- All trades uploaded to Turso LibSQL
- Network-wide pattern detection
- Rug pull warnings shared instantly
- Collective win rate tracking
- Shadow learning from others' trades

---

# UI FEATURES

## Main Dashboard
- Live wallet balance (SOL + fiat)
- Live SOL/USD price
- Paper/Live mode indicator
- 23 currency options

## Position Panels (Separate for each layer)
- Open Positions (V3 main)
- Treasury Scalps
- Blue Chip Trades
- ShitCoin Degen

## AI Status Panel
- All 25 AI layers status
- Mode indicators per layer
- Win/loss tracking
- Daily P&L per layer

## Quick Action Tiles
- AI Brain status
- Shadow Learning toggle
- Market Regimes view
- Treasury Mode control

---

# V4.1 UPDATE HIGHLIGHTS

## Build Stability
- Extracted `processToken()` to fix compiler StackOverflowError
- Extracted `initTradingModes()` for cleaner code
- CI now builds reliably

## Loss Prevention
- **Velocity Exit:** Catches rapid dumps before -30%
- **Entry Velocity Block:** Won't enter during active dumps
- **Smarter Trailing:** Locks in profits more aggressively

## Re-entry Intelligence
- 10 minute cooldown (was 2)
- Must show SUSTAINED buy pressure
- Only ONE chance per token
- Smaller position size (40%)

## Treasury Optimization
- Profit target: 7% → 4% (catch more trades)
- Minimum profit: 5% → 3.5%
- Maximum profit: 10% → 8%

## DipHunter Safety
- Added daily loss limit (0.2 SOL)
- Added daily hunt limit (15)
- Tightened position sizes
- Reduced concurrent dips (4 → 3)

---

# TECHNICAL SPECS

| Spec | Value |
|------|-------|
| Platform | Native Android (Kotlin) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| Database | Local SharedPreferences + Remote Turso LibSQL |
| APIs | DexScreener, Birdeye, Pump.fun, Jupiter |
| Build | GitHub Actions CI |
| Source Files | 182 Kotlin files |

---

*AATE V4.1 - December 2025*
