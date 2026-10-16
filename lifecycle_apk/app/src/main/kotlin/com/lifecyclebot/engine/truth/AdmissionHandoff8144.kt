package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 §A_HANDOFF_IS_DONE_WHEN_THE_RECEIVER_HAS_JUDGED.
 *
 * 5.0.8142: PROJECT_SNIPER emitted 737 phase hand-offs (PHASE_HANDOFF_8136_<stage>_TO_<lane>) and qualified zero, and
 * nothing recorded whether any named lane ever looked at those launches. The name in the reason was the whole
 * hand-off: emitting "TO_MOONSHOT" counted as handing it on, even when MOONSHOT was not in the token's native scope,
 * refused it on its own rules, or qualified it and was then never let through to the FinalDecisionGate.
 *
 * Every sniper hand-off is now a record that ends one way only:
 *  - ARRIVED_EVALUATED: the receiving lane completed an evaluation of the token after the hand-off — its own native
 *    refusal (it judged and said no), or a FinalDecisionGate verdict (allow or refuse). The receiver is the named
 *    lane when it qualified natively, else the best other natively qualified lane (never the sender, never CORE).
 *  - NO_QUALIFIED_RECEIVER: lanes were read after the hand-off and none qualified, and the named lane was not read.
 *  - EXPIRED_NOT_EVALUATED: [HANDOFF_TTL_MS_8144] passed with no completed evaluation — named-only, or qualified and
 *    never reaching the FinalDecisionGate. This is the failure the bare name used to hide.
 * The hand-off itself still rides the existing reason-based route (SpecialistOwnership7951.laneFitRefusal8132 reads
 * the PHASE_HANDOFF / TOO_OLD reason; SpecialistElection8136 elects the rescue): this ledger records arrival, it does not
 * route, and no gate reads it.
 *
 * V5.0.8153 — recording was the whole defect (5.0.8151: 899 qualified, 809 expired, 2 at the FDG): a RECEIVER_QUALIFIED
 * hand-off now opens a version-bound evaluation task (engine.HandoffReceiverTasks8153) that the BotService lane gate
 * consumes for that mint + lane. While that task is live the hand-off does not expire on the emission clock; the task's
 * own clock is refreshed only by a fresh valid read and capped, so stale authorisation is never prolonged. A receiver's
 * FIT refusal hands the hand-off on to the next qualified lane ([onNativeTransfer8153]); a launch's next phase is a new
 * hand-off, not swallowed by the previous one's settled hold ([newHandoff8153]).
 */
object AdmissionHandoff8144 {

    enum class State8144 { EMITTED, RECEIVER_QUALIFIED, ARRIVED_EVALUATED, NO_QUALIFIED_RECEIVER, EXPIRED_NOT_EVALUATED }

    data class Handoff8144(
        val mint: String,
        val from: String,
        val named: String?,
        val reason: String,
        val emittedAtMs: Long,
        val receiver: String? = null,
        val state: State8144 = State8144.EMITTED,
        val verdict: String = "",
        val settledAtMs: Long = 0L,
        /** V5.0.8153 — receivers that refused this hand-off on fit (it is not handed back to them). */
        val tried8153: Set<String> = emptySet(),
    ) {
        val open: Boolean get() = state == State8144.EMITTED || state == State8144.RECEIVER_QUALIFIED
    }

    /** One lane's native read of the token in one assessment. */
    data class LaneRead8144(val lane: String, val eligible: Boolean, val score: Int, val reason: String)

    const val HANDOFF_TTL_MS_8144 = 120_000L
    /** A native read computed in the same assessment as the hand-off is stamped a little before it. */
    internal const val SAME_READ_SLACK_MS_8144 = 5_000L
    /** A settled hand-off is not re-opened by the sender's next identical refusal inside this window. */
    internal const val SETTLED_HOLD_MS_8144 = 10L * 60_000L

    internal fun canon8144(lane: String?): String = lane.orEmpty().trim().uppercase().replace('-', '_').replace(' ', '_')

    // ── pure transitions ──

    /** Pure: the receiving lane — the named lane when natively qualified, else the best other qualified lane, else null. */
    fun receiver8144(named: String?, from: String, reads: Collection<LaneRead8144>): String? {
        val sender = canon8144(from)
        val q = reads.filter { it.eligible && canon8144(it.lane) != sender && canon8144(it.lane) != "CORE" && canon8144(it.lane).isNotBlank() }
        val n = canon8144(named)
        if (n.isNotBlank()) q.firstOrNull { canon8144(it.lane) == n }?.let { return n }
        return q.maxByOrNull { it.score }?.let { canon8144(it.lane) }
    }

