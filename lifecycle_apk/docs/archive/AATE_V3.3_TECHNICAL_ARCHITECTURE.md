# AATE V3.3 - Complete Technical Architecture (Sentient Trading)

## TRADING MODES (19 Total + Treasury)

| # | Mode | Emoji | Risk | Description |
|---|------|-------|------|-------------|
| 1 | STANDARD | 📈 | 3 | Balanced quality gates |
| 2 | MOONSHOT | 🚀 | 4 | Early high-potential detection |
| 3 | PUMP_SNIPER | 🔫 | 5 | Viral pump detection |
| 4 | COPY_TRADE | 🦊 | 3 | Whale wallet following (DISABLED) |
| 5 | LONG_HOLD | 💎 | 2 | Diamond hands - long-term conviction |
| 6 | BLUE_CHIP | 🔵 | 1 | Safe treasury growth |
| 7 | CYCLIC | ♻️ | 2 | Pattern-based trading |
| 8 | SLEEPER | 💤 | 4 | Dormant token revivals |
| 9 | NICHE | 🧬 | 4 | Low supply opportunities |
| 10 | PRESALE_SNIPE | 🎯 | 5 | First-block entry |
| 11 | ARBITRAGE | ⚡ | 2 | Cross-DEX spreads |
| 12 | MOMENTUM_SWING | 🌊 | 3 | Strong trend following |
| 13 | MICRO_CAP | 🔬 | 5 | Ultra-small mcap plays |
| 14 | REVIVAL | 🔥 | 4 | Phoenix - crashed token recovery |
| 15 | WHALE_FOLLOW | 🐋 | 3 | Smart money tracking (RESTRICTED) |
| 16 | PUMP_DUMP | 💣 | 5 | Aggressive accumulation |
| 17 | MARKET_MAKER | 🏛️ | 2 | Spread profit capture |
| 18 | LIQUIDATION_HUNTER | 🦅 | 4 | Distressed selling |
| 19 | TREASURY | 💰 | 1 | Conservative cash generation |

---

## 24 AI SCORING LAYERS

### Layer 1-14: Base Scoring Modules

| Layer | AI Module | Function | Score Range |
|-------|-----------|----------|-------------|
| 1 | SourceReliabilityAI | Rates signal source quality | -10 to +15 |
| 2 | EntryTimingAI | Perfect entry timing detection | -15 to +20 |
| 3 | MomentumPredictorAI | Price trajectory analysis | -10 to +15 |
| 4 | LiquidityDepthAI | Liquidity pool analysis | -20 to +10 |
| 5 | VolumeProfileAI | Volume pattern analysis | -10 to +15 |
| 6 | HolderSafetyAI | Rug pull detection (6 checks) | -30 to +5 |
| 7 | NarrativeDetectorAI | Viral trend detection | -5 to +20 |
| 8 | TokenWinMemoryAI | Historical performance | -15 to +15 |
| 9 | MarketRegimeAI | Market conditions | -10 to +10 |
| 10 | TimeOptimizationAI | Best trading hours | -5 to +10 |
| 11 | CopyTradeAI | Whale wallet signals | 0 (DISABLED) |
| 12 | SuppressionDetectorAI | Market manipulation detection | -25 to 0 |
| 13 | FearGreedAI | Sentiment analysis | -10 to +10 |
| 14 | SocialVelocityAI | Social momentum | -5 to +15 |

### Layer 15-19: V3.2 Advanced Modules

| Layer | AI Module | Function | Score Range |
|-------|-----------|----------|-------------|
| 15 | VolatilityRegimeAI | Volatility patterns & squeezes | -10 to +15 |
| 16 | OrderFlowImbalanceAI | Order book analysis | -10 to +10 |
| 17 | SmartMoneyDivergenceAI | Institutional tracking | -15 to +20 |
| 18 | HoldTimeOptimizerAI | Exit timing optimization | -5 to +10 |
| 19 | LiquidityCycleAI | Market-wide cycles | -10 to +10 |

### Layer 20-22: Meta & Collective Intelligence

