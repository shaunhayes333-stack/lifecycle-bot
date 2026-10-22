package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8153 §A_QUALIFIED_RECEIVER_IS_A_TASK_THE_CYCLE_CONSUMES.
 *
 * Device 5.0.8151: 981 sniper hand-offs emitted, 899 RECEIVER_QUALIFIED, 809 EXPIRED_NOT_EVALUATED and 2 reaching the
 * FinalDecisionGate; the rescue election made 1,415 claims and zero unclaimed-FDG adoptions. Causes read in code:
 *  - AdmissionHandoff8144 only RECORDED a qualified receiver. Nothing scheduled it: BotService.laneEvaluationAllowed7951
 *    lets a non-primary lane evaluate only as the bounded rescue, through handOffAllowed7960 (which needs the CYCLE
 *    PRIMARY to have refused) or with a proven cell. PROJECT_SNIPER is rarely the cycle primary, so its phase / TOO_OLD
 *    refusal opened nothing, and the election never heard the receiver's name (a yield was set only from the cycle
 *    primary's own native fit refusal) — so the lottery's hash pick took the one slot and the receiver ended
 *    SHADOW_NOT_PRIMARY_8029 / RECEIVER_NOT_HANDED_OFF until the 120 s TTL expired it.
 *  - The ledger TTL ran from emission whatever the receiver did, and a launch's next phase (BIRTH -> EXPANDING, the RCR
 *    shape) was swallowed by the 10-minute settled hold of the previous hand-off.
 *
 * Now a qualified receiver is a bounded, version-bound evaluation task per mint ([Task8153]). The cycle consumes it in the
 * lane gate (SpecialistOwnership7951.handOffAllowed7960 -> [HandoffTaskRuntime8153.admits8153] -> the election's one
 * rescue slot, SpecialistElection8136.claimHandoffReceiver8153), and the task ends exactly one way:
 *  - EVALUATED: the receiver's FinalDecisionGate verdict (allow or refuse);
 *  - DEFERRED_DATA: only data was missing (DEFERRED_*), and the task lapsed still waiting for it;
 *  - REFUSED: a named hard refusal — token-wide safety / structure (binding, never handed on), the receiver's own
 *    judgement, another lane already sealed this version's buy, or stale (no fresh valid read kept it alive).
 * A FIT refusal by the receiver (cap / age band, phase, role floor, lane quarantine) hands the task on to the best other
 * natively eligible lane ([transfer8153]); safety refusals stay binding. A task is refreshed only by a FRESH native read
 * in which its receiver is still eligible and the token still passes safety, never past [TASK_MAX_AGE_MS_8153] from its
 * creation: stale authorisation is never auto-prolonged.
 *
 * The task never admits, sizes or executes: every lane gate, the FDG, hard safety, sizing and the coordinator's one-owner
 * election still decide. No Android dependencies; explicit clocks; bounded.
 */
object HandoffReceiverTasks8153 {

    enum class Outcome8153 { PENDING, EVALUATED, DEFERRED_DATA, REFUSED }

    /** How a refusal met by a pending task is treated. */
    enum class Block8153 { DATA, WAIT, FIT, BINDING, JUDGEMENT }

    data class Task8153(
        val mint: String,
        val lane: String,
        val from: String,
        val reason: String,
        val version: Long,
        val createdAtMs: Long,
        val validUntilMs: Long,
        val refreshedAtMs: Long,
        val outcome: Outcome8153 = Outcome8153.PENDING,
        val verdict: String = "",
        val settledAtMs: Long = 0L,
        val scheduledAtMs: Long = 0L,
        val schedules: Int = 0,
        val lastBlock: String = "",
        val tried: Set<String> = emptySet(),
        /** V5.0.8190 — the version of the pending task this one carried forward (0 = opened fresh). */
        val carriedFrom8190: Long = 0L,
    ) {
        val pending: Boolean get() = outcome == Outcome8153.PENDING
    }

    /** A task lives this long past its last valid refresh. */
    const val TASK_TTL_MS_8153 = 45_000L
    /** ... and never longer than this from its creation, however often it is refreshed. */
    const val TASK_MAX_AGE_MS_8153 = 180_000L
    /** A settled task for the same lane is not re-opened inside this window by a repeat of the same hand-off. */
    internal const val SETTLED_HOLD_MS_8153 = 120_000L
    internal const val SAME_READ_SLACK_MS_8153 = 5_000L
    private const val MAX_TASKS_8153 = 2_048
    private const val DEFERRED_PREFIX_8153 = "DEFERRED:"

    /** Never a receiver: no per-token buy block (RESCUE_EXCLUDED_8136). */
    private val NOT_RECEIVERS_8153 = setOf("CORE", "CYCLIC")
    /** A refusal that says only "THIS lane cannot take it" — handed on, like a fit refusal. */
    private val LANE_SCOPED_8153 = listOf("LANE_QUARANTINE", "TRADER_DISABLED", "OWN_EXECUTOR", "LANE_EXECUTOR_WOULD_REFUSE",
        "SNIPER_NOT_DESIGNATED_POOL")
    /** The receiving lane's own learned refusal: its judgement (named, not handed on), never mistaken for token safety. */
    private val LANE_JUDGEMENT_8153 = listOf("MEME_CONSENSUS_HARD_BLOCK", "MEME_PWIN_GATE", "RECEIVER_RECORD_UNSOUND")
    /** Missing data, not a verdict: the task waits for it. */
    private val DATA_WORDS_8153 = listOf("NO_TOKEN_STATE", "PRICE_UNKNOWN", "PRICE_STALE", "DATA_MISSING", "MISSING_DATA",
        "DATA_INCOMPLETE", "TOO_FEW_BARS", "UNKNOWN_ECONOMICS", "NO_SNAPSHOT", "ARBITRATION_ERROR",
        "UNHYDRATED", "METADATA_HYDRATING", "PRICE_NOT_FRESH",
        "CURVE_RESERVES_STALE", "CURVE_DEPTH_UNVERIFIED")
    /** The slot or the cycle was busy: the task waits (named at expiry if it never got in). */
    private val WAIT_WORDS_8153 = listOf("SLOT_HELD", "RESERVED_FOR", "OUTRANKED", "GATE_UNNAMED", "NO_PROPOSAL")

    internal fun canon8153(lane: String?): String =
        try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { lane.orEmpty().trim().uppercase() }

    // ── pure ──

