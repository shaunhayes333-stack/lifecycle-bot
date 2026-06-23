package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.execution.ForensicReportExporter
import com.lifecyclebot.engine.execution.PositionWalletReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.3700 — ReportingHub.
 *
 * Single reporting coordinator for heavy operator reports. This intentionally
 * excludes raw Trade Journal row export, but includes compact journal metrics. Existing collectors remain
 * the data sources; this object owns orchestration, background execution,
 * caching, and UI-safe callbacks so four screens do not each spin their own
 * reporting workload against the bot loop.
 */
object ReportingHub {
    enum class Kind { UNIFIED_HEALTH, PIPELINE_HEALTH, ERROR_LOG, FORENSIC_SUMMARY }

    data class TextReport(
        val kind: Kind,
        val title: String,
        val generatedAtMs: Long,
        val text: String,
    )

    private data class CacheEntry(val atMs: Long, val report: TextReport)

    private const val CACHE_TTL_MS = 2_500L
    private const val STALE_CACHE_TTL_MS = 120_000L
    private const val REPORT_BUILD_TIMEOUT_MS = 6_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildMutex = Mutex()
    private val textCache = ConcurrentHashMap<Kind, CacheEntry>()

    fun buildTextAsync(
        kind: Kind,
        forceFresh: Boolean = false,
        callback: (report: TextReport?, error: Throwable?) -> Unit,
    ) {
        scope.launch {
            val result = try {
                buildText(kind, forceFresh) to null
            } catch (t: Throwable) {
                null to t
            }
            withContext(Dispatchers.Main) { callback(result.first, result.second) }
        }
    }

