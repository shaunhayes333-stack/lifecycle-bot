# AATE V5.2 - Complete AI Layer Reference

## Overview

AATE implements **28 specialized AI layers** that work in concert to evaluate, enter, manage, and exit trades. Each layer has a specific responsibility and communicates with other layers via the CrossTalk system.

---

## Layer Categories

### 1. Trading Layers (Core Execution)

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **CashGenerationAI** | `CashGenerationAI.kt` | 1,067 | Treasury scalping - self-funding operations |
| **ShitCoinTraderAI** | `ShitCoinTraderAI.kt` | 1,058 | Meme/degen token specialist |
| **QualityTraderAI** | `QualityTraderAI.kt` | 417 | Professional mid-cap trading ($100K-$1M) |
| **BlueChipTraderAI** | `BlueChipTraderAI.kt` | 674 | Large cap trading ($1M+) |
| **MoonshotTraderAI** | `MoonshotTraderAI.kt` | 829 | 100%+ gain hunter |
| **ShitCoinExpress** | `ShitCoinExpress.kt` | 627 | Fast-track meme entries |
| **DipHunterAI** | `DipHunterAI.kt` | 673 | Oversold bounce detector |

### 2. Learning & Adaptation Layers

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **FluidLearningAI** | `FluidLearningAI.kt` | 1,224 | Adaptive thresholds that evolve |
| **BehaviorAI** | `BehaviorAI.kt` | 864 | Tracks and corrects bad patterns |
| **MetaCognitionAI** | `MetaCognitionAI.kt` | 739 | AI that monitors other AIs |
| **CollectiveIntelligenceAI** | `CollectiveIntelligenceAI.kt` | 914 | Cross-device learning sync |
| **EducationSubLayerAI** | `EducationSubLayerAI.kt` | 885 | Harvard Brain education feed |

### 3. Exit & Risk Management Layers

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **AdvancedExitManager** | `AdvancedExitManager.kt` | 478 | Multi-factor exit decisions |
| **SellOptimizationAI** | `SellOptimizationAI.kt` | 540 | Exit timing optimization |
| **HoldTimeOptimizerAI** | `HoldTimeOptimizerAI.kt` | 582 | Dynamic hold duration |
| **UltraFastRugDetectorAI** | `UltraFastRugDetectorAI.kt` | 614 | Sub-second rug detection |

### 4. Market Analysis Layers

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **RegimeTransitionAI** | `RegimeTransitionAI.kt` | 686 | Market regime detection |
| **OrderFlowImbalanceAI** | `OrderFlowImbalanceAI.kt` | 472 | Buy/sell pressure analysis |
| **LiquidityCycleAI** | `LiquidityCycleAI.kt` | 485 | Liquidity pattern recognition |
| **SmartMoneyDivergenceAI** | `SmartMoneyDivergenceAI.kt` | - | Whale movement tracking |
| **FearGreedAI** | `FearGreedAI.kt` | - | Market sentiment gauge |
| **SocialVelocityAI** | `SocialVelocityAI.kt` | - | Social momentum detection |

### 5. Arbitrage & Coordination Layers

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **SolanaArbAI** | `SolanaArbAI.kt` | 533 | Cross-venue arbitrage |
| **LayerTransitionManager** | `LayerTransitionManager.kt` | - | Token promotion/demotion |
| **UnifiedScorer** | `UnifiedScorer.kt` | - | Final score aggregation |
| **ScoreCard** | `ScoreCard.kt` | - | Trade grading system |
| **NarrativeDetectorAI** | `NarrativeDetectorAI.kt` | - | Trending narrative identification |

---

## Layer Communication

### CrossTalk System

All layers communicate via `AICrossTalk.kt` (924 lines):

```kotlin
// Signal types
enum class SignalType {
    ENTRY_OPPORTUNITY,
    EXIT_WARNING,
    RUG_ALERT,
    REGIME_CHANGE,
    LAYER_OVERRIDE,
    CONFIDENCE_BOOST,
    CONFIDENCE_PENALTY
}

// Example broadcast
CrossTalk.broadcast(CrossTalkSignal(
    source = "UltraFastRugDetectorAI",
    type = SignalType.RUG_ALERT,
    mint = token.mint,
    confidence = 0.95,
    message = "LIQUIDITY DRAIN DETECTED - EXIT IMMEDIATELY"
))
```

