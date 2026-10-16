package com.lifecyclebot.engine

/**
 * V5.0.8153 — the per-position rung (milestone) lifecycle of the spike / runner ladders (pure: no Android, the clock is
 * always passed in; RunnerLive8153 owns one and persists [encode8153]).
 *
 * Device, 5.0.8151: no spike-tier fills, no runner proceeds, no confirmed banked lifecycle profits. SpikeCapture7943
 * wrote `firedTiers[key] = reached` BEFORE the sale was even dispatched: a tranche the share budget refused was
 * CONSUMED on the spot, a tranche the executor refused (net-edge floor, uneconomic slice, busy lock) was re-armed at
 * most 16 times and then consumed for good, and the fired tiers lived only in memory — a restart re-sold rungs that had
 * already filled. Here a milestone has two separate parts:
 *  - the CONFIRMED tier per ladder (SCALP / RUNNER): it advances only on authoritative on-chain fill accounting
 *    ([confirm8153]) and survives restarts ([encode8153] / [restore8153], idempotent max-merge);
 *  - at most ONE pending tranche per position, walking REQUESTED -> TICKET_ACCEPTED -> BROADCAST ->
 *    CONFIRMED_PARTIAL_FILL -> PROCEEDS_BOOKED. A pending tranche blocks every new request (no duplicate sells); a
 *    rejected, superseded or expired one goes back ([revert8153] / [settle8153]) without consuming anything, and a
 *    rejected one retries on a growing backoff ([backoffMs8153]) instead of being dropped.
 * Booked proceeds are deduplicated by transaction signature (exactly once per fill), and the book keeps each
 * position's gross opportunity (entry cost x verified peak) beside the net SOL its confirmed slices actually banked.
 * Thread-safe.
 */
class RunnerRungBook8153(private val cap: Int = 2_048, private val seenCap: Int = 4_096) {

    enum class Stage8153 { REQUESTED, TICKET_ACCEPTED, BROADCAST, CONFIRMED_PARTIAL_FILL, PROCEEDS_BOOKED }
    enum class Revert8153 { REJECTED, SUPERSEDED, EXPIRED }

    data class Pending8153(val ladder: String, val tier: Int, val reason: String, val stage: Stage8153, val atMs: Long, val stageAtMs: Long)

    private class Rungs8153(
        var ladder: String,
        var scalpConfirmed: Int = 0,
        var runnerConfirmed: Int = 0,
        var pending: Pending8153? = null,
        var refusals: Int = 0,
        var retryAtMs: Long = 0L,
        var promoted: Boolean = false,
        var proceedsSol: Double = 0.0,
        var soldCostSol: Double = 0.0,
        var opportunitySol: Double = 0.0,
    )

    private val book = LinkedHashMap<String, Rungs8153>()
    private val seenSignatures = LinkedHashSet<String>()
    private var closedOpportunitySol = 0.0
    private var closedNetSol = 0.0
    private var closedPositions = 0L
    private var requested = 0L
    private var accepted = 0L
    private var broadcast = 0L
    private var confirmed = 0L
    private var booked = 0L
    private var rejected = 0L
    private var superseded = 0L
    private var expired = 0L
    private var duplicateFills = 0L

    private fun rungs(key: String, ladder: String): Rungs8153 {
        book[key]?.let { return it }
        if (book.size >= cap) { val oldest = book.keys.iterator(); oldest.next(); oldest.remove() }
        return Rungs8153(ladder).also { book[key] = it }
    }

    private fun confirmedOf(r: Rungs8153, ladder: String): Int = if (ladder == LADDER_RUNNER_8153) r.runnerConfirmed else r.scalpConfirmed

    /** The confirmed tier of [ladder] for the position [key] (0 = none). */
    @Synchronized
    fun confirmedTier8153(key: String, ladder: String): Int = book[key]?.let { confirmedOf(it, ladder) } ?: 0

    @Synchronized
    fun pending8153(key: String): Pending8153? = book[key]?.pending

    /**
     * A ladder asks to sell [tier] of [ladder]. Refused while another tranche of this position is pending (no duplicate
     * sell), when the tier is already confirmed, or inside the backoff after a rejection.
     */
    @Synchronized
    fun request8153(key: String, ladder: String, tier: Int, reason: String, nowMs: Long): Boolean {
        if (key.isBlank() || tier <= 0) return false
        val r = rungs(key, ladder)
        if (r.pending != null || tier <= confirmedOf(r, ladder) || nowMs < r.retryAtMs) return false
        r.ladder = ladder
        r.pending = Pending8153(ladder, tier, reason, Stage8153.REQUESTED, nowMs, nowMs)
        requested++
        return true
    }

