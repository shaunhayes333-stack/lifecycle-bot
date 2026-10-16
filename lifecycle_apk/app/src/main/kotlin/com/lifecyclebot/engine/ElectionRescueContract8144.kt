package com.lifecyclebot.engine

/**
 * V5.0.8144 — THE RESCUE HALF OF THE ELECTION CONTRACT, AS PURE DECISIONS.
 *
 * Device 5.0.8142: thousands of qualifications, very few owner selections; many claimed rescues and no confirmed
 * execution through a secondary path; 35 named yields and ZERO receiving-lane arrivals. Causes read in code:
 *  - SpecialistOwnership7951.rescueClaim8136 answered `true` from its exception handler: an arbitration that threw let a
 *    lane evaluate on a claim nobody granted. [guardedClaim8144] turns any throw into DEFERRED_ARBITRATION_ERROR, which
 *    never executes.
 *  - The bounded-rescue lottery (ExecutionAuthorityPolicy6533.selectOneRescue) claimed the one slot for its hash pick on
 *    the first cycle, before the primary refused and before CORE / TREASURY named a receiver, so the named receiver was
 *    refused "slot held" and the hash pick (often with no native evidence) never reached the FDG. The lottery now only
 *    fills an empty slot when no stronger lane with native evidence and no named receiver wants it ([lotteryMayFill8144]).
 *  - The rescue pool re-ranked lanes on its own and could out-rank the receiver the refusing primary named; the named
 *    receiver now wins the slot when it is eligible ([poolWinner8144]).
 *  - Every 30 s bucket roll (LaneExecutionCoordinator.candidateVersionFor) closed the election record mid-rescue; the
 *    rescue's FDG verdict then arrived as "stale" or on a fresh record that knew nothing of the claim. An unconcluded
 *    rescue / refusal / yield now keeps its record across the roll for [LINEAGE_MAX_MS_8144] ([carriesLineage8144]).
 *
 * No Android dependencies: every decision here is unit-tested on the JVM. Nothing here admits, sizes or executes.
 */
object ElectionRescueContract8144 {
    /** An election lineage (one candidate across clock buckets) lives at most this long — the 7960 hand-off window. */
    const val LINEAGE_MAX_MS_8144 = 90_000L

    /** One rescue-slot claim. Only GRANTED / HELD let the lane evaluate as the rescue; every other outcome refuses. */
    enum class ClaimOutcome8144(val executable: Boolean) {
        GRANTED(true),
        HELD(true),
        DENIED_HELD_BY_OTHER(false),
        DENIED_OUTRANKED(false),
        DENIED_JUDGEMENT(false),
        DENIED_INELIGIBLE(false),
        DENIED_NO_PROPOSAL(false),
        DEFERRED_ARBITRATION_ERROR(false),
    }

    /** Pure: run one arbitration; a throw is a named deferred outcome, never an executable claim. */
    inline fun guardedClaim8144(block: () -> ClaimOutcome8144): ClaimOutcome8144 =
        try { block() } catch (_: Throwable) { ClaimOutcome8144.DEFERRED_ARBITRATION_ERROR }

    /**
     * Pure: the rescue among [pool]. The receiver a fit refusal named ([named]) wins when it is in the pool and may be a
     * rescue at all (never the primary, CORE or CYCLIC); otherwise the best lane on its own scale (electRescue8136).
     */
    fun poolWinner8144(pool: Collection<SpecialistElection8136.Contender8136>, primary: String, named: String?): String? {
        if (!named.isNullOrBlank() && named != primary && named !in SpecialistElection8136.RESCUE_EXCLUDED_8136 &&
            pool.any { it.lane == named }) return named
        return SpecialistElection8136.electRescue8136(pool, primary)
    }

    /**
     * Pure: may the lottery's hash pick [pick] fill an EMPTY rescue slot? Not when a fit refusal named a different
     * receiver, and not when a lane with native evidence ([best], the own-scale winner of the open proposals) is
     * stronger. With no evidence at all the lottery keeps its old job.
     */
    fun lotteryMayFill8144(pick: String, named: String?, best: String?): Boolean = when {
        !named.isNullOrBlank() -> named == pick
        best.isNullOrBlank() -> true
        else -> best == pick
    }