    suspend fun buildText(kind: Kind, forceFresh: Boolean = false): TextReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceFresh) {
            textCache[kind]?.let { cached ->
                if (now - cached.atMs <= CACHE_TTL_MS) return@withContext cached.report
            }
        }
        buildMutex.withLock {
            val now2 = System.currentTimeMillis()
            if (!forceFresh) {
                textCache[kind]?.let { cached ->
                    if (now2 - cached.atMs <= CACHE_TTL_MS) return@withLock cached.report
                }
            }
            val text = try {
                withTimeout(REPORT_BUILD_TIMEOUT_MS) { buildTextSync(kind) }
            } catch (_: TimeoutCancellationException) {
                textCache[kind]?.takeIf { now2 - it.atMs <= STALE_CACHE_TTL_MS }?.report?.text
                    ?: buildEmergencyText(kind, "timeout>${REPORT_BUILD_TIMEOUT_MS}ms")
            } catch (t: Throwable) {
                textCache[kind]?.takeIf { now2 - it.atMs <= STALE_CACHE_TTL_MS }?.report?.text
                    ?: buildEmergencyText(kind, "error=${t.javaClass.simpleName}:${t.message?.take(80)}")
            }
            val report = TextReport(kind, titleFor(kind), now2, text)
            textCache[kind] = CacheEntry(now2, report)
            report
        }
    }

    fun exportForensicFileAsync(
        context: Context,
        callback: (file: File?, error: Throwable?) -> Unit,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val result = try {
                exportForensicFile(appContext) to null
            } catch (t: Throwable) {
                null to t
            }
            withContext(Dispatchers.Main) { callback(result.first, result.second) }
        }
    }

    suspend fun exportForensicFile(context: Context): File = withContext(Dispatchers.IO) {
        buildMutex.withLock {
            try { PositionWalletReconciler.forceTick() } catch (_: Throwable) {}
            ForensicReportExporter.dumpToFile(context.applicationContext)
        }
    }

    fun invalidate() {
        textCache.clear()
    }

    private fun titleFor(kind: Kind): String = when (kind) {
        Kind.UNIFIED_HEALTH -> "AATE Unified Operational Report"
        Kind.PIPELINE_HEALTH -> "AATE Pipeline Health"
        Kind.ERROR_LOG -> "AATE Error Logs"
        Kind.FORENSIC_SUMMARY -> "AATE Forensic Summary"
    }

    private fun buildTextSync(kind: Kind): String = when (kind) {
        Kind.PIPELINE_HEALTH -> safe("pipeline") { PipelineHealthCollector.dumpText() }
        Kind.ERROR_LOG -> safe("error_log") { ErrorLogger.exportToText() }
        Kind.FORENSIC_SUMMARY -> buildForensicSummary()
        Kind.UNIFIED_HEALTH -> buildUnifiedHealth()
    }


    private fun buildEmergencyText(kind: Kind, reason: String): String = buildString(4096) {
        appendHeader("AATE REPORT DEGRADED")
        appendLine("Generated: ${stamp()}")
        appendLine("Kind: $kind")
        appendLine("Reason: $reason")
        appendLine("This is a bounded fallback so the UI returns instead of freezing while full report generation is overloaded.")
        appendLine()
        val snap = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val rt = try { BotRuntimeController.snapshot() } catch (_: Throwable) { null }
        val journal = try { TradeHistoryStore.getStatsCached() } catch (_: Throwable) { null }
        val avgCycle = if (snap != null && snap.cycleCount > 0L) snap.totalCycleMs / snap.cycleCount else -1L
        fun phase(name: String): Long = snap?.phaseCounts?.get(name) ?: 0L
        val loop = phase("BOT_LOOP_TICK")
        val intake = phase("INTAKE")
        val lane = phase("LANE_EVAL")
        val fdg = phase("FDG")
        val exec = phase("EXEC")
        val journalRows = phase("TRADEJRNL_REC")
        appendLine("Runtime: state=${rt?.state ?: "?"} active=${rt?.runtimeActive ?: "?"} paper=${rt?.paperMode ?: "?"} enabled=${rt?.enabledTraders ?: "?"}")
        appendLine("Funnel: loop=$loop intake=$intake lane=$lane fdg=$fdg exec=$exec journal=$journalRows")
        appendLine("ANR: hints=${snap?.anrHints ?: -1} maxFrame=${snap?.maxFrameGapMs ?: -1}ms avgCycle=${avgCycle}ms")
        appendLine("Journal cache: trades=${journal?.totalStoredTrades ?: -1} WR=${journal?.winRate?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}% PnL=${journal?.totalPnlSol?.let { String.format(Locale.US, "%.4f", it) } ?: "?"} SOL")
        appendLine()
        appendLine("Action: close heavy report screen, keep bot running, then retry after installing the latest build if available.")
    }

    private fun buildUnifiedHealth(): String {
        val budget = MAX_UNIFIED_REPORT_CHARS
        val out = StringBuilder(budget + 1024)
        out.appendHeader("AATE UNIFIED OPERATIONAL REPORT")
        out.appendLine("Generated: ${stamp()}")
        out.appendLine("Scope: runtime / pipeline / learning / tuning / journal / forensic / errors")
        out.appendLine("Size budget: ${budget} chars hard-capped for chat delivery; sections are priority-budgeted before truncation; raw journal rows excluded.")
        out.appendLine()

        fun addBoundedSection(title: String, maxChars: Int, body: () -> String) {
            if (out.length >= budget) return
            out.appendSection(title)
            val raw = safe(title.lowercase(Locale.US).replace(" ", "_")) { body() }.trimEnd()
            out.appendLine(condenseText(raw, maxChars.coerceAtMost((budget - out.length - 256).coerceAtLeast(512))))
            out.appendLine()
        }

        // V5.0.3854 — REPORT BUDGET RECOMPILE.
        // Previous budgets summed above the hard cap, so the tail (often toolkit proof,
        // forensic/errors) was still chopped. Keep all sections, but give each a strict
        // pre-cap budget and remove learning duplication from the pipeline block.
        addBoundedSection("EXECUTIVE SNAPSHOT", 2_600) { buildExecutiveSnapshot() }
        addBoundedSection("TOOLKIT SIGNAL SHEET", 2_600) { buildToolkitSignalSummary() }
        addBoundedSection("PIPELINE HEALTH — CORE", 10_500) { compactPipelineDump(PipelineHealthCollector.dumpText()) }
        addBoundedSection("LEARNING + TUNING STATE", 7_200) { buildLearningTuningSummary() }
        addBoundedSection("TRADE JOURNAL SUMMARY", 5_200) { buildJournalSummary() }
        addBoundedSection("FORENSIC SUMMARY", 2_800) { buildForensicSummary() }
        addBoundedSection("ERROR LOGS — RECENT", 3_200) { ErrorLogger.exportToCompactTable(limit = 60) }

        val text = out.toString().trimEnd()
        return if (text.length <= budget) text else text.take(budget - 180) + "\n\n[REPORT_TRUNCATED hardCap=$budget chars — sections above are priority-ordered and internally condensed]"
    }


    private const val MAX_UNIFIED_REPORT_CHARS = 42_000

    private fun buildExecutiveSnapshot(): String = buildString(3 * 1024) {
        val rt = safeSnapshot { BotRuntimeController.snapshot() }
        val pipe = safeSnapshot { PipelineHealthCollector.snapshot() }
        val doctor = try { RuntimeDoctor.requestTick() } catch (_: Throwable) { null }
        val perf = try { PerformanceAnalytics.lastSnapshotOrNull() } catch (_: Throwable) { null }
        val journal = try { TradeHistoryStore.getCanonicalTotals() } catch (_: Throwable) { null }
        appendLine("Runtime: state=${rt?.state ?: "?"} active=${rt?.runtimeActive ?: "?"} paper=${rt?.paperMode ?: "?"} enabled=${rt?.enabledTraders ?: "?"}")
        appendLine("Doctor: ${doctor?.diagnosis?.faultCode ?: "?"}/${doctor?.diagnosis?.subsystem ?: "?"} confidence=${doctor?.diagnosis?.confidence?.fmt2() ?: "?"} faults=${doctor?.faults?.size ?: "?"}")
        if (pipe != null) {
            val loop = pipe.phaseCounts["BOT_LOOP_TICK"] ?: 0L
            val intake = pipe.phaseCounts["INTAKE"] ?: 0L
            val lane = pipe.phaseCounts["LANE_EVAL"] ?: 0L
            val fdg = ((pipe.phaseAllow["FDG"] ?: 0L) + (pipe.phaseBlock["FDG"] ?: 0L)).takeIf { it > 0L } ?: (pipe.phaseCounts["FDG"] ?: 0L)
            val exec = pipe.phaseCounts["EXEC"] ?: 0L
            val journalRows = pipe.labelCounts["TRADEJRNL_REC"] ?: 0L
            // V5.0.3829 — `avgCycleMs` is not a snapshot field, it is derived
            // from totalCycleMs/cycleCount. The previous V5.0.3828 broke
            // :app:compileReleaseKotlin by referencing a non-existent property.
            val avgCycleMs = if (pipe.cycleCount > 0L) pipe.totalCycleMs / pipe.cycleCount else 0L
            appendLine("Funnel: loop=$loop intake=$intake lane=$lane fdg=$fdg exec=$exec journalRows=$journalRows avgCycle=${avgCycleMs}ms max=${pipe.maxCycleMs}ms ANR=${pipe.anrHints}")
            val topAnr = pipe.anrStackCounts.entries.maxByOrNull { it.value }
            if (topAnr != null) appendLine("ANR top: [${topAnr.value}] ${topAnr.key.take(140)}")
        }
        if (perf != null) appendLine("Perf(last analyze): n=${perf.totalTrades} WR=${perf.winRate.fmt1()}% PnL=${perf.totalPnlSol.fmt4()} SOL PF=${perf.profitFactor.fmt2()} DD=${perf.currentDrawdownPct.fmt1()}% streak=${perf.currentStreak}")
        if (journal != null) appendLine("Journal canonical: closes=${journal.trades} W/L=${journal.wins}/${journal.losses} WR=${journal.winRatePct().fmt1()}% PnL=${journal.pnlSol.fmt4()} SOL")
        appendLine("Learning: ${safe("token_win_stats") { TokenWinMemory.getStats() }} | ${safe("collective") { com.lifecyclebot.collective.CollectiveLearning.getInsightsSummary() }} | quarantined=${learningQuarantineLine()}")
        appendLine("Tuning: ${safe("pattern_auto_tuner") { PatternAutoTuner.getStatus() }}")
    }

    private fun buildToolkitSignalSummary(): String = buildString(3 * 1024) {
        val s = safeSnapshot { PipelineHealthCollector.snapshot() }
        val labels = s?.labelCounts ?: emptyMap()
        val refreshed = labels["TOOLKIT_SIGNAL_SHEET_REFRESHED"] ?: 0L
        val failed = labels["TOOLKIT_SIGNAL_SHEET_REFRESH_FAILED"] ?: 0L
        appendLine("Helper: cached snapshot + silent AppDispatchers.sideEffect refresh; no FDG/executor fanout")
        appendLine("Refresh: ok=$refreshed failed=$failed")
        appendLine(InternetEdgeDesk.summaryLine())
        appendLine("InternetEdge counters: ok=${labels["INTERNET_EDGE_REFRESHED"] ?: 0L} fail=${labels["INTERNET_EDGE_REFRESH_FAILED"] ?: 0L} parse=${labels["INTERNET_EDGE_PARSE_FAILED"] ?: 0L} skip=${labels["INTERNET_EDGE_SKIPPED_LLM_UNAVAILABLE"] ?: 0L}")
        val setups = labels.entries
            .filter { it.key.startsWith("TOOLKIT_SETUP_") }
            .sortedByDescending { it.value }
            .take(12)
        appendLine("Top setups:")
        if (setups.isEmpty()) appendLine("  none yet (cache warmup or report before async refresh)")
        setups.forEach { e -> appendLine("  ${e.key.removePrefix("TOOLKIT_SETUP_").lowercase(Locale.US)}=${e.value}") }
        val charts = labels.entries
            .filter { it.key.startsWith("TOOLKIT_CHART_") }
            .sortedByDescending { it.value }
            .take(8)
        appendLine("Top chart/setup patterns:")
        if (charts.isEmpty()) appendLine("  none yet")
        charts.forEach { e -> appendLine("  ${e.key.removePrefix("TOOLKIT_CHART_").lowercase(Locale.US)}=${e.value}") }
        appendLine("Styles available: diamond_hands, degen_snipe, pump_graduation, chart_breakout, pullback_reclaim, whale/copy, narrative/social, liquidity_depth, panic_reversion, arb_flow, mev_protected, reentry, defensive_probe")
    }

    private fun buildLearningTuningSummary(): String = buildString(10 * 1024) {
        appendLine("TokenWinMemory: ${safe("token_win_stats") { TokenWinMemory.getStats() }}")
        appendLine("Learning quarantine: ${learningQuarantineLine()}")
        appendLine("PatternMemory: ${safe("token_pattern_summary") { TokenWinMemory.getPatternSummary() }}")
        safe("best_patterns") {
            val best = TokenWinMemory.getBestPatterns(5).joinToString(" | ") { (k, v) -> "$k n=${v.wins + v.losses} WR=${(v.winRate * 100.0).fmt1()}% avgWin=${v.avgWinPnl.fmt1()}%" }
            val worst = TokenWinMemory.getWorstPatterns(5).joinToString(" | ") { (k, v) -> "$k n=${v.wins + v.losses} WR=${(v.winRate * 100.0).fmt1()}%" }
            "Best patterns: ${best.ifBlank { "none" }}\nWorst patterns: ${worst.ifBlank { "none" }}"
        }.let { appendLine(it) }
        appendLine(safe("losing_pattern_memory") { LosingPatternMemory.formatForPipelineDump().trim() }.ifBlank { "Losing-pattern memory: no mature danger buckets" })
        appendLine(safe("autonomous_meta_policy") { AutonomousMetaPolicy.formatForPipelineDump().trim() }.ifBlank { "Autonomous Meta-Policy: no mature contexts yet" })
        appendLine(safe("unified_policy_head") { UnifiedPolicyHead.formatForPipelineDump().trim() }.ifBlank { "Unified Policy Head: no trained weights yet" })
        appendLine(safe("unified_exit_policy_head") { UnifiedExitPolicyHead.formatForPipelineDump().trim() }.ifBlank { "" })
        // V5.0.4097 — ScannerSourceBrain (per-source intake AGI)
        appendLine(safe("scanner_source_brain") { ScannerSourceBrain.summary().trim() }.ifBlank { "ScannerSourceBrain: bootstrap" })
        // V5.0.4102 — ExitProviderHealth (Jupiter 503 + Pump 0x1788 circuit breakers)
        appendLine(safe("exit_provider_health") { com.lifecyclebot.engine.sell.ExitProviderHealth.summary().trim() }.ifBlank { "ExitProviderHealth: ok" })
        // V5.0.4104 — RecoveredHoldGuard
        appendLine(safe("recovered_hold_guard") { RecoveredHoldGuard.summary().trim() }.ifBlank { "RecoveredHoldGuard: idle" })
        appendLine(safe("live_probability_engine") { LiveProbabilityEngine.statusLine() })
        appendLine(safe("strategy_hypothesis") { StrategyHypothesisEngine.formatForPipelineDump().trim() }.ifBlank { "Strategy Hypothesis Engine: no active/promoted experiments" })
        appendLine(safe("lane_exit_tuner") { com.lifecyclebot.engine.learning.LaneExitTuner.formatForPipelineDump().trim() }.ifBlank { "Lane Exit Tuner: no lane tuning snapshot" })
        appendLine(safe("live_strategy_tuner") { LiveStrategyTuner.statusLine() })
        appendLine("PatternAutoTuner: ${safe("pattern_auto_tuner") { PatternAutoTuner.getStatus() }}")
        safe("pattern_auto_tuner_details") {
            val adj = PatternAutoTuner.getDetailedAdjustments().entries
                .filter { kotlin.math.abs(it.value - 1.0) >= 0.01 }
                .sortedByDescending { kotlin.math.abs(it.value - 1.0) }
                .take(12)
                .joinToString(" | ") { "${it.key}×${it.value.fmt2()}" }
            "PatternAutoTuner details: trades=${PatternAutoTuner.getTradesAnalyzed()} last=${PatternAutoTuner.getLastUpdateTs()} adj=${adj.ifBlank { "neutral" }}"
        }.let { appendLine(it) }
        appendLine(safe("collective") { com.lifecyclebot.collective.CollectiveLearning.getInsightsSummary() })
    }

    private fun buildJournalSummary(): String = buildString(6 * 1024) {
        val totals = safeSnapshot { TradeHistoryStore.getCanonicalTotals() }
        val lifetime = safeSnapshot { TradeHistoryStore.getLifetimeStats() }
        val stats = safeSnapshot { TradeHistoryStore.getStatsCached() }
        val sells24 = safeSnapshot { TradeHistoryStore.getSells24h() } ?: emptyList()
        val allSells = safeSnapshot { TradeHistoryStore.getRecentValidClosedTrades(limit = 2_500, includePartials = true) } ?: emptyList()
        if (totals != null) appendLine("Canonical totals: closes=${totals.trades} W/L=${totals.wins}/${totals.losses} WR=${totals.winRatePct().fmt1()}% PnL=${totals.pnlSol.fmt4()} SOL")
        if (lifetime != null) appendLine("Lifetime persisted: sells=${lifetime.totalSells} wins=${lifetime.totalWins} losses=${lifetime.totalLosses} pnl=${lifetime.realizedPnlSol.fmt4()} SOL")
        if (stats != null) appendLine("Store stats cache: trades=${stats.totalStoredTrades} WR=${stats.winRate.fmt1()}% avgHold=${stats.avgHoldTimeMinutes.toDouble().fmt1()}m")
        appendLine("24h closes: n=${sells24.size} W/L=${sells24.count { isJournalWin(it) }}/${sells24.count { isJournalLoss(it) }} PnL=${sells24.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }.fmt4()} SOL")
        val byMode = allSells.groupBy { TradeHistoryStore.normalizeTradeModeName(it.tradingMode).ifBlank { "UNKNOWN" } }
            .mapValues { (_, rows) ->
                val w = rows.count { isJournalWin(it) }; val l = rows.count { isJournalLoss(it) }
                val pnl = rows.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
                Triple(w, l, pnl)
            }
            .entries.sortedByDescending { it.value.third }.take(10)
        // V5.0.4106 — operator queued: WALLET_RECOVERED is a position
        // SOURCE/STATE, not a trading lane. Display it under a separate
        // 'Recovered Inventory' line so it never shows up as 'best lane'
        // and stops polluting lane attribution stats.
        val (recoveredBuckets, laneBuckets) = byMode.partition { (mode, _) ->
            val m = mode.uppercase()
            m.contains("WALLET_RECOVERED") || m.contains("RECOVERED") || m.contains("ORPHAN")
        }
        appendLine("By lane/mode (top pnl):")
        laneBuckets.forEach { (mode, t) ->
            val n = t.first + t.second
            val wr = if (n > 0) t.first * 100.0 / n else 0.0
            appendLine("  $mode n=$n W/L=${t.first}/${t.second} WR=${wr.fmt1()}% PnL=${t.third.fmt4()} SOL")
        }
        if (recoveredBuckets.isNotEmpty()) {
            appendLine("Recovered Inventory (position-source, NOT a lane):")
            recoveredBuckets.forEach { (mode, t) ->
                val n = t.first + t.second
                val wr = if (n > 0) t.first * 100.0 / n else 0.0
                appendLine("  $mode n=$n W/L=${t.first}/${t.second} WR=${wr.fmt1()}% PnL=${t.third.fmt4()} SOL ⚠ source/state — excluded from lane attribution")
            }
        }
        appendLine("Recent closes:")
        allSells.asReversed().take(10).forEach { t ->
            appendLine("  ${t.side} ${t.mode.uppercase(Locale.US)} ${t.mint.take(8)} lane=${TradeHistoryStore.normalizeTradeModeName(t.tradingMode)} pnl=${t.pnlPct.fmt1()}%/${(if (t.netPnlSol != 0.0) t.netPnlSol else t.pnlSol).fmt4()} SOL reason=${t.reason.take(48)}")
        }
    }

    private fun compactPipelineDump(raw: String): String {
        val keepHeaders = listOf(
            "===== AATE Pipeline Health Snapshot", "===== Pipeline funnel", "===== Bot-loop cycle timing", "===== Runtime stall sentinels",
            "===== Per-lane open positions", "===== Gate allow / block tally", "===== Top block reasons", "===== Intake by source", "===== LANE_EVAL by lane",
            "===== LIVE execution telemetry", "===== PAPER execution telemetry", "===== ANR / main-thread health", "===== ANR top blocking call sites",
            "===== Strategy expectancy", "===== Regime detector",
            "===== Performance analytics", "===== Separated WR metrics", "===== Throughput choke audit", "===== Token meta cache", "===== Slot health / close ledger",
            "===== Birdeye budget", "===== Trading fee accumulator", "===== API health", "===== Key verdicts"
        )
        val sections = splitSections(raw)
        val out = StringBuilder(14 * 1024)
        for ((header, body) in sections) {
            if (keepHeaders.any { header.startsWith(it) }) {
                out.appendLine(header)
                out.appendLine(condenseSectionBody(header, body))
            }
        }
        return out.toString().ifBlank { raw.lineSequence().take(220).joinToString("\n") }
    }

    private fun splitSections(raw: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var currentHeader = "===== HEADER ====="
        val buf = StringBuilder()
        raw.lineSequence().forEach { line ->
            if (line.startsWith("=====") && line.endsWith("=====")) {
                if (buf.isNotEmpty()) result += currentHeader to buf.toString().trimEnd()
                currentHeader = line
                buf.clear()
            } else buf.appendLine(line)
        }
        if (buf.isNotEmpty()) result += currentHeader to buf.toString().trimEnd()
        return result
    }

    private fun condenseSectionBody(header: String, body: String): String {
        val maxLines = when {
            "ANR / main-thread" in header -> 8
            "ANR top blocking" in header -> 8
            "API health" in header -> 10
            "Key verdicts" in header -> 8
            "Intake by source" in header -> 9
            "LANE_EVAL" in header -> 9
            "Strategy expectancy" in header -> 13
            "Performance analytics" in header -> 14
            "Throughput choke" in header -> 18
            "Pipeline funnel" in header -> 12
            "Trading fee accumulator" in header -> 6
            else -> 7
        }
        return body.lineSequence()
            .filter { it.isNotBlank() }
            .take(maxLines)
            .joinToString("\n")
    }

    private fun condenseText(raw: String, maxChars: Int): String {
        if (raw.length <= maxChars) return raw
        val lines = raw.lineSequence().toList()
        val out = StringBuilder(maxChars)
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars - 96) break
            out.appendLine(line)
        }
        out.append("[section condensed: ${raw.length}→${out.length} chars]")
        return out.toString()
    }

    private fun learningQuarantineLine(): String {
        val s = safeSnapshot { PipelineHealthCollector.snapshot() }
        val total = s?.labelCounts?.get("LEARNING_PNL_QUARANTINED") ?: 0L
        val top = s?.labelCounts?.entries
            ?.filter { it.key.startsWith("LEARNING_PNL_QUARANTINED_") }
            ?.sortedByDescending { it.value }
            ?.take(3)
            ?.joinToString(",") { "${it.key.removePrefix("LEARNING_PNL_QUARANTINED_")}=${it.value}" }
            ?: ""
        return if (top.isBlank()) total.toString() else "$total ($top)"
    }

    private fun isJournalWin(t: com.lifecyclebot.data.Trade): Boolean = t.pnlPct >= 0.5
    private fun isJournalLoss(t: com.lifecyclebot.data.Trade): Boolean = t.pnlPct <= -2.0

    private fun Double.fmt1(): String = String.format(Locale.US, "%.1f", this)
    private fun Double.fmt2(): String = String.format(Locale.US, "%.2f", this)
    private fun Double.fmt4(): String = String.format(Locale.US, "%.4f", this)
    private fun TradeHistoryStore.AssetCounts.winRatePct(): Double {
        val decisive = wins + losses
        return if (decisive > 0) wins * 100.0 / decisive else 0.0
    }

    private fun buildForensicSummary(): String = buildString(8 * 1024) {
        appendHeader("AATE FORENSIC SUMMARY")
        appendLine("Generated: ${stamp()}")
        appendLine(safe("forensic_summary") { ForensicReportExporter.textSummary() })
        appendLine()
        appendLine("Runtime:")
        val runtime = safeSnapshot { BotRuntimeController.snapshot() }
        if (runtime != null) {
            appendLine("  generation=${runtime.runtimeGeneration}")
            appendLine("  state=${runtime.state.name}")
            appendLine("  active=${runtime.runtimeActive}")
            appendLine("  botLoop=${runtime.botLoopJobActive}")
            appendLine("  scanner=${runtime.scannerActive}")
            appendLine("  paper=${runtime.paperMode}")
            appendLine("  enabled=${runtime.enabledTraders}")
        } else {
            appendLine("  unavailable")
        }
        appendLine()
        appendLine("Doctor:")
        val doctor = try { RuntimeDoctor.requestTick() } catch (_: Throwable) { null }
        if (doctor != null) {
            appendLine("  diagnosis=${doctor.diagnosis.faultCode}")
            appendLine("  state=${doctor.diagnosis.state}")
            appendLine("  subsystem=${doctor.diagnosis.subsystem}")
            appendLine("  confidence=${String.format(Locale.US, "%.2f", doctor.diagnosis.confidence)}")
            appendLine("  faults=${doctor.faults.size}")
            doctor.faults.take(12).forEach { f -> appendLine("  - ${f.code}/${f.severity}: ${f.detail.take(160)}") }
        } else {
            appendLine("  unavailable")
        }
    }

    private inline fun <T> safeSnapshot(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    private inline fun safe(label: String, block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "($label report unavailable: ${t.javaClass.simpleName}: ${t.message})"
    }

    private fun StringBuilder.appendHeader(title: String) {
        appendLine("═══════════════════════════════════════════")
        appendLine(title)
        appendLine("═══════════════════════════════════════════")
    }

    private fun StringBuilder.appendSection(title: String) {
        appendLine("===== $title =====")
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