    /** The pending tranche moved forward (never back, never to the confirmed stages: those are [confirm8153]). */
    @Synchronized
    fun advance8153(key: String, stage: Stage8153, nowMs: Long): Boolean {
        val r = book[key] ?: return false
        val p = r.pending ?: return false
        if (stage.ordinal <= p.stage.ordinal || stage.ordinal >= Stage8153.CONFIRMED_PARTIAL_FILL.ordinal) return false
        r.pending = p.copy(stage = stage, stageAtMs = nowMs)
        if (stage == Stage8153.TICKET_ACCEPTED) accepted++ else broadcast++
        return true
    }

    /** The pending tranche did not become a fill: it goes back, nothing is consumed. A rejection backs the next try off. */
    @Synchronized
    fun revert8153(key: String, why: Revert8153, nowMs: Long, slowAfter: Int = 16): Pending8153? {
        val r = book[key] ?: return null
        val p = r.pending ?: return null
        r.pending = null
        when (why) {
            Revert8153.REJECTED -> { rejected++; r.refusals++; r.retryAtMs = nowMs + backoffMs8153(r.refusals, slowAfter) }
            Revert8153.SUPERSEDED -> superseded++
            Revert8153.EXPIRED -> expired++
        }
        return p
    }

    /**
     * A pending tranche's status on a new print: a sale in flight for the mint ([inFlight]) moves it to BROADCAST;
     * one that never reached a sale within its stage's grace EXPIRES (retryable, not consumed). Returns true when no
     * tranche is pending any more (none, or it just expired).
     */
    @Synchronized
    fun settle8153(key: String, inFlight: Boolean, nowMs: Long): Boolean {
        val r = book[key] ?: return true
        val p = r.pending ?: return true
        if (inFlight) {
            if (p.stage.ordinal < Stage8153.BROADCAST.ordinal) { r.pending = p.copy(stage = Stage8153.BROADCAST, stageAtMs = nowMs); broadcast++ }
            return false
        }
        if (nowMs - p.stageAtMs < graceMs8153(p.stage)) return false
        r.pending = null
        expired++
        return true
    }

    /**
     * Authoritative on-chain fill accounting: [tier] of the ladder the fill was requested on (the pending tranche's
     * ladder, else [ladder]) is CONFIRMED (max-merge); proceeds of [proceedsSol] for [soldCostSol] of cost basis are
     * booked once per [signature]. Clears the pending tranche when the fill covers it. Returns true when proceeds booked.
     */
    @Synchronized
    fun confirm8153(key: String, ladder: String, tier: Int, signature: String, proceedsSol: Double, soldCostSol: Double): Boolean {
        if (key.isBlank()) return false
        val r = rungs(key, ladder)
        val p = r.pending
        if (tier > 0) {
            val l = if (p != null && p.tier == tier) p.ladder else ladder
            if (l == LADDER_RUNNER_8153) r.runnerConfirmed = maxOf(r.runnerConfirmed, tier) else r.scalpConfirmed = maxOf(r.scalpConfirmed, tier)
            if (p != null && p.tier <= confirmedOf(r, p.ladder)) { r.pending = null; confirmed++ }
            r.refusals = 0
            r.retryAtMs = 0L
        }
        val sig = signature.trim()
        if (sig.isEmpty() || !proceedsSol.isFinite() || proceedsSol < 0.0 || !soldCostSol.isFinite() || soldCostSol <= 0.0) return false
        if (sig in seenSignatures) { duplicateFills++; return false }
        seenSignatures.add(sig)
        while (seenSignatures.size > seenCap) { val oldest = seenSignatures.iterator(); oldest.next(); oldest.remove() }
        r.proceedsSol += proceedsSol
        r.soldCostSol += soldCostSol
        booked++
        return true
    }

    /** The position's gross opportunity: its entry cost x the verified peak gain (ratchets up only). */
    @Synchronized
    fun noteOpportunity8153(key: String, ladder: String, costSol: Double, verifiedPeakPct: Double) {
        if (key.isBlank() || !costSol.isFinite() || costSol <= 0.0 || !verifiedPeakPct.isFinite() || verifiedPeakPct <= 0.0) return
        if (verifiedPeakPct >= RunnerPlan8153.BAD_UNITS_PCT_8153) return
        val r = rungs(key, ladder)
        r.opportunitySol = maxOf(r.opportunitySol, costSol * verifiedPeakPct / 100.0)
    }

    /** The position's promotion is latched. Returns true when newly latched. */
    @Synchronized
    fun latchPromotion8153(key: String): Boolean {
        if (key.isBlank()) return false
        val r = rungs(key, LADDER_RUNNER_8153)
        if (r.promoted) return false
        r.promoted = true
        return true
    }

    @Synchronized
    fun promoted8153(key: String): Boolean = book[key]?.promoted == true

    /** The position closed: its rungs leave the book; its opportunity and banked net move to the closed totals. */
    @Synchronized
    fun retire8153(key: String): Boolean {
        val r = book.remove(key) ?: return false
        closedOpportunitySol += r.opportunitySol
        closedNetSol += r.proceedsSol - r.soldCostSol
        closedPositions++
        return true
    }

    @Synchronized
    fun keys8153(): List<String> = book.keys.toList()

