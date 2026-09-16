package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6833 §RUNTIME_TUNE — operator directive (Feb 2026, atop 6832):
 *
 *   §2 EXPRESS BLEED CONTROL
 *     - Keep LaneExpectancyDamper size=0.18
 *     - Add admission multiplier ~=0.25 (probe-scale)
 *     - liveP < 0.30                  => PROBE_ONLY
 *     - expectedPnL < 0 AND liveP<0.30 => NO_BUY
 *     - EXPRESS S41-60                => LAB/PROBE_ONLY until reproven
 *     - Raw score MUST NOT override strongly negative learned edge.
 *
 *   §3 EDGE CAPACITY REDISTRIBUTION
 *     - QUALITY size × 1.125 (mid of 1.10..1.15)
 *     - CORE size × 1.10
 *     - PROJECT_SNIPER size × 1.10
 *     - MOONSHOT stays damped × 0.67 pending more terminals.
 *     - Preserves hard safety/liquidity/rug gates — this is a
 *       multiplicative factor consumed by OrderSizeResolver AFTER the
 *       adaptive/pressure chain and BEFORE the risk/cash/lane clamps.
 *
 *   §4 INVENTORY PRESSURE STAIRCASE (extended in
 *       InventoryPressureGovernor6829 itself)
 *     - >= 35 begin progressive throttling
 *     - >= 45 halve weak-entry admissions
 *     - >= 55 HIGH_EDGE only
 *     - >= 70 exceptional entries only
 *     - HIGH_EDGE composite = learned pWin * expectedPnl scaled by source
 *       quality — NOT lane score alone.
 *
 *   §5 EXIT THROUGHPUT
 *     - opens > 45 OR cashRatio < 20% => raise exit-worker priority
 *     - Never tightens SL just to churn.
 *
 *   §6 MARK -> SIZE REPAIR (advisory)
 *     - Any (validSource, missingExecutableMark) sample must be routed to
 *       a terminal reject reason; phantom-sized-only counts get published
 *       as PHANTOM_SIZED_REJECTED_6833.
 *
 *   §7 SHITCOIN TICKET HANDOFF (advisory)
 *     - Track sized -> ticket -> exec conversions for SHITCOIN and
 *       surface the causal choke as SHITCOIN_HANDOFF_STALLED_6833 so the
 *       operator can see the drop *without* changing scoring.
 *
 *   §8 ACCOUNTING BOUNDARY (advisory)
 *     - CanonicalPositionAuthority + PaperAccountLedger remain the sole
 *       capital authority. Any code path that would read forensic
 *       journal delta into a size/reason/weight must call
 *       assertNotInSizingPath6833(...) which records a violation.
 *
 * ALL RULES ADVISORY / MULTIPLICATIVE — no global caps, no CASH_STARVED
 * relaxation, no bot-loop cadence change.
 */
object RuntimeTune6833 {

    // ── §2 EXPRESS BLEED CONTROL ────────────────────────────────────────

    const val EXPRESS_ADMISSION_MULT = 0.25
    const val EXPRESS_LIVE_P_PROBE_ONLY_MAX = 0.30
    const val EXPRESS_LAB_SCORE_BAND_LO = 41
    const val EXPRESS_LAB_SCORE_BAND_HI = 60

    enum class ExpressVerdict { ALLOW, PROBE_ONLY, NO_BUY }

