package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8190 §SPECIALIST_HANDOFFS_REACH_THE_RECEIVER.
 *
 * Live 5.0.8186 (owner diagnosis + evidence8186b): handoffTask8153 opened=400 scheduled=118 evaluated=3 refused=335 —
 * refusedBy STALE_NOT_SCHEDULED=117, RECEIVER_REFUSED:RECEIVER_RECORD_UNSOUND=81; election yields refused
 * RECEIVER_SUPERSEDED_EXPIRED=133. Causes read in code:
 *  - A task is bound to its candidate version, and LaneExecutionCoordinator.candidateVersionFor is the 30 s wall-clock
 *    bucket until a seal. A task opened in bucket N was no longer due in bucket N+1 (isDue8153 compares versions), so the
 *    lane gate never admitted it, onNotScheduled8153 never even named it, and it lapsed STALE_NOT_SCHEDULED. The clock is
 *    not a newer candidate (7276): a live task now CARRIES to the newer version ([HandoffReceiverTasks8153.carry8190]) —
 *    inside its own unchanged validity, never prolonged. A newer hand-off that replaces a pending task carries its
 *    lineage too and is counted CARRIED, not REFUSED (SUPERSEDED_FRESH_VERSION_8170 used to end it as a refusal).
 *  - The receiver's mint left the per-cycle work set: BotService's priority window rotates 128 of the watchlist per cycle
 *    and the supervisor slice takes fresh / unseen / cold mints, so a hand-off target could go unprocessed for the whole
 *    120 s election record (RECEIVER_SUPERSEDED_EXPIRED: the record was never touched again). [HandoffPriority8190] puts
 *    every live task target and open election yield in the next cycle's window and slice (bounded).
 *  - RECEIVER_RECORD_UNSOUND_8094 read the lane's NO_TRIGGER cohort as "the coin's strategy" when no setup fired only
 *    because the setups' inputs were unobserved (all-feature coverage 2%), and read a missing TokenState as unsound.
 *    [ReceiverRecord8190] defers those (re-fetching the inputs once) and keeps a genuine losing record a refusal.
 *  - Several native "refusals" were data gaps (Insufficient data / thin_data / RC pending / unknown h1 / *_UNKNOWN /
 *    *_STALE), lane floors / bands another eligible lane may meet (LANE_LIQ_BELOW_FLOOR, HIGH_MCAP ...), or a plain WAIT
 *    with no reason — all ended the task as the receiver's judgement. [HandoffRefusal8190] classifies them: data defers
 *    and re-fetches, a floor / band hands on to the next eligible receiver, a WAIT keeps the task until its validity ends.
 *    A judgement made on a mark older than [STALE_EVIDENCE_MS_8190] defers and asks for a fresh mark.
 * Safety, rug, dev, bundle, freeze, liquidity collapse and every other binding refusal stay binding; no gate, size or
 * FDG rule changes. [HandoffTrace8190] keeps the per-task causal trace (receiver, version, evidence age, terminal).
 */
object HandoffRefusal8190 {

    /** A judgement made on a mark older than this is a judgement on stale data. */
    const val STALE_EVIDENCE_MS_8190 = 60_000L
    const val STALE_PREFIX_8190 = "DEFERRED_STALE_EVIDENCE_8190:"
    const val NATIVE_WAIT_PREFIX_8190 = "NATIVE_WAIT_8190:"

