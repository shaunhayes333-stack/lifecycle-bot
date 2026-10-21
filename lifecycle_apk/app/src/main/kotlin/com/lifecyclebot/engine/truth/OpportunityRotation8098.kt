package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8098 §A_BETTER_TRADE_TAKES_THE_WORST_SLOT.
 *
 * Owner: "if the bot identifies a better trading opportunity force sell the poorest performing currently held trade to
 * free new lanes". 5.0.8095 refused LANE_SLOT_CAP_7807 63 times in four minutes while the lane's slots held flat or
 * red positions; CAPITAL_ROTATION_7948 frees capital only when the wallet cannot fund one order, never a full lane.
 *
 * DEMAND: LiveRiskPolicy7807.record — a candidate with a PROVEN positive edge (its setup / cell / kind Kelly share > 0)
 * refused only because its lane is full.
 * VICTIM: the poorest-performing live position in that lane (lowest verified PnL), never: a moonbag tail, a runner
 * (peak >= +[RUNNER_PEAK_PCT] or now >= +[MAX_VICTIM_PNL_PCT]), a position under [MIN_AGE_MS] old, or one without a
 * fresh verified mark. One rotation per [COOLDOWN_MS]; the demand expires after [DEMAND_TTL_MS].
 * The sell goes through requestSell like any exit (moonbag set-aside, verification, journal); lifecycle holds let it
 * through (LifecycleHold8065.passes8065).
 */
object OpportunityRotation8098 {
    const val REASON = "OPPORTUNITY_ROTATION_8098"
    const val RUNNER_PEAK_PCT = 20.0
    const val MAX_VICTIM_PNL_PCT = 10.0
    const val MIN_AGE_MS = 3L * 60_000L
    private const val DEMAND_TTL_MS = 90_000L
    private const val COOLDOWN_MS = 2L * 60_000L

    private class Demand(val mint: String, val atMs: Long)

    private val demandByLane = ConcurrentHashMap<String, Demand>()
    @Volatile private var lastRotationMs = 0L
    private val demands = AtomicLong(0)
    private val rotations = AtomicLong(0)

    private fun lane(raw: String?): String = LiveRiskPolicy7807.canonicalLane(raw)

    // V5.0.8103 — owner: rotation also at the total live cap (TOTAL_LIVE_SLOTS_7807 = 20). A proven candidate whose own
    // lane has room but the whole book is full asks for the poorest position of ANY lane.
    const val BOOK_8103 = "BOOK_8103"