    /**
     * Consult BEFORE final sizing when the lane is EXPRESS.
     *  liveP is the learned probability of positive terminal (0..1);
     *  expectedPnl is the learned expectancy (negative -> bleeding);
     *  rawScore is the (adaptively-blended) executor score for the setup.
     *
     * Return semantics:
     *   ALLOW       — no additional damping beyond the pre-existing
     *                 LaneExpectancyDamper (0.18) + any pressure damping.
     *   PROBE_ONLY  — caller applies EXPRESS_ADMISSION_MULT (0.25) on top.
     *   NO_BUY      — caller must not size; produce a terminal reject.
     */
    fun expressAdmissionVerdict(
        liveP: Double,
        expectedPnl: Double,
        rawScore: Int,
    ): ExpressVerdict {
        val pFinite = liveP.isFinite() && liveP in 0.0..1.0
        val evFinite = expectedPnl.isFinite()
        val labBand = rawScore in EXPRESS_LAB_SCORE_BAND_LO..EXPRESS_LAB_SCORE_BAND_HI

        // Hardest rule: negative learned expectancy AND weak probability
        // must never buy, regardless of the raw score.
        if (pFinite && evFinite &&
            expectedPnl < 0.0 &&
            liveP < EXPRESS_LIVE_P_PROBE_ONLY_MAX
        ) {
            try {
                PipelineHealthCollector.labelInc("EXPRESS_ADMISSION_NO_BUY_6833")
            } catch (_: Throwable) {}
            return ExpressVerdict.NO_BUY
        }

        // Weak probability alone -> probe (size × 0.25).
        if (pFinite && liveP < EXPRESS_LIVE_P_PROBE_ONLY_MAX) {
            try { PipelineHealthCollector.labelInc("EXPRESS_ADMISSION_PROBE_ONLY_LIVEP_6833") } catch (_: Throwable) {}
            return ExpressVerdict.PROBE_ONLY
        }

        // S41-60 must be lab/probe until reproven — even at ok liveP.
        if (labBand) {
            try { PipelineHealthCollector.labelInc("EXPRESS_ADMISSION_LAB_BAND_6833") } catch (_: Throwable) {}
            return ExpressVerdict.PROBE_ONLY
        }

        return ExpressVerdict.ALLOW
    }

    /**
     * Multiplicative form for consumers that only apply size damping
     * (they can't reject). ALLOW=1.0, PROBE_ONLY=0.25, NO_BUY=0.0.
     * Callers that get 0.0 back must convert to an explicit terminal
     * reject rather than emit a zero-sized ticket (see §6).
     */
    fun expressAdmissionMultiplier(
        liveP: Double,
        expectedPnl: Double,
        rawScore: Int,
    ): Double = when (expressAdmissionVerdict(liveP, expectedPnl, rawScore)) {
        ExpressVerdict.ALLOW -> 1.0
        ExpressVerdict.PROBE_ONLY -> EXPRESS_ADMISSION_MULT
        ExpressVerdict.NO_BUY -> 0.0
    }

    // ── §3 EDGE CAPACITY REDISTRIBUTION ─────────────────────────────────

    /**
     * Lane redistribution multiplier — additive/multiplicative factor
     * applied inside the size resolver AFTER the adaptive chain and
     * BEFORE the hard caps. Hard risk/cash/lane clamps still bound the
     * output; this can never manufacture an oversized order.
     */
    fun laneCapacityMultiplier(laneName: String): Double {
        val key = laneName.trim().uppercase()
        return when (key) {
            "QUALITY" -> 1.125
            "CORE" -> 1.10
            "PROJECT_SNIPER", "PROJECT-SNIPER" -> 1.10
            "MOONSHOT" -> 0.67
            else -> 1.0
        }.also { m ->
            if (kotlin.math.abs(m - 1.0) > 1e-6) {
                try {
                    PipelineHealthCollector.labelInc("LANE_CAPACITY_REDIST_6833_${key.take(24)}")
                } catch (_: Throwable) {}
            }
        }
    }

    // ── §4 HIGH_EDGE COMPOSITE (used by InventoryPressureGovernor6829
    //      staircase for the 55+/70+ tiers) ─────────────────────────────

