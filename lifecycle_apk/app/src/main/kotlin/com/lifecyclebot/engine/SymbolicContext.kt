package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * SymbolicContext — Global symbolic intelligence state
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Maintains a live snapshot of all 16+ symbolic signals, refreshed every
 * scan cycle. Any AI module can query this for symbolic intelligence
 * without needing direct references to every other module.
 *
 * This is the SHARED NERVOUS SYSTEM of AATE.
 *
 * Used by: FinalDecisionGate, EntryIntelligence, HoldingLogicLayer,
 *          ExitIntelligence, BotBrain, EducationAI,
 *          LifecycleStrategy, SentientPersonality, and any future module.
 *
 * V5.9.14:
 *   - Persistence via SharedPreferences (mood/edge survive app restarts)
 *   - bypassMode for A/B testing — when true, refresh() writes neutrals
 */
object SymbolicContext {

    private const val TAG = "SymCtx"
    private const val PREFS_NAME = "symbolic_context"
    private const val K_RISK       = "overallRisk"
    private const val K_CONF       = "overallConfidence"
    private const val K_HEALTH     = "marketHealth"
    private const val K_EDGE       = "edgeStrength"
    private const val K_MOOD       = "emotionalState"
    private const val K_REFRESHED  = "lastRefresh"

    // Live signal snapshot — refreshed every scan cycle
    @Volatile private var signals = mapOf<String, Double>()
    @Volatile private var lastRefresh = 0L

    // Derived composite scores
    @Volatile var overallRisk: Double = 0.3        // 0=safe, 1=danger
    @Volatile var overallConfidence: Double = 0.5   // 0=uncertain, 1=certain
    @Volatile var marketHealth: Double = 0.5        // 0=sick, 1=healthy
    @Volatile var edgeStrength: Double = 0.5        // 0=no edge, 1=strong edge
    @Volatile var emotionalState: String = "NEUTRAL" // GREEDY/FEARFUL/NEUTRAL/EUPHORIC/PANIC

    // V5.9.14: A/B toggle — when true, refresh() freezes to neutral state
    // and persistence is skipped. Used by BacktestActivity.
    @Volatile var bypassMode: Boolean = false

    // V5.9.14: Lazy application context for save/load
    @Volatile private var appContext: Context? = null

    /** Call once from AATEApp.onCreate to wire persistence. */
    fun init(context: Context) {
        appContext = context.applicationContext
        load(context)
    }

    private fun prefs(ctx: Context?): SharedPreferences? =
        try { ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (_: Exception) { null }

    /** Load last persisted symbolic state (called from init). */
    fun load(context: Context) {
        try {
            val p = prefs(context) ?: return
            overallRisk       = p.getFloat(K_RISK,      0.3f).toDouble()
            overallConfidence = p.getFloat(K_CONF,      0.5f).toDouble()
            marketHealth      = p.getFloat(K_HEALTH,    0.5f).toDouble()
            edgeStrength      = p.getFloat(K_EDGE,      0.5f).toDouble()
            emotionalState    = p.getString(K_MOOD,     "NEUTRAL") ?: "NEUTRAL"
            lastRefresh       = p.getLong(K_REFRESHED,  0L)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "load error: ${e.message}")
        }
    }