    /**
     * Pure: how a refusal [reason] is treated by a pending task. [fit] = a lane-fit refusal (laneFitRefusal8132),
     * [binding] = a token-wide safety / structural refusal (refusalIsJudgement8136). DEFERRED_ is data before anything;
     * a lane-scoped inability is fit; binding beats fit.
     */
    fun classify8153(reason: String?, fit: Boolean, binding: Boolean): Block8153 {
        val r = reason?.trim()?.uppercase()?.replace(' ', '_').orEmpty()
        return when {
            r.isEmpty() -> Block8153.WAIT
            r.startsWith("DEFERRED_") -> Block8153.DATA
            LANE_SCOPED_8153.any { r.startsWith(it) } -> Block8153.FIT
            LANE_JUDGEMENT_8153.any { r.startsWith(it) } -> Block8153.JUDGEMENT
            binding -> Block8153.BINDING
            DATA_WORDS_8153.any { r.contains(it) } -> Block8153.DATA
            WAIT_WORDS_8153.any { r.contains(it) } -> Block8153.WAIT
            fit -> Block8153.FIT
            else -> Block8153.JUDGEMENT
        }
    }

    /**
     * Pure: a hand-off's qualified receiver [lane] opens a task, unless one is still live for this mint (the first live
     * receiver stands for the candidate), or the same lane's task settled inside [SETTLED_HOLD_MS_8153] and this is not
     * a hand-off emitted after that settlement ([newHandoffAtMs]).
     */
    fun open8153(prior: Task8153?, mint: String, lane: String, from: String, reason: String, version: Long,
                 evidenceAtMs: Long, nowMs: Long, newHandoffAtMs: Long = 0L): Task8153? {
        val l = canon8153(lane)
        if (mint.isBlank() || l.isBlank() || l in NOT_RECEIVERS_8153 || l == canon8153(from)) return prior
        // A newer, independently emitted handoff with a DIFFERENT candidate version
        // supersedes the old receiver task. 8169 could keep the old version alive
        // until TTL, preventing the fresh receiver from ever reaching its FDG.
        // Repeated reads of the SAME handoff/version still coalesce as before.
        // V5.0.8175 — a hand-off whose emission time is KNOWN ([newHandoffAtMs] > 0) and not after the live/settled
        // task is old evidence: it never supersedes, whatever version it carries. An unstamped (0) different version
        // still supersedes (8174), so a fresh candidate is never parked behind a stale receiver task.
        if (prior != null && prior.pending && nowMs <= prior.validUntilMs &&
            (version <= 0L || prior.version <= 0L || prior.version == version ||
                oldEvidence8175(newHandoffAtMs, prior.createdAtMs))
        ) return prior
        if (prior != null && !prior.pending && prior.lane == l && nowMs - prior.settledAtMs in 0L..SETTLED_HOLD_MS_8153 &&
            prior.settledAtMs >= newHandoffAtMs &&
            (version <= 0L || prior.version <= 0L || prior.version == version ||
                oldEvidence8175(newHandoffAtMs, prior.settledAtMs)) &&
            !reopensOnFreshEvidence8190(prior, evidenceAtMs)) return prior
        // V5.0.8190 — a newer hand-off replacing a still-pending task carries its lineage (the receivers already tried);
        // it is counted CARRIED, not as a refusal of the old version.
        if (prior != null && prior.pending && nowMs <= prior.validUntilMs) {
            return Task8153(mint, l, canon8153(from), reason.take(80), version, nowMs, nowMs + TASK_TTL_MS_8153, evidenceAtMs,
                tried = prior.tried - l, carriedFrom8190 = if (prior.version > 0L) prior.version else -1L)
        }
        return Task8153(mint, l, canon8153(from), reason.take(80), version, nowMs, nowMs + TASK_TTL_MS_8153, evidenceAtMs)
    }

    /**
     * Pure (V5.0.8190): a task that ended WITHOUT the receiver judging it (deferred for data, never scheduled, scheduled
     * without a verdict) is reopened inside the settled hold by a native read newer than its settlement. A judged task
     * (FDG verdict, receiver refusal, binding, no compatible receiver) keeps the hold.
     */
    fun reopensOnFreshEvidence8190(prior: Task8153, evidenceAtMs: Long): Boolean {
        if (prior.pending || evidenceAtMs <= prior.settledAtMs) return false
        return prior.outcome == Outcome8153.DEFERRED_DATA ||
            (prior.outcome == Outcome8153.REFUSED && (prior.verdict.startsWith("STALE_NOT_SCHEDULED") ||
                prior.verdict.startsWith("NOT_SCHEDULED:") || prior.verdict.startsWith("SCHEDULED_NO_FDG_VERDICT")))
    }

    /**
     * Pure (V5.0.8190): the clock is not a newer candidate (LaneExecutionCoordinator 7276). A live task whose candidate
     * version the bucket has moved past ([currentVersion] newer) carries to it — its validity is unchanged, never prolonged.
     */
    fun carry8190(t: Task8153, currentVersion: Long, nowMs: Long): Task8153 =
        if (!t.pending || nowMs > t.validUntilMs || t.version <= 0L || currentVersion <= t.version) t
        else t.copy(version = currentVersion)

    /** V5.0.8175 — pure: a stamped hand-off emitted at or before [taskAtMs] is old evidence (0 = unstamped, not old). */
    private fun oldEvidence8175(newHandoffAtMs: Long, taskAtMs: Long): Boolean = newHandoffAtMs > 0L && newHandoffAtMs <= taskAtMs

    /**
     * Pure: a FRESH native read ([evidenceAtMs] after the last refresh) in which [lane] is still eligible and the token
     * still valid ([candidateValid]) extends the task — to [TASK_TTL_MS_8153] from now, never past
     * [TASK_MAX_AGE_MS_8153] from creation. A lapsed task is never revived; an invalid or repeated read extends nothing.
     */
    fun refresh8153(t: Task8153, lane: String, version: Long, candidateValid: Boolean, evidenceAtMs: Long, nowMs: Long): Task8153 {
        if (!t.pending || t.lane != canon8153(lane) || !candidateValid) return t
        if (nowMs > t.validUntilMs || evidenceAtMs <= t.refreshedAtMs) return t
        val until = minOf(nowMs + TASK_TTL_MS_8153, t.createdAtMs + TASK_MAX_AGE_MS_8153)
        return t.copy(validUntilMs = maxOf(t.validUntilMs, until), refreshedAtMs = evidenceAtMs,
            version = if (version > 0L) maxOf(version, t.version) else t.version)
    }