| Layer | AI Module | Function | Score Range |
|-------|-----------|----------|-------------|
| 20 | CollectiveIntelligenceAI | Hive mind synthesis | -20 to +20 |
| 21 | MetaCognitionAI | Self-aware oversight | -10 to +15 |
| 22 | CashGenerationAI | Treasury mode scoring | Pass/Fail |

### Layer 23-24: V3.3 Sentient Trading (NEW)

| Layer | AI Module | Function | Score Range |
|-------|-----------|----------|-------------|
| 23 | **FluidLearningAI** | Centralized adaptive thresholds | N/A (Controller) |
| 24 | **SellOptimizationAI** | Intelligent exit strategies | N/A (Exit Logic) |

#### FluidLearningAI Details (Layer 23)
- **Purpose**: Centralizes ALL adaptive thresholds in one location
- **Scaling**: 30% confidence (Day 1) → 75-80% (mature bot)
- **Methods**:
  - `getLearningProgress()` - Returns 0.0 to 1.0 based on lifetime trades
  - `lerp(loose, strict)` - Interpolates between loose and strict values
  - `getTreasuryConfidenceThreshold()` - Fluid confidence for Treasury Mode
  - `getMinScoreThreshold()` - Fluid minimum score for entries
  - `getRugFilterLiq*()` - Fluid liquidity floors for rug detection

#### SellOptimizationAI Details (Layer 24)
- **Purpose**: Intelligent profit-taking and exit management
- **Exit Strategies**:
  - `HOLD` - Keep position
  - `CHUNK_25` - Sell 25% at first profit milestone
  - `CHUNK_50` - Sell 50% at second milestone
  - `CHUNK_75` - Sell 75% at third milestone
  - `FULL_EXIT` - Close entire position
  - `TRAILING_LOCK` - Set trailing stop at current level
- **Urgency Levels**: NONE, LOW, MEDIUM, HIGH, CRITICAL
- **Tracking**: Maintains chunk state per position for coordinated exits

---

## FLUID LEARNING SYSTEM (V3.3 NEW)

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│                    FLUID LEARNING ARCHITECTURE                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  Lifetime Trade │
                    │     Counter     │
                    └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
        ┌─────────┐    ┌─────────────┐   ┌─────────┐
        │ 0-50    │    │  50-200     │   │ 200+    │
        │ trades  │    │  trades     │   │ trades  │
        │ (FRESH) │    │ (LEARNING)  │   │ (MATURE)│
        └────┬────┘    └──────┬──────┘   └────┬────┘
              │               │               │
              ▼               ▼               ▼
        ┌─────────┐    ┌─────────────┐   ┌─────────┐
        │  LOOSE  │    │   SCALING   │   │ STRICT  │
        │   30%   │    │   30-75%    │   │  75-80% │
        │ thresholds│  │ thresholds  │   │thresholds│
        └─────────┘    └─────────────┘   └─────────┘
```

### Threshold Categories

| Category | Fresh (0-50) | Learning (50-200) | Mature (200+) |
|----------|--------------|-------------------|---------------|
| Confidence | 30% | 30% → 75% | 75-80% |
| Min Score | 15 | 15 → 40 | 40+ |
| Liquidity Floor | $2,000 | $2K → $15K | $15,000+ |
| Top Holder Max | 25% | 25% → 12% | 12% |
| Buy Pressure Min | 40% | 40% → 58% | 58%+ |

---

## SELL OPTIMIZATION SYSTEM (V3.3 NEW)

### Exit Strategy Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                 SELL OPTIMIZATION AI FLOW                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ Position Open   │
                    │ Current PnL: X% │
                    └────────┬────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ SellOptimization│
                    │ AI.evaluate()   │
                    └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐───────────────┐
              │               │               │               │
              ▼               ▼               ▼               ▼
        ┌─────────┐    ┌─────────────┐ ┌────────────┐  ┌──────────┐
        │PnL < 15%│    │PnL 15-30%   │ │PnL 30-50%  │  │PnL > 50% │
        │  HOLD   │    │ CHUNK_25    │ │ CHUNK_50   │  │ CHUNK_75 │
        └─────────┘    └──────┬──────┘ └─────┬──────┘  └────┬─────┘
                              │              │              │
                              ▼              ▼              ▼
                       ┌─────────────────────────────────────────┐
                       │ Sell X% of position, keep remainder     │
                       │ Update chunk state in SellOptimizationAI│
                       │ Set trailing stop on remaining          │
                       └─────────────────────────────────────────┘
```

