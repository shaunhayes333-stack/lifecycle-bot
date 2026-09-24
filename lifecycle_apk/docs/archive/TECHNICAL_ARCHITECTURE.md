# AATE Technical Architecture Document
## Autonomous AI Trading Engine V5.2

---

## Executive Summary

AATE (Autonomous AI Trading Engine) is a **native Android application** that implements a sophisticated multi-layer AI trading system for the Solana blockchain. Built entirely on a mobile device over approximately 10 days, the system comprises **110,000+ lines of Kotlin code**, **28 specialized AI layers**, and a **4-tier token graduation architecture**.

This document provides a comprehensive technical overview of the system architecture, AI layer interactions, data flow, and implementation details.

---

## 1. System Architecture

### 1.1 High-Level Components

```
┌─────────────────────────────────────────────────────────────────┐
│                       AATE APPLICATION                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │     UI      │  │   Engine    │  │   Network   │             │
│  │   Layer     │  │    Layer    │  │    Layer    │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         │                │                │                     │
│         └────────────────┼────────────────┘                     │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    V3 ENGINE MANAGER                        ││
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          ││
│  │  │ Scanner │ │ Scoring │ │Decision │ │Execution│          ││
│  │  │ Module  │ │ Module  │ │ Module  │ │ Module  │          ││
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘          ││
│  └─────────────────────────────────────────────────────────────┘│
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                  DATA PERSISTENCE                           ││
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      ││
│  │  │SharedPrefs   │  │  SQLite DB   │  │ Cloud Sync   │      ││
│  │  │(Settings)    │  │(Trade History)│  │(Collective)  │      ││
│  │  └──────────────┘  └──────────────┘  └──────────────┘      ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Package Structure

```
com.lifecyclebot/
├── AATEApp.kt                    # Application entry point
├── ui/                           # UI components
│   └── MainActivity.kt           # Main dashboard & controls
├── engine/                       # Core trading engine (67,000+ lines)
│   ├── BotService.kt            # Main service orchestrator (6,089 lines)
│   ├── Executor.kt              # Trade execution (6,197 lines)
│   ├── LifecycleStrategy.kt     # Strategy engine (3,601 lines)
│   ├── FinalDecisionGate.kt     # Final approval gate (3,377 lines)
│   ├── SolanaMarketScanner.kt   # Market data scanner (2,860 lines)
│   ├── BotBrain.kt              # Central coordinator (2,104 lines)
│   ├── AICrossTalk.kt           # Inter-AI communication (924 lines)
│   └── [45+ more engine files]
├── v3/                           # V3 AI subsystem
│   ├── V3EngineManager.kt       # V3 orchestrator (28,744 lines)
│   ├── scoring/                  # 28 AI scoring layers (16,766 lines)
│   ├── decision/                 # Decision engine
│   ├── execution/                # Trade executor
│   ├── learning/                 # Learning subsystem
│   ├── risk/                     # Risk management
│   ├── shadow/                   # Shadow trading
│   ├── arb/                      # Arbitrage module
│   └── [more v3 modules]
├── data/                         # Data models
│   └── Models.kt                # Core data structures
├── network/                      # API clients
│   └── DexScreenerClient.kt     # Market data API
├── collective/                   # Collective intelligence
│   └── CollectiveLearning.kt    # Cross-device sync
└── util/                         # Utilities
```

---

## 2. The 4-Tier Trading Architecture

AATE implements a **token graduation system** where assets move between tiers based on performance and characteristics.

### 2.1 Tier Overview

| Tier | Layer Name | Market Cap Range | Position Size | Target Profit | Max Hold |
|------|------------|------------------|---------------|---------------|----------|
| 1 | Treasury | $1K - $100K | 0.01 SOL | 3-8% | 15 min |
| 2 | ShitCoin | $10K - $500K | 0.05 SOL | 8-25% | 30 min |
| 3 | Quality | $100K - $1M | 0.08 SOL | 15-50% | 60 min |
| 4 | BlueChip/Moonshot | $1M+ | 0.15 SOL | 25-200%+ | Hours |

### 2.2 Promotion/Demotion Rules

```kotlin
// Promotion triggers (LayerTransitionManager.kt)
enum class PromotionTrigger {
    MCAP_THRESHOLD,      // Market cap crossed tier boundary
    GAIN_THRESHOLD,      // Gains exceeded tier expectations  
    TIME_MATURITY,       // Token proved stable over time
    QUALITY_SCORE,       // AI scoring improved
    MOONSHOT_DETECTED    // 100%+ gains detected
}