    /** Pure: [lane] holds a live task the cycle must consume (inside its validity, and its candidate version not left behind). */
    fun due8153(t: Task8153?, lane: String, nowMs: Long, currentVersion: Long = -1L): Boolean =
        t != null && t.pending && t.lane == canon8153(lane) && nowMs <= t.validUntilMs &&
            (currentVersion <= 0L || t.version <= 0L || currentVersion == t.version)

    /** Pure: the cycle let the receiver evaluate. */
    fun scheduled8153(t: Task8153, lane: String, nowMs: Long): Task8153 =
        if (!t.pending || t.lane != canon8153(lane)) t else t.copy(scheduledAtMs = nowMs, schedules = t.schedules + 1)

    /** Pure: the receiver's FinalDecisionGate verdict completes the task (another lane's verdict does not). */
    fun onFdg8153(t: Task8153, lane: String, allow: Boolean, reason: String?, atMs: Long, currentVersion: Long = -1L): Task8153 {
        if (!t.pending || t.lane != canon8153(lane) || atMs + SAME_READ_SLACK_MS_8153 < t.createdAtMs ||
            (currentVersion > 0L && !due8153(t, lane, atMs, currentVersion))) return t
        return t.copy(outcome = Outcome8153.EVALUATED, settledAtMs = atMs,
            verdict = if (allow) "FDG_ALLOW" else "FDG_REFUSED:${reason.orEmpty().take(60)}")
    }

    /**
     * Pure: a refusal met by [lane]'s pending task. DATA / WAIT keep it pending with the reason remembered; BINDING and
     * JUDGEMENT end it REFUSED by name; FIT with nowhere to go ([transfer8153] is the path with candidates) ends it too.
     */
    fun block8153(t: Task8153, lane: String, block: Block8153, reason: String, nowMs: Long): Task8153 {
        if (!t.pending || t.lane != canon8153(lane)) return t
        val r = reason.take(60)
        return when (block) {
            Block8153.DATA -> t.copy(lastBlock = DEFERRED_PREFIX_8153 + r)
            Block8153.WAIT -> t.copy(lastBlock = r)
            Block8153.BINDING -> t.copy(outcome = Outcome8153.REFUSED, verdict = "BINDING:$r", settledAtMs = nowMs)
            Block8153.JUDGEMENT -> t.copy(outcome = Outcome8153.REFUSED, verdict = "RECEIVER_REFUSED:$r", settledAtMs = nowMs)
            Block8153.FIT -> t.copy(outcome = Outcome8153.REFUSED, verdict = "FIT_NO_COMPATIBLE_RECEIVER:$r", settledAtMs = nowMs)
        }
    }

    /**
     * Pure: [refusing] (the task's receiver) refused on FIT. The task passes to the best other eligible lane in
     * [candidates] (lane -> own-scale claim) that is not the sender, not CORE / CYCLIC and has not already refused it;
     * its clock restarts but stays inside [TASK_MAX_AGE_MS_8153]. None left: REFUSED FIT_NO_COMPATIBLE_RECEIVER.
     */
    fun transfer8153(t: Task8153, refusing: String, reason: String, candidates: Map<String, Double>, nowMs: Long): Task8153 {
        if (!t.pending || t.lane != canon8153(refusing) || nowMs > t.validUntilMs) return t
        val tried = t.tried + t.lane
        val next = candidates.entries.asSequence()
            .map { canon8153(it.key) to it.value }
            .filter { (l, v) -> l.isNotBlank() && l !in tried && l != t.from && l !in NOT_RECEIVERS_8153 && v.isFinite() }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .firstOrNull()?.first
            ?: return block8153(t, t.lane, Block8153.FIT, reason, nowMs)
        return t.copy(lane = next, tried = tried, verdict = "TRANSFERRED_FROM_${t.lane}:${reason.take(40)}",
            validUntilMs = minOf(nowMs + TASK_TTL_MS_8153, t.createdAtMs + TASK_MAX_AGE_MS_8153),
            scheduledAtMs = 0L, schedules = 0, lastBlock = "")
    }

    /**
     * Pure: a pending task past its validity ends by name — DEFERRED_DATA when it was waiting only for data, else REFUSED:
     * scheduled but no FDG verdict, never scheduled (with the last block), or stale.
     */
    fun expire8153(t: Task8153, nowMs: Long): Task8153 {
        if (!t.pending || nowMs <= t.validUntilMs) return t
        // V5.0.8330 — a task deferred for a missing TokenState is not terminalised before asking whether the state arrived.
        if (awaitsState8330(t) && stateSeen8330.containsKey(t.mint)) {
            expiredAfterState8330.incrementAndGet()
            val resumed = resumeOnState8330(t, true, nowMs)
            if (resumed != null) { resumed8330.incrementAndGet(); return resumed }
            return t.copy(outcome = Outcome8153.DEFERRED_DATA, verdict = t.lastBlock.removePrefix(DEFERRED_PREFIX_8153) + "_STATE_ARRIVED_LATE_8330", settledAtMs = nowMs)
        }
        return when {
            t.lastBlock.startsWith(DEFERRED_PREFIX_8153) ->
                t.copy(outcome = Outcome8153.DEFERRED_DATA, verdict = t.lastBlock.removePrefix(DEFERRED_PREFIX_8153), settledAtMs = nowMs)
            // V5.0.8190 — the receiver evaluated natively and kept saying WAIT for the whole validity: its judgement.
            t.lastBlock.startsWith(HandoffRefusal8190.NATIVE_WAIT_PREFIX_8190) -> t.copy(outcome = Outcome8153.REFUSED,
                verdict = "RECEIVER_REFUSED:" + t.lastBlock.removePrefix(HandoffRefusal8190.NATIVE_WAIT_PREFIX_8190), settledAtMs = nowMs)
            t.schedules > 0 -> t.copy(outcome = Outcome8153.REFUSED, verdict = "SCHEDULED_NO_FDG_VERDICT_x${t.schedules}", settledAtMs = nowMs)
            t.lastBlock.isNotEmpty() -> t.copy(outcome = Outcome8153.REFUSED, verdict = "NOT_SCHEDULED:${t.lastBlock}", settledAtMs = nowMs)
            else -> t.copy(outcome = Outcome8153.REFUSED, verdict = "STALE_NOT_SCHEDULED", settledAtMs = nowMs)
        }
    }