### Urgency Classification

| Urgency | Condition | Action |
|---------|-----------|--------|
| NONE | Position healthy | Continue holding |
| LOW | Minor warning signs | Monitor closely |
| MEDIUM | Profit targets approaching | Prepare chunk sell |
| HIGH | Strong sell signal | Execute chunk immediately |
| CRITICAL | Danger (rug/dump detected) | Full exit NOW |

---

## EXECUTION FLOWS

### BUY FLOWS

```
┌─────────────────────────────────────────────────────────────────┐
│                        BUY DECISION TREE                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  Token Detected  │
                    │  (Scanner/Feed)  │
                    └────────┬────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ V3 Engine Score │
                    │ (22 AI Layers)  │
                    └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
        ┌─────────┐    ┌─────────────┐   ┌─────────┐
        │ EXECUTE │    │    WATCH    │   │ REJECT  │
        │ Score>X │    │ Score=X-Y   │   │ Score<Y │
        └────┬────┘    └──────┬──────┘   └─────────┘
              │               │
              │               ▼
              │        ┌─────────────────┐
              │        │ Treasury Mode   │
              │        │ Evaluation      │
              │        │ (CashGenAI)     │
              │        └────────┬────────┘
              │                 │
              │     ┌───────────┴───────────┐
              │     │                       │
              ▼     ▼                       ▼
        ┌─────────────────┐           ┌─────────┐
        │   Paper Mode?    │           │  SKIP   │
        └────────┬────────┘           └─────────┘
                  │
        ┌─────────┴─────────┐
        │                   │
        ▼                   ▼
  ┌───────────┐       ┌───────────┐
  │ paperBuy()│       │ liveBuy() │
  │ (Simulated)│      │ (On-Chain)│
  └───────────┘       └─────┬─────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ Jupiter Swap    │
                    │ + Jito (MEV)    │
                    └─────────────────┘
```

### SELL FLOWS

```
┌─────────────────────────────────────────────────────────────────┐
│                       SELL DECISION TREE                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ Position Open   │
                    │ Price Update    │
                    └────────┬────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
     ┌────────────────┐ ┌──────────────┐ ┌────────────┐
     │ Treasury Mode? │ │ Mode-Specific│ │ Emergency  │
     │ (Quick Scalp)  │ │ Exit Logic   │ │ Stop Loss  │
     └───────┬────────┘ └──────┬───────┘ └─────┬──────┘
              │                 │               │
              ▼                 ▼               ▼
     ┌────────────────┐ ┌──────────────┐ ┌────────────┐
     │ CashGenAI      │ │ HoldLogic    │ │ Hard -X%   │
     │ checkExit()    │ │ Layer        │ │ Trigger    │
     └───────┬────────┘ └──────┬───────┘ └─────┬──────┘
              │                 │               │
              └────────────────┬┴───────────────┘
                               │
                               ▼
                    ┌─────────────────┐
                    │   Paper Mode?    │
                    └────────┬────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
              ▼                               ▼
        ┌───────────┐                   ┌───────────┐
        │paperSell()│                   │ liveSell()│
        │(Simulated)│                   │(On-Chain) │
        └───────────┘                   └─────┬─────┘
                                               │
                                               ▼
                                    ┌─────────────────┐
                                    │ Jupiter Swap    │
                                    │ + Dust Buster   │
                                    └─────────────────┘
```

---

## FINAL DECISION GATE (FDG)

### Block Levels

| Level | Type | Description | Recovery |
|-------|------|-------------|----------|
| FATAL | Rug indicators, zero liquidity | Permanent block | None |
| HARD | Extreme sell pressure | Cooldown | 30 min |
| SOFT | Low confidence, edge skip | Retry allowed | Immediate |
| INFO | Warning only | No block | N/A |