// Example: ShitCoin → Quality promotion
if (currentMcap > 100_000 && qualityScore > 40 && !isMeme) {
    promoteToQuality(token)
}

// Example: Quality → BlueChip promotion  
if (currentMcap > 1_000_000 && pnlPct > 0) {
    promoteToBlueChip(token)
}

// Example: Quality → Moonshot promotion
if (pnlPct >= 100) {
    promoteToMoonshot(token)
}
```

---

## 3. AI Layer Deep Dive

### 3.1 Scoring Layer Architecture

Each AI layer implements a common interface:

```kotlin
interface ScoringLayer {
    fun evaluate(
        mint: String,
        symbol: String,
        price: Double,
        mcap: Double,
        liquidity: Double,
        // ... additional parameters
    ): LayerSignal
    
    fun getWeight(): Double  // Layer importance in final score
    fun getName(): String
}

data class LayerSignal(
    val shouldEnter: Boolean,
    val confidence: Double,      // 0.0 - 1.0
    val score: Int,              // 0 - 100
    val reason: String,
    val metadata: Map<String, Any> = emptyMap()
)
```

### 3.2 Key AI Layers Explained

#### FluidLearningAI (1,224 lines)
The brain that makes everything adaptive.

```kotlin
object FluidLearningAI {
    // Thresholds evolve with experience
    private fun getLearningProgress(): Double {
        val totalTrades = TradeHistoryStore.getTotalTrades()
        return min(1.0, totalTrades / MATURITY_TRADES.toDouble())
    }
    
    // Take profit starts conservative, expands with experience
    fun getFluidTakeProfit(layer: TradingLayer): Double {
        val progress = getLearningProgress()
        return when(layer) {
            TREASURY -> 3.0 + (8.0 - 3.0) * progress      // 3% → 8%
            SHITCOIN -> 8.0 + (25.0 - 8.0) * progress     // 8% → 25%
            QUALITY -> 15.0 + (50.0 - 15.0) * progress    // 15% → 50%
            BLUECHIP -> 25.0 + (100.0 - 25.0) * progress  // 25% → 100%
        }
    }
}
```

#### BehaviorAI (864 lines)
Tracks and corrects "bad behavior" patterns.

```kotlin
object BehaviorAI {
    // Bad behaviors tracked
    enum class BadBehavior {
        PANIC_SELL,           // Sold too early in fear
        FOMO_ENTRY,           // Entered too late after pump
        REVENGE_TRADE,        // Trading to recover losses
        OVERTRADING,          // Too many trades too fast
        IGNORING_STOPS,       // Not honoring stop losses
        CHASING_LOSSES        // Increasing size after loss
    }
    
    fun detectBadBehavior(trade: Trade): List<BadBehavior> {
        val behaviors = mutableListOf<BadBehavior>()
        
        // Check for panic sell (exited profitable trade too early)
        if (trade.holdTimeMinutes < 2 && trade.pnlPct in 0.5..3.0) {
            behaviors.add(PANIC_SELL)
        }
        
        // Check for FOMO entry (entered after 50%+ pump)
        if (trade.entryPumpPct > 50) {
            behaviors.add(FOMO_ENTRY)
        }
        
        return behaviors
    }
}
```

#### UltraFastRugDetectorAI (614 lines)
Sub-second rug pull detection.

```kotlin
object UltraFastRugDetectorAI {
    // Rug indicators
    data class RugSignals(
        val liquidityDrain: Boolean,      // >50% liquidity removed
        val devSelling: Boolean,          // Dev wallet dumping
        val priceCollapse: Boolean,       // >30% drop in seconds
        val holderExodus: Boolean,        // Mass holder exits
        val honeypot: Boolean             // Can't sell
    )
    
    fun checkForRug(token: TokenState): RugSignals {
        val currentLiq = token.lastLiquidityUsd
        val previousLiq = token.history.lastOrNull()?.liquidity ?: currentLiq
        
        return RugSignals(
            liquidityDrain = (previousLiq - currentLiq) / previousLiq > 0.5,
            // ... other checks
        )
    }
}
```

#### MetaCognitionAI (739 lines)
The AI that watches the other AIs.

```kotlin
object MetaCognitionAI {
    // Tracks which AI layers are performing well
    private val layerPerformance = ConcurrentHashMap<String, LayerStats>()
    