    /** Net SOL the confirmed slices of [key] banked (proceeds less the cost basis they sold). */
    @Synchronized
    fun bankedNetSol8153(key: String): Double = book[key]?.let { it.proceedsSol - it.soldCostSol } ?: 0.0

    @Synchronized
    fun bankedProceedsSol8153(key: String): Double = book[key]?.proceedsSol ?: 0.0

    /**
     * One line per position: key;scalpConfirmed;runnerConfirmed;ladder;promoted;proceeds;soldCost;opportunity, plus a
     * `#closed` totals line. Pending tranches are never persisted: an unconfirmed request is retryable after a restart.
     */
    @Synchronized
    fun encode8153(): String {
        val sb = StringBuilder()
        sb.append("#closed;").append(closedOpportunitySol).append(';').append(closedNetSol).append(';').append(closedPositions).append('\n')
        for ((k, r) in book) {
            sb.append(k).append(';').append(r.scalpConfirmed).append(';').append(r.runnerConfirmed).append(';').append(r.ladder)
                .append(';').append(if (r.promoted) 1 else 0).append(';').append(r.proceedsSol).append(';').append(r.soldCostSol)
                .append(';').append(r.opportunitySol).append('\n')
        }
        return sb.toString()
    }

    /** Rebuild from [encode8153] (a restart). Idempotent: confirmed tiers, banked sums and totals merge by max. Returns rows read. */
    @Synchronized
    fun restore8153(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        var n = 0
        for (line in text.lineSequence()) {
            val f = line.trim().split(';')
            if (f.size >= 4 && f[0] == "#closed") {
                // The totals line is a snapshot of the same running totals: the newer (more positions) snapshot wins.
                val positions = f[3].toLongOrNull() ?: 0L
                if (positions >= closedPositions) {
                    closedOpportunitySol = f[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: closedOpportunitySol
                    closedNetSol = f[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: closedNetSol
                    closedPositions = positions
                }
                continue
            }
            if (f.size < 8 || f[0].isBlank() || !f[0].contains('|')) continue
            val ladder = if (f[3] == LADDER_RUNNER_8153) LADDER_RUNNER_8153 else LADDER_SCALP_8153
            val r = rungs(f[0], ladder)
            r.scalpConfirmed = maxOf(r.scalpConfirmed, f[1].toIntOrNull()?.coerceIn(0, 16) ?: 0)
            r.runnerConfirmed = maxOf(r.runnerConfirmed, f[2].toIntOrNull()?.coerceIn(0, 16) ?: 0)
            if (ladder == LADDER_RUNNER_8153) r.ladder = ladder
            if (f[4] == "1") r.promoted = true
            r.proceedsSol = maxOf(r.proceedsSol, f[5].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
            r.soldCostSol = maxOf(r.soldCostSol, f[6].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
            r.opportunitySol = maxOf(r.opportunitySol, f[7].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
            n++
        }
        return n
    }

    /** Gross opportunity vs net SOL actually banked by confirmed slices (partials included), open + closed positions. */
    @Synchronized
    fun statusLine8153(): String {
        val openOpp = book.values.sumOf { it.opportunitySol }
        val openNet = book.values.sumOf { it.proceedsSol - it.soldCostSol }
        val opp = openOpp + closedOpportunitySol
        val net = openNet + closedNetSol
        val share = if (opp > 1e-9) "%.0f%%".format(net / opp * 100.0) else "-"
        return "grossOppSol=${"%.4f".format(opp)} netBankedSol=${"%+.4f".format(net)} banked/opp=$share " +
            "positions=${book.size}+${closedPositions}closed promoted=${book.values.count { it.promoted }} pending=${book.values.count { it.pending != null }} " +
            "rungs[req=$requested ticket=$accepted bcast=$broadcast conf=$confirmed booked=$booked rejected=$rejected superseded=$superseded expired=$expired dupFill=$duplicateFills]"
    }

    companion object {
        const val LADDER_SCALP_8153 = "SCALP"
        const val LADDER_RUNNER_8153 = "RUNNER"

        /** Pure: a rejected tranche's wait before it may ask again: 1, 2, 4, 8, 16, 30 s; 60 s after [slowAfter] refusals. */
        fun backoffMs8153(refusals: Int, slowAfter: Int = 16): Long {
            if (refusals <= 0) return 0L
            if (refusals > slowAfter) return 60_000L
            return (1_000L shl (refusals - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        }

        /** Pure: how long a pending tranche may sit at [stage] with no sale in flight before it expires. */
        fun graceMs8153(stage: Stage8153): Long = when (stage) {
            Stage8153.REQUESTED -> 5_000L                                  // never handed to the executor (8081's rule)
            Stage8153.TICKET_ACCEPTED -> 20_000L                           // handed off, the sell lock not yet seen
            else -> ExitTruthArbiter8144.TICKET_TTL_MS_8144                // a sale was in flight (verifier timeout 60 s)
        }
    }
}
