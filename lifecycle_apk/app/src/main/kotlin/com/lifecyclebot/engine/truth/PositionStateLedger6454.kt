package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6454 §P0 — TERMINAL SELL CAS AT SIDE-EFFECT DOOR.
 *
 * OPERATOR MANDATE:
 *   "Wire PositionStateLedger.reserveTerminalSell() BEFORE every
 *    paper/live terminal SELL. PositionId mandatory; remove blank-ID
 *    fail-open. Do NOT use mint as PositionId.
 *
 *    Only: OPEN/PARTIAL -> CLOSING via CAS
 *          CLOSING -> CLOSED via CAS after settlement.
 *
 *    confirmTerminalSell must:
 *      - CAS CLOSING -> CLOSED
 *      - return false if already CLOSED
 *      - NEVER increment terminal count twice
 *      - NEVER journal/account/reward a rejected duplicate
 *
 *    DsXR94/2cxRDE/2JLR9u repeated SELL pattern must become impossible."
 *
 * V5.0.6702 — CLOSING now has an age. Previously a crashed/cancelled paper
 * close owner could leave Lifecycle.CLOSING forever; every later paper sell
 * then returned REJECTED_ALREADY_CLOSING and the position could never round
 * trip. PAPER may reclaim a proven-stale reservation while the canonical
 * position is still open.
 *
 * V5.0.7146 — LIVE now has an age too, on a six-times-longer window, because
 * liveSell reserves and never releases (no finally block, and no call to
 * confirmTerminalSell or abandonTerminalSell anywhere in its body) and the
 * 6702 fail-closed choice therefore made one failed attempt permanent. An
 * unregistered id that the canonical authority proves still owns quantity is
 * likewise reserved rather than refused: liveBuy never calls onEntry, so
 * absence from this ledger is an absence of observation, not evidence that
 * the position is gone.
 */
object PositionStateLedger6454 {

    enum class Lifecycle { UNKNOWN, OPEN, PARTIAL, CLOSING, CLOSED }

    enum class ReserveResult { RESERVED, REJECTED_BLANK_ID, REJECTED_ALREADY_CLOSING, REJECTED_ALREADY_CLOSED, REJECTED_UNKNOWN }
    enum class ConfirmResult { CONFIRMED, REJECTED_NOT_CLOSING, REJECTED_ALREADY_CLOSED, REJECTED_BLANK_ID }

    private val states = ConcurrentHashMap<String, Lifecycle>()
    private val closingSinceMs6702 = ConcurrentHashMap<String, Long>()
    private val terminalCount = ConcurrentHashMap<String, AtomicLong>()
    private val reservations = AtomicLong(0L)
    private val reservationRejects = AtomicLong(0L)
    private val confirms = AtomicLong(0L)
    private val confirmRejects = AtomicLong(0L)
    private val blankIdRejects = AtomicLong(0L)

    // Ordinary paper close attempts get a generous 30s ownership window.
    // Emergency stale/max-hold/rug exits may reclaim after 2s; a paper close is
    // local/atomic and should never legitimately remain CLOSING that long.
    private const val PAPER_STALE_CLOSING_MS_6702 = 30_000L
    private const val PAPER_EMERGENCY_STALE_CLOSING_MS_6702 = 2_000L

    // V5.0.7146 — live reservations leak, so live needs an age too.
    //
    // liveSell reserves at Executor:24367 and has no finally block and no call
    // to confirmTerminalSell or abandonTerminalSell anywhere in its body; all
    // four release call sites are inside paperSell. Every one of its non-terminal
    // returns therefore strands Lifecycle.CLOSING forever, after which
    // reserveTerminalSell answers REJECTED_ALREADY_CLOSING and exitEligibility6570
    // answers TERMINAL_CLAIM_ACTIVE for the life of the process. Real tokens,
    // permanently unsellable, from one failed attempt.
    //
    // The 6702 note reasoned that LIVE must stay fail-closed because live
    // finality may legitimately wait on chain confirmation. That is true of a
    // close that is IN FLIGHT; it is not true of one abandoned minutes ago. The
    // window below is six times the paper window precisely so a genuinely
    // pending confirmation is never interrupted, and recovery still requires the
    // canonical authority to prove the position is economically open — if the
    // close did land, remainingQtyRaw is zero and nothing is reclaimed.
    private const val LIVE_STALE_CLOSING_MS_7146 = 180_000L
    private const val LIVE_EMERGENCY_STALE_CLOSING_MS_7146 = 30_000L