    /** Pure: a new hand-off unless one for this mint is still open, or settled within [SETTLED_HOLD_MS_8144]. */
    fun emit8144(prior: Handoff8144?, mint: String, from: String, named: String?, reason: String, nowMs: Long): Handoff8144 {
        if (prior != null && prior.open && nowMs - prior.emittedAtMs <= HANDOFF_TTL_MS_8144) return prior
        if (prior != null && !prior.open && newHandoff8153(prior, named, reason)) return Handoff8144(mint, canon8144(from), canon8144(named).ifBlank { null }, reason.take(80), nowMs)
        if (prior != null && !prior.open && nowMs - prior.settledAtMs in 0L..SETTLED_HOLD_MS_8144) return prior
        return Handoff8144(mint, canon8144(from), canon8144(named).ifBlank { null }, reason.take(80), nowMs)
    }

    private fun dataPending8167(reason: String): Boolean =
        com.lifecyclebot.engine.HandoffReceiverTasks8153.classify8153(reason, fit = false, binding = false) ==
            com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153.DATA

    /** Pure: a native assessment of every lane in scope ([reads], computed at [atMs]) applied to [h]. */
    fun onNative8144(h: Handoff8144, reads: Collection<LaneRead8144>, atMs: Long): Handoff8144 {
        if (!h.open || atMs + SAME_READ_SLACK_MS_8144 < h.emittedAtMs) return h
        if (h.state == State8144.RECEIVER_QUALIFIED) {
            val rr = reads.firstOrNull { canon8144(it.lane) == h.receiver } ?: return h
            return if (rr.eligible || dataPending8167(rr.reason)) h
            else h.copy(state = State8144.ARRIVED_EVALUATED, verdict = "NATIVE_REFUSED:${rr.reason.take(60)}", settledAtMs = atMs)
        }
        receiver8144(h.named, h.from, reads)?.let { r ->
            return h.copy(receiver = r, state = State8144.RECEIVER_QUALIFIED, verdict = if (r == h.named) "NAMED_QUALIFIED" else "OTHER_QUALIFIED")
        }
        val namedRead = h.named?.let { n -> reads.firstOrNull { canon8144(it.lane) == n } }
        if (namedRead != null) {
            if (dataPending8167(namedRead.reason)) return h
            return h.copy(receiver = h.named, state = State8144.ARRIVED_EVALUATED,
                verdict = "NATIVE_REFUSED:${namedRead.reason.take(60)}", settledAtMs = atMs)
        }
        if (reads.any { canon8144(it.lane) != h.from && dataPending8167(it.reason) }) return h
        val others = reads.count { canon8144(it.lane) != h.from && canon8144(it.lane) != "CORE" }
        return if (others > 0) h.copy(state = State8144.NO_QUALIFIED_RECEIVER, verdict = "NONE_OF_${others}_QUALIFIED", settledAtMs = atMs)
        else h
    }

    /** Pure: a FinalDecisionGate verdict by [lane] at [atMs]: a completed evaluation when it is the receiver (or none was set yet). */
    fun onFdg8144(h: Handoff8144, lane: String, allow: Boolean, reason: String?, atMs: Long): Handoff8144 {
        if (!h.open || atMs + SAME_READ_SLACK_MS_8144 < h.emittedAtMs) return h
        val l = canon8144(lane)
        if (l.isBlank() || l == h.from) return h
        if (h.receiver != null && l != h.receiver) return h
        return h.copy(receiver = l, state = State8144.ARRIVED_EVALUATED,
            verdict = if (allow) "FDG_ALLOW" else "FDG_REFUSED:${reason.orEmpty().take(60)}", settledAtMs = atMs)
    }

    /** Pure (V5.0.8153): the launch stage a PHASE_HANDOFF_8136_<stage>_TO_<lane> reason names, or null (TOO_OLD, other). */
    internal fun stageOf8153(reason: String?): LaunchLifecycle8136.Stage8136? {
        val r = reason.orEmpty()
        if (!r.startsWith("PHASE_HANDOFF_8136_")) return null
        val stage = r.removePrefix("PHASE_HANDOFF_8136_").substringBefore("_TO_")
        return LaunchLifecycle8136.Stage8136.values().firstOrNull { it.name == stage }
    }