    data class LayerStats(
        var totalSignals: Int = 0,
        var profitableSignals: Int = 0,
        var totalPnl: Double = 0.0
    )
    
    // Adjusts layer weights based on performance
    fun getAdjustedWeight(layerName: String): Double {
        val stats = layerPerformance[layerName] ?: return 1.0
        val winRate = stats.profitableSignals.toDouble() / stats.totalSignals
        
        return when {
            winRate > 0.6 -> 1.5   // Boost performing layers
            winRate < 0.3 -> 0.5   // Reduce poor performers
            else -> 1.0
        }
    }
}
```

### 3.3 Layer Communication (AICrossTalk)

AI layers don't work in isolation. They communicate:

```kotlin
object AICrossTalk {
    // Broadcast signals to all listening layers
    fun broadcast(signal: CrossTalkSignal) {
        listeners.forEach { it.onSignal(signal) }
    }
    
    data class CrossTalkSignal(
        val source: String,           // Which AI sent this
        val type: SignalType,         // ENTRY, EXIT, WARNING, etc
        val mint: String,
        val confidence: Double,
        val message: String,
        val metadata: Map<String, Any>
    )
    
    // Example: RugDetector warns all layers
    fun rugWarning(mint: String) {
        broadcast(CrossTalkSignal(
            source = "UltraFastRugDetectorAI",
            type = SignalType.RUG_WARNING,
            mint = mint,
            confidence = 0.95,
            message = "LIQUIDITY DRAIN DETECTED"
        ))
    }
}
```

---

## 4. Decision Pipeline

### 4.1 Complete Flow

```
Market Data (DexScreener/Birdeye)
          │
          ▼
┌─────────────────────┐
│  SolanaMarketScanner │  ─── Filters: mcap, liquidity, age
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   V3 Engine Manager  │  ─── Coordinates all subsystems
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   28 AI Scoring      │  ─── Each layer evaluates
│      Layers          │      and returns score/signal
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   UnifiedScorer      │  ─── Aggregates all scores
└──────────┬──────────┘      with MetaCognition weights
           │
           ▼
┌─────────────────────┐
│  FinalDecisionGate   │  ─── Final approval/rejection
└──────────┬──────────┘      with risk checks
           │
           ▼
┌─────────────────────┐
│ FinalExecutionPermit │  ─── Concurrency control
└──────────┬──────────┘      (one execution at a time)
           │
           ▼
┌─────────────────────┐
│      Executor        │  ─── Places trade on-chain
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ EducationSubLayerAI  │  ─── Records outcome for learning
└─────────────────────┘
```

### 4.2 Scoring Aggregation

```kotlin
// UnifiedScorer.kt
fun calculateFinalScore(
    token: TokenState,
    layerSignals: Map<String, LayerSignal>
): FinalScore {
    var weightedSum = 0.0
    var totalWeight = 0.0
    
    layerSignals.forEach { (layerName, signal) ->
        val baseWeight = getLayerWeight(layerName)
        val adjustedWeight = MetaCognitionAI.getAdjustedWeight(layerName)
        val finalWeight = baseWeight * adjustedWeight
        
        weightedSum += signal.score * finalWeight
        totalWeight += finalWeight
    }
    
    return FinalScore(
        score = (weightedSum / totalWeight).toInt(),
        confidence = calculateConfidence(layerSignals),
        signals = layerSignals
    )
}
```

---

## 5. Exit Management

### 5.1 Exit Triggers

AATE uses multiple exit mechanisms:

```kotlin
enum class ExitSignal {
    HOLD,              // Keep position
    TAKE_PROFIT,       // Hit profit target
    STOP_LOSS,         // Hit loss limit
    TRAILING_STOP,     // Trailing stop triggered
    TIME_EXIT,         // Max hold time exceeded
    RUG_DETECTED,      // Emergency rug exit
    PROMOTE_BLUECHIP,  // Graduating to higher tier
    PROMOTE_MOONSHOT,  // Moonshot detected
    MANUAL             // User-initiated
}
```

### 5.2 Trailing Stop Implementation

```kotlin
// AdvancedExitManager.kt
fun updateTrailingStop(position: Position, currentPrice: Double) {
    val pnlPct = (currentPrice - position.entryPrice) / position.entryPrice * 100
    
    // Update high water mark
    if (currentPrice > position.highWaterMark) {
        position.highWaterMark = currentPrice
        
        // Trailing stop activates at different gains for different tiers
        val trailActivation = when(position.layer) {
            TREASURY -> 3.0      // Trail after 3%
            SHITCOIN -> 8.0      // Trail after 8%
            QUALITY -> 10.0      // Trail after 10%
            BLUECHIP -> 15.0     // Trail after 15%
        }
        
        if (pnlPct > trailActivation) {
            // Trail at 60% of peak (keep 60% of gains)
            position.trailingStop = currentPrice * 0.94  // 6% from peak
        }
    }
}
```

---

## 6. Learning System

### 6.1 Education Sub-Layer

Every trade teaches the system:

```kotlin
// EducationSubLayerAI.kt
fun recordTradeOutcome(trade: CompletedTrade) {
    // 1. Update layer performance
    MetaCognitionAI.recordOutcome(trade.winningLayers, trade.pnlPct > 0)
    
    // 2. Update behavior patterns
    BehaviorAI.analyzeAndRecord(trade)
    
    // 3. Update fluid thresholds
    FluidLearningAI.adjustThresholds(trade)
    
    // 4. Sync to collective (if enabled)
    if (CollectiveLearning.isEnabled()) {
        CollectiveLearning.uploadTrade(trade)
    }
    
    // 5. Persist locally
    TradeHistoryStore.saveTrade(trade)
    
    // 6. Update Harvard Brain
    HarvardBrain.learn(trade)
}
```

### 6.2 Collective Intelligence

Multiple AATE instances share learnings:

```kotlin
// CollectiveLearning.kt
object CollectiveLearning {
    private const val SYNC_ENDPOINT = "https://api.collective.aate.ai/sync"
    