    // ── V5.0.8330 — resume on evidence arrival ──
    //
    // 5.0.8326: 22 tasks ended "missing receiver TokenState", 23 expired unevaluated, 0 immediate recoveries. The
    // deferral asked for watchlist intake and checked for the installed state in the same breath; when the state did
    // arrive (a later poll), nothing told the task. Now the arrival (HandoffRetention8185.rehydrate8185 installed,
    // HandoffPriority8190.poolWithLiveTargets8216 finding the state) resumes the SAME task: same mint / lane / from /
    // reason / version / evidence, the deferral block cleared, validity extended to TASK_TTL from now and never past
    // TASK_MAX_AGE from creation. A resumed task rides the next cycle's priority window (pending tasks are always in it).

    private val stateSeen8330 = ConcurrentHashMap<String, Long>()
    private val resumed8330 = AtomicLong(0L)
    private val expiredAfterState8330 = AtomicLong(0L)

    /** Pure: the task's remembered block is the missing-state deferral. */
    fun awaitsState8330(t: Task8153): Boolean =
        t.pending && t.lastBlock.startsWith(DEFERRED_PREFIX_8153) && t.lastBlock.contains("NO_TOKEN_STATE")

    /**
     * Pure: the same task, resumed — block cleared, validity extended within the max age. Null when there is nothing
     * to resume (no task, not pending, not awaiting the state, state not installed, or max age reached).
     */
    fun resumeOnState8330(t: Task8153?, installed: Boolean, nowMs: Long): Task8153? {
        if (t == null || !installed || !awaitsState8330(t)) return null
        val until = minOf(nowMs + TASK_TTL_MS_8153, t.createdAtMs + TASK_MAX_AGE_MS_8153)
        if (until <= nowMs) return null
        return t.copy(lastBlock = "", validUntilMs = maxOf(t.validUntilMs, until))
    }