    /** Missing or stale data named in a native reason (normalised upper-case, spaces as '_'). */
    private val DATA_WORDS_8190 = listOf("INSUFFICIENT_DATA", "THIN_DATA", "EARLY_UNKNOWN", "UNKNOWN_H1", "RC_PENDING",
        "HYDRATING", "UNOBSERVED", "UNKNOWN_DEFER", "_UNKNOWN", "UNKNOWN_", "_STALE", "STALE_", "NOT_HYDRATED", "NO_HISTORY")
    /** A lane's own floor / band / window — another natively eligible lane may meet it: the task is handed on. */
    private val SCOPE_WORDS_8190 = listOf("BELOW_FLOOR", "ABOVE_CEILING", "OUT_OF_BAND", "OUT_OF_SCOPE", "NOT_IN_SCOPE",
        "LOW_MCAP", "HIGH_MCAP", "LOW_LIQ", "HIGH_LIQ", "LIQ_TOO_LOW", "TOKEN_TOO_OLD", "GRADUATED", "NOT_A_LAUNCH")
    /** "Not yet": the receiver waits for its own window. */
    private val NOT_YET_WORDS_8190 = listOf("TOO_FRESH", "(WAITING)", "WAITING")
    /** The receiving lane's own learned judgement — never re-read. */
    private val LANE_JUDGEMENT_8190 = listOf("MEME_CONSENSUS_HARD_BLOCK", "MEME_PWIN_GATE", "RECEIVER_RECORD_UNSOUND")

    private fun norm8190(reason: String?): String = reason?.trim()?.uppercase()?.replace(' ', '_').orEmpty()

    /**
     * Pure: [HandoffReceiverTasks8153.classify8153], then a JUDGEMENT that names missing / stale data is DATA, one that
     * names a lane floor / band is FIT (handed on), and a "not yet" is WAIT. Every other outcome is unchanged (binding
     * stays binding: it is decided before any of this).
     */
    fun classify8190(reason: String?, fit: Boolean, binding: Boolean): HandoffReceiverTasks8153.Block8153 {
        val base = HandoffReceiverTasks8153.classify8153(reason, fit, binding)
        if (base != HandoffReceiverTasks8153.Block8153.JUDGEMENT) return base
        val r = norm8190(reason)
        if (LANE_JUDGEMENT_8190.any { r.startsWith(it) }) return base
        return when {
            DATA_WORDS_8190.any { r.contains(it) } -> HandoffReceiverTasks8153.Block8153.DATA
            NOT_YET_WORDS_8190.any { r.contains(it) } -> HandoffReceiverTasks8153.Block8153.WAIT
            SCOPE_WORDS_8190.any { r.contains(it) } -> HandoffReceiverTasks8153.Block8153.FIT
            else -> base
        }
    }

    /** Pure: a mark is stale for a judgement (no price, never stamped, or older than [STALE_EVIDENCE_MS_8190]). */
    fun staleMark8190(lastPrice: Double, lastPriceUpdateMs: Long, nowMs: Long): Boolean =
        !(lastPrice.isFinite() && lastPrice > 0.0) || lastPriceUpdateMs <= 0L || nowMs - lastPriceUpdateMs > STALE_EVIDENCE_MS_8190

    /**
     * Pure: a JUDGEMENT / WAIT met on a stale mark is a data deferral; anything else (DATA, FIT, BINDING) is unchanged.
     */
    fun staleAware8190(block: HandoffReceiverTasks8153.Block8153, stale: Boolean): HandoffReceiverTasks8153.Block8153 =
        if (stale && (block == HandoffReceiverTasks8153.Block8153.JUDGEMENT || block == HandoffReceiverTasks8153.Block8153.WAIT))
            HandoffReceiverTasks8153.Block8153.DATA else block

    /** The receiver's TokenState mark is stale now (no TokenState: not judged stale — nothing to read). Asks for a fresh mark. */
    fun markStaleFor8190(mint: String, nowMs: Long): Boolean {
        val ts = HandoffRoute8180.tokenFor8180(mint) ?: return false
        val stale = staleMark8190(ts.lastPrice, ts.lastPriceUpdate, nowMs)
        if (stale) {
            try { com.lifecyclebot.engine.truth.CandidateMarkFresh8094.ensureFresh8094(ts, nowMs) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("HANDOFF_STALE_EVIDENCE_DEFERRED_8190") } catch (_: Throwable) {}
        }
        return stale
    }