    /**
     * Pure (V5.0.8153): a settled hand-off does not swallow the launch's NEXT phase: a later stage, or the same phase
     * handed to a different named lane (its cap crossed the runner floor), is a new hand-off. 5.0.8151: a BIRTH hand-off
     * that settled NO_QUALIFIED_RECEIVER held the mint for ten minutes, and the EXPANDING hand-off to the runner lane
     * that followed (the RCR shape) was never recorded, so no receiver was ever asked.
     */
    fun newHandoff8153(prior: Handoff8144, named: String?, reason: String): Boolean {
        val now = stageOf8153(reason) ?: return false
        val was = stageOf8153(prior.reason) ?: return true
        val n = canon8144(named)
        return now.ordinal > was.ordinal || (n.isNotBlank() && prior.named != null && n != prior.named)
    }

    /** Pure (V5.0.8161): a transferred receiver remains the same hand-off; no eligible successor is a named terminal. */
    fun retarget8153(h: Handoff8144, from: String, to: String, reason: String, atMs: Long): Handoff8144 {
        val f = canon8144(from)
        if (!h.open || h.state != State8144.RECEIVER_QUALIFIED || h.receiver != f) return h
        val tried = h.tried8153 + f
        val next = canon8144(to)
        return if (next.isNotBlank()) h.copy(
            receiver = next, tried8153 = tried, verdict = "TRANSFERRED_TO_$next:${reason.take(40)}",
        ) else h.copy(
            receiver = null, tried8153 = tried, state = State8144.NO_QUALIFIED_RECEIVER,
            verdict = "FIT_NO_COMPATIBLE_RECEIVER:${reason.take(50)}", settledAtMs = atMs,
        )
    }

    /**
     * Pure (V5.0.8153): [onNative8144], except that a qualified receiver now refusing on FIT ([fit]: cap / age band,
     * phase, role floor) hands the hand-off on to the best other qualified lane it has not refused for; with none left,
     * or on any other refusal (judgement / safety stay binding), the receiver judged it exactly as before.
     */
    fun onNativeTransfer8153(h: Handoff8144, reads: Collection<LaneRead8144>, atMs: Long, fit: (String) -> Boolean): Handoff8144 {
        if (!h.open || h.state != State8144.RECEIVER_QUALIFIED || atMs + SAME_READ_SLACK_MS_8144 < h.emittedAtMs) return onNative8144(h, reads, atMs)
        val receiver = h.receiver ?: return onNative8144(h, reads, atMs)
        val rr = reads.firstOrNull { canon8144(it.lane) == receiver } ?: return h
        if (rr.eligible || !fit(rr.reason)) return onNative8144(h, reads, atMs)
        val tried = h.tried8153 + receiver
        val next = receiver8144(null, h.from, reads.filter { canon8144(it.lane) !in tried }) ?: return onNative8144(h, reads, atMs)
        return h.copy(receiver = next, tried8153 = tried, verdict = "TRANSFERRED_FIT_FROM_${receiver}")
    }

    /** Pure: an open hand-off past [HANDOFF_TTL_MS_8144] ends unevaluated, never as a success. */
    fun expire8144(h: Handoff8144, nowMs: Long): Handoff8144 {
        if (!h.open || nowMs - h.emittedAtMs <= HANDOFF_TTL_MS_8144) return h
        val why = if (h.state == State8144.RECEIVER_QUALIFIED) "QUALIFIED_${h.receiver}_NEVER_REACHED_FDG" else "NAMED_${h.named ?: "-"}_NEVER_EVALUATED"
        return h.copy(state = State8144.EXPIRED_NOT_EVALUATED, verdict = why, settledAtMs = nowMs)
    }

    // ── runtime ledger ──

