package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.execution.ForensicReportExporter
import com.lifecyclebot.engine.execution.PositionWalletReconciler
import com.lifecyclebot.util.AppDispatchers
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
        out.appendLine("Size budget: ${budget} chars paste-safe hard cap; every major section is represented; raw rows are summarized, not dumped.")
        out.appendLine()

        fun addBoundedSection(title: String, maxChars: Int, body: () -> String) {
            if (out.length >= budget) return
            out.appendSection(title)
            val raw = safe(title.lowercase(Locale.US).replace(" ", "_")) { body() }.trimEnd()
            out.appendLine(condenseText(raw, maxChars.coerceAtMost((budget - out.length - 256).coerceAtLeast(512))))
            out.appendLine()
        }

        // V5.0.4487 — PASTE-SAFE REPORT CONTRACT.
        // Operator reports were still too large for chat: downstream channels chopped
        // the tail even though each section had its own local budget. Keep every major
        // category, but shrink the global envelope and summarize raw/error rows.
        // V5.0.6048 — REPORT EXPANDED (operator ask 2026-07-03: 'expand the
        // runtime report for as much data and context as you can handle. grab
        // as much data as you think will help'). Budgets bumped ~5x per
        // section, envelope raised to 100_000. Still readable — larger fonts
        // still line-based.
        addBoundedSection("EXECUTIVE SNAPSHOT", 8_000) { buildExecutiveSnapshot() }
        addBoundedSection("TOOLKIT SIGNAL SHEET", 8_000) { buildToolkitSignalSummary() }
        addBoundedSection("MONEY PATH TRUTH", 9_000) { buildMoneyPathTruthSummary6029() }
        addBoundedSection("PIPELINE HEALTH — CORE", 25_000) { compactPipelineDump(PipelineHealthCollector.dumpText()) }
        addBoundedSection("LEARNING + TUNING STATE", 18_000) { buildLearningTuningSummary() }
        addBoundedSection("TRADE JOURNAL SUMMARY", 12_000) { buildJournalSummary() }
        addBoundedSection("FORENSIC SUMMARY", 8_000) { buildForensicSummary() }
        addBoundedSection("ERROR LOGS — RECENT", 8_000) { ErrorLogger.exportToCompactTable(limit = 80) }
        out.appendLine("Report envelope: PASTE_SAFE_V6048 chars=${out.length}/$budget sections=8 errorLimit=80")

        val text = out.toString().trimEnd()
        return if (text.length <= budget) text else text.take(budget - 180) + "\n\n[REPORT_TRUNCATED_UNEXPECTED hardCap=$budget chars — paste-safe budgets failed; reduce section budgets]"
    }


    private const val MAX_UNIFIED_REPORT_CHARS = 100_000

    private fun buildMoneyPathTruthSummary6029(): String = buildString(2 * 1024) {
        val pipe = safeSnapshot { PipelineHealthCollector.snapshot() }
        val wallet = try { BotService.status.walletSol } catch (_: Throwable) { 0.0 }
        val open = try { BotService.status.openPositions.toList() } catch (_: Throwable) { emptyList() }
        var trustedOpenPnl = 0.0
        var trustedOpenCount = 0
        val top = open.asSequence().map { ts ->
            val p = ts.position
            // V5.0.6037: no local price/PnL math here; consume canonical pricing truth below.
            val truth6037 = try { OpenPnlSanity.pricingTruth(ts, "ReportingHub.money_path_6037/${ts.symbol}/${ts.mint.take(8)}", emit = false) } catch (_: Throwable) { OpenPnlSanity.PricingTruth(ts.ref, 0.0, 0.0, false, "PRICING_TRUTH_THROW", ts.lastPriceSource.ifBlank { "UNKNOWN" }) }
            val route = try { RealPriceLock.lastRouteTruth(ts.mint) } catch (_: Throwable) { null }
            val pnlPct = truth6037.pnlPct
            val pnlSol = truth6037.pnlSol
            if (truth6037.trusted && !p.isPaperPosition && pnlSol > 0.0) { trustedOpenPnl += pnlSol; trustedOpenCount += 1 }
            val routeTxt = when {
                route != null -> "route=${route.impliedRatio.fmt1()}x/${if (route.ok) "ok" else "claim_mismatch"}"
                else -> "route=canonical_mark_source:${truth6037.source}"
            }
            val basisTxt = if (truth6037.trusted) "basis=trusted" else "basis=${truth6037.reason.ifBlank { "untrusted" }}"
            Triple(pnlPct, pnlSol, "${ts.symbol.ifBlank { ts.mint.take(6) }} lane=${p.tradingMode.ifBlank { "?" }} ${basisTxt} ${routeTxt} open=${p.costSol.fmt4()} mark=${truth6037.markPrice} pnl=${pnlPct.fmt1()}%/${pnlSol.fmt4()} SOL")
        }.sortedByDescending { it.first }.take(5).toList()
        appendLine("wallet=${wallet.fmt4()} SOL hostOpen=${open.count { !it.position.isPaperPosition }} trustedOpen=${trustedOpenPnl.fmt4()} SOL runners=$trustedOpenCount note=open_unrealized_not_wallet_until_sell_finality")
        if (pipe != null) {
            fun label(k: String): Long = pipe.labelCounts[k] ?: 0L
            appendLine("harvest: walletGrowth=${label("WALLET_GROWTH_HARVEST_TRIGGERED_6028")} routeReal=${label("ROUTE_REAL_CLAIM_MISMATCH_HARVEST_6029")} priceUnreal=${label("WALLET_GROWTH_HARVEST_DEFERRED_PRICE_UNREAL_6028") + label("ULTRA_RUNNER_BANK_DEFERRED_PRICE_UNREAL")} walletNull=${label("WALLET_GROWTH_HARVEST_DEFERRED_WALLET_NULL_6028")}")
            val liveSellOk6037 = try { PipelineHealthCollector.execLiveSellOkCount() } catch (_: Throwable) { label("LIVE_SELL_OK") + label("EXEC/LIVE_SELL_OK") + label("LIFECYCLE/SELL_FINALIZED") }
            val liveSellPending6037 = try { PipelineHealthCollector.execLiveSellPendingFinalityCount() } catch (_: Throwable) { label("LIVE_SELL_PENDING") + label("SELL_FINALITY_PENDING_RETRY") + label("SELL_FINALITY_PENDING_RETRY_NO_PROCEEDS") }
            val liveSellFail6037 = try { PipelineHealthCollector.execLiveSellFailCount() } catch (_: Throwable) { label("LIVE_SELL_FAIL") + label("EXEC/LIVE_SELL_FAIL") }
            appendLine("sell finality: liveSellOk=$liveSellOk6037 pending=$liveSellPending6037 fail=$liveSellFail6037 source=PipelineHealthCollector_event_counters_6037")
        }
        if (top.isEmpty()) appendLine("top opens: none") else {
            appendLine("top opens:")
            top.forEach { appendLine("  ${it.third}") }
        }
        appendLine("truth contract: DISCOVERY price/source -> BUY entry snapshot -> OPEN unrealized basis/route label -> SELL finality net SOL -> JOURNAL strategy truth -> WALLET balance")
    }

    private fun buildExecutiveSnapshot(): String = buildString(3 * 1024) {
        val rt = safeSnapshot { BotRuntimeController.snapshot() }
        val pipe = safeSnapshot { PipelineHealthCollector.snapshot() }
        val doctor = try { RuntimeDoctor.requestTick() } catch (_: Throwable) { null }
        val perf = try { PerformanceAnalytics.lastSnapshotOrNull() } catch (_: Throwable) { null }
        val journal = try { TradeHistoryStore.getCanonicalTotals() } catch (_: Throwable) { null }
        // V5.0.4509 — executive money truth. Raw/canonical journal totals can
        // include historical duplicate-terminal and recovery-inventory rows; the
        // Trade Journal section already exposes StrategyTruthLedger.clean, but the
        // old executive headline still printed inflated raw PnL first. When the
        // truth ledger excludes any rows, headline Strategy Clean and demote raw
        // canonical to an audit line so the operator sees wallet-correspondent PnL.
        val strategyTruth4509 = safeSnapshot {
            val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 2_500, includePartials = true)
            StrategyTruthLedger.clean(raw, 2_500)
        }
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
        val truthRows4509 = strategyTruth4509?.rows.orEmpty()
        val truthAudit4509 = strategyTruth4509?.audit
        val excluded4509 = (truthAudit4509?.deduped ?: 0) + (truthAudit4509?.recoveryExcluded ?: 0) + (truthAudit4509?.partialNotTerminal ?: 0) + (truthAudit4509?.badEntryExcluded ?: 0) + (truthAudit4509?.forensicExcluded ?: 0)
        if (strategyTruth4509 != null) {
            val sw = truthRows4509.count { isJournalWin(it) }
            val sl = truthRows4509.count { isJournalLoss(it) }
            val spnl = truthRows4509.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
            val swr = if (sw + sl > 0) sw * 100.0 / (sw + sl) else 0.0
            appendLine("Strategy Clean headline: closes=${truthRows4509.size} W/L=$sw/$sl WR=${swr.fmt1()}% PnL=${spnl.fmt4()} SOL source=StrategyTruthLedger")
            if (journal != null) appendLine("Raw journal audit: rows=${journal.trades} W/L=${journal.wins}/${journal.losses} WR=${journal.winRatePct().fmt1()}% PnL=${journal.pnlSol.fmt4()} SOL excluded=$excluded4509 note=raw_not_strategy_truth")
        } else if (journal != null) {
            appendLine("Raw journal fallback: rows=${journal.trades} W/L=${journal.wins}/${journal.losses} WR=${journal.winRatePct().fmt1()}% PnL=${journal.pnlSol.fmt4()} SOL note=StrategyTruthLedger_unavailable_not_canonical")
        }
        appendLine("Learning: ${safe("token_win_stats") { TokenWinMemory.getStats() }} | ${safe("collective") { com.lifecyclebot.collective.CollectiveLearning.getInsightsSummary() }} | quarantined=${learningQuarantineLine()}")
        appendLine("Tuning: ${safe("pattern_auto_tuner") { PatternAutoTuner.getStatus() }}")
    }

    private fun primeResearchScoutFromRuntime4516() {
        try {
            CoroutineScope(AppDispatchers.sideEffect).launch {
                try {
                    val tokens = BotService.status.tokens.values
                        .asSequence()
                        .filter { it.mint.isNotBlank() }
                        .sortedByDescending { maxOf(it.lastMcap, it.lastLiquidityUsd, (it.lastV3Score ?: it.entryScore.toInt()).toDouble()) }
                        .take(12)
                        .toList()
                    var queued = 0
                    tokens.forEach { ts ->
                        val id = ResearchScout.enqueueBackgroundRequest(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            reason = "runtime_report_cached_toolkit_prime_4516",
                            sourceTag = "BACKGROUND_RESEARCH_SCOUT_REPORT_4516",
                        )
                        if (id.isNotBlank()) queued += 1
                    }
                    val swept = ResearchScout.maybeRunPeriodicBackgroundSweep(
                        sourceTag = "BACKGROUND_RESEARCH_SCOUT_REPORT_SWEEP_4516",
                        maxRequests = 12,
                    )
                    if (queued > 0 || swept > 0) {
                        try { PipelineHealthCollector.labelInc("RESEARCH_SCOUT_REPORT_PRIMED_4516") } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun buildToolkitSignalSummary(): String = buildString(3 * 1024) {
        primeResearchScoutFromRuntime4516()
        val s = safeSnapshot { PipelineHealthCollector.snapshot() }
        val labels = s?.labelCounts ?: emptyMap()
        val refreshed = labels["TOOLKIT_SIGNAL_SHEET_REFRESHED"] ?: 0L
        val failed = labels["TOOLKIT_SIGNAL_SHEET_REFRESH_FAILED"] ?: 0L
        appendLine("Helper: cached snapshot + silent AppDispatchers.sideEffect refresh; no FDG/executor fanout")
        appendLine("Refresh: ok=$refreshed failed=$failed")
        appendLine(InternetEdgeDesk.summaryLine())
        appendLine("InternetEdge counters: ok=${labels["INTERNET_EDGE_REFRESHED"] ?: 0L} fail=${labels["INTERNET_EDGE_REFRESH_FAILED"] ?: 0L} parse=${labels["INTERNET_EDGE_PARSE_FAILED"] ?: 0L} skip=${labels["INTERNET_EDGE_SKIPPED_LLM_UNAVAILABLE"] ?: 0L}")
        appendLine("ResearchScout: ${safe("research_scout_free_status") { ResearchScout.freeSourceStatus() }} primed=${labels["RESEARCH_SCOUT_REPORT_PRIMED_4516"] ?: 0L} background_only=true")
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
        appendLine("Styles available (V5.0.6050 — expanded from 13 to full doctrine surface):")
        appendLine("  Entry: diamond_hands, degen_snipe, pump_graduation, chart_breakout, pullback_reclaim,")
        appendLine("         whale_copy_follow, narrative_social_ignition, liquidity_depth_quality,")
        appendLine("         panic_reversion, arb_flow_imbalance, mev_protected_entry, reentry_after_shakeout,")
        appendLine("         defensive_probe, fresh_pool_momentum, accumulation_compression, volume_ignition_scalp,")
        appendLine("         quality_accumulation_swing, runner_near_high, smart_wallet_follow, exhaustion_upper_wick,")
        appendLine("         mainstream_crypto_swing, dip_hunter_gap_fill, project_sniper_launchpad, blue_chip_swing,")
        appendLine("         cashgen_micro_scalp, treasury_compounder, moonshot_diamond_hold, shitcoin_flash_flip,")
        appendLine("         express_5min_bank, cyclic_reentry, manipulated_probe, cutlass_reject, dead_pool_avoid,")
        appendLine("         holder_concentration_reject, low_liquidity_dust_probe, high_tax_avoid, honeypot_hard_reject,")
        appendLine("         lp_unlocked_hard_reject, whale_dump_avoid, developer_dump_avoid, first_hour_pump,")
        appendLine("         graduation_ramp_ride, first_partial_at_25, first_partial_at_50, first_partial_at_100,")
        appendLine("         moonshot_promotion_from_quality, quality_promotion_from_standard, project_sniper_transfer_out,")
        appendLine("         smart_recycle_after_win, smart_reentry_after_moonshot, cool_off_after_loss, streak_bank_pause,")
        appendLine("         drawdown_defensive_size, regime_chop_size_down, regime_bull_fan_size_up, regime_bear_fan_avoid,")
        appendLine("         theme_space_boost, theme_ai_boost, theme_musk_avoid, theme_solana_generic_avoid,")
        appendLine("         theme_inu_avoid, theme_bonk_avoid, name_medium_boost, name_short_symbol_boost,")
        appendLine("         name_ambiguous_avoid, buy_pressure_weak_probe, buy_pressure_strong_bank_early,")
        appendLine("         sell_pressure_strong_exit, momentum_up_hold, momentum_down_exit, liquidity_eroding_exit,")
        appendLine("         time_pnl_stagnation_exit, break_even_defer_below_effBE, tick_catastrophic_confirmed_exit,")
        appendLine("         trailing_fluid_loss_exit, quick_runner_6x_bank_95pct, quick_runner_10x_full_exit,")
        appendLine("         realized_scratch_after_profit_signal, realized_scratch_after_risk_exit_signal,")
        appendLine("         realized_win_after_risk_exit_signal, catastrophic_stop_loss_overrun, universal_sl_backstop,")
        appendLine("         strict_sl_neg3, strict_sl_neg4, strict_sl_neg5, hard_sl_backstop_bluechip_neg8,")
        appendLine("         moonshot_stop_loss, moonshot_trailing_stop, quality_stop_loss, treasury_stop_loss,")
        appendLine("         cashgen_stop_loss, project_sniper_stop_loss, express_stop_loss, shitcoin_stop_loss,")
        appendLine("         manip_stop_loss, cyclic_ring_reentry, provider_proof_holder_cascade_blind_softshape,")
        appendLine("         provider_proof_holder_cascade_bypass_api_degraded, holder_growth_positive_boost,")
        appendLine("         holder_concentration_penalty, insider_wallet_follow, insider_wallet_dump_avoid,")
        appendLine("         llm_lab_promoted_strategy, agi_authority_authoritative_override, agi_authority_advisory_softshape,")
        appendLine("         wallet_growth_harvest, wallet_growth_harvest_stale_force, ultra_runner_live_bank,")
        appendLine("         route_real_claim_mismatch_harvest, price_verifier_disagrees_defer, network_signal_auto_buy,")
        appendLine("         moonshot_dust_probe, manip_dust_probe, cutlass_soft_shape, low_wr_lane_auto_raise,")
        appendLine("         low_ev_lane_auto_quarantine, dyn_quarantine_wr_below_15, lane_release_wr_above_30,")
        appendLine("         cold_start_priors_first_30_trades")
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
        appendLine(safe("execution_route_reliability") { ExecutionRouteReliabilityMemory.statusLine() })
        appendLine(safe("live_lane_fanout_pressure") { val f = LiveLaneFanoutPressure.snapshot(); "LiveLaneFanoutPressure: active=${f.active} ratio=${f.ratio.fmt2()} WR=${f.liveWrPct.fmt1()}% n=${f.liveN} reason=${f.reason}" })
        // V5.0.4104 — RecoveredHoldGuard
        appendLine(safe("recovered_hold_guard") { RecoveredHoldGuard.summary().trim() }.ifBlank { "RecoveredHoldGuard: idle" })
        appendLine(safe("live_probability_engine") { LiveProbabilityEngine.statusLine() })
        appendLine(safe("clean_live_strategy_truth") {
            val clean = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(750)
            val n = clean.sumOf { it.trades }
            val w = clean.sumOf { it.wins }
            val l = clean.sumOf { it.losses }
            val pnl = clean.sumOf { it.totalSolPnl }
            val wr = if (w + l > 0) w * 100.0 / (w + l) else 0.0
            "CleanLiveStrategyTruth: lanes=${clean.size} n=$n W/L=$w/$l WR=${wr.fmt1()}% PnL=${pnl.fmt4()} SOL source=StrategyTruthLedger"
        })
        appendLine(safe("strategy_hypothesis") { StrategyHypothesisEngine.formatForPipelineDump().trim() }.ifBlank { "Strategy Hypothesis Engine: no active/promoted experiments" })
        appendLine(safe("lane_exit_tuner") { com.lifecyclebot.engine.learning.LaneExitTuner.formatForPipelineDump().trim() }.ifBlank { "Lane Exit Tuner: no lane tuning snapshot" })
        appendLine(safe("live_strategy_tuner") { LiveStrategyTuner.statusLine() })
        appendLine(safe("realized_wallet_compounding") { RealizedWalletCompoundingGovernor.statusLine() })
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
        val cleanStats4517 = safeSnapshot { TradeHistoryStore.getCleanStatsSnapshot4517() }
        val sells24 = safeSnapshot { TradeHistoryStore.getSells24h() } ?: emptyList()
        val allSells = safeSnapshot { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 2_500, includePartials = true) } ?: emptyList()
        val truth = safeSnapshot { StrategyTruthLedger.clean(allSells, 2_500) }
        if (truth != null) {
            val clean = truth.rows
            val cw = clean.count { isJournalWin(it) }; val cl = clean.count { isJournalLoss(it) }
            val cpnl = clean.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
            val inv = StrategyTruthLedger.inventoryRecoveryRows(allSells)
            appendLine("Strategy Clean: closes=${clean.size} W/L=$cw/$cl WR=${(if (cw + cl > 0) cw * 100.0 / (cw + cl) else 0.0).fmt1()}% PnL=${cpnl.fmt4()} SOL")
            val liveWalletSol4496 = safeSnapshot { BotService.status.walletSol } ?: 0.0
            val hostOpen4496 = safeSnapshot { HostWalletTokenTracker.getOpenCount() } ?: 0
            val rawJournalPnl4496 = allSells.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
            appendLine("Wallet Realization: liveWallet=${liveWalletSol4496.fmt4()} SOL strategyCleanPnL=${cpnl.fmt4()} SOL rawJournalPnL=${rawJournalPnl4496.fmt4()} SOL hostOpen=$hostOpen4496 note=journal_pnl_is_not_wallet_balance")
            appendLine("Inventory Recovery: positions=${inv.size} realised/unrealised=${inv.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }.fmt4()} SOL")
            appendLine("Excluded From Strategy: recovered=${truth.audit.recoveryExcluded} duplicateTerminal=${truth.audit.deduped} partialNotTerminal=${truth.audit.partialNotTerminal} badEntry=${truth.audit.badEntryExcluded} forensic=${truth.audit.forensicExcluded}")
        }
        if (totals != null) appendLine("Raw journal totals: rows=${totals.trades} W/L=${totals.wins}/${totals.losses} WR=${totals.winRatePct().fmt1()}% PnL=${totals.pnlSol.fmt4()} SOL note=pre_truth_ledger_audit_not_strategy_closes")
        if (lifetime != null) appendLine("Lifetime persisted: sells=${lifetime.totalSells} wins=${lifetime.totalWins} losses=${lifetime.totalLosses} pnl=${lifetime.realizedPnlSol.fmt4()} SOL")
        if (stats != null) appendLine("Store stats cache: trades=${stats.totalStoredTrades} WR=${stats.winRate.fmt1()}% avgHold=${stats.avgHoldTimeMinutes.toDouble().fmt1()}m")
        if (cleanStats4517 != null) appendLine("Store clean stats 4517: trades=${cleanStats4517.totalStoredTrades} W/L=${cleanStats4517.totalWins}/${cleanStats4517.totalLosses} WR=${cleanStats4517.winRate.fmt1()}% PnL=${cleanStats4517.totalPnlSol.fmt4()} SOL PF=${cleanStats4517.profitFactor.fmt2()} source=StrategyTruthLedger rawPreserved=true")
        appendLine("24h raw sell rows: n=${sells24.size} W/L=${sells24.count { isJournalWin(it) }}/${sells24.count { isJournalLoss(it) }} PnL=${sells24.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }.fmt4()} SOL note=includes_raw_partial_or_audit_rows")
        val byMode = allSells.groupBy { TradeHistoryStore.normalizeTradeModeName(it.tradingMode).ifBlank { "UNKNOWN" } }
            .mapValues { (_, rows) ->
                val w = rows.count { isJournalWin(it) }; val l = rows.count { isJournalLoss(it) }
                val pnl = rows.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
                Triple(w, l, pnl)
            }
            .entries.sortedByDescending { it.value.third }.take(10)
        appendLine("By lane/mode (top pnl):")
        byMode.forEach { (mode, t) ->
            val n = t.first + t.second
            val wr = if (n > 0) t.first * 100.0 / n else 0.0
            appendLine("  $mode n=$n W/L=${t.first}/${t.second} WR=${wr.fmt1()}% PnL=${t.third.fmt4()} SOL")
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
