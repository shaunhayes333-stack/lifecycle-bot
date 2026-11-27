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
class RunnerRungBook8153(private val cap: Int = 2_048) {

    enum class Stage8153 { REQUESTED, TICKET_ACCEPTED, SIGNED_PENDING, BROADCAST, CONFIRMED_PARTIAL_FILL, PROCEEDS_BOOKED }
    enum class Revert8153 { REJECTED, SUPERSEDED, EXPIRED }

    data class Pending8153(val ladder: String, val tier: Int, val reason: String, val stage: Stage8153, val atMs: Long, val stageAtMs: Long,
                          val signature: String = "", val requestedRaw: java.math.BigInteger = java.math.BigInteger.ZERO)
    data class Remainder9043(val ladder: String, val tier: Int, val raw: java.math.BigInteger)

    private class Rungs8153(
        var ladder: String,
        var scalpConfirmed: Int = 0,
        var runnerConfirmed: Int = 0,
        var scalpExitConfirmed8394: Int = 0,
        var pending: Pending8153? = null,
        var refusals: Int = 0,
        var retryAtMs: Long = 0L,
        var promoted: Boolean = false,
        var proceedsSol: Double = 0.0,
        var soldCostSol: Double = 0.0,
        var opportunitySol: Double = 0.0,
        var remainder9043: Remainder9043? = null,
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
    private val modeEvents9043 = LinkedHashMap<String, Long>()
    private fun event9043(key: String, event: String) {
        val k = RunnerIdentity9043.mode(key) + ":" + event
        modeEvents9043[k] = (modeEvents9043[k] ?: 0L) + 1L
    }

    private fun rungs(key: String, ladder: String): Rungs8153 {
        book[key]?.let { return it }
        if (book.size >= cap) {
            // Never evict a reservation or an unfilled remainder to admit another position.
            // Ledger recovery may temporarily exceed the soft cap; new requests cannot.
            book.entries.firstOrNull { it.value.pending == null && it.value.remainder9043 == null }
                ?.key?.let { retire8153(it) }
        }
        return Rungs8153(ladder).also { book[key] = it }
    }

    private fun confirmedOf(r: Rungs8153, ladder: String): Int = when (ladder) {
        LADDER_RUNNER_8153 -> r.runnerConfirmed
        LADDER_SCALP_EXIT_8394 -> r.scalpExitConfirmed8394
        else -> r.scalpConfirmed
    }

    /** The confirmed tier of [ladder] for the position [key] (0 = none). */
    @Synchronized
    fun confirmedTier8153(key: String, ladder: String): Int = book[key]?.let { confirmedOf(it, ladder) } ?: 0

    @Synchronized
    fun pending8153(key: String): Pending8153? = book[key]?.pending

    @Synchronized
    fun containsKey9043(key: String): Boolean = book.containsKey(key)

    @Synchronized
    fun remainder9043(key: String, ladder: String): Remainder9043? = book[key]?.remainder9043?.takeIf { it.ladder == ladder }

    /** Legacy state is assigned only when the canonical inventory proves one exact owner of that entry clock. */
    @Synchronized
    fun migrateLegacy9043(legacyKey: String, positionKey: String, uniqueOwner: Boolean): Boolean {
        if (!uniqueOwner || positionKey == legacyKey || book.containsKey(positionKey)) return false
        val row = book.remove(legacyKey) ?: return false
        book[positionKey] = row
        return true
    }

    /**
     * A ladder asks to sell [tier] of [ladder]. Refused while another tranche of this position is pending (no duplicate
     * sell), when the tier is already confirmed, or inside the backoff after a rejection.
     */
    @Synchronized
    fun request8153(key: String, ladder: String, tier: Int, reason: String, nowMs: Long): Boolean {
        if (key.isBlank() || tier <= 0) return false
        if (!book.containsKey(key) && book.size >= cap && book.values.none { it.pending == null && it.remainder9043 == null }) return false
        val r = rungs(key, ladder)
        if (r.pending != null || tier <= confirmedOf(r, ladder) || nowMs < r.retryAtMs) return false
        val remainder = r.remainder9043
        if (remainder != null && (remainder.ladder != ladder || remainder.tier != tier)) return false
        r.ladder = ladder
        r.pending = Pending8153(ladder, tier, reason, Stage8153.REQUESTED, nowMs, nowMs,
            requestedRaw = remainder?.raw ?: java.math.BigInteger.ZERO)
        requested++
        event9043(key, "requested")
        if (ladder == LADDER_RUNNER_8153 && tier == 1) try { PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_FIRST_TAKE_REQUESTED") } catch (_: Throwable) {}
        return true
    }

    /** The pending tranche moved forward (never back, never to the confirmed stages: those are [confirm8153]). */
    @Synchronized
    fun advance8153(key: String, stage: Stage8153, nowMs: Long, expectedReason8397: String? = null, expectedRequestAtMs8397: Long? = null): Boolean {
        val r = book[key] ?: return false
        val p = r.pending ?: return false
        if (expectedReason8397 != null && p.reason != expectedReason8397) return false
        if (expectedRequestAtMs8397 != null && p.atMs != expectedRequestAtMs8397) return false
        if (stage.ordinal <= p.stage.ordinal || stage.ordinal >= Stage8153.CONFIRMED_PARTIAL_FILL.ordinal) return false
        r.pending = p.copy(stage = stage, stageAtMs = nowMs)
        when (stage) {
            Stage8153.TICKET_ACCEPTED -> { accepted++; event9043(key, "ticket") }
            Stage8153.BROADCAST -> { broadcast++; event9043(key, "submitted") }
            Stage8153.SIGNED_PENDING -> event9043(key, "signedPending")
            else -> {}
        }
        return true
    }

    /** Bind only the accepted request to the locally signed transaction before the network can see it. */
    @Synchronized
    fun signed9043(request: RunnerIdentity9043.Request, signature: String, raw: java.math.BigInteger, nowMs: Long, recovering: Boolean = false): Boolean {
        if (signature.isBlank() || raw.signum() <= 0) return false
        val r = if (recovering) rungs(request.positionKey, request.ladder) else book[request.positionKey] ?: return false
        var p = r.pending
        if (p == null && recovering && request.tier > confirmedOf(r, request.ladder)) {
            p = Pending8153(request.ladder, request.tier, request.reason, Stage8153.TICKET_ACCEPTED, request.requestedAtMs, nowMs)
            r.pending = p
        }
        p ?: return false
        if (p.ladder != request.ladder || p.tier != request.tier || p.reason != request.reason || p.atMs != request.requestedAtMs) return false
        if (p.signature.isNotBlank() && p.signature != signature) return false
        if (p.stage == Stage8153.REQUESTED) return false
        if (r.remainder9043?.let { raw > it.raw } == true) return false
        r.pending = p.copy(signature = signature, requestedRaw = raw)
        if (p.stage.ordinal < Stage8153.SIGNED_PENDING.ordinal) advance8153(request.positionKey, Stage8153.SIGNED_PENDING, nowMs, p.reason, p.atMs)
        return true
    }

    /** Only the ledger's real submit boundary advances SEND; a generic mint lock never does. */
    @Synchronized
    fun submitted9043(request: RunnerIdentity9043.Request, signature: String, nowMs: Long): Boolean {
        val p = book[request.positionKey]?.pending ?: return false
        if (p.signature != signature || p.ladder != request.ladder || p.tier != request.tier) return false
        return advance8153(request.positionKey, Stage8153.BROADCAST, nowMs, request.reason, request.requestedAtMs)
    }

    /** A timeout/cancel is not proof of failure. Only the exact signed ledger's terminal failure releases this reservation. */
    @Synchronized
    fun failed9043(request: RunnerIdentity9043.Request, signature: String, nowMs: Long): Boolean {
        val p = book[request.positionKey]?.pending ?: return false
        if (p.signature != signature || p.atMs != request.requestedAtMs || p.ladder != request.ladder || p.tier != request.tier) return false
        return revert8153(request.positionKey, Revert8153.REJECTED, nowMs, provenFailure9043 = true) != null
    }

    /** The arbiter may already have accepted this offer before its queued coroutine starts. */
    @Synchronized
    fun acceptDispatch8397(key: String, reason: String, requestedAtMs: Long, nowMs: Long, expectedLadder8397: String? = null): Boolean {
        val p = book[key]?.pending ?: return false
        if (p.reason != reason || p.atMs != requestedAtMs || p.stage.ordinal > Stage8153.TICKET_ACCEPTED.ordinal) return false
        if (expectedLadder8397 != null && p.ladder != expectedLadder8397) return false
        if (p.stage == Stage8153.REQUESTED) advance8153(key, Stage8153.TICKET_ACCEPTED, nowMs, reason, requestedAtMs)
        return true
    }

    /** The pending tranche did not become a fill: it goes back, nothing is consumed. A rejection backs the next try off. */
    @Synchronized
    fun revert8153(key: String, why: Revert8153, nowMs: Long, slowAfter: Int = 16, provenFailure9043: Boolean = false): Pending8153? {
        val r = book[key] ?: return null
        val p = r.pending ?: return null
        if (p.stage.ordinal >= Stage8153.SIGNED_PENDING.ordinal && !provenFailure9043) return null
        r.pending = null
        event9043(key, why.name.lowercase())
        when (why) {
            Revert8153.REJECTED -> { rejected++; r.refusals++; r.retryAtMs = nowMs + backoffMs8153(r.refusals, slowAfter) }
            Revert8153.SUPERSEDED -> superseded++
            Revert8153.EXPIRED -> expired++
        }
        return p
    }

    /** A late failed dispatch cannot release a newer request for the same position/tier. */
    @Synchronized
    fun revertMatching8397(key: String, reason: String, requestedAtMs: Long, nowMs: Long, slowAfter: Int = 16,
                           expectedLadder8397: String? = null): Boolean {
        val pending = book[key]?.pending ?: return false
        if (pending.reason != reason || pending.atMs != requestedAtMs) return false
        if (expectedLadder8397 != null && pending.ladder != expectedLadder8397) return false
        return revert8153(key, Revert8153.REJECTED, nowMs, slowAfter) != null
    }

    /**
     * A pending tranche's status on a new print: a sale in flight for the mint ([inFlight]) keeps its reservation;
     * one that never reached a sale within its stage's grace EXPIRES (retryable, not consumed). Returns true when no
     * tranche is pending any more (none, or it just expired).
     */
    @Synchronized
    fun settle8153(key: String, inFlight: Boolean, nowMs: Long): Boolean {
        val r = book[key] ?: return true
        val p = r.pending ?: return true
        if (inFlight || p.stage.ordinal >= Stage8153.SIGNED_PENDING.ordinal) return false
        if (nowMs - p.stageAtMs < graceMs8153(p.stage)) return false
        r.pending = null
        expired++
        event9043(key, "expired")
        return true
    }

    /**
     * Authoritative on-chain fill accounting: [tier] of the ladder the fill was requested on (the pending tranche's
     * ladder, else [ladder]) is CONFIRMED (max-merge); proceeds of [proceedsSol] for [soldCostSol] of cost basis are
     * booked once per [signature]. Clears the pending tranche when the fill covers it. Returns true when proceeds booked.
     */
    @Synchronized
    fun confirm8153(key: String, ladder: String, tier: Int, signature: String, proceedsSol: Double, soldCostSol: Double,
                    consumedRaw9043: java.math.BigInteger? = null): Boolean {
        val sig = signature.trim()
        if (key.isBlank() || sig.isEmpty() || !proceedsSol.isFinite() || proceedsSol < 0.0 ||
            !soldCostSol.isFinite() || soldCostSol <= 0.0) return false
        if (sig in seenSignatures) { duplicateFills++; return false }
        val r = rungs(key, ladder)
        val p = r.pending
        val exact = p != null && p.ladder == ladder && p.tier == tier && (p.signature.isBlank() || p.signature == sig)
        if (exact && p != null && p.requestedRaw.signum() > 0 && consumedRaw9043 != null && consumedRaw9043.signum() > 0 && consumedRaw9043 < p.requestedRaw) {
            // Book only what filled. The unfilled raw quantity remains the same logical rung,
            // and the next attempt is capped to this remainder instead of selling the fraction again.
            r.remainder9043 = Remainder9043(ladder, tier, p.requestedRaw - consumedRaw9043)
            r.pending = null
            confirmed++
            event9043(key, "partialFill")
        } else if (exact || p == null || p.signature.isBlank()) {
            confirmAppliedTier8198(key, ladder, tier)
        }
        seenSignatures.add(sig)
        r.proceedsSol += proceedsSol
        r.soldCostSol += soldCostSol
        booked++
        event9043(key, "booked")
        // V5.0.8254 — runner funnel: a confirmed signature with proceeds; net banked only when proceeds beat the cost sold.
        if (ladder == LADDER_RUNNER_8153) try {
            if (tier == 1) PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_FIRST_TAKE_LANDED")
            if (proceedsSol > soldCostSol) PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_NET_PROFIT_BANKED")
        } catch (_: Throwable) {}
        return true
    }

    /** An APPLIED quantity receipt confirms a tier, but supplies no banked-proceeds evidence.
     * Called only by RunnerLive8153's already-applied/chain-consumed callbacks. */
    @Synchronized
    internal fun confirmAppliedTier8198(key: String, ladder: String, tier: Int): Boolean {
        if (key.isBlank() || tier !in 1..16) return false
        val r = rungs(key, ladder)
        val p = r.pending
        val l = ladder // the confirmed receipt owns its ladder; equal tier numbers are not identity
        val previous = confirmedOf(r, l)
        when (l) {
            LADDER_RUNNER_8153 -> r.runnerConfirmed = maxOf(r.runnerConfirmed, tier)
            LADDER_SCALP_EXIT_8394 -> r.scalpExitConfirmed8394 = maxOf(r.scalpExitConfirmed8394, tier)
            else -> r.scalpConfirmed = maxOf(r.scalpConfirmed, tier)
        }
        if (p != null && p.tier <= confirmedOf(r, p.ladder)) { r.pending = null; confirmed++ }
        if (r.remainder9043?.let { it.ladder == l && it.tier <= confirmedOf(r, l) } == true) r.remainder9043 = null
        r.refusals = 0
        r.retryAtMs = 0L
        return tier > previous
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
     * One line per position: key;scalpConfirmed;runnerConfirmed;ladder;promoted;proceeds;soldCost;opportunity, plus
     * `#closed` totals and `#sig` idempotency lines. Requests, signed reservations and unfilled raw quantities
     * are persisted too. A restart or timeout cannot turn an uncertain signed transaction into permission to resend.
     */
    @Synchronized
    fun encode8153(): String {
        val sb = StringBuilder()
        sb.append("#closed;").append(closedOpportunitySol).append(';').append(closedNetSol).append(';').append(closedPositions).append('\n')
        // Persist the idempotency keys with the aggregates. Without these, replaying a confirmed chain event after an
        // app restart could add the same SOL proceeds a second time even though the in-memory guard worked pre-restart.
        for (sig in seenSignatures) sb.append("#sig;").append(sig).append('\n')
        for ((k, r) in book) {
            sb.append(k).append(';').append(r.scalpConfirmed).append(';').append(r.runnerConfirmed).append(';').append(r.ladder)
                .append(';').append(if (r.promoted) 1 else 0).append(';').append(r.proceedsSol).append(';').append(r.soldCostSol)
                .append(';').append(r.opportunitySol).append(';').append(r.scalpExitConfirmed8394)
                .append(';').append(r.refusals).append(';').append(r.retryAtMs).append('\n')
            r.pending?.let { p ->
                sb.append(listOf("#pending9043", RunnerIdentity9043.encode(k), RunnerIdentity9043.encode(p.ladder), p.tier,
                    RunnerIdentity9043.encode(p.reason), p.stage.name, p.atMs, p.stageAtMs, p.signature, p.requestedRaw).joinToString(";")).append('\n')
            }
            r.remainder9043?.let { p ->
                sb.append(listOf("#remainder9043", RunnerIdentity9043.encode(k), RunnerIdentity9043.encode(p.ladder), p.tier, p.raw).joinToString(";")).append('\n')
            }
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
            if (f.size >= 10 && f[0] == "#pending9043") {
                try {
                    val key = RunnerIdentity9043.decode(f[1]); val ladder = RunnerIdentity9043.decode(f[2])
                    val p = Pending8153(ladder, f[3].toInt(), RunnerIdentity9043.decode(f[4]), Stage8153.valueOf(f[5]),
                        f[6].toLong(), f[7].toLong(), f[8], java.math.BigInteger(f[9]))
                    val r = rungs(key, ladder)
                    if (p.tier in 1..16 && p.atMs > 0L && p.requestedRaw.signum() >= 0 && p.tier > confirmedOf(r, ladder) &&
                        (r.pending == null || (r.pending?.atMs == p.atMs && r.pending!!.stage.ordinal < p.stage.ordinal))) r.pending = p
                } catch (_: Throwable) { /* an unreadable row cannot manufacture a confirmed fill */ }
                continue
            }
            if (f.size >= 5 && f[0] == "#remainder9043") {
                try {
                    val key = RunnerIdentity9043.decode(f[1]); val ladder = RunnerIdentity9043.decode(f[2])
                    val p = Remainder9043(ladder, f[3].toInt(), java.math.BigInteger(f[4]))
                    val r = rungs(key, ladder)
                    if (p.tier in 1..16 && p.raw.signum() > 0 && p.tier > confirmedOf(r, ladder)) r.remainder9043 = p
                } catch (_: Throwable) { }
                continue
            }
            if (f.size >= 2 && f[0] == "#sig") {
                val sig = f[1].trim()
                if (sig.isNotEmpty()) seenSignatures.add(sig)
                continue
            }
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
            val ladder = f[3].takeIf { it == LADDER_RUNNER_8153 || it == LADDER_SCALP_EXIT_8394 } ?: LADDER_SCALP_8153
            val r = rungs(f[0], ladder)
            r.scalpConfirmed = maxOf(r.scalpConfirmed, f[1].toIntOrNull()?.coerceIn(0, 16) ?: 0)
            r.runnerConfirmed = maxOf(r.runnerConfirmed, f[2].toIntOrNull()?.coerceIn(0, 16) ?: 0)
            r.scalpExitConfirmed8394 = maxOf(r.scalpExitConfirmed8394, f.getOrNull(8)?.toIntOrNull()?.coerceIn(0, 2) ?: 0)
            r.refusals = maxOf(r.refusals, f.getOrNull(9)?.toIntOrNull() ?: 0)
            r.retryAtMs = maxOf(r.retryAtMs, f.getOrNull(10)?.toLongOrNull() ?: 0L)
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
            "rungs[req=$requested ticket=$accepted bcast=$broadcast conf=$confirmed booked=$booked rejected=$rejected superseded=$superseded expired=$expired dupFill=$duplicateFills] " +
            "sessionModes9043{${modeEvents9043.entries.joinToString(",") { "${it.key}=${it.value}" }}} " +
            "pendingByMode9043{${book.entries.filter { it.value.pending != null }.groupingBy { RunnerIdentity9043.mode(it.key) }.eachCount()}} aggregatesIncludeRestored=true"
    }

    companion object {
        const val LADDER_SCALP_EXIT_8394 = "SCALP_EXIT_8351"
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