    /**
     * Pure: does a record superseded by a newer clock bucket keep its lineage? Only while its election is unconcluded
     * (a primary refusal with no rescue chosen yet, a rescue claimed but not evaluated, a named yield not settled) and
     * the lineage is younger than [LINEAGE_MAX_MS_8144].
     */
    fun carriesLineage8144(refusalOpen: Boolean, rescuePending: Boolean, yieldPending: Boolean, lineageAgeMs: Long): Boolean =
        (refusalOpen || rescuePending || yieldPending) && lineageAgeMs in 0..LINEAGE_MAX_MS_8144

    /** A BUY attempt id "generation:MODE:mint:BUY:LANE:version:ticket" (ExecutableOpenGate.canonicalExecutionKey). */
    data class BuyAttempt8144(val mint: String, val lane: String, val version: Long)

    /** Pure: parse a canonical BUY attempt id; null for anything else (no guessing). */
    fun parseBuyAttempt8144(attemptId: String): BuyAttempt8144? {
        val p = attemptId.trim().split(':')
        if (p.size != 7 || !p[3].equals("BUY", true)) return null
        val version = p[5].toLongOrNull() ?: return null
        if (p[2].isBlank() || p[4].isBlank()) return null
        return BuyAttempt8144(p[2], p[4].uppercase(), version)
    }
}

/**
 * V5.0.8144 — the rescue funnel per lane, one binding per (mint, lane) rescue: CLAIMED (or ADOPTED at the FDG without a
 * claim) > SCHEDULED (BotService let the lane evaluate) > EVALUATED (an FDG verdict) > FDG_ALLOWED > EXECUTED (a sealed
 * ticket handed to the executor) > CONFIRMED (the fill was confirmed: EXEC / POSITION_OPENED). Each stage counts once per
 * binding and only after its predecessor, so the counters always reconcile:
 *   claimed + adopted >= scheduled, claimed + adopted >= evaluated >= fdgAllowed >= executed >= confirmed.
 * A stage with no binding / predecessor is counted as `unbound`, never dropped silently. Pure JVM, own lock.
 */
object ElectionRescueFunnel8144 {
    enum class Stage8144(val code: String) {
        CLAIMED("c"), ADOPTED("a"), SCHEDULED("s"), EVALUATED("e"), FDG_ALLOWED("f"), EXECUTED("x"), CONFIRMED("k"),
    }

    private const val BINDING_TTL_MS_8144 = 120_000L
    private const val MAX_BINDINGS_8144 = 4_096

    private class Binding8144(val fromVersion: Long, var lastVersion: Long, val boundAtMs: Long) {
        var bits = 0
    }

    private val lock8144 = Any()
    private val bindings8144 = LinkedHashMap<String, Binding8144>()
    private val laneCounts8144 = HashMap<String, LongArray>()
    private var unbound8144 = 0L

    private fun key8144(mint: String, lane: String): String = "$mint|${lane.trim().uppercase()}"
    private fun bit8144(s: Stage8144): Int = 1 shl s.ordinal
    private fun row8144(lane: String): LongArray = laneCounts8144.getOrPut(lane.trim().uppercase()) { LongArray(Stage8144.values().size) }

    private fun current8144(b: Binding8144, version: Long, nowMs: Long): Boolean =
        nowMs - b.boundAtMs in 0..BINDING_TTL_MS_8144 &&
            (version <= 0L || b.fromVersion <= 0L || (version >= b.fromVersion - 1L && version <= b.lastVersion + 1L))

    private fun count8144(lane: String, b: Binding8144, s: Stage8144): Boolean {
        if ((b.bits and bit8144(s)) != 0) return false
        b.bits = b.bits or bit8144(s)
        row8144(lane)[s.ordinal]++
        return true
    }