    suspend fun uploadTrade(trade: CompletedTrade) {
        // Anonymize and upload
        val payload = TradePayload(
            tokenMint = trade.mint,
            entryMcap = trade.entryMcap,
            exitMcap = trade.exitMcap,
            pnlPct = trade.pnlPct,
            holdMinutes = trade.holdMinutes,
            layerScores = trade.layerScores,
            exitReason = trade.exitReason
        )
        
        api.post(SYNC_ENDPOINT, payload)
    }
    
    suspend fun downloadInsights(): CollectiveInsights {
        // Download aggregated learnings from fleet
        return api.get("$SYNC_ENDPOINT/insights")
    }
}
```

---

## 7. Risk Management

### 7.1 Position Limits

```kotlin
// PositionSizing.kt
object PositionSizing {
    // Hard limits
    const val MAX_TOTAL_EXPOSURE_SOL = 0.5      // Max 0.5 SOL at risk
    const val MAX_CONCURRENT_POSITIONS = 8      // Max 8 open positions
    const val MAX_SINGLE_POSITION_SOL = 0.25    // Max 0.25 SOL per trade
    
    fun canOpenPosition(sizeSol: Double): Boolean {
        val currentExposure = getOpenPositions().sumOf { it.costSol }
        val positionCount = getOpenPositions().size
        
        return sizeSol <= MAX_SINGLE_POSITION_SOL &&
               currentExposure + sizeSol <= MAX_TOTAL_EXPOSURE_SOL &&
               positionCount < MAX_CONCURRENT_POSITIONS
    }
}
```

### 7.2 SAFE MODE

Automatic risk reduction:

```kotlin
// SafeModeManager.kt
object SafeModeManager {
    private var consecutiveLosses = 0
    private var dailyDrawdown = 0.0
    
    fun checkAndActivateSafeMode() {
        val shouldActivate = consecutiveLosses >= 3 || 
                            dailyDrawdown <= -0.1 // -10% daily
        
        if (shouldActivate && !isSafeModeActive) {
            activateSafeMode()
        }
    }
    
    private fun activateSafeMode() {
        // Reduce all position sizes by 50%
        PositionSizing.setMultiplier(0.5)
        
        // Tighten all stop losses
        ExitManager.setStopLossMultiplier(0.7) // 30% tighter
        
        // Disable aggressive layers
        ShitCoinExpress.disable()
        MoonshotTraderAI.disable()
        
        ErrorLogger.warn("SAFE_MODE", "🛡️ SAFE MODE ACTIVATED")
    }
}
```

---

## 8. Data Models

### 8.1 Core Models

```kotlin
// Models.kt
data class TokenState(
    val mint: String,
    val symbol: String,
    val name: String,
    var lastPrice: Double,
    var lastMcap: Double,
    var lastLiquidityUsd: Double,
    var lastBuyPressurePct: Double,
    var topHolderPct: Double?,
    val history: ArrayDeque<Candle>,  // 1m candles
    val history5m: ArrayDeque<Candle>,
    val history15m: ArrayDeque<Candle>,
    var position: Position,
    val trades: MutableList<Trade>
)