    private const val MAX_MINTS_8144 = 4_000
    private val ledger = ConcurrentHashMap<String, Handoff8144>()
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * V5.0.8153 — expiry while a receiver task carries the hand-off: a live task keeps it open (its own clock, refreshed
     * only by valid evidence and capped, decides); an evaluated task is the arrival; a task that ended deferred or refused
     * by name ends the hand-off with that name. Without a task, [expire8144] as before.
     */
    private fun expireLive8153(h: Handoff8144, nowMs: Long): Handoff8144 {
        if (!h.open) return h
        val t = try { com.lifecyclebot.engine.HandoffReceiverTasks8153.taskOf8153(h.mint, nowMs) } catch (_: Throwable) { null }
        if (t == null || t.createdAtMs + SAME_READ_SLACK_MS_8144 < h.emittedAtMs || (h.receiver != null && t.lane != h.receiver)) return expire8144(h, nowMs)
        return when (t.outcome) {
            com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153.PENDING -> h
            com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153.EVALUATED ->
                h.copy(receiver = t.lane, state = State8144.ARRIVED_EVALUATED, verdict = t.verdict.take(70), settledAtMs = t.settledAtMs)
            else -> h.copy(state = State8144.EXPIRED_NOT_EVALUATED, verdict = "TASK_${t.outcome.name}:${t.verdict.take(50)}", settledAtMs = nowMs)
        }
    }

    /** V5.0.8153 — the receiver task moved [from] -> [to] at the lane gate (a fit refusal there): the ledger follows. */
    fun retarget8153(mint: String, from: String, to: String, reason: String = "", nowMs: Long = System.currentTimeMillis()) {
        val prior = ledger[mint] ?: return
        val next = retarget8153(prior, from, to, reason, nowMs)
        if (next === prior) return
        ledger[mint] = next
        settled8144(prior, next)
    }

    /**
     * V5.0.8153 — after a native read: a qualified receiver opens / refreshes its evaluation task (the ledger follows the
     * lane the candidate's task is for), a fit transfer moves the task, and a receiver that refused ends its task by name.
     */
    private fun followTask8153(before: Handoff8144, after: Handoff8144, reads: Collection<LaneRead8144>, atMs: Long): Handoff8144 {
        try {
            val rt = com.lifecyclebot.engine.HandoffTaskRuntime8153
            val was = before.receiver
            if (before.state == State8144.RECEIVER_QUALIFIED && was != null) {
                if (after.state == State8144.RECEIVER_QUALIFIED && after.receiver != null && after.receiver != was) {
                    rt.onLedgerTransfer8153(after.mint, was, after.receiver, after.verdict, atMs)
                } else if (after.state == State8144.ARRIVED_EVALUATED && after.verdict.startsWith("NATIVE_REFUSED:")) {
                    rt.onReceiverNativeRefused8153(after.mint, was, after.verdict.removePrefix("NATIVE_REFUSED:"), atMs)
                }
            }
            val receiver = after.receiver
            if (after.state != State8144.RECEIVER_QUALIFIED || receiver == null) return after
            val eligible = reads.filter { it.eligible }.map { canon8144(it.lane) }.toSet()
            val lane = rt.onReceiverQualified8153(after.mint, after.from, receiver, after.reason, eligible, after.emittedAtMs, atMs, atMs)
            if (lane != null && lane != receiver) return after.copy(receiver = lane, verdict = "TASK_RECEIVER_$lane")
        } catch (_: Throwable) {}
        return after
    }

    private fun count8144(key: String) {
        counts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SNIPER_HANDOFF_8144_$key") } catch (_: Throwable) {}
    }

    /** Counts a settle transition once and feeds the runner trace. */
    private fun settled8144(before: Handoff8144?, after: Handoff8144) {
        if (before === after || before?.state == after.state) return
        when (after.state) {
            State8144.ARRIVED_EVALUATED -> count8144(if (after.verdict.startsWith("FDG_")) "ARRIVED_FDG" else "ARRIVED_NATIVE_REFUSED")
            State8144.NO_QUALIFIED_RECEIVER -> count8144("NO_QUALIFIED_RECEIVER")
            // V5.0.8153 — a task that ended deferred / refused by name is not "never evaluated": counted under its own name.
            State8144.EXPIRED_NOT_EVALUATED -> count8144(when {
                after.verdict.startsWith("TASK_DEFERRED_DATA") -> "TASK_DEFERRED_DATA"
                after.verdict.startsWith("TASK_REFUSED") -> "TASK_REFUSED_NAMED"
                else -> "EXPIRED_NOT_EVALUATED"
            })
            State8144.RECEIVER_QUALIFIED -> count8144("RECEIVER_QUALIFIED")
            State8144.EMITTED -> {}
        }
        try { AdmissionRunnerTrace8144.noteHandoff8144(after.mint, "${after.state.name}:${after.receiver ?: after.named ?: "-"}:${after.verdict.take(50)}") } catch (_: Throwable) {}
    }

