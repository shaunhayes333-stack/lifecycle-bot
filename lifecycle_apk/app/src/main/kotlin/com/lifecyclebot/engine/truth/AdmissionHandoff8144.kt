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
        if (prior != null && !prior.open && nowMs - prior.settledAtMs in 0L..SETTLED_HOLD_MS_8144) return prior
        return Handoff8144(mint, canon8144(from), canon8144(named).ifBlank { null }, reason.take(80), nowMs)
    }

    /** Pure: a native assessment of every lane in scope ([reads], computed at [atMs]) applied to [h]. */
    fun onNative8144(h: Handoff8144, reads: Collection<LaneRead8144>, atMs: Long): Handoff8144 {
        if (!h.open || atMs + SAME_READ_SLACK_MS_8144 < h.emittedAtMs) return h
        if (h.state == State8144.RECEIVER_QUALIFIED) {
            val rr = reads.firstOrNull { canon8144(it.lane) == h.receiver } ?: return h
            return if (rr.eligible) h
            else h.copy(state = State8144.ARRIVED_EVALUATED, verdict = "NATIVE_REFUSED:${rr.reason.take(60)}", settledAtMs = atMs)
        }
        receiver8144(h.named, h.from, reads)?.let { r ->
            return h.copy(receiver = r, state = State8144.RECEIVER_QUALIFIED, verdict = if (r == h.named) "NAMED_QUALIFIED" else "OTHER_QUALIFIED")
        }
        val namedRead = h.named?.let { n -> reads.firstOrNull { canon8144(it.lane) == n } }
        if (namedRead != null) {
            return h.copy(receiver = h.named, state = State8144.ARRIVED_EVALUATED,
                verdict = "NATIVE_REFUSED:${namedRead.reason.take(60)}", settledAtMs = atMs)
        }
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
            State8144.EXPIRED_NOT_EVALUATED -> count8144("EXPIRED_NOT_EVALUATED")
            State8144.RECEIVER_QUALIFIED -> count8144("RECEIVER_QUALIFIED")
            State8144.EMITTED -> {}
        }
        try { AdmissionRunnerTrace8144.noteHandoff8144(after.mint, "${after.state.name}:${after.receiver ?: after.named ?: "-"}:${after.verdict.take(50)}") } catch (_: Throwable) {}
    }

    /** ProjectSniperAI / LaunchLifecycle8136.noteRoute8136: the sniper handed [mint] on to [named] for [reason]. */
    fun emitHandoff8144(mint: String, from: String, named: String?, reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val prior = ledger[mint]
        val prev = prior?.let { expire8144(it, nowMs) }
        if (prior != null && prev != null && prev !== prior) { ledger[mint] = prev; settled8144(prior, prev) }
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
        val next = onNative8144(expire8144(prior, atMs), reads, atMs)
        if (next === prior) return
        ledger[mint] = next
        settled8144(prior, next)
    }

    /** SpecialistOwnership7951.onFdgVerdict7951: the FinalDecisionGate judged [mint] for [lane]. */
    fun onFdgVerdict8144(mint: String, lane: String, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        try { AdmissionRunnerTrace8144.noteFdg8144(mint, lane, allow, reason, nowMs) } catch (_: Throwable) {}
        val prior = ledger[mint] ?: return
        val next = onFdg8144(expire8144(prior, nowMs), lane, allow, reason, nowMs)
        if (next === prior) return
        ledger[mint] = next
        settled8144(prior, next)
    }

    /** The current hand-off record for [mint] (expired on read), or null. */
    fun handoffOf8144(mint: String, nowMs: Long = System.currentTimeMillis()): Handoff8144? = ledger[mint]?.let { expire8144(it, nowMs) }

    private fun sweep8144(nowMs: Long) {
        try {
            for ((k, h) in ledger.entries.toList()) {
                val e = expire8144(h, nowMs)
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
            "open=${ledger.values.count { it.open }}]"
    }
}