### FDG Blocked Trade Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    FDG BLOCKED TRADE HANDLING                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  Trade Proposed  │
                    └────────┬────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │      FDG        │
                    │  Evaluation     │
                    └────────┬────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
              ▼                               ▼
        ┌───────────┐                   ┌───────────┐
        │ APPROVED  │                   │ BLOCKED   │
        └─────┬─────┘                   └─────┬─────┘
              │                               │
              ▼                               ▼
        ┌───────────┐                   ┌─────────────────┐
        │  Execute  │                   │ Shadow Learning │
        │  Trade    │                   │ Engine          │
        └───────────┘                   └────────┬────────┘
                                                  │
                                                  ▼
                                        ┌─────────────────┐
                                        │ Track Virtually │
                                        │ (Paper Sim)     │
                                        └────────┬────────┘
                                                  │
                                                  ▼
                                        ┌─────────────────┐
                                        │ Compare Outcome │
                                        │ vs Block Reason │
                                        └────────┬────────┘
                                                  │
                              ┌───────────────────┴───────────────┐
                              │                                   │
                              ▼                                   ▼
                    ┌─────────────────┐                 ┌─────────────────┐
                    │ Blocked = Win   │                 │ Blocked = Loss  │
                    │ (FDG too strict)│                 │ (FDG correct)   │
                    └────────┬────────┘                 └────────┬────────┘
                              │                                   │
                              ▼                                   ▼
                    ┌─────────────────┐                 ┌─────────────────┐
                    │ Log for Review  │                 │ Confirm Block   │
                    │ Adjust Threshold│                 │ Pattern         │
                    └─────────────────┘                 └─────────────────┘
```

---

## TREASURY MODE (CashGenerationAI)

### Operating Modes

| Mode | Condition | Position Size | Take Profit |
|------|-----------|---------------|-------------|
| HUNT | < 50% of daily target | 100% base | 7% |
| CRUISE | 50-80% of target | 70% base | 7% |
| DEFENSIVE | > 80% of target | 40% base | 5% |
| AGGRESSIVE | Negative P&L | 115% base | 10% |
| PAUSED | Hit $50 max loss | 0% | N/A |

### Entry Criteria (A-Grade Only)

- Confidence >= 80%
- Score >= 30
- Liquidity >= $15,000
- Top holder < 12%
- Buy pressure >= 58%
- Max 4 concurrent positions

### Exit Strategy

- Take Profit: 5-10% (mode dependent)
- Stop Loss: -2% (HARD)
- Trailing Stop: 2% (after profit)
- Max Hold: 8 minutes
- Early Exit: 5%+ profit after 4 mins

### Separate Paper/Live Tracking

```
┌─────────────────────────────────────────────────────────────────┐
│                  TREASURY BALANCE TRACKING                       │
└─────────────────────────────────────────────────────────────────┘

  PAPER MODE                              LIVE MODE
  ══════════                              ═════════
  ┌─────────────────┐                     ┌─────────────────┐
  │ paperTreasury   │                     │ liveTreasury    │
  │ Balance: X SOL  │                     │ Balance: Y SOL  │
  ├─────────────────┤                     ├─────────────────┤
  │ Daily P&L: A    │                     │ Daily P&L: B    │
  │ Wins: N         │                     │ Wins: M         │
  │ Losses: L       │                     │ Losses: K       │
  │ Mode: HUNT      │                     │ Mode: CRUISE    │
  └─────────────────┘                     └─────────────────┘
           │                                       │
           └───────────────┬───────────────────────┘
                           │
                           ▼
                 ┌─────────────────┐
                 │  Mode Switch    │
                 │  (Paper ↔ Live) │
                 └────────┬────────┘
                           │
                           ▼
                 ┌─────────────────┐
                 │ Display Correct │
                 │ Balance for     │
                 │ Current Mode    │
                 └─────────────────┘
