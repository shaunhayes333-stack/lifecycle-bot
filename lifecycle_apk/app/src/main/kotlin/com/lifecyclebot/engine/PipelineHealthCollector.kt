package com.lifecyclebot.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.915–V5.9.915 — PipelineHealthCollector
 *
 * In-app mirror of the CI runtime-test funnel summary, plus an
 * accurate main-thread watchdog ANR sampler and a deep ring buffer
 * of recent forensic events.
 *
 * V5.9.915 changelog:
 *   - Real watchdog-based ANR sampler: pings main thread Handler
 *     every 250ms; if no ACK in 700ms+ samples mainThread.stackTrace
 *     WHILE STILL BLOCKED. Previous Choreographer-based sampler
 *     captured the stack AFTER the freeze ended — always showed
 *     captureMainThreadStack itself, useless.
 *   - Deep operator diagnostics: per-source intake counters, per-
 *     lane eval counters, per-block-reason histogram, top symbols
 *     seen, top blocked reasons, bot-loop cycle timing (last + max
 *     + avg), recent executions ring, ANR stack frequency (so the
 *     same blocking call is grouped instead of spammed).
 *   - Snapshot dump expanded to surface every counter for clipboard
 *     export.
 *
 * Wiring strategy (unchanged):
 *   - ForensicLogger.phase / gate / decision / exec / lifecycle each
 *     fan into PipelineHealthCollector.* hooks.
 *   - TradeHistoryStore -> onTradeJournal()
 *   - WalletPositionLock + CryptoUniverseExecutor -> event(...)
 *   - BotService.onCreate -> installAnrWatcherOnMainThread()
 *
 * Zero overhead when forensic logging is enabled (we just bump
 * atomic counters). Read paths take a snapshot using lock-free
 * concurrent collections.
 */
object PipelineHealthCollector {

    // ════════════════════════════════════════════════════════════════
    // Counters — atomic, non-blocking, monotonically increasing.
    // ════════════════════════════════════════════════════════════════

    /** Phase counters keyed by phase tag (e.g. "INTAKE", "SAFETY", "V3"). */
    private val phaseCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Phase ALLOW (✅ gate) counts. */
    private val phaseAllow = ConcurrentHashMap<String, AtomicLong>()

    /** Phase BLOCK (🚫 gate) counts. */
    private val phaseBlock = ConcurrentHashMap<String, AtomicLong>()

    /** Decision verdict tallies (e.g. EXECUTE_AGGRESSIVE, REJECT, WATCH). */
    private val verdictCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Custom labelled counters (e.g. lane name, error class). */
    private val labelCounts = ConcurrentHashMap<String, AtomicLong>()
    // V5.9.1378 (P0 #9) — per-lane MFE aggregation: [peakSumX100, realizedSumX100, n].
    // Lets the snapshot show avg peak vs avg realized give-back per lane so the exit
    // ladder can be tuned against "runners getting cut".
    private val mfeByLane = ConcurrentHashMap<String, LongArray>()

    // V5.9.1082 — last-seen EXECUTION_STATE_BLOCKED for the snapshot
    // top-bar EXECUTION_STATE field. Operator must SEE buying is paused.
    @Volatile private var lastExecutionStateBlockedMs: Long = 0L
    @Volatile private var lastExecutionStateBlockedFields: String = ""

    /** V5.9.915 — per-source intake counters (PUMP_PORTAL_WS / RAYDIUM_NEW_POOL / etc.). */
    private val intakeBySource = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.915 — per-lane LANE_EVAL counters (SHITCOIN / MOONSHOT / QUALITY / BLUECHIP). */
    private val laneEvalCounts = ConcurrentHashMap<String, AtomicLong>()
    private val laneEvalSuppressedCounts = ConcurrentHashMap<String, AtomicLong>()
    private val fdgPathCounts = ConcurrentHashMap<String, AtomicLong>()
    private val fdgSuppressedPathCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.915 — block-reason histogram across all gate types (top key on dump). */
    private val blockReasonCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.0.3894 — reason histogram for LIVE_BUY_FAIL. BUY ok/fail alone hides
     *  whether current live trading is choked by quote, route, wallet/rent, safety,
     *  duplicate, mutex, simulation, or finality. Built from ForensicLogger.exec
     *  fields so direct emitters that bypass Executor.emitLiveBuyFail still count. */
    private val liveBuyFailReasonCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.1046 — V3 REJECTED_FATAL reason histogram. The bare lifecycle
     *  counter shows '132 rejects' but operator can't tell what the
     *  dominant cause is. This histogram surfaces the normalised reason
     *  key extracted from each REJECTED_FATAL_V3 event so the operator
     *  can prioritise which V3 sub-gate to tune. */
    private val v3RejectReasonCounts = ConcurrentHashMap<String, AtomicLong>()

    // ════════════════════════════════════════════════════════════════
    // V5.9.915 — Per-mode FDG / EXEC counters
    //
    // Base44 ticket asked for FDG_LIVE_ALLOW / FDG_LIVE_BLOCK /
    // FDG_PAPER_ALLOW / FDG_PAPER_BLOCK and EXEC_LIVE_ATTEMPT /
    // EXEC_LIVE_BUY_OK / EXEC_LIVE_BUY_FAIL / EXEC_PAPER_BUY_OK so that
    // forensic snapshots can disambiguate "FDG allow=0 block=174" —
    // is the bot in paper mode (expected), or is live actively being
    // blocked (regression)?
    //
    // Implementation: don't touch the 7 FDG emit sites in BotService.
    // Instead, snapshot the current paperMode flag on a @Volatile field
    // (cheap, one write per cycle from BotService.processTokenCycle)
    // and have onGate/onExec read it locally to bump the right counter.
    // Zero behaviour change. Pure observability.
    // ════════════════════════════════════════════════════════════════

    @Volatile var modeSnapshot: String = "UNKNOWN"

    fun resetModeCountersForRuntime(mode: String) {
        modeSnapshot = mode
        fdgLiveAllow.set(0L)
        fdgLiveBlock.set(0L)
        fdgPaperAllow.set(0L)
        fdgPaperBlock.set(0L)
        execLiveAttempt.set(0L)
        execLiveBuyOk.set(0L)
        execLiveBuyFail.set(0L)
        execLiveSellOk.set(0L)
        execLiveSellFail.set(0L)
        execPaperBuyOk.set(0L)
        execPaperSellOk.set(0L)
        execPaperPartialOk.set(0L)
        liveBuyFailReasonCounts.clear()
        // V5.0.3810 — reset journal-derived paper labels with paper OK atomics;
        // otherwise reports can show old PAPER_JOURNAL_ROWS with freshly-zero OK counters.
        listOf("PAPER_JOURNAL_ROWS", "TRADEJRNL_REC_PAPER", "TRADEJRNL_REC_LIVE", "TRADEJRNL_REC_PARTIAL", "PAPER_LEARNING_ROW_QUARANTINED").forEach { labelCounts.remove(it) }
        try { ForensicLogger.lifecycle("MODE_COUNTERS_RESET", "mode=$mode") } catch (_: Throwable) {}
    }

    private val fdgLiveAllow   = AtomicLong(0L)
    private val fdgLiveBlock   = AtomicLong(0L)
    private val fdgPaperAllow  = AtomicLong(0L)
    private val fdgPaperBlock  = AtomicLong(0L)

    private val execLiveAttempt  = AtomicLong(0L)
    private val execLiveBuyOk    = AtomicLong(0L)
    private val execLiveBuyFail  = AtomicLong(0L)

    // V5.9.1355 P0.5 — public getters for LIVE_MODE_TRANSFER_AUDIT.
    fun fdgLiveAllowCount(): Long = fdgLiveAllow.get()
    fun fdgLiveBlockCount(): Long = fdgLiveBlock.get()
    fun execLiveAttemptCount(): Long = execLiveAttempt.get()
    fun execLiveBuyOkCount(): Long = execLiveBuyOk.get()
    fun execLiveSellOkCount(): Long = execLiveSellOk.get()
    private val execLiveSellOk   = AtomicLong(0L)
    private val execLiveSellFail = AtomicLong(0L)
    private val execPaperBuyOk   = AtomicLong(0L)
    private val execPaperSellOk  = AtomicLong(0L)
    private val execPaperPartialOk = AtomicLong(0L)

    /** V5.9.998 — operator triage: cache the PerformanceAnalytics block.
     *  db.getAllTrades() + analyze() takes 100-500 ms on a hot DB; we don't
     *  need real-time analytics in the pipeline dump. 30-s stale is fine. */
    @Volatile private var perfAnalyticsCache: String? = null
    @Volatile private var perfAnalyticsCacheAt: Long = 0L