    /** Pure: the outcome class a settled task's verdict belongs to (for the trace report). */
    fun kindOf8190(outcome: HandoffReceiverTasks8153.Outcome8153, verdict: String): String = when {
        outcome == HandoffReceiverTasks8153.Outcome8153.EVALUATED -> "FDG"
        outcome == HandoffReceiverTasks8153.Outcome8153.DEFERRED_DATA -> "DATA"
        verdict.startsWith("BINDING:") || verdict.startsWith("VERSION_SEALED") -> "BINDING"
        verdict.startsWith("FIT_NO_COMPATIBLE_RECEIVER") -> "SCOPE"
        verdict.startsWith("RECEIVER_REFUSED:") -> "JUDGEMENT"
        verdict.startsWith("SCHEDULED_NO_FDG") || verdict.startsWith("NOT_SCHEDULED") || verdict.startsWith("STALE_NOT_SCHEDULED") -> "UNEVALUATED"
        else -> "OTHER"
    }
}

/**
 * V5.0.8190 — the receiver's own record for this coin, read on complete data. RECEIVER_RECORD_UNSOUND_8094 stands only
 * when the coin's classified setup in the receiving lane is losing on its own 12+ labels; a NO_TRIGGER read whose setups
 * could not fire because their inputs are unobserved first defers and re-fetches those inputs (once per mint and lane
 * inside [INPUT_GRACE_MS_8190]: after that the record is judged on the best data obtained), and a missing TokenState is
 * a data deferral, never "unsound".
 */
object ReceiverRecord8190 {
    const val UNSOUND_8190 = "RECEIVER_RECORD_UNSOUND_8094"
    const val DEFER_NO_STATE_8190 = "DEFERRED_RECEIVER_RECORD_NO_TOKEN_STATE_8190"
    const val DEFER_INPUTS_8190 = "DEFERRED_RECEIVER_SETUP_INPUTS_8190"
    const val INPUT_GRACE_MS_8190 = 60_000L
    private const val MAX_8190 = 2_048

    private val inputDeferredAt8190 = ConcurrentHashMap<String, Long>()
    private val counts8190 = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure: null = sound. [negativeSetup] = LanePlaybook7907.pickedSetupNegative8094; [missingInputs] = the unobserved
     * inputs of a NO_TRIGGER read; [firstDeferAtMs] = when this mint/lane first deferred for inputs (null = never).
     */
    fun verdict8190(hasState: Boolean, provenSetup: Boolean, negativeSetup: String?, missingInputs: List<String>,
                    firstDeferAtMs: Long?, nowMs: Long): String? = when {
        !hasState -> DEFER_NO_STATE_8190
        provenSetup || negativeSetup == null -> null
        negativeSetup == com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER && missingInputs.isNotEmpty() &&
            (firstDeferAtMs == null || nowMs - firstDeferAtMs in 0L..INPUT_GRACE_MS_8190) -> DEFER_INPUTS_8190
        else -> UNSOUND_8190
    }

    /** SpecialistOwnership7951: the receiver-record verdict for [lane] on [mint] now (null = sound). Never throws. */
    fun check8190(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): String? = try {
        val ts = HandoffRoute8180.tokenFor8180(mint)
        if (ts == null) verdict8190(false, false, null, emptyList(), null, nowMs).also { count8190(it) } else {
            val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
            val proven = pb.provenSetup8056(ts, lane, nowMs) != null
            val negative = if (proven) null else pb.pickedSetupNegative8094(ts, lane, nowMs)
            val missing = if (negative == pb.NO_TRIGGER) (pb.classify8084(ts, lane, nowMs)?.missing ?: emptyList()) else emptyList()
            val key = "${ts.mint}|${lane.uppercase()}"
            val v = verdict8190(true, proven, negative, missing, inputDeferredAt8190[key], nowMs)
            if (v == DEFER_INPUTS_8190) {
                if (inputDeferredAt8190.putIfAbsent(key, nowMs) == null) {
                    if (inputDeferredAt8190.size > MAX_8190) inputDeferredAt8190.entries.removeIf { nowMs - it.value > 10 * INPUT_GRACE_MS_8190 }
                    try { DataDeferFetch8180.request8180(ts, missing, nowMs) } catch (_: Throwable) {}
                }
            }
            count8190(v)
            v
        }
    } catch (_: Throwable) { null }

    private fun count8190(v: String?) {
        val k = v ?: "SOUND"
        counts8190.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RECEIVER_RECORD_8190_$k") } catch (_: Throwable) {}
    }