```

---

## COLLECTIVE INTELLIGENCE FLOW

```
┌─────────────────────────────────────────────────────────────────┐
│                  COLLECTIVE BRAIN ARCHITECTURE                   │
└─────────────────────────────────────────────────────────────────┘

  AATE Instance 1        AATE Instance 2        AATE Instance N
  ═══════════════        ═══════════════        ═══════════════
  ┌─────────────┐        ┌─────────────┐        ┌─────────────┐
  │ Local Trade │        │ Local Trade │        │ Local Trade │
  │ History     │        │ History     │        │ History     │
  └──────┬──────┘        └──────┬──────┘        └──────┬──────┘
         │                      │                      │
         └──────────────────────┼──────────────────────┘
                                │
                                ▼
                 ┌──────────────────────────┐
                 │      TURSO DATABASE       │
                 │   (Shared Collective)     │
                 ├──────────────────────────┤
                 │ • token_patterns         │
                 │ • mode_performance       │
                 │ • instance_heartbeats    │
                 │ • trade_signals          │
                 │ • blacklisted_tokens     │
                 └────────────┬─────────────┘
                              │
                              ▼
                 ┌──────────────────────────┐
                 │ CollectiveIntelligenceAI │
                 ├──────────────────────────┤
                 │ INTERPRETATION:          │
                 │ • Pattern quality scoring│
                 │ • Cross-instance signals │
                 │ • Mode aggregation       │
                 ├──────────────────────────┤
                 │ MAINTENANCE:             │
                 │ • Prune stale (>7 days)  │
                 │ • Deduplicate            │
                 │ • Anomaly detection      │
                 ├──────────────────────────┤
                 │ INTELLIGENCE:            │
                 │ • Token success predict  │
                 │ • Dynamic thresholds     │
                 │ • Consensus scoring      │
                 └────────────┬─────────────┘
                              │
                              ▼
                 ┌──────────────────────────┐
                 │ Feed into UnifiedScorer  │
                 │ as Layer 20 Score        │
                 │ (-20 to +20 adjustment)  │
                 └──────────────────────────┘
```

---

## EXECUTOR METHODS SUMMARY

| Method | Mode | Description |
|--------|------|-------------|
| `v3Buy()` | Both | V3 Engine buy (routes to paper/live) |
| `treasuryBuy()` | Both | Treasury mode buy (routes to paper/live) |
| `paperBuy()` | Paper | Simulated buy, updates local state |
| `liveBuy()` | Live | On-chain Jupiter swap |
| `requestSell()` | Both | Request sell (routes appropriately) |
| `paperSell()` | Paper | Simulated sell, updates local state |
| `liveSell()` | Live | On-chain Jupiter swap + dust buster |

---

## MARKET REGIMES (7 Total)

| Regime | Condition | Modes Favored |
|--------|-----------|---------------|
| BULL_STRONG | BTC up, high volume | MOONSHOT, MOMENTUM_SWING |
| BULL_WEAK | BTC up, low volume | STANDARD, CYCLIC |
| BEAR_STRONG | BTC down, high selling | TREASURY, BLUE_CHIP |
| BEAR_WEAK | BTC down, low activity | LONG_HOLD, CYCLIC |
| SIDEWAYS | Choppy, no trend | ARBITRAGE, MARKET_MAKER |
| VOLATILE | High swings both ways | PUMP_SNIPER, MICRO_CAP |
| UNCERTAIN | Mixed signals | STANDARD (safe default) |

---

## DATA PERSISTENCE

### Local (SharedPreferences)
- Trade history (last 1000 trades)
- Mode statistics
- Position state
- Config settings

### Cloud (Turso)
- Collective patterns
- Mode performance
- Instance heartbeats
- Blacklisted tokens
- Legal agreements

---

## BUILD & DEPLOYMENT

```
GitHub Push → GitHub Actions CI → Gradle Build → APK Release
                     │
                     ├── Lint checks
                     ├── Kotlin compile
                     ├── Sign APK
                     └── Upload artifact
```

---

*Document Version: V3.3 (Sentient Trading)*
*Last Updated: March 2026*
*Total AI Layers: 24*
*Total Trading Modes: 19 + Treasury*