data class Candle(
    val ts: Long,           // Timestamp
    val o: Double,          // Open
    val h: Double,          // High
    val l: Double,          // Low
    val c: Double,          // Close
    val v: Double,          // Volume
    val holderCount: Int,   // Holder count
    val liquidity: Double   // Liquidity USD
)

data class Position(
    var isOpen: Boolean = false,
    var mint: String = "",
    var entryPrice: Double = 0.0,
    var entryTime: Long = 0L,
    var costSol: Double = 0.0,
    var tokensHeld: Double = 0.0,
    var takeProfitPct: Double = 0.0,
    var stopLossPct: Double = 0.0,
    var highWaterMark: Double = 0.0,
    var trailingStop: Double = 0.0,
    var layer: TradingLayer = TradingLayer.TREASURY
)
```

---

## 9. Performance Characteristics

### 9.1 Latency Targets

| Operation | Target | Implementation |
|-----------|--------|----------------|
| Market scan | <500ms | Parallel API calls |
| AI scoring | <100ms | In-memory computation |
| Decision gate | <50ms | Cached checks |
| Trade execution | <2s | Jupiter aggregator |

### 9.2 Memory Management

```kotlin
// Candle history limits to prevent OOM
const val MAX_1M_CANDLES = 300   // 5 hours of 1m data
const val MAX_5M_CANDLES = 100   // ~8 hours of 5m data
const val MAX_15M_CANDLES = 60   // 15 hours of 15m data

// Automatic pruning
fun pruneHistory(history: ArrayDeque<Candle>, maxSize: Int) {
    while (history.size > maxSize) {
        history.removeFirst()
    }
}
```

---

## 10. Development Notes

### 10.1 Build System

- **Language**: Kotlin 1.9.0
- **Min SDK**: Android 26 (Oreo)
- **Target SDK**: Android 34
- **Build Tool**: Gradle 8.7
- **CI/CD**: GitHub Actions

### 10.2 No Local Compiler

The entire application was developed without a local IDE or compiler. All compilation happens via GitHub Actions CI:

1. Code written on mobile device
2. Committed and pushed to GitHub
3. GitHub Actions workflow triggers
4. APK built and uploaded as artifact
5. Download and test on device

This constraint required:
- Extremely careful Kotlin syntax
- Comprehensive mental compilation
- Incremental, testable commits
- Heavy reliance on CI feedback

---

## 11. Future Architecture Considerations

### 11.1 Planned Improvements

1. **WebSocket Market Data**: Replace polling with real-time streams
2. **On-Device ML**: TensorFlow Lite for pattern recognition
3. **Sharded Learning**: Distributed learning across device clusters
4. **Advanced Arbitrage**: Cross-DEX arbitrage with MEV protection

### 11.2 Scaling Considerations

- Current: Single device, local learning
- Near-term: Collective intelligence sync
- Future: Distributed trading network

---

## Appendix A: File Size Reference

```
Engine Layer (67,291 lines):
├── Executor.kt                 6,197
├── BotService.kt               6,089
├── LifecycleStrategy.kt        3,601
├── FinalDecisionGate.kt        3,377
├── SolanaMarketScanner.kt      2,860
├── BotBrain.kt                 2,104
├── HistoricalChartScanner.kt   1,046
├── ShadowLearningEngine.kt     1,024
├── ModeSpecificExits.kt          958
├── AICrossTalk.kt                924
└── [40+ more files]

V3 Scoring Layer (16,766 lines):
├── FluidLearningAI.kt          1,224
├── CashGenerationAI.kt         1,067
├── ShitCoinTraderAI.kt         1,058
├── CollectiveIntelligenceAI.kt   914
├── EducationSubLayerAI.kt        885
├── BehaviorAI.kt                 864
├── MoonshotTraderAI.kt           829
├── MetaCognitionAI.kt            739
└── [20+ more AI layers]

V3 Engine Manager: 28,744 lines
Total: 110,444+ lines
```

---

**Document Version**: 1.0  
**Last Updated**: April 2026  
**Author**: AATE Development Team