    /** ProjectSniperAI / LaunchLifecycle8136.noteRoute8136: the sniper handed [mint] on to [named] for [reason]. */
    fun emitHandoff8144(mint: String, from: String, named: String?, reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val prior = ledger[mint]
        val prev = prior?.let { expireLive8153(it, nowMs) }
        if (prior != null && prev != null && prev !== prior) { ledger[mint] = prev; settled8144(prior, prev) }
        if (prev != null && prev.open) return  // V5.0.8153 — an open hand-off (its receiver task live) is not overwritten
        val next = emit8144(prev, mint, from, named, reason, nowMs)
        if (next === prev) return
        if (ledger.size >= MAX_MINTS_8144) sweep8144(nowMs)
        ledger[mint] = next
        count8144("EMITTED")
        try { AdmissionRunnerTrace8144.noteHandoff8144(mint, "EMITTED:${next.named ?: "-"}:${reason.take(50)}") } catch (_: Throwable) {}
    }

    /** SpecialistBrainBridge7542: a fresh native assessment of every lane in scope for [mint]. */
    fun onNativeSnapshot8144(mint: String, atMs: Long, reads: Collection<LaneRead8144>) {
        try { AdmissionRunnerTrace8144.noteNative8144(mint, atMs, reads) } catch (_: Throwable) {}
        val prior = ledger[mint] ?: return
        // V5.0.8153 — a fit refusal by the receiver hands it on; a qualified receiver becomes a task the cycle consumes.
        val live = expireLive8153(prior, atMs)
        val judged = onNativeTransfer8153(live, reads, atMs) { r -> try { com.lifecyclebot.engine.SpecialistOwnership7951.laneFitRefusal8132(r) } catch (_: Throwable) { false } }
        val next = followTask8153(live, judged, reads, atMs)
        if (next === prior) return
        ledger[mint] = next
        settled8144(prior, next)
    }

    /** SpecialistOwnership7951.onFdgVerdict7951: the FinalDecisionGate judged [mint] for [lane]. */
    fun onFdgVerdict8144(mint: String, lane: String, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        try { AdmissionRunnerTrace8144.noteFdg8144(mint, lane, allow, reason, nowMs) } catch (_: Throwable) {}
        val prior = ledger[mint] ?: return
        val next = onFdg8144(expireLive8153(prior, nowMs), lane, allow, reason, nowMs)
        if (next === prior) return
        ledger[mint] = next
        settled8144(prior, next)
    }

    /** The current hand-off record for [mint] (expired on read), or null. */
    fun handoffOf8144(mint: String, nowMs: Long = System.currentTimeMillis()): Handoff8144? = ledger[mint]?.let { expireLive8153(it, nowMs) }

    private fun sweep8144(nowMs: Long) {
        try {
            for ((k, h) in ledger.entries.toList()) {
                val e = expireLive8153(h, nowMs)
                if (e !== h) { ledger[k] = e; settled8144(h, e) }
            }
            ledger.entries.removeIf { !it.value.open && nowMs - it.value.settledAtMs > SETTLED_HOLD_MS_8144 }
            if (ledger.size >= MAX_MINTS_8144) ledger.entries.sortedBy { it.value.emittedAtMs }.take(MAX_MINTS_8144 / 10).forEach { ledger.remove(it.key) }
        } catch (_: Throwable) {}
    }

    fun statusLine8144(): String {
        sweep8144(System.currentTimeMillis())
        val c = { k: String -> counts[k]?.get() ?: 0L }
        return "handoff8144[emitted=${c("EMITTED")} arrivedFdg=${c("ARRIVED_FDG")} arrivedNativeRefused=${c("ARRIVED_NATIVE_REFUSED")} " +
            "qualified=${c("RECEIVER_QUALIFIED")} noReceiver=${c("NO_QUALIFIED_RECEIVER")} expiredUnevaluated=${c("EXPIRED_NOT_EVALUATED")} " +
            "open=${ledger.values.count { it.open }} taskDeferred=${c("TASK_DEFERRED_DATA")} taskRefusedNamed=${c("TASK_REFUSED_NAMED")}]"
    }
}