    fun counts8190(): Map<String, Long> = counts8190.mapValues { it.value.get() }

    internal fun resetForTests8190() { inputDeferredAt8190.clear(); counts8190.clear() }
}

/**
 * V5.0.8190 — a live hand-off target is processed in the next cycle. BotService's priority window (128 rotating) and
 * the supervisor slice (fresh / unseen / cold) did not know a receiver task or an open election yield existed.
 * Bounded: at most [MAX_TARGETS_8190] mints per cycle, soonest-expiring first, only mints already in the caller's list.
 */
object HandoffPriority8190 {
    const val MAX_TARGETS_8190 = 12

    /** Pure: [targets] (mint -> validUntil, sooner first) restricted to [present], bounded. */
    fun select8190(targets: List<Pair<String, Long>>, present: Collection<String>, max: Int = MAX_TARGETS_8190): List<String> {
        if (targets.isEmpty() || present.isEmpty()) return emptyList()
        val set = present as? Set<String> ?: present.toHashSet()
        return targets.asSequence().filter { it.first in set }.sortedBy { it.second }.map { it.first }.distinct().take(max).toList()
    }

    /** Every live receiver task target and open election yield now (mint -> deadline). */
    private fun targets8190(nowMs: Long): List<Pair<String, Long>> {
        val tasks = try { HandoffReceiverTasks8153.pendingTargets8190(nowMs) } catch (_: Throwable) { emptyList() }
        val yields = try { SpecialistElection8136.openYieldMints8190().map { it to nowMs + 120_000L } } catch (_: Throwable) { emptyList() }
        return tasks + yields
    }

    /** BotService priority window: the due targets among [all] (always inside the window, like open positions). */
    fun windowTargets8190(all: Collection<String>, nowMs: Long = System.currentTimeMillis()): List<String> =
        try { select8190(targets8190(nowMs), all) } catch (_: Throwable) { emptyList() }

    /** BotService.selectOrderedMintsForCycle: the due targets among [otherMints], put in the slice first. */
    fun dueTargets8190(otherMints: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> =
        windowTargets8190(otherMints, nowMs).also { if (it.isNotEmpty()) try { PipelineHealthCollector.labelInc("HANDOFF_TARGET_PRIORITISED_8190") } catch (_: Throwable) {} }
}

/** V5.0.8190 — the per-task causal trace: receiver, candidate version, evidence age, terminal reason (bounded ring). */
object HandoffTrace8190 {
    data class Trace8190(val mint: String, val receiver: String, val version: Long, val evidenceAgeMs: Long,
                         val outcome: String, val verdict: String, val kind: String, val schedules: Int, val atMs: Long)

    private const val RING_8190 = 64
    private val ring8190 = ArrayDeque<Trace8190>()
    private val kinds8190 = ConcurrentHashMap<String, AtomicLong>()
    private val carried8190 = AtomicLong(0L)

    /** Pure: the trace of a settled task. */
    fun traceOf8190(t: HandoffReceiverTasks8153.Task8153): Trace8190 = Trace8190(
        t.mint, t.lane, t.version, if (t.refreshedAtMs > 0L) (t.settledAtMs - t.refreshedAtMs).coerceAtLeast(0L) else -1L,
        t.outcome.name, t.verdict.take(70), HandoffRefusal8190.kindOf8190(t.outcome, t.verdict), t.schedules, t.settledAtMs,
    )

    /** HandoffReceiverTasks8153.account8153: [t] just settled. Total. */
    fun note8190(t: HandoffReceiverTasks8153.Task8153) {
        try {
            val tr = traceOf8190(t)
            synchronized(ring8190) {
                ring8190.addLast(tr)
                while (ring8190.size > RING_8190) ring8190.removeFirst()
            }
            kinds8190.computeIfAbsent(tr.kind) { AtomicLong(0L) }.incrementAndGet()
            com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.noteHandoff8144(t.mint, "TASK8190:${line8190(tr)}")
        } catch (_: Throwable) {}
    }

    /** A pending task carried to a newer candidate version / hand-off (not a terminal). */
    fun noteCarried8190() { carried8190.incrementAndGet() }