    /** The receiver's TokenState for [mint] is installed now: resume its awaiting task at once (idempotent, cheap when none). */
    fun onStateInstalled8330(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val m = HandoffRoute8180.memeKey8180(mint.trim())
        val t = tasks8153[m] ?: tasks8153[mint] ?: return
        if (!awaitsState8330(t)) return
        if (stateSeen8330.size > 2_048) stateSeen8330.entries.removeIf { nowMs - it.value > TASK_MAX_AGE_MS_8153 }
        stateSeen8330[t.mint] = nowMs
        val after = update8153(t.mint, nowMs) { prior -> resumeOnState8330(prior, true, nowMs) ?: prior }
        if (after != null && after.pending && after.lastBlock.isEmpty()) {
            resumed8330.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("HANDOFF_TASK_RESUMED_ON_STATE_8330_${after.lane}")
                ForensicLogger.lifecycle("HANDOFF_TASK_RESUMED_8330", "mint=${t.mint.take(10)} lane=${after.lane} from=${after.from} version=${after.version} validUntilMs=${after.validUntilMs} action=same_task_next_cycle")
            } catch (_: Throwable) {}
        }
    }

    /** " handoffResumed8330=N expiredAfterState=M" */
    fun resumeLine8330(): String = " handoffResumed8330=${resumed8330.get()} expiredAfterState=${expiredAfterState8330.get()}"

    // ── bounded runtime table (one task per mint) ──

    private val tasks8153 = ConcurrentHashMap<String, Task8153>()
    private val counts8153 = ConcurrentHashMap<String, AtomicLong>()

    // ── V5.0.8169 §EVERY_LOST_HANDOFF_IS_NAMED ──
    // 5.0.8168: 271 tasks opened, 10 evaluated at the FDG, 118 DEFERRED_DATA and 137 REFUSED — and the status line said
    // only those totals, so whether a loss was a legitimate refusal (the receiver's judgement, safety, another lane's
    // sealed buy) or a defect (stale version, never scheduled) could not be read off a device log. Every settled task's
    // verdict is now tallied by name ([verdictKey8169]) and printed with its outcome.
    private val verdicts8169 = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure (V5.0.8169): a verdict's stable name — its first two ':'-separated parts (e.g. "RECEIVER_REFUSED:NATIVE_NO_BUY_WAIT",
     * "DEFERRED_NATIVE:DATA_INCOMPLETE"), each cut at the first space and to 40 chars, with digit runs folded to '#'
     * so one rule with varying numbers is one key.
     */
    fun verdictKey8169(verdict: String): String =
        verdict.split(':').take(2).joinToString(":") { it.trim().substringBefore(' ').take(40).replace(Regex("[0-9]+"), "#") }
            .trim(':').ifBlank { "UNNAMED" }

    /** Pure (V5.0.8169): "KEY=n,..." for the [top] largest tallies whose key starts with [prefix] (prefix removed). */
    fun topTallies8169(tallies: Map<String, Long>, prefix: String, top: Int): String =
        tallies.entries.filter { it.key.startsWith(prefix) }.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(top).joinToString(",") { "${it.key.removePrefix(prefix)}=${it.value}" }.ifBlank { "-" }

    private fun tally8169(t: Task8153) {
        if (verdicts8169.size >= 512 && !verdicts8169.containsKey("${t.outcome.name}|${verdictKey8169(t.verdict)}")) return
        verdicts8169.computeIfAbsent("${t.outcome.name}|${verdictKey8169(t.verdict)}") { AtomicLong(0L) }.incrementAndGet()
    }

    private fun count8153(key: String) {
        counts8153.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HANDOFF_TASK_8153_$key") } catch (_: Throwable) {}
    }

    /** Counts what changed between [before] and [after] once (opened / transferred / settled by outcome). */
    private fun account8153(before: Task8153?, after: Task8153?) {
        if (after == null || before === after) return
        // V5.0.8190 — a pending task replaced by a newer hand-off is carried (its lineage continues in [after]), not refused.
        val carried8190 = before != null && before.pending && before.createdAtMs != after.createdAtMs && after.pending &&
            after.carriedFrom8190 != 0L
        if (before != null && before.pending && before.createdAtMs != after.createdAtMs && !carried8190) {
            // Preserve terminal accounting when a newly emitted candidate version
            // supersedes an older pending task; never silently discard lineage.
            count8153("REFUSED")
            tally8169(before.copy(outcome = Outcome8153.REFUSED, verdict = "SUPERSEDED_FRESH_VERSION_8170",
                settledAtMs = after.createdAtMs))
        }
        if (carried8190) { count8153("CARRIED_8190"); HandoffTrace8190.noteCarried8190() }
        else if (before == null || (after.createdAtMs != before.createdAtMs)) count8153("OPENED")
        else if (after.lane != before.lane && after.pending) count8153("TRANSFERRED")
        if (before != null && after.schedules == 1 && before.schedules == 0 && after.createdAtMs == before.createdAtMs &&
            after.lane == before.lane) count8153("SCHEDULED")
        if (!after.pending && (before == null || before.pending || before.createdAtMs != after.createdAtMs)) {
            count8153(after.outcome.name)
            tally8169(after)  // V5.0.8169
            HandoffTrace8190.note8190(after)  // V5.0.8190 — per-task causal trace
        }
    }

    private fun update8153(mint: String, nowMs: Long, step: (Task8153?) -> Task8153?): Task8153? {
        if (mint.isBlank()) return null
        if (tasks8153.size >= MAX_TASKS_8153 && !tasks8153.containsKey(mint)) sweep8153(nowMs)
        var before: Task8153? = null
        var after: Task8153? = null
        tasks8153.compute(mint) { _, prior ->
            val live = prior?.let { expire8153(it, nowMs) }
            if (prior != null && live !== prior) account8153(prior, live)
            before = live
            after = step(live)
            after
        }
        account8153(before, after)
        return after
    }

    /** Open (or keep) the mint's task for [lane]; returns the mint's task after the call. */
    fun openTask8153(mint: String, lane: String, from: String, reason: String, version: Long, evidenceAtMs: Long,
                     nowMs: Long, newHandoffAtMs: Long = 0L): Task8153? {
        // V5.0.8180 — a crypto-desk identity has no meme cycle to consume a task.
        if (HandoffRoute8180.deskIdentity8180(mint)) return null
        var opened8196 = false
        val task = update8153(mint, nowMs) { prior ->
            open8153(prior, mint, lane, from, reason, version, evidenceAtMs, nowMs, newHandoffAtMs).also {
                opened8196 = it != null && it.pending && it !== prior
            }
        }
        // 8196: a newly qualified rescue MUST receive its own FDG verdict. A 12 s
        // cached refusal from the previous owner/version previously made a receiver
        // "scheduled" but never "evaluated". Only invalidate on an actual new task;
        // repeated native reads of the SAME handoff retain the cache.
        if (opened8196) {
            try { FinalDecisionGate.invalidateCandidate6734(mint) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("HANDOFF_NEW_RECEIVER_FDG_CACHE_BUST_8196") } catch (_: Throwable) {}
        }
        return task
    }

    fun refreshTask8153(mint: String, lane: String, version: Long, candidateValid: Boolean, evidenceAtMs: Long, nowMs: Long): Task8153? =
        update8153(mint, nowMs) { it?.let { t -> refresh8153(t, lane, version, candidateValid, evidenceAtMs, nowMs) } }

    fun markScheduled8153(mint: String, lane: String, nowMs: Long) {
        if (!isDue8153(mint, lane, nowMs)) return
        update8153(mint, nowMs) { it?.let { t -> scheduled8153(t, lane, nowMs) } }
    }

    fun onFdgVerdict8153(mint: String, lane: String, allow: Boolean, reason: String?, nowMs: Long, candidateVersion: Long = -1L) {
        if (tasks8153[mint]?.pending != true) return
        carryForward8190(mint, candidateVersion, nowMs)  // V5.0.8190 — the receiver's verdict on the newer bucket is its evaluation
        update8153(mint, nowMs) { it?.let { t -> onFdg8153(t, lane, allow, reason, nowMs, candidateVersion) } }
    }

    fun blockTask8153(mint: String, lane: String, block: Block8153, reason: String, nowMs: Long): Task8153? =
        update8153(mint, nowMs) { it?.let { t -> block8153(t, lane, block, reason, nowMs) } }

    /** [refusing] refused on fit: hand the task on; returns the new receiver, or null when it ended with no receiver. */
    internal fun transferTask8153(mint: String, refusing: String, reason: String, candidates: Map<String, Double>, nowMs: Long): String? =
        update8153(mint, nowMs) { it?.let { t -> transfer8153(t, refusing, reason, candidates, nowMs) } }
            ?.takeIf { it.pending && it.lane != canon8153(refusing) }?.lane

    /** Only while the remembered block is empty: a later, vaguer "not scheduled" never hides a precise one. */
    internal fun noteWaiting8153(mint: String, lane: String, reason: String, nowMs: Long) {
        if (tasks8153[mint]?.lastBlock?.isEmpty() != true) return
        blockTask8153(mint, lane, Block8153.WAIT, reason, nowMs)
    }

    /** The mint's task as of [nowMs] (expiry applied on read, not stored). */
    fun taskOf8153(mint: String, nowMs: Long): Task8153? = tasks8153[mint]?.let { expire8153(it, nowMs) }

    /** V5.0.8190 — carry the mint's live task to [currentVersion] when the clock bucket moved past it ([carry8190]). */
    fun carryForward8190(mint: String, currentVersion: Long, nowMs: Long) {
        val t = tasks8153[mint] ?: return
        if (carry8190(t, currentVersion, nowMs) === t) return
        update8153(mint, nowMs) { it?.let { p -> carry8190(p, currentVersion, nowMs) } }
        try { PipelineHealthCollector.labelInc("HANDOFF_TASK_VERSION_CARRIED_8190") } catch (_: Throwable) {}
    }

    /**
     * V5.0.8198 P2 — the cycle could not schedule [mint]'s live task for [cause] (NOT_IN_CYCLE_POOL / BEYOND_CYCLE_BOUND).
     * Remembered on the task so its expiry terminal names the cause (NOT_SCHEDULED:<cause>) instead of STALE_NOT_SCHEDULED.
     * A task already scheduled or carrying a receiver block keeps its own record.
     */
    fun noteUnscheduled8198(mint: String, cause: String, nowMs: Long) {
        val t = tasks8153[mint] ?: return
        if (!t.pending || t.schedules > 0 || t.lastBlock.isNotEmpty() || nowMs > t.validUntilMs) return
        update8153(mint, nowMs) { it?.let { p -> if (p.pending && p.schedules == 0 && p.lastBlock.isEmpty()) p.copy(lastBlock = cause.take(40)) else p } }
    }

    /** V5.0.8190 — every live task's target (mint -> validUntil) for the cycle's priority window (HandoffPriority8190). */
    fun pendingTargets8190(nowMs: Long): List<Pair<String, Long>> =
        tasks8153.values.filter { it.pending && nowMs <= it.validUntilMs }.map { it.mint to it.validUntilMs }

    fun isDue8153(mint: String, lane: String, nowMs: Long, currentVersion: Long = -1L): Boolean =
        due8153(tasks8153[mint], lane, nowMs, currentVersion)

    fun outcomeCounts8153(): Map<String, Long> = counts8153.mapValues { it.value.get() }

    private fun sweep8153(nowMs: Long) {
        try {
            for ((k, t) in tasks8153.entries.toList()) {
                val e = expire8153(t, nowMs)
                if (e !== t && tasks8153.replace(k, t, e)) account8153(t, e)
            }
            tasks8153.entries.removeIf { !it.value.pending && nowMs - it.value.settledAtMs > SETTLED_HOLD_MS_8153 }
            if (tasks8153.size >= MAX_TASKS_8153) {
                tasks8153.entries.sortedBy { it.value.createdAtMs }.take(MAX_TASKS_8153 / 10).forEach { tasks8153.remove(it.key) }
            }
        } catch (_: Throwable) {}
    }

    fun statusLine8153(nowMs: Long = System.currentTimeMillis()): String {
        sweep8153(nowMs)
        val c = { k: String -> counts8153[k]?.get() ?: 0L }
        return "handoffTask8153[opened=${c("OPENED")} scheduled=${c("SCHEDULED")} transferred=${c("TRANSFERRED")} " +
            "evaluated=${c(Outcome8153.EVALUATED.name)} deferredData=${c(Outcome8153.DEFERRED_DATA.name)} " +
            "refused=${c(Outcome8153.REFUSED.name)} pending=${tasks8153.values.count { it.pending }} " +
            verdictLine8169() + "]" + resumeLine8330() + HandoffTrace8190.statusLine8190()  // V5.0.8190 / V5.0.8330
    }

    /** V5.0.8169 — "refusedBy8169{..} deferredBy8169{..} evaluatedBy8169{..}": every settled task's verdict by name. */
    internal fun verdictLine8169(): String {
        val v = verdicts8169.mapValues { it.value.get() }
        return "refusedBy8169{${topTallies8169(v, "${Outcome8153.REFUSED.name}|", 8)}} " +
            "deferredBy8169{${topTallies8169(v, "${Outcome8153.DEFERRED_DATA.name}|", 5)}} " +
            "evaluatedBy8169{${topTallies8169(v, "${Outcome8153.EVALUATED.name}|", 4)}}"
    }

    internal fun resetForTests8153() { tasks8153.clear(); counts8153.clear(); verdicts8169.clear(); stateSeen8330.clear(); resumed8330.set(0L); expiredAfterState8330.set(0L) }
}

