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
        lastRotationMs = nowMs
        demandByLane.remove(l, d)
        rotations.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OPPORTUNITY_ROTATION_8098_$l") } catch (_: Throwable) {}
        return true
    }

    fun statusLine8098(): String = "demands=${demands.get()} rotations=${rotations.get()} waiting=${demandByLane.keys.joinToString(",").ifBlank { "-" }}"
}
