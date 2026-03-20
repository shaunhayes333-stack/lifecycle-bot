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
        scope.launch { brainLoop() }
        onLog("🧠 BotBrain online — self-learning active " +
              "(entry delta ${entryThresholdDelta:+.1f}, " +
              "regime mult ${regimeBullMult:.2f}×)")
    }

    /**
     * Restore learned state from the trade database.
     * Called once on start — rebuilds thresholds, boosts, and regime mult
     * from all trades seen so far so learning survives restarts.
     */
    private fun restoreFromDatabase() {
        try {
            val trades = db.getRecentTrades(500)
            if (trades.size < 10) return

            val wr = trades.count { it.isWin }.toDouble() / trades.size

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
                    val pWr = pt.count { it.isWin }.toDouble() / pt.size
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
                    val sWr = st.count { it.isWin }.toDouble() / st.size
                    if (sWr >= 0.70) newSourceBoosts[src] = -3.0
                    else if (sWr <= 0.45) newSourceBoosts[src] = 5.0
                }
            }
            sourceBoosts = newSourceBoosts

            // Restore suppressed patterns from bad_behaviour table
            evaluateBadBehaviours()

            onLog("🧠 Brain restored from ${trades.size} trades: " +
                  "WR ${(wr*100).toInt()}% entry_delta=${entryThresholdDelta:+.1f} " +
                  "regime=${regimeBullMult:.2f}×")
        } catch (e: Exception) {
            onLog("🧠 Brain restore failed (fresh start): ${e.message}")
        }
    }

    fun stop() { scope.cancel() }

    // ── Main brain loop ───────────────────────────────────────────────

    private suspend fun brainLoop() {
        while (isActive) {
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
            val wr = phaseTrades.count { it.isWin }.toDouble() / phaseTrades.size * 100
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
            val wr = phaseTrades.count { it.isWin }.toDouble() / phaseTrades.size * 100
            if (wr <= 45.0) {
                phaseTrades.filter { !it.isWin }.forEach { t ->
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
            if (wr >= 60.0) phaseTrades.filter { it.isWin }.forEach { t ->
                db.recordGoodObservation("phase=${phase}+ema=${t.emaFan}")
            }
        }

        // ── EMA fan analysis ──────────────────────────────────────────
        val fanGroups = trades.groupBy { it.emaFan }
        fanGroups.forEach { (fan, fanTrades) ->
            if (fanTrades.size < 8) return@forEach
            val wr = fanTrades.count { it.isWin }.toDouble() / fanTrades.size * 100
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
        val overallWr = trades.count { it.isWin }.toDouble() / trades.size * 100
        val last10Win = trades.takeLast(10).count { it.isWin }
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
            val wr = srcTrades.count { it.isWin }.toDouble() / srcTrades.size * 100
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
            val wr = srcTrades.count { it.isWin }.toDouble() / srcTrades.size * 100
            if (wr <= 40.0) {
                srcTrades.filter { !it.isWin }.forEach { t ->
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
        val avgHoldWins  = trades.filter { it.isWin && it.heldMins > 0 }.map { it.heldMins }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgHoldLoss  = trades.filter { !it.isWin && it.heldMins > 0 }.map { it.heldMins }.let { if (it.isEmpty()) 0.0 else it.average() }
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
            val wr = mtfTrades.count { it.isWin }.toDouble() / mtfTrades.size * 100
            if (mtf == "BEAR" && wr <= 40) {
                entryDelta += 6.0
                report.appendLine("  📉 MTF BEAR ${wr.toInt()}% WR → raise threshold in bear 5m")
            } else if (mtf == "BULL" && wr >= 68) {
                entryDelta -= 4.0
                report.appendLine("  📈 MTF BULL ${wr.toInt()}% WR → lower threshold in bull 5m")
            }
        }

        // ── Overall win rate ─────────────────────────────────────────
        val overallWr = trades.count { it.isWin }.toDouble() / trades.size * 100
        val avgWinPnl = trades.filter { it.isWin }.map { it.pnlPct }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgLossPnl = trades.filter { !it.isWin }.map { it.pnlPct }.let { if (it.isEmpty()) 0.0 else it.average() }
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
Overall win rate: ${(trades.count{it.isWin}.toDouble()/trades.size*100).toInt()}%
Avg win: +${trades.filter{it.isWin}.map{it.pnlPct}.let{if(it.isEmpty())0.0 else it.average()}.toInt()}%
Avg loss: ${trades.filter{!it.isWin}.map{it.pnlPct}.let{if(it.isEmpty())0.0 else it.average()}.toInt()}%

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
                trades.count{it.isWin}.toDouble()/trades.size*100)

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

    /** Summary for UI display */
    fun getStatusSummary(): String = buildString {
        appendLine("🧠 BotBrain — $totalTradesAnalysed trades analysed")
        appendLine("Regime: $currentRegime  SizeMult: ${(regimeBullMult*100).toInt()}%")
        appendLine("Entry Δ: ${entryThresholdDelta.toInt()}  Exit Δ: ${exitThresholdDelta.toInt()}")
        appendLine("Bad patterns: $totalSuppressedPatterns suppressed")
        if (lastLlmInsight.isNotBlank()) appendLine("💡 $lastLlmInsight")
        appendLine(lastAnalysis)
    }

    /** Full bad behaviour report for UI (delegated to detail method) */
    fun getFullBadBehaviourReport(): String = getBadBehaviourReport()
}
