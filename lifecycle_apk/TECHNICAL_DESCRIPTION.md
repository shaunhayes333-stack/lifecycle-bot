# AATE V3.2 - Technical Architecture Document

```
     █████╗  █████╗ ████████╗███████╗    ██╗   ██╗██████╗    ██████╗ 
    ██╔══██╗██╔══██╗╚══██╔══╝██╔════╝    ██║   ██║╚════██╗   ╚════██╗
    ███████║███████║   ██║   █████╗      ██║   ██║ █████╔╝    █████╔╝
    ██╔══██║██╔══██║   ██║   ██╔══╝      ╚██╗ ██╔╝ ╚═══██╗   ██╔═══╝ 
    ██║  ██║██║  ██║   ██║   ███████╗     ╚████╔╝ ██████╔╝   ███████╗
    ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚══════╝      ╚═══╝  ╚═════╝    ╚══════╝
    
    TECHNICAL ARCHITECTURE DOCUMENT
```

---

## TABLE OF CONTENTS

1. [System Overview](#system-overview)
2. [Core Architecture](#core-architecture)
3. [21 AI Layer Deep Dive](#21-ai-layer-deep-dive)
4. [Multi-Regime Trading System](#multi-regime-trading-system)
5. [Decision Pipeline](#decision-pipeline)
6. [Safety Systems](#safety-systems)
7. [Data Persistence](#data-persistence)
8. [External Integrations](#external-integrations)
9. [Performance Characteristics](#performance-characteristics)

---

## SYSTEM OVERVIEW

### Technology Stack

| Component | Technology |
|-----------|------------|
| **Platform** | Native Android (Kotlin) |
| **Min SDK** | Android 8.0 (API 26) |
| **Architecture** | MVVM + Coroutines |
| **Networking** | OkHttp + Retrofit |
| **Blockchain** | Solana (via Jupiter V2) |
| **Wallet** | Solana-Android SDK |
| **Persistence** | SharedPreferences + SQLite |
| **Cloud** | Turso (LibSQL) for collective |

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         ANDROID APPLICATION                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                     UI LAYER (Activities)                      │  │
│  │  MainActivity | JournalActivity | CollectiveBrainActivity     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    SERVICE LAYER                               │  │
│  │                    BotService (Foreground)                     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│         ┌────────────────────┼────────────────────┐                │
│         ▼                    ▼                    ▼                │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐          │
│  │   SCANNER   │     │   V3 ENGINE │     │  EXECUTOR   │          │
│  │  (Market    │     │  (21 AI     │     │  (Trade     │          │
│  │   Data)     │     │   Layers)   │     │   Execution)│          │
│  └─────────────┘     └─────────────┘     └─────────────┘          │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    SAFETY LAYER                                │  │
│  │  FDG | TokenBlacklist | ToxicModeCircuitBreaker | SecurityGuard│  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                  PERSISTENCE LAYER                             │  │
│  │  TradeHistoryStore | WalletState | CollectiveLearning         │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## CORE ARCHITECTURE

### BotService (Foreground Service)

The BotService runs as an Android Foreground Service, ensuring continuous operation even when the app is backgrounded.

```kotlin
class BotService : Service() {
    companion object {
        val status = BotStatus()           // Shared state
        var instance: BotService? = null   // Singleton access
    }
    
    // Core components initialized on startup
    private lateinit var executor: Executor
    private lateinit var scanner: SolanaMarketScanner
    private lateinit var v3Engine: V3EngineManager
}
```

**Key Responsibilities:**
- Market scanning loop (5-second intervals)
- Token state management
- Trade execution coordination
- Position monitoring
- Safety system enforcement

### V3 Engine Manager

The V3 Engine coordinates all 21 AI layers through the `BotOrchestrator`.

```kotlin
object V3EngineManager {
    private var orchestrator: BotOrchestrator? = null
    
    fun initialize(scope: CoroutineScope) {
        orchestrator = BotOrchestrator(
            scorer = UnifiedScorer(),
            confidenceEngine = ConfidenceEngine(),
            decisionMatrix = DecisionMatrix(),
            lifecycle = TradeLifecycle,
            shadowTracker = ShadowTracker,
            logger = StageLogger
        )
    }
    
    suspend fun evaluate(candidate: CandidateSnapshot): ProcessResult {
        return orchestrator?.processCandidate(candidate) 
            ?: ProcessResult.Skip("V3 not initialized")
    }
}
```

---

## 21 AI LAYER DEEP DIVE

### Layer Architecture

Each AI layer implements a common interface:

```kotlin
interface AILayer {
    val name: String
    val weight: Double  // Base weight (adjusted by MetaCognition)
    
    suspend fun score(context: ScoringContext): LayerScore
    
    data class LayerScore(
        val value: Double,        // -10 to +10
        val confidence: Double,   // 0.0 to 1.0
        val signals: List<String>
    )
}
```

### Layer Categories

#### Scoring Layers (1-15)

| Layer | Class | Key Metrics |
|-------|-------|-------------|
| 1 | VolatilityRegimeAI | ATR, Squeeze signals, Vol regime |
| 2 | OrderFlowImbalanceAI | Buy/sell pressure, Volume delta |
| 3 | SmartMoneyDivergenceAI | Whale trades vs price action |
| 4 | HoldTimeOptimizerAI | Optimal hold duration prediction |
| 5 | LiquidityCycleAI | Global liquidity state |
| 6 | MarketRegimeAI | Bull/Bear/Sideways detection |
| 7 | WhaleTrackerAI | Large wallet movements |
| 8 | MomentumPredictorAI | Momentum strength/exhaustion |
| 9 | NarrativeDetectorAI | Social trends, meme momentum |
| 10 | TimeOptimizationAI | Time-of-day patterns |
| 11 | LiquidityDepthAI | Pool depth analysis |
| 12 | EntryIntelligence | Entry signal patterns |
| 13 | ExitIntelligence | Exit timing patterns |
| 14 | FearGreedAI | Market sentiment score |
| 15 | SocialVelocityAI | Social momentum velocity |

#### Meta & Coordination Layers (16-19)

| Layer | Class | Function |
|-------|-------|----------|
| 16 | MetaCognitionAI | Self-aware executive control |
| 17 | AICrossTalk | Inter-layer coordination |
| 18 | OrthogonalSignals | Independent validation |
| 19 | RegimeTransitionAI | Cross-regime detection |

#### Learning Layers (20-21)

| Layer | Class | Function |
|-------|-------|----------|
| 20 | EdgeLearning | Edge discovery/validation |
| 21 | TokenWinMemory | Token-specific patterns |

### MetaCognitionAI Deep Dive

```kotlin
object MetaCognitionAI {
    // Track accuracy per layer
    private val layerAccuracy = ConcurrentHashMap<String, AccuracyTracker>()
    
    // Trust multipliers (0.7x to 1.3x)
    private val trustMultipliers = ConcurrentHashMap<String, Double>()
    
    data class AccuracyTracker(
        var totalPredictions: Int = 0,
        var correctPredictions: Int = 0,
        var lastUpdated: Long = 0
    ) {
        val accuracy: Double get() = 
            if (totalPredictions > 0) correctPredictions.toDouble() / totalPredictions 
            else 0.5
    }
    
    fun adjustLayerWeight(layer: String, baseWeight: Double): Double {
        val multiplier = trustMultipliers[layer] ?: 1.0
        return baseWeight * multiplier
    }
    
    fun recordTradeOutcome(signals: Map<String, Boolean>, tradeWon: Boolean) {
        signals.forEach { (layer, predicted) ->
            val tracker = layerAccuracy.getOrPut(layer) { AccuracyTracker() }
            tracker.totalPredictions++
            if (predicted == tradeWon) tracker.correctPredictions++
            
            // Adjust trust based on rolling accuracy
            val newMultiplier = 0.7 + (tracker.accuracy * 0.6)  // 0.7 to 1.3
            trustMultipliers[layer] = newMultiplier
        }
    }
    
    fun shouldVeto(scores: Map<String, LayerScore>): Boolean {
        // Veto if high-trust layers disagree
        val reliableLayers = trustMultipliers.filter { it.value > 1.1 }.keys
        val reliableScores = scores.filter { it.key in reliableLayers }
        
        if (reliableScores.size < 2) return false
        
        val bullish = reliableScores.values.count { it.value > 3 }
        val bearish = reliableScores.values.count { it.value < -3 }
        
        // Strong disagreement among reliable layers = veto
        return bullish > 0 && bearish > 0 && 
               (bullish.toDouble() / reliableScores.size) in 0.3..0.7
    }
}
```

---

## MULTI-REGIME TRADING SYSTEM

### MarketStructureRouter

```kotlin
object MarketStructureRouter {
    
    enum class MarketRegime(val emoji: String, val label: String) {
        MEME_MICRO("🎰", "Meme Micro"),
        MEME_MOMENTUM("🚀", "Meme Momentum"),
        MAJOR_TREND("📈", "Major Trend"),
        MID_CAP_VALUE("💎", "Mid-Cap Value"),
        PERP_FUNDING("📊", "Perp Funding"),
        CEX_ARBITRAGE("🔄", "CEX Arb"),
        VOLATILITY_HARVEST("🌊", "Vol Harvest"),
        DEFENSIVE("🛡️", "Defensive")
    }
    
    enum class StructureMode(
        val regime: MarketRegime,
        val emoji: String,
        val label: String
    ) {
        // 26 trading modes across 8 regimes
        FRESH_LAUNCH(MEME_MICRO, "🆕", "Fresh Launch"),
        NARRATIVE_BURST(MEME_MICRO, "📰", "Narrative Burst"),
        MOMENTUM_CONTINUATION(MEME_MOMENTUM, "🚀", "Momentum"),
        BREAKOUT_PLAY(MEME_MOMENTUM, "📈", "Breakout"),
        TREND_FOLLOW(MAJOR_TREND, "📊", "Trend Follow"),
        DIP_BUY(MAJOR_TREND, "🎯", "Dip Buy"),
        VALUE_ACCUMULATION(MID_CAP_VALUE, "💎", "Value Accum"),
        TECHNICAL_SETUP(MID_CAP_VALUE, "📐", "Technical"),
        FUNDING_ARB(PERP_FUNDING, "💰", "Funding Arb"),
        SQUEEZE_HUNTER(PERP_FUNDING, "🔥", "Squeeze"),
        SUPPORT_SNIPE(CEX_ARBITRAGE, "🎯", "Support Snipe"),
        WALL_FADE(CEX_ARBITRAGE, "🧱", "Wall Fade"),
        STRANGLE(VOLATILITY_HARVEST, "🦋", "Strangle"),
        STRADDLE(VOLATILITY_HARVEST, "⚖️", "Straddle"),
        GAMMA_SCALP(VOLATILITY_HARVEST, "⚡", "Gamma Scalp"),
        // ... and more
    }
    
    fun classifyToken(snapshot: CandidateSnapshot): StructureMode {
        // Classification logic based on:
        // - Market cap
        // - Liquidity depth
        // - Volume profile
        // - Social signals
        // - Price action patterns
    }
}
```

### Regime-Specific AI Weights

Each trading mode has custom AI layer weights:

```kotlin
data class ModeConfig(
    val mode: StructureMode,
    val layerWeights: Map<String, Double>,  // AI layer weight overrides
    val positionParams: PositionParams,     // Position sizing
    val exitRules: ExitRules                // Exit conditions
)

// Example: FRESH_LAUNCH mode emphasizes speed and narrative
val FRESH_LAUNCH_CONFIG = ModeConfig(
    mode = StructureMode.FRESH_LAUNCH,
    layerWeights = mapOf(
        "NarrativeDetectorAI" to 1.5,
        "SocialVelocityAI" to 1.4,
        "MomentumPredictorAI" to 1.3,
        "LiquidityDepthAI" to 0.8,     // Less important for fresh launches
        "TimeOptimizationAI" to 0.7
    ),
    positionParams = PositionParams(
        maxSize = 0.05,  // 5% max
        scaleFactor = 0.8
    ),
    exitRules = ExitRules(
        takeProfit = 0.30,   // 30% TP
        stopLoss = -0.15,    // 15% SL
        trailingStop = true
    )
)
```

---

## DECISION PIPELINE

### Stage Flow

```
CANDIDATE → SCORING → CONFIDENCE → PRE-PROPOSAL KILL → FINAL DECISION → EXECUTION
     │          │           │              │                  │              │
     ▼          ▼           ▼              ▼                  ▼              ▼
  Token    21 AI     Statistical    C-grade +         Band +        Jupiter
  Snapshot Layers    + Structural   conf < 35%?       Quality       V2 API
                     + Operational   → SHADOW         Assessment
                     Confidence      TRACK
```

### BotOrchestrator.processCandidate()

```kotlin
suspend fun processCandidate(candidate: CandidateSnapshot): ProcessResult {
    
    // ─── SCORING ───
    val scoreCard = scorer.score(candidate)
    logger.stage("SCORING", candidate.symbol, "OK", 
        "total=${scoreCard.total} components=${scoreCard.components.size}")
    
    // ─── CONFIDENCE ───
    val confidence = confidenceEngine.compute(scoreCard, learningMetrics, opsMetrics)
    logger.stage("CONFIDENCE", candidate.symbol, "OK",
        "stat=${confidence.statistical} struct=${confidence.structural} " +
        "ops=${confidence.operational} eff=${confidence.effective}")
    
    // ─── PRE-PROPOSAL KILL (V3.2) ───
    val earlyQuality = when {
        scoreCard.total >= 55 -> "B"
        scoreCard.total >= 45 -> "B"
        else -> "C"
    }
    val memoryScore = scoreCard.byName("memory")?.value ?: 0
    
    if (earlyQuality == "C") {
        val effConf = confidence.effective
        val shouldKillEarly = (effConf < 35) || (memoryScore <= -8)
        
        if (shouldKillEarly) {
            logger.stage("PRE_PROPOSAL_KILL", candidate.symbol, "BLOCKED",
                "quality=$earlyQuality conf=${effConf}% memory=$memoryScore → SHADOW_TRACK")
            shadowTracker.track(candidate, scoreCard, effConf, "C_GRADE_EARLY_KILL")
            return ProcessResult.Watch(scoreCard.total, effConf)
        }
    }
    
    // ─── FINAL DECISION ───
    val decision = decisionMatrix.decide(scoreCard, confidence)
    
    // ─── LIQUIDITY GATE ───
    val setupQuality = decision.setupQuality
    val liquidityFloor = when (setupQuality) {
        "A" -> 5_000.0
        "B" -> 7_500.0
        else -> 10_000.0  // C-grade needs $10K+ liquidity
    }
    
    if (decision.band.isExecute && candidate.liquidityUsd < liquidityFloor) {
        logger.stage("LIQUIDITY_CHECK", candidate.symbol, "BLOCKED",
            "liq=$${candidate.liquidityUsd} < floor=$${liquidityFloor}")
        return ProcessResult.Watch(decision.finalScore, confidence.effective)
    }
    
    // ─── RETURN RESULT ───
    return when (decision.band) {
        DecisionBand.EXECUTE_AGGRESSIVE -> ProcessResult.Execute(...)
        DecisionBand.EXECUTE_STANDARD -> ProcessResult.Execute(...)
        DecisionBand.EXECUTE_SMALL -> ProcessResult.Execute(...)
        DecisionBand.WATCH -> ProcessResult.Watch(...)
        DecisionBand.SKIP -> ProcessResult.Skip(...)
    }
}
```

---

## SAFETY SYSTEMS

### Defense Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                      SAFETY ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Layer 1: TokenBlacklist                                        │
│  ├── Permanent mint-level bans                                  │
│  ├── Rug pattern detection                                      │
│  └── Historical loss tracking                                   │
│                                                                 │
│  Layer 2: ToxicModeCircuitBreaker                               │
│  ├── Mode-level freeze after 3 losses                          │
│  ├── Auto-unfreeze after 24h cooldown                          │
│  └── Catastrophic loss triggers                                 │
│                                                                 │
│  Layer 3: Pre-Proposal Kill (V3.2)                              │
│  ├── C-grade + conf < 35% → immediate SHADOW_TRACK             │
│  ├── C-grade + memory ≤ -8 → immediate SHADOW_TRACK            │
│  └── Preserves learning without wasting compute                 │
│                                                                 │
│  Layer 4: FinalDecisionGate                                     │
│  ├── Hard confidence floors (30% min)                          │
│  ├── C-grade looper prevention                                  │
│  ├── COPY_TRADE mode completely disabled                       │
│  └── AI degradation checks                                      │
│                                                                 │
│  Layer 5: SecurityGuard                                         │
│  ├── Daily loss limits                                          │
│  ├── Maximum open positions                                     │
│  ├── Trade rate limiting                                        │
│  └── Emergency halt conditions                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### FinalDecisionGate Hard Kills

```kotlin
object FinalDecisionGate {
    
    fun evaluate(decision: PreliminaryDecision): FDGDecision {
        
        // 1. COPY_TRADE - completely banned
        if (decision.entryMode == "COPY_TRADE") {
            return FDGDecision.HARD_KILL("COPY_TRADE_BANNED")
        }
        
        // 2. Confidence floor - garbage
        if (decision.confidence < 30) {
            return FDGDecision.HARD_KILL("CONFIDENCE_FLOOR_30%")
        }
        
        // 3. C-grade + low confidence
        if (decision.quality == "C" && decision.confidence < 35) {
            return FDGDecision.HARD_KILL("C_GRADE_CONFIDENCE_FLOOR_35%")
        }
        
        // 4. AI degraded + low confidence
        if (decision.aiDegraded && decision.confidence < 40) {
            return FDGDecision.HARD_KILL("AI_DEGRADED_CONFIDENCE_FLOOR_40%")
        }
        
        // 5. Toxic flag accumulation (Kris Rule)
        if (decision.toxicFlags >= 3) {
            return FDGDecision.HARD_KILL("TOXIC_FLAG_THRESHOLD_3+")
        }
        
        return FDGDecision.APPROVE(decision)
    }
}
```

---

## DATA PERSISTENCE

### TradeHistoryStore

```kotlin
object TradeHistoryStore {
    private const val PREFS_NAME = "trade_history_store"
    private const val KEY_TRADES = "trades_json"
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
    
    private var prefs: SharedPreferences? = null
    private val trades = mutableListOf<Trade>()
    
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadTrades()
        cleanupOldTrades()
    }
    
    fun recordTrade(trade: Trade) {
        trades.add(trade)
        saveTrades()  // Immediate commit() for persistence
    }
    
    private fun saveTrades() {
        val arr = JSONArray()
        trades.forEach { t ->
            arr.put(JSONObject().apply {
                put("ts", t.ts)
                put("side", t.side)
                put("sol", t.sol)
                put("price", t.price)
                put("pnlSol", t.pnlSol)
                put("pnlPct", t.pnlPct)
                put("mint", t.mint)
                // ... more fields
            })
        }
        prefs?.edit()?.putString(KEY_TRADES, arr.toString())?.commit()
    }
}
```

### Shadow Learning Engine

```kotlin
object ShadowLearningEngine {
    private val shadowTrades = ConcurrentHashMap<String, ShadowTrade>()
    
    data class ShadowTrade(
        val mint: String,
        val entryPrice: Double,
        val entryTime: Long,
        val scoreCard: ScoreCard,
        val confidence: Double,
        val blockReason: String
    )
    
    fun onFdgBlockedTrade(
        candidate: CandidateSnapshot,
        scoreCard: ScoreCard,
        confidence: Double,
        reason: String
    ) {
        // Shadow-track the blocked trade
        shadowTrades[candidate.mint] = ShadowTrade(
            mint = candidate.mint,
            entryPrice = candidate.price,
            entryTime = System.currentTimeMillis(),
            scoreCard = scoreCard,
            confidence = confidence,
            blockReason = reason
        )
    }
    
    fun evaluateShadowOutcome(mint: String, currentPrice: Double) {
        val shadow = shadowTrades[mint] ?: return
        val pnlPct = ((currentPrice - shadow.entryPrice) / shadow.entryPrice) * 100
        
        // Would this blocked trade have won?
        val wouldHaveWon = pnlPct > 10  // 10% threshold for "win"
        
        // Feed back to AI calibration
        MetaCognitionAI.recordShadowOutcome(
            scoreCard = shadow.scoreCard,
            wouldHaveWon = wouldHaveWon,
            actualPnlPct = pnlPct
        )
    }
}
```

---

## EXTERNAL INTEGRATIONS

### Jupiter V2 (Swap Execution)

```kotlin
object JupiterClient {
    private const val BASE_URL = "https://quote-api.jup.ag/v6"
    
    suspend fun getQuote(
        inputMint: String,
        outputMint: String,
        amount: Long,
        slippageBps: Int = 100
    ): QuoteResponse {
        return api.getQuote(
            inputMint = inputMint,
            outputMint = outputMint,
            amount = amount,
            slippageBps = slippageBps,
            onlyDirectRoutes = false,
            asLegacyTransaction = false
        )
    }
    
    suspend fun executeSwap(
        quote: QuoteResponse,
        userPublicKey: String
    ): SwapResult {
        val swapRequest = SwapRequest(
            quoteResponse = quote,
            userPublicKey = userPublicKey,
            wrapAndUnwrapSol = true,
            computeUnitPriceMicroLamports = "auto"
        )
        return api.swap(swapRequest)
    }
}
```

### DexScreener (Market Data)

```kotlin
object DexScreenerClient {
    suspend fun getTokenProfile(mint: String): TokenProfile? {
        return api.getTokenProfile(mint)
    }
    
    suspend fun searchTokens(query: String): List<TokenSearchResult> {
        return api.searchTokens(query)
    }
}
```

### Birdeye (Enhanced Analytics)

```kotlin
object BirdeyeClient {
    suspend fun getTokenOverview(mint: String): TokenOverview? {
        return api.getTokenOverview(mint, apiKey)
    }
    
    suspend fun getOHLCV(mint: String, interval: String): List<Candle> {
        return api.getOHLCV(mint, interval, apiKey)
    }
}
```

---

## PERFORMANCE CHARACTERISTICS

### Resource Usage

| Metric | Target | Measured |
|--------|--------|----------|
| Memory | < 256MB | ~180MB |
| CPU (idle) | < 5% | ~3% |
| CPU (scanning) | < 20% | ~15% |
| Battery | < 10%/hr | ~8%/hr |
| Network | < 1MB/min | ~500KB/min |

### Latency

| Operation | Target | P50 | P99 |
|-----------|--------|-----|-----|
| Market scan | < 5s | 2.1s | 4.8s |
| AI scoring | < 500ms | 180ms | 420ms |
| Trade execution | < 3s | 1.2s | 2.8s |

### Throughput

- Token evaluations: ~200/minute
- Trades executed: Up to 50/hour (rate limited)
- Shadow trades tracked: Unlimited

---

## BUILD & DEPLOYMENT

### Build System

```groovy
// build.gradle.kts
android {
    namespace = "com.lifecyclebot"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.lifecyclebot"
        minSdk = 26
        targetSdk = 34
        versionCode = 320
        versionName = "3.2.0"
    }
}
```

### GitHub Actions CI

```yaml
name: Build APK
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build APK
        run: ./gradlew assembleRelease
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: AATE_v3.2.0.apk
          path: app/build/outputs/apk/release/*.apk
```

---

## APPENDIX: CODE STATISTICS

| Category | Files | Lines |
|----------|-------|-------|
| **Engine** | 45 | 38,000 |
| **AI Layers** | 21 | 15,000 |
| **UI** | 18 | 12,000 |
| **Data** | 12 | 8,000 |
| **Utils** | 25 | 10,000 |
| **V3 Core** | 15 | 12,000 |
| **Tests** | 8 | 3,000 |
| **Total** | 144 | **98,000+** |

---

*Document version: 3.2.0 | Last updated: December 2025*