    // V5.0.3843 — report mux authority: PerformanceAnalytics must consume the
    // same canonical journal rows as Journal Summary / TradeHistoryStore stats.
    // The legacy TradeDatabase path defaulted many modes to RANGE and could read
    // raw legacy accounting rows, producing contradictions like canonical WR=42.7%
    // beside PerformanceAnalytics WR=9.2% / RANGE-only.
    private fun canonicalPerformanceTrades(limit: Int = 1000): List<TradeRecord> {
        fun sellLike(side: String): Boolean = side.equals("SELL", true) || side.equals("PARTIAL_SELL", true)
        fun isWinPct(p: Double): Boolean = p >= 0.5
        fun isLossPct(p: Double): Boolean = p <= -2.0
        return try {
            com.lifecyclebot.engine.TradeHistoryStore.getRecentValidClosedTrades(limit.coerceAtLeast(1), includePartials = true)
                .asSequence()
                .filter { sellLike(it.side) }
                .map { t ->
                    val entryTs = t.entryTsMs.takeIf { it > 0L } ?: t.ts
                    val entryPrice = t.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 } ?: t.price
                    val solIn = t.entryCostSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                    val pnl = t.netPnlSol.takeIf { it.isFinite() && it != 0.0 } ?: t.pnlSol
                    TradeRecord(
                        tsEntry = entryTs,
                        tsExit = t.ts,
                        symbol = t.sig.ifBlank { t.mint.take(8) },
                        mint = t.mint,
                        mode = t.tradingMode.ifBlank { "STANDARD" },
                        entryPrice = entryPrice,
                        entryScore = t.score,
                        exitPrice = t.price,
                        exitReason = t.reason,
                        heldMins = ((t.ts - entryTs).coerceAtLeast(0L) / 60_000.0),
                        solIn = solIn,
                        solOut = solIn + pnl,
                        pnlSol = pnl,
                        pnlPct = t.pnlPct,
                        isWin = when {
                            isWinPct(t.pnlPct) -> true
                            isLossPct(t.pnlPct) -> false
                            else -> null
                        },
                        isScratch = !isWinPct(t.pnlPct) && !isLossPct(t.pnlPct),
                        source = t.entryPriceSource.ifBlank { t.proofState },
                    )
                }
                .toList()
                .asReversed()
        } catch (_: Throwable) { emptyList() }
    }

    /** V5.9.915 — per-symbol intake counter (top-10 surfaced in dump). */
    private val symbolIntakeCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.915 — ANR stack frequency. Grouping by top frame so a 10s freeze
     *  on the same call site is one entry, not 40 separate ANR_HINT lines. */
    private val anrStackCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.915 — bot-loop cycle timing samples (most recent N). */
    private val recentCycleMsSamples = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private val recentCycleMsSize = AtomicInteger(0)
    private const val CYCLE_SAMPLE_CAP = 50
    private val totalCycleMs = AtomicLong(0L)
    private val maxCycleMs = AtomicLong(0L)
    private val cycleCount = AtomicLong(0L)

    /** V5.9.915 — recent executions ring (BUY/SELL with size + mode + reason). */
    data class ExecRecord(
        val tsMs: Long,
        val side: String,
        val mode: String,
        val symbol: String,
        val sizeSol: Double,
        val pnlSol: Double,
        val reason: String,
        val proofState: String = "",
        val positionId: String = "",
        val lane: String = "",
        val entryPriceSnapshot: Double = 0.0,
        val entryMcapUsd: Double = 0.0,
        val entryQtyToken: Double = 0.0,
        val entryCostSol: Double = 0.0,
        val entryPriceSource: String = "",
    )
    private val recentExecs = java.util.concurrent.ConcurrentLinkedDeque<ExecRecord>()
    private val recentExecsSize = AtomicInteger(0)
    private const val EXEC_RING_CAP = 30

    /** ANR-style hint counter (watchdog long-frame events). */
    private val anrHintCount = AtomicInteger(0)

    /** Long-frame max delta seen (ms). */
    private val maxFrameGapMs = AtomicLong(0L)

    /** Total long-frame ms accumulated (for "time spent stuttering"). */
    private val totalFrameStallMs = AtomicLong(0L)

    /** V5.9.915 — count of sample attempts where main thread was blocked. */
    private val anrSamplesTaken = AtomicLong(0L)

    /** V5.9.915 — Pre-freeze rolling main-thread sample ring.
     *  Every watchdog tick (250ms) we capture (ts, sinceLastAckMs, topFrame)
     *  whether or not main thread is responsive. When a freeze fires, the
     *  dump now shows the last 30 samples = ~7.5s of pre-freeze history,
     *  letting the operator see what main was doing in the seconds
     *  leading up to the hang — not just its stack at the moment the
     *  watchdog noticed. Capped at STACK_RING_CAP. */
    data class StackSample(val tsMs: Long, val sinceLastAckMs: Long, val topFrame: String)
    private val stackRing = java.util.concurrent.ConcurrentLinkedDeque<StackSample>()
    private val stackRingSize = AtomicInteger(0)
    private const val STACK_RING_CAP = 30

    private fun pushStackSample(sample: StackSample) {
        stackRing.addLast(sample)
        if (stackRingSize.incrementAndGet() > STACK_RING_CAP) {
            stackRing.pollFirst()
            stackRingSize.decrementAndGet()
        }
    }

    /** Started-at epoch ms (for uptime in dump). */
    private val startedAtMs = AtomicLong(0L)

    // ════════════════════════════════════════════════════════════════
    // Event ring buffer — last N forensic events for clipboard export.
    // ════════════════════════════════════════════════════════════════

    private const val RING_CAP = 150  // V5.9.916 — reduced from 300: 16KB dumpText.setText every 2s was main-thread ANR storm

    // V5.9.1082 — operator complaint: every snapshot displayed
    // "Tag: V5.9.1078" no matter which APK was actually installed
    // (because BUILD_TAG was hardcoded). That meant the operator was
    // told "still on 1078" while running 1081+. The build/version line
    // now reads from BuildConfig.VERSION_NAME (set by Gradle to the
    // real CI build name) so the snapshot tag matches reality.
    // No const anymore — single source of truth via getBuildTag().
    private fun getBuildTag(): String = try {
        com.lifecyclebot.BuildConfig.VERSION_NAME
    } catch (_: Throwable) { "unknown" }

    data class Event(
        val tsMs: Long,
        val tag: String,
        val symbol: String,
        val message: String,
    )

    private val ring = java.util.concurrent.ConcurrentLinkedDeque<Event>()
    private val ringSize = java.util.concurrent.atomic.AtomicInteger(0)

    /** Pluggable hook so other singletons can add events without depending on this class first. */
    @Suppress("unused")
    @Volatile var attached: Boolean = true

    // ════════════════════════════════════════════════════════════════
    // Forensic hooks — called from ForensicLogger.
    // ════════════════════════════════════════════════════════════════

    private fun extractModeFromText(text: String): String {
        val m = Regex("(?:^|[\\s|])(?:mode|runtimeMode|execMode)=([A-Za-z_]+)").find(text)
            ?.groupValues?.getOrNull(1)?.uppercase()
        return when (m) {
            "LIVE" -> "LIVE"
            "PAPER" -> "PAPER"
            else -> ""
        }
    }

    private fun modeFromExec(action: String, fields: String): String = when {
        action.startsWith("LIVE_", ignoreCase = true) -> "LIVE"
        action.startsWith("PAPER_", ignoreCase = true) -> "PAPER"
        else -> extractModeFromText(fields)
    }

    private fun normalizedProofState(mode: String, proofState: String, side: String = ""): String {
        val p = proofState.trim().uppercase()
        if (p in setOf("PAPER_SIMULATED", "LIVE_BROADCAST", "LIVE_SIG_CONFIRMED", "LIVE_BALANCE_CONFIRMED", "LIVE_FINALIZED")) return p
        return when (mode.uppercase()) {
            "PAPER" -> "PAPER_SIMULATED"
            "LIVE" -> "LIVE_BROADCAST"
            else -> ""
        }
    }

    fun onPhase(phaseTag: String, symbol: String, fields: String) {
        if (!attached) return
        if (phaseTag == "LANE_EVAL") {
            val lane = Regex("lane=([A-Z_]+)").find(fields)?.groupValues?.getOrNull(1) ?: ""
            if (lane.isNotBlank() && RuntimeConfigOverlay.isLaneDisabled(lane)) {
                bump(laneEvalSuppressedCounts, RuntimeConfigOverlay.normalizeLane(lane))
                bump(labelCounts, "LANE_EVAL_SUPPRESSED_OVERLAY")
                appendEvent(Event(System.currentTimeMillis(), "PHASE/LANE_EVAL_SUPPRESSED", symbol, fields.take(220)))
                return
            }
        }
        if (phaseTag == "FDG") {
            val path = Regex("path=([A-Z_]+)").find(fields)?.groupValues?.getOrNull(1) ?: ""
            if (path.isNotBlank() && RuntimeConfigOverlay.isLaneDisabled(path)) {
                // V5.9.1110 — suppressed FDG is NOT active FDG. The 1108 leak audit
                // showed active=97 and suppressed=97 because this method bumped the
                // active path counter before checking the overlay. Count disabled paths
                // only in fdgSuppressedPathCounts so Active non-QUALITY FDG means a
                // real FinalDecisionGate call reached a disabled lane.
                bump(fdgSuppressedPathCounts, RuntimeConfigOverlay.normalizeLane(path))
                bump(labelCounts, "FDG_SUPPRESSED_OVERLAY")
                appendEvent(Event(System.currentTimeMillis(), "PHASE/FDG_SUPPRESSED", symbol, fields.take(220)))
                return
            }
            if (path.isNotBlank()) bump(fdgPathCounts, RuntimeConfigOverlay.normalizeLane(path))
        }
        bump(phaseCounts, phaseTag)
        if (phaseTag == "SCAN_CB" && fields.contains("BOT_LOOP_TICK")) {
            bump(labelCounts, "BOT_LOOP_TICK")
            // V5.9.915 — extract prevCycleMs and record cycle timing.
            val m = Regex("prevCycleMs=(\\d+)").find(fields)
            if (m != null) {
                m.groupValues[1].toLongOrNull()?.let { recordCycleMs(it) }
            }
        }
        // V5.9.915 — per-source intake bump for INTAKE phase events.
        if (phaseTag == "INTAKE" && symbol.isNotEmpty()) {
            val srcMatch = Regex("src=([^ ]+)").find(fields)
            srcMatch?.groupValues?.get(1)?.let { bump(intakeBySource, it.take(40)) }
            bump(symbolIntakeCounts, symbol.take(20))
        }
        // V5.9.915 — per-lane eval bump for LANE_EVAL phase events.
        if (phaseTag == "LANE_EVAL") {
            val laneMatch = Regex("lane=([A-Z_]+)").find(fields)
            laneMatch?.groupValues?.get(1)?.let { bump(laneEvalCounts, it) }
        }
        appendEvent(Event(System.currentTimeMillis(), "PHASE/$phaseTag", symbol, fields.take(220)))
    }

    fun onGate(phaseTag: String, symbol: String, allow: Boolean, reason: String) {
        if (!attached) return
        if (phaseTag == "LANE_EVAL") {
            val lane = Regex("lane=([A-Z_]+)").find(reason)?.groupValues?.getOrNull(1) ?: ""
            if (lane.isNotBlank() && RuntimeConfigOverlay.isLaneDisabled(lane)) {
                bump(laneEvalSuppressedCounts, RuntimeConfigOverlay.normalizeLane(lane))
                bump(labelCounts, "LANE_EVAL_SUPPRESSED_OVERLAY")
                return
            }
        }
        if (phaseTag == "FDG") {
            if (RuntimeConfigOverlay.isTradingPaused()) {
                bump(labelCounts, "FDG_SUPPRESSED_RUNTIME_PAUSED")
                return
            }
            val path = Regex("path=([A-Z_]+)").find(reason)?.groupValues?.getOrNull(1) ?: ""
            if (path.isNotBlank() && RuntimeConfigOverlay.isLaneDisabled(path)) {
                // V5.9.1110 — disabled-lane FDG gate telemetry is suppressed-only,
                // not active FDG. Keeps QUALITY-only leak audit truthful.
                bump(fdgSuppressedPathCounts, RuntimeConfigOverlay.normalizeLane(path))
                bump(labelCounts, "FDG_SUPPRESSED_OVERLAY")
                return
            }
            if (path.isNotBlank()) bump(fdgPathCounts, RuntimeConfigOverlay.normalizeLane(path))
        }
        bump(phaseCounts, phaseTag)
        if (allow) bump(phaseAllow, phaseTag) else bump(phaseBlock, phaseTag)
        // V5.9.916 — extract lane=… from gate() reasons too so the per-lane
        // LANE_EVAL counter sees MOONSHOT/QUALITY/BLUECHIP permit-deny paths,
        // not just SHITCOIN's unconditional phase() emit. The "only SHITCOIN
        // visible" bug in V5.9.915 dumps was forensics-only — every lane was
        // actually evaluating, but only SHITCOIN emitted via phase() while
        // the others emitted via gate() and slipped past the parser.
        if (phaseTag == "LANE_EVAL") {
            val laneMatch = Regex("lane=([A-Z_]+)").find(reason)
            laneMatch?.groupValues?.get(1)?.let { bump(laneEvalCounts, it) }
        }
        if (!allow) {
            // V5.9.915 — block-reason histogram. Truncate to the first token of
            // the reason so we group "EXCEPTION cls=Foo" across distinct messages.
            val reasonKey = reason.substringBefore(' ').take(40).ifEmpty { "unspecified" }
            bump(blockReasonCounts, "$phaseTag/$reasonKey")
        }
        // V5.9.915 — per-mode FDG counters. Other phases don't get this
        // split because only FDG is the live-money gate; SAFETY/V3/EXIT
        // run identically in paper and live so the unified counter is
        // already correct for them.
        if (phaseTag == "FDG") {
            // V5.0.3789 operator fault #5: every FDG decision MUST land in a
            // per-mode counter. Previously, if modeSnapshot was still UNKNOWN
            // (not yet stamped by runtime start), FDG allow=25 produced
            // FDG_LIVE_ALLOW=0 / FDG_LIVE_BLOCK=0 — invalid telemetry. Fall back
            // to the canonical RuntimeModeAuthority so the split is never lost.
            // V5.0.3797 — mixed-mode telemetry fix: derive FDG attribution from
            // the event's own mode= field, not the UI/current snapshot at copy time.
            val eventMode = extractModeFromText(reason)
            val effMode = when (eventMode) {
                "LIVE", "PAPER" -> eventMode
                else -> when (modeSnapshot) {
                    "LIVE", "PAPER" -> modeSnapshot
                    else -> try {
                        if (com.lifecyclebot.engine.RuntimeModeAuthority.isLive()) "LIVE" else "PAPER"
                    } catch (_: Throwable) { "UNKNOWN" }
                }
            }
            when (effMode) {
                "LIVE"  -> if (allow) fdgLiveAllow.incrementAndGet()  else fdgLiveBlock.incrementAndGet()
                "PAPER" -> if (allow) fdgPaperAllow.incrementAndGet() else fdgPaperBlock.incrementAndGet()
                else    -> bump(labelCounts, "FDG_MODE_UNKNOWN_AT_DECISION")
            }
        }
        appendEvent(Event(
            System.currentTimeMillis(),
            if (allow) "GATE_ALLOW/$phaseTag" else "GATE_BLOCK/$phaseTag",
            symbol,
            reason.take(220),
        ))
    }

    fun onDecision(phaseTag: String, symbol: String, verdict: String, score: Int, conf: Int, reason: String) {
        if (!attached) return
        bump(phaseCounts, phaseTag)
        bump(verdictCounts, verdict)
        appendEvent(Event(
            System.currentTimeMillis(),
            "DEC/$phaseTag/$verdict",
            symbol,
            "score=$score conf=$conf $reason".take(220),
        ))
    }

    private fun liveBuyFailReason(fields: String): String {
        val raw = fields.substringAfter("reason=", "").trim()
        val token = raw.takeWhile { !it.isWhitespace() }.ifBlank { "UNKNOWN" }
        return token.take(96).replace('/', '_').replace('\n', '_').ifBlank { "UNKNOWN" }
    }

    fun onExec(action: String, symbol: String, fields: String) {
        if (!attached) return
        bump(phaseCounts, "EXEC")
        bump(labelCounts, "EXEC/$action")
        // V5.9.915 — per-mode EXEC outcome counters. Action strings come
        // from the existing ForensicLogger.exec call sites:
        //   "PAPER_BUY", "PAPER_SELL", "LIVE_BUY_ATTEMPT",
        //   "LIVE_BUY_OK", "LIVE_BUY_FAIL", "LIVE_SELL_OK",
        //   "LIVE_SELL_FAIL" (and similar). We pattern-match the
        //   prefix/suffix instead of an enum so new action labels added
        //   in the future are automatically classified.
        // ORDER MATTERS: longer / more specific prefixes first so a generic
        // "LIVE_BUY" doesn't shadow "LIVE_BUY_OK" / "LIVE_BUY_FAIL" once
        // those finer-grained actions are wired by callers.
        val eventMode = modeFromExec(action, fields)
        when {
            action.startsWith("LIVE_BUY_OK")      -> execLiveBuyOk.incrementAndGet()
            action.startsWith("LIVE_BUY_FAIL")    -> {
                execLiveBuyFail.incrementAndGet()
                bump(liveBuyFailReasonCounts, liveBuyFailReason(fields))
            }
            action.startsWith("LIVE_BUY_ATTEMPT") -> execLiveAttempt.incrementAndGet()
            action.startsWith("LIVE_BUY")         -> execLiveAttempt.incrementAndGet()  // existing emit site fires this at attempt time
            action.startsWith("LIVE_SELL_OK")     -> execLiveSellOk.incrementAndGet()
            action.startsWith("LIVE_SELL_FAIL")   -> execLiveSellFail.incrementAndGet()
            // PAPER OK counters are journal-attributed in recordExec(); PAPER_BUY/PAPER_SELL
            // exec labels are attempts and must not inflate successful journal rows.
            eventMode == "LIVE" && action.contains("BUY", ignoreCase = true) -> execLiveAttempt.incrementAndGet()
        }
        appendEvent(Event(System.currentTimeMillis(), "EXEC/$action", symbol, fields.take(220)))
    }

    fun onLifecycle(event: String, fields: String) {
        if (!attached) return
        bump(labelCounts, "LIFECYCLE/$event")
        // V5.9.1082 — track the most recent EXECUTION_STATE_BLOCKED so the
        // snapshot top-bar can render an explicit EXECUTION_STATE field. The
        // operator must SEE the bot is paused buying via circuit breaker
        // rather than silently watch the loop tick with no trades.
        if (event == "EXECUTION_STATE_BLOCKED") {
            lastExecutionStateBlockedMs = System.currentTimeMillis()
            lastExecutionStateBlockedFields = fields.take(220)
        }
        // V5.0.3797 — mixed-mode proof attribution. Some live execution/finality
        // evidence is emitted as LIFECYCLE labels, not EXEC labels. Count those
        // from the event authority itself so a PAPER UI snapshot cannot hide live
        // execution in the per-mode block.
        when (event) {
            "MEME_LIVE_EXEC_ENTRY" -> execLiveAttempt.incrementAndGet()
            "LIVE_BUY_LANDED", "BUY_CONFIRMED", "LIVE_POSITION_CONFIRMED_FROM_WALLET", "LIVE_POSITION_CONFIRMED_FROM_SIGNATURE" -> execLiveBuyOk.incrementAndGet()
            "SELL_FINALIZED_ONCE", "SELL_FINALIZED", "EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED", "SELL_SIG_CONFIRMED" -> execLiveSellOk.incrementAndGet()
        }
        // V5.9.1046 — V3 reject reason histogram. Extract the normalised
        // V3 reason key from fields like 'mint=… sym=… v3=Rejected
        // reason=V3:RUG_FATAL:TOP_HOLDER' and bump a separate counter.
        // Falls back to v3Decision class name when reason is '(none)'.
        if (event == "REJECTED_FATAL_V3") {
            try {
                val raw = Regex("reason=([^ ]+)").find(fields)?.groupValues?.get(1)
                val cls = Regex("v3=([^ ]+)").find(fields)?.groupValues?.get(1) ?: "Unknown"
                val keyRaw = if (raw.isNullOrBlank() || raw == "(none)") cls else raw
                // Normalise: keep only the first 2 colon-segments so
                // 'V3:RUG_FATAL:HOLDER_X' collapses with 'V3:RUG_FATAL:HOLDER_Y'.
                val parts = keyRaw.split(":")
                val key = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else keyRaw
                bump(v3RejectReasonCounts, key.take(80))
            } catch (_: Throwable) {}
        }
        appendEvent(Event(System.currentTimeMillis(), "LIFECYCLE/$event", "", fields.take(220)))
    }

    fun onSnapshot(label: String, fields: String) {
        if (!attached) return
        bump(labelCounts, "SNAP/$label")
        // V5.9.915 — also record cycle timing if this is a LOOP_TOP snapshot.
        if (label == "LOOP_TOP" || label == "BOT_LOOP_TICK") {
            val m = Regex("prevCycleMs=(\\d+)").find(fields)
            if (m != null) m.groupValues[1].toLongOrNull()?.let { recordCycleMs(it) }
        }
        appendEvent(Event(System.currentTimeMillis(), "SNAP/$label", "", fields.take(220)))
    }

    /** Generic event injection point for callers outside ForensicLogger. */
    fun event(tag: String, symbol: String, message: String) {
        if (!attached) return
        bump(labelCounts, tag)
        appendEvent(Event(System.currentTimeMillis(), tag, symbol, message.take(220)))
    }

    /** Hook for TradeHistoryStore — tracked separately because CI funnel cares about it. */
    fun onTradeJournal() {
        if (!attached) return
        bump(labelCounts, "TRADEJRNL_REC")
    }

    /**
     * V5.9.915 — public hook for executions so the dump can surface the last
     * 30 trades right alongside the funnel counts. Called from TradeHistoryStore
     * after each successful journal write.
     */
    fun recordExec(
        side: String,
        mode: String,
        symbol: String,
        sizeSol: Double,
        pnlSol: Double,
        reason: String,
        proofState: String = "",
        positionId: String = "",
        lane: String = "",
        entryPriceSnapshot: Double = 0.0,
        entryMcapUsd: Double = 0.0,
        entryQtyToken: Double = 0.0,
        entryCostSol: Double = 0.0,
        entryPriceSource: String = "",
    ) {
        if (!attached) return
        val eventMode = when (mode.trim().uppercase()) {
            "LIVE" -> "LIVE"
            "PAPER" -> "PAPER"
            else -> extractModeFromText(reason).ifBlank { mode.trim().uppercase().ifBlank { "UNKNOWN" } }
        }
        val proof = normalizedProofState(eventMode, proofState, side)
        val sideUpper = side.uppercase()
        bump(labelCounts, "EXEC_$sideUpper")
        bump(labelCounts, "EXEC_${eventMode}_$sideUpper")
        bump(labelCounts, "TRADEJRNL_REC_$eventMode")
        if (eventMode == "PAPER") bump(labelCounts, "PAPER_JOURNAL_ROWS")
        if (sideUpper == "PARTIAL_SELL") bump(labelCounts, "TRADEJRNL_REC_PARTIAL")
        if (eventMode == "PAPER") {
            when (sideUpper) {
                "BUY" -> { execPaperBuyOk.incrementAndGet(); bump(labelCounts, "PAPER_COUNTER_SIDE_MAPPED") }
                "SELL" -> { execPaperSellOk.incrementAndGet(); bump(labelCounts, "PAPER_COUNTER_SIDE_MAPPED") }
                "PARTIAL_SELL" -> { execPaperPartialOk.incrementAndGet(); bump(labelCounts, "PAPER_COUNTER_SIDE_MAPPED") }
                else -> bump(labelCounts, "PAPER_COUNTER_SKIPPED_UNKNOWN_SIDE")
            }
            bump(labelCounts, "PAPER_COUNTER_INCREMENTED_FROM_JOURNAL")
        }
        if (proof.isNotBlank()) bump(labelCounts, "PROOF_$proof")
        if (positionId.isNotBlank()) bump(labelCounts, "TRADEJRNL_POSITION_LINKED")
        if (entryPriceSnapshot > 0.0) bump(labelCounts, "TRADEJRNL_ENTRY_SNAPSHOT_VISIBLE")
        recentExecs.addLast(ExecRecord(
            tsMs = System.currentTimeMillis(),
            side = side,
            mode = eventMode,
            symbol = symbol,
            sizeSol = sizeSol,
            pnlSol = pnlSol,
            reason = reason,
            proofState = proof,
            positionId = positionId,
            lane = lane,
            entryPriceSnapshot = entryPriceSnapshot,
            entryMcapUsd = entryMcapUsd,
            entryQtyToken = entryQtyToken,
            entryCostSol = entryCostSol,
            entryPriceSource = entryPriceSource,
        ))
        if (recentExecsSize.incrementAndGet() > EXEC_RING_CAP) {
            recentExecs.pollFirst()
            recentExecsSize.decrementAndGet()
        }
    }

    private fun recordCycleMs(ms: Long) {
        if (ms <= 0) return
        recentCycleMsSamples.addLast(ms)
        if (recentCycleMsSize.incrementAndGet() > CYCLE_SAMPLE_CAP) {
            recentCycleMsSamples.pollFirst()
            recentCycleMsSize.decrementAndGet()
        }
        totalCycleMs.addAndGet(ms)
        cycleCount.incrementAndGet()
        var prev = maxCycleMs.get()
        while (ms > prev && !maxCycleMs.compareAndSet(prev, ms)) { prev = maxCycleMs.get() }
    }

    // ════════════════════════════════════════════════════════════════
    // ANR detector — V5.9.915 watchdog-thread sampler.
    //   Choreographer.FrameCallback (legacy) still measures durations.
    //   New: a 250ms-cadence watchdog thread that pings the main-thread
    //   Handler. If a ping isn't ACKed in > LONG_FRAME_THRESHOLD_MS,
    //   we sample mainThread.stackTrace WHILE STILL BLOCKED — that
    //   shows the actual blocking call site rather than the post-hoc
    //   captureMainThreadStack frames we used to see.
    // ════════════════════════════════════════════════════════════════

    private const val LONG_FRAME_THRESHOLD_MS = 700L  // > 700ms gap = "the bot froze"
    private const val WATCHDOG_INTERVAL_MS    = 250L
    // V5.9.915 — operator audit: ignore Choreographer gaps > 60s as
    // they are virtually certain to be screen-off / Doze / background
    // gaps rather than legitimate UI stalls. ActivityManager force-
    // closes a foreground app long before this for genuine hangs.
    private const val MAX_REAL_STALL_MS       = 60_000L
    @Volatile private var anrInstalled = false
    private var lastFrameNs: Long = 0L
    @Volatile private var watchdogThread: HandlerThread? = null
    @Volatile private var mainHandler: Handler? = null

    /**
     * Install the main-thread frame monitor + watchdog. Idempotent;
     * safe to call from BotService.onCreate OR from the activity's
     * onCreate (whichever fires first wins). Must be called on the
     * main thread.
     */
    fun installAnrWatcherOnMainThread() {
        if (anrInstalled) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        anrInstalled = true
        if (startedAtMs.get() == 0L) startedAtMs.set(System.currentTimeMillis())

        val mainThread = Looper.getMainLooper().thread
        mainHandler = Handler(Looper.getMainLooper())

        // Choreographer still records duration of long frames (cheap).
        val choreo = try { Choreographer.getInstance() } catch (_: Throwable) { null }
        choreo?.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val prev = lastFrameNs
                lastFrameNs = frameTimeNanos
                if (prev != 0L) {
                    val deltaMs = (frameTimeNanos - prev) / 1_000_000L
                    // V5.9.915 — operator audit (build-2733 dump showed
                    // maxFrameGapMs=229,816ms / stall=40% which was a
                    // FALSE READING. The Choreographer callback simply
                    // stops firing when the screen is off / app is in
                    // Doze / background, then resumes — yielding a delta
                    // that's the entire screen-off duration. We were
                    // accounting for that as a "main thread stall".
                    //
                    // Guard: any gap > MAX_REAL_STALL_MS (60s) is almost
                    // certainly screen-off / Doze. Skip accumulation so
                    // the stall % stays honest. Anything real (a JS-style
                    // UI freeze) caps at ~30s before ActivityManager kills
                    // the process — 60s is comfortably above the worst
                    // legitimate stall a foreground app can survive.
                    if (deltaMs > LONG_FRAME_THRESHOLD_MS && deltaMs <= MAX_REAL_STALL_MS) {
                        anrHintCount.incrementAndGet()
                        totalFrameStallMs.addAndGet(deltaMs)
                        var prevMax = maxFrameGapMs.get()
                        while (deltaMs > prevMax && !maxFrameGapMs.compareAndSet(prevMax, deltaMs)) {
                            prevMax = maxFrameGapMs.get()
                        }
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })

        // V5.9.915 — watchdog thread that samples mainThread WHILE blocked.
        val hThread = HandlerThread("ANR_Watchdog", Thread.MIN_PRIORITY).apply { start() }
        watchdogThread = hThread
        val watchdogHandler = Handler(hThread.looper)
        val ackTs = AtomicLong(SystemClock.elapsedRealtime())
        val mainPing = Runnable { ackTs.set(SystemClock.elapsedRealtime()) }

        watchdogHandler.post(object : Runnable {
            // De-dup so a 10s freeze on the same call only emits a single ANR_HINT
            // (followed by aggregated count) instead of 40 lines.
            private var lastReportedKey: String = ""
            private var lastReportedAtMs: Long = 0L

            override fun run() {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val gap = now - ackTs.get()

                    // V5.9.915 — sample main-thread top frame EVERY tick (not just
                    // on freeze) and push to the rolling ring. Cheap (~1ms),
                    // gives us 7.5s of pre-freeze history when a hang fires.
                    val tickTrace = try {
                        captureMainThreadStack(mainThread)
                    } catch (_: Throwable) { "" }
                    val tickTop = tickTrace.lineSequence()
                        .firstOrNull { it.contains("com.lifecyclebot") }?.trim()
                        ?: tickTrace.lineSequence().firstOrNull()?.trim()
                        ?: "(idle)"
                    pushStackSample(StackSample(System.currentTimeMillis(), gap, tickTop.take(160)))

                    if (gap > LONG_FRAME_THRESHOLD_MS && gap <= MAX_REAL_STALL_MS) {
                        // Main thread hasn't acked in > 700ms — reuse the tick
                        // sample we just captured rather than re-walking the
                        // stack a second time.
                        val trace = tickTrace.ifBlank { "(stack capture failed)" }
                        val topFrame = tickTop

                        // V5.9.1151 — watchdog false-positive fix.
                        // Previously, once gap crossed 700ms we stopped posting
                        // fresh pings until gap fell below threshold. If the main
                        // thread recovered but no ping was queued, ackTs stayed old
                        // and the watchdog kept emitting fake ANR_HINTs forever
                        // (nativePollOnce/Unsafe.park dominated 3117). Always post
                        // the next ping; count/log only while the current ping is
                        // genuinely late.
                        mainHandler?.post(mainPing)

                        bump(anrStackCounts, topFrame.take(120))
                        anrSamplesTaken.incrementAndGet()

                        // Throttle event emission: one ANR_HINT every 5s per
                        // unique top frame. Long freezes on the same call get
                        // grouped via the anrStackCounts counter above.
                        val emitNow = topFrame != lastReportedKey ||
                                (System.currentTimeMillis() - lastReportedAtMs) > 5_000L
                        if (emitNow) {
                            lastReportedKey = topFrame
                            lastReportedAtMs = System.currentTimeMillis()
                            val preRingSb = StringBuilder()
                            preRingSb.append("\n--- pre-freeze rolling main-thread sample (last 30) ---\n")
                            val ringSnapshot = stackRing.toList()
                            for (smp in ringSnapshot.asReversed()) {
                                preRingSb.append("  +").append(smp.sinceLastAckMs.toString().padStart(5))
                                    .append("ms  ").append(smp.topFrame).append('\n')
                            }
                            appendEvent(Event(
                                System.currentTimeMillis(),
                                "ANR_HINT",
                                "",
                                "main thread blocked for ${gap}ms — top frame: $topFrame\n$trace$preRingSb",
                            ))

                            // V5.9.1258 — auto-surface the blocking call-site to
                            // the operator (Telegram/Discord) instead of burying it
                            // in the forensic dump. Only on SEVERE stalls (>3s) so
                            // minor blips don't alert; already throttled to one fire
                            // per unique top-frame per 5s by the emitNow gate above.
                            // Fail-open: never let alerting break the watchdog.
                            if (gap > 3_000L) {
                                try {
                                    val ctx = BotService.instance?.applicationContext
                                    if (ctx != null) {
                                        val cfgNow = com.lifecyclebot.data.ConfigStore.load(ctx)
                                        TradeAlerts.onMainThreadStall(cfgNow, topFrame, gap)
                                    }
                                } catch (_: Throwable) { /* alerting must never crash the watchdog */ }
                            }
                        }
                    } else {
                        // Main has been responsive — ping for the next round.
                        mainHandler?.post(mainPing)
                    }
                } catch (_: Throwable) {
                    // Never let the watchdog crash itself.
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        })
    }

    /**
     * Capture a compact main-thread stack-trace string. Used both by
     * the watchdog (during the freeze) and the post-hoc Choreographer
     * sample (no longer relied upon for diagnostics).
     */
    private fun captureMainThreadStack(t: Thread): String {
        val frames = t.stackTrace ?: return "(no frames)"
        if (frames.isEmpty()) return "(empty stack)"
        val sb = StringBuilder()
        var emitted = 0
        for (f in frames) {
            val cls = f.className ?: ""
            // Skip our own watchdog sampler frames so we don't pollute output.
            if (cls.startsWith("com.lifecyclebot.engine.PipelineHealthCollector")) continue
            if (cls.startsWith("java.lang.Thread") && f.methodName == "getStackTrace") continue
            if (cls.startsWith("dalvik.system.VMStack")) continue
            val isOurs = cls.startsWith("com.lifecyclebot")
            if (emitted < 6 || isOurs) {
                sb.append("    at ").append(cls).append('.').append(f.methodName)
                if (f.fileName != null) {
                    sb.append('(').append(f.fileName).append(':').append(f.lineNumber).append(')')
                }
                sb.append('\n')
                emitted++
                if (emitted >= 24) break
            }
        }
        return sb.toString().trimEnd()
    }

    // ════════════════════════════════════════════════════════════════
    // Read paths — snapshot + clipboard dump.
    // ════════════════════════════════════════════════════════════════

    data class Snapshot(
        val startedAtMs: Long,
        val nowMs: Long,
        val phaseCounts: Map<String, Long>,
        val phaseAllow: Map<String, Long>,
        val phaseBlock: Map<String, Long>,
        val verdictCounts: Map<String, Long>,
        val labelCounts: Map<String, Long>,
        val intakeBySource: Map<String, Long>,
        val laneEvalCounts: Map<String, Long>,
        val laneEvalSuppressedCounts: Map<String, Long>,
        val fdgPathCounts: Map<String, Long>,
        val fdgSuppressedPathCounts: Map<String, Long>,
        val blockReasonCounts: Map<String, Long>,
        val liveBuyFailReasonCounts: Map<String, Long>,
        val v3RejectReasonCounts: Map<String, Long>,
        val symbolIntakeCounts: Map<String, Long>,
        val anrStackCounts: Map<String, Long>,
        val recentExecs: List<ExecRecord>,
        val anrHints: Int,
        val anrSamplesTaken: Long,
        val maxFrameGapMs: Long,
        val totalFrameStallMs: Long,
        val recentCycleMsSamples: List<Long>,
        val totalCycleMs: Long,
        val cycleCount: Long,
        val maxCycleMs: Long,
        val recentEvents: List<Event>,
        val stackRing: List<StackSample>,
    )

    fun anrHintCountNow(): Int = anrHintCount.get()
    fun maxFrameGapMsNow(): Long = maxFrameGapMs.get()

    fun recentEventCount(tag: String, windowMs: Long = 120_000L): Long {
        val cutoff = System.currentTimeMillis() - windowMs.coerceAtLeast(1L)
        return try { ring.count { it.tsMs >= cutoff && it.tag == tag }.toLong() } catch (_: Throwable) { 0L }
    }

    fun scannerRecentlyActive(windowMs: Long = 15_000L): Boolean {
        val cutoff = System.currentTimeMillis() - windowMs
        return try {
            ring.any { it.tsMs >= cutoff && (it.tag == "PHASE/SCAN_CB" || it.tag == "PHASE/INTAKE" || it.tag == "LIFECYCLE/SCANNER_SOURCE_DONE") }
        } catch (_: Throwable) { false }
    }

    fun snapshot(): Snapshot {
        val events = ring.toList()
        return Snapshot(
            startedAtMs            = startedAtMs.get(),
            nowMs                  = System.currentTimeMillis(),
            phaseCounts            = phaseCounts.mapValues { it.value.get() },
            phaseAllow             = phaseAllow.mapValues  { it.value.get() },
            phaseBlock             = phaseBlock.mapValues  { it.value.get() },
            verdictCounts          = verdictCounts.mapValues { it.value.get() },
            labelCounts            = labelCounts.mapValues { it.value.get() },
            intakeBySource         = intakeBySource.mapValues { it.value.get() },
            laneEvalCounts         = laneEvalCounts.mapValues { it.value.get() },
            laneEvalSuppressedCounts = laneEvalSuppressedCounts.mapValues { it.value.get() },
            fdgPathCounts          = fdgPathCounts.mapValues { it.value.get() },
            fdgSuppressedPathCounts = fdgSuppressedPathCounts.mapValues { it.value.get() },
            blockReasonCounts      = blockReasonCounts.mapValues { it.value.get() },
            liveBuyFailReasonCounts = liveBuyFailReasonCounts.mapValues { it.value.get() },
            v3RejectReasonCounts   = v3RejectReasonCounts.mapValues { it.value.get() },
            symbolIntakeCounts     = symbolIntakeCounts.mapValues { it.value.get() },
            anrStackCounts         = anrStackCounts.mapValues { it.value.get() },
            recentExecs            = recentExecs.toList(),
            anrHints               = anrHintCount.get(),
            anrSamplesTaken        = anrSamplesTaken.get(),
            maxFrameGapMs          = maxFrameGapMs.get(),
            totalFrameStallMs      = totalFrameStallMs.get(),
            recentCycleMsSamples   = recentCycleMsSamples.toList(),
            totalCycleMs           = totalCycleMs.get(),
            cycleCount             = cycleCount.get(),
            maxCycleMs             = maxCycleMs.get(),
            recentEvents           = events,
            stackRing              = stackRing.toList(),
        )
    }

    /**
     * Render the full diagnostic dump for clipboard export.
     *
     * V5.9.915 — expanded sections:
     *   - Pipeline funnel (CI parity)
     *   - Bot-loop cycle timing
     *   - Gate allow/block tally
     *   - Top block reasons (histogram)
     *   - Per-source intake (PUMP_PORTAL_WS / RAYDIUM / etc.)
     *   - Per-lane eval (SHITCOIN / MOONSHOT / QUALITY / BLUECHIP)
     *   - Decision verdicts
     *   - Top intaked symbols (Top 15)
     *   - Recent executions (last 30 BUY/SELL)
     *   - ANR / main-thread health (watchdog samples)
     *   - ANR top blocking call sites
     *   - Labelled counters
     *   - Recent forensic events
     *   - Interpretation cheat-sheet
     */
    fun dumpText(): String {
        val s = snapshot()
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val uptimeSec = if (s.startedAtMs > 0) ((s.nowMs - s.startedAtMs) / 1000L) else 0L

        fun line(label: String, value: Any?, hint: String = ""): String {
            val v = value?.toString() ?: "0"
            val h = if (hint.isNotEmpty()) "  ($hint)" else ""
            return "  ${label.padEnd(28)}$v$h"
        }

        val sb = StringBuilder(16 * 1024)
        sb.append("===== AATE Pipeline Health Snapshot =====\n")
        // V5.9.915 — version stamp at top so we never debate which build
        // is on the device. Tag is a hardcoded const bumped per release;
        // appVer comes from BuildConfig.VERSION_NAME (set by gradle to
        // "5.0.<ciBuildNumber>"). When the operator pastes a dump we can
        // immediately confirm the fixes in this dump actually exist.
        val _appVer = try {
            com.lifecyclebot.BuildConfig.VERSION_NAME
        } catch (_: Throwable) { "unknown" }
        sb.append("  Build:                 ${_appVer}  |  Tag: ${getBuildTag()}\n")
        sb.append("  Captured at:           ${df.format(Date(s.nowMs))}\n")
        sb.append("  Uptime since start:    ${uptimeSec}s\n")
        sb.append("  Forensic logging:      ${if (ForensicLogger.enabled) "ON" else "OFF"}\n")
        // V5.9.1082 — operator-spec'd EXECUTION_STATE field. Sources its
        // value from the most recent EXECUTION_STATE_BLOCKED event tracked
        // in onLifecycle(). The operator must SEE that the bot has stopped
        // buying for a specific reason, not silently watch the loop tick
        // while no trades happen.
        try {
            // V5.0.3789 — single canonical runtime authority. The Execution state
            // line MUST be derived from BotRuntimeController (the same source the
            // forensic summary reads), never an independent "ACTIVE" guess. A report
            // header could previously print ACTIVE while the runtime was STOPPED,
            // making "no buys" look like an active trading failure. Now: if the
            // runtime authority is not RUNNING, the header reflects that and the
            // report is stamped POST_STOP_SNAPSHOT so a stopped bot is not
            // diagnosed as a live execution fault.
            val rt = try { com.lifecyclebot.engine.BotRuntimeController.snapshot() } catch (_: Throwable) { null }
            val runState = rt?.state
            val blockedMs = lastExecutionStateBlockedMs
            val ageSec = if (blockedMs > 0) ((System.currentTimeMillis() - blockedMs) / 1000L) else -1L
            val state = when {
                runState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.STOPPED ->
                    "STOPPED (runtime authority) — POST_STOP_SNAPSHOT"
                runState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.STOPPING ->
                    "STOPPING (runtime authority)"
                runState == com.lifecyclebot.engine.BotRuntimeController.RuntimeState.STARTING ->
                    "STARTING (runtime authority)"
                blockedMs > 0 && ageSec in 0..120 ->
                    "CIRCUIT_BREAKER (last block ${ageSec}s ago)"
                else -> "ACTIVE"
            }
            sb.append("  Execution state:       $state\n")
            if (runState != null && runState != com.lifecyclebot.engine.BotRuntimeController.RuntimeState.RUNNING) {
                sb.append("  Runtime authority:     state=$runState active=${rt.runtimeActive} botLoop=${rt.botLoopJobActive} scanner=${rt.scannerActive}\n")
                sb.append("  POST_STOP_SNAPSHOT: bot is not RUNNING — 'no buys / no exec' below is expected, NOT a live execution fault.\n")
            }
            if (blockedMs > 0 && ageSec in 0..120) {
                sb.append("  Execution block reason: ${lastExecutionStateBlockedFields.take(160)}\n")
            }
        } catch (_: Throwable) {
            sb.append("  Execution state:       UNKNOWN (snapshot read error)\n")
        }
        // V5.9.997 — surface LiveLayerGateRelaxer (z38) state to operator.
        try {
            sb.append("  ${com.lifecyclebot.engine.LiveLayerGateRelaxer.summaryLine()}\n")
        } catch (_: Throwable) {}
        // V5.9.1324 — P2-12 surgical: Root-cause-likely banner at the top.
        // Operator §12: one section says where to look first based on the
        // dominant counter pattern.
        try {
            val anrCount = s.anrHints
            val supTimeout = s.labelCounts["SUPERVISOR_WORKER_TIMEOUT"] ?: 0L
            val supTimeoutRecent2m = recentEventCount("LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT", 120_000L)
            val v3 = com.lifecyclebot.engine.runtime.V3VerdictContract.snapshot()
            val exec = com.lifecyclebot.engine.runtime.ExecutionCounterContract.snapshot()
            val v3Unaccounted = v3.unaccounted
            val rootCauses = mutableListOf<String>()
            if (anrCount > 20) rootCauses.add("UI_MAIN_THREAD (ANR_HINTS=$anrCount)")
            if (supTimeoutRecent2m > 15) rootCauses.add("WORKER_TIMEOUT_RECENT (recent2m=$supTimeoutRecent2m cumulative=$supTimeout)")
            if (v3.entries > 0 && v3Unaccounted > 0) rootCauses.add("V3_ACCOUNTING_GAP (entries=${v3.entries} unaccounted=$v3Unaccounted)")
            val txCount = (exec["paper_sell_success"] ?: 0L) + (exec["live_sell_success"] ?: 0L)
            val journalSell = exec["journal_sell_records"] ?: 0L
            if (txCount > 0 && journalSell > txCount * 2) rootCauses.add("LEARNING_ACCOUNTING_GAP (journal_sell=$journalSell > 2x close_success=$txCount)")

            // V5.9.1378 (P0 #8) — HEALTH-DIAGNOSIS TRUTH. The pipeline can be
            // mechanically "healthy" (no ANR, no accounting gap) while the STRATEGY
            // is bleeding — the snapshot used to print "appears healthy" with a 12% WR
            // and negative P&L, which is a lying diagnosis. Add the real disease as a
            // root cause when the realized performance is sub-floor. Phase-aware: WR
            // floors differ for bootstrap (<5000 lifetime closes) vs mature.
            try {
                val perf = com.lifecyclebot.engine.PerformanceAnalytics.lastSnapshotOrNull()
                if (perf != null && perf.totalTrades >= 20) {
                    // True persisted lifetime close count (survives the 1000-row analyze cap).
                    val lifetime = try { com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats().totalSells } catch (_: Throwable) { com.lifecyclebot.engine.PerformanceAnalytics.lifetimeClosedCount() }
                    val mature = lifetime >= 5000
                    val wrFloor = if (mature) 50.0 else 20.0
                    val phaseTag = if (mature) "MATURE" else "BOOTSTRAP"
                    if (perf.winRate < wrFloor) {
                        rootCauses.add("WR_BELOW_FLOOR ($phaseTag wr=${"%.1f".format(perf.winRate)}% < floor=${wrFloor.toInt()}% n=${perf.totalTrades} lifetime=$lifetime)")
                    }
                    // Negative P&L is only a "broken strategy" verdict in the mature
                    // phase; bootstrap is expected to pay tuition.
                    if (mature && perf.totalPnlSol < 0.0) {
                        rootCauses.add("NEGATIVE_PNL_MATURE (pnl=${"%.4f".format(perf.totalPnlSol)} SOL pf=${"%.2f".format(perf.profitFactor)})")
                    }
                    // A profit factor < 1 with a meaningful sample means avg_win*WR is
                    // losing to avg_loss*(1-WR) — the bleed-curve the doctrine warns about.
                    if (perf.totalTrades >= 50 && perf.profitFactor in 0.0001..0.99) {
                        rootCauses.add("PROFIT_FACTOR_SUB1 (pf=${"%.2f".format(perf.profitFactor)} — avg_win*WR < avg_loss*(1-WR))")
                    }
                }
            } catch (_: Throwable) {}

            // V5.9.1539 — REGRESSION-GUARD OVERRIDE (operator spec): structural
            // invariant faults (LEDGER_DRIFT / RECONCILER_STALLED / ORPHAN_LIVE /
            // HOST_TRACKER_DESYNC / SELL_RECONCILER_DEAD) MUST surface as the root
            // cause and can never be masked by the NONE fallthrough.
            try {
                val faults = com.lifecyclebot.engine.RuntimeDoctor.currentFaults()
                for (f in faults) { rootCauses.add(0, ("" + f.code + " (" + f.severity + ") " + f.detail).take(120)) }
                val diag = com.lifecyclebot.engine.RuntimeDoctor.currentDiagnosis()
                if (diag != null && diag.faultCode !in setOf("NO_FAULT", "HEALTHY")) {
                    rootCauses.add(0, "${diag.faultCode}/${diag.subsystem}: ${diag.rootCause}".take(160))
                }
            } catch (_: Throwable) {}
            if (rootCauses.isEmpty()) rootCauses.add("HEALTHY — mechanics and recent performance within deterministic bands")
            sb.append("  Root cause likely:    ${rootCauses.distinct().joinToString(" | ").take(220)}\n")
        } catch (_: Throwable) {}
        sb.append("\n")

        // ── Funnel ──────────────────────────────────────────────────
        sb.append("===== Pipeline funnel (in-app mirror of CI funnel) =====\n")
        val phasesOfInterest = listOf(
            "BOT_LOOP_TICK" to "loop iterations",
            "SCAN_CB"       to "processTokenCycle invocations",
            "INTAKE"        to "watchlist intake events",
            "SAFETY"        to "safety checks",
            "V3"            to "V3 engine ticks",
            "LANE_EVAL"     to "lane evaluations",
            "FDG"           to "FinalDecisionGate decision outcomes",
            "EXEC"          to "executor invocations",
            "EXIT"          to "exit gate evaluations",
            "TRADEJRNL_REC" to "journal writes",
        )
        val fdgDecisionCount = (s.phaseAllow["FDG"] ?: 0L) + (s.phaseBlock["FDG"] ?: 0L)
        val rawFdgRows = s.phaseCounts["FDG"] ?: 0L
        phasesOfInterest.forEach { (k, hint) ->
            val v = if (k == "FDG" && fdgDecisionCount > 0L) fdgDecisionCount else (s.phaseCounts[k] ?: s.labelCounts[k] ?: 0L)
            sb.append(line("$k:", v, hint)).append('\n')
        }
        if (rawFdgRows > fdgDecisionCount && fdgDecisionCount > 0L) {
            sb.append(line("FDG_RAW_ROWS:", rawFdgRows, "forensic FDG rows; not unique evaluations")).append('\n')
        }
        sb.append('\n')

        // ── Bot loop cycle timing ───────────────────────────────────
        sb.append("===== Bot-loop cycle timing =====\n")
        val avgCycle = if (s.cycleCount > 0) s.totalCycleMs / s.cycleCount else 0L
        sb.append(line("Cycles seen:", s.cycleCount)).append('\n')
        sb.append(line("Avg cycle ms:", avgCycle)).append('\n')
        sb.append(line("Max cycle ms:", s.maxCycleMs)).append('\n')
        if (s.recentCycleMsSamples.isNotEmpty()) {
            val recent = s.recentCycleMsSamples.takeLast(10).joinToString(", ")
            sb.append("  Last 10 cycles (ms):    [$recent]\n")
        }
        if (s.maxCycleMs > 30_000) {
            sb.append("  Status: 🛑 cycles > 30s indicate watchlist or scanner overload.\n")
        }
        sb.append('\n')

        // ── V5.9.1088 staged — Runtime stall sentinels ───────────────
        // Purpose: after the 1085-1087 regression fixes, the operator's next
        // phone dump must prove whether exit sweep gates, universal SL gates,
        // supervisor leases, or UI lifecycle gates are still the active stall
        // source. Labelled counters contain these events, but the top-40 list
        // can bury the relationship between START/DONE/SKIP/TIMEOUT. This
        // compact section renders the ratios explicitly.
        fun lc(name: String): Long = s.labelCounts["LIFECYCLE/$name"] ?: 0L
        // V5.9.1318 (Item 2) — UNIFY exit sentinels with the canonical single-owner
        // ExitCoordinator (V5.9.1198). The legacy EXIT_SWEEP_ASYNC_* / UNIVERSAL_SL_SWEEP_*
        // counters were orphaned when the coordinator actor took over, which is why the
        // panel showed 0/0 while EXIT_COORDINATOR_FULL_START/DONE showed 20/20. Read the
        // coordinator counters first; fall back to legacy names only if the coordinator
        // has not emitted yet (older builds / pre-coordinator path).
        fun lcAny(vararg names: String): Long { for (n in names) { val v = lc(n); if (v > 0L) return v }; return 0L }
        val exStart = lcAny("EXIT_COORDINATOR_FULL_START", "EXIT_SWEEP_ASYNC_START")
        val exDone = lcAny("EXIT_COORDINATOR_FULL_DONE", "EXIT_SWEEP_ASYNC_DONE")
        val exLate = lc("EXIT_SWEEP_LATE_DONE")
        val exSkip = lcAny("EXIT_COORDINATOR_FULL_SKIPPED", "EXIT_COORDINATOR_FULL_RATE_LIMITED", "EXIT_SWEEP_SKIPPED")
        val exTimeout = lc("EXIT_SWEEP_TIMEOUT")
        val exReset = lcAny("EXIT_COORDINATOR_STALE_RESET", "EXIT_SWEEP_FORCE_RESET")
        val slStart = lcAny("EXIT_COORDINATOR_UNIVERSAL_START", "UNIVERSAL_SL_SWEEP_START")
        val slDone = lcAny("EXIT_COORDINATOR_UNIVERSAL_DONE", "UNIVERSAL_SL_SWEEP_DONE")
        val slLate = lc("UNIVERSAL_SL_SWEEP_LATE_DONE")
        val slSkip = lcAny("EXIT_COORDINATOR_UNIVERSAL_SKIPPED", "EXIT_COORDINATOR_UNIVERSAL_RATE_LIMITED", "UNIVERSAL_SL_SWEEP_SKIPPED")
        val slTimeout = lc("UNIVERSAL_SL_SWEEP_TIMEOUT")
        val slReset = lcAny("EXIT_COORDINATOR_STALE_RESET", "UNIVERSAL_SL_SWEEP_FORCE_RESET")
        // V5.9.1318 (Item 2) — explicit stale-reset telemetry the operator requested.
        // (Lock-age is emitted in the EXIT_COORDINATOR_STALE_RESET event fields itself;
        // labelCounts is a counter store, not a gauge store, so we surface the count here.)
        val exitStaleReset = lc("EXIT_COORDINATOR_STALE_RESET")
        val supCap = lc("SUPERVISOR_INFLIGHT_CAP")
        val supSat = lc("SUPERVISOR_POOL_SATURATED_NO_RESET")
        val supExpired = lc("SUPERVISOR_LEASE_EXPIRED")
        val supTimeout = lc("SUPERVISOR_WORKER_TIMEOUT")
        val supTimeoutRecent2m = recentEventCount("LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT", 120_000L)
        val paperSoftHold = lc("PAPER_SOFT_LOSS_MIN_HOLD")
        val uiInactiveSkip = lc("MAIN_UPDATE_SKIPPED_INACTIVE")
        if (exStart + exDone + exSkip + exTimeout + exReset + slStart + slDone + slSkip + slTimeout + slReset + supCap + supSat + supExpired + supTimeout + paperSoftHold + uiInactiveSkip > 0L) {
            sb.append("===== Runtime stall sentinels =====\n")
            sb.append(line("Exit sweep start/done:", "$exStart / $exDone", "late=$exLate skip=$exSkip timeout=$exTimeout reset=$exReset")).append('\n')
            sb.append(line("Universal SL start/done:", "$slStart / $slDone", "late=$slLate skip=$slSkip timeout=$slTimeout reset=$slReset")).append('\n')
            sb.append(line("ExitCoordinator stale resets:", exitStaleReset, "EXIT_COORDINATOR_STALE_RESET (should be ~0 in steady state; see event fields for lock age)")).append('\n')
            sb.append(line("Supervisor cap/sat:", "$supCap / $supSat", "expiredLeases=$supExpired workerTimeout=$supTimeout recent2m=$supTimeoutRecent2m")).append('\n')
            sb.append(line("Paper soft-loss holds:", paperSoftHold, "1086 gate delaying fake instant paper losses")).append('\n')
            sb.append(line("UI inactive skips:", uiInactiveSkip, "should be near zero while Main is visible")).append('\n')
            if (exStart > 0 && exDone == 0L && exTimeout == 0L && exReset == 0L)
                sb.append("  ⚠ EXIT sweep starts but never completes/timeouts — worker may be wedged before watchdog ownership logs.\n")
            if (exSkip > exDone + exTimeout + exReset + 3)
                sb.append("  ⚠ EXIT sweep skip pressure exceeds done/timeout/reset — alreadyRunning still choking exits.\n")
            if (slSkip > slDone + slTimeout + slReset + 3)
                sb.append("  ⚠ Universal SL skip pressure exceeds done/timeout/reset — hard-floor sweep may still be choked.\n")
            if (supCap > 0 && supExpired == 0L)
                sb.append("  ⚠ Supervisor cap hit but no lease expiry — lease pruning may not be freeing capacity.\n")
            if (uiInactiveSkip > 0)
                sb.append("  ⚠ Main update skipped while inactive — if visible, V5.9.1085 did not fully close the UI gate regression.\n")
            sb.append('\n')
        }

        // ── V5.9.1331 — Per-lane open positions (slot-cap diagnostic) ──
        // Operator: "Add memeOpen cap so a slot stall vs an exposure stall is
        // immediately identifiable in the dump." When a lane reports stalls,
        // this section tells us whether the lane is HOLDING positions (slot
        // ceiling) or simply not finding setups (intake/score floor).
        try {
            val scOpen = try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().size } catch (_: Throwable) { -1 }
            val msOpen = try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().size } catch (_: Throwable) { -1 }
            val hostOpen = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 }
            val memeOpen = (if (scOpen >= 0) scOpen else 0) + (if (msOpen >= 0) msOpen else 0)
            sb.append("===== Per-lane open positions (slot-cap diagnostic) =====\n")
            sb.append(line("ShitCoin open:", scOpen, "active ShitCoin paper+live positions")).append('\n')
            sb.append(line("Moonshot open:", msOpen, "active Moonshot paper+live positions")).append('\n')
            sb.append(line("Meme open (SC+MS):", memeOpen, "combined meme-lane slot consumption")).append('\n')
            sb.append(line("Host wallet open:", hostOpen, "cross-lane wallet tracker count")).append('\n')
            sb.append("  Read: high memeOpen + zero EXEC ⇒ slot stall (waiting on exits). Low memeOpen + zero EXEC ⇒ intake/score-floor stall.\n")
            sb.append('\n')
        } catch (_: Throwable) {}

        // ── V5.9.1333 — Tactic switcher + personality tune state ──
        // Per-(lane, scoreBand) current tactic + bot personality multipliers.
        try {
            val tacticSnaps = try {
                com.lifecyclebot.engine.learning.TacticSwitcher.snapshotAll()
            } catch (_: Throwable) { emptyList() }
            if (tacticSnaps.isNotEmpty()) {
                sb.append("===== Tactic Switcher (V5.9.1333) — fluid tactic rotation =====\n")
                tacticSnaps.take(15).forEach { snap ->
                    val ageMin = snap.ageMs / 60_000L
                    val laneAndBand = snap.key.split("|", limit = 2)
                    val lane = laneAndBand.getOrNull(0) ?: ""
                    val band = laneAndBand.getOrNull(1) ?: ""
                    // V5.9.1334 — if LAB_PROPOSED, append the live lab-shape so
                    // the operator can see which lab strategy is steering this
                    // bucket right now.
                    val labTag = if (snap.tactic == com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.LAB_PROPOSED) {
                        try {
                            val shape = com.lifecyclebot.engine.learning.TacticSwitcher.labShapeFor(lane, band)
                            if (shape != null) " 🧪 ${shape.strategyName.take(18)} (floor=${shape.entryScoreMin} tp=${"%.0f".format(shape.takeProfitPct)}%)"
                            else " 🧪 (lab pending — falling back to MOMENTUM shape)"
                        } catch (_: Throwable) { "" }
                    } else ""
                    sb.append("  ${snap.key.padEnd(24)} ${snap.tactic.name.padEnd(15)} n=${snap.tradesSinceRotation} W/L=${snap.winsSinceRotation}/${snap.lossesSinceRotation} μ=${"%+.1f".format(snap.meanPnlPct)}% age=${ageMin}m$labTag\n")
                }
                sb.append("  (Rotates MOMENTUM → PULLBACK → REACCUMULATION → BREAKOUT → LAB_PROPOSED on Bayesian early-stop (8+ decisive closes), hard bleed, or persistent bleed. NEVER disables.)\n\n")
            }
            sb.append("  ${com.lifecyclebot.engine.PersonalityTraitMultipliers.summaryLine()}\n\n")
        } catch (_: Throwable) {}

        // ── Gate tally ──────────────────────────────────────────────
        if (s.phaseAllow.isNotEmpty() || s.phaseBlock.isNotEmpty()) {
            sb.append("===== Gate allow / block tally =====\n")
            val allKeys = (s.phaseAllow.keys + s.phaseBlock.keys).distinct().sorted()
            for (k in allKeys) {
                val a = s.phaseAllow[k] ?: 0L
                val b = s.phaseBlock[k] ?: 0L
                sb.append("  $k:  allow=$a  block=$b\n")
            }
            sb.append('\n')
        }

        // ── Block reason histogram ──────────────────────────────────
        if (s.blockReasonCounts.isNotEmpty()) {
            sb.append("===== Top block reasons (gate -> reason) =====\n")
            s.blockReasonCounts.entries.sortedByDescending { it.value }.take(20)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── V3 reject reason histogram (V5.9.1046) ──────────────────
        // REJECTED_FATAL_V3 lifecycle counter shows the bare total but
        // operator can't tell *which* V3 sub-gate dominates. This
        // surfaces the normalised reason key so the operator can chase
        // the biggest contributor.
        if (s.v3RejectReasonCounts.isNotEmpty()) {
            sb.append("===== Top V3 reject reasons (REJECTED_FATAL_V3) =====\n")
            s.v3RejectReasonCounts.entries.sortedByDescending { it.value }.take(15)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Per-source intake ──────────────────────────────────────
        if (s.intakeBySource.isNotEmpty()) {
            sb.append("===== Intake by source =====\n")
            s.intakeBySource.entries.sortedByDescending { it.value }.take(20)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Per-lane eval ───────────────────────────────────────────
        if (s.laneEvalCounts.isNotEmpty()) {
            sb.append("===== LANE_EVAL by lane =====\n")
            s.laneEvalCounts.entries.sortedByDescending { it.value }
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        sb.append("===== QUALITY-only runtime leak audit =====\n")
        val activeMitigationText = RuntimeConfigOverlay.activeCommands()
            .joinToString(" ; ") { cmd -> "${cmd.kind}:${cmd.target}=${cmd.value}" }
            .ifBlank { "none" }
        sb.append("  Policy:                  ${RuntimeConfigOverlay.qualityOnlySummary()}\n")
        sb.append("  Active mitigations:      $activeMitigationText\n")
        val activeQualityEval = s.laneEvalCounts["QUALITY"] ?: 0L
        val activeNonQualityEval = s.laneEvalCounts.filterKeys { RuntimeConfigOverlay.normalizeLane(it) != "QUALITY" }.values.sum()
        val suppressedNonQualityEval = s.laneEvalSuppressedCounts.values.sum()
        val activeQualityFdg = s.fdgPathCounts["QUALITY"] ?: 0L
        val activeNonQualityFdg = s.fdgPathCounts.filterKeys { RuntimeConfigOverlay.normalizeLane(it) != "QUALITY" }.values.sum()
        val suppressedNonQualityFdg = s.fdgSuppressedPathCounts.values.sum()
        // V5.9.1323 — LEAK label gating (P1-5 surgical). Operator §5:
        // non-QUALITY activity is only a LEAK when quality-only is actually on.
        // Otherwise it's intentional multi-lane operation.
        val hardQualityOnly = try { com.lifecyclebot.engine.RuntimeConfigOverlay.isHardQualityOnlyActive() } catch (_: Throwable) { false }
        val forcedPrimary = try { com.lifecyclebot.engine.RuntimeConfigOverlay.forcedPrimaryLane() } catch (_: Throwable) { null }
        val qualityOnlyEnforced = hardQualityOnly || forcedPrimary == "QUALITY"
        val evalLeakLabel = if (qualityOnlyEnforced && activeNonQualityEval > 0) "LEAK" else if (activeNonQualityEval > 0) "MULTI_LANE_ACTIVE" else "OK"
        val fdgLeakLabel = if (qualityOnlyEnforced && activeNonQualityFdg > 0) "LEAK" else if (activeNonQualityFdg > 0) "MULTI_LANE_ACTIVE" else "OK"
        sb.append("  Active QUALITY eval:      $activeQualityEval\n")
        sb.append("  Active non-QUALITY eval:  $activeNonQualityEval $evalLeakLabel\n")
        sb.append("  Suppressed non-QUALITY eval: $suppressedNonQualityEval\n")
        sb.append("  Active QUALITY FDG:       $activeQualityFdg\n")
        sb.append("  Active non-QUALITY FDG:   $activeNonQualityFdg $fdgLeakLabel\n")
        sb.append("  Suppressed non-QUALITY FDG: $suppressedNonQualityFdg\n")
        if (s.laneEvalSuppressedCounts.isNotEmpty()) {
            sb.append("  Suppressed lane eval by lane:\n")
            for (entry in s.laneEvalSuppressedCounts.entries.sortedByDescending { it.value }) {
                sb.append("    • ${entry.key}: ${entry.value}\n")
            }
        }
        if (s.fdgSuppressedPathCounts.isNotEmpty()) {
            sb.append("  Suppressed FDG by path:\n")
            for (entry in s.fdgSuppressedPathCounts.entries.sortedByDescending { it.value }) {
                sb.append("    • ${entry.key}: ${entry.value}\n")
            }
        }
        val qualityBlockLabels = s.labelCounts.filterKeys {
            it.contains("QUALITY_ONLY") || it.contains("PREAUTH_BLOCK") || it.contains("EXEC_OPEN_BLOCKED")
        }
        if (qualityBlockLabels.isNotEmpty()) {
            sb.append("  Quality/preauth/open block labels:\n")
            for (entry in qualityBlockLabels.entries.sortedByDescending { it.value }.take(30)) {
                sb.append("    • ${entry.key}: ${entry.value}\n")
            }
        }
        sb.append('\n')

        // ── Decision verdicts ───────────────────────────────────────
        if (s.verdictCounts.isNotEmpty()) {
            sb.append("===== Decision verdicts =====\n")
            s.verdictCounts.entries.sortedByDescending { it.value }
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Top intaked symbols ─────────────────────────────────────
        if (s.symbolIntakeCounts.isNotEmpty()) {
            sb.append("===== Top intaked symbols =====\n")
            s.symbolIntakeCounts.entries.sortedByDescending { it.value }.take(15)
                .forEach { sb.append(line("${it.key}:", it.value, "intake hits")).append('\n') }
            sb.append('\n')
        }

        // ── Mixed-mode execution breakdown ──────────────────────────
        val liveRecentRows = s.recentExecs.count { it.mode.equals("LIVE", true) }
        val paperRecentRows = s.recentExecs.count { it.mode.equals("PAPER", true) }
        sb.append("===== LIVE execution telemetry (event-attributed) =====\n")
        sb.append("  FDG allow/block:      ${fdgLiveAllow.get()} / ${fdgLiveBlock.get()}\n")
        sb.append("  EXEC attempt:         ${execLiveAttempt.get()}\n")
        sb.append("  BUY ok/fail:          ${execLiveBuyOk.get()} / ${execLiveBuyFail.get()}\n")
        sb.append("  SELL ok/fail:         ${execLiveSellOk.get()} / ${execLiveSellFail.get()}\n")
        val topLiveBuyFailReasons = s.liveBuyFailReasonCounts.entries.sortedByDescending { it.value }.take(8)
        if (topLiveBuyFailReasons.isNotEmpty()) {
            sb.append("  Top BUY fail reasons: ")
            sb.append(topLiveBuyFailReasons.joinToString(" · ") { "${it.key}=${it.value}" })
            sb.append("\n")
        }
        sb.append("  Recent journal rows:  ${liveRecentRows}\n")
        sb.append("  Execution leases:     ${ExecutionAttemptLease.formatForReport()}\n")
        sb.append("\n")
        val paperJournalRows = labelCounts["PAPER_JOURNAL_ROWS"]?.get() ?: paperRecentRows.toLong()
        val paperQuarantinedRows = labelCounts["PAPER_LEARNING_ROW_QUARANTINED"]?.get() ?: 0L
        val jrnlLiveRows = labelCounts["TRADEJRNL_REC_LIVE"]?.get() ?: 0L
        val jrnlPaperRows = labelCounts["TRADEJRNL_REC_PAPER"]?.get() ?: 0L
        val jrnlPartialRows = labelCounts["TRADEJRNL_REC_PARTIAL"]?.get() ?: 0L
        sb.append("===== PAPER execution telemetry (event-attributed) =====\n")
        sb.append("  TRADEJRNL_SPLIT liveRows=$jrnlLiveRows paperRows=$jrnlPaperRows partialRows=$jrnlPartialRows quarantinedRows=$paperQuarantinedRows\n")
        sb.append("  FDG allow/block:      ${fdgPaperAllow.get()} / ${fdgPaperBlock.get()}\n")
        sb.append("  BUY ok:               ${execPaperBuyOk.get()}\n")
        sb.append("  SELL ok:              ${execPaperSellOk.get()}\n")
        sb.append("  PARTIAL ok:           ${execPaperPartialOk.get()}\n")
        sb.append("  PAPER_JOURNAL_ROWS:   $paperJournalRows\n")
        sb.append("  PAPER_QUARANTINED_ROWS: $paperQuarantinedRows\n")
        sb.append("  Recent journal rows:  ${paperRecentRows}\n")
        val paperOkRows = execPaperBuyOk.get() + execPaperSellOk.get() + execPaperPartialOk.get()
        val expectedPaperRows = jrnlPaperRows
        if (expectedPaperRows == paperOkRows) {
            bump(labelCounts, "TRADEJRNL_COUNTER_PARITY_OK")
            sb.append("  Counter parity:       OK paperOk=$paperOkRows rows=$expectedPaperRows\n")
        } else {
            bump(labelCounts, "TRADEJRNL_COUNTER_PARITY_FAIL")
            sb.append("  Counter parity:       FAIL paperOk=$paperOkRows rows=$expectedPaperRows\n")
        }
        sb.append("\n")

        // ── Recent executions ───────────────────────────────────────
        if (s.recentExecs.isNotEmpty()) {
            fun appendExecList(title: String, rows: List<ExecRecord>) {
                sb.append("===== ").append(title).append(" (last ").append(rows.size).append(") =====\n")
                if (rows.isEmpty()) {
                    sb.append("  (none)\n\n")
                    return
                }
                for (ex in rows.asReversed()) {
                    sb.append("  ").append(df.format(Date(ex.tsMs))).append(' ')
                        .append(ex.side.padEnd(4)).append(' ')
                        .append(ex.mode.padEnd(5)).append(' ')
                        .append(ex.symbol.padEnd(10)).append(' ')
                        .append("sol=").append(if (ex.side.equals("PARTIAL_SELL", true)) String.format("%.6f", ex.sizeSol) else String.format("%.3f", ex.sizeSol)).append(' ')
                        .append("pnl=").append(if (ex.side.equals("PARTIAL_SELL", true)) String.format("%+.6f", ex.pnlSol) else String.format("%+.3f", ex.pnlSol))
                    if (ex.proofState.isNotBlank()) sb.append(" proof=").append(ex.proofState)
                    if (ex.positionId.isNotBlank()) sb.append(" pid=").append(ex.positionId.takeLast(12))
                    if (ex.lane.isNotBlank()) sb.append(" lane=").append(ex.lane.take(16))
                    if (ex.entryPriceSnapshot > 0.0) sb.append(" entry=").append(String.format("%.8g", ex.entryPriceSnapshot))
                    if (ex.entryCostSol > 0.0) sb.append(" cost=").append(String.format("%.4f", ex.entryCostSol))
                    if (ex.entryQtyToken > 0.0) sb.append(" qty=").append(String.format("%.4g", ex.entryQtyToken))
                    if (ex.entryMcapUsd > 0.0) sb.append(" mcap=$").append(String.format("%.0f", ex.entryMcapUsd))
                    if (ex.entryPriceSource.isNotBlank()) sb.append(" src=").append(ex.entryPriceSource.take(18))
                    if (ex.reason.isNotBlank()) sb.append(" reason=").append(ex.reason.take(60))
                    sb.append('\n')
                }
                sb.append('\n')
            }
            appendExecList("Recent LIVE executions", s.recentExecs.filter { it.mode.equals("LIVE", true) })
            appendExecList("Recent PAPER executions", s.recentExecs.filter { it.mode.equals("PAPER", true) })
        }

        // ── ANR health (watchdog) ───────────────────────────────────
        sb.append("===== ANR / main-thread health (watchdog sampler) =====\n")
        sb.append("  ANR_HINTS (>${LONG_FRAME_THRESHOLD_MS}ms):           ${s.anrHints}\n")
        sb.append("  Watchdog samples taken:                ${s.anrSamplesTaken}\n")
        sb.append("  Max frame gap:                          ${s.maxFrameGapMs} ms\n")
        sb.append("  Total stall time accumulated:           ${s.totalFrameStallMs} ms\n")
        val stallPct = if (uptimeSec > 0) (s.totalFrameStallMs / (uptimeSec * 10.0)) else 0.0
        sb.append("  Stall % of uptime:                      ${"%.1f".format(stallPct)}%\n")
        sb.append("  Watcher installed:                      $anrInstalled\n")
        if (s.anrHints == 0) {
            sb.append("  Status: ✅ no long-frame events captured.\n")
        } else if (s.anrHints < 5) {
            sb.append("  Status: ⚠ minor — likely startup hiccup.\n")
        } else {
            sb.append("  Status: 🛑 sustained main-thread blocking. Inspect top blocking call sites below.\n")
        }
        sb.append('\n')

        // ── Top blocking call sites ─────────────────────────────────
        if (s.anrStackCounts.isNotEmpty()) {
            sb.append("===== ANR top blocking call sites (most frequent first) =====\n")
            s.anrStackCounts.entries.sortedByDescending { it.value }.take(20).forEach { (frame, count) ->
                sb.append("  [$count]  $frame\n")
            }
            sb.append('\n')
        }

        // ── V5.9.915 — Pre-freeze rolling main-thread sample ────────
        // Captured every watchdog tick (250ms). Newest first. When a
        // freeze fires, this shows the ~7.5s leading up to it — far
        // more useful than the single stack sampled at detection time.
        if (s.stackRing.isNotEmpty()) {
            sb.append("===== Pre-freeze rolling main-thread sample (last ${s.stackRing.size}, newest first) =====\n")
            for (smp in s.stackRing.asReversed()) {
                sb.append("  ").append(df.format(Date(smp.tsMs)))
                    .append("  gap=").append(smp.sinceLastAckMs.toString().padStart(5)).append("ms  ")
                    .append(smp.topFrame).append('\n')
            }
            sb.append('\n')
        }

        // ── Labelled counters ───────────────────────────────────────
        // V5.9.915 — LIFECYCLE/* and SNAP/* entries are pinned BEFORE the
        // top-40 by-count slice. The previous frequency-sorted-take(40)
        // pushed singleton lifecycle counters (BATTERY_OPT_CHECK=1,
        // LOOP_HEARTBEAT_ALARM=15, CYCLE_PHASE=63 etc.) below the cut
        // whenever a few hundred SCAN_CB / GATE_BLOCK entries flooded
        // labelCounts, so the dump never proved whether the latest
        // version's lifecycle hooks were actually firing on device.
        // Now all LIFECYCLE/* and SNAP/* counters are emitted FIRST in
        // sorted order, then the remaining counters fill up to 40 slots.
        if (s.labelCounts.isNotEmpty()) {
            sb.append("===== Labelled counters (lane / error / lock / etc.) =====\n")
            val all = s.labelCounts.entries.toList()
            val lifecycle = all.filter { it.key.startsWith("LIFECYCLE/") }
                .sortedByDescending { it.value }
            val snaps = all.filter { it.key.startsWith("SNAP/") }
                .sortedByDescending { it.value }
            val pinnedKeys = (lifecycle + snaps).map { it.key }.toSet()
            // Render pinned (lifecycle then snap) regardless of count rank.
            for (e in lifecycle) sb.append(line("${e.key}:", e.value)).append('\n')
            for (e in snaps) sb.append(line("${e.key}:", e.value)).append('\n')
            // Fill remaining slots up to 40 with the highest-count NON-pinned
            // entries so high-volume tags (TRADEJRNL_REC, BOT_LOOP_TICK, gate
            // tags) still appear when they out-rank lifecycle singletons.
            val remainingSlots = (40 - lifecycle.size - snaps.size).coerceAtLeast(0)
            all.asSequence()
                .filter { it.key !in pinnedKeys }
                .sortedByDescending { it.value }
                .take(remainingSlots)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Recent events ───────────────────────────────────────────
        // V5.9.916 — cap rendered events to last 80 even if ring holds more.
        // Operator V5.9.915 ANR forensics: setting 16KB TextView text every 2s
        // saturated the main thread (PipelineHealthActivity.renderSnapshotAsync
        // gap=604ms repeatedly). The ring still captures 150 for snapshot/copy
        // — we only truncate what gets rendered live every 2s.
        val renderEvents = s.recentEvents.takeLast(80)
        sb.append("===== Recent events (last ${renderEvents.size} of ${s.recentEvents.size}) =====\n")
        for (ev in renderEvents.asReversed()) {
            sb.append("  ${df.format(Date(ev.tsMs))}  ")
                .append(ev.tag.padEnd(28))
            if (ev.symbol.isNotEmpty()) sb.append(ev.symbol.padEnd(12)) else sb.append("            ")
            sb.append(ev.message).append('\n')
        }
        sb.append('\n')

        // V5.9.915 — operator-only strategy expectancy block. Read-only;
        // surfaces which tradingMode strategies are net-positive vs which
        // are bleeding, so the operator can decide what to retire without
        // dumping the journal CSV and pivoting it by hand. Empty when the
        // journal hasn't accumulated ≥5 trades per strategy yet.
        try {
            val strategyBlock = StrategyTelemetry.formatForPipelineDump()
            if (strategyBlock.isNotEmpty()) sb.append(strategyBlock)
            // V5.9.1273 — show the live lane-size damper state next to expectancy.
            try { sb.append("  " + LaneExpectancyDamper.statusLine() + "\n") } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // telemetry is non-essential; never let it break the dump
        }
        // V5.9.915 — Regime / LosingPattern / BrainConsensus telemetry.
        try { sb.append(RegimeDetector.formatForPipelineDump()) } catch (_: Throwable) {}
        try {
            val lp = LosingPatternMemory.formatForPipelineDump()
            if (lp.isNotEmpty()) sb.append(lp)
        } catch (_: Throwable) {}
        try {
            val bcg = BrainConsensusGate.formatForPipelineDump()
            val metaPolicyDump = try { com.lifecyclebot.engine.AutonomousMetaPolicy.formatForPipelineDump() } catch (_: Throwable) { "" }
            val fwdDump = try { com.lifecyclebot.engine.ForwardOutcomeModel.formatForPipelineDump() } catch (_: Throwable) { "" }
            val uphDump = try { com.lifecyclebot.engine.UnifiedPolicyHead.formatForPipelineDump() } catch (_: Throwable) { "" }
            val moeDump = try { com.lifecyclebot.v3.scoring.SpecialistMoEGate.formatForPipelineDump() } catch (_: Throwable) { "" }
            val hypoDump = try { com.lifecyclebot.engine.StrategyHypothesisEngine.formatForPipelineDump() } catch (_: Throwable) { "" }
            if (bcg.isNotEmpty()) sb.append(bcg)
            if (metaPolicyDump.isNotEmpty()) sb.append(metaPolicyDump)
            if (fwdDump.isNotEmpty()) sb.append(fwdDump)
            if (uphDump.isNotEmpty()) sb.append(uphDump)
            if (moeDump.isNotEmpty()) sb.append(moeDump)
            if (hypoDump.isNotEmpty()) sb.append(hypoDump)
            val laneTunerDump = try { com.lifecyclebot.engine.learning.LaneExitTuner.formatForPipelineDump() } catch (_: Throwable) { "" }
            if (laneTunerDump.isNotEmpty()) sb.append(laneTunerDump)
        } catch (_: Throwable) {}

        // ── PerformanceAnalytics block ─────────────────────────────
        // V5.9.997 — z PerformanceAnalytics revival (was 0-caller).
        // V5.0.3843 — now pulls last 1000 canonical closed journal rows via
        // TradeHistoryStore, not legacy TradeDatabase, so WR/lane analytics match
        // the Journal Summary and canonical totals in this same report.
        // Cached: only refresh the analytics block every 30s; stale diagnostic
        // output is fine and avoids hot-loop DB/report churn.
        try {
            val cached = perfAnalyticsCache
            val now = System.currentTimeMillis()
            if (cached != null && (now - perfAnalyticsCacheAt) < 30_000L) {
                sb.append(cached)
            } else {
                val perfTrades = canonicalPerformanceTrades()
                if (perfTrades.isNotEmpty()) {
                    val stats = com.lifecyclebot.engine.PerformanceAnalytics.analyze(perfTrades)
                    val block = buildString {
                        appendLine()
                        appendLine("===== Performance analytics (last 1000 closed) =====")
                        append(com.lifecyclebot.engine.PerformanceAnalytics.formatSummary(stats))
                        appendLine()
                        try {
                            val lifetime = try { com.lifecyclebot.engine.TradeHistoryStore.getLifetimeStats().totalSells } catch (_: Throwable) { stats.totalTrades }
                            val mature = lifetime >= 5000
                            val phaseTag = if (mature) "MATURE" else "BOOTSTRAP"
                            val floor = if (mature) "50-89%" else "20-35%"
                            appendLine("===== Separated WR metrics (V5.9.1378) =====")
                            appendLine("  Phase:        $phaseTag  (lifetime closes=$lifetime; doctrine floor=$floor)")
                            appendLine("  Blended WR:   ${"%.1f".format(stats.winRate)}%  (n=${stats.totalTrades} in window)")
                            val onFloor = if (mature) stats.winRate >= 50.0 else stats.winRate >= 20.0
                            appendLine("  Floor status: ${if (onFloor) "✅ within band" else "🔴 BELOW $phaseTag floor"}")
                            val byPhase = stats.winRateByPhase
                            val cntByPhase = stats.tradeCountByPhase
                            if (byPhase.isNotEmpty()) {
                                appendLine("  By lane (WR | n):")
                                byPhase.entries
                                    .filter { (cntByPhase[it.key] ?: 0) >= 5 }
                                    .sortedBy { it.value }
                                    .forEach { (lane, wr) ->
                                        val n = cntByPhase[lane] ?: 0
                                        val flag = if (wr < 20.0) " 🔴bleeder" else if (wr >= 50.0) " ✅" else ""
                                        appendLine("    ${lane.padEnd(14)} ${"%.1f".format(wr)}%  n=$n$flag")
                                    }
                            }
                            appendLine()
                        } catch (_: Throwable) { /* diagnostic only */ }
                    }
                    perfAnalyticsCache = block
                    perfAnalyticsCacheAt = now
                    sb.append(block)
                }
            }
        } catch (_: Throwable) { /* fail-open — diagnostic only */ }

        // ── Cheat-sheet ─────────────────────────────────────────────
        // V5.9.915 — expanded cheat-sheet with actionable context
        // V5.9.1048 — fix counter-key divergence reported in V5.9.1047
        // operator snapshot (EXEC_BUY=0 despite many actual BUYs in
        // journal log). The reader was looking for legacy `EXEC/PAPER_BUY`
        // + `EXEC/LIVE_BUY` keys, but TradeHistoryStore.recordExec writes
        // `EXEC_BUY` / `EXEC_SELL` (underscore form, side only). execSell
        // already matched the writer; only execBuy was wrong.
        val execBuy  = labelCounts["EXEC_BUY"]?.get() ?: 0L
        val execSell = labelCounts["EXEC_SELL"]?.get() ?: 0L
        val stall    = if (uptimeSec > 0) (s.totalFrameStallMs * 100L / (uptimeSec * 1000L)) else 0L
        val avgCycleMs = if (s.cycleCount > 0L) s.totalCycleMs / s.cycleCount else 0L
        // ── live counters for cheat-sheet ──────────────────────────────
        val loopTick    = labelCounts["BOT_LOOP_TICK"]?.get() ?: 0L
        val scanCb      = s.phaseCounts["SCAN_CB"] ?: 0L
        val intake      = s.phaseCounts["INTAKE"]  ?: 0L
        val safety      = s.phaseCounts["SAFETY"]  ?: 0L
        val v3          = s.phaseCounts["V3"]       ?: 0L
        val laneEval    = s.phaseCounts["LANE_EVAL"]?: 0L
        val fdgRawRows  = s.phaseCounts["FDG"]      ?: 0L
        val fdgBlock    = s.phaseBlock["FDG"]       ?: 0L
        val fdgAllow    = s.phaseAllow["FDG"]       ?: 0L
        val fdgTotal    = (fdgAllow + fdgBlock).takeIf { it > 0L } ?: fdgRawRows
        val intakeBlock = s.phaseBlock["INTAKE"]    ?: 0L
        val exitAllow   = s.phaseAllow["EXIT"]      ?: 0L
        val exitBlock   = s.phaseBlock["EXIT"]      ?: 0L
        val noPairCnt   = s.blockReasonCounts.filter { it.key.startsWith("INTAKE/NO_PAIR") }.values.sumOf { it }
        val safetyBlock = s.phaseBlock["SAFETY"]    ?: 0L
        val safetyAllow = s.phaseAllow["SAFETY"]    ?: 0L

        sb.append("===== Interpretation cheat-sheet =====\n")

        // ── Funnel health ───────────────────────────────────────────────
        sb.append("\n  [FUNNEL]\n")
        sb.append("  BOT_LOOP_TICK=$loopTick")
        if (loopTick == 0L)  sb.append(" ⚠ botLoop never iterated — service may not have started")
        else if (avgCycleMs > 30_000) sb.append(" ⚠ avg cycle ${avgCycleMs}ms — watchlist may be overloaded")
        else sb.append(" ✅")
        sb.append("\n")

        sb.append("  SCAN_CB=$scanCb  INTAKE=$intake  SAFETY=$safety  V3=$v3  LANE_EVAL=$laneEval  FDG=$fdgTotal  EXEC=$execBuy  EXIT=$exitAllow\n")
        if (fdgRawRows > fdgTotal) sb.append("  FDG_RAW_ROWS=$fdgRawRows  (forensic rows; decision outcomes=$fdgTotal)\n")
        if (scanCb > 0 && intake == 0L)
            sb.append("  ⚠ SCAN_CB>0 but INTAKE=0 — scanner discoveries not reaching intake gate.\n")
        if (intake > 0 && safety == 0L && intakeBlock < intake)
            sb.append("  ⚠ INTAKE>0 but SAFETY=0 — tokens may be lost between intake and safety check.\n")
        if (safety > 0 && v3 == 0L)
            sb.append("  ⚠ SAFETY>0 but V3=0 — V3 engine not receiving scored tokens; check V3EngineEnabled or liquid bucket routing.\n")
        if (v3 > 0 && laneEval == 0L)
            sb.append("  ⚠ V3>0 but LANE_EVAL=0 — V3 short-circuiting before lane routing; check V3EngineEnabled flag.\n")
        if (laneEval > 0 && execBuy == 0L)
            sb.append("  ⚠ LANE_EVAL>0 but EXEC_BUY=0 — executor not firing; FDG may be blocking all (see below) or cbState.isPaused.\n")

        // ── INTAKE gate ─────────────────────────────────────────────────
        sb.append("\n  [INTAKE GATE]  block=$intakeBlock\n")
        sb.append("  Note: INTAKE allow counter is always 0 — passing tokens are not separately tallied.\n")
        sb.append("        Use SAFETY>0 as the downstream confirmation that intake is passing tokens.\n")
        if (noPairCnt > 0) {
            sb.append("  NO_PAIR_NO_FALLBACK=$noPairCnt — these pump.fun tokens have no Raydium pair yet.\n")
            sb.append("        Normal during bonding-curve phase; clears once token graduates to Raydium.\n")
            sb.append("        High count is expected on fresh boot — not a problem unless SAFETY=0 too.\n")
        }

        // ── FDG gate ────────────────────────────────────────────────────
        sb.append("\n  [FDG GATE]  allow=$fdgAllow  block=$fdgBlock\n")
        // V5.9.915 — EMERGENT-MEME #9: derive interpretation from
        // actual top block reason, not blanket "likely bootstrap".
        // The user's V5.9.915 dump had top reason
        // LIQUIDITY_BELOW_EXECUTION_FLOOR (824/839) but the text
        // still said "CONFIDENCE_FLOOR means live-WR data too
        // sparse" — wrong call to action.
        val topBlockReasonRaw = s.blockReasonCounts.entries
            .filter { it.key.startsWith("FDG/") }
            .maxByOrNull { it.value }?.key?.removePrefix("FDG/")
            ?: ""
        if (fdgBlock > 0 && fdgAllow == 0L) {
            when {
                topBlockReasonRaw.contains("SAFETY_NOT_READY") -> {
                    sb.append("  ⚠ FDG blocking 100% on SAFETY_NOT_READY — safety pipeline miswired or rugcheck pending.\n")
                    sb.append("    Action: verify SafetyReport.write keys == FDG read keys (canonical mint).\n")
                }
                topBlockReasonRaw.contains("LIQUIDITY_BELOW") -> {
                    sb.append("  ⚠ FDG blocking 100% on $topBlockReasonRaw — execution floor too high for current universe.\n")
                    sb.append("    Action: audit ts.lastLiquidityUsd freshness or lower SHITCOIN execution-floor lerp.\n")
                }
                topBlockReasonRaw.contains("CONFIDENCE") -> {
                    sb.append("  ⚠ FDG blocking 100% on CONFIDENCE_FLOOR — live-WR data too sparse for LIVE.\n")
                    sb.append("    Normal in first 1000 paper trades. Paper trades still execute.\n")
                }
                topBlockReasonRaw.isNotEmpty() -> {
                    sb.append("  ⚠ FDG blocking 100% — top reason: $topBlockReasonRaw.\n")
                    sb.append("    Action: inspect that gate / lerp / threshold first.\n")
                }
                else -> {
                    sb.append("  ⚠ FDG blocking 100% — no block reason recorded; check FDG instrumentation.\n")
                }
            }
        }
        else if (fdgBlock > fdgAllow * 2)
            sb.append("  ⚠ FDG blocking majority — check DANGER_ZONE, edge veto rate, or brain state.\n")
        else if (fdgAllow > 0)
            sb.append("  ✅ FDG passing $fdgAllow / ${fdgTotal} evaluations.\n")

        // V5.9.915 — per-mode FDG / EXEC breakdown so operator (and
        // forensics consumers like Base44) can disambiguate at a glance
        // whether live trading is actually happening.
        sb.append("\n  [MODE]  current=${modeSnapshot}\n")
        sb.append("  [FDG PER-MODE]\n")
        sb.append("    FDG_LIVE_ALLOW=${fdgLiveAllow.get()}   FDG_LIVE_BLOCK=${fdgLiveBlock.get()}\n")
        sb.append("    FDG_PAPER_ALLOW=${fdgPaperAllow.get()}  FDG_PAPER_BLOCK=${fdgPaperBlock.get()}\n")
        sb.append("  [EXEC PER-MODE]\n")
        sb.append("    EXEC_LIVE_ATTEMPT=${execLiveAttempt.get()}\n")
        sb.append("    EXEC_LIVE_BUY_OK=${execLiveBuyOk.get()}   EXEC_LIVE_BUY_FAIL=${execLiveBuyFail.get()}\n")
        if (s.liveBuyFailReasonCounts.isNotEmpty()) {
            sb.append("    EXEC_LIVE_BUY_FAIL_REASONS=")
            sb.append(s.liveBuyFailReasonCounts.entries.sortedByDescending { it.value }.take(8).joinToString(" · ") { "${it.key}:${it.value}" })
            sb.append("\n")
        }
        sb.append("    EXEC_LIVE_SELL_OK=${execLiveSellOk.get()}  EXEC_LIVE_SELL_FAIL=${execLiveSellFail.get()}\n")
        sb.append("    EXEC_PAPER_BUY_OK=${execPaperBuyOk.get()}  EXEC_PAPER_SELL_OK=${execPaperSellOk.get()}  EXEC_PAPER_PARTIAL_OK=${execPaperPartialOk.get()}\n")
        sb.append("    PAPER_JOURNAL_ROWS=$paperJournalRows  PAPER_QUARANTINED_ROWS=$paperQuarantinedRows\n")
        if (modeSnapshot == "LIVE" && fdgLiveBlock.get() > 0 && fdgLiveAllow.get() == 0L) {
            sb.append("  ⚠ LIVE mode but FDG_LIVE_ALLOW=0 — live trading is fully blocked.\n")
            sb.append("    Check block-reason histogram below for the gate that\'s vetoing.\n")
        }
        if (modeSnapshot == "PAPER" && fdgLiveAllow.get() > 0) {
            sb.append("  ⚠ Mode=PAPER but live FDG passes recorded — historical from a prior live session.\n")
        }

        // ── SAFETY gate ─────────────────────────────────────────────────
        sb.append("\n  [SAFETY GATE]  allow=$safetyAllow  block=$safetyBlock\n")
        if (safetyBlock > safetyAllow * 3)
            sb.append("  ⚠ SAFETY rejecting most tokens — check rug-score thresholds / rcScore floor.\n")
        else if (safetyAllow > 0)
            sb.append("  ✅ Safety passing $safetyAllow tokens downstream.\n")

        // ── EXIT gate ───────────────────────────────────────────────────
        sb.append("\n  [EXIT GATE]  allow=$exitAllow  block=$exitBlock\n")
        if (exitAllow > 0 && exitBlock == 0L)
            sb.append("  ✅ Exit gate healthy — all $exitAllow exit evaluations passed.\n")
        else if (exitBlock > 0)
            sb.append("  Note: $exitBlock exits were blocked (unusual — check EXIT gate logic).\n")

        // ── Execution counters ──────────────────────────────────────────
        sb.append("\n  [EXECUTION COUNTERS]\n")
        sb.append("  EXEC_BUY=$execBuy  EXEC_SELL=$execSell  (TradeHistoryStore journal rows; NOT on-chain proof)\n")
        val execFunnelCount = s.phaseCounts["EXEC"] ?: 0L
        sb.append("  Note: EXEC funnel counter (=$execFunnelCount) counts executor invocations.\n")
        sb.append("        Journal BUY/SELL rows can be written before wallet-delta/finality proof;\n")
        sb.append("        use EXEC_LIVE_BUY_OK / EXEC_LIVE_SELL_OK + BUY_VERIFIED_LANDED / SELL_FINALIZED for landed on-chain truth.\n")
        val jrnlRec = labelCounts["TRADEJRNL_REC"]?.get() ?: 0L
        sb.append("  TRADEJRNL_REC=$jrnlRec — journal records written this session.\n")
        if (execBuy > 0 && jrnlRec == 0L)
            sb.append("  ⚠ EXEC_BUY>0 but TRADEJRNL_REC=0 — executor firing but journal not writing.\n")

        // ── Lane coverage ───────────────────────────────────────────────
        sb.append("\n  [LANE COVERAGE]\n")
        if (s.laneEvalCounts.isEmpty())
            sb.append("  No lane evaluations yet.\n")
        else {
            val totalLaneEvals = s.laneEvalCounts.values.sumOf { it }
            s.laneEvalCounts.entries.sortedByDescending { it.value }.forEach { (lane, cnt) ->
                val pct = if (totalLaneEvals > 0) cnt * 100 / totalLaneEvals else 0L
                sb.append("  $lane: $cnt evals ($pct%)\n")
            }
            if (s.laneEvalCounts.size == 1) {
                sb.append("  Note: Only one lane active — other lanes require higher confidence/score thresholds.\n")
                sb.append("        This is normal during bootstrap. More lanes activate as AI scores improve.\n")
            }
        }

        // ── Cycle timing ────────────────────────────────────────────────
        sb.append("\n  [BOT-LOOP TIMING]\n")
        sb.append("  Avg=${avgCycleMs}ms  Max=${s.maxCycleMs}ms\n")
        if (s.maxCycleMs > 30_000 && avgCycleMs <= 30_000) {
            sb.append("  ⚠ Max spike >30s but avg is normal — isolated stall. Check for GC pause or main-thread lock.\n")
        } else if (avgCycleMs > 30_000) {
            sb.append("  ⚠ Avg cycle >30s — watchlist or scanner overload. May need to reduce watchlist size.\n")
        } else if (s.maxCycleMs <= 30_000) {
            sb.append("  ✅ All cycles within 30s.\n")
        }

        // ── ANR / stall ─────────────────────────────────────────────────
        sb.append("\n  [ANR / MAIN THREAD]\n")
        val anrHints = s.anrHints
        sb.append("  ANR_HINTS=$anrHints  Stall=${stall}%% of uptime\n")
        // V5.9.915 — EMERGENT-MEME #9: interpretation derived from
        // actual data. The previous logic skipped the severe path
        // when ANR_HINTS=0 even if stall=94% / maxFrameGap=77s,
        // and printed "✅ No ANR events" — a complete misread.
        // Source order matters: gate severity on stall + max frame
        // gap first, then ANR count, then top stack site.
        val maxFrameGap = s.maxFrameGapMs
        val topAnrSite = s.anrStackCounts.entries.maxByOrNull { it.value }?.key ?: ""
        when {
            maxFrameGap > 5_000 || stall > 80 -> {
                sb.append("  🛑 SEVERE: maxFrameGap=${maxFrameGap}ms  stall=${stall}%% — bot is barely getting cycles.\n")
                if (topAnrSite.contains("SolanaWallet.rpc") || topAnrSite.contains("getSolBalance")) {
                    sb.append("    Root cause: wallet RPC blocking main thread. Wrap caller in withContext(Dispatchers.IO).\n")
                } else if (topAnrSite.contains("MainActivity") || topAnrSite.contains("renderOpenPositions") || topAnrSite.contains("buildTokenCard")) {
                    sb.append("    Root cause: UI render saturation. Move heavy work off Dispatchers.Main; cap renderOpenPositions list size.\n")
                } else if (topAnrSite.contains("TradeJournal") || topAnrSite.contains("TradeHistoryStore")) {
                    sb.append("    Root cause: SQLite query on main thread. Move journal stats reads to Dispatchers.IO.\n")
                } else if (topAnrSite.isNotEmpty()) {
                    sb.append("    Top blocking call site: $topAnrSite — investigate why it runs on main.\n")
                }
            }
            anrHints > 0 || stall > 50 -> {
                sb.append("  ⚠ Main thread under pressure — inspect 'ANR top blocking call sites' above.\n")
                if (topAnrSite.isNotEmpty()) sb.append("    Top: $topAnrSite\n")
            }
            else -> {
                sb.append("  ✅ Main thread healthy. Stall=${stall}%%.\n")
            }
        }

        // V5.9.915 — EMERGENT-MEME #9: live-paper contamination
        // interpretation. Surface the mode mismatch in plain text.
        if (modeSnapshot == "LIVE" && execPaperBuyOk.get() > 0) {
            sb.append("  ⚠ MODE CONTAMINATION CHECK: LIVE active and cumulative EXEC_PAPER_BUY_OK=${execPaperBuyOk.get()}.\n")
            sb.append("    If PAPER_BUY_IN_LIVE_MODE_BLOCKED is absent and paper rows predate the LIVE generation, this is stale cumulative telemetry; V5.0.3725 blocks new normal paperBuy() rows in LIVE.\n")
        }

        // ── V3 / skip rate ──────────────────────────────────────────────
        val v3Skipped = s.phaseBlock["V3"] ?: 0L
        if (v3Skipped > 0)
            sb.append("\n  V3_SKIPPED=$v3Skipped — V3 engine skipping tokens (expected during bootstrap/learning phase).\n")

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.951 — THROUGHPUT CHOKE AUDIT
        //
        // The PERFORMANCE_DOCTRINE requires 500-1000 trades/day. When volume
        // is below target this section tells you EXACTLY where the funnel is
        // collapsing — which gate is silently eating the most candidates.
        // Numbers are lifetime-since-restart; pair with TradeDatabase counts
        // to see persisted volume.
        // ═══════════════════════════════════════════════════════════════════
        sb.append("\n===== Throughput choke audit (V5.9.951) =====\n")
        val totalIntake = s.intakeBySource.values.sum()
        val totalLaneEval = s.laneEvalCounts.values.sum()
        val throughputFdgRawRows = s.phaseCounts["FDG"] ?: 0L
        val throughputFdgDecisions = ((s.phaseAllow["FDG"] ?: 0L) + (s.phaseBlock["FDG"] ?: 0L)).takeIf { it > 0L } ?: throughputFdgRawRows
        val totalVerdicts = s.verdictCounts.values.sum()
        val v3Allow = s.phaseAllow["V3"] ?: 0L
        val execGateAllow = s.phaseAllow["EXEC_GATE"] ?: 0L
        val execGateBlock = s.phaseBlock["EXEC_GATE"] ?: 0L
        val recentExecCount = s.recentExecs.size.toLong()
        val acceptedJournalRows = (s.phaseCounts["TRADEJRNL_REC"] ?: s.labelCounts["TRADEJRNL_REC"] ?: 0L)
        sb.append("  intake total:         $totalIntake (sum of all scanner sources)\n")
        sb.append("  lane evaluations:     $totalLaneEval active (${s.laneEvalSuppressedCounts.values.sum()} suppressed by QUALITY-only policy)\n")
        sb.append("  V3 evaluations:       ${v3Allow + v3Skipped}\n")
        sb.append("  FDG active/suppressed:$throughputFdgDecisions / ${s.fdgSuppressedPathCounts.values.sum()}\n")
        if (throughputFdgRawRows > throughputFdgDecisions) sb.append("    └─ raw FDG forensic rows: $throughputFdgRawRows\n")
        sb.append("    ├─ V3 allow:        $v3Allow\n")
        sb.append("    └─ V3 skip:         $v3Skipped\n")
        sb.append("  verdicts produced:    $totalVerdicts\n")
        sb.append("  EXEC_GATE allow:      $execGateAllow\n")
        sb.append("  EXEC_GATE block:      $execGateBlock\n")
        sb.append("  recent execs in ring: $recentExecCount\n")
        // Conversion ratios — operator can see where the funnel is hemorrhaging.
        if (totalIntake > 0L) {
            val intakeToEval = (totalLaneEval.toDouble() / totalIntake * 100.0)
            sb.append("  intake → lane eval:   ${"%.1f".format(intakeToEval)}%  (target >40%)\n")
        }
        if (totalLaneEval > 0L) {
            // V5.9.1343 — HONEST PER-TOKEN RATIO. LANE_EVAL is counted PER LANE (each
            // token fans out to ~4 meme lanes), V3 is counted PER TOKEN. The old ratio
            // divided per-token V3 by per-lane evals (~4x fan-out), reporting a falsely
            // alarming ~8% when the real per-token rate is ~30-40%. Normalise by observed
            // fan-out so it means "of tokens lane-evaluated, what % reached a V3 decision".
            val distinctLanes = s.laneEvalCounts.size.coerceAtLeast(1)
            val avgFanout = (totalLaneEval.toDouble() / distinctLanes).coerceAtLeast(1.0)
            val approxTokensEvaluated = (totalLaneEval.toDouble() / avgFanout).coerceAtLeast(1.0)
            val v3Total2 = (v3Allow + v3Skipped).toDouble()
            val evalToV3PerToken = (v3Total2 / approxTokensEvaluated * 100.0).coerceAtMost(100.0)
            val rawEvalToV3 = (v3Total2 / totalLaneEval * 100.0)
            sb.append("  lane eval → V3:       ${"%.1f".format(evalToV3PerToken)}% per-token  (target >20%)\n")
            sb.append("    └─ raw per-lane:    ${"%.1f".format(rawEvalToV3)}%  ($totalLaneEval evals / $distinctLanes lanes ≈ ${approxTokensEvaluated.toLong()} tokens, fan-out≈${"%.1f".format(avgFanout)})\n")
        }
        val v3Total = v3Allow + v3Skipped
        if (v3Total > 0L) {
            val v3AllowPct = (v3Allow.toDouble() / v3Total * 100.0)
            sb.append("  V3 allow rate:        ${"%.1f".format(v3AllowPct)}%  (target >30%; <15% = audit V3_SKIPPED reasons below)\n")
        }
        // Top 5 block reasons — usually one or two dominate.
        val topBlocks = s.blockReasonCounts.entries.sortedByDescending { it.value }.take(5)
        if (topBlocks.isNotEmpty()) {
            sb.append("  top block reasons:\n")
            for ((reason, n) in topBlocks) {
                sb.append("    • $reason: $n\n")
            }
        }
        // Throughput rate — project from accepted journal rows, not the 30-row recentExec ring.
        val uptimeMs = (s.nowMs - s.startedAtMs).coerceAtLeast(1L)
        val uptimeHr = uptimeMs / 3_600_000.0
        if (uptimeHr >= 0.1 && acceptedJournalRows > 0) {
            val execsPerHour = acceptedJournalRows / uptimeHr
            val execsPerDay = execsPerHour * 24.0
            val band = when {
                execsPerDay in 500.0..1000.0 -> "✅ ON TARGET (500-1000/day band)"
                execsPerDay > 1000.0 -> "🔴 ABOVE TARGET BAND (>1000/day; verify quality/FDG finality and churn)"
                execsPerDay >= 200.0 -> "⚠ BELOW TARGET (need 500+/day; audit selector/slot/lane-eval choke)"
                else -> "🛑 CRITICAL (need 500+/day; check lifecycle uptime + scanner pool)"
            }
            sb.append("  projected execs/day:  ${"%.0f".format(execsPerDay)}  $band (journalRows=$acceptedJournalRows, not 30-row ring)\n")
        }

        // ── V5.9.915 — Self-healing tier surface (H1+H2+H3) ──────────────
        // Show operator: which API hosts are healthy, which keys are flagged
        // DEAD, and how many times AutoEndpointMigrator has fired. This is
        // the ONE place to look when "why is intake stalled?" comes up.

        // V5.9.948 — TokenMetaCache snapshot. Surfaces how much the
        // disk-backed metadata cache is saving the bot. High hit rate =
        // restarts cheap; low hit rate = either fresh device or cache
        // pruning too aggressively. Read-only telemetry; never gates.
        try {
            // V5.9.948 — TokenMetaCache may not be initialized yet on a
            // pre-startup snapshot. snapshotIfPresent() returns null in that
            // case and we silently skip the section.
            val cacheSnap = com.lifecyclebot.engine.TokenMetaCache.snapshotIfPresent()
            if (cacheSnap != null) {
            sb.append("\n===== Token meta cache (V5.9.948) =====\n")
            sb.append("  live rows:       ${cacheSnap.liveRows}\n")
            sb.append("  dirty rows:      ${cacheSnap.dirtyRows}\n")
            sb.append("  read hits:       ${cacheSnap.totalReadHits}\n")
            sb.append("  read misses:     ${cacheSnap.totalReadMisses}\n")
            sb.append("  total writes:    ${cacheSnap.totalWrites}\n")
            sb.append("  hit rate:        ${"%.1f".format(cacheSnap.hitRatePct)}%\n")
            }
        } catch (_: Throwable) { /* best-effort telemetry */ }

        // V5.9.1470 - Slot-health / close-ledger surfacing (IDLE/STUCK fix).
        try {
            sb.append("\n===== Slot health / close ledger (V5.9.1470) =====\n")
            sb.append("  close ledger:    ${com.lifecyclebot.engine.PositionCloseLedger.size()} mints stamped CLOSED\n")
            sb.append("  slot health:     ${com.lifecyclebot.engine.SlotHealthGate.snapshotLine()}\n")
            sb.append("  Read: ghost>0 or forced>20 => buys defer (EXEC_DEFERRED_SLOT_HEALTH) until cleanup; never a permanent block.\n")
        } catch (_: Throwable) { /* best-effort telemetry */ }

        // V5.9.952 — Birdeye budget surfacing. Operator burned 5M Starter cap
        // in 19 days because 5 of 7 call sites bypassed the gate. This section
        // makes the burn rate visible so it never happens silently again.
        try {
            val bsnap = com.lifecyclebot.engine.BirdeyeBudgetGate.snapshot()
            sb.append("\n===== Birdeye budget (V5.9.952) =====\n")
            sb.append("  daily calls:     ${bsnap.callsToday}\n")
            sb.append("  daily CU:        ${bsnap.cuToday}/${bsnap.dailyCap} (${"%.1f".format(bsnap.pctUsed)}%)\n")
            sb.append("  monthly CU:      ${bsnap.cuThisMonth}/5,000,000 (${"%.1f".format(bsnap.monthlyPctUsed)}%)\n")
            sb.append("  entry lockdown:  ${if (bsnap.lockedDown) "🛑 ACTIVE — entries blocked by CU exhaustion" else "✅ off"}\n")
            sb.append("  provider mode:   ${if (bsnap.providerConservation) "🟡 EMERGENCY CONSERVATION — non-emergency Birdeye paused" else if (bsnap.providerLockedDown) "🛑 PROVIDER LOCKDOWN" else "✅ normal"}\n")
            if (bsnap.monthlyPctUsed >= 60.0 && !bsnap.lockedDown && !bsnap.providerConservation) {
                sb.append("  ⚠ scanner-lane throttle active (every 5min instead of every 8s)\n")
            }
        } catch (_: Throwable) { /* best-effort telemetry */ }

        try {
            val apiSnap = ApiHealthMonitor.snapshot()
            if (apiSnap.isNotEmpty()) {
                sb.append("\n===== API health (V5.9.915 ApiHealthMonitor) =====\n")
                val sorted = apiSnap.entries.sortedBy { it.key }
                for ((host, st) in sorted) {
                    val total = st.successes.get() + st.failures4xx.get() + st.failures5xx.get() + st.networkErrors.get()
                    if (total == 0) continue
                    val sr = (st.successRate() * 100).toInt()
                    val avg = st.avgLatencyMs().toInt()
                    val icon = when {
                        sr >= 90 -> "✅"
                        sr >= 60 -> "🟡"
                        else     -> "🔴"
                    }
                    sb.append(String.format(
                        "  %s %-14s sr=%3d%%  avg=%4dms  s=%-5d  4xx=%-3d  5xx=%-3d  net=%-3d\n",
                        icon, host, sr, avg,
                        st.successes.get(), st.failures4xx.get(), st.failures5xx.get(), st.networkErrors.get()
                    ))
                    val lastErr = st.lastErrorMessage.get()
                    if (lastErr != null && st.failures4xx.get() + st.failures5xx.get() + st.networkErrors.get() > 0) {
                        sb.append("       last_err: ").append(lastErr.take(120)).append('\n')
                    }
                }
            }
        } catch (_: Throwable) { /* observability never fails dumpText */ }

        try {
            sb.append("\n===== Provider capability (execution truth) =====\n")
            fun lc(k: String): Long = labelCounts[k]?.get() ?: 0L
            sb.append("  Helius role: HOT_PATH=false critical=false\n")
            sb.append("  Helius degraded: ${if (!KeyValidator.isLive("helius")) "HELIUS_DEGRADED_NON_CRITICAL" else "ok"}\n")
            sb.append("  Jupiter quote/build/confirm: quoteFail=${lc("JUPITER_QUOTE_FAIL")} buildOk=${lc("JUPITER_SWAP_BUILD_OK")} confirmOk=${lc("JUPITER_CONFIRM_OK")} quoteRejected=${lc("JUPITER_QUOTE_REJECTED")}\n")
            sb.append("  Buy terminal: planOk=${lc("BUY_PLAN_OK")} execSelected=${lc("EXEC_SELECTED")} ticket=${lc("EXEC_TICKET_CREATED")} quoteReq=${lc("QUOTE_REQUESTED")} quoteOk=${lc("QUOTE_OK")} swapBuilt=${lc("SWAP_BUILT")} txSigned=${lc("TX_SIGNED")} txSubmitted=${lc("TX_SUBMITTED")} txConfirmed=${lc("TX_CONFIRMED")} journaled=${lc("BUY_JOURNALED")} ok=${lc("BUY_TERMINAL_OK")} fail=${lc("BUY_TERMINAL_FAIL")} duplicateSuppressed=${lc("EXEC_DUPLICATE_SUPPRESSED")} backoff=${lc("EXEC_RETRY_BACKOFF_SET")}\n")
            if (lc("TX_CONFIRMED") > 0L && lc("BUY_JOURNALED") <= 0L) {
                sb.append("  REGRESSION_GUARDS_FAIL: TX_CONFIRMED_WITHOUT_BUY_JOURNALED txConfirmed=${lc("TX_CONFIRMED")} journaled=${lc("BUY_JOURNALED")}\n")
            }
            sb.append("  Buy fail buckets: finality=${lc("BUY_FAILED_FINALITY")} route=${lc("BUY_FAILED_ROUTE")} staleTicket=${lc("BUY_FAILED_STALE_TICKET")} safety=${lc("BUY_FAILED_SAFETY")}\n")
            sb.append("  Pre-attempt suppressions: providerProofBlind=${lc("LIVE_BUY_PREATTEMPT_PROVIDER_PROOF_BLIND")} brainPattern=${lc("LIVE_BUY_PREATTEMPT_BRAIN_PATTERN_SUPPRESSED")} staleAuthPruned=${lc("STALE_AUTH_LOCK_PRUNED")}\n")
            sb.append("  Live lane policy: CYCLIC=liveSoftSized MEME_RING=liveOwnerCollapsed MANIPULATED=dumpSoftSized TREASURY=dumpSoftSized ownerCollapse=${lc("LIVE_RING_OWNER_COLLAPSE")} ownerLane=${lc("MEMETRADER_OWNER_LANE")} dumpSizeEvents=${lc("DUMP_REGIME_LIVE_SIZE_SHAPED")} noPairHeldHot=${lc("INTAKE_NO_PAIR_HELD_HOT_FOR_HYDRATION")}\n")
        } catch (_: Throwable) { /* capability report never fails dumpText */ }

        try {
            val keySnap = KeyValidator.snapshot()
            if (keySnap.isNotEmpty()) {
                sb.append("\n===== Key verdicts (V5.9.915 KeyValidator) =====\n")
                for ((svc, t) in keySnap.entries.sortedBy { it.key }) {
                    val (isLive, http, err) = t
                    val icon = if (isLive) "✅" else "🔴"
                    sb.append(String.format("  %s %-10s live=%-5s  http=%-4d  %s\n",
                        icon, svc, isLive.toString(), http, (err ?: "").take(80)))
                }
            }
        } catch (_: Throwable) {}

        try {
            if (mfeByLane.isNotEmpty()) {
                sb.append("\n===== MFE give-back (V5.9.1378 — peak vs realized per lane) =====\n")
                mfeByLane.entries.sortedBy { it.key }.forEach { (lane, arr) ->
                    val n = arr[2]
                    if (n > 0) {
                        val avgPeak = arr[0] / 100.0 / n
                        val avgReal = arr[1] / 100.0 / n
                        val giveBack = avgPeak - avgReal
                        val flag = if (avgPeak >= 20.0 && giveBack >= 25.0) "  🔴 cutting runners" else ""
                        sb.append(String.format("  %-14s n=%-4d avgPeak=%+.1f%%  avgRealized=%+.1f%%  giveBack=%.1fpp%s\n",
                            lane, n, avgPeak, avgReal, giveBack, flag))
                    }
                }
                sb.append("  Read: large giveBack on a +peak lane ⇒ trail too tight / exit too early (cut runners);\n")
                sb.append("        avgRealized << avgPeak with low WR ⇒ winners fading to losses (hold too long).\n\n")
            }
        } catch (_: Throwable) {}

        try {
            val migSnap = AutoEndpointMigrator.snapshot()
            if (migSnap.isNotEmpty()) {
                sb.append("\n===== Endpoint migrations (V5.9.915 AutoEndpointMigrator) =====\n")
                for ((dead, pair) in migSnap.entries.sortedBy { it.key }) {
                    val (live, count) = pair
                    sb.append(String.format("  ↪ %-32s → %-32s  (rewrites=%d)\n", dead, live, count))
                }
            }
        } catch (_: Throwable) {}

        return sb.toString()
    }

    /** Reset all counters — operator-triggered "fresh capture" for export. */
    fun reset() {
        phaseCounts.clear()
        phaseAllow.clear()
        phaseBlock.clear()
        verdictCounts.clear()
        labelCounts.clear()
        intakeBySource.clear()
        laneEvalCounts.clear()
        blockReasonCounts.clear()
        v3RejectReasonCounts.clear()
        symbolIntakeCounts.clear()
        anrStackCounts.clear()
        anrHintCount.set(0)
        anrSamplesTaken.set(0)
        maxFrameGapMs.set(0L)
        totalFrameStallMs.set(0L)
        startedAtMs.set(System.currentTimeMillis())
        ring.clear()
        ringSize.set(0)
        recentExecs.clear()
        recentExecsSize.set(0)
        recentCycleMsSamples.clear()
        recentCycleMsSize.set(0)
        totalCycleMs.set(0L)
        cycleCount.set(0L)
        maxCycleMs.set(0L)
    }

    // ════════════════════════════════════════════════════════════════
    // Internals
    // ════════════════════════════════════════════════════════════════

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        var c = map[key]
        if (c == null) {
            c = map.computeIfAbsent(key) { AtomicLong(0L) }
        }
        c.incrementAndGet()
    }

    /** V5.9.1321 — public label-bump helper for FdgRouteVerdict + future modules.
     *  Routes through `labelCounts` so the snapshot dump picks it up automatically. */
    fun labelInc(key: String) {
        if (!attached) return
        bump(labelCounts, key)
    }

    // V5.9.1378 (P0 #9) — record an MFE observation for [lane]: the peak gain reached
    // and the realized close pnl (both %). Accumulated as fixed-point x100 sums.
    fun recordMfe(lane: String, peakPct: Double, realizedPct: Double) {
        if (!attached) return
        try {
            val arr = mfeByLane.getOrPut(lane) { LongArray(3) }
            synchronized(arr) {
                arr[0] += (peakPct * 100.0).toLong()
                arr[1] += (realizedPct * 100.0).toLong()
                arr[2] += 1L
            }
        } catch (_: Throwable) {}
    }

    private fun appendEvent(ev: Event) {
        ring.addLast(ev)
        if (ringSize.incrementAndGet() > RING_CAP) {
            ring.pollFirst()
            ringSize.decrementAndGet()
        }
    }

    @Suppress("unused")
    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()
}