/**
 * V5.0.8153 — the runtime half: reads the election, the token's safety, the candidate version and the cached native
 * snapshot, and drives [HandoffReceiverTasks8153]. Every entry point is total (never throws into its caller).
 */
object HandoffTaskRuntime8153 {
    private fun version8153(mint: String): Long = try {
        LaneExecutionCoordinator.candidateVersionFor(mint)
            .also { HandoffReceiverTasks8153.carryForward8190(mint, it, System.currentTimeMillis()) }  // V5.0.8190 — the bucket is not a new candidate
    } catch (_: Throwable) { -1L }

    private fun fit8153(reason: String?): Boolean = try { SpecialistOwnership7951.laneFitRefusal8132(reason) } catch (_: Throwable) { false }

    private fun binding8153(reason: String?): Boolean = try { SpecialistOwnership7951.refusalIsJudgement8136(reason, fdg = false) } catch (_: Throwable) { false }

    /** How the runtime treats [reason] (fit / binding read from the same rules the election uses). */
    internal fun blockOf8153(reason: String?): HandoffReceiverTasks8153.Block8153 =
        HandoffRefusal8190.classify8190(reason, fit8153(reason), binding8153(reason))  // V5.0.8190 — data / scope / not-yet named

    /** Every authoritative, natively eligible lane that may own execution, on its own scale. */
    private fun eligibleNow8153(mint: String): Map<String, Double> = try {
        SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.values.orEmpty()
            .filter { it.eligible && it.authoritative && LaneExecutionCoordinator.laneCanOwnExecution6910(it.lane) }
            .associate { o ->
                HandoffReceiverTasks8153.canon8153(o.lane) to
                    SpecialistElection8136.normalised8136(maxOf(o.score, o.confidence).toDouble(), Double.NaN, o.ownershipConviction7948)
            }
    } catch (_: Throwable) { emptyMap() }

    /** A refusal met by [lane]'s task: fit hands it on (election and ledger follow), anything else is applied as classified. */
    private fun refused8153(mint: String, lane: String, reason0: String, nowMs: Long) {
        // V5.0.8190 — a judgement on a stale mark defers (and asks for a fresh mark) instead of ending the task.
        val classified = blockOf8153(reason0)
        val block = HandoffRefusal8190.staleAware8190(classified,
            (classified == HandoffReceiverTasks8153.Block8153.JUDGEMENT || classified == HandoffReceiverTasks8153.Block8153.WAIT) &&
                HandoffRefusal8190.markStaleFor8190(mint, nowMs))
        val reason = if (block != classified) HandoffRefusal8190.STALE_PREFIX_8190 + reason0 else reason0
        if (block == HandoffReceiverTasks8153.Block8153.DATA) {
            // Do not spend the entire 45 s receiver TTL merely waiting for another
            // ordinary scanner poll. Request the missing input through the existing
            // per-mint 30 s coalescer; failed providers still leave it UNKNOWN.
            try {
                val ts = HandoffRoute8180.tokenFor8180(mint)  // V5.0.8180 — the meme key too
                if (ts != null) {
                    val missing = com.lifecyclebot.engine.cortex.LanePlaybook7907.dataIncomplete8136(ts, lane, nowMs).orEmpty()
                    val liq = if (reason.contains("LIQ", true) && missing.none { it.startsWith("liq:") }) listOf("liq:UNKNOWN") else emptyList()
                    com.lifecyclebot.engine.cortex.LanePlaybook7907.requestEvidence8153(ts, missing + liq, nowMs)
                }
            } catch (_: Throwable) {}
        }
        if (block != HandoffReceiverTasks8153.Block8153.FIT) {
            HandoffReceiverTasks8153.blockTask8153(mint, lane, block, reason, nowMs)
            return
        }
        val next = HandoffReceiverTasks8153.transferTask8153(mint, lane, reason, eligibleNow8153(mint), nowMs)
        try { SpecialistElection8136.retargetReceiver8153(mint, lane, next.orEmpty(), reason, nowMs) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.AdmissionHandoff8144.retarget8153(mint, lane, next.orEmpty(), reason, nowMs) } catch (_: Throwable) {}
    }

