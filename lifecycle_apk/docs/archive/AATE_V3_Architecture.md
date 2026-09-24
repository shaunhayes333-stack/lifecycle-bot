# AATE V3 Architecture - Technical Deep Dive

## Executive Summary

AATE V3 represents a fundamental shift from hard-gating to unified scoring. Every signal becomes a weighted input to a central decision engine, enabling the system to capture opportunities that rigid rule-based systems would reject.

**Key Innovation:** Instead of `if (condition) return BLOCK`, V3 uses `score += module.evaluate()` for all non-fatal conditions.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AATE V3 Architecture                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │
│  │ DexScreener │    │   Raydium   │    │  Pump.fun   │             │
│  │   Boosted   │    │  New Pools  │    │  Graduates  │             │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘             │
│         │                  │                  │                     │
│         └──────────────────┼──────────────────┘                     │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │ DISCOVERY LAYER │                               │
│                   │ (CandidateSnapshot)                             │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │ ELIGIBILITY    │  Cooldowns, Exposure,          │
│                   │ GATE           │  Portfolio Risk                │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│         ┌──────────────────────────────────────┐                    │
│         │         UNIFIED SCORER (12 AI)       │                    │
│         │  ┌────────┐ ┌────────┐ ┌────────┐   │                    │
│         │  │ Entry  │ │Momentum│ │Liquidity│  │                    │
│         │  └────────┘ └────────┘ └────────┘   │                    │
│         │  ┌────────┐ ┌────────┐ ┌────────┐   │                    │
│         │  │ Volume │ │Holders │ │Narrative│  │                    │
│         │  └────────┘ └────────┘ └────────┘   │                    │
│         │  ┌────────┐ ┌────────┐ ┌────────┐   │                    │
│         │  │ Memory │ │ Regime │ │  Time  │   │                    │
│         │  └────────┘ └────────┘ └────────┘   │                    │
│         │  ┌────────┐ ┌────────┐ ┌────────┐   │                    │
│         │  │CopyTrad│ │Suppress│ │ Source │   │                    │
│         │  └────────┘ └────────┘ └────────┘   │                    │
│         └──────────────────┬───────────────────┘                    │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │  FATAL RISK    │  Only: Rugged, Honeypot,       │
│                   │  CHECKER       │  Unsellable, Liq Collapsed     │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │  CONFIDENCE    │  Statistical + Structural      │
│                   │  ENGINE        │  + Operational                 │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │  DECISION      │  EXECUTE_AGGRESSIVE            │
│                   │  ENGINE        │  EXECUTE_STANDARD              │
│                   │                │  EXECUTE_SMALL                 │
│                   │                │  WATCH / REJECT / BLOCK        │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │  SMARTSIZER    │  Dynamic sizing based on       │
│                   │  V3            │  confidence, drawdown, regime  │
│                   └────────┬───────┘                                │
│                            ▼                                        │
│                   ┌────────────────┐                                │
│                   │  EXECUTION     │  Jupiter Ultra + Jito MEV      │
│                   └────────────────┘                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. CandidateSnapshot

The unified data structure for all tokens entering the V3 pipeline:

```kotlin
data class CandidateSnapshot(
    val mint: String,
    val symbol: String,
    val source: SourceType,
    val discoveredAtMs: Long,
    val ageMinutes: Double,
    val liquidityUsd: Double,
    val marketCapUsd: Double,
    val buyPressurePct: Double,
    val volume1mUsd: Double,
    val volume5mUsd: Double,
    val holders: Int?,
    val topHolderPct: Double?,
    val bundledPct: Double?,
    val hasIdentitySignals: Boolean,
    val isSellable: Boolean?,
    val rawRiskScore: Int?,
    val extra: Map<String, Any?>  // Extensible metadata
)
```

### 2. ScoreCard & ScoreComponent

Every AI module outputs a ScoreComponent:

```kotlin
data class ScoreComponent(
    val name: String,      // "entry", "momentum", "suppression"
    val value: Int,        // -30 to +30 typically
    val reason: String     // Human-readable explanation
)

class ScoreCard(val components: List<ScoreComponent>) {
    fun total(): Int = components.sumOf { it.value }
    fun breakdown(): String = components.map { "${it.name}=${it.value}" }.joinToString(" | ")
}
```