    /** Pure: one trace as "RECEIVER:vVERSION:ageNs:sN:VERDICT". */
    fun line8190(t: Trace8190): String =
        "${t.receiver}:v${t.version}:age${if (t.evidenceAgeMs < 0L) "?" else (t.evidenceAgeMs / 1000L).toString()}s:s${t.schedules}:${t.verdict.take(50)}"

    fun recent8190(): List<Trace8190> = synchronized(ring8190) { ring8190.toList() }

    /** " handoffTrace8190[carried=.. byKind{..} last{..}]" — the report line. */
    fun statusLine8190(): String {
        val k = kinds8190.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val last = recent8190().takeLast(3).joinToString(";") { "${it.mint.take(6)}>${line8190(it)}" }.ifBlank { "-" }
        val rec = ReceiverRecord8190.counts8190().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        return " handoffTrace8190[carried=${carried8190.get()} byKind{$k} record{$rec} last{$last}]"
    }

    internal fun resetForTests8190() { synchronized(ring8190) { ring8190.clear() }; kinds8190.clear(); carried8190.set(0L) }
}

/**
 * V5.0.8190 — EXPRESS's 1-hour continuation read when the token is younger than an hour and has fewer than two price
 * points: the move since its own pump.fun create price (an observed price ratio, the token's whole life). 5.0.8186:
 * NATIVE_NO_CONTINUATION=2061 "0.0% < 1.0%" — an unobserved 1 h change and an empty history read as a flat 0.0%.
 * No create price or no fresh SOL price: unchanged (the existing fallback).
 */
object ExpressMomentum8190 {
    /** Pure: [lastChange1h] when observed, else the history move when 2+ points exist, else [sinceLaunchPct], else [histPct]. */
    fun momentum8190(lastChange1h: Double, histPct: Double, histPoints: Int, sinceLaunchPct: Double?): Double = when {
        lastChange1h.isFinite() && kotlin.math.abs(lastChange1h) > 0.000001 -> lastChange1h
        histPoints >= 2 -> histPct
        sinceLaunchPct != null && sinceLaunchPct.isFinite() -> sinceLaunchPct
        else -> histPct
    }

    /** The % move since the pump.fun create price for a token under an hour old, or null. */
    fun sinceLaunchPct8190(ts: TokenState, ageMin: Double?): Double? = try {
        val createSol = com.lifecyclebot.network.PumpCurveKeys7269.createPriceSol7280(ts.mint)
        val solUsd = WalletManager.lastKnownSolPrice
        val px = ts.lastPrice
        if (ageMin == null || !(ageMin in 0.0..60.0) || createSol == null || !createSol.isFinite() || createSol <= 0.0 ||
            !solUsd.isFinite() || solUsd <= 0.0 || !px.isFinite() || px <= 0.0) null
        else (com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(px, solUsd) / createSol - 1.0) * 100.0
    } catch (_: Throwable) { null }
}

/**
 * V5.0.8190 — PROJECT_SNIPER's designated pool includes its own hunter's selection. 5.0.8186: candidateN=0 with compute
 * skippedBy SNIPER_NOT_DESIGNATED_POOL_6599=6935 while LaneHunter7297's PROJECT_SNIPER profile (known age within 3 min,
 * $2k liquidity, -5..35% move — the sniper's native mandate) selected fresh ~1-minute coins: a hunted coin reaches the
 * lane gate as desk-qualified (huntedByLane7297) and then had no toolkit hypothesis (the generic snipe hypothesis keys on
 * a "PUMP" source tag, and a hunted coin's source is MARKET_HUNT_PROJECT_SNIPER), so the pool rule refused it before the
 * sniper's own assessment ran. Only the no-hypothesis case is widened; another lane's hypothesis still refuses, and the
 * sniper's native assessment, hard safety, the FDG and sizing still decide every buy.
 */
object SniperScope8190 {
    fun ownHunterPool8190(noDeskHypothesis: Boolean, huntedByOwnHunter: Boolean): Boolean = noDeskHypothesis && huntedByOwnHunter
}