    /** LiveRiskPolicy7807.record: a proven candidate passed its lane but the total live book is full. */
    fun noteBookDemand8103(mint: String, edgeKellyFrac: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!(edgeKellyFrac.isFinite() && edgeKellyFrac > 0.0) || mint.isBlank()) return
        demandByLane[BOOK_8103] = Demand(mint, nowMs)
        demands.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_DEMAND_8103_BOOK") } catch (_: Throwable) {}
    }

    /** Is a proven candidate waiting on the total book (any lane's position may be rotated)? */
    fun bookDemand8103(nowMs: Long = System.currentTimeMillis()): Boolean =
        demandByLane[BOOK_8103]?.let { nowMs - it.atMs in 0L..DEMAND_TTL_MS } == true

    /**
     * V5.0.8262 — FinalDecisionGate (via MemeChokes7951): an APPROVED live entry the wallet cannot fund
     * (ROUTABLE_CAPACITY / SIZE_NOT_EXECUTABLE). It already cleared admission, hard safety and economics; on an unlearned
     * book it has no proven edge fraction, so [noteBookDemand8103] never fired (8261: demands=0, 63 starved). The
     * entry itself is the demand; who may be sold for it is still [victimEligible8098] + [shouldRotate8098].
     */
    fun noteCapitalDemand8262(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val cur = demandByLane[BOOK_8103]
        if (cur != null && cur.mint == mint && nowMs - cur.atMs in 0L..DEMAND_TTL_MS) return
        demandByLane[BOOK_8103] = Demand(mint, nowMs)
        demands.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_DEMAND_8262_CAPITAL") } catch (_: Throwable) {}
    }

    /** LiveRiskPolicy7807.record: a proven candidate was refused only for its lane's slots. */
    fun noteDemand8098(laneRaw: String, mint: String, edgeKellyFrac: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!(edgeKellyFrac.isFinite() && edgeKellyFrac > 0.0) || mint.isBlank()) return
        demandByLane[lane(laneRaw)] = Demand(mint, nowMs)
        demands.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_DEMAND_8098_${lane(laneRaw)}") } catch (_: Throwable) {}
    }

    /** Is a proven candidate waiting on [laneRaw]'s slots right now? (cheap; checked before ranking the lane) */
    fun hasDemand8098(laneRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        demandByLane[lane(laneRaw)]?.let { nowMs - it.atMs in 0L..DEMAND_TTL_MS } == true

    /** Pure: may a held position be rotated out at all? */
    fun victimEligible8098(pnlPct: Double, peakPct: Double, ageMs: Long, isTail: Boolean, markFresh: Boolean): Boolean =
        !isTail && markFresh && pnlPct.isFinite() && pnlPct < MAX_VICTIM_PNL_PCT &&
            (!peakPct.isFinite() || peakPct < RUNNER_PEAK_PCT) && ageMs >= MIN_AGE_MS

    data class Held(val mint: String, val pnlPct: Double)

    /** Pure: the poorest performer among eligible [held] positions, or null. */
    fun poorest8098(held: List<Held>): String? = held.filter { it.pnlPct.isFinite() }.minByOrNull { it.pnlPct }?.mint

    /**
     * Executor (per held live position tick): should THIS position be sold now to free its lane for the waiting
     * candidate? [laneHeld] lists the lane's live positions with their verified PnL and eligibility.
     */
    fun shouldRotate8098(mint: String, laneRaw: String, candidates: List<Held>, nowMs: Long = System.currentTimeMillis()): Boolean {
        val l = if (laneRaw == BOOK_8103) BOOK_8103 else lane(laneRaw)
        val d = demandByLane[l] ?: return false
        if (nowMs - d.atMs !in 0L..DEMAND_TTL_MS) { demandByLane.remove(l, d); return false }
        if (d.mint == mint || nowMs - lastRotationMs < COOLDOWN_MS) return false
        if (poorest8098(candidates) != mint) return false
        if (active8270.values.any { it.victim == mint && it.state.live }) return false   // one rotation per victim
        lastRotationMs = nowMs
        demandByLane.remove(l, d)
        active8270[mint] = Rotation8270(successor = d.mint, victim = mint, lane = l, requestedAtMs = d.atMs, state = State8270.RESERVED, updatedAtMs = nowMs)
        reservations8270.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_RESERVED_8270_$l") } catch (_: Throwable) {}
        return true
    }

    // ── V5.0.8270 — rotation is a lifecycle, not a requestSell call ──────────────────────────────────────────────────
    // 8098 counted a rotation and consumed the demand the moment the poorest position was *asked* to sell. A refused or
    // unbroadcast sell left the successor with no demand and the report with a "rotation" that freed nothing. Now:
    // REQUESTED (demand) → RESERVED (victim chosen) → SELL_PENDING (sell accepted) → CAPITAL_CONFIRMED (that mint's sell
    // finalized on chain) → SUCCESSOR_REEVALUATION (the successor is due a fresh look); RETRY restores the demand when
    // the sell was not accepted; EXPIRED when nothing confirms inside the window. `rotations` counts CAPITAL_CONFIRMED only.
    enum class State8270(val live: Boolean) { RESERVED(true), SELL_PENDING(true), CAPITAL_CONFIRMED(false), SUCCESSOR_REEVALUATION(false), RETRY(false), EXPIRED(false) }
    private class Rotation8270(val successor: String, val victim: String, val lane: String, val requestedAtMs: Long, @Volatile var state: State8270, @Volatile var updatedAtMs: Long)
    private const val RESERVATION_TTL_MS_8270 = 10L * 60_000L
    private val active8270 = ConcurrentHashMap<String, Rotation8270>()
    private val reservations8270 = AtomicLong(0)
    private val retries8270 = AtomicLong(0)
    private val expired8270 = AtomicLong(0)
    private val successorDue8270 = ConcurrentHashMap<String, Long>()

    /** Executor: the victim's sell was asked for; [accepted] = a sell is actually pending/confirmed, not refused. */
    fun sellRequested8270(victim: String, accepted: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val r = active8270[victim] ?: return
        if (r.state != State8270.RESERVED) return
        if (accepted) { r.state = State8270.SELL_PENDING; r.updatedAtMs = nowMs; return }
        r.state = State8270.RETRY; r.updatedAtMs = nowMs
        retries8270.incrementAndGet()
        lastRotationMs = 0L
        demandByLane.putIfAbsent(r.lane, Demand(r.successor, nowMs))      // the successor's demand is restored
        active8270.remove(victim, r)
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_RETRY_8270") } catch (_: Throwable) {}
    }

    /** SellFinalizationCoordinator: this mint's sell is finalized on chain — the capital is real. */
    fun capitalConfirmed8270(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val r = active8270[mint] ?: return
        if (!r.state.live) return
        r.state = State8270.CAPITAL_CONFIRMED; r.updatedAtMs = nowMs
        rotations.incrementAndGet()
        r.state = State8270.SUCCESSOR_REEVALUATION
        successorDue8270[r.successor] = nowMs
        active8270.remove(mint, r)
        try { com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(r.successor, nowMs) } catch (_: Throwable) {}   // refreshed first
        try {
            PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_CAPITAL_CONFIRMED_8270_${r.lane}")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("OPPORTUNITY_ROTATION_CAPITAL_CONFIRMED_8270",
                "victim=${mint.take(10)} successor=${r.successor.take(10)} lane=${r.lane} waitedMs=${nowMs - r.requestedAtMs} action=successor_due_fresh_evaluation")
        } catch (_: Throwable) {}
    }

    /** FDG / coordinator: is [mint] a rotation successor whose capital was just confirmed? Consumed on read. */
    fun successorReevaluationDue8270(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = successorDue8270.remove(mint) ?: return false
        return nowMs - at in 0L..DEMAND_TTL_MS
    }

    private fun expire8270(nowMs: Long) {
        for ((k, r) in active8270) if (r.state.live && nowMs - r.updatedAtMs > RESERVATION_TTL_MS_8270) {
            r.state = State8270.EXPIRED; expired8270.incrementAndGet(); active8270.remove(k, r)
            try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_EXPIRED_8270") } catch (_: Throwable) {}
        }
    }

    fun statusLine8098(nowMs: Long = System.currentTimeMillis()): String {
        expire8270(nowMs)
        return "demands=${demands.get()} reserved=${reservations8270.get()} rotations=${rotations.get()} retries=${retries8270.get()} expired=${expired8270.get()} " +
            "active=${active8270.values.joinToString(",") { "${it.victim.take(6)}>${it.successor.take(6)}:${it.state}" }.ifBlank { "-" }} " +
            "waiting=${demandByLane.keys.joinToString(",").ifBlank { "-" }}"
    }
}