    /**
     * AdmissionHandoff8144 (a fresh native read with the hand-off's receiver qualified) and the primary's fit yield
     * ([onYield8153]): open / keep the mint's task, name its receiver in the election (one named receiver per candidate),
     * and refresh it only while the receiver is eligible in [eligibleLanes] and the token passes safety. Returns the lane
     * the live task is for (null = none).
     */
    fun onReceiverQualified8153(mint: String, from: String, receiver: String, reason: String, eligibleLanes: Set<String>,
                                newHandoffAtMs: Long, evidenceAtMs: Long, nowMs: Long = System.currentTimeMillis()): String? = try {
        val version = version8153(mint)
        var t = HandoffReceiverTasks8153.openTask8153(mint, receiver, from, reason, version, evidenceAtMs, nowMs, newHandoffAtMs)
        if (t == null || !t.pending) null else {
            val named = SpecialistElection8136.nameHandoffReceiver8153(mint, t.from, t.lane, nowMs)
            if (named != null && named != t.lane) {
                // The election already names another receiver for this candidate: the task follows it (one rescue slot).
                HandoffReceiverTasks8153.transferTask8153(mint, t.lane, "ELECTION_NAMED_$named", mapOf(named to 100.0), nowMs)
                t = HandoffReceiverTasks8153.taskOf8153(mint, nowMs)
            }
            val lane = t?.takeIf { it.pending }?.lane
            if (lane != null) {
                val ineligible = SpecialistOwnership7951.rescueIneligible8136(mint, lane)
                if (ineligible == null) {
                    HandoffReceiverTasks8153.refreshTask8153(mint, lane, version,
                        lane in eligibleLanes.map { HandoffReceiverTasks8153.canon8153(it) }, evidenceAtMs, nowMs)
                } else {
                    refused8153(mint, lane, ineligible, nowMs)
                }
            }
            HandoffReceiverTasks8153.taskOf8153(mint, nowMs)?.takeIf { it.pending }?.lane
        }
    } catch (_: Throwable) { null }

    /** SpecialistOwnership7951.yieldReceiver8136: a primary's fit refusal named [receiver] — that hand-off is a task too. */
    fun onYield8153(mint: String, primary: String, receiver: String, ops: Map<String, SpecialistBrainBridge7542.Opinion>,
                    nowMs: Long = System.currentTimeMillis()) {
        try {
            val eligible = ops.values.filter { it.eligible && it.authoritative }.map { HandoffReceiverTasks8153.canon8153(it.lane) }.toSet()
            val evidenceAt = SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.evaluatedAtMs ?: nowMs
            onReceiverQualified8153(mint, primary, receiver, "YIELD:" + ops[primary]?.reason.orEmpty().take(60), eligible, 0L, evidenceAt, nowMs)
        } catch (_: Throwable) {}
    }

