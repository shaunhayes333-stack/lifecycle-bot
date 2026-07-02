package com.lifecyclebot.engine.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.LiveTradeLogStore
import com.lifecyclebot.engine.BotRuntimeController
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.ExecutionRouteGuard
import com.lifecyclebot.engine.LaneExecutionCoordinator
import com.lifecyclebot.engine.QuarantineStore
import com.lifecyclebot.engine.TradeOutcomeLedger
import com.lifecyclebot.engine.RuntimeRegressionGuards
import com.lifecyclebot.engine.RuntimeDoctor
import com.lifecyclebot.engine.RuntimeStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V5.9.495z22 — Forensic Report Exporter (item D of the abcd refactor).
 *
 * Operator spec: "one-tap report listing intended_symbol / stored_mint /
 * wallet_balance_for_that_mint / last_buy_signature".
 *
 * One static call:
 *   • dumpToFile(ctx) writes a fully-detailed JSON report to the app's
 *     external cache dir and returns the File reference.
 *   • shareIntent(ctx, file) wraps the file in an ACTION_SEND chooser so
 *     the operator can email / Drive / Telegram it instantly.
 *
 * The JSON payload bundles:
 *   { exportedAtMs, app_version, reconciler { lastSnapshot, sources },
 *     host_tracker { open_count, positions[] },
 *     forensics_events[] (recent ring-buffer),
 *     trade_log_groups[] (latest per-trade-key timeline),
 *     phantom_findings[] (current PositionWalletReconciler verdict) }
 */
object ForensicReportExporter {

    private const val TAG = "ForensicExport"
    private const val SUBDIR = "forensics"
    /** FileProvider authority used for the share intent. Must match the
     *  manifest entry — uses the app id which we don't know statically here,
     *  so we resolve it at runtime. */
    private fun authority(ctx: Context): String = "${ctx.packageName}.fileprovider"

    /** Writes the full JSON snapshot to cache and returns the File. */
    @Synchronized
    fun dumpToFile(ctx: Context): File {
        val payload = buildPayload()
        val dir = File(ctx.cacheDir, SUBDIR).apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "forensics_$ts.json")
        try {
            out.writeText(payload.toString(2))
            ErrorLogger.info(TAG, "✅ forensic report written: ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "❌ forensic report write failed: ${e.message}")
        }
        return out
    }

