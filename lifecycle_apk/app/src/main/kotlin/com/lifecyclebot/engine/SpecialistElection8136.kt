package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8136 — ONE CAUSAL SPECIALIST ELECTION CONTRACT PER CANDIDATE.
 *
 * 5.0.8127 live: zero owner selections for QUALITY, BLUECHIP, SHITCOIN, CORE, MANIPULATED, TREASURY, CYCLIC,
 * DIP_HUNTER and PROJECT_SNIPER under heavy assessment; SHADOW_NOT_PRIMARY_8029 in the missed-runner audit (a +1207%
 * runner); CORE_YIELD_CLEAR_SPECIALIST_OWNER not reaching the named specialist; TREASURY role-floor refusals
 * suppressing runner theses. The hand-offs of 7960 / 8094 / 8119 / 8131 / 8132 were each a separate OR in front of the
 * lane gate: any number of READY lanes could be handed the coin at once, the named receiver of a yield was picked on
 * the RAW native score (CASHGEN's 76 on a 72 bar out-ranked MOONSHOT's 55 on a 30 bar), and a READY proposal that was
 * not let evaluate ended as a label (READY_NOT_EVALUATED_PRIMARY_x) with no terminal at all.
 *
 * One record per candidate version (mint + LaneExecutionCoordinator.candidateVersionFor). Every qualified lane proposal
 * (the lane's own authoritative native brain said eligible, or its resident book holds READY) terminates in exactly one
 * [Terminal8136]. Exactly one executable primary (the cycle owner, sealed / forced owners authoritative) and at most
 * ONE rescue: when the primary refuses (FDG, native, or gated before the FDG) the best other lane on its OWN scale
 * ([normalised8136]: ownership conviction relative to that lane's admission bar) with independent native evidence,
 * token safety passing and a sound receiver record is the rescue — evaluated before the candidate is concluded. A
 * judgement refusal (safety / rug / dev / bundle, or a token-wide FDG wall) is never rescued.
 *
 * Bookkeeping and arbitration of WHO may evaluate only. It never admits, sizes or executes: every lane gate, the FDG,
 * hard safety, sizing and the coordinator's one-owner election still decide. Bounded (LRU + TTL), one lock.
 *
 * V5.0.8144 — one contract end to end: DISCOVER > QUALIFY (registerNative8136) > NATIVE_ASSESS > OWNER_SELECT
 * (cyclePrimary8136) > PRIMARY_DECISION > OPTIONAL_RESCUE (claimRescueOutcome8144 / boundedRescue8141) > FDG
 * (onFdgVerdict8136) > MARK > SIZE > SEALED_TICKET > EXECUTE > CONFIRM (ElectionRescueFunnel8144 via the desk stages),
 * on ONE lineage per candidate: a clock-bucket roll no longer closes an unconcluded rescue / refusal / yield, the named
 * receiver of a fit refusal wins the slot, the lottery fills only an empty slot nobody with evidence wants, the
 * coordinator honours the elected rescue (executableRescue8144), and an arbitration that throws never executes.
 */
object SpecialistElection8136 {
    enum class Terminal8136(val rank: Int, val code: String) {
        DEFERRED_DATA(1, "DD"),
        CONTRIBUTOR_SHADOW(2, "S"),
        INELIGIBLE(3, "I"),
        SUPERSEDED(4, "X"),
        FDG_REFUSED(5, "F"),
        RESCUE_EVALUATED(6, "R"),
        ELECTED_EVALUATED(7, "E"),
    }

    /** One lane's claim on the candidate, already on its own lane scale ([normalised8136]). */
    data class Contender8136(val lane: String, val normalised: Double, val proven: Boolean = false)

    /** One lane's native assessment of the candidate. [ownable] = its trader may own execution (EnabledTraderAuthority). */
    data class NativeView8136(val eligible: Boolean, val normalised: Double, val reason: String, val ownable: Boolean = true)

    const val TRIGGER_FDG = "FDG"
    const val TRIGGER_NATIVE = "NATIVE"
    const val TRIGGER_PRE_FDG = "PRE_FDG"
    const val TRIGGER_CELL_PROVEN = "CELL_PROVEN"
    const val PROVEN_BONUS_8136 = 10.0
    internal const val OFFER_MS_8136 = 4_000L
    /** A primary noted this long without an FDG verdict or a refusal declined to reach the funnel: the rescue opens. */
    internal const val PRIMARY_SILENT_MS_8136 = 6_000L
    private const val MAX_RECORDS_8136 = 2_048
    private const val RECORD_TTL_MS_8136 = 120_000L
    private const val SWEEP_MS_8136 = 5_000L
    private const val FDG_REFUSAL_TTL_MS_8136 = 90_000L
    private const val NATIVE_REFUSED_8136 = "NATIVE_REFUSED:"
    /** CORE and CYCLIC have no per-token buy block (BotService RESCUE_EXCLUDED_8031): never the rescue. */
    val RESCUE_EXCLUDED_8136: Set<String> = setOf("CORE", "CYCLIC")

    /** FDG refusals that hold for every lane (token-wide wall / capital / identity): a rescue meets the same wall. */
    private val FDG_WALLS_8136 = listOf(
        "LIQUIDITY", "SELLAB", "CIRCUIT_BREAKER", "AUTO_TRADE_DISABLED", "SIZE_NOT_EXECUTABLE", "SIZE_TOO_SMALL",
        "CAPITAL", "WALLET", "TOKEN_2022", "HOSTILE", "IDENTITY", "PRICE_UNKNOWN", "REENTRY", "LOCKOUT", "DISTRIBUTION",
    )

    // ── pure ──

    /**
     * Pure: a lane's claim on ONE scale. [calibrated] (the lane's ownership conviction — 7948 lane bar / 7951 own
     * eligible population) wins when known; else [raw] relative to the lane's own admission [bar]: the bar reads 50,
     * 100 reads 100. With neither, the raw value (0..100).
     */
    fun normalised8136(raw: Double, bar: Double, calibrated: Double?): Double {
        if (calibrated != null && calibrated.isFinite()) return calibrated.coerceIn(0.0, 100.0)
        if (!raw.isFinite()) return 0.0
        val v = raw.coerceIn(0.0, 100.0)
        if (!bar.isFinite() || bar <= 0.0 || bar >= 100.0) return v
        return if (v >= bar) 50.0 + 50.0 * (v - bar) / (100.0 - bar) else 50.0 * v / bar
    }

    /** Pure: rescue rank — the lane's own-scale claim, + [PROVEN_BONUS_8136] when its own graded cell is proven (8094). */
    fun rescueRank8136(c: Contender8136): Double = c.normalised + if (c.proven) PROVEN_BONUS_8136 else 0.0

    /** Pure: the one rescue among [contenders] (never [primary], CORE or CYCLIC); ties break on the lane name. */
    fun electRescue8136(contenders: Collection<Contender8136>, primary: String): String? {
        val p = canon8136(primary)
        return contenders.asSequence()
            .map { it.copy(lane = canon8136(it.lane)) }
            .filter { it.lane.isNotBlank() && it.lane != p && it.lane !in RESCUE_EXCLUDED_8136 && it.normalised.isFinite() }
            .sortedWith(compareByDescending<Contender8136> { rescueRank8136(it) }.thenBy { it.lane })
            .firstOrNull()?.lane
    }

    /** V5.0.8141 — BotService's bounded-rescue verdict for one lane: who the rescue is ([rescue], null = none) and whether to claim now. */
    data class RescueSlot8141(val rescue: String?, val claim: Boolean)

    /**
     * Pure (canonical lane names): the candidate's ONE rescue as seen by [lane]'s evaluation. The election's [holder]
     * wins. ExecutionAuthorityPolicy6533.selectOneRescue's pick ([lottery]) only fills an EMPTY slot, and only through
     * the same claim: never the primary, CORE / CYCLIC, a lane this version already ruled ineligible, or after a
     * judgement refusal. A second rescue is refused (it used to evaluate and count as overflow).
     */
    fun rescueSlot8141(holder: String, lottery: String, lane: String, primary: String,
                       primaryJudgement: Boolean, laneIneligible: Boolean): RescueSlot8141 = when {
        holder.isNotBlank() -> RescueSlot8141(holder, false)
        lottery.isBlank() || lottery == primary || lottery in RESCUE_EXCLUDED_8136 -> RescueSlot8141(null, false)
        lane != lottery -> RescueSlot8141(lottery, false)
        primaryJudgement || laneIneligible -> RescueSlot8141(null, false)
        else -> RescueSlot8141(lottery, true)
    }

    /** Pure: an (upper-cased) FDG refusal that is a token-wide wall, not this lane's judgement of fit. */
    fun fdgWall8136(reasonUpper: String): Boolean = FDG_WALLS_8136.any { reasonUpper.contains(it) }

    private fun canon8136(lane: String?): String =
        try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { lane.orEmpty().trim().uppercase() }

    private fun short8136(reason: String?): String = reason.orEmpty().replace('\n', ' ').take(60)

    // ── ledger state (all under [lock8136]) ──

    private class Slot8136(val lane: String, var normalised: Double, var evidenceAtMs: Long) {
        var terminal: Terminal8136? = null
        var reason: String = ""
        var proven: Boolean = false
    }

    private class Record8136(val mint: String, var version: Long, var touchedAtMs: Long) {
        // V5.0.8144 — one candidate across clock buckets: the first version of this lineage and when it started.
        var lineageFrom: Long = version
        val lineageStartMs: Long = touchedAtMs
        // V5.0.8144 — the silent primary of the version this record replaced keeps its silence clock.
        var inheritedPrimary = ""
        var inheritedSinceMs = 0L
        var rescueViaLottery = false
        val slots = LinkedHashMap<String, Slot8136>()
        val assessed = HashSet<String>()
        val formerPrimaries = HashSet<String>()
        var primary = ""
        var primaryRefusal = ""
        var primaryJudgement = false
        var rescue = ""
        var rescueConsumed = false
        var offerTo = ""
        var offerAtMs = 0L
        var yieldFrom = ""
        var yieldTo = ""
        var yieldResolved = false
        var primarySinceMs = 0L
        var primaryVerdictSeen = false
        // V5.0.8153 — the lane whose rescue claim came from a qualified hand-off task (HandoffReceiverTasks8153).
        var handoffRescue8153 = ""
        // V5.0.8180 — when the rescue slot was claimed (an unconsumed holder past RescueIdle8180's window is idle).
        var rescueClaimedAtMs8180 = 0L
    }

    private val lock8136 = Any()
    private const val C_DISCOVERED = 0
    private const val C_QUALIFIED = 1
    private const val C_OPEN = 2
    private const val C_TERMINAL0 = 3
    private val laneCountTable8136 = HashMap<String, LongArray>()
    private val yieldRefusals8136 = HashMap<String, Long>()
    private val fdgRefusals8136 = ConcurrentHashMap<String, Pair<String, Long>>()
    private var lastSweepMs8136 = 0L
    private var rescueClaims8136 = 0L
    private var rescueAdopted8136 = 0L
    private var rescueOverflow8136 = 0L
    private var rescueHeldDenied8136 = 0L
    private var judgementNoRescue8136 = 0L
    private var outranked8136 = 0L
    private var staleEvents8136 = 0L
    private var unqualifiedFdg8136 = 0L
    private var yieldNamed8136 = 0L
    private var yieldReached8136 = 0L
    private var authoritativeOverrides8136 = 0L
    private var closedRecords8136 = 0L
    private var lotteryFilled8141 = 0L
    private var lotteryRefused8141 = 0L
    private var lineageCarried8144 = 0L
    private var lotteryBarred8144 = 0L
    private var namedWins8144 = 0L
    private var preempted8144 = 0L
    private var arbitrationErrors8144 = 0L
    private val gateReasons8144 = HashMap<String, String>()
    private var handoffNamed8153 = 0L
    private var handoffClaims8153 = 0L
    private var handoffPreempted8153 = 0L
    private var handoffDenied8153 = 0L
    private var handoffRetargeted8153 = 0L

    private val records8136 = object : LinkedHashMap<String, Record8136>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Record8136>?): Boolean {
            if (size <= MAX_RECORDS_8136 || eldest == null) return false
            close8136(eldest.value, "EVICTED_LRU")
            return true
        }
    }

    private fun counts8136(lane: String): LongArray =
        laneCountTable8136.getOrPut(lane) { LongArray(C_TERMINAL0 + Terminal8136.values().size) }

    private fun sweep8136(nowMs: Long) {
        if (nowMs - lastSweepMs8136 < SWEEP_MS_8136) return
        lastSweepMs8136 = nowMs
        val iter = records8136.entries.iterator()
        while (iter.hasNext()) {
            val r = iter.next().value
            if (nowMs - r.touchedAtMs > RECORD_TTL_MS_8136) { close8136(r, "EXPIRED"); iter.remove() }
        }
        if (fdgRefusals8136.size > 4_096) fdgRefusals8136.entries.removeIf { nowMs - it.value.second > FDG_REFUSAL_TTL_MS_8136 }
    }

    /** The record of [mint] at [version] (<= 0 = whichever is current). A newer version supersedes; an older one is stale (null). */
    private fun recordFor8136(mint: String, version: Long, nowMs: Long, create: Boolean = true): Record8136? {
        sweep8136(nowMs)
        val v = if (version > 0L) version else -1L
        val r = records8136[mint]
        if (r == null) {
            if (!create) return null
            return Record8136(mint, v, nowMs).also { records8136[mint] = it }
        }
        if (v <= 0L || v == r.version) { r.touchedAtMs = nowMs; return r }
        if (r.version <= 0L) { r.version = v; r.lineageFrom = v; r.touchedAtMs = nowMs; return r }
        if (v < r.version) {
            // V5.0.8144 — an earlier bucket of this same lineage (the clock rolled while the rescue was in flight) lands here.
            if (r.lineageFrom in 1L..v) { r.touchedAtMs = nowMs; return r }
            staleEvents8136++; return null
        }
        // V5.0.8144 — the clock is not a newer candidate: an unconcluded rescue / refusal / yield keeps its record.
        if (ElectionRescueContract8144.carriesLineage8144(refusalOpen8144(r), r.rescue.isNotEmpty() && !r.rescueConsumed,
                yieldOpen8144(r), nowMs - r.lineageStartMs)) {
            lineageCarried8144++
            r.version = v
            r.touchedAtMs = nowMs
            if (r.rescue.isNotEmpty()) ElectionRescueFunnel8144.extend8144(mint, r.rescue, v)
            return r
        }
        close8136(r, "NEWER_VERSION")
        return Record8136(mint, v, nowMs).also { next ->
            if (r.primary.isNotEmpty() && !r.primaryVerdictSeen && r.primaryRefusal.isEmpty() && r.primarySinceMs > 0L) {
                next.inheritedPrimary = r.primary
                next.inheritedSinceMs = r.primarySinceMs
            }
            records8136[mint] = next
        }
    }

    /** V5.0.8144 — the primary refused (not a judgement) and no rescue has been chosen yet. */
    private fun refusalOpen8144(r: Record8136): Boolean = r.primaryRefusal.isNotEmpty() && !r.primaryJudgement && r.rescue.isEmpty()

    /** V5.0.8144 — a fit refusal named a receiver that has not yet reached the funnel or ended by name. */
    private fun yieldOpen8144(r: Record8136): Boolean = r.yieldTo.isNotEmpty() && !r.yieldResolved

    /** V5.0.8144 — the named receiver while its yield is open ("" = none). */
    private fun namedOpen8144(r: Record8136): String = if (yieldOpen8144(r)) r.yieldTo else ""

    /** V5.0.8144 — the own-scale winner among the open proposals other than the primary (no refusal required). */
    private fun bestOpen8144(r: Record8136): String? = electRescue8136(r.slots.values.filter { s ->
        s.lane != r.primary &&
            (s.terminal == null || s.terminal == Terminal8136.CONTRIBUTOR_SHADOW || s.terminal == Terminal8136.DEFERRED_DATA)
    }.map { Contender8136(it.lane, it.normalised, it.proven) }, r.primary)

    /** The lane's proposal on this record; creating it counts one discovered + qualified + open proposal. */
    private fun slotFor8136(r: Record8136, lane: String, normalised: Double, evidenceAtMs: Long): Slot8136 {
        r.slots[lane]?.let { s ->
            if (normalised.isFinite() && evidenceAtMs >= s.evidenceAtMs) { s.normalised = normalised; s.evidenceAtMs = evidenceAtMs }
            return s
        }
        if (r.assessed.add(lane)) counts8136(lane)[C_DISCOVERED]++
        val c = counts8136(lane)
        c[C_QUALIFIED]++
        c[C_OPEN]++
        return Slot8136(lane, if (normalised.isFinite()) normalised else 0.0, evidenceAtMs).also { r.slots[lane] = it }
    }

    /** A terminal replaces another only when it ranks higher (duplicates and late lower verdicts are suppressed). */
    private fun terminate8136(s: Slot8136, t: Terminal8136, reason: String): Boolean {
        val old = s.terminal
        if (old != null && t.rank <= old.rank) return false
        val c = counts8136(s.lane)
        if (old == null) c[C_OPEN]-- else c[C_TERMINAL0 + old.ordinal]--
        c[C_TERMINAL0 + t.ordinal]++
        s.terminal = t
        s.reason = short8136(reason)
        return true
    }

    private fun reopen8136(s: Slot8136) {
        val old = s.terminal ?: return
        val c = counts8136(s.lane)
        c[C_TERMINAL0 + old.ordinal]--
        c[C_OPEN]++
        s.terminal = null
        s.reason = ""
    }

    /** Terminate and settle the yield when [s] is the named receiver of a fit refusal. */
    private fun end8136(r: Record8136, s: Slot8136, t: Terminal8136, reason: String) {
        terminate8136(s, t, reason)
        if (s.lane != r.yieldTo || r.yieldResolved) return
        when (s.terminal) {
            Terminal8136.ELECTED_EVALUATED, Terminal8136.RESCUE_EVALUATED, Terminal8136.FDG_REFUSED -> {
                r.yieldResolved = true
                yieldReached8136++
            }
            Terminal8136.INELIGIBLE -> yieldRefused8136(r, s.reason)
            else -> {}
        }
    }

    private fun yieldRefused8136(r: Record8136, reason: String) {
        if (r.yieldResolved) return
        r.yieldResolved = true
        val key = reason.substringBefore(':').uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48).ifBlank { "UNNAMED" }
        if (yieldRefusals8136.size > 64 && key !in yieldRefusals8136) return
        yieldRefusals8136[key] = (yieldRefusals8136[key] ?: 0L) + 1L
    }

    private fun setPrimary8136(r: Record8136, lane: String, nowMs: Long, keepSilence: Boolean = false) {
        if (lane.isBlank() || lane == r.primary) return
        // V5.0.8144 — the silence clock is the candidate's: a re-election without any verdict, or the same primary as the
        // silent one of the version this record replaced, does not restart it (a flipping owner kept the 6 s rescue shut).
        val silentSince = when {
            keepSilence && r.primarySinceMs > 0L && !r.primaryVerdictSeen -> r.primarySinceMs
            r.primary.isEmpty() && lane == r.inheritedPrimary && r.inheritedSinceMs > 0L -> r.inheritedSinceMs
            else -> nowMs
        }
        if (r.primary.isNotEmpty()) r.formerPrimaries.add(r.primary)
        r.formerPrimaries.remove(lane)
        r.primary = lane
        r.primarySinceMs = silentSince
        r.primaryVerdictSeen = false
        r.slots[lane]?.let { if (it.terminal == Terminal8136.CONTRIBUTOR_SHADOW || it.terminal == Terminal8136.DEFERRED_DATA) reopen8136(it) }
    }

    private fun claim8136(r: Record8136, lane: String, nowMs: Long, viaLottery: Boolean = false) {
        val s = slotFor8136(r, lane, Double.NaN, nowMs)
        if (s.terminal == Terminal8136.CONTRIBUTOR_SHADOW || s.terminal == Terminal8136.DEFERRED_DATA) reopen8136(s)
        r.rescue = lane
        r.rescueViaLottery = viaLottery
        r.rescueClaimedAtMs8180 = nowMs
        r.offerTo = ""
        rescueClaims8136++
        // V5.0.8144 — the rescue funnel: CLAIMED, bound to this record's version (and carried with its lineage).
        ElectionRescueFunnel8144.bind8144(r.mint, lane, r.version, adopted = false, nowMs = nowMs)
    }

    private var idleReleased8180 = 0L

    /**
     * V5.0.8180 — [r]'s rescue holder (not [asker]) claimed the slot more than RescueIdle8180.RESCUE_IDLE_MS_8180 ago and
     * never produced an FDG verdict: it ends CONTRIBUTOR_SHADOW (RESCUE_IDLE_RELEASED_8180) and the slot is empty. Returns
     * true when released. A consumed rescue is never released (one executable rescue per version).
     */
    private fun releaseIdle8180(r: Record8136, asker: String, nowMs: Long): Boolean {
        if (r.rescue.isEmpty() || r.rescue == asker || !RescueIdle8180.idle8180(r.rescueConsumed, r.rescueClaimedAtMs8180, nowMs)) return false
        r.slots[r.rescue]?.let { end8136(r, it, Terminal8136.CONTRIBUTOR_SHADOW, "RESCUE_IDLE_RELEASED_8180") }
        r.rescue = ""
        r.rescueViaLottery = false
        r.rescueClaimedAtMs8180 = 0L
        idleReleased8180++
        return true
    }

    private fun markIneligible8136(r: Record8136, s: Slot8136, why: String) {
        if (why.startsWith("DEFERRED_")) { end8136(r, s, Terminal8136.DEFERRED_DATA, why); return }
        end8136(r, s, Terminal8136.INELIGIBLE, why)
        if (r.rescue == s.lane && !r.rescueConsumed) r.rescue = ""
    }

    /** Close a record: open proposals end by name — the primary / rescue that never produced an FDG verdict, else superseded. */
    private fun close8136(r: Record8136, why: String) {
        for (s in r.slots.values) {
            if (s.terminal != null) continue
            when (s.lane) {
                r.primary -> terminate8136(s, Terminal8136.INELIGIBLE, "PRIMARY_NO_FDG_VERDICT")
                r.rescue -> terminate8136(s, Terminal8136.INELIGIBLE, "RESCUE_NO_FDG_VERDICT")
                else -> terminate8136(s, Terminal8136.SUPERSEDED, why)
            }
        }
        if (r.yieldTo.isNotEmpty() && !r.yieldResolved) {
            val s = r.slots[r.yieldTo]
            when (s?.terminal) {
                Terminal8136.ELECTED_EVALUATED, Terminal8136.RESCUE_EVALUATED, Terminal8136.FDG_REFUSED -> { r.yieldResolved = true; yieldReached8136++ }
                null -> yieldRefused8136(r, "RECEIVER_NO_NATIVE_EVIDENCE")
                else -> yieldRefused8136(r, "RECEIVER_${s?.terminal?.name}_${s?.reason}")
            }
        }
        closedRecords8136++
    }

    // ── events ──

    /** SpecialistBrainBridge7542: a fresh native assessment of every lane for this candidate version. */
    fun registerNative8136(mint: String, version: Long, evidenceAtMs: Long, views: Map<String, NativeView8136>, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || views.isEmpty()) return
        synchronized(lock8136) {
            val r = recordFor8136(mint, version, nowMs) ?: return
            for ((raw, v) in views) {
                val lane = canon8136(raw)
                if (lane.isBlank()) continue
                val existing = r.slots[lane]
                if (v.eligible) {
                    // Newer evidence lifts a native refusal of this same version.
                    if (existing != null && existing.terminal == Terminal8136.INELIGIBLE &&
                        existing.reason.startsWith(NATIVE_REFUSED_8136) && evidenceAtMs > existing.evidenceAtMs) reopen8136(existing)
                    val s = slotFor8136(r, lane, v.normalised, evidenceAtMs)
                    if (!v.ownable) markIneligible8136(r, s, "TRADER_DISABLED_7815")
                } else {
                    if (r.assessed.add(lane)) counts8136(lane)[C_DISCOVERED]++
                    if (existing != null && evidenceAtMs >= existing.evidenceAtMs) {
                        existing.evidenceAtMs = evidenceAtMs
                        markIneligible8136(r, existing, NATIVE_REFUSED_8136 + short8136(v.reason))
                    }
                }
            }
        }
    }

    /**
     * LaneExecutionCoordinator.readyOwnerForCycle8081: the cycle owner under the contract. A sealed / forced owner
     * ([authoritative]) is the primary. An owner re-elected after the primary refused IS the one rescue (when
     * [ownerIneligible] is null); a judgement refusal keeps the refused primary (no rescue); a held rescue slot keeps the
     * rescue — a third lane never takes the version. Returns the lane the cycle must run as its primary.
     */
    fun cyclePrimary8136(mint: String, version: Long, owner: String, authoritative: Boolean, ownerIneligible: String?,
                         nowMs: Long = System.currentTimeMillis()): String {
        val o = canon8136(owner)
        if (mint.isBlank() || o.isBlank()) return owner
        return synchronized(lock8136) {
            val r = recordFor8136(mint, version, nowMs) ?: return owner
            when {
                r.primary.isEmpty() -> { setPrimary8136(r, o, nowMs); owner }
                o == r.primary || o == r.rescue -> owner
                authoritative -> { authoritativeOverrides8136++; setPrimary8136(r, o, nowMs); owner }
                r.primaryRefusal.isEmpty() -> {
                    r.slots[r.primary]?.let { end8136(r, it, Terminal8136.CONTRIBUTOR_SHADOW, "PRIMARY_REELECTED_TO_$o") }
                    setPrimary8136(r, o, nowMs, keepSilence = true)
                    owner
                }
                r.primaryJudgement -> {
                    judgementNoRescue8136++
                    r.slots[o]?.let { end8136(r, it, Terminal8136.INELIGIBLE, "PRIMARY_JUDGEMENT:" + r.primaryRefusal) }
                    r.primary
                }
                r.rescue.isNotEmpty() && !releaseIdle8180(r, o, nowMs) -> {
                    rescueHeldDenied8136++
                    r.slots[o]?.let { end8136(r, it, Terminal8136.CONTRIBUTOR_SHADOW, "RESCUE_SLOT_HELD_BY_${r.rescue}") }
                    r.rescue
                }
                // V5.0.8144 — the refusing primary named its receiver: the coordinator's other READY pick does not take
                // the one slot from it (the receiver claims it through its own hand-off and every gate of its lane).
                namedOpen8144(r).let { it.isNotEmpty() && it != o } -> {
                    r.slots[o]?.let { end8136(r, it, Terminal8136.CONTRIBUTOR_SHADOW, "RESCUE_RESERVED_FOR_${r.yieldTo}") }
                    r.primary
                }
                ownerIneligible != null -> {
                    markIneligible8136(r, slotFor8136(r, o, Double.NaN, nowMs), ownerIneligible)
                    r.primary
                }
                else -> { claim8136(r, o, nowMs); owner }
            }
        }
    }

    /** The primary's native refusal (graded verdict of its own brain). [judgement] = never rescued. */
    fun notePrimaryRefusal8136(mint: String, primary: String, refusal: String, judgement: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val p = canon8136(primary)
        if (mint.isBlank() || p.isBlank()) return
        synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs) ?: return
            if (r.primary.isEmpty()) setPrimary8136(r, p, nowMs)
            if (r.primary != p) return
            if (r.primaryRefusal.isEmpty() || r.primaryRefusal.startsWith(TRIGGER_PRE_FDG)) r.primaryRefusal = short8136(refusal)
            if (judgement) r.primaryJudgement = true
        }
    }

    /**
     * A fit refusal by [from] names [to] as the specialist that wants the coin (null = nobody eligible). V5.0.8144 —
     * returns the receiver RECORDED for this candidate (the first name stands for the lineage, so a refreshed snapshot
     * that would rank another lane first cannot strand the receiver the primary already named), else [to].
     */
    fun noteYield8136(mint: String, from: String, to: String?, nowMs: Long = System.currentTimeMillis()): String? {
        if (mint.isBlank()) return to
        val f = canon8136(from)
        return synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs) ?: return to
            if (r.yieldFrom.isNotEmpty()) return if (r.yieldFrom == f) r.yieldTo.ifEmpty { null } else to
            r.yieldFrom = f
            val t = canon8136(to)
            if (t.isBlank()) { yieldRefused8136(r, "NO_ELIGIBLE_RECEIVER"); return null }
            r.yieldTo = t
            yieldNamed8136++
            r.slots[t]?.let { s -> s.terminal?.let { end8136(r, s, it, s.reason) } }
            t
        }
    }

    /**
     * A non-primary lane asks for the candidate's ONE rescue slot. [trigger]: the primary refused at the FDG / natively /
     * before the FDG, or the lane's own cell is proven. Granted only to the best eligible lane on its own scale; a
     * judgement refusal grants nobody; a winner that never claims loses its offer after [OFFER_MS_8136].
     */
    fun claimRescue8136(mint: String, lane: String, primary: String, trigger: String, refusal: String, refusalJudgement: Boolean,
                        contenders: List<Contender8136>, ineligible: String?, nowMs: Long = System.currentTimeMillis()): Boolean =
        claimRescueOutcome8144(mint, lane, primary, trigger, refusal, refusalJudgement, contenders, ineligible, nowMs).executable

    /**
     * V5.0.8144 — [claimRescue8136] with its named outcome (ElectionRescueContract8144.ClaimOutcome8144): only GRANTED /
     * HELD let the lane evaluate as the rescue. The receiver a fit refusal named wins the slot when eligible, and takes it
     * back from a lottery pick that has not been evaluated yet; a judgement refusal grants nobody.
     */
    fun claimRescueOutcome8144(mint: String, lane: String, primary: String, trigger: String, refusal: String, refusalJudgement: Boolean,
                               contenders: List<Contender8136>, ineligible: String?,
                               nowMs: Long = System.currentTimeMillis()): ElectionRescueContract8144.ClaimOutcome8144 {
        val l = canon8136(lane)
        val p = canon8136(primary)
        if (mint.isBlank() || l.isBlank() || l == p) return ElectionRescueContract8144.ClaimOutcome8144.DENIED_NO_PROPOSAL
        return synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs) ?: return ElectionRescueContract8144.ClaimOutcome8144.DENIED_NO_PROPOSAL
            if (r.primary.isEmpty()) setPrimary8136(r, p, nowMs)
            for (c in contenders) { val cl = canon8136(c.lane); if (cl.isNotBlank()) slotFor8136(r, cl, c.normalised, nowMs) }
            val self = r.slots[l] ?: return ElectionRescueContract8144.ClaimOutcome8144.DENIED_NO_PROPOSAL
            if (r.rescue == l) return ElectionRescueContract8144.ClaimOutcome8144.HELD
            if (ineligible != null) { markIneligible8136(r, self, ineligible); return ElectionRescueContract8144.ClaimOutcome8144.DENIED_INELIGIBLE }
            val named = namedOpen8144(r)
            if (r.rescue.isNotEmpty()) {
                // V5.0.8144 — the named receiver outranks a lottery hash pick that has not reached the FDG yet.
                val holder = r.slots[r.rescue]
                if (l == named && r.rescueViaLottery && !r.rescueConsumed && holder != null) {
                    preempted8144++
                    end8136(r, holder, Terminal8136.CONTRIBUTOR_SHADOW, "RESCUE_PREEMPTED_BY_NAMED_RECEIVER_$l")
                    r.rescue = ""
                } else if (releaseIdle8180(r, l, nowMs)) {
                    // V5.0.8180 — the holder never reached its FDG inside the idle window: the slot is open again.
                } else {
                    rescueHeldDenied8136++
                    return ElectionRescueContract8144.ClaimOutcome8144.DENIED_HELD_BY_OTHER
                }
            }
            val primaryTrigger = trigger != TRIGGER_CELL_PROVEN
            if (primaryTrigger && (r.primaryRefusal.isEmpty() || r.primaryRefusal.startsWith(TRIGGER_PRE_FDG))) {
                r.primaryRefusal = short8136("$trigger:$refusal")
            }
            if (primaryTrigger && refusalJudgement) r.primaryJudgement = true
            if (r.primaryJudgement) {
                judgementNoRescue8136++
                end8136(r, self, Terminal8136.INELIGIBLE, "PRIMARY_JUDGEMENT:" + r.primaryRefusal)
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_JUDGEMENT
            }
            if (trigger == TRIGGER_CELL_PROVEN) self.proven = true
            val offered = r.offerTo
            if (offered.isNotEmpty() && offered != l && nowMs - r.offerAtMs > OFFER_MS_8136) {
                r.slots[offered]?.let { end8136(r, it, Terminal8136.INELIGIBLE, "RESCUE_OFFER_UNCLAIMED") }
                r.offerTo = ""
            }
            val refused = r.primaryRefusal.isNotEmpty()
            val pool = r.slots.values.filter { s ->
                s.lane != r.primary && s.lane != p && (refused || s.proven || s.lane == named) &&
                    (s.terminal == null || s.terminal == Terminal8136.CONTRIBUTOR_SHADOW || s.terminal == Terminal8136.DEFERRED_DATA)
            }.map { Contender8136(it.lane, it.normalised, it.proven) }
            val winner = ElectionRescueContract8144.poolWinner8144(pool, r.primary, named.ifEmpty { null })
            if (winner != l) {
                outranked8136++
                if (winner != null && r.offerTo != winner) { r.offerTo = winner; r.offerAtMs = nowMs }
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_OUTRANKED
            }
            if (l == named) namedWins8144++
            claim8136(r, l, nowMs)
            ElectionRescueContract8144.ClaimOutcome8144.GRANTED
        }
    }

    /**
     * V5.0.8141 — BotService.laneEvaluationAllowed7951's bounded rescue through the election ([rescueSlot8141]): returns
     * the lane that holds this candidate's one rescue slot ([lane] in its own spelling when it is that lane), the
     * [lottery] pick when nothing is held and it is not this lane, or null. When [lane] is the lottery pick and the slot
     * is empty it claims the slot here, so no second lane evaluates as a rescue for this candidate version.
     */
    fun boundedRescue8141(mint: String, lane: String, primary: String, lottery: String?, nowMs: Long = System.currentTimeMillis()): String? {
        val l = canon8136(lane)
        val p = canon8136(primary)
        val pick = canon8136(lottery)
        if (mint.isBlank() || l.isBlank()) return lottery
        return synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs, create = pick.isNotBlank() && pick == l) ?: return lottery
            if (r.primary.isEmpty()) setPrimary8136(r, p, nowMs)
            // V5.0.8144 — the hash pick fills an empty slot only when no named receiver and no stronger lane with native
            // evidence wants it (it used to take the slot on the first cycle, before the primary refused or named anyone).
            val barred8144 = pick == l && r.rescue.isEmpty() &&
                !ElectionRescueContract8144.lotteryMayFill8144(l, namedOpen8144(r).ifEmpty { null }, bestOpen8144(r))
            if (barred8144) lotteryBarred8144++
            val slot = rescueSlot8141(r.rescue, pick, l, r.primary.ifEmpty { p }, r.primaryJudgement,
                r.slots[l]?.terminal == Terminal8136.INELIGIBLE || barred8144)
            if (slot.claim) {
                claim8136(r, l, nowMs, viaLottery = true)
                lotteryFilled8141++
            } else if (pick.isNotBlank() && pick == l && slot.rescue != l) {
                lotteryRefused8141++
            }
            val rescue = slot.rescue
            when {
                rescue == null -> null
                rescue == l -> lane
                rescue == pick -> lottery
                else -> rescue
            }
        }
    }

    /** V5.0.8141 — (lottery picks that filled the empty slot, lottery picks refused because the slot was held / barred). */
    fun lotteryCounts8141(): Pair<Long, Long> = synchronized(lock8136) { lotteryFilled8141 to lotteryRefused8141 }

    /** A lane may not take the rescue: [reason] (prefix DEFERRED_ = data missing, not a verdict on the lane). */
    fun markIneligible8136(mint: String, lane: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val l = canon8136(lane)
        if (mint.isBlank() || l.isBlank()) return
        synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs, create = false) ?: return
            val s = r.slots[l] ?: return
            markIneligible8136(r, s, reason)
        }
    }

    /**
     * BotService did not let [lane] evaluate this cycle (SpecialistOwnership7951.noteReadyNotEvaluated7951). The primary
     * gated before the FDG is a refusal (the rescue opens); a gated rescue releases the slot; anyone else is a shadow.
     */
    fun noteNotEvaluated8136(mint: String, lane: String, primary: String, ready: Boolean, normalised: Double, nowMs: Long = System.currentTimeMillis()) {
        val l = canon8136(lane)
        val p = canon8136(primary)
        if (mint.isBlank() || l.isBlank()) return
        synchronized(lock8136) {
            // V5.0.8144 — the lane gate BotService named for this refusal (noteGateReason8144), consumed once.
            val gate = gateReasons8144.remove("$mint|$l")
            val r = recordFor8136(mint, -1L, nowMs, create = ready || l == p) ?: return
            if (r.primary.isEmpty()) setPrimary8136(r, p, nowMs)
            val s = r.slots[l] ?: if (ready) slotFor8136(r, l, normalised, nowMs) else null
            if (l == p) {
                if (p == r.primary && r.primaryRefusal.isEmpty()) r.primaryRefusal = "$TRIGGER_PRE_FDG:PRIMARY_GATED"
                s?.let { end8136(r, it, Terminal8136.INELIGIBLE, if (gate == null) "PRIMARY_GATED_PRE_FDG" else "PRIMARY_GATED_PRE_FDG:$gate") }
                return
            }
            if (s == null) return
            if (l == r.rescue && !r.rescueConsumed) {
                r.rescue = ""
                end8136(r, s, Terminal8136.INELIGIBLE, if (gate == null) "RESCUE_GATED_PRE_FDG" else "RESCUE_GATED_PRE_FDG:$gate")
                return
            }
            // V5.0.8144 — the receiver a fit refusal named could not evaluate: its yield ends with that precise reason
            // (35 named yields, 0 arrivals, and no name for why on 5.0.8142).
            if (l == namedOpen8144(r)) {
                // V5.0.8153 — a receiver whose hand-off task is still live and was only kept waiting (slot held, not yet
                // handed) stays open: it is consumed on a later cycle, not stranded INELIGIBLE for the rest of the version.
                if (gate == null && HandoffReceiverTasks8153.isDue8153(mint, l, nowMs)) {
                    end8136(r, s, Terminal8136.CONTRIBUTOR_SHADOW,
                        if (r.rescue.isNotEmpty()) "RECEIVER_TASK_WAITING_SLOT_${r.rescue}" else "RECEIVER_TASK_PENDING_8153")
                    return
                }
                end8136(r, s, Terminal8136.INELIGIBLE, when {
                    gate != null -> "RECEIVER_GATED_$gate"
                    r.rescue.isNotEmpty() -> "RECEIVER_SLOT_HELD_BY_${r.rescue}"
                    else -> "RECEIVER_NOT_HANDED_OFF"
                })
                return
            }
            // The lane the rescue was offered to could not even evaluate: the offer passes on at once.
            if (l == r.offerTo) {
                r.offerTo = ""
                end8136(r, s, Terminal8136.INELIGIBLE, "RESCUE_OFFER_GATED_PRE_FDG")
                return
            }
            end8136(r, s, Terminal8136.CONTRIBUTOR_SHADOW, when {
                r.rescue.isNotEmpty() && r.rescue != l -> "RESCUE_SLOT_HELD_BY_${r.rescue}"
                gate != null -> "GATED_$gate"
                else -> "NOT_PRIMARY_$p"
            })
        }
    }

    /**
     * V5.0.8144 — BotService.laneEvaluationAllowed7951 refused [lane] for [mint] at a named lane gate (quarantine, the
     * 6604 consensus / pWin gates, the sniper's designated pool). The next noteNotEvaluated8136 for that lane ends its
     * proposal with this reason instead of the generic one. Total: never throws into the lane gate.
     */
    fun noteGateReason8144(mint: String, lane: String, reason: String) {
        try {
            val l = canon8136(lane)
            if (mint.isBlank() || l.isBlank() || reason.isBlank()) return
            synchronized(lock8136) {
                if (gateReasons8144.size > 1_024) gateReasons8144.clear()
                gateReasons8144["$mint|$l"] = short8136(reason)
            }
        } catch (_: Throwable) {}
    }

    /**
     * FinalDecisionGate verdict (SpecialistOwnership7951.onFdgVerdict7951). The primary ends ELECTED_EVALUATED or
     * FDG_REFUSED(reason); the rescue ends RESCUE_EVALUATED. A qualified lane reaching the FDG with no rescue claimed is
     * adopted as the rescue; a second one is counted as overflow. [qualified] = the lane holds native evidence.
     */
    fun onFdgVerdict8136(mint: String, lane: String, version: Long, shouldTrade: Boolean, reason: String?, refusalJudgement: Boolean,
                         qualified: Boolean, normalised: Double, nowMs: Long = System.currentTimeMillis()) {
        val l = canon8136(lane)
        if (mint.isBlank() || l.isBlank()) return
        val why = reason?.takeIf { it.isNotBlank() } ?: "FDG_NO_REASON"
        if (!shouldTrade) fdgRefusals8136["$l|$mint"] = why to nowMs
        synchronized(lock8136) {
            val r = recordFor8136(mint, version, nowMs) ?: return
            if (l == r.primary) r.primaryVerdictSeen = true
            val s = r.slots[l] ?: if (qualified) slotFor8136(r, l, normalised, nowMs) else { unqualifiedFdg8136++; return }
            if (r.primary.isEmpty()) { setPrimary8136(r, l, nowMs); r.primaryVerdictSeen = true }
            when {
                l == r.primary -> if (shouldTrade) end8136(r, s, Terminal8136.ELECTED_EVALUATED, "FDG_ALLOW") else {
                    end8136(r, s, Terminal8136.FDG_REFUSED, why)
                    if (r.primaryRefusal.isEmpty() || r.primaryRefusal.startsWith(TRIGGER_PRE_FDG)) r.primaryRefusal = short8136("$TRIGGER_FDG:$why")
                    if (refusalJudgement) r.primaryJudgement = true
                }
                // A lane that was this version's primary before a re-election keeps its primary verdict (it is no rescue).
                l in r.formerPrimaries -> end8136(r, s,
                    if (shouldTrade) Terminal8136.ELECTED_EVALUATED else Terminal8136.FDG_REFUSED, if (shouldTrade) "FDG_ALLOW_FORMER_PRIMARY" else why)
                l == r.rescue -> {
                    r.rescueConsumed = true
                    end8136(r, s, Terminal8136.RESCUE_EVALUATED, if (shouldTrade) "FDG_ALLOW" else "FDG_REFUSED:$why")
                    rescueEvaluated8144(r, l, shouldTrade, nowMs)
                }
                r.rescue.isEmpty() -> {
                    r.rescue = l
                    r.rescueConsumed = true
                    // Adoption = an FDG verdict with no prior claim; claims are counted separately (rescueClaims8136 /
                    // the funnel's CLAIMED), so `adopted` never reads as "claimed rescues that executed".
                    rescueAdopted8136++
                    end8136(r, s, Terminal8136.RESCUE_EVALUATED, if (shouldTrade) "ADOPTED_FDG_ALLOW" else "ADOPTED_FDG_REFUSED:$why")
                    ElectionRescueFunnel8144.bind8144(r.mint, l, r.version, adopted = true, nowMs = nowMs)
                    rescueEvaluated8144(r, l, shouldTrade, nowMs)
                }
                else -> {
                    rescueOverflow8136++
                    end8136(r, s, Terminal8136.RESCUE_EVALUATED, "OVERFLOW_SECOND_RESCUE:${if (shouldTrade) "ALLOW" else why}")
                }
            }
        }
    }

    /** V5.0.8144 — the rescue's FDG verdict in its funnel: EVALUATED, and FDG_ALLOWED on an allow. */
    private fun rescueEvaluated8144(r: Record8136, lane: String, allowed: Boolean, nowMs: Long) {
        ElectionRescueFunnel8144.mark8144(r.mint, lane, ElectionRescueFunnel8144.Stage8144.EVALUATED, r.version, nowMs)
        if (allowed) ElectionRescueFunnel8144.mark8144(r.mint, lane, ElectionRescueFunnel8144.Stage8144.FDG_ALLOWED, r.version, nowMs)
    }

    /**
     * V5.0.8144 — BotService.shouldRunBuyLaneForCycle let [lane] evaluate [mint]: when it holds the candidate's rescue,
     * the funnel's SCHEDULED stage (a claimed rescue that was never scheduled is a gate problem, not an FDG one).
     */
    fun noteScheduled8144(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()) {
        val l = canon8136(lane)
        if (mint.isBlank() || l.isBlank()) return
        // V5.0.8153 — a hand-off receiver's task was consumed by the cycle (primary or rescue alike).
        try { HandoffReceiverTasks8153.markScheduled8153(mint, l, nowMs) } catch (_: Throwable) {}
        val version = synchronized(lock8136) { records8136[mint]?.takeIf { it.rescue == l }?.version } ?: return
        ElectionRescueFunnel8144.mark8144(mint, l, ElectionRescueFunnel8144.Stage8144.SCHEDULED, version, nowMs)
    }

    /**
     * V5.0.8144 — ToolkitSignalSheet desk stage of a BUY attempt ("gen:MODE:mint:BUY:LANE:version:ticket"): a sealed
     * TICKET is the rescue's EXECUTED stage, EXEC (stamped on the confirmed fill) its CONFIRMED stage. Only a lane with
     * a live rescue binding for that mint and version whose FDG allowed counts; a primary's own trade is not a rescue
     * and is not counted at all (the binding is the record of the claim, so a bucket roll cannot lose the fill).
     */
    fun noteExecutionStage8144(lane: String, stage: String, attemptId: String, nowMs: Long = System.currentTimeMillis()) {
        val st = when (stage.uppercase()) {
            "TICKET" -> ElectionRescueFunnel8144.Stage8144.EXECUTED
            "EXEC" -> ElectionRescueFunnel8144.Stage8144.CONFIRMED
            else -> return
        }
        val a = ElectionRescueContract8144.parseBuyAttempt8144(attemptId) ?: return
        val l = canon8136(lane.ifBlank { a.lane })
        ElectionRescueFunnel8144.mark8144(a.mint, l, st, a.version, nowMs, countUnbound = false)
    }

    /**
     * V5.0.8144 — LaneExecutionCoordinator: [lane] is this candidate's executable owner as its rescue — the primary
     * refused (not a judgement), [lane] holds the one slot and has not been ruled ineligible. One owner per version:
     * the coordinator lets it replace only an UNSEALED pre-seal election.
     */
    fun executableRescue8144(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val l = canon8136(lane)
        if (mint.isBlank() || l.isBlank()) return false
        return synchronized(lock8136) {
            val r = records8136[mint]
            r != null && r.rescue == l && r.primary != l && !r.primaryJudgement && r.slots[l]?.terminal != Terminal8136.INELIGIBLE &&
                // V5.0.8153 — or the rescue is a hand-off receiver and the primary is the sender / sat silent past the window.
                (r.primaryRefusal.isNotEmpty() || handoffPrimaryOut8153(r, l, nowMs))
        }
    }

    /** V5.0.8153 — [l] took the slot as a hand-off receiver and the primary is out: it handed off itself, or it sat silent. */
    private fun handoffPrimaryOut8153(r: Record8136, l: String, nowMs: Long): Boolean =
        r.handoffRescue8153 == l && (r.primary == r.yieldFrom ||
            (!r.primaryVerdictSeen && r.primarySinceMs > 0L && nowMs - r.primarySinceMs > PRIMARY_SILENT_MS_8136))

    /** V5.0.8144 — true while [lane] is the receiver a fit refusal named and its yield is not settled. */
    fun isNamedReceiver8144(mint: String, lane: String): Boolean {
        val l = canon8136(lane)
        return l.isNotBlank() && synchronized(lock8136) { records8136[mint]?.let { namedOpen8144(it) == l } == true }
    }

    /**
     * V5.0.8144 — BotService desk qualification: the rescue holder and the named receiver are qualified by the election
     * itself (their own native brain is the independent evidence), so a missing desk hypothesis no longer short-circuits
     * them before the hand-off runs. Every other gate of their lane still decides.
     */
    fun electionQualified8144(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        holdsRescue8136(mint, lane) || isNamedReceiver8144(mint, lane) ||
            HandoffReceiverTasks8153.isDue8153(mint, canon8136(lane), nowMs)  // V5.0.8153 — a live hand-off task is its own qualification
    } catch (_: Throwable) { false }

    /** V5.0.8144 — a rescue arbitration threw: the lane's proposal ends DEFERRED by name, and it does not evaluate. */
    fun noteArbitrationError8144(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            synchronized(lock8136) { arbitrationErrors8144++ }
            markIneligible8136(mint, lane, "DEFERRED_RESCUE_ARBITRATION_ERROR", nowMs)
        } catch (_: Throwable) {}
    }

    // ── V5.0.8153 — a qualified hand-off receiver is a task the cycle consumes (HandoffReceiverTasks8153) ──

    /**
     * V5.0.8153 — the receiver a hand-off qualified (PROJECT_SNIPER phase / TOO_OLD, or a primary's fit yield) is this
     * candidate's named receiver even when the sender is not the cycle primary: the lottery may not fill the slot over it
     * (lotteryMayFill8144) and the coordinator's other READY pick does not take the version (cyclePrimary8136). The first
     * OPEN name stands; returns the lane the candidate names (null = none may be named).
     */
    fun nameHandoffReceiver8153(mint: String, from: String, receiver: String, nowMs: Long = System.currentTimeMillis()): String? {
        val t = canon8136(receiver)
        if (mint.isBlank() || t.isBlank() || t in RESCUE_EXCLUDED_8136) return null
        return synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs) ?: return null
            val open = namedOpen8144(r)
            if (open.isNotEmpty()) return open
            // The receiver already runs as the cycle primary: nothing to reserve (and the lottery is not barred by it).
            if (r.yieldTo == t || r.primary == t) return t
            if (r.yieldFrom.isEmpty()) r.yieldFrom = canon8136(from)
            r.yieldTo = t
            r.yieldResolved = false
            yieldNamed8136++
            handoffNamed8153++
            r.slots[t]?.let { s -> s.terminal?.let { end8136(r, s, it, s.reason) } }
            t
        }
    }

    /**
     * V5.0.8153 — the BotService lane gate consumes [lane]'s hand-off task: [lane] asks for the candidate's ONE rescue slot
     * as the qualified receiver. GRANTED / HELD evaluate; the primary itself is HELD (it evaluates anyway). A named
     * receiver takes the slot back from an unconsumed LOTTERY claim; an evidence claim or a consumed rescue keeps it
     * (one executable rescue, no second buyer); a judgement refusal grants nobody; [ineligible] (token safety / receiver
     * record / DEFERRED_ data) refuses by name.
     */
    fun claimHandoffReceiver8153(mint: String, lane: String, primary: String, ineligible: String?,
                                 nowMs: Long = System.currentTimeMillis()): ElectionRescueContract8144.ClaimOutcome8144 {
        val l = canon8136(lane)
        val p = canon8136(primary)
        if (mint.isBlank() || l.isBlank() || l in RESCUE_EXCLUDED_8136) return ElectionRescueContract8144.ClaimOutcome8144.DENIED_NO_PROPOSAL
        return synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs) ?: return ElectionRescueContract8144.ClaimOutcome8144.DENIED_NO_PROPOSAL
            if (r.primary.isEmpty() && p.isNotBlank()) setPrimary8136(r, p, nowMs)
            if (l == r.primary) return ElectionRescueContract8144.ClaimOutcome8144.HELD
            val self = slotFor8136(r, l, Double.NaN, nowMs)
            if (r.rescue == l) return ElectionRescueContract8144.ClaimOutcome8144.HELD
            if (self.terminal == Terminal8136.INELIGIBLE) {
                handoffDenied8153++
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_INELIGIBLE
            }
            if (ineligible != null) {
                markIneligible8136(r, self, ineligible)
                handoffDenied8153++
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_INELIGIBLE
            }
            if (r.primaryJudgement) {
                judgementNoRescue8136++
                handoffDenied8153++
                end8136(r, self, Terminal8136.INELIGIBLE, "PRIMARY_JUDGEMENT:" + r.primaryRefusal)
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_JUDGEMENT
            }
            val named = namedOpen8144(r)
            if (named.isNotEmpty() && named != l) {
                handoffDenied8153++
                return ElectionRescueContract8144.ClaimOutcome8144.DENIED_OUTRANKED
            }
            if (r.rescue.isNotEmpty()) {
                val holder = r.slots[r.rescue]
                if (r.rescueViaLottery && !r.rescueConsumed && holder != null) {
                    preempted8144++
                    handoffPreempted8153++
                    end8136(r, holder, Terminal8136.CONTRIBUTOR_SHADOW, "RESCUE_PREEMPTED_BY_HANDOFF_RECEIVER_$l")
                    r.rescue = ""
                } else if (releaseIdle8180(r, l, nowMs)) {
                    // V5.0.8180 — an idle holder (no FDG verdict inside the window) no longer blocks the named receiver.
                } else {
                    rescueHeldDenied8136++
                    handoffDenied8153++
                    return ElectionRescueContract8144.ClaimOutcome8144.DENIED_HELD_BY_OTHER
                }
            }
            if (named.isEmpty()) {
                if (r.yieldFrom.isEmpty()) r.yieldFrom = "HANDOFF"
                r.yieldTo = l
                r.yieldResolved = false
                yieldNamed8136++
            }
            claim8136(r, l, nowMs)
            r.handoffRescue8153 = l
            handoffClaims8153++
            ElectionRescueContract8144.ClaimOutcome8144.GRANTED
        }
    }

    /**
     * V5.0.8153 — the hand-off task moved from [from] (it refused on fit) to [to] ("" = no compatible receiver left): the
     * named receiver follows, a rescue slot [from] held unconsumed is released, and [from]'s proposal ends by name.
     */
    fun retargetReceiver8153(mint: String, from: String, to: String, why: String, nowMs: Long = System.currentTimeMillis()) {
        val o = canon8136(from)
        val n = canon8136(to)
        if (mint.isBlank() || o.isBlank()) return
        synchronized(lock8136) {
            val r = recordFor8136(mint, -1L, nowMs, create = false) ?: return
            handoffRetargeted8153++
            val wasNamed = r.yieldTo == o && !r.yieldResolved
            if (wasNamed && n.isNotBlank()) r.yieldTo = n
            if (r.rescue == o && !r.rescueConsumed) r.rescue = ""
            r.slots[o]?.let { s ->
                if (s.terminal == null || s.terminal == Terminal8136.CONTRIBUTOR_SHADOW) terminate8136(s, Terminal8136.INELIGIBLE, "HANDOFF_FIT_REFUSED:$why")
            }
            if (wasNamed && n.isBlank()) yieldRefused8136(r, "FIT_NO_COMPATIBLE_RECEIVER")
        }
    }

    /** V5.0.8153 — the lane gate BotService named for [lane] (noteGateReason8144), read without consuming it. */
    fun gateReasonOf8153(mint: String, lane: String): String? =
        synchronized(lock8136) { gateReasons8144["$mint|${canon8136(lane)}"] }

    // ── reads ──

    /** The last FDG refusal reason of [lane] for [mint] within the hand-off window. */
    fun lastFdgRefusal8136(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): String? =
        fdgRefusals8136["${canon8136(lane)}|$mint"]?.takeIf { nowMs - it.second in 0..FDG_REFUSAL_TTL_MS_8136 }?.first

    /** The candidate's current primary ("" = none yet). */
    fun primaryOf8136(mint: String): String = synchronized(lock8136) { records8136[mint]?.primary.orEmpty() }

    /** The candidate's rescue lane ("" = none). */
    fun rescueOf8136(mint: String): String = synchronized(lock8136) { records8136[mint]?.rescue.orEmpty() }

    /** True when [lane] holds this candidate's one rescue slot. */
    fun holdsRescue8136(mint: String, lane: String): Boolean {
        val l = canon8136(lane)
        return l.isNotBlank() && synchronized(lock8136) { records8136[mint]?.rescue == l }
    }

    /** True when the cycle's primary [primary] was gated before the FDG this version (a refusal that opens the rescue). */
    fun primaryGatedPreFdg8136(mint: String, primary: String): Boolean {
        val p = canon8136(primary)
        return synchronized(lock8136) {
            val r = records8136[mint]
            r != null && r.primary == p && r.primaryRefusal.startsWith(TRIGGER_PRE_FDG)
        }
    }

    /**
     * True when the cycle's primary [primary] has been the primary for [PRIMARY_SILENT_MS_8136] with no FDG verdict and no
     * refusal: its own branch declined to reach the decision funnel (no BUY intent), so the rescue is evaluated too.
     */
    fun primarySilent8136(mint: String, primary: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = canon8136(primary)
        return synchronized(lock8136) {
            val r = records8136[mint]
            r != null && r.primary == p && !r.primaryVerdictSeen && r.primaryRefusal.isEmpty() && r.primarySinceMs > 0L &&
                nowMs - r.primarySinceMs > PRIMARY_SILENT_MS_8136
        }
    }

    /** False when [lane]'s proposal for this version already ended INELIGIBLE (it may not own the candidate). */
    fun mayOwn8136(mint: String, lane: String): Boolean {
        val l = canon8136(lane)
        return synchronized(lock8136) { records8136[mint]?.slots?.get(l)?.terminal != Terminal8136.INELIGIBLE }
    }

    /** The terminal (null = still open) and its reason; null when [lane] holds no proposal for the current version. */
    fun terminalOf8136(mint: String, lane: String): Pair<Terminal8136?, String>? {
        val l = canon8136(lane)
        return synchronized(lock8136) { records8136[mint]?.slots?.get(l)?.let { it.terminal to it.reason } }
    }

    /** Per-lane counters: discovered, qualified, open and one per terminal (Terminal8136.name). */
    fun laneCounts8136(lane: String): Map<String, Long> {
        val l = canon8136(lane)
        return synchronized(lock8136) {
            val c = laneCountTable8136[l] ?: LongArray(C_TERMINAL0 + Terminal8136.values().size)
            val out = linkedMapOf("DISCOVERED" to c[C_DISCOVERED], "QUALIFIED" to c[C_QUALIFIED], "OPEN" to c[C_OPEN])
            Terminal8136.values().forEach { out[it.name] = c[C_TERMINAL0 + it.ordinal] }
            out
        }
    }

    private fun compact8136(c: LongArray): String =
        "D${c[C_DISCOVERED]}>Q${c[C_QUALIFIED]}>" +
            listOf(Terminal8136.ELECTED_EVALUATED, Terminal8136.FDG_REFUSED, Terminal8136.RESCUE_EVALUATED, Terminal8136.CONTRIBUTOR_SHADOW,
                Terminal8136.SUPERSEDED, Terminal8136.DEFERRED_DATA, Terminal8136.INELIGIBLE)
                .joinToString("/") { "${it.code}${c[C_TERMINAL0 + it.ordinal]}" } + " open${c[C_OPEN]}"

    /** "D12>Q5>E0/F1/R1/S2/X0/DD0/I1 open0" — Discovered > NativeQualified > Elected/FdgRefused/Rescue/Shadow/Superseded/Deferred/Ineligible. */
    fun laneCompact8136(lane: String): String {
        val l = canon8136(lane)
        return synchronized(lock8136) { compact8136(laneCountTable8136[l] ?: LongArray(C_TERMINAL0 + Terminal8136.values().size)) }
    }

    fun statusLine8136(): String = synchronized(lock8136) {
        val lanes = laneCountTable8136.entries.filter { it.value[C_DISCOVERED] > 0L || it.value[C_QUALIFIED] > 0L }
            .sortedByDescending { it.value[C_QUALIFIED] }
            .joinToString(" ; ") { "${it.key}=${compact8136(it.value)}" }
        val refused = yieldRefusals8136.entries.sortedByDescending { it.value }.take(4).joinToString(",") { "${it.key}=${it.value}" }
        "election8136 records=${records8136.size} closed=$closedRecords8136 rescue(claim=$rescueClaims8136 adopted=$rescueAdopted8136 " +
            "overflow=$rescueOverflow8136 heldDenied=$rescueHeldDenied8136 outranked=$outranked8136 judgementNone=$judgementNoRescue8136) " +
            "yield(named=$yieldNamed8136 reached=$yieldReached8136 refused={${refused.ifBlank { "-" }}}) authoritative=$authoritativeOverrides8136 " +
            "stale=$staleEvents8136 unqualifiedFdg=$unqualifiedFdg8136 | ${lanes.ifBlank { "no proposals yet" }}" +
            // V5.0.8144 — how the one slot was decided, and arbitration failures (never executable).
            " | slot8144(namedWins=$namedWins8144 preempted=$preempted8144 lotteryBarred=$lotteryBarred8144 " +
            "lineageCarried=$lineageCarried8144 arbitrationErrors=$arbitrationErrors8144 idleReleased8180=$idleReleased8180)" +
            // V5.0.8153 — qualified hand-off receivers as tasks the cycle consumes.
            " | handoff8153(named=$handoffNamed8153 claims=$handoffClaims8153 preemptedLottery=$handoffPreempted8153 " +
            "denied=$handoffDenied8153 retargeted=$handoffRetargeted8153) "
    } + (try { HandoffReceiverTasks8153.statusLine8153() } catch (_: Throwable) { "handoffTask8153[unavailable]" })  // read outside the election lock

    internal fun resetForTests8136() {
        synchronized(lock8136) {
            records8136.clear(); laneCountTable8136.clear(); yieldRefusals8136.clear(); fdgRefusals8136.clear()
            lastSweepMs8136 = 0L; rescueClaims8136 = 0L; rescueAdopted8136 = 0L; rescueOverflow8136 = 0L; rescueHeldDenied8136 = 0L
            judgementNoRescue8136 = 0L; outranked8136 = 0L; staleEvents8136 = 0L; unqualifiedFdg8136 = 0L; yieldNamed8136 = 0L
            yieldReached8136 = 0L; authoritativeOverrides8136 = 0L; closedRecords8136 = 0L
            lotteryFilled8141 = 0L; lotteryRefused8141 = 0L
            lineageCarried8144 = 0L; lotteryBarred8144 = 0L; namedWins8144 = 0L; preempted8144 = 0L; arbitrationErrors8144 = 0L
            idleReleased8180 = 0L
            gateReasons8144.clear()
            handoffNamed8153 = 0L; handoffClaims8153 = 0L; handoffPreempted8153 = 0L; handoffDenied8153 = 0L; handoffRetargeted8153 = 0L
        }
        HandoffReceiverTasks8153.resetForTests8153()
        ElectionRescueFunnel8144.resetForTests8144()
    }
}
