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
 *          ExitIntelligence, AdvancedExitManager, BotBrain, EducationAI,
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

            // Overall Risk: high regime risk + high fragility + portfolio heat + tilt = danger
            overallRisk = ((regime * 0.25) + (fragility * 0.25) + (portfolioHeat * 0.25) + (behaviorTilt * 0.25)).coerceIn(0.0, 1.0)

            // Overall Confidence: trust + shadow performance + education + metacognition
            overallConfidence = ((trustAvg * 0.3) + (shadowWR * 0.3) + (education * 0.2) + (metaCog * 0.2)).coerceIn(0.0, 1.0)

            // Market Health: inverse of risk weighted by regime
            marketHealth = (1.0 - overallRisk * 0.7 - regime * 0.3).coerceIn(0.0, 1.0)

            // Edge Strength: confidence weighted by trust and shadow
            edgeStrength = ((trustAvg * 0.4) + (shadowWR * 0.4) + (education * 0.2)).coerceIn(0.0, 1.0)

            // Emotional State: derived from risk + confidence
            emotionalState = when {
                overallRisk > 0.8                                  -> "PANIC"
                overallRisk > 0.6                                  -> "FEARFUL"
                overallConfidence > 0.7 && overallRisk < 0.3       -> "EUPHORIC"
                overallConfidence > 0.6 && overallRisk < 0.4       -> "GREEDY"
                overallConfidence < 0.3 && overallRisk > 0.5       -> "FEARFUL"
                else                                                -> "NEUTRAL"
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
    fun getEntryAdjustment(): Double {
        return when {
            shouldBeDefensive()  -> 1.3   // Raise the bar 30%
            shouldBeAggressive() -> 0.8   // Lower the bar 20%
            isHighRisk()         -> 1.2   // Moderate raise
            else                 -> 1.0   // Neutral
        }
    }

    /**
     * Get position size adjustment factor.
     * <1.0 = reduce size (high risk)
     * >1.0 = increase size (high confidence + low risk)
     * 1.0 = neutral
     */
    fun getSizeAdjustment(): Double {
        return when {
            emotionalState == "PANIC"    -> 0.3   // Cut to 30%
            emotionalState == "FEARFUL"  -> 0.6   // Cut to 60%
            emotionalState == "EUPHORIC" -> 1.2   // Boost 20%
            shouldBeDefensive()          -> 0.7
            shouldBeAggressive()         -> 1.15
            else                         -> 1.0
        }
    }

    /**
     * Get hold patience factor.
     * >1.0 = more patient (let winners run)
     * <1.0 = less patient (cut faster)
     */
    fun getHoldPatience(): Double {
        return when {
            isHighRisk()         -> 0.6   // Cut fast
            shouldBeDefensive()  -> 0.8
            shouldBeAggressive() -> 1.3   // Let winners breathe
            isConfident()        -> 1.2
            else                 -> 1.0
        }
    }

    /** Get freshness — milliseconds since last refresh. */
    fun getAge(): Long = System.currentTimeMillis() - lastRefresh

    /** Is data fresh (<30s old)? */
    fun isFresh(): Boolean = getAge() < 30_000

    fun getDiagnostics(): String {
        return "SymCtx: risk=${"%.2f".format(overallRisk)} conf=${"%.2f".format(overallConfidence)} " +
               "health=${"%.2f".format(marketHealth)} edge=${"%.2f".format(edgeStrength)} " +
               "mood=$emotionalState age=${getAge()/1000}s signals=${signals.size}"
    }
}
