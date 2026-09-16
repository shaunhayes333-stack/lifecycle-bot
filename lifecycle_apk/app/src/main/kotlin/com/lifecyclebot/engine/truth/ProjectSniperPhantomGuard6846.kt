package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6846 §PROJECT_SNIPER_PHANTOM_GUARD — operator directive Feb 2026.
 *
 * PROBLEM (5.0.6845 operator diagnosis):
 *   PROJECT_SNIPER ownerSelected=0 sizedExecutable=0 ticket=0 exec=0
 *   BUT the recent journal contained many PROJECT_SNIPER BUYs and
 *   phantomSizedOnly=90 with J_PHANTOM_SIZED_ONLY audit failing.
 *
 * ROOT SHAPE — a side path is writing PROJECT_SNIPER journal opens from
 *   sizing telemetry / hypothesis / mark-ready events / legacy handoff /
 *   inferred ticket state, bypassing the canonical causal chain:
 *
 *     ownerSelected → canonical intent → sealed FDG → authoritative
 *     mark → canonical size → ticket → executor → canonical open →
 *     terminal
 *
 * PURPOSE — refuse to journal or open a PROJECT_SNIPER position (and,
 * generally, ANY lane the caller registers with `enforceLane6846`)
 * unless every stage of the chain has been stamped for the same
 * (positionId, mint, generation).
 *
 * SCOPE (§8 must remain untouched):
 *   * Does NOT modify: canonical accounting, mark sanity, same-mint
 *     dedup, inventory caps, reconciler, FDG sealing, terminal
 *     idempotency, capital conservation, stale-ticket protection.
 *   * Does NOT touch existing successful paths. Only refuses journal
 *     BUY events whose causal chain is incomplete.
 *
 * USAGE:
 *   * The canonical pipeline calls `stamp*` at each successful stage.
 *   * The journal writer calls `allowJournalBuy(...)` before writing.
 *     Missing stamps → reject + PHANTOM_LANE_BUY_REJECTED_6846.
 *
 * Enforced lanes default to just PROJECT_SNIPER; more can be added
 * without a new authority.
 */
object ProjectSniperPhantomGuard6846 {

    private val enforcedLanes = java.util.concurrent.CopyOnWriteArraySet(setOf("PROJECT_SNIPER"))

    private data class Chain(
        val ownerSelected: Boolean = false,
        val intent: Boolean = false,
        val fdgSealed: Boolean = false,
        val markAuthoritative: Boolean = false,
        val sized: Boolean = false,
        val ticket: Boolean = false,
        val executor: Boolean = false,
        val open: Boolean = false,
    ) {
        fun complete(): Boolean = ownerSelected && intent && fdgSealed && markAuthoritative &&
            sized && ticket && executor && open
    }

    /** Chain state keyed by (positionId::mint::generation). */
    private val chains = ConcurrentHashMap<String, Chain>()

    private val phantomRejected = AtomicLong(0L)
    private val chainCompleted = AtomicLong(0L)
    private val unenforcedPassthrough = AtomicLong(0L)

    fun enforceLane6846(lane: String) {
        enforcedLanes.add(lane.trim().uppercase())
    }

    private fun keyOf(positionId: String, mint: String, generation: Long): String =
        "${positionId.take(28)}::${mint.take(28)}::$generation"

    private fun laneEnforced(lane: String): Boolean =
        enforcedLanes.contains(lane.trim().uppercase())

    // ── stamps (idempotent; late stamps only ratchet forward) ─────────
    fun stampOwnerSelected(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(ownerSelected = true)) { a, _ -> a.copy(ownerSelected = true) }
    }

    fun stampIntent(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(intent = true)) { a, _ -> a.copy(intent = true) }
    }

    fun stampFdgSealed(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(fdgSealed = true)) { a, _ -> a.copy(fdgSealed = true) }
    }

    fun stampMarkAuthoritative(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(markAuthoritative = true)) { a, _ -> a.copy(markAuthoritative = true) }
    }

    fun stampSized(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(sized = true)) { a, _ -> a.copy(sized = true) }
    }

    fun stampTicket(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(ticket = true)) { a, _ -> a.copy(ticket = true) }
    }

    fun stampExecutor(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(executor = true)) { a, _ -> a.copy(executor = true) }
    }

    fun stampOpen(lane: String, positionId: String, mint: String, generation: Long) {
        if (!laneEnforced(lane)) return
        val k = keyOf(positionId, mint, generation)
        chains.merge(k, Chain(open = true)) { a, _ -> a.copy(open = true) }
        val cur = chains[k]
        if (cur?.complete() == true) chainCompleted.incrementAndGet()
    }

    /**
     * Called immediately before a journal writer emits a BUY event for
     * the enforced lane. Returns true if the causal chain is complete
     * (i.e. every stage has been stamped for this positionId/mint/gen).
     * Returns false + increments PHANTOM_LANE_BUY_REJECTED_6846 if the
     * chain is not complete; the writer must skip.
     *
     * When the lane is not enforced, always returns true (passthrough).
     */
    fun allowJournalBuy(lane: String, positionId: String, mint: String, generation: Long): Boolean {
        if (!laneEnforced(lane)) {
            unenforcedPassthrough.incrementAndGet()
            return true
        }
        val k = keyOf(positionId, mint, generation)
        val c = chains[k]
        val ok = c?.complete() == true
        if (!ok) {
            phantomRejected.incrementAndGet()
            try {
                val laneKey = lane.trim().uppercase()
                PipelineHealthCollector.labelInc("PHANTOM_LANE_BUY_REJECTED_6846")
                PipelineHealthCollector.labelInc("PHANTOM_LANE_BUY_REJECTED_6846_$laneKey")
                ForensicLogger.lifecycle(
                    "PHANTOM_LANE_BUY_REJECTED_6846",
                    "lane=$laneKey positionId=${positionId.take(12)} mint=${mint.take(10)} " +
                        "generation=$generation chain=${c ?: "null"}",
                )
            } catch (_: Throwable) {}
        }
        return ok
    }

    data class Summary(
        val phantomRejected: Long,
        val chainCompleted: Long,
        val unenforcedPassthrough: Long,
        val activeChains: Int,
    )

    fun summary(): Summary = Summary(
        phantomRejected = phantomRejected.get(),
        chainCompleted = chainCompleted.get(),
        unenforcedPassthrough = unenforcedPassthrough.get(),
        activeChains = chains.size,
    )

    fun statusLine(): String {
        val s = summary()
        return "ProjectSniperPhantomGuard6846 rejected=${s.phantomRejected} " +
            "completed=${s.chainCompleted} passthrough=${s.unenforcedPassthrough} " +
            "activeChains=${s.activeChains} enforcedLanes=${enforcedLanes.joinToString(",")}"
    }

    internal fun clearForTest() {
        chains.clear()
        phantomRejected.set(0L)
        chainCompleted.set(0L)
        unenforcedPassthrough.set(0L)
    }
}