### 3. Unified Scorer

Orchestrates all 12 AI modules:

```kotlin
class UnifiedScorer {
    private val modules = listOf(
        EntryAI(),
        MomentumAI(),
        LiquidityAI(),
        VolumeProfileAI(),
        HolderSafetyAI(),
        NarrativeAI(),
        MemoryAI(),
        MarketRegimeAI(),
        TimeAI(),
        CopyTradeAI(),
        SuppressionAI()  // V3 Migration: converts legacy blocks to penalties
    )
    
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        return ScoreCard(
            listOf(sourceScore(candidate.source)) +
            modules.map { it.score(candidate, ctx) }
        )
    }
}
```

---

## AI Module Details

### EntryAI
Evaluates entry conditions based on price action:
- RSI oversold (+8)
- Higher lows formation (+10)
- Pump building signal (+12)
- Near VAL accumulation (+6)

### MomentumAI
Measures trend strength:
- Strong momentum (+8)
- Weak/fading momentum (-10)
- Consolidation pattern (+4)

### LiquidityAI
Analyzes liquidity depth and stability:
- Deep liquidity (+6)
- Draining liquidity (-15)
- Growing liquidity (+8)

### VolumeProfileAI
Volume pattern recognition:
- Volume surge (+10)
- Volume expansion (+6)
- Declining volume (-8)

### HolderSafetyAI
Holder distribution analysis:
- Healthy distribution (+6)
- High concentration (-12)
- Zero/unknown holders (-8)

### NarrativeAI
Social and narrative signals:
- Trending narrative (+6)
- Suspicious name (-5)
- Identity signals (+4)

### MemoryAI
Historical pattern matching:
- Previous winner on this token (+8)
- Previous loss (-10)
- Similar pattern to winner (+5)

### MarketRegimeAI
Macro market conditions:
- Bull regime (+8)
- Bear regime (-8)
- Neutral (0)
- Volatility adjustment (±5)

### TimeAI
Time-of-day optimization:
- Optimal hours (+6)
- Danger zone hours (-8)
- Weekend adjustment (-4)

### CopyTradeAI
Whale following signals:
- Stale copy pattern (-8)
- Crowded setup (-4)
- Fresh whale buy (+6)

### SuppressionAI (V3 Migration)
Converts legacy hard blocks to penalties:
- COPY_TRADE_INVALIDATION → -15
- WHALE_ACCUMULATION_INVALIDATION → -20
- STOP_LOSS → -25
- DISTRIBUTION → -30

```kotlin
class SuppressionAI : ScoringModule {
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val penalty = DistributionFadeAvoider.getSuppressionPenalty(candidate.mint)
        
        if (penalty == 0) {
            return ScoreComponent("suppression", 0, "No suppression")
        }
        
        val reason = when {
            penalty >= 25 -> "Recent stop-loss/distribution"
            penalty >= 20 -> "Whale invalidation penalty"
            penalty >= 15 -> "Copy-trade invalidation penalty"
            else -> "Minor suppression cooldown"
        }
        
        return ScoreComponent("suppression", -penalty, "$reason (-$penalty)")
    }
}
```

---

## Decision Engine

### Decision Bands

```kotlin
enum class DecisionBand(val minScore: Int, val maxScore: Int) {
    EXECUTE_AGGRESSIVE(75, 100),   // High conviction, max size
    EXECUTE_STANDARD(55, 74),      // Normal conviction, standard size
    EXECUTE_SMALL(40, 54),         // Low conviction, reduced size
    WATCH(25, 39),                 // Track but don't trade
    REJECT(0, 24),                 // Poor setup
    BLOCK(-100, -1)                // Fatal issues
}
```

### Confidence Engine

Three dimensions of confidence:

```kotlin
data class ConfidenceBreakdown(
    val statistical: Double,   // Win rate, sample size
    val structural: Double,    // Technical setup quality
    val operational: Double    // System health, latency
)

fun calculateConfidence(
    scoreCard: ScoreCard,
    learningMetrics: LearningMetrics,
    opsMetrics: OpsMetrics
): ConfidenceBreakdown {
    val statistical = when {
        learningMetrics.classifiedTrades < 20 -> 50.0  // Not enough data
        learningMetrics.last20WinRatePct >= 60 -> 80.0
        learningMetrics.last20WinRatePct >= 50 -> 65.0
        else -> 45.0
    }
    
    val structural = (scoreCard.total() + 50).coerceIn(0, 100).toDouble()
    
    val operational = when {
        !opsMetrics.apiHealthy -> 30.0
        opsMetrics.latencyMs > 500 -> 60.0
        else -> 90.0
    }
    
    return ConfidenceBreakdown(statistical, structural, operational)
}
```

---

## SmartSizer V3

Dynamic position sizing based on multiple factors:

```kotlin
class SmartSizerV3(private val config: TradingConfigV3) {
    
    fun calculateSize(
        band: DecisionBand,
        confidence: ConfidenceBreakdown,
        wallet: WalletSnapshot,
        risk: PortfolioRiskState
    ): Double {
        // Base size from band
        val basePct = when (band) {
            DecisionBand.EXECUTE_AGGRESSIVE -> 0.08  // 8% of tradeable
            DecisionBand.EXECUTE_STANDARD -> 0.05   // 5%
            DecisionBand.EXECUTE_SMALL -> 0.025     // 2.5%
            else -> 0.0
        }
        
        // Confidence multiplier (0.5x to 1.5x)
        val avgConfidence = (confidence.statistical + confidence.structural + confidence.operational) / 3
        val confMultiplier = (avgConfidence / 100.0).coerceIn(0.5, 1.5)
        
        // Drawdown multiplier (reduce size during drawdowns)
        val drawdownMultiplier = when {
            risk.recentDrawdownPct > 20 -> 0.25
            risk.recentDrawdownPct > 10 -> 0.50
            risk.recentDrawdownPct > 5 -> 0.75
            else -> 1.0
        }
        
        // Calculate final size
        val sizeSol = wallet.tradeableSol * basePct * confMultiplier * drawdownMultiplier
        
        // Apply min/max bounds
        return sizeSol.coerceIn(config.minPositionSol, config.maxPositionSol)
    }
}
```

---

## Fatal Risk Checker

Only truly fatal conditions block in V3:

```kotlin
class FatalRiskChecker {
    fun check(candidate: CandidateSnapshot, ctx: TradingContext): FatalRiskResult {
        // FATAL: Rugged/honeypot suppression
        if (DistributionFadeAvoider.isFatalSuppression(candidate.mint)) {
            return FatalRiskResult(true, "FATAL_SUPPRESSION")
        }
        
        // FATAL: Liquidity collapsed (can't exit)
        if (candidate.liquidityUsd <= 250.0) {
            return FatalRiskResult(true, "LIQUIDITY_COLLAPSED")
        }
        
        // FATAL: Marked unsellable
        if (candidate.isSellable == false) {
            return FatalRiskResult(true, "UNSELLABLE")
        }
        
        // FATAL: Invalid pair
        if (candidate.mint.isBlank() || candidate.symbol.isBlank()) {
            return FatalRiskResult(true, "PAIR_INVALID")
        }
        
        // FATAL: Extreme rug score (90+)
        val rugScore = rugModel.score(candidate, ctx)
        if (rugScore >= 90) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }
        
        // Everything else → scoring, not blocking
        return FatalRiskResult(false)
    }
}
```

---

## V3 vs Legacy Comparison

### Before V3 (Hard Gates)

```kotlin
// Old BotService.kt flow
if (suppressionReason != null) {
    return@launch  // KILLED
}

if (!decision.shouldTrade) {
    return@launch  // KILLED
}

if (copyTradeInvalidation) {
    return@launch  // KILLED
}

if (rugcheckScore < 20) {
    return@launch  // KILLED
}

// Only survivors reach execution
executeTrade()
```

**Problem:** 90% of opportunities killed by cascading gates.

### After V3 (Unified Scoring)

```kotlin
// New BotService.kt flow
val candidate = V3Adapter.toCandidate(ts)
val scoreCard = unifiedScorer.score(candidate, ctx)

val fatalCheck = fatalRiskChecker.check(candidate, ctx)
if (fatalCheck.blocked) {
    return V3Decision.blocked(fatalCheck.reason)  // Only true fatals
}

val confidence = confidenceEngine.calculate(scoreCard, learning, ops)
val decision = decisionEngine.decide(scoreCard, confidence)

when (decision) {
    is Execute -> executeTrade(decision.sizeSol)
    is Watch -> trackForLater()
    is Reject -> shadowLearn()
}
```