### Layer Dependencies

```
FluidLearningAI
    └── Provides thresholds to ALL trading layers

MetaCognitionAI  
    └── Adjusts weights of ALL scoring layers

BehaviorAI
    └── Penalizes entries matching bad patterns

CollectiveIntelligenceAI
    └── Syncs learnings across device fleet

EducationSubLayerAI
    └── Records outcomes for Harvard Brain
    └── Feeds back to FluidLearningAI
```

---

## Layer Details

### FluidLearningAI (1,224 lines)

**The Adaptive Brain**

This is the most important layer. It makes everything else adaptive.

**Key Functions:**
- `getLearningProgress()`: Returns 0.0-1.0 based on trade count
- `getFluidTakeProfit(layer)`: Returns current TP% for layer
- `getFluidStopLoss(layer)`: Returns current SL% for layer
- `adjustThresholds(trade)`: Updates thresholds based on outcome

**Evolution Example:**
```
Treasury TP%:  3.0 → 8.0  (over 1000 trades)
ShitCoin TP%:  8.0 → 25.0
Quality TP%:  15.0 → 50.0
BlueChip TP%: 25.0 → 100.0
```

---

### BehaviorAI (864 lines)

**The Pattern Police**

Tracks and corrects destructive trading patterns.

**Bad Behaviors Tracked:**
- `PANIC_SELL`: Exiting profitable trades too early
- `FOMO_ENTRY`: Entering after significant pumps
- `REVENGE_TRADE`: Trading to recover losses
- `OVERTRADING`: Too many trades in short period
- `IGNORING_STOPS`: Not honoring stop losses
- `CHASING_LOSSES`: Increasing size after losses

**Impact:**
- Detected behaviors add entry penalties
- Repeat offenses increase penalty severity
- Penalties decay over time with good behavior

---

### UltraFastRugDetectorAI (614 lines)

**The Guardian**

Sub-second detection of rug pulls and scams.

**Detection Signals:**
- Liquidity drain > 50% in short period
- Dev wallet mass selling
- Price collapse > 30% in seconds
- Honeypot characteristics (can't sell)
- Mass holder exodus

**Response:**
- Broadcasts RUG_ALERT to all layers
- Triggers immediate exit for affected positions
- Blacklists token for future entries

---

### MetaCognitionAI (739 lines)

**The AI Supervisor**

Monitors performance of all other AI layers and adjusts their influence.

**Functions:**
- Tracks win rate per layer
- Tracks average P&L per layer
- Adjusts layer weights dynamically
- Identifies and dampens failing layers
- Boosts consistently profitable layers

**Weight Adjustments:**
```
Win Rate > 60%: Weight × 1.5
Win Rate 40-60%: Weight × 1.0
Win Rate < 30%: Weight × 0.5
```

---

### QualityTraderAI (417 lines) [NEW in V5.2]

**The Professional**

Handles mid-cap tokens that aren't meme coins.

**Characteristics:**
- Market Cap: $100K - $1M
- Position Size: 0.08 SOL
- Take Profit: 15-50%
- Stop Loss: -8% to -12%
- Hold Time: 15-60 minutes

**Quality Filters:**
- Min liquidity: $20K
- Min token age: 30 minutes
- Min holders: 50
- Max top holder: 30%
- Not classified as meme

**Promotion Rules:**
- MCAP > $1M → Promote to BlueChip
- Gains > 100% → Promote to Moonshot

---

## Total Code Stats

```
Scoring Layers Total:     16,766 lines
Engine Layer Total:       67,291 lines
V3 Engine Manager:        28,744 lines
──────────────────────────────────────
Grand Total:             110,444+ lines
```

---

## Layer Interaction Flow

```
Market Scanner
     │
     ▼
┌────────────────────────────────────────┐
│           All 28 AI Layers             │
│                                        │
│  [Each evaluates independently]        │
│  [CrossTalk shares signals]            │
│  [MetaCognition adjusts weights]       │
└────────────────────────────────────────┘
     │
     ▼
UnifiedScorer (aggregates with weights)
     │
     ▼
FinalDecisionGate (risk checks)
     │
     ▼
Executor (places trade)
     │
     ▼
EducationSubLayerAI (records outcome)
     │
     ▼
FluidLearningAI (updates thresholds)
```

---

**Document Version**: 1.0  
**Last Updated**: April 2026