    /**
     * Composite edge signal built from learned probability, expected PnL
     * and source quality. Bounded [-1, +1]. Used as the HIGH_EDGE gate at
     * >=55 opens and the EXCEPTIONAL_EDGE gate at >=70 opens.
     *
     * Deliberately NOT lane-score based — the operator directive is
     * explicit: "must use learned pWin/EV + source quality, not lane
     * score alone."
     */
    fun edgeComposite(
        livePWin: Double,
        expectedPnl: Double,
        sourceQuality: Double,
    ): Double {
        val p = if (livePWin.isFinite()) livePWin.coerceIn(0.0, 1.0) else 0.5
        val ev = if (expectedPnl.isFinite()) expectedPnl.coerceIn(-1.0, 1.0) else 0.0
        val sq = if (sourceQuality.isFinite()) sourceQuality.coerceIn(0.0, 1.0) else 0.5
        // p in [0,1] centered on 0.5; EV in [-1,+1]; source multiplies.
        val centeredP = (p - 0.5) * 2.0   // [-1, +1]
        val raw = (0.55 * centeredP + 0.45 * ev) * (0.5 + 0.5 * sq)
        return raw.coerceIn(-1.0, 1.0)
    }

    /** HIGH_EDGE gate for the 55+ tier. */
    fun isHighEdge(composite: Double): Boolean = composite >= 0.35

    /** EXCEPTIONAL gate for the 70+ tier. */
    fun isExceptionalEdge(composite: Double): Boolean = composite >= 0.60

    // ── §5 EXIT WORKER PRIORITY BOOST ───────────────────────────────────

    /**
     * Callers running the exit worker loop poll this to decide whether
     * to raise their scheduling priority. True when opens > 45 OR the
     * cash ratio drops below 20%.
     */
    fun exitWorkerShouldBoost(openPositions: Int, cashRatio: Double): Boolean {
        val boost = openPositions > 45 || (cashRatio.isFinite() && cashRatio < 0.20)
        if (boost) {
            try {
                PipelineHealthCollector.labelInc("EXIT_WORKER_PRIORITY_BOOST_REQUESTED_6833")
            } catch (_: Throwable) {}
        }
        return boost
    }

    // ── §6 MARK -> SIZE CONTRACT (advisory) ─────────────────────────────

    private val phantomSizedRejected = AtomicLong(0L)
    private val markContractHonored = AtomicLong(0L)