    /** AdmissionHandoff8144: the receiver judged and refused natively (not on fit, or fit with no other receiver). */
    fun onReceiverNativeRefused8153(mint: String, lane: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            if (!HandoffReceiverTasks8153.isDue8153(mint, lane, nowMs, version8153(mint))) return
            // A fit refusal is THIS lane's refusal, not a token-wide rejection.
            // The previous path called blockTask8153(FIT) directly and ended a
            // recoverable handoff even if another native specialist was eligible.
            // The shared adapter transfers to the next eligible receiver (or
            // produces a named terminal); safety refusals remain binding.
            refused8153(mint, lane, reason, nowMs)
        } catch (_: Throwable) {}
    }

    /** A receiver's native lane decision is an evaluation even when it produces no FDG BUY candidate. */
    fun onNativeCandidate8153(mint: String, lane: String, shouldTrade: Boolean, signal: String, reason: String?,
                              nowMs: Long = System.currentTimeMillis()) {
        try {
            val l = HandoffReceiverTasks8153.canon8153(lane)
            if (!HandoffReceiverTasks8153.isDue8153(mint, l, nowMs, version8153(mint))) return
            SpecialistElection8136.noteScheduled8144(mint, l, nowMs)
            if (shouldTrade) return // the same task will be completed by its actual FDG verdict
            val r = reason?.takeIf { it.isNotBlank() } ?: "NATIVE_NO_BUY_${signal.ifBlank { "WAIT" }}"
            val classified = blockOf8153(r)
            // V5.0.8190 — a judgement / wait made on a stale mark is a data deferral (a fresh mark is requested).
            when (val block = HandoffRefusal8190.staleAware8190(classified,
                (classified == HandoffReceiverTasks8153.Block8153.JUDGEMENT || classified == HandoffReceiverTasks8153.Block8153.WAIT) &&
                    HandoffRefusal8190.markStaleFor8190(mint, nowMs))) {
                HandoffReceiverTasks8153.Block8153.DATA ->
                    HandoffReceiverTasks8153.blockTask8153(mint, l, block,
                        "DEFERRED_NATIVE:" + (if (block != classified) HandoffRefusal8190.STALE_PREFIX_8190 else "") + r, nowMs)
                HandoffReceiverTasks8153.Block8153.FIT -> refused8153(mint, l, r, nowMs)
                HandoffReceiverTasks8153.Block8153.BINDING,
                HandoffReceiverTasks8153.Block8153.JUDGEMENT -> HandoffReceiverTasks8153.blockTask8153(mint, l, block, r, nowMs)
                // V5.0.8190 — a WAIT keeps the task (the receiver re-evaluates each cycle while it is valid); it is named the
                // receiver's judgement (RECEIVER_REFUSED:NATIVE_NO_BUY_*) only at expiry. superseded: ended it here as JUDGEMENT.
                HandoffReceiverTasks8153.Block8153.WAIT -> HandoffReceiverTasks8153.blockTask8153(
                    mint, l, HandoffReceiverTasks8153.Block8153.WAIT,
                    HandoffRefusal8190.NATIVE_WAIT_PREFIX_8190 + r, nowMs,
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8204 — ExecutableOpenGate refused [lane]'s attempt on [mint] BEFORE (or instead of) an FDG verdict: the gate's
     * log name and reason are the receiver's terminal. Only a due, pending task of this lane is touched; a task the FDG
     * already settled, or another lane's task, is not. An FDG refusal surfacing through the gate is already recorded by
     * SpecialistOwnership7951 and is skipped here. Data/not-yet gates defer (the task survives to re-evaluate); scope
     * gates transfer to the next eligible receiver; everything else is this lane's named refusal.
     */
    fun onPreFdgGateBlock8204(mint: String, lane: String, gateLog: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val l = HandoffReceiverTasks8153.canon8153(lane)
            if (gateLog.contains("FDG")) return
            if (!HandoffReceiverTasks8153.isDue8153(mint, l, nowMs, version8153(mint))) return
            val r = "PRE_FDG_GATE:${gateLog.take(48)}:${reason.take(60)}"
            try { PipelineHealthCollector.labelInc("HANDOFF_RECEIVER_PRE_FDG_GATE_8204_${gateLog.take(48)}") } catch (_: Throwable) {}
            when (val block = blockOf8153(reason)) {
                HandoffReceiverTasks8153.Block8153.DATA, HandoffReceiverTasks8153.Block8153.WAIT ->
                    HandoffReceiverTasks8153.blockTask8153(mint, l, HandoffReceiverTasks8153.Block8153.DATA, "DEFERRED_GATE:$r", nowMs)
                HandoffReceiverTasks8153.Block8153.FIT -> refused8153(mint, l, r, nowMs)
                else -> HandoffReceiverTasks8153.blockTask8153(mint, l, block, r, nowMs)
            }
        } catch (_: Throwable) {}
    }

    /** AdmissionHandoff8144: the hand-off's receiver refused on fit and the ledger passed it to [to]: the task follows. */
    fun onLedgerTransfer8153(mint: String, from: String, to: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            if (!HandoffReceiverTasks8153.isDue8153(mint, from, nowMs, version8153(mint))) return
            val next = HandoffReceiverTasks8153.transferTask8153(mint, from, reason, mapOf(to to 100.0), nowMs)
            SpecialistElection8136.retargetReceiver8153(mint, from, next.orEmpty(), reason, nowMs)
            com.lifecyclebot.engine.truth.AdmissionHandoff8144.retarget8153(mint, from, next.orEmpty(), reason, nowMs)
        } catch (_: Throwable) {}
    }

    /**
     * SpecialistOwnership7951.handOffAllowed7960 (the BotService lane gate): [lane] holds the mint's live task — it takes
     * the candidate's one rescue slot through the election (a named receiver beats an unconsumed lottery claim) and
     * evaluates. A refused claim is applied to the task by name (binding / fit transfer / data / waiting).
     */
    fun admits8153(lane: String, mint: String, primaryLane: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val l = HandoffReceiverTasks8153.canon8153(lane)
        // Cheap map read first (every non-primary lane asks every cycle); the candidate version only for a live task.
        if (!HandoffReceiverTasks8153.isDue8153(mint, l, nowMs) || !HandoffReceiverTasks8153.isDue8153(mint, l, nowMs, version8153(mint))) false else {
            val ineligible = SpecialistOwnership7951.rescueIneligible8136(mint, l)
            val outcome = ElectionRescueContract8144.guardedClaim8144 {
                SpecialistElection8136.claimHandoffReceiver8153(mint, l, primaryLane, ineligible, nowMs)
            }
            if (outcome.executable) {
                try { PipelineHealthCollector.labelInc("HANDOFF_TASK_8153_ADMITTED_$l") } catch (_: Throwable) {}
            } else claimRefused8153(mint, l, outcome, ineligible, nowMs)
            outcome.executable
        }
    } catch (_: Throwable) { false }

    private fun claimRefused8153(mint: String, lane: String, outcome: ElectionRescueContract8144.ClaimOutcome8144, ineligible: String?, nowMs: Long) {
        when (outcome) {
            ElectionRescueContract8144.ClaimOutcome8144.DENIED_JUDGEMENT ->
                HandoffReceiverTasks8153.blockTask8153(mint, lane, HandoffReceiverTasks8153.Block8153.BINDING, "PRIMARY_JUDGEMENT", nowMs)
            ElectionRescueContract8144.ClaimOutcome8144.DENIED_INELIGIBLE ->
                refused8153(mint, lane, ineligible ?: SpecialistElection8136.terminalOf8136(mint, lane)?.second.orEmpty().ifBlank { "INELIGIBLE" }, nowMs)
            ElectionRescueContract8144.ClaimOutcome8144.DEFERRED_ARBITRATION_ERROR ->
                HandoffReceiverTasks8153.blockTask8153(mint, lane, HandoffReceiverTasks8153.Block8153.DATA, "DEFERRED_ARBITRATION_ERROR", nowMs)
            ElectionRescueContract8144.ClaimOutcome8144.DENIED_HELD_BY_OTHER ->
                HandoffReceiverTasks8153.blockTask8153(mint, lane, HandoffReceiverTasks8153.Block8153.WAIT,
                    "SLOT_HELD_BY_" + SpecialistElection8136.rescueOf8136(mint).ifBlank { "OTHER" }, nowMs)
            else -> HandoffReceiverTasks8153.blockTask8153(mint, lane, HandoffReceiverTasks8153.Block8153.WAIT, outcome.name, nowMs)
        }
    }

    /**
     * SpecialistOwnership7951.noteReadyNotEvaluated7951: the lane gate refused [lane] this cycle while it held a live task.
     * Another lane's sealed buy for this version is binding (one executable owner); a named gate is applied as
     * classified (fit hands on); an unnamed refusal is remembered and named at expiry.
     */
    fun onNotScheduled8153(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val l = HandoffReceiverTasks8153.canon8153(lane)
            if (!HandoffReceiverTasks8153.isDue8153(mint, l, nowMs, version8153(mint))) return
            val sealedBy = try {
                LaneExecutionCoordinator.currentElection6600(mint)?.takeIf { it.sealed }?.primaryLane?.takeIf { !it.equals(l, true) }
            } catch (_: Throwable) { null }
            val gate = SpecialistElection8136.gateReasonOf8153(mint, l)
            when {
                sealedBy != null -> HandoffReceiverTasks8153.blockTask8153(mint, l, HandoffReceiverTasks8153.Block8153.BINDING, "VERSION_SEALED_BY_$sealedBy", nowMs)
                gate != null -> refused8153(mint, l, gate, nowMs)
                else -> HandoffReceiverTasks8153.noteWaiting8153(mint, l, "GATE_UNNAMED", nowMs)
            }
        } catch (_: Throwable) {}
    }
}