    private fun emergencyReason6702(reason: String): Boolean {
        val r = reason.uppercase()
        return listOf(
            "STALE", "MAX_HOLD", "CATASTROPHE", "ZOMBIE", "MUST_SELL",
            "EMERGENCY", "RUG", "HARD_FLOOR", "PHANTOM", "SHUTDOWN",
        ).any { r.contains(it) }
    }

    private fun canonicalOpenPaper6702(positionId: String): Boolean {
        val p = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
        return p != null && p.mode.equals("paper", true) && p.remainingQtyRaw > java.math.BigInteger.ZERO
    }

    /** V5.0.7146 — canonical proof that a LIVE position still owns quantity. */
    private fun canonicalOpenLive7146(positionId: String): Boolean {
        val p = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
        return p != null && !p.mode.equals("paper", true) && p.remainingQtyRaw > java.math.BigInteger.ZERO
    }

    /** V5.0.7146 — canonical proof of remaining quantity, either mode. */
    private fun canonicalOpenWithQty7146(positionId: String): Boolean {
        val p = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
        return p != null && p.remainingQtyRaw > java.math.BigInteger.ZERO
    }

    /**
     * V5.0.7146 — LIVE stale reservation recovery, the mirror of the 6702 paper
     * path. Returns true only when this caller moves the exact stale CLOSING
     * back to OPEN; the CAS in reserveTerminalSell then owns a fresh attempt.
     */
    private fun recoverStaleLiveClosing7146(positionId: String, reason: String, now: Long): Boolean {
        if (!canonicalOpenLive7146(positionId)) return false
        val since = closingSinceMs6702[positionId] ?: return false
        val age = now - since
        val ttl = if (emergencyReason6702(reason)) LIVE_EMERGENCY_STALE_CLOSING_MS_7146 else LIVE_STALE_CLOSING_MS_7146
        if (age < ttl) return false
        if (!states.replace(positionId, Lifecycle.CLOSING, Lifecycle.OPEN)) return false
        closingSinceMs6702.remove(positionId, since)
        try {
            PipelineHealthCollector.labelInc("LIVE_TERMINAL_STALE_CLOSING_RECOVERED_7146")
            ForensicLogger.lifecycle(
                "LIVE_TERMINAL_STALE_CLOSING_RECOVERED_7146",
                "positionId=${positionId.take(18)} ageMs=$age ttlMs=$ttl reason=${reason.take(80)} action=closing_to_open_retry",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * PAPER-only stale reservation recovery. Returns true only when this caller
     * successfully changes the exact stale CLOSING state back to OPEN. The next
     * CAS in reserveTerminalSell then owns a fresh terminal attempt.
     */
    private fun recoverStalePaperClosing6702(positionId: String, reason: String, now: Long): Boolean {
        if (!canonicalOpenPaper6702(positionId)) return false
        val since = closingSinceMs6702[positionId] ?: return false
        val age = now - since
        val ttl = if (emergencyReason6702(reason)) PAPER_EMERGENCY_STALE_CLOSING_MS_6702 else PAPER_STALE_CLOSING_MS_6702
        if (age < ttl) return false
        if (!states.replace(positionId, Lifecycle.CLOSING, Lifecycle.OPEN)) return false
        closingSinceMs6702.remove(positionId, since)
        try {
            PipelineHealthCollector.labelInc("PAPER_TERMINAL_STALE_CLOSING_RECOVERED_6702")
            ForensicLogger.lifecycle(
                "PAPER_TERMINAL_STALE_CLOSING_RECOVERED_6702",
                "positionId=${positionId.take(18)} ageMs=$age ttlMs=$ttl reason=${reason.take(80)} action=closing_to_open_retry",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** V5.0.6519 — projection rebuild from CanonicalPositionAuthority only. */
    fun syncFromCanonical6519(openPositions: List<CanonicalPositionAuthority6441.Position>) {
        states.clear()
        closingSinceMs6702.clear()
        openPositions.forEach { p ->
            if (p.positionId.isNotBlank() && p.remainingQtyRaw > java.math.BigInteger.ZERO) {
                states[p.positionId] = when (p.lifecycle) {
                    CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED -> Lifecycle.PARTIAL
                    else -> Lifecycle.OPEN
                }
            }
        }
        try { PipelineHealthCollector.labelInc("POSITION_STATE_PROJECTED_FROM_CANONICAL_6519") } catch (_: Throwable) {}
    }

    fun openOrPartialCount6519(): Int = states.values.count { it == Lifecycle.OPEN || it == Lifecycle.PARTIAL }

    /** Called at position creation to seed lifecycle=OPEN. */
    fun onEntry(positionId: String) {
        if (positionId.isBlank()) return
        states.putIfAbsent(positionId, Lifecycle.OPEN)
        try {
            PositionLifecycleFormalization6617.markDiscovered(positionId, mint = "", symbol = "", lane = "")
        } catch (_: Throwable) {}
    }

    /** Called on any partial sell that leaves >0 remaining. */
    fun onPartial(positionId: String) {
        if (positionId.isBlank()) return
        val prior = states[positionId]
        if (prior == Lifecycle.OPEN || prior == Lifecycle.PARTIAL) {
            states[positionId] = Lifecycle.PARTIAL
            closingSinceMs6702.remove(positionId)
        }
    }

    /**
     * CAS OPEN/PARTIAL -> CLOSING. Must be called BEFORE any side effect
     * of a terminal sell (cash mutation, journal write, reward).
     */
    fun reserveTerminalSell(positionId: String, reason: String): ReserveResult {
        if (positionId.isBlank()) {
            blankIdRejects.incrementAndGet()
            reservationRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_BLANK_POSITION_ID_6454",
                    "reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_BLANK_POSITION_ID_6454")
            } catch (_: Throwable) {}
            return ReserveResult.REJECTED_BLANK_ID
        }

        val now = System.currentTimeMillis()
        var prior = states.putIfAbsent(positionId, Lifecycle.CLOSING)
        if (prior == null) {
            // V5.0.7146 — an unregistered id is an ABSENCE, not proof the
            // position does not exist.
            //
            // Every call site of onEntry is paper-side (Executor:15450, :15598,
            // :22753; CanonicalPaperTransaction6486:460, :526; the 6441 recovery
            // carry). The live buy commit registers CanonicalLotQuantity6464,
            // EconomicEventSchema6464, CanonicalMintOccupancyRegistry6464 and
            // EntryStrategySnapshot6450 — and skips the one ledger the sell door
            // reads. A live pid therefore only ever appears here once the
            // periodic syncFromCanonical6519 projection has swept it, so
            // refusing the sell means the position's sellability is a race
            // against a maintenance pass rather than a property of the open.
            //
            // When the canonical authority can PROVE remaining quantity, the
            // CLOSING that putIfAbsent just wrote IS the correct reservation.
            // Keep it instead of rolling it back. Without canonical proof the
            // original fail-closed refusal stands, unchanged.
            if (canonicalOpenWithQty7146(positionId)) {
                closingSinceMs6702[positionId] = now
                reservations.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TERMINAL_SELL_SEEDED_FROM_CANONICAL_7146",
                        "positionId=${positionId.take(18)} reason=${reason.take(40)} action=unregistered_but_canonically_open",
                    )
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_SEEDED_FROM_CANONICAL_7146")
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_RESERVED_6454")
                } catch (_: Throwable) {}
                return ReserveResult.RESERVED
            }
            states.remove(positionId, Lifecycle.CLOSING)
            closingSinceMs6702.remove(positionId)
            reservationRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_UNKNOWN_POSITION_6454",
                    "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_UNKNOWN_POSITION_6454")
            } catch (_: Throwable) {}
            return ReserveResult.REJECTED_UNKNOWN
        }

        // V5.0.6702 — reclaim only PAPER reservations proven stale while their
        // canonical position remains economically open. Then continue through
        // the normal OPEN->CLOSING CAS below in this same call.
        // V5.0.7146 extends the same treatment to LIVE on a longer window.
        if (prior == Lifecycle.CLOSING &&
            (recoverStalePaperClosing6702(positionId, reason, now) ||
                recoverStaleLiveClosing7146(positionId, reason, now))
        ) {
            prior = Lifecycle.OPEN
        }

        return when (prior) {
            Lifecycle.OPEN, Lifecycle.PARTIAL -> {
                if (states.replace(positionId, prior, Lifecycle.CLOSING)) {
                    closingSinceMs6702[positionId] = now
                    reservations.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("TERMINAL_SELL_RESERVED_6454") } catch (_: Throwable) {}
                    ReserveResult.RESERVED
                } else {
                    reservationRejects.incrementAndGet()
                    when (states[positionId]) {
                        Lifecycle.CLOSING -> ReserveResult.REJECTED_ALREADY_CLOSING
                        Lifecycle.CLOSED -> ReserveResult.REJECTED_ALREADY_CLOSED
                        else -> ReserveResult.REJECTED_ALREADY_CLOSING
                    }
                }
            }
            Lifecycle.CLOSING -> {
                reservationRejects.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TERMINAL_SELL_DUPLICATE_CLOSING_REJECTED_6454",
                        "positionId=${positionId.take(12)} reason=${reason.take(40)} ageMs=${closingSinceMs6702[positionId]?.let { now - it } ?: -1L}",
                    )
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_CLOSING_REJECTED_6454")
                    PipelineHealthCollector.labelInc("DUPLICATE_TERMINAL_MUTATION_6578")
                } catch (_: Throwable) {}
                ReserveResult.REJECTED_ALREADY_CLOSING
            }
            Lifecycle.CLOSED -> {
                reservationRejects.incrementAndGet()
                closingSinceMs6702.remove(positionId)
                try {
                    ForensicLogger.lifecycle(
                        "TERMINAL_SELL_DUPLICATE_CLOSED_REJECTED_6454",
                        "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                    )
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_CLOSED_REJECTED_6454")
                    PipelineHealthCollector.labelInc("DUPLICATE_TERMINAL_MUTATION_6578")
                } catch (_: Throwable) {}
                ReserveResult.REJECTED_ALREADY_CLOSED
            }
            Lifecycle.UNKNOWN -> ReserveResult.REJECTED_UNKNOWN
        }
    }

    /**
     * CAS CLOSING -> CLOSED. Must be called AFTER settlement side effects
     * (journal + accounting + reward publish) succeed.
     */
    fun confirmTerminalSell(positionId: String): ConfirmResult {
        if (positionId.isBlank()) {
            blankIdRejects.incrementAndGet()
            confirmRejects.incrementAndGet()
            return ConfirmResult.REJECTED_BLANK_ID
        }
        val cur = states[positionId]
        if (cur == Lifecycle.CLOSED) {
            confirmRejects.incrementAndGet()
            closingSinceMs6702.remove(positionId)
            try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRM_ALREADY_CLOSED_6454") } catch (_: Throwable) {}
            return ConfirmResult.REJECTED_ALREADY_CLOSED
        }
        if (cur != Lifecycle.CLOSING) {
            confirmRejects.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRM_NOT_CLOSING_6454") } catch (_: Throwable) {}
            return ConfirmResult.REJECTED_NOT_CLOSING
        }
        if (!states.replace(positionId, Lifecycle.CLOSING, Lifecycle.CLOSED)) {
            confirmRejects.incrementAndGet()
            return ConfirmResult.REJECTED_ALREADY_CLOSED
        }
        closingSinceMs6702.remove(positionId)
        terminalCount.getOrPut(positionId) { AtomicLong(0L) }.incrementAndGet()
        confirms.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRMED_6454") } catch (_: Throwable) {}
        try { PositionLifecycleFormalization6617.markClosed(positionId) } catch (_: Throwable) {}
        return ConfirmResult.CONFIRMED
    }

    fun lifecycle(positionId: String): Lifecycle = states[positionId] ?: Lifecycle.UNKNOWN

    /** Revert CLOSING -> OPEN when a reserved terminal SELL fails to settle. */
    fun abandonTerminalSell(positionId: String, reason: String): Boolean {
        if (positionId.isBlank()) return false
        val ok = states.replace(positionId, Lifecycle.CLOSING, Lifecycle.OPEN)
        if (ok) {
            closingSinceMs6702.remove(positionId)
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_ABANDONED_6454",
                    "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_ABANDONED_6454")
            } catch (_: Throwable) {}
        }
        return ok
    }

    fun terminalCount(positionId: String): Long = terminalCount[positionId]?.get() ?: 0L

    fun statusLine(): String = "positions=${states.size} reserved=${reservations.get()}/rej=${reservationRejects.get()} " +
        "confirmed=${confirms.get()}/rej=${confirmRejects.get()} blankIdRejects=${blankIdRejects.get()} " +
        "closingAges=${closingSinceMs6702.size}"

    internal fun resetForTest() {
        states.clear(); closingSinceMs6702.clear(); terminalCount.clear()
        reservations.set(0); reservationRejects.set(0)
        confirms.set(0); confirmRejects.set(0); blankIdRejects.set(0)
    }
}