    /** Persist symbolic state to disk. Throttled to max 1 write per 10 s. */
    @Volatile private var lastSaveTs = 0L
    fun save() {
        if (bypassMode) return
        val now = System.currentTimeMillis()
        if (now - lastSaveTs < 10_000L) return  // V5.9.19: throttle, don't spam prefs
        lastSaveTs = now
        try {
            val p = prefs(appContext) ?: return
            p.edit()
                .putFloat(K_RISK,      overallRisk.toFloat())
                .putFloat(K_CONF,      overallConfidence.toFloat())
                .putFloat(K_HEALTH,    marketHealth.toFloat())
                .putFloat(K_EDGE,      edgeStrength.toFloat())
                .putString(K_MOOD,     emotionalState)
                .putLong(K_REFRESHED,  lastRefresh)
                .apply()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "save error: ${e.message}")
        }
    }

    /**
     * Refresh all signals — called from BotService every scan cycle.
     * V5.9.19: Throttled to max 1 full refresh per 2 s; cheaper callers hit the cache.
     */
    fun refresh(symbol: String = "", mint: String = "") {
        // V5.9.14: Bypass = freeze to neutral baseline (for A/B testing)
        if (bypassMode) {
            signals = emptyMap()
            overallRisk       = 0.3
            overallConfidence = 0.5
            marketHealth      = 0.5
            edgeStrength      = 0.5
            emotionalState    = "NEUTRAL"
            lastRefresh       = System.currentTimeMillis()
            return
        }
        // V5.9.19: Skip recompute if we refreshed less than 2 s ago — FDG.evaluate()
        // runs per candidate (32+/loop) and the 16 subsystems underneath were being
        // queried hundreds of times per second, contributing to app-boot black-screen
        // hangs after the paper-wallet inflation storm.
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 2_000L) return
        try {
            signals = SymbolicExitReasoner.getSignalSnapshot(symbol, mint)
            lastRefresh = now

            // Derive composite scores from raw signals
            val trustAvg = signals["StrategyTrust"] ?: 0.5
            val regime = signals["CrossRegime"] ?: 0.1
            val fragility = signals["Fragility"] ?: 0.3
            val portfolioHeat = signals["PortfolioHeat"] ?: 0.3
            val behaviorTilt = signals["BehaviorTilt"] ?: 0.0
            val shadowWR = signals["ShadowWR"] ?: 0.5
            val education = signals["EducationLevel"] ?: 0.5
            val metaCog = signals["MetaCognition"] ?: 0.5

            // V5.9.212 — expanded composite scores incorporating 24-channel signal set
            val leadLag       = signals["LeadLagMult"] ?: 1.0    // >1 = bullish rotation incoming
            val levSurvival   = signals["LevSurvival"] ?: 0.7    // 0=dangerous, 1=safe
            val execConf      = signals["ExecConfidence"] ?: 0.5
            val collectiveOk  = signals["CollectiveConsensus"] ?: 0.5
            val trustNet      = signals["TrustNetAvg"] ?: 0.5
            val regimeTrans   = signals["RegimeTransitionPressure"] ?: 0.0  // 0=stable, 1=chaotic
            val circuitAgg    = signals["DrawdownCircuitAgg"] ?: 1.0        // 0=circuit tripped, 1=open
            val fundingAgg    = signals["FundingRateAgg"] ?: 1.0

            // Overall Risk: original 4 signals + new regime transition + leverage danger + circuit
            // leadLag < 1.0 means lagging = risk, invert it; circuitAgg < 1 = circuit pulling back
            val leadLagRisk = (1.0 - leadLag.coerceIn(0.5, 1.5) / 1.5).coerceIn(0.0, 1.0)
            val levRisk = (1.0 - levSurvival).coerceIn(0.0, 1.0)
            val circuitRisk = (1.0 - circuitAgg).coerceIn(0.0, 1.0)
            overallRisk = ((regime      * 0.20) +
                           (fragility   * 0.18) +
                           (portfolioHeat * 0.18) +
                           (behaviorTilt * 0.15) +
                           (regimeTrans * 0.12) +
                           (leadLagRisk * 0.09) +
                           (levRisk     * 0.05) +
                           (circuitRisk * 0.03)).coerceIn(0.0, 1.0)

            // Overall Confidence: trust + shadow + education + metacognition + collective + trustNet + execConf
            overallConfidence = ((trustAvg     * 0.22) +
                                 (shadowWR     * 0.22) +
                                 (education    * 0.15) +
                                 (metaCog      * 0.15) +
                                 (collectiveOk * 0.12) +
                                 (trustNet     * 0.08) +
                                 (execConf     * 0.06)).coerceIn(0.0, 1.0)

            // Market Health: inverse of risk + funding + execution health
            val fundingHealth = ((fundingAgg - 0.5) * 0.2).coerceIn(-0.15, 0.1)
            marketHealth = (1.0 - overallRisk * 0.65 - regime * 0.25 + fundingHealth).coerceIn(0.0, 1.0)

            // Edge Strength: confidence + trust + shadow + collective consensus
            edgeStrength = ((trustAvg     * 0.32) +
                            (shadowWR     * 0.30) +
                            (education    * 0.18) +
                            (collectiveOk * 0.20)).coerceIn(0.0, 1.0)

            // V5.9.212 — Emotional State: expanded vocabulary (7 states)
            // ANALYTICAL: high edge but moderate confidence — learning mode
            // CURIOUS:    moderate everything — exploring, no strong signal yet
            // V5.9.319: HARD-OVERRIDE based on actual TradingCopilot recent
            // performance. The composite signals could say 'GREEDY' (trust+
            // edge high) while the bot was 26 losses deep at -32% drawdown.
            // Mood must reflect REALITY, not just upstream subsystem averages.
            val coachMood = try { com.lifecyclebot.engine.TradingCopilot.current() } catch (_: Exception) { null }
            val realityOverride = when (coachMood?.mood) {
                com.lifecyclebot.engine.TradingCopilot.TradeMood.EMERGENCY_BRAKE -> "PANIC"
                com.lifecyclebot.engine.TradingCopilot.TradeMood.PROTECT -> "FEARFUL"
                else -> null
            }
            emotionalState = realityOverride ?: when {
                overallRisk > 0.8                                         -> "PANIC"
                overallRisk > 0.6                                         -> "FEARFUL"
                overallConfidence > 0.75 && overallRisk < 0.25            -> "EUPHORIC"
                overallConfidence > 0.6  && overallRisk < 0.4             -> "GREEDY"
                edgeStrength > 0.65 && overallConfidence in 0.4..0.65     -> "ANALYTICAL"
                overallConfidence < 0.3  && overallRisk > 0.5             -> "FEARFUL"
                overallConfidence in 0.35..0.55 && overallRisk < 0.5      -> "CURIOUS"
                else                                                       -> "NEUTRAL"
            }

            // V5.9.14: Persist latest state (best-effort)
            save()

        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Refresh error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY INTERFACE — any AI module can call these
    // ═══════════════════════════════════════════════════════════════════

    /** Get raw signal value (0.0-1.0). Returns default if not available. */
    fun getSignal(name: String, default: Double = 0.5): Double = signals[name] ?: default

    /** Get all signals. */
    fun getAllSignals(): Map<String, Double> = HashMap(signals)

    /** Is the system in a high-risk state? */
    fun isHighRisk(): Boolean = overallRisk > 0.6

    /** Is the system confident in its edge? */
    fun isConfident(): Boolean = overallConfidence > 0.6

    /** Is the market healthy for trading? */
    fun isMarketHealthy(): Boolean = marketHealth > 0.5

    /** Should we be aggressive (high confidence + low risk)? */
    fun shouldBeAggressive(): Boolean = overallConfidence > 0.65 && overallRisk < 0.35

    /** Should we be defensive (low confidence or high risk)? */
    fun shouldBeDefensive(): Boolean = overallConfidence < 0.4 || overallRisk > 0.55

    /**
     * Get entry adjustment factor.
     * >1.0 = raise entry bar (be more selective)
     * <1.0 = lower entry bar (be more aggressive)
     * 1.0 = neutral
     */
    /**
     * V5.9.212: Entry bar adjustment — now also considers regime transitions + execution.
     * >1.0 = raise entry bar (be more selective)
     * <1.0 = lower entry bar (be more aggressive)
     */
    fun getEntryAdjustment(): Double {
        val base = when {
            emotionalState == "PANIC"       -> 1.6   // Almost nothing passes
            emotionalState == "FEARFUL"     -> 1.35
            emotionalState == "ANALYTICAL"  -> 1.15  // Picky in analysis mode
            shouldBeDefensive()             -> 1.3
            shouldBeAggressive()            -> 0.8
            isHighRisk()                    -> 1.2
            emotionalState == "GREEDY"      -> 0.85
            emotionalState == "EUPHORIC"    -> 0.75  // Lower bar in euphoria (watch out)
            else                            -> 1.0
        }
        // Regime transitions = raise bar (instability)
        val transPressure = getSignal("RegimeTransitionPressure", 0.0)
        val regimeAdj = 1.0 + transPressure * 0.3
        // LeadLag below 0.85 = rotation risk, be selective
        val leadLag = getSignal("LeadLagMult", 1.0)
        val leadAdj = if (leadLag < 0.85) 1.15 else 1.0
        return (base * regimeAdj * leadAdj).coerceIn(0.6, 2.0)
    }

    /**
     * Get position size adjustment factor.
     * V5.9.212: now also scales on DrawdownCircuit + CollectiveConsensus + FundingRate
     * <1.0 = reduce size, >1.0 = increase size, 1.0 = neutral
     */
    fun getSizeAdjustment(): Double {
        val base = when {
            emotionalState == "PANIC"      -> 0.25
            emotionalState == "FEARFUL"    -> 0.55
            emotionalState == "EUPHORIC"   -> 1.2
            emotionalState == "ANALYTICAL" -> 0.85  // Learning mode — smaller size
            emotionalState == "CURIOUS"    -> 0.9
            shouldBeDefensive()            -> 0.7
            shouldBeAggressive()           -> 1.15
            else                           -> 1.0
        }
        // Layer extra adjustments on top
        val circuitAdj  = getSignal("DrawdownCircuitAgg", 1.0).coerceIn(0.2, 1.0) // circuit tripped = smaller
        val collectAdj  = if (getSignal("CollectiveConsensus", 0.5) < 0.3) 0.8 else 1.0 // collective avoid = smaller
        val fundAdj     = ((getSignal("FundingRateAgg", 1.0) - 0.5) * 0.2 + 1.0).coerceIn(0.85, 1.1)
        return (base * circuitAdj * collectAdj * fundAdj).coerceIn(0.2, 1.5)
    }

    /**
     * Get hold patience factor.
     * >1.0 = more patient (let winners run)
     * <1.0 = less patient (cut faster)
     */
    /**
     * V5.9.212: Hold patience — also considers collective network + trust network avg.
     * >1.0 = more patient (let winners run), <1.0 = cut faster
     */
    fun getHoldPatience(): Double {
        val base = when {
            emotionalState == "PANIC"      -> 0.4
            isHighRisk()                   -> 0.6
            emotionalState == "FEARFUL"    -> 0.75
            shouldBeDefensive()            -> 0.8
            emotionalState == "ANALYTICAL" -> 1.1   // Patient in analysis mode
            shouldBeAggressive()           -> 1.3
            isConfident()                  -> 1.2
            emotionalState == "EUPHORIC"   -> 1.35
            emotionalState == "GREEDY"     -> 1.25
            else                           -> 1.0
        }
        // If collective says avoid → cut patience
        val collectOk = getSignal("CollectiveConsensus", 0.5)
        val collectMult = if (collectOk < 0.3) 0.7 else if (collectOk > 0.75) 1.1 else 1.0
        // If trust network avg is healthy → can be patient
        val trustNet = getSignal("TrustNetAvg", 0.5)
        val trustMult = if (trustNet > 0.7) 1.1 else if (trustNet < 0.4) 0.85 else 1.0
        return (base * collectMult * trustMult).coerceIn(0.3, 1.6)
    }

    /** Get freshness — milliseconds since last refresh. */
    fun getAge(): Long = System.currentTimeMillis() - lastRefresh

    /** Is data fresh (<30s old)? */
    fun isFresh(): Boolean = getAge() < 30_000

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.212 — 24-CHANNEL SYMBOLIC HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /** Is the drawdown circuit pulling back aggression significantly? */
    fun isCircuitBreaking(): Boolean = getSignal("DrawdownCircuitAgg", 1.0) < 0.6

    /** Is there an active regime transition in flight? */
    fun isRegimeTransitioning(): Boolean = getSignal("RegimeTransitionPressure", 0.0) > 0.5

    /** Is cross-asset lead-lag rotation warning active? */
    fun isLeadLagWarning(): Boolean = getSignal("LeadLagMult", 1.0) < 0.8

    /** Is execution degraded enough to be cautious about new entries? */
    fun isExecutionDegraded(): Boolean = getSignal("ExecConfidence", 0.5) < 0.4

    /** Is funding rate pulling back aggression? */
    fun isFundingUnfavourable(): Boolean = getSignal("FundingRateAgg", 1.0) < 0.5

    /**
     * Composite "green light" for new entries — all 9 major gates.
     * Returns 0.0-1.0. Higher = more universe agreement that conditions are right.
     */
    fun getEntryGreenLight(): Double {
        val circuitScore = getSignal("DrawdownCircuitAgg", 1.0).coerceIn(0.0, 1.0)
        val collectScore = getSignal("CollectiveConsensus", 0.5).coerceIn(0.0, 1.0)
        val execScore    = getSignal("ExecConfidence", 0.5).coerceIn(0.0, 1.0)
        val fundScore    = getSignal("FundingRateAgg", 1.0).coerceIn(0.0, 1.0)
        val trustScore   = getSignal("TrustNetAvg", 0.5).coerceIn(0.0, 1.0)
        val regimeScore  = 1.0 - getSignal("RegimeTransitionPressure", 0.0).coerceIn(0.0, 1.0)
        val leadRaw      = getSignal("LeadLagMult", 1.0).coerceIn(0.7, 1.3)
        val leadScore    = (leadRaw - 0.7) / 0.6
        val confidScore  = overallConfidence
        val riskScore    = 1.0 - overallRisk
        return ((circuitScore * 0.18) + (collectScore * 0.15) + (execScore * 0.12) +
                (fundScore   * 0.10) + (trustScore  * 0.12) + (regimeScore * 0.12) +
                (leadScore   * 0.08) + (confidScore * 0.08) + (riskScore   * 0.05))
            .coerceIn(0.0, 1.0)
    }

    fun getDiagnostics(): String {
        return "SymCtx: risk=${"%.2f".format(overallRisk)} conf=${"%.2f".format(overallConfidence)} " +
               "health=${"%.2f".format(marketHealth)} edge=${"%.2f".format(edgeStrength)} " +
               "mood=$emotionalState age=${getAge()/1000}s signals=${signals.size} " +
               "greenLight=${"%.2f".format(getEntryGreenLight())} " +
               "circuit=${"%.2f".format(getSignal("DrawdownCircuitAgg", 1.0))}"
    }
}