    /** Share intent — operator taps the export button → chooser opens. */
    fun shareIntent(ctx: Context, file: File): Intent? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AATE Forensic Report ${file.name}")
                putExtra(Intent.EXTRA_TEXT,
                    "AATE forensic export attached. " +
                    "Compare intended_symbol vs stored_mint vs wallet_balance vs last_buy_signature.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "shareIntent failed: ${e.message}")
            null
        }
    }

    /** Plain-text fallback summary for the in-app diagnostics row. */
    fun textSummary(): String {
        val recon = PositionWalletReconciler.snapshot()
        val sellChecked6037 = try { com.lifecyclebot.engine.sell.SellReconciler.totalChecked } catch (_: Throwable) { 0L }
        val sellTicks6037 = try { com.lifecyclebot.engine.sell.SellReconciler.totalTicks } catch (_: Throwable) { 0L }
        val liveWalletChecked6037 = try { com.lifecyclebot.engine.sell.LiveWalletReconciler.totalChecked().toLong() } catch (_: Throwable) { 0L }
        val effectiveRecon6037 = maxOf(recon.totalChecked.toLong(), sellChecked6037, liveWalletChecked6037)
        val tracker = try { HostWalletTokenTracker.getStats() } catch (_: Throwable) { "n/a" }
        val groups = try { LiveTradeLogStore.groupedByTrade().size } catch (_: Throwable) { 0 }
        return "Recon: effective=$effectiveRecon6037 position=${recon.totalChecked} sell=$sellChecked6037 liveWallet=$liveWalletChecked6037 ticks=$sellTicks6037 healthy=${recon.healthy} " +
               "phantoms=${recon.phantoms} noMint=${recon.noMint} grace=${recon.grace} | " +
               "Tracker: $tracker | TradeLog groups: $groups"
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private fun buildPayload(): JSONObject {
        val root = JSONObject()
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("schemaVersion", "z22.2-runtime")
        val runtime = BotRuntimeController.snapshot()
        val runtimeSnapshot = try { RuntimeStateSnapshot.current() } catch (_: Throwable) { null }
        root.put("runtimeGeneration", runtime.runtimeGeneration)
        val doctor = try { RuntimeDoctor.tick() } catch (_: Throwable) { null }
        root.put("runtime_doctor", JSONObject().apply {
            put("fault_count", doctor?.faults?.size ?: -1)
            put("diagnosis_fault", doctor?.diagnosis?.faultCode ?: "unavailable")
            put("diagnosis_state", doctor?.diagnosis?.state ?: "unavailable")
            put("diagnosis_subsystem", doctor?.diagnosis?.subsystem ?: "unavailable")
            put("diagnosis_confidence", doctor?.diagnosis?.confidence ?: 0.0)
            put("safe_actions", doctor?.recommendedActions?.joinToString(",") { it.action.name + ":" + it.target } ?: "")
            put("latest_snapshot_ts", doctor?.snapshot?.timestampMs ?: 0L)
            put("active_mitigations", doctor?.snapshot?.activeMitigations?.joinToString(",") ?: "")
            put("quality_only_policy", com.lifecyclebot.engine.RuntimeConfigOverlay.qualityOnlySummary())
        })
        root.put("runtime", JSONObject().apply {
            put("generation", runtime.runtimeGeneration)
            put("state", runtime.state.name)
            put("runtimeActive", runtime.runtimeActive)
            put("botLoopJobActive", runtime.botLoopJobActive)
            put("scannerActive", runtime.scannerActive)
            put("hotExitJobActive", runtime.hotExitJobActive)
            put("paperMode", runtime.paperMode)
            put("enabledTraders", runtime.enabledTraders)
            put("hostTrackerOpenCount", runtime.hostTrackerOpenCount)
            put("positionStoreOpenCount", runtimeSnapshot?.positionStoreOpenCount ?: -1)
            put("paperOpenPositions", runtimeSnapshot?.paperOpenPositions ?: -1)
            put("liveOpenPositions", runtimeSnapshot?.liveOpenPositions ?: -1)
            put("walletHeldMints", runtimeSnapshot?.walletHeldMints ?: -1)
            put("canonicalOpenPositions", runtimeSnapshot?.canonicalOpenPositions ?: -1)
            put("orphanPaperPositions", runtimeSnapshot?.orphanPaperPositions ?: -1)
            put("orphanLivePositions", runtimeSnapshot?.orphanLivePositions ?: -1)
            put("sellReconcilerStarted", runtime.sellReconcilerStarted)
            put("updatedAtMs", runtime.updatedAtMs)
        })
        val regressionChecks = try {
            RuntimeRegressionGuards.evaluate(
                RuntimeRegressionGuards.Input(
                    uniqueClosedPositionIds = TradeOutcomeLedger.uniqueClosedPositionCount().toLong(),
                    learningTrades = TradeOutcomeLedger.uniqueClosedPositionCount().toLong(),
                    forensicsEvents = try { Forensics.size().toLong() } catch (_: Throwable) { 0L },
                    forensicLoggingOn = com.lifecyclebot.engine.ForensicLogger.enabled,
                    runtimeActive = runtime.runtimeActive,
                    uiRunning = runtime.runtimeActive,
                    mode = runtimeSnapshot?.mode ?: if (runtime.paperMode) "PAPER" else "LIVE",
                    sellReconcilerStarted = runtime.sellReconcilerStarted,
                    hostTrackerOpenCount = runtimeSnapshot?.hostTrackerOpenCount ?: runtime.hostTrackerOpenCount,
                    positionStoreOpenCount = runtimeSnapshot?.positionStoreOpenCount ?: -1,
                    paperOpenPositions = runtimeSnapshot?.paperOpenPositions ?: 0,
                    liveOpenPositions = runtimeSnapshot?.liveOpenPositions ?: 0,
                    walletHeldMints = runtimeSnapshot?.walletHeldMints ?: 0,
                    canonicalOpenPositions = runtimeSnapshot?.canonicalOpenPositions ?: 0,
                    // V5.9.1522 — live execution finalisation guard inputs
                    orphanLivePositions = runtimeSnapshot?.orphanLivePositions ?: 0,
                    reconcilerTotalTicks = try { com.lifecyclebot.engine.sell.SellReconciler.totalTicks } catch (_: Throwable) { 0L },
                    sellJobsActive = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size } catch (_: Throwable) { 0 },
                    noSignatureLeakedLock = try { com.lifecyclebot.engine.sell.SellFailureHistory.hasLeakedNoSignatureLock() } catch (_: Throwable) { false },
                    // grace: only assert orphan==0 once the reconciler has actually ticked a few times
                    reconciliationGraceElapsed = try { com.lifecyclebot.engine.sell.SellReconciler.totalTicks >= 3L } catch (_: Throwable) { true },
                    // V5.9.1526 — canonical close-authority guard inputs
                    closedPositionsWithNonDustBalance = try { com.lifecyclebot.engine.HostWalletTokenTracker.closeAuthorityAudit().closedWithNonDustBalance } catch (_: Throwable) { 0 },
                    closedPositionsWithoutSignature = try { com.lifecyclebot.engine.HostWalletTokenTracker.closeAuthorityAudit().closedWithoutSig } catch (_: Throwable) { 0 },
                    duplicateCanonicalOpenMints = try { com.lifecyclebot.engine.HostWalletTokenTracker.closeAuthorityAudit().duplicateOpenMints } catch (_: Throwable) { 0 },
                    // V5.9.1533 — sell-safety / balance-authority / venue / learning guard inputs
                    liveSellsBroadcastOnUnconfirmedBalance = try { com.lifecyclebot.engine.RuntimeRegressionState.broadcastOnUnconfirmedBalanceCount() } catch (_: Throwable) { 0 },
                    liveSellsAboveSlippageCap = try { com.lifecyclebot.engine.RuntimeRegressionState.liveSellAboveSlippageCapCount() } catch (_: Throwable) { 0 },
                    pumpRouteInvalidNotReResolved = try { com.lifecyclebot.engine.RuntimeRegressionState.pumpRouteInvalidNotReResolvedCount() } catch (_: Throwable) { 0 },
                    learningFromUnconfirmedClose = try { com.lifecyclebot.engine.RuntimeRegressionState.learningFromUnconfirmedCloseCount() } catch (_: Throwable) { 0 },
                )
            )
        } catch (_: Throwable) { emptyList() }
        root.put("regression_guard_summary", try { RuntimeRegressionGuards.summary(regressionChecks) } catch (_: Throwable) { "REGRESSION_GUARDS_ERROR" })
        root.put("regression_counters", JSONObject().apply {
            put("lane_duplicate_open_suppressed", try { LaneExecutionCoordinator.duplicateOpenSuppressions() } catch (_: Throwable) { -1 })
            put("quarantine_suppressed", try { QuarantineStore.suppressedCount() } catch (_: Throwable) { -1 })
            put("duplicate_open_attempts_suppressed", try { TradeOutcomeLedger.duplicateOpenSuppressions() } catch (_: Throwable) { -1 })
            put("duplicate_close_attempts_suppressed", try { TradeOutcomeLedger.duplicateCloseSuppressions() } catch (_: Throwable) { -1 })
            // V5.9.1527 — close-lease lifecycle (spec item 3 acceptance B/G).
            put("close_lease_duplicate_suppressed", try { com.lifecyclebot.engine.sell.CloseLease.duplicateCloseAttemptsSuppressed } catch (_: Throwable) { -1L })
            put("close_lease_active", try { com.lifecyclebot.engine.sell.CloseLease.activeLeaseCount() } catch (_: Throwable) { -1 })
            put("close_lease_blocking", try { com.lifecyclebot.engine.sell.CloseLease.activeBlockingLeaseCount() } catch (_: Throwable) { -1 })
            put("orphan_closes_suppressed", try { TradeOutcomeLedger.orphanCloseSuppressions() } catch (_: Throwable) { -1 })
            put("learning_duplicate_suppressions", try { TradeOutcomeLedger.learningDuplicateSuppressions() } catch (_: Throwable) { -1 })
            put("unique_closed_positions", try { TradeOutcomeLedger.uniqueClosedPositionCount() } catch (_: Throwable) { -1 })
            put("paper_blocked_in_live", try { ExecutionRouteGuard.paperBlockedInLiveCount() } catch (_: Throwable) { -1 })
            put("live_route_blocked", try { ExecutionRouteGuard.liveBlockedCount() } catch (_: Throwable) { -1 })
            put("shadow_route_allowed", try { ExecutionRouteGuard.shadowAllowedCount() } catch (_: Throwable) { -1 })
            put("paper_route_allowed", try { ExecutionRouteGuard.paperAllowedCount() } catch (_: Throwable) { -1 })
            put("live_route_allowed", try { ExecutionRouteGuard.liveAllowedCount() } catch (_: Throwable) { -1 })
        })

        // Reconciler section
        val recon = PositionWalletReconciler.snapshot()
        val reconJson = JSONObject().apply {
            put("totalChecked", recon.totalChecked)
            put("healthy",      recon.healthy)
            put("phantoms",     recon.phantoms)
            put("noMint",       recon.noMint)
            put("grace",        recon.grace)
            put("inconclusive", recon.inconclusive)
            put("tickAtMs",     recon.tickAtMs)
            val pf = JSONArray()
            for (f in recon.phantomFindings) {
                pf.put(JSONObject().apply {
                    put("laneTag", f.laneTag)
                    put("symbol", f.symbol)
                    put("mint", f.mint ?: "")
                    put("walletUiAmount", f.walletUiAmount)
                    put("expectedUiAmount", f.expectedUiAmount)
                    put("verdict", f.verdict.name)
                    put("ageMs", f.ageMs)
                })
            }
            put("phantomFindings", pf)
        }
        root.put("reconciler", reconJson)

        // V5.9.774 — EMERGENT MEME: surface SellReconciler stats in the
        // forensic export. Operator's last forensic dump showed
        // `reconciler.totalChecked = 0` and concluded the reconciler
        // wasn't running — but the triage agent traced
        // `SellReconciler.tick()` to a 10 s coroutine loop launched
        // from BotService that IS running. The above
        // `PositionWalletReconciler` snapshot is a DIFFERENT (phantom-
        // detection) reconciler and its totalChecked is correctly 0
        // when no live positions exist. The sell-side reconciler stats
        // were simply never exported. This adds a `sell_reconciler`
        // section so the operator can verify totalTicks > 0,
        // totalChecked > 0 and lastTickAtMs is recent on every dump.
        val sellReconJson = JSONObject().apply {
            put("totalTicks",   com.lifecyclebot.engine.sell.SellReconciler.totalTicks)
            put("totalChecked", com.lifecyclebot.engine.sell.SellReconciler.totalChecked)
            put("lastTickAtMs", com.lifecyclebot.engine.sell.SellReconciler.lastTickAtMs)
            put("isStarted",    runtime.sellReconcilerStarted || com.lifecyclebot.engine.sell.SellReconciler.isStarted)
            put("runtimeGeneration", runtime.runtimeGeneration)
            put("activeJobs",   com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size)
        }
        root.put("sell_reconciler", sellReconJson)

        // HostWalletTokenTracker section
        val trackerArr = JSONArray()
        try {
            for (p in HostWalletTokenTracker.snapshot()) {
                trackerArr.put(JSONObject().apply {
                    put("intended_symbol", p.symbol ?: "")
                    put("stored_mint",     p.mint)
                    put("wallet_uiAmount", p.uiAmount)
                    put("last_buy_signature", p.buySignature ?: "")
                    put("last_sell_signature", p.sellSignature ?: "")
                    put("status",          p.status.name)
                    put("source",          p.source.name)
                    put("buyTimeMs",       p.buyTimeMs ?: 0L)
                    put("entryPriceUsd",   p.entryPriceUsd ?: 0.0)
                    put("entrySol",        p.entrySol ?: 0.0)
                    put("currentPriceUsd", p.currentPriceUsd ?: 0.0)
                    put("maxGainPct",      p.maxGainPct)
                    put("maxDrawdownPct",  p.maxDrawdownPct)
                    put("venue",           p.venue ?: "")
                })
            }
        } catch (_: Throwable) {}
        root.put("host_tracker", JSONObject().apply {
            put("open_count", try { HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 })
            put("runtime_open_count", runtime.hostTrackerOpenCount)
            put("paper_open_positions", runtimeSnapshot?.paperOpenPositions ?: -1)
            put("live_open_positions", runtimeSnapshot?.liveOpenPositions ?: -1)
            put("wallet_held_mints", runtimeSnapshot?.walletHeldMints ?: -1)
            put("canonical_open_positions", runtimeSnapshot?.canonicalOpenPositions ?: -1)
            put("positions", trackerArr)
        })

        // Forensics ring buffer
        val forensicsArr = JSONArray()
        try {
            if (Forensics.size() == 0 && ForensicLogger.enabled) {
                Forensics.log(Forensics.Event.RUNTIME_EVENT, "", "runtimeGeneration=${runtime.runtimeGeneration} state=${runtime.state.name} exporter_fallback=true")
            }
            for (e in Forensics.recent(limit = 200)) {
                forensicsArr.put(JSONObject().apply {
                    put("ts", e.ts)
                    put("event", e.event.name)
                    put("mintShort", e.mintShort)
                    put("msg", e.msg)
                })
            }
        } catch (_: Throwable) {}
        root.put("forensics_events", forensicsArr)

        // Trade-log groups (latest 80 trade lifecycles)
        val groupsArr = JSONArray()
        try {
            val groups = LiveTradeLogStore.groupedByTrade().take(80)
            for (g in groups) {
                val evs = JSONArray()
                for (e in g.events) {
                    evs.put(JSONObject().apply {
                        put("ts", e.ts)
                        put("phase", e.phase.name)
                        put("side", e.side)
                        put("msg", e.message)
                        e.sig?.let { put("sig", it) }
                        e.solAmount?.let { put("sol", it) }
                        e.tokenAmount?.let { put("qty", it) }
                        e.priceUsd?.let { put("px", it) }
                    })
                }
                groupsArr.put(JSONObject().apply {
                    put("tradeKey", g.tradeKey)
                    put("mint", g.mint)
                    put("symbol", g.symbol)
                    put("traderTag", g.traderTag)
                    put("firstTs", g.firstTs)
                    put("lastTs", g.lastTs)
                    put("latestPhase", g.latestPhase.name)
                    put("latestSide", g.latestSide)
                    put("events", evs)
                })
            }
        } catch (_: Throwable) {}
        root.put("trade_log_groups", groupsArr)

        return root
    }
}