    /**
     * Consult once the mark authority has produced (or refused to
     * produce) an executable canonical mark for a validated source. When
     * `sourceEvidenceValid` is true, we require either
     * `executableMarkProduced` to be true OR the caller to record an
     * explicit terminal reject (`explicitTerminalReject=true`). A "sized
     * without mark" leak is counted and blocked.
     *
     * Returns true if the caller may proceed to sizing.
     */
    fun markContractAllowsSizing6833(
        sourceEvidenceValid: Boolean,
        executableMarkProduced: Boolean,
        explicitTerminalReject: Boolean,
        laneName: String,
    ): Boolean {
        if (!sourceEvidenceValid) return executableMarkProduced
        return if (executableMarkProduced) {
            markContractHonored.incrementAndGet()
            true
        } else if (explicitTerminalReject) {
            // Caller resolved this to a terminal reject — contract honored.
            markContractHonored.incrementAndGet()
            false
        } else {
            phantomSizedRejected.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PHANTOM_SIZED_REJECTED_6833")
                PipelineHealthCollector.labelInc(
                    "PHANTOM_SIZED_REJECTED_6833_${laneName.trim().uppercase().take(24)}"
                )
                ForensicLogger.lifecycle(
                    "PHANTOM_SIZED_REJECTED_6833",
                    "lane=$laneName sourceEvidenceValid=true executableMark=false " +
                        "terminalReject=false action=block_sizing_until_mark_or_reject",
                )
            } catch (_: Throwable) {}
            false
        }
    }

    fun phantomSizedRejectedCount6833(): Long = phantomSizedRejected.get()
    fun markContractHonoredCount6833(): Long = markContractHonored.get()

    // ── §7 SHITCOIN TICKET HANDOFF (advisory) ───────────────────────────

    private data class HandoffCounters(
        val sized: AtomicLong = AtomicLong(0L),
        val ticket: AtomicLong = AtomicLong(0L),
        val exec: AtomicLong = AtomicLong(0L),
        val stalled: AtomicLong = AtomicLong(0L),
    )

    private val handoff = ConcurrentHashMap<String, HandoffCounters>()

    private fun counters(laneName: String): HandoffCounters =
        handoff.computeIfAbsent(laneName.trim().uppercase()) { HandoffCounters() }

    fun recordSized6833(laneName: String) { counters(laneName).sized.incrementAndGet() }
    fun recordTicket6833(laneName: String) { counters(laneName).ticket.incrementAndGet() }
    fun recordExec6833(laneName: String) { counters(laneName).exec.incrementAndGet() }

    /**
     * Call this periodically (post-tick, post-audit). Emits
     * SHITCOIN_HANDOFF_STALLED_6833 when the SHITCOIN lane has produced
     * sized candidates without any tickets/execs — this makes the causal
     * choke visible without touching scoring.
     */
    fun evaluateHandoffChoke6833() {
        val sc = handoff["SHITCOIN"] ?: return
        val sized = sc.sized.get()
        val tkt = sc.ticket.get()
        val exec = sc.exec.get()
        if (sized >= 5L && tkt == 0L && exec == 0L) {
            sc.stalled.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("SHITCOIN_HANDOFF_STALLED_6833")
                ForensicLogger.lifecycle(
                    "SHITCOIN_HANDOFF_STALLED_6833",
                    "lane=SHITCOIN sized=$sized ticket=0 exec=0 " +
                        "action=investigate_sized_to_ticket_causal_link",
                )
            } catch (_: Throwable) {}
        }
    }

    data class HandoffSnapshot(
        val laneName: String,
        val sized: Long,
        val ticket: Long,
        val exec: Long,
        val stalledEvents: Long,
    )

    fun handoffSnapshot6833(laneName: String): HandoffSnapshot {
        val c = counters(laneName)
        return HandoffSnapshot(
            laneName = laneName.trim().uppercase(),
            sized = c.sized.get(),
            ticket = c.ticket.get(),
            exec = c.exec.get(),
            stalledEvents = c.stalled.get(),
        )
    }

    // ── §8 ACCOUNTING BOUNDARY (advisory) ───────────────────────────────

    private val accountingBoundaryViolations = AtomicLong(0L)

    /**
     * Call from any code path that is about to feed a forensic journal
     * delta into sizing, weighting or reason emission. Returns true if
     * the caller SHOULD NOT USE the value; also records a violation
     * counter so the operator can see who is trying to leak diagnostic
     * data into the economic authority.
     */
    fun assertForensicNotInSizingPath6833(callerTag: String): Boolean {
        accountingBoundaryViolations.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ACCOUNTING_BOUNDARY_VIOLATION_6833")
            ForensicLogger.lifecycle(
                "ACCOUNTING_BOUNDARY_VIOLATION_6833",
                "caller=$callerTag action=refuse_forensic_delta_in_sizing_path",
            )
        } catch (_: Throwable) {}
        return true
    }

    fun accountingBoundaryViolations6833(): Long = accountingBoundaryViolations.get()

    // ── Diagnostic dump ─────────────────────────────────────────────────

    fun statusLine6833(): String {
        val sc = handoffSnapshot6833("SHITCOIN")
        return "RuntimeTune6833 " +
            "phantomSizedRejected=${phantomSizedRejectedCount6833()} " +
            "markContractHonored=${markContractHonoredCount6833()} " +
            "SHITCOIN[sized=${sc.sized},ticket=${sc.ticket},exec=${sc.exec},stalled=${sc.stalledEvents}] " +
            "acctBoundaryViolations=${accountingBoundaryViolations6833()}"
    }

    internal fun clearForTest() {
        phantomSizedRejected.set(0L)
        markContractHonored.set(0L)
        accountingBoundaryViolations.set(0L)
        handoff.clear()
    }
}