**Result:** Every signal weighted, nothing wasted.

---

## Collective Learning Integration

V3 feeds into the Turso collective learning system:

```kotlin
// On trade outcome
V3EngineManager.recordOutcome(
    mint = mint,
    symbol = symbol,
    pnlPct = pnlPct,
    holdTimeMinutes = holdMins,
    exitReason = reason,
    entryPhase = phase,
    tradingMode = mode,
    discoverySource = source,
    liquidityUsd = liq,
    emaTrend = trend
)

// Uploads to Turso:
// - Pattern effectiveness (phase + liquidity + timing → outcome)
// - Blacklist additions (rugs, scams)
// - Whale wallet scores
// - Mode performance by time
```

---

## Performance Metrics

### Expected Improvements from V3

| Metric | Legacy | V3 Expected |
|--------|--------|-------------|
| Opportunity Capture | 10% | 40%+ |
| False Positive Rate | High | Lower (weighted) |
| Learning Speed | Slow | Fast (collective) |
| Decision Explainability | Low | High (scorecard) |
| Adaptation Speed | Manual | Automatic |

---

## Configuration

```kotlin
data class TradingConfigV3(
    // Scoring thresholds
    val executeAggressiveMin: Int = 75,
    val executeStandardMin: Int = 55,
    val executeSmallMin: Int = 40,
    val watchMin: Int = 25,
    
    // Fatal thresholds
    val fatalRugThreshold: Int = 90,
    val minLiquidityUsd: Double = 250.0,
    
    // Sizing bounds
    val minPositionSol: Double = 0.01,
    val maxPositionSol: Double = 1.0,
    val maxExposurePct: Double = 0.70,
    
    // Mode
    val shadowMode: Boolean = false,  // Log only, don't execute
    val paperMode: Boolean = true     // Paper trading
)
```

---

## Logging Format

V3 produces unified, parseable logs:

```
⚡ V3 EXECUTE: SYMBOL | band=EXECUTE_SMALL | score=72 | conf=68% | size=0.15 SOL | legacy:✗ FDG:✓
   └─ entry=+8 | momentum=+6 | liquidity=+4 | suppression=-15 | time=+2 | ...

⚡ V3 WATCH: SYMBOL | score=32 | conf=45% | FDG:✓
   └─ Reason: Score below execute threshold

⚡ V3 BLOCK (FATAL): SYMBOL | LIQUIDITY_COLLAPSED | FDG:✓
```

---

## Migration Notes

### Files Modified for V3

1. **BotService.kt** - V3 is PRIMARY decision maker
2. **DistributionFadeAvoider.kt** - Added `getSuppressionPenalty()`, `isFatalSuppression()`
3. **ModeSpecificExits.kt** - Invalidation creates penalty signal
4. **SolanaMarketScanner.kt** - quickRugcheck only blocks confirmed rugs
5. **Executor.kt** - Legacy `shouldTrade` is advisory only
6. **V3/scoring/ScoringModules.kt** - Added SuppressionAI
7. **V3/scoring/UnifiedScorer.kt** - Wired SuppressionAI
8. **V3/risk/FatalRiskChecker.kt** - Added fatal suppression check
9. **V3/bridge/V3Adapter.kt** - Passes suppression data to extras

### Legacy Files (Advisory Only)

These files still exist but are now advisory/comparison only:
- EdgeOptimizer.kt
- FinalDecisionGate.kt
- SmartSizer.kt
- EntryIntelligence.kt

V3 controls runtime. Legacy provides comparison logging.

---

## Conclusion

V3 Architecture transforms AATE from a rule-based gating system to a learning-capable scoring engine. The unified scoring approach:

1. **Captures more opportunities** - No more cascade kills
2. **Learns faster** - Every signal contributes to the scorecard
3. **Adapts automatically** - Collective learning updates weights
4. **Explains decisions** - Full scorecard breakdown for every trade

This is the foundation for true autonomous trading.