    /** [lane] took [mint]'s rescue slot (claim, or FDG adoption when [adopted]). A live binding is reused, never double-counted. */
    fun bind8144(mint: String, lane: String, version: Long, adopted: Boolean, nowMs: Long): Boolean {
        if (mint.isBlank() || lane.isBlank()) return false
        return synchronized(lock8144) {
            val k = key8144(mint, lane)
            val old = bindings8144[k]
            val b = if (old != null && current8144(old, version, nowMs)) old else {
                if (old == null && bindings8144.size >= MAX_BINDINGS_8144) bindings8144.remove(bindings8144.keys.first())
                Binding8144(version, version, nowMs).also { bindings8144[k] = it }
            }
            if (version > b.lastVersion) b.lastVersion = version
            val origins = bit8144(Stage8144.CLAIMED) or bit8144(Stage8144.ADOPTED)
            if ((b.bits and origins) != 0) false
            else count8144(lane, b, if (adopted) Stage8144.ADOPTED else Stage8144.CLAIMED)
        }
    }

    /** The election lineage carried the rescue to [version] (a clock roll, not a new candidate). */
    fun extend8144(mint: String, lane: String, version: Long) {
        synchronized(lock8144) {
            val b = bindings8144[key8144(mint, lane)]
            if (b != null && version > b.lastVersion) b.lastVersion = version
        }
    }

    /**
     * Mark [stage] for [lane]'s rescue of [mint]; false (and `unbound`) without its predecessor, or without a live binding
     * when [countUnbound] (execution stamps of non-rescue trades pass false: they are simply not rescues).
     */
    fun mark8144(mint: String, lane: String, stage: Stage8144, version: Long, nowMs: Long, countUnbound: Boolean = true): Boolean {
        if (stage == Stage8144.CLAIMED || stage == Stage8144.ADOPTED) return false
        val needs: Stage8144? = when (stage) {
            Stage8144.FDG_ALLOWED -> Stage8144.EVALUATED
            Stage8144.EXECUTED -> Stage8144.FDG_ALLOWED
            Stage8144.CONFIRMED -> Stage8144.EXECUTED
            else -> null
        }
        return synchronized(lock8144) {
            val b = bindings8144[key8144(mint, lane)]?.takeIf { current8144(it, version, nowMs) }
            when {
                b == null -> { if (countUnbound) unbound8144++; false }
                needs != null && (b.bits and bit8144(needs)) == 0 -> { unbound8144++; false }
                else -> count8144(lane, b, stage)
            }
        }
    }

    fun counts8144(lane: String): Map<String, Long> = synchronized(lock8144) {
        val c = laneCounts8144[lane.trim().uppercase()] ?: LongArray(Stage8144.values().size)
        Stage8144.values().associate { it.name to c[it.ordinal] }
    }

    /** Stages offered with no live binding (or before their predecessor): named, never dropped silently. */
    fun unboundCount8144(): Long = synchronized(lock8144) { unbound8144 }

    /** "c3/a0>s2>e2>f1>x1>k0" — claimed/adopted > scheduled > evaluated > fdgAllowed > executed > confirmed. */
    fun compact8144(lane: String): String = synchronized(lock8144) {
        compactOf8144(laneCounts8144[lane.trim().uppercase()] ?: LongArray(Stage8144.values().size))
    }

    private fun compactOf8144(c: LongArray): String =
        "c${c[Stage8144.CLAIMED.ordinal]}/a${c[Stage8144.ADOPTED.ordinal]}>s${c[Stage8144.SCHEDULED.ordinal]}>" +
            "e${c[Stage8144.EVALUATED.ordinal]}>f${c[Stage8144.FDG_ALLOWED.ordinal]}>x${c[Stage8144.EXECUTED.ordinal]}>k${c[Stage8144.CONFIRMED.ordinal]}"

    fun statusLine8144(): String = synchronized(lock8144) {
        val rows = laneCounts8144.entries.filter { e -> e.value.any { it > 0L } }.sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${compactOf8144(it.value)}" }
        "rescueFunnel8144(c/a>s>e>f>x>k) ${rows.ifBlank { "none" }} unbound=$unbound8144"
    }

    internal fun resetForTests8144() {
        synchronized(lock8144) { bindings8144.clear(); laneCounts8144.clear(); unbound8144 = 0L }
    }
}
