package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * BotBrain — the self-learning engine
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Three layers of learning, each with a different time horizon:
 *
 * LAYER 1 — Statistical pattern recognition (runs every 20 trades)
 * ──────────────────────────────────────────────────────────────────
 * Analyses the local database. No external calls. Instant.
 * Tracks win rates per signal combination and directly adjusts
 * strategy thresholds in BotConfig based on what's working.
 *
 * Examples of what it learns:
 *   - "BULL_FAN + pumping phase → 78% win rate over 32 trades → lower entry threshold by 5"
 *   - "reclaim_attempt + FLAT EMA → 28% win rate → raise entry threshold by 8"
 *   - "DEX_TRENDING source → 61% win rate, average +94% → boost discovery score"
 *   - "held > 45 mins on RANGE_TRADE → exits too late → tighten trailing stop"
 *
 * LAYER 2 — Groq LLM pattern analysis (runs every 50 trades or weekly)
 * ──────────────────────────────────────────────────────────────────────
 * Sends a structured summary of recent trades to Groq (llama-3.3-70b-versatile).
 * Asks it to identify patterns, explain what's working vs failing, and
 * suggest specific parameter changes. Returns structured JSON.
 * Falls back gracefully if no key or rate limited.
 *
 * LAYER 3 — Regime detection (runs every poll cycle)
 * ──────────────────────────────────────────────────
 * Real-time market regime classifier. Detects if the broader Solana
 * market is bullish, bearish, sideways, or high-volatility. Adjusts
 * entry thresholds globally — be more selective in bear regimes,
 * more aggressive in bull regimes.
 *
 * PARAMETER ADJUSTMENTS:
 * ─────────────────────────────────────────────────────────────────────
 * The brain only adjusts parameters within safe bounds. It can never:
 *   - Lower entry threshold below 35 (stops junk entries)
 *   - Raise entry threshold above 70 (stops missing real moves)
 *   - Reduce stop loss below 5% (minimum protection)
 *   - Change more than 3 parameters at once (prevents thrashing)
 *   - Make changes on fewer than 10 trades per signal (not enough data)
 *
 * All changes are logged to param_history in the database.
 * The UI shows exactly what the brain changed and why.
 */
class BotBrain(
    private val ctx: Context,
    private val db: TradeDatabase,
    private val cfg: () -> BotConfig,
    private val onLog: (String) -> Unit,
    private val onParamChanged: (String, Double, Double, String) -> Unit,
) {
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http   = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Learned parameter overrides ───────────────────────────────────
    // These start at 0 (no adjustment) and drift based on what the bot learns.
    // Applied on top of config values — config is the baseline, brain is the delta.
    @Volatile var entryThresholdDelta: Double  = 0.0   // + means stricter
    @Volatile var exitThresholdDelta: Double   = 0.0   // + means easier to exit
    @Volatile var regimeBullMult: Double       = 1.0   // size multiplier in bull regime
    @Volatile var regimeBearMult: Double       = 1.0   // size multiplier in bear regime
    @Volatile var phaseBoosts: Map<String, Double> = emptyMap()  // phase → entry score delta
    @Volatile var sourceBoosts: Map<String, Double> = emptyMap() // source → discovery score delta
    @Volatile var currentRegime: String        = "UNKNOWN"
    @Volatile var lastAnalysis: String         = "No analysis yet — needs 20+ trades"
    @Volatile var totalTradesAnalysed: Int     = 0
    @Volatile var lastLlmInsight: String       = ""

    // ── ADAPTIVE HARD BLOCK THRESHOLDS ───────────────────────────────────
    // These thresholds are learned from trade outcomes.
    // Start with defaults and adjust based on what actually causes losses.
    
    /** Rugcheck score threshold - tokens below this are blocked */
    @Volatile var learnedRugcheckThreshold: Int = 15  // Default: block ≤15
    
    /** Minimum buy pressure % - below this is extreme sell pressure */
    @Volatile var learnedMinBuyPressure: Double = 15.0  // Default: block <15%
    
    /** Max top holder % - above this is rug risk */
    @Volatile var learnedMaxTopHolder: Double = 70.0  // Default: block >70%
    
    /** Min liquidity USD - below this can't trade effectively */
    @Volatile var learnedMinLiquidity: Double = 100.0  // Default: block <$100
    
    // Stats for threshold learning
    private val rugcheckLosses = mutableMapOf<Int, Int>()  // score bucket → loss count
    private val rugcheckWins = mutableMapOf<Int, Int>()    // score bucket → win count
    private val pressureLosses = mutableMapOf<Int, Int>()  // pressure bucket → loss count
    private val pressureWins = mutableMapOf<Int, Int>()    // pressure bucket → win count
    private val topHolderLosses = mutableMapOf<Int, Int>() // holder% bucket → loss count
    private val topHolderWins = mutableMapOf<Int, Int>()   // holder% bucket → win count

    // ══════════════════════════════════════════════════════════════════
    // ROLLING MEMORY SYSTEM
    // Split into global (long-term) and recent (current market) memory
    // Recent trades have higher weight for adaptive behavior
    // ══════════════════════════════════════════════════════════════════
    
    companion object {
        const val RECENT_MEMORY_SIZE = 200      // Last 200 trades = high weight
        const val GLOBAL_MEMORY_SIZE = 2000     // Long-term pattern storage
        const val RECENT_WEIGHT = 0.7           // 70% weight to recent memory
        const val GLOBAL_WEIGHT = 0.3           // 30% weight to global memory
        const val DECAY_HALF_LIFE = 50          // Trades until weight halves
        
        // LIVE TRADE WEIGHTING
        // Live trades count 3x more than paper trades because:
        // 1. They're validated with real money
        // 2. They prove the strategy works in production
        // 3. They include real slippage, fees, and execution factors
        const val LIVE_TRADE_WEIGHT = 3.0
    }
    
    /**
     * Trade record for memory storage with timestamp for decay calculation
     */
    data class MemoryTrade(
        val timestamp: Long,
        val isWin: Boolean,
        val pnlPct: Double,
        val phase: String,
        val emaFan: String,
        val source: String,
        val rugcheckScore: Int,
        val buyPressure: Double,
        val topHolderPct: Double,
        val liquidityUsd: Double,
    )
    
    // Recent memory - last N trades, high weight, adapts quickly
    private val recentMemory = ArrayDeque<MemoryTrade>(RECENT_MEMORY_SIZE + 10)
    
    // Global memory - long-term patterns, lower weight, stable knowledge
    private val globalMemory = ArrayDeque<MemoryTrade>(GLOBAL_MEMORY_SIZE + 10)
    
    // Aggregated stats from both memories (computed periodically)
    data class MemoryStats(
        val recentWinRate: Double,
        val globalWinRate: Double,
        val blendedWinRate: Double,
        val recentAvgPnl: Double,
        val globalAvgPnl: Double,
        val recentTradeCount: Int,
        val globalTradeCount: Int,
        val topPhases: List<Pair<String, Double>>,      // phase → win rate
        val topSources: List<Pair<String, Double>>,     // source → win rate
        val dangerZones: List<String>,                   // patterns to avoid
    )
    
    @Volatile var memoryStats: MemoryStats = MemoryStats(
        recentWinRate = 0.0, globalWinRate = 0.0, blendedWinRate = 0.0,
        recentAvgPnl = 0.0, globalAvgPnl = 0.0,
        recentTradeCount = 0, globalTradeCount = 0,
        topPhases = emptyList(), topSources = emptyList(), dangerZones = emptyList()
    )

    private var lastStatAnalysisTrades = 0
    @Volatile private var analysisRunning = false
    private var lastLlmAnalysisTrades  = 0

    // ── Bad behaviour registry ────────────────────────────────────────
    // In-memory cache of confirmed/suppressed patterns.
    // Updated after every statistical analysis. Applied before every entry.
    // Key = featureKey (e.g. "phase=dying+ema=BEAR_FAN")
    // Value = suppression strength (0-100 pts subtracted from entry score)
    @Volatile var suppressedPatterns: Map<String, Double> = emptyMap()
    @Volatile var badBehaviourLog: List<BadBehaviourEntry> = emptyList()
    @Volatile var totalSuppressedPatterns: Int = 0

    // ── Start / Stop ──────────────────────────────────────────────────

    fun start() {
        // Restore learned state from database before the first trade
        // so the brain doesn't reset to zero on every restart
        restoreFromDatabase()
        loadThresholdsFromPrefs()  // Load adaptive thresholds
        loadMemoryFromPrefs()      // Load rolling memory system
        scope.launch { brainLoop() }
        onLog("🧠 BotBrain online — self-learning active " +
              "(entry delta ${String.format("%+.1f", entryThresholdDelta)}, " +
              "regime mult ${String.format("%.2f", regimeBullMult)}×, " +
              "rugcheck≤$learnedRugcheckThreshold, " +
              "memory[${recentMemory.size}/${globalMemory.size}])")
    }

    /**
     * Restore learned state from the trade database.
     * Called once on start — rebuilds thresholds, boosts, and regime mult
     * from all trades seen so far so learning survives restarts.
     * 
     * NOTE: Scratch trades (isWin == null) are excluded from learning calculations.
     */
    private fun restoreFromDatabase() {
        try {
            val allTrades = db.getRecentTrades(500)
            // Filter out scratch trades - only learn from meaningful outcomes
            val trades = allTrades.filter { it.isWin != null }
            if (trades.size < 10) return

            val wr = trades.count { it.isWin == true }.toDouble() / trades.size

            // Restore entry threshold delta from overall win rate
            entryThresholdDelta = when {
                wr >= 0.80 -> -6.0
                wr >= 0.72 -> -3.0
                wr >= 0.65 ->  0.0
                wr >= 0.50 ->  3.0
                else       ->  8.0
            }

            // Restore regime multiplier
            regimeBullMult = when {
                wr >= 0.80 -> 1.40
                wr >= 0.72 -> 1.20
                wr >= 0.65 -> 1.00
                else       -> 0.80
            }

            // Restore phase boosts from phase win rates
            val newPhaseBoosts = mutableMapOf<String, Double>()
            trades.groupBy { it.entryPhase }.forEach { (phase, pt) ->
                if (pt.size >= 8) {
                    val pWr = pt.count { it.isWin == true }.toDouble() / pt.size
                    newPhaseBoosts[phase] = when {
                        pWr >= 0.75 -> -5.0
                        pWr <= 0.40 ->  8.0
                        else        ->  0.0
                    }
                }
            }
            phaseBoosts = newPhaseBoosts

            // Restore source boosts
            val newSourceBoosts = mutableMapOf<String, Double>()
            trades.groupBy { it.source }.forEach { (src, st) ->
                if (st.size >= 8) {
                    val sWr = st.count { it.isWin == true }.toDouble() / st.size
                    if (sWr >= 0.70) newSourceBoosts[src] = -3.0
                    else if (sWr <= 0.45) newSourceBoosts[src] = 5.0
                }
            }
            sourceBoosts = newSourceBoosts

            // Restore suppressed patterns from bad_behaviour table
            evaluateBadBehaviours()

            onLog("🧠 Brain restored from ${trades.size} trades: " +
                  "WR ${(wr*100).toInt()}% entry_delta=${String.format("%+.1f", entryThresholdDelta)} " +
                  "regime=${String.format("%.2f", regimeBullMult)}×")
        } catch (e: Exception) {
            onLog("🧠 Brain restore failed (fresh start): ${e.message}")
        }
    }

    fun stop() { 
        // Save rolling memory before stopping
        saveMemoryToPrefs()
        saveThresholdsToPrefs()
        scope.cancel() 
        onLog("🧠 BotBrain stopped — memory saved (${recentMemory.size} recent, ${globalMemory.size} global)")
    }

    // ── Main brain loop ───────────────────────────────────────────────

    private suspend fun brainLoop() {
        while (scope.isActive) {
            try {
                val totalTrades = db.getTotalTrades()
                totalTradesAnalysed = totalTrades

                // Layer 1: Statistical analysis every 20 new trades (non-blocking)
                if (totalTrades - lastStatAnalysisTrades >= 20 && totalTrades >= 10) {
                    lastStatAnalysisTrades = totalTrades
                    if (!analysisRunning) scope.launch {
                        analysisRunning = true
                        try {
                            runStatisticalAnalysis()
                            evaluateBadBehaviours()
                        } finally {
                            analysisRunning = false
                        }
                    }
                }

                // Layer 2: LLM deep analysis every 50 trades
                if (totalTrades - lastLlmAnalysisTrades >= 50 && totalTrades >= 20
                    && cfg().groqApiKey.isNotBlank()) {
                    scope.launch { runLlmAnalysis() }
                    lastLlmAnalysisTrades = totalTrades
                }

                delay(60_000L)  // check every minute
            } catch (e: Exception) {
                onLog("BotBrain error: ${e.message?.take(60)}")
                delay(120_000L)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // LAYER 1 — Statistical pattern recognition
    // ══════════════════════════════════════════════════════════════════

    private fun runStatisticalAnalysis() {
        val trades = db.getRecentTrades(300)
        if (trades.size < 10) return

        val report = StringBuilder()
        report.appendLine("📊 Brain analysis — ${trades.size} trades")

        val changes = mutableListOf<String>()
        var entryDelta    = 0.0
        var exitDelta     = 0.0
        val newPhaseBoosts = mutableMapOf<String, Double>()
        val newSourceBoosts = mutableMapOf<String, Double>()

        // ── Phase analysis ────────────────────────────────────────────
        val phaseGroups = trades.groupBy { it.entryPhase }
        phaseGroups.forEach { (phase, phaseTrades) ->
            if (phaseTrades.size < 8) return@forEach
            val wr = phaseTrades.count { it.isWin == true }.toDouble() / phaseTrades.size * 100
            val avgPnl = phaseTrades.map { it.pnlPct }.average()
            val expected = 60.0  // baseline expected win rate

            when {
                wr >= 75 && avgPnl > 80 -> {
                    // Excellent — lower threshold to catch more
                    newPhaseBoosts[phase] = -5.0
                    report.appendLine("  ✅ $phase: ${wr.toInt()}% WR avg +${avgPnl.toInt()}% → boost entry")
                    changes.add("$phase threshold -5")
                }
                wr <= 40 && phaseTrades.size >= 10 -> {
                    // Struggling — raise threshold to be more selective
                    newPhaseBoosts[phase] = +8.0
                    report.appendLine("  ❌ $phase: ${wr.toInt()}% WR → tighten entry")
                    changes.add("$phase threshold +8")
                }
                else -> {
                    newPhaseBoosts[phase] = 0.0
                }
            }
        }

        // ── Record bad observations for confirmed losing patterns ────────
        // Every time we identify a losing phase/signal combo, we record it
        // to the bad_behaviour table so it accumulates evidence over time.
        phaseGroups.forEach { (phase, phaseTrades) ->
            if (phaseTrades.size < 4) return@forEach
            val wr = phaseTrades.count { it.isWin == true }.toDouble() / phaseTrades.size * 100
            if (wr <= 45.0) {
                phaseTrades.filter { it.isWin == false }.forEach { t ->
                    val key = "phase=${phase}+ema=${t.emaFan}"
                    db.recordBadObservation(
                        featureKey    = key,
                        behaviourType = "ENTRY_SIGNAL",
                        description   = "Entering $phase with ${t.emaFan} EMA — consistently losing",
                        lossPct       = t.pnlPct,
                    )
                }
            }
            // Also record wins to allow recovery
            if (wr >= 60.0) phaseTrades.filter { it.isWin == true }.forEach { t ->
                db.recordGoodObservation("phase=${phase}+ema=${t.emaFan}")
            }
        }

        // ── EMA fan analysis ──────────────────────────────────────────
        val fanGroups = trades.groupBy { it.emaFan }
        fanGroups.forEach { (fan, fanTrades) ->
            if (fanTrades.size < 8) return@forEach
            val wr = fanTrades.count { it.isWin == true }.toDouble() / fanTrades.size * 100
            when {
                fan == "BULL_FAN" && wr >= 70 -> {
                    entryDelta -= 3.0
                    report.appendLine("  📈 BULL_FAN ${wr.toInt()}% WR → reward with lower threshold")
                }
                fan == "FLAT" && wr <= 45 -> {
                    entryDelta += 5.0
                    report.appendLine("  ⚠️ FLAT EMA ${wr.toInt()}% WR → raise threshold")
                }
            }
        }

        // CHANGE 8: Press position sizing on hot streaks
        // When the edge is working, deploy more capital (within SmartSizer caps)
        val overallWr = trades.count { it.isWin == true }.toDouble() / trades.size * 100
        val last10Win = trades.takeLast(10).count { it.isWin == true }
        when {
            overallWr >= 80 && trades.size >= 20 -> {
                regimeBullMult = (regimeBullMult * 1.20).coerceAtMost(1.50)
                report.appendLine("  🔥 WR ${overallWr.toInt()}% → pressing size (${(regimeBullMult*100).toInt()}%)")
                changes.add("regime_mult ${(regimeBullMult*100).toInt()}% (hot streak)")
            }
            overallWr >= 72 && last10Win >= 8 -> {
                regimeBullMult = (regimeBullMult * 1.10).coerceAtMost(1.35)
                report.appendLine("  📈 ${last10Win}/10 recent + ${overallWr.toInt()}% WR → size up")
            }
            overallWr <= 45 -> {
                regimeBullMult = (regimeBullMult * 0.85).coerceAtLeast(0.60)
                report.appendLine("  ⚠️ WR ${overallWr.toInt()}% → pulling back size")
                changes.add("regime_mult ${(regimeBullMult*100).toInt()}% (struggling)")
            }
        }

        // ── Source analysis ───────────────────────────────────────────
        val sourceGroups = trades.groupBy { it.source }
        sourceGroups.forEach { (source, srcTrades) ->
            if (srcTrades.size < 5) return@forEach
            val wr = srcTrades.count { it.isWin == true }.toDouble() / srcTrades.size * 100
            val avgPnl = srcTrades.map { it.pnlPct }.average()
            when {
                wr >= 70 -> { newSourceBoosts[source] = +10.0
                    report.appendLine("  🌐 $source: ${wr.toInt()}% WR → boost discovery score") }
                wr <= 40 -> { newSourceBoosts[source] = -15.0
                    report.appendLine("  🔴 $source: ${wr.toInt()}% WR → lower priority") }
                else     -> { newSourceBoosts[source] = 0.0 }
            }
        }

        // Record bad sources
        sourceGroups.forEach { (source, srcTrades) ->
            if (srcTrades.size < 5) return@forEach
            val wr = srcTrades.count { it.isWin == true }.toDouble() / srcTrades.size * 100
            if (wr <= 40.0) {
                srcTrades.filter { it.isWin == false }.forEach { t ->
                    db.recordBadObservation(
                        featureKey    = "source=${source}",
                        behaviourType = "SOURCE",
                        description   = "Source $source has ${wr.toInt()}% win rate — poor signal quality",
                        lossPct       = t.pnlPct,
                    )
                }
            }
        }

        // ── Hold time analysis ────────────────────────────────────────
        val avgHoldWins  = trades.filter { it.isWin == true && it.heldMins > 0 }.map { it.heldMins }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgHoldLoss  = trades.filter { it.isWin == false && it.heldMins > 0 }.map { it.heldMins }.let { if (it.isEmpty()) 0.0 else it.average() }
        if (avgHoldLoss > avgHoldWins * 1.5 && avgHoldLoss > 10) {
            exitDelta += 3.0
            report.appendLine("  ⏱ Losses held ${avgHoldLoss.toInt()}m vs wins ${avgHoldWins.toInt()}m → tighten exit")
            changes.add("exit threshold +3 (holding losers too long)")
        }

        // Record bad exit timing as a behaviour pattern
        if (avgHoldLoss > avgHoldWins * 1.5 && avgHoldLoss > 10) {
            db.recordBadObservation(
                featureKey    = "exit_timing=hold_too_long",
                behaviourType = "EXIT_TIMING",
                description   = "Holding losers ${avgHoldLoss.toInt()}m vs winners ${avgHoldWins.toInt()}m — exits too slow on bad trades",
                lossPct       = -avgHoldLoss * 0.5,  // approximate cost
            )
        }

        // ── MTF analysis ──────────────────────────────────────────────
        val mtfGroups = trades.groupBy { it.mtf5m }
        mtfGroups.forEach { (mtf, mtfTrades) ->
            if (mtfTrades.size < 8) return@forEach
            val wr = mtfTrades.count { it.isWin == true }.toDouble() / mtfTrades.size * 100
            if (mtf == "BEAR" && wr <= 40) {
                entryDelta += 6.0
                report.appendLine("  📉 MTF BEAR ${wr.toInt()}% WR → raise threshold in bear 5m")
            } else if (mtf == "BULL" && wr >= 68) {
                entryDelta -= 4.0
                report.appendLine("  📈 MTF BULL ${wr.toInt()}% WR → lower threshold in bull 5m")
            }
        }

        // ── Overall win rate ─────────────────────────────────────────
        // Use overallWr computed earlier in CHANGE 8
        val avgWinPnl = trades.filter { it.isWin == true }.map { it.pnlPct }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgLossPnl = trades.filter { it.isWin == false }.map { it.pnlPct }.let { if (it.isEmpty()) 0.0 else it.average() }
        report.appendLine("  Overall: ${overallWr.toInt()}% WR  avgWin:+${avgWinPnl.toInt()}%  avgLoss:${avgLossPnl.toInt()}%")

        // ── Apply changes with safety bounds ─────────────────────────
        entryThresholdDelta  = (entryThresholdDelta + entryDelta).coerceIn(-12.0, +15.0)
        exitThresholdDelta   = (exitThresholdDelta  + exitDelta).coerceIn(-8.0, +10.0)
        phaseBoosts          = newPhaseBoosts
        sourceBoosts         = newSourceBoosts

        if (changes.isNotEmpty()) {
            report.appendLine("  Changes: ${changes.joinToString(", ")}")
            changes.forEach { change ->
                db.recordParamChange(change, 0.0, 0.0, "statistical_analysis", trades.size, overallWr)
            }
        }

        lastAnalysis = report.toString()
        onLog("🧠 ${report.lines().first()}")
        if (changes.isNotEmpty()) onLog("🧠 Adjusted: ${changes.joinToString(" | ")}")
    }

    // ══════════════════════════════════════════════════════════════════
    // BAD BEHAVIOUR EVALUATION — runs after every statistical analysis
    // ══════════════════════════════════════════════════════════════════

    /**
     * Evaluates accumulated evidence and promotes patterns to CONFIRMED_BAD
     * or SUPPRESSED status. Updates the in-memory suppression map used by
     * LifecycleStrategy to penalise entry scores in real time.
     *
     * Rules for escalation:
     *   MONITORING   → evidence accumulating, soft penalty applied
     *   CONFIRMED_BAD → 8+ trades, WR ≤ 38% — strong penalty (-45 pts)
     *   SUPPRESSED   → 8+ trades, WR ≤ 25% — near-block (-80 pts on entry score)
     *
     * The LLM layer CANNOT override CONFIRMED_BAD or SUPPRESSED patterns.
     * Only human override (clearBadPattern) can lift a suppression.
     * This ensures the bot never un-learns a hard-earned lesson.
     */
    private fun evaluateBadBehaviours() {
        val promoted = db.evaluateBadPatterns()
        val allBad   = db.getBadPatterns()

        // Update in-memory cache
        suppressedPatterns    = allBad.associate { it.featureKey to it.suppressionStrength }
        badBehaviourLog       = allBad
        totalSuppressedPatterns = allBad.count { it.status != "MONITORING" }

        // Log newly promoted patterns
        promoted.forEach { bad ->
            val icon = if (bad.status == "SUPPRESSED") "🚫" else "⚠️"
            onLog("$icon BAD PATTERN ${bad.status}: ${bad.featureKey}")
            onLog("   ${bad.notes}")
            onParamChanged(
                "bad_behaviour:${bad.featureKey}",
                0.0,
                bad.suppressionStrength,
                "${bad.status} — ${bad.notes}"
            )
        }

        if (allBad.isNotEmpty()) {
            onLog("🧠 Bad behaviour registry: ${allBad.size} patterns " +
                  "(${allBad.count{it.status=="SUPPRESSED"}} suppressed, " +
                  "${allBad.count{it.status=="CONFIRMED_BAD"}} confirmed bad)")
        }
    }

    /**
     * Get suppression penalty for a specific entry context.
     * Called by LifecycleStrategy before finalising entry score.
     * Returns points to SUBTRACT from entry score (0 = no penalty).
     *
     * Checks multiple feature key combinations:
     *   - phase + ema_fan combo
     *   - source alone
     *   - exit timing patterns
     * Takes the maximum penalty across all matching keys.
     */
    fun getSuppressionPenalty(phase: String, emaFan: String, source: String = ""): Double {
        val keys = buildList {
            add("phase=${phase}+ema=${emaFan}")
            add("phase=${phase}")
            add("ema=${emaFan}")
            if (source.isNotBlank()) add("source=${source}")
        }
        return keys.maxOfOrNull { suppressedPatterns[it] ?: 0.0 } ?: 0.0
    }

    /** Whether a specific pattern is hard-suppressed (near-blocked) */
    fun isHardSuppressed(phase: String, emaFan: String): Boolean {
        val key = "phase=${phase}+ema=${emaFan}"
        return (suppressedPatterns[key] ?: 0.0) >= 70.0
    }

    /** Full bad behaviour report for UI display */
    fun getBadBehaviourReport(): String = buildString {
        if (badBehaviourLog.isEmpty()) {
            appendLine("No bad patterns identified yet — needs more trades")
            return@buildString
        }
        appendLine("🚫 Bad Behaviour Log (${badBehaviourLog.size} patterns)")
        appendLine()
        badBehaviourLog.sortedByDescending { it.suppressionStrength }.forEach { bad ->
            val icon = when (bad.status) {
                "SUPPRESSED"    -> "🚫"
                "CONFIRMED_BAD" -> "⚠️"
                else            -> "👁"
            }
            appendLine("$icon [${bad.status}] ${bad.featureKey}")
            appendLine("   Penalty: -${bad.suppressionStrength.toInt()} pts | ${bad.notes}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // LAYER 2 — Groq LLM deep analysis
    // ══════════════════════════════════════════════════════════════════

    private suspend fun runLlmAnalysis() {
        val trades = db.getRecentTrades(100)
        if (trades.size < 20) return

        val topSignals  = db.getTopSignals(minTrades = 5, limit = 10)
        val worstSignals = db.getTopSignals(minTrades = 5, limit = 10)
            .sortedBy { it.winRate }.take(5)

        // Build a structured summary for the LLM
        val tradeSummary = trades.take(50).map { t ->
            mapOf(
                "phase"   to t.entryPhase,
                "ema_fan" to t.emaFan,
                "mtf_5m"  to t.mtf5m,
                "mode"    to t.mode,
                "source"  to t.source,
                "score"   to t.entryScore.toInt(),
                "held_m"  to t.heldMins.toInt(),
                "pnl_pct" to t.pnlPct.toInt(),
                "win"     to t.isWin,
                "liq_k"   to (t.liquidityUsd / 1000).toInt(),
                "age_h"   to t.tokenAgeHours.toInt(),
            )
        }

        val prompt = """
You are analysing trade data for a Solana DEX trading bot. Your job is to identify patterns
and suggest specific parameter adjustments to improve performance.

RECENT TRADE SUMMARY (${trades.size} trades):
Overall win rate: ${(trades.count{it.isWin == true}.toDouble()/trades.size*100).toInt()}%
Avg win: +${trades.filter{it.isWin == true}.map{it.pnlPct}.let{if(it.isEmpty())0.0 else it.average()}.toInt()}%
Avg loss: ${trades.filter{it.isWin == false}.map{it.pnlPct}.let{if(it.isEmpty())0.0 else it.average()}.toInt()}%

TOP PERFORMING SIGNALS:
${topSignals.joinToString("\n") { "  ${it.featureKey}: ${it.winRate.toInt()}% WR avg +${it.avgPnlPct.toInt()}% (${it.trades} trades)" }}

WORST PERFORMING SIGNALS:
${worstSignals.joinToString("\n") { "  ${it.featureKey}: ${it.winRate.toInt()}% WR avg ${it.avgPnlPct.toInt()}% (${it.trades} trades)" }}

LAST 50 TRADES (phase|ema_fan|mtf|mode|score|held_mins|pnl_pct|win):
${tradeSummary.joinToString("\n") { t -> "  ${t["phase"]}|${t["ema_fan"]}|${t["mtf_5m"]}|${t["mode"]}|${t["score"]}|${t["held_m"]}m|${t["pnl_pct"]}%|${if(t["win"]==true)"W" else "L"}" }}

CURRENT STRATEGY PARAMETERS:
- Entry threshold: ${cfg().let{42 + entryThresholdDelta.toInt()}}
- Exit threshold: 58
- Entry threshold delta from learning: ${entryThresholdDelta.toInt()}

Analyse this data and respond with ONLY valid JSON in this exact format:
{
  "summary": "2-3 sentence plain English summary of what's working and what isn't",
  "top_pattern": "the single most profitable pattern you see",
  "problem_pattern": "the single most problematic pattern you see", 
  "entry_delta": <number between -8 and +8, negative = lower threshold = more trades>,
  "exit_delta": <number between -5 and +5, negative = hold longer>,
  "phase_adjustments": {"phase_name": <delta number>, ...},
  "insight": "one actionable sentence the bot should act on immediately"
}
        """.trimIndent()

        try {
            val requestBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("max_tokens", 600)
                put("temperature", 0.2)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
            }.toString().toRequestBody("application/json".toMediaType())

            val response = http.newCall(
                Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer ${cfg().groqApiKey}")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()
            ).execute()

            if (!response.isSuccessful) return
            val body    = response.body?.string() ?: return
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: return

            // Parse the JSON response
            val clean = content.trim().removePrefix("```json").removeSuffix("```").trim()
            val result = JSONObject(clean)

            val summary  = result.optString("summary","")
            val topPat   = result.optString("top_pattern","")
            val probPat  = result.optString("problem_pattern","")
            val eDelta   = result.optDouble("entry_delta", 0.0).coerceIn(-8.0, 8.0)
            val xDelta   = result.optDouble("exit_delta", 0.0).coerceIn(-5.0, 5.0)
            val insight  = result.optString("insight","")

            // Apply LLM suggestions — but NEVER override confirmed bad behaviour
            // The LLM can suggest lowering thresholds but cannot un-suppress
            // patterns that have earned their CONFIRMED_BAD or SUPPRESSED status.
            // This is the critical rule: learn good, lock in bad.
            val blendedEntry = entryThresholdDelta * 0.4 + eDelta * 0.6
            val blendedExit  = exitThresholdDelta  * 0.4 + xDelta * 0.6
            // Only apply LLM delta if it doesn't conflict with our bad pattern suppression
            val suppressedCount = suppressedPatterns.values.count { it >= 45.0 }
            val llmEntryGuard = if (suppressedCount > 3) {
                // Many bad patterns confirmed — don't let LLM lower threshold globally
                blendedEntry.coerceAtLeast(entryThresholdDelta - 1.0)
            } else blendedEntry
            entryThresholdDelta = llmEntryGuard.coerceIn(-12.0, 15.0)
            exitThresholdDelta  = blendedExit.coerceIn(-8.0, 10.0)

            // Phase adjustments from LLM
            val phaseAdj = result.optJSONObject("phase_adjustments")
            if (phaseAdj != null) {
                val newBoosts = phaseBoosts.toMutableMap()
                phaseAdj.keys().forEach { phase ->
                    val delta = phaseAdj.optDouble(phase, 0.0).coerceIn(-10.0, 10.0)
                    newBoosts[phase] = (newBoosts[phase] ?: 0.0) * 0.4 + delta * 0.6
                }
                phaseBoosts = newBoosts
            }

            lastLlmInsight = insight
            lastAnalysis = "🤖 LLM: $summary\n📈 Best pattern: $topPat\n⚠️ Problem: $probPat\n💡 $insight"

            db.recordParamChange("llm_entry_delta", entryThresholdDelta - eDelta,
                entryThresholdDelta, "llm_analysis: $insight", trades.size,
                trades.count{it.isWin == true}.toDouble()/trades.size*100)

            onLog("🧠 LLM insight: $insight")
            onLog("🧠 Entry delta → ${entryThresholdDelta.toInt()}  Exit delta → ${exitThresholdDelta.toInt()}")

        } catch (e: Exception) {
            onLog("🧠 LLM analysis failed: ${e.message?.take(60)} — using statistical only")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // LAYER 3 — Market regime detection
    // ══════════════════════════════════════════════════════════════════

    /**
     * Called every poll cycle with recent market data.
     * Classifies the current market regime and adjusts sizing multipliers.
     */
    fun updateRegime(
        recentWinRate: Double,        // win rate of last 10 trades
        avgVolRatio: Double,          // average vol/liquidity across watchlist
        consecutiveLosses: Int,
        solPriceChange1h: Double,     // SOL price change last hour
    ) {
        val newRegime = when {
            consecutiveLosses >= 3             -> "DANGER"       // circuit breaker territory
            recentWinRate >= 75 && avgVolRatio > 1.5 -> "BULL_HOT"   // everything pumping
            recentWinRate >= 65                -> "BULL"
            recentWinRate <= 35 && avgVolRatio < 0.5 -> "BEAR_COLD"  // nothing working
            recentWinRate <= 45                -> "BEAR"
            avgVolRatio > 2.0                  -> "HIGH_VOL"     // volatile, be selective
            else                               -> "NEUTRAL"
        }

        if (newRegime != currentRegime) {
            onLog("🌡️ Market regime: $currentRegime → $newRegime")
            currentRegime = newRegime
        }

        // Adjust size multipliers based on regime
        regimeBullMult = when (newRegime) {
            "BULL_HOT"  -> 1.30   // press hard when everything's working
            "BULL"      -> 1.15
            "NEUTRAL"   -> 1.00
            "HIGH_VOL"  -> 0.80   // reduce size when unpredictable
            "BEAR"      -> 0.70
            "BEAR_COLD" -> 0.50
            "DANGER"    -> 0.30   // near-minimum — protect capital
            else        -> 1.00
        }
        regimeBearMult = regimeBullMult  // same for now — future: bull/bear separate
    }

    // ── Public interface for strategy ─────────────────────────────────

    /** Entry score boost/penalty for a specific phase, learned from history */
    fun getPhaseBoost(phase: String): Double = phaseBoosts[phase] ?: 0.0

    /** Discovery score boost/penalty for a token source */
    fun getSourceBoost(source: String): Double = sourceBoosts[source] ?: 0.0

    /** Effective entry threshold incorporating all learning */
    fun effectiveEntryThreshold(baseThreshold: Double = 42.0): Double =
        (baseThreshold + entryThresholdDelta).coerceIn(35.0, 68.0)

    /** Effective exit threshold incorporating all learning */
    fun effectiveExitThreshold(baseThreshold: Double = 58.0): Double =
        (baseThreshold + exitThresholdDelta).coerceIn(45.0, 72.0)

    /** Size multiplier for current market regime */
    fun regimeSizeMultiplier(): Double = regimeBullMult
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FDG INTEGRATION - Record blocked trades for learning (without execution)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val blockedTradesByReason = mutableMapOf<String, Int>()
    private val blockedTradesByPhase = mutableMapOf<String, Int>()
    private val blockedTradesBySource = mutableMapOf<String, Int>()
    
    /**
     * Record a trade that was blocked by the Final Decision Gate.
     * This allows learning from blocked opportunities without executing them.
     * 
     * Use cases:
     * - Track which block reasons fire most often (may need threshold adjustment)
     * - Track which phases/sources get blocked (pattern detection)
     * - Inform future threshold learning
     */
    fun recordBlockedTrade(
        mint: String,
        phase: String,
        source: String,
        blockReason: String,
        quality: String,
        confidence: Double,
    ) {
        try {
            // Count blocks by reason
            blockedTradesByReason[blockReason] = (blockedTradesByReason[blockReason] ?: 0) + 1
            
            // Count blocks by phase
            blockedTradesByPhase[phase] = (blockedTradesByPhase[phase] ?: 0) + 1
            
            // Count blocks by source
            blockedTradesBySource[source] = (blockedTradesBySource[source] ?: 0) + 1
            
            // Log for visibility
            val totalBlocks = blockedTradesByReason.values.sum()
            onLog("🚫 FDG Block recorded: $blockReason | phase=$phase | src=$source | " +
                  "total_blocks=$totalBlocks")
            
            // If a hard block fires too often on high-quality setups, it might be too strict
            if (quality in listOf("A+", "A") && confidence >= 60) {
                val reasonCount = blockedTradesByReason[blockReason] ?: 0
                if (reasonCount >= 10 && blockReason.startsWith("HARD_BLOCK_")) {
                    onLog("⚠️ High-quality setup blocked $reasonCount times by $blockReason - " +
                          "consider threshold adjustment")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "recordBlockedTrade error: ${e.message}")
        }
    }
    
    /**
     * Get total trade count for determining bootstrap phase.
     */
    fun getTotalTradeCount(): Int = totalTradesAnalysed
    
    /**
     * Get blocked trade statistics for debugging/UI.
     */
    fun getBlockedTradeStats(): Map<String, Any> = mapOf(
        "byReason" to blockedTradesByReason.toMap(),
        "byPhase" to blockedTradesByPhase.toMap(),
        "bySource" to blockedTradesBySource.toMap(),
        "totalBlocks" to blockedTradesByReason.values.sum(),
    )
    
    /**
     * Real-time learning from a completed trade.
     * Updates internal state immediately for faster adaptation.
     * 
     * ROLLING MEMORY INTEGRATION:
     * This function now records trades to the rolling memory system, which prioritizes
     * recent trades (last 200) over global historical data (up to 2000).
     * 
     * FAST-CHANGING METRICS (prioritize recent memory only):
     * - Momentum strength (tracked via phase performance)
     * - Source quality (tracked weekly)
     * - Market regime
     * - Optimal entry aggressiveness
     * - Launch type profitability
     * 
     * These metrics are calculated primarily from recent memory to adapt quickly
     * to changing market conditions.
     * 
     * Returns true if the token should be blacklisted (2+ losses on same token).
     */
    fun learnFromTrade(
        isWin: Boolean, 
        phase: String, 
        emaFan: String, 
        source: String, 
        pnlPct: Double, 
        mint: String = "",
        // Optional: additional metrics for rolling memory
        rugcheckScore: Int = 50,
        buyPressure: Double = 50.0,
        topHolderPct: Double = 10.0,
        liquidityUsd: Double = 10000.0,
        // LIVE TRADE WEIGHTING: Live trades count more than paper trades
        isLiveTrade: Boolean = false,
    ): Boolean {
        // Live trades have 3x weight - they're validated with real money
        val tradeWeight = if (isLiveTrade) LIVE_TRADE_WEIGHT else 1.0
        
        try {
            // ═══════════════════════════════════════════════════════════════════
            // ROLLING MEMORY: Record trade for adaptive learning
            // This is the NEW primary learning mechanism
            // Live trades recorded multiple times for extra weight
            // ═══════════════════════════════════════════════════════════════════
            val recordCount = if (isLiveTrade) LIVE_TRADE_WEIGHT.toInt() else 1
            repeat(recordCount) {
                recordToMemory(
                    isWin = isWin,
                    pnlPct = pnlPct,
                    phase = phase,
                    emaFan = emaFan,
                    source = source,
                    rugcheckScore = rugcheckScore,
                    buyPressure = buyPressure,
                    topHolderPct = topHolderPct,
                    liquidityUsd = liquidityUsd,
                )
            }
            
            if (isLiveTrade) {
                onLog("🔴 LIVE TRADE learning (${tradeWeight.toInt()}x weight): ${if(isWin) "WIN" else "LOSS"} ${pnlPct.toInt()}%")
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // FAST-CHANGING METRICS: Use RECENT MEMORY primarily
            // These should NOT be dominated by global/historical data
            // ═══════════════════════════════════════════════════════════════════
            
            // Track phase performance in session counters (reset on restart)
            // These give immediate feedback, rolling memory provides context
            val phaseWins = phaseWinCounts.getOrDefault(phase, 0)
            val phaseLosses = phaseLossCounts.getOrDefault(phase, 0)
            if (isWin) {
                phaseWinCounts[phase] = phaseWins + 1
            } else {
                phaseLossCounts[phase] = phaseLosses + 1
            }
            
            // Phase boost: Use RECENT MEMORY win rate (not global)
            // This ensures fast adaptation to current market conditions
            val recentPhaseWinRate = getRecentPhaseWinRate(phase)
            if (recentPhaseWinRate != null) {
                val newBoost = when {
                    recentPhaseWinRate >= 0.70 -> -5.0  // Lower entry bar = more aggressive
                    recentPhaseWinRate >= 0.55 -> 0.0   // Neutral
                    recentPhaseWinRate >= 0.40 -> 5.0   // Raise entry bar = more selective
                    else -> 10.0                         // High bar = very selective
                }
                phaseBoosts = phaseBoosts.toMutableMap().apply { put(phase, newBoost) }
            }
            
            // Track source performance
            val sourceWins = sourceWinCounts.getOrDefault(source, 0)
            val sourceLosses = sourceLossCounts.getOrDefault(source, 0)
            if (isWin) {
                sourceWinCounts[source] = sourceWins + 1
            } else {
                sourceLossCounts[source] = sourceLosses + 1
            }
            
            // Source boost: Use RECENT MEMORY win rate
            // Source quality changes quickly week-to-week
            val recentSourceWinRate = getRecentSourceWinRate(source)
            if (recentSourceWinRate != null) {
                val newSourceBoost = when {
                    recentSourceWinRate >= 0.70 -> 10.0   // Good source = boost score
                    recentSourceWinRate >= 0.50 -> 0.0    // Neutral
                    else -> -10.0                          // Bad source = penalize
                }
                sourceBoosts = sourceBoosts.toMutableMap().apply { put(source, newSourceBoost) }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // ENTRY THRESHOLD: Blend recent + global (30/70 recent bias)
            // This is a balance between quick adaptation and stable baseline
            // ═══════════════════════════════════════════════════════════════════
            val blendedWinRate = memoryStats.blendedWinRate
            if (memoryStats.recentTradeCount >= 10) {
                // Use blended rate but weight recent changes more heavily
                val recentRecentWinRate = memoryStats.recentWinRate
                entryThresholdDelta = when {
                    recentRecentWinRate >= 0.75 -> -4.0   // Hot streak - be aggressive
                    recentRecentWinRate >= 0.65 -> -2.0
                    recentRecentWinRate >= 0.55 -> 0.0
                    recentRecentWinRate >= 0.45 -> 3.0
                    recentRecentWinRate >= 0.35 -> 6.0
                    else -> 10.0                           // Cold streak - be selective
                }.coerceIn(-12.0, 15.0)
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // PATTERN SUPPRESSION: Track EMA fan performance
            // ═══════════════════════════════════════════════════════════════════
            val key = "${phase}+${emaFan}"
            if (!isWin) {
                val currentLosses = patternLossCounts.getOrDefault(key, 0) + 1
                patternLossCounts[key] = currentLosses
                
                // Auto-suppress patterns with 3+ consecutive losses
                if (currentLosses >= 3) {
                    onLog("🧠 Pattern '$key' suppressed after $currentLosses losses")
                }
                
                // Track losses per token for auto-blacklisting
                if (mint.isNotBlank()) {
                    val tokenLosses = tokenLossCounts.getOrDefault(mint, 0) + 1
                    tokenLossCounts[mint] = tokenLosses
                    
                    if (tokenLosses >= TOKEN_LOSS_BLACKLIST_THRESHOLD) {
                        onLog("🚫 Token ${mint.take(8)}... has $tokenLosses losses — BLACKLISTING")
                        return true  // Signal to caller that token should be blacklisted
                    }
                }
            } else {
                // Win resets the loss count for this pattern
                patternLossCounts[key] = 0
                // Win on a token also resets its loss count — second chance
                if (mint.isNotBlank()) {
                    tokenLossCounts[mint] = 0
                }
            }
            
            totalTradesLearned++
            
            // Log with memory context
            val memSize = recentMemory.size
            val globalSize = globalMemory.size
            onLog("🧠 Learned: ${if(isWin) "WIN" else "LOSS"} ${pnlPct.toInt()}% | $phase | $source | mem[$memSize/$globalSize]")
            
            // Save memory periodically (every 5 trades)
            if (totalTradesLearned % 5 == 0) {
                saveMemoryToPrefs()
            }
            
            return false  // No blacklisting needed
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "learnFromTrade error: ${e.message}")
            return false
        }
    }
    
    /**
     * Get win rate for a phase from RECENT MEMORY only.
     * This ensures fast-changing metrics adapt quickly.
     * Returns null if not enough data (< 3 trades).
     */
    private fun getRecentPhaseWinRate(phase: String): Double? {
        val recentTrades = synchronized(recentMemory) { recentMemory.filter { it.phase == phase } }
        if (recentTrades.size < 3) return null
        val wins = recentTrades.count { it.isWin == true }
        return wins.toDouble() / recentTrades.size
    }
    
    /**
     * Get win rate for a source from RECENT MEMORY only.
     * Source quality changes weekly, so we only look at recent trades.
     * Returns null if not enough data (< 3 trades).
     */
    private fun getRecentSourceWinRate(source: String): Double? {
        val recentTrades = synchronized(recentMemory) { recentMemory.filter { it.source == source } }
        if (recentTrades.size < 3) return null
        val wins = recentTrades.count { it.isWin == true }
        return wins.toDouble() / recentTrades.size
    }
    
    /**
     * Get blended win rate for entry aggressiveness decisions.
     * Uses 70% recent / 30% global weighting.
     */
    fun getBlendedWinRate(): Double = memoryStats.blendedWinRate
    
    /**
     * Get recent win rate as percentage (0-100).
     * Used by FDG for auto-adjusting thresholds.
     */
    fun getRecentWinRate(): Double {
        val trades = synchronized(recentMemory) { recentMemory.toList() }
        if (trades.isEmpty()) return 50.0
        val wins = trades.count { it.pnl > 0 }
        return (wins.toDouble() / trades.size) * 100.0
    }
    
    /**
     * Get trade count for FDG learning phase calculation.
     */
    fun getTradeCount(): Int = synchronized(recentMemory) { recentMemory.size }
    
    /**
     * Check if current market regime (from recent trades) is favorable.
     * Returns true if recent win rate >= 50%.
     */
    fun isRecentRegimeFavorable(): Boolean = memoryStats.recentWinRate >= 0.50
    
    // Real-time tracking maps
    private val phaseWinCounts = mutableMapOf<String, Int>()
    private val phaseLossCounts = mutableMapOf<String, Int>()
    private val sourceWinCounts = mutableMapOf<String, Int>()
    private val sourceLossCounts = mutableMapOf<String, Int>()
    private val patternLossCounts = mutableMapOf<String, Int>()
    private var totalTradesLearned = 0
    
    // Track losses per token mint for auto-blacklisting
    // Key = mint address, Value = number of losing trades on this token
    private val tokenLossCounts = mutableMapOf<String, Int>()
    private val TOKEN_LOSS_BLACKLIST_THRESHOLD = 2  // Blacklist after 2 losses on same token
    
    /**
     * Get a risk-adjusted size multiplier for a specific trade context.
     * Takes into account:
     * - Suppressed patterns (reduce size for known bad patterns)
     * - Source performance (reduce size for poor-performing sources)
     * - Phase win rate (reduce size in losing phases)
     * - Overall learned caution level
     * Returns a multiplier between 0.3 and 1.5
     */
    fun getRiskAdjustedSizeMultiplier(phase: String, emaFan: String, source: String): Double {
        var mult = regimeBullMult
        
        // Reduce size for suppressed patterns (risk reduction)
        val suppressionPenalty = getSuppressionPenalty(phase, emaFan, source)
        when {
            suppressionPenalty >= 70 -> mult *= 0.3  // Near-blocked pattern = minimal size
            suppressionPenalty >= 45 -> mult *= 0.5  // Confirmed bad = half size
            suppressionPenalty >= 20 -> mult *= 0.7  // Monitoring = reduced size
        }
        
        // Adjust for phase performance
        val phaseBoost = phaseBoosts[phase] ?: 0.0
        when {
            phaseBoost <= -5.0 -> mult *= 1.2  // Winning phase = bigger size
            phaseBoost >= 8.0 -> mult *= 0.6   // Losing phase = smaller size
        }
        
        // Adjust for source performance
        val sourceBoost = sourceBoosts[source] ?: 0.0
        when {
            sourceBoost >= 10.0 -> mult *= 1.15  // Good source = slightly bigger
            sourceBoost <= -10.0 -> mult *= 0.7   // Bad source = smaller
        }
        
        // Apply learned caution (entry threshold delta)
        // High positive delta = brain is cautious = smaller sizes
        // Negative delta = brain is confident = normal sizes
        if (entryThresholdDelta > 5) mult *= 0.85
        if (entryThresholdDelta > 10) mult *= 0.7
        
        return mult.coerceIn(0.3, 1.5)
    }
    
    /**
     * Should we skip this trade entirely based on learned risk?
     * Returns true if the combination of factors is too risky.
     */
    fun shouldSkipTrade(phase: String, emaFan: String, source: String, entryScore: Double): Boolean {
        // Hard suppression = skip (but only if score is very low)
        if (isHardSuppressed(phase, emaFan) && entryScore < 30) return true
        
        // Very cautious brain + very low score = skip
        if (entryThresholdDelta > 20 && entryScore < 25) return true  // loosened further
        
        // Multiple risk factors = skip (only if ALL 4 factors present AND very low score)
        val suppressionPenalty = getSuppressionPenalty(phase, emaFan, source)
        val phaseBoost = phaseBoosts[phase] ?: 0.0
        val sourceBoost = sourceBoosts[source] ?: 0.0
        
        val riskFactors = listOf(
            suppressionPenalty > 50,  // raised threshold
            phaseBoost >= 15,         // raised threshold
            sourceBoost <= -20,       // raised threshold
            currentRegime == "DANGER",  // only block on DANGER, not BEAR
        ).count { it }
        
        // ALL 4 risk factors = too risky (very rare)
        if (riskFactors == 4 && entryScore < 40) return true
        
        return false
    }

    /** Summary for UI display */
    fun getStatusSummary(): String = buildString {
        appendLine("🧠 BotBrain — $totalTradesAnalysed trades analysed")
        appendLine("Regime: $currentRegime  SizeMult: ${(regimeBullMult*100).toInt()}%")
        appendLine("Entry Δ: ${entryThresholdDelta.toInt()}  Exit Δ: ${exitThresholdDelta.toInt()}")
        appendLine("Bad patterns: $totalSuppressedPatterns suppressed")
        appendLine("📊 Thresholds: rugcheck≤$learnedRugcheckThreshold buy%≥${learnedMinBuyPressure.toInt()} topHolder≤${learnedMaxTopHolder.toInt()}%")
        if (lastLlmInsight.isNotBlank()) appendLine("💡 $lastLlmInsight")
        appendLine(lastAnalysis)
    }

    /** Full bad behaviour report for UI (delegated to detail method) */
    fun getFullBadBehaviourReport(): String = getBadBehaviourReport()
    
    // ══════════════════════════════════════════════════════════════════
    // ADAPTIVE THRESHOLD LEARNING
    // Learn optimal hard block thresholds from actual trade outcomes
    // ══════════════════════════════════════════════════════════════════
    
    /**
     * Record a trade outcome to learn optimal thresholds.
     * Called after every trade closes.
     */
    fun learnThreshold(
        isWin: Boolean,
        rugcheckScore: Int,
        buyPressure: Double,
        topHolderPct: Double,
        liquidityUsd: Double,
        pnlPct: Double,
    ) {
        // Bucket the values for statistical analysis
        val rugBucket = (rugcheckScore / 10) * 10  // 0, 10, 20, 30...
        val pressBucket = (buyPressure / 10).toInt() * 10  // 0, 10, 20, 30...
        val holderBucket = (topHolderPct / 10).toInt() * 10  // 0, 10, 20...
        
        if (isWin) {
            rugcheckWins[rugBucket] = (rugcheckWins[rugBucket] ?: 0) + 1
            pressureWins[pressBucket] = (pressureWins[pressBucket] ?: 0) + 1
            topHolderWins[holderBucket] = (topHolderWins[holderBucket] ?: 0) + 1
        } else {
            rugcheckLosses[rugBucket] = (rugcheckLosses[rugBucket] ?: 0) + 1
            pressureLosses[pressBucket] = (pressureLosses[pressBucket] ?: 0) + 1
            topHolderLosses[holderBucket] = (topHolderLosses[holderBucket] ?: 0) + 1
        }
        
        // Recalculate thresholds periodically (every 10 trades)
        val totalTrades = rugcheckWins.values.sum() + rugcheckLosses.values.sum()
        if (totalTrades >= 10 && totalTrades % 10 == 0) {
            recalculateThresholds()
        }
    }
    
    /**
     * Recalculate optimal thresholds based on win/loss ratios at each bucket.
     * The goal is to find the threshold where losses significantly outweigh wins.
     */
    private fun recalculateThresholds() {
        // RUGCHECK: Find the score below which losses dominate
        var newRugThreshold = 15  // Default
        for (bucket in listOf(0, 10, 20, 30)) {
            val wins = rugcheckWins[bucket] ?: 0
            val losses = rugcheckLosses[bucket] ?: 0
            val total = wins + losses
            if (total >= 3) {
                val lossRate = losses.toDouble() / total
                // If loss rate > 70% at this bucket, set threshold above it
                if (lossRate > 0.70) {
                    newRugThreshold = bucket + 10
                }
            }
        }
        // Don't go too extreme
        learnedRugcheckThreshold = newRugThreshold.coerceIn(5, 40)
        
        // BUY PRESSURE: Find the pressure below which losses dominate
        var newPressThreshold = 15.0
        for (bucket in listOf(0, 10, 20, 30)) {
            val wins = pressureWins[bucket] ?: 0
            val losses = pressureLosses[bucket] ?: 0
            val total = wins + losses
            if (total >= 3) {
                val lossRate = losses.toDouble() / total
                if (lossRate > 0.70) {
                    newPressThreshold = (bucket + 10).toDouble()
                }
            }
        }
        learnedMinBuyPressure = newPressThreshold.coerceIn(10.0, 35.0)
        
        // TOP HOLDER: Find the holder% above which losses dominate
        var newHolderThreshold = 70.0
        for (bucket in listOf(40, 50, 60, 70, 80)) {
            val wins = topHolderWins[bucket] ?: 0
            val losses = topHolderLosses[bucket] ?: 0
            val total = wins + losses
            if (total >= 3) {
                val lossRate = losses.toDouble() / total
                // If loss rate > 60% at this bucket, set threshold to this level
                if (lossRate > 0.60) {
                    newHolderThreshold = bucket.toDouble()
                    break  // Use first bucket that's bad
                }
            }
        }
        learnedMaxTopHolder = newHolderThreshold.coerceIn(40.0, 80.0)
        
        ErrorLogger.info("BotBrain", "📊 THRESHOLDS UPDATED: " +
            "rugcheck≤$learnedRugcheckThreshold buy%≥${learnedMinBuyPressure.toInt()} topHolder≤${learnedMaxTopHolder.toInt()}%")
        
        // Save to prefs for persistence
        saveThresholdsToPrefs()
    }
    
    private fun saveThresholdsToPrefs() {
        try {
            val prefs = ctx.getSharedPreferences("bot_brain_thresholds", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("rugcheck_threshold", learnedRugcheckThreshold)
                .putFloat("min_buy_pressure", learnedMinBuyPressure.toFloat())
                .putFloat("max_top_holder", learnedMaxTopHolder.toFloat())
                .putFloat("min_liquidity", learnedMinLiquidity.toFloat())
                .apply()
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "Failed to save thresholds: ${e.message}")
        }
    }
    
    private fun loadThresholdsFromPrefs() {
        try {
            val prefs = ctx.getSharedPreferences("bot_brain_thresholds", Context.MODE_PRIVATE)
            learnedRugcheckThreshold = prefs.getInt("rugcheck_threshold", 15)
            learnedMinBuyPressure = prefs.getFloat("min_buy_pressure", 15f).toDouble()
            learnedMaxTopHolder = prefs.getFloat("max_top_holder", 70f).toDouble()
            learnedMinLiquidity = prefs.getFloat("min_liquidity", 100f).toDouble()
            
            ErrorLogger.info("BotBrain", "📊 THRESHOLDS LOADED: " +
                "rugcheck≤$learnedRugcheckThreshold buy%≥${learnedMinBuyPressure.toInt()} topHolder≤${learnedMaxTopHolder.toInt()}%")
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "Failed to load thresholds: ${e.message}")
        }
    }
    
    /**
     * Get the current learned thresholds for use in hard blocks.
     * Returns a data class with all threshold values.
     */
    fun getLearnedThresholds() = LearnedThresholds(
        rugcheckMin = learnedRugcheckThreshold,
        buyPressureMin = learnedMinBuyPressure,
        topHolderMax = learnedMaxTopHolder,
        liquidityMin = learnedMinLiquidity,
    )
    
    data class LearnedThresholds(
        val rugcheckMin: Int,
        val buyPressureMin: Double,
        val topHolderMax: Double,
        val liquidityMin: Double,
    )
    
    // ══════════════════════════════════════════════════════════════════
    // ROLLING MEMORY FUNCTIONS
    // ══════════════════════════════════════════════════════════════════
    
    /**
     * Record a trade to both recent and global memory.
     * Recent memory maintains last N trades with high weight.
     * Global memory stores long-term patterns with decay.
     */
    fun recordToMemory(
        isWin: Boolean,
        pnlPct: Double,
        phase: String,
        emaFan: String,
        source: String,
        rugcheckScore: Int,
        buyPressure: Double,
        topHolderPct: Double,
        liquidityUsd: Double,
    ) {
        val trade = MemoryTrade(
            timestamp = System.currentTimeMillis(),
            isWin = isWin,
            pnlPct = pnlPct,
            phase = phase,
            emaFan = emaFan,
            source = source,
            rugcheckScore = rugcheckScore,
            buyPressure = buyPressure,
            topHolderPct = topHolderPct,
            liquidityUsd = liquidityUsd,
        )
        
        // Add to recent memory (FIFO, keeps last N)
        synchronized(recentMemory) {
            recentMemory.addLast(trade)
            while (recentMemory.size > RECENT_MEMORY_SIZE) {
                // Move oldest to global memory before removing
                recentMemory.removeFirst()?.let { old ->
                    synchronized(globalMemory) {
                        globalMemory.addLast(old)
                        while (globalMemory.size > GLOBAL_MEMORY_SIZE) {
                            globalMemory.removeFirst()
                        }
                    }
                }
            }
        }
        
        // Recompute stats periodically (every 10 trades)
        if ((recentMemory.size + globalMemory.size) % 10 == 0) {
            recomputeMemoryStats()
        }
        
        ErrorLogger.debug("BotBrain", "📝 Memory: recent=${recentMemory.size} global=${globalMemory.size}")
    }
    
    /**
     * Calculate weight for a trade based on age.
     * Newer trades have weight closer to 1.0, older trades decay toward 0.
     */
    private fun calculateDecayWeight(trade: MemoryTrade, referenceTime: Long): Double {
        val ageMs = referenceTime - trade.timestamp
        val ageHours = ageMs / 3_600_000.0
        // Exponential decay: weight = 0.5^(age/halfLife)
        // Half-life of ~24 hours means a trade from yesterday has 50% weight
        val halfLifeHours = 24.0
        return Math.pow(0.5, ageHours / halfLifeHours).coerceIn(0.1, 1.0)
    }
    
    /**
     * Recompute aggregated stats from recent and global memory.
     * Uses weighted averages with decay for older trades.
     */
    private fun recomputeMemoryStats() {
        val now = System.currentTimeMillis()
        
        // Recent memory stats (high weight, no decay)
        val recentTrades = synchronized(recentMemory) { recentMemory.toList() }
        val recentWins = recentTrades.count { it.isWin == true }
        val recentWinRate = if (recentTrades.isNotEmpty()) 
            recentWins.toDouble() / recentTrades.size else 0.0
        val recentAvgPnl = if (recentTrades.isNotEmpty())
            recentTrades.map { it.pnlPct }.average() else 0.0
        
        // Global memory stats (with decay weighting)
        val globalTrades = synchronized(globalMemory) { globalMemory.toList() }
        var globalWeightedWins = 0.0
        var globalTotalWeight = 0.0
        var globalWeightedPnl = 0.0
        
        for (trade in globalTrades) {
            val weight = calculateDecayWeight(trade, now)
            globalTotalWeight += weight
            if (trade.isWin == true) globalWeightedWins += weight
            globalWeightedPnl += trade.pnlPct * weight
        }
        
        val globalWinRate = if (globalTotalWeight > 0)
            globalWeightedWins / globalTotalWeight else 0.0
        val globalAvgPnl = if (globalTotalWeight > 0)
            globalWeightedPnl / globalTotalWeight else 0.0
        
        // Blended win rate: 70% recent, 30% global
        val blendedWinRate = (RECENT_WEIGHT * recentWinRate) + (GLOBAL_WEIGHT * globalWinRate)
        
        // Find top performing phases (from recent memory for adaptiveness)
        val phaseStats = recentTrades.groupBy { it.phase }
            .mapValues { (_, trades) -> 
                val wins = trades.count { it.isWin == true }
                if (trades.size >= 3) wins.toDouble() / trades.size else 0.0
            }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
        
        // Find top performing sources
        val sourceStats = recentTrades.groupBy { it.source }
            .mapValues { (_, trades) ->
                val wins = trades.count { it.isWin == true }
                if (trades.size >= 3) wins.toDouble() / trades.size else 0.0
            }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
        
        // Find danger zones (patterns with >70% loss rate in recent memory)
        val dangerZones = mutableListOf<String>()
        
        // Check phases with high loss rate
        for ((phase, trades) in recentTrades.groupBy { it.phase }) {
            if (trades.size >= 5) {
                val lossRate = trades.count { it.isWin == false }.toDouble() / trades.size
                if (lossRate > 0.70) {
                    dangerZones.add("phase:$phase (${(lossRate*100).toInt()}% loss)")
                }
            }
        }
        
        // Check sources with high loss rate
        for ((source, trades) in recentTrades.groupBy { it.source }) {
            if (trades.size >= 5) {
                val lossRate = trades.count { it.isWin == false }.toDouble() / trades.size
                if (lossRate > 0.70) {
                    dangerZones.add("source:$source (${(lossRate*100).toInt()}% loss)")
                }
            }
        }
        
        memoryStats = MemoryStats(
            recentWinRate = recentWinRate,
            globalWinRate = globalWinRate,
            blendedWinRate = blendedWinRate,
            recentAvgPnl = recentAvgPnl,
            globalAvgPnl = globalAvgPnl,
            recentTradeCount = recentTrades.size,
            globalTradeCount = globalTrades.size,
            topPhases = phaseStats,
            topSources = sourceStats,
            dangerZones = dangerZones,
        )
        
        ErrorLogger.info("BotBrain", "📊 MEMORY STATS: " +
            "recent=${recentTrades.size} (${(recentWinRate*100).toInt()}% win) | " +
            "global=${globalTrades.size} (${(globalWinRate*100).toInt()}% win) | " +
            "blended=${(blendedWinRate*100).toInt()}% | " +
            "dangers=${dangerZones.size}")
        
        // Update phase/source boosts based on memory
        updateBoostsFromMemory()
    }
    
    /**
     * Update phase and source boosts based on memory stats.
     * High-performing patterns get positive boosts, low-performing get negative.
     */
    private fun updateBoostsFromMemory() {
        // Phase boosts: +10 for >60% win rate, -10 for <40% win rate
        val newPhaseBoosts = mutableMapOf<String, Double>()
        for ((phase, winRate) in memoryStats.topPhases) {
            val boost = when {
                winRate >= 0.60 -> 10.0
                winRate >= 0.50 -> 5.0
                winRate < 0.30 -> -15.0
                winRate < 0.40 -> -10.0
                else -> 0.0
            }
            if (boost != 0.0) newPhaseBoosts[phase] = boost
        }
        phaseBoosts = newPhaseBoosts
        
        // Source boosts: similar logic
        val newSourceBoosts = mutableMapOf<String, Double>()
        for ((source, winRate) in memoryStats.topSources) {
            val boost = when {
                winRate >= 0.60 -> 10.0
                winRate >= 0.50 -> 5.0
                winRate < 0.30 -> -15.0
                winRate < 0.40 -> -10.0
                else -> 0.0
            }
            if (boost != 0.0) newSourceBoosts[source] = boost
        }
        sourceBoosts = newSourceBoosts
        
        if (newPhaseBoosts.isNotEmpty() || newSourceBoosts.isNotEmpty()) {
            ErrorLogger.info("BotBrain", "🎯 BOOSTS UPDATED: " +
                "phases=$newPhaseBoosts | sources=$newSourceBoosts")
        }
    }
    
    /**
     * Check if a pattern is in a danger zone based on recent memory.
     */
    fun isInDangerZone(phase: String, source: String): Boolean {
        return memoryStats.dangerZones.any { 
            it.contains("phase:$phase") || it.contains("source:$source")
        }
    }
    
    /**
     * Get memory summary for UI display.
     */
    fun getMemorySummary(): String = buildString {
        appendLine("📊 Rolling Memory:")
        appendLine("  Recent: ${memoryStats.recentTradeCount} trades (${(memoryStats.recentWinRate*100).toInt()}% win, avg ${memoryStats.recentAvgPnl.toInt()}%)")
        appendLine("  Global: ${memoryStats.globalTradeCount} trades (${(memoryStats.globalWinRate*100).toInt()}% win)")
        appendLine("  Blended: ${(memoryStats.blendedWinRate*100).toInt()}% win rate")
        if (memoryStats.dangerZones.isNotEmpty()) {
            appendLine("  ⚠️ Danger zones: ${memoryStats.dangerZones.joinToString(", ")}")
        }
        if (memoryStats.topPhases.isNotEmpty()) {
            val top = memoryStats.topPhases.firstOrNull()
            if (top != null) appendLine("  🏆 Best phase: ${top.first} (${(top.second*100).toInt()}%)")
        }
    }
    
    /**
     * Save memory to persistent storage.
     */
    fun saveMemoryToPrefs() {
        try {
            val prefs = ctx.getSharedPreferences("bot_brain_memory", Context.MODE_PRIVATE)
            val recentJson = synchronized(recentMemory) {
                org.json.JSONArray().apply {
                    recentMemory.forEach { trade ->
                        put(org.json.JSONObject().apply {
                            put("ts", trade.timestamp)
                            put("win", trade.isWin)
                            put("pnl", trade.pnlPct)
                            put("phase", trade.phase)
                            put("ema", trade.emaFan)
                            put("src", trade.source)
                            put("rug", trade.rugcheckScore)
                            put("press", trade.buyPressure)
                            put("holder", trade.topHolderPct)
                            put("liq", trade.liquidityUsd)
                        })
                    }
                }.toString()
            }
            prefs.edit().putString("recent_memory", recentJson).apply()
            ErrorLogger.info("BotBrain", "💾 Memory saved: ${recentMemory.size} recent trades")
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "Failed to save memory: ${e.message}")
        }
    }
    
    /**
     * Load memory from persistent storage.
     */
    fun loadMemoryFromPrefs() {
        try {
            val prefs = ctx.getSharedPreferences("bot_brain_memory", Context.MODE_PRIVATE)
            val recentJson = prefs.getString("recent_memory", null) ?: return
            
            val jsonArray = org.json.JSONArray(recentJson)
            synchronized(recentMemory) {
                recentMemory.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    recentMemory.addLast(MemoryTrade(
                        timestamp = obj.getLong("ts"),
                        isWin = obj.getBoolean("win"),
                        pnlPct = obj.getDouble("pnl"),
                        phase = obj.optString("phase", ""),
                        emaFan = obj.optString("ema", ""),
                        source = obj.optString("src", ""),
                        rugcheckScore = obj.optInt("rug", 50),
                        buyPressure = obj.optDouble("press", 50.0),
                        topHolderPct = obj.optDouble("holder", 0.0),
                        liquidityUsd = obj.optDouble("liq", 0.0),
                    ))
                }
            }
            
            // Recompute stats after loading
            recomputeMemoryStats()
            
            ErrorLogger.info("BotBrain", "📂 Memory loaded: ${recentMemory.size} recent trades")
        } catch (e: Exception) {
            ErrorLogger.error("BotBrain", "Failed to load memory: ${e.message}")
        }
    }
}
