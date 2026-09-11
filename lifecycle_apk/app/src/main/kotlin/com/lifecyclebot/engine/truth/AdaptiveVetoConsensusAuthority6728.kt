package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6728 — §ADAPTIVE_VETO_CONSENSUS.
 *
 * Operator diagnostic from 6727: "the learning/risk brains detect toxic
 * behaviour but remain mostly advisory while BUY/WAIT overrides keep
 * trading." Direct evidence:
 *   Brain Consensus            82.2% SOFT_BLOCK, 0 HARD_BLOCK
 *   Unified Policy bias        -0.83 (strongly negative) — still executing
 *   LLM signal                 "BLOCK: Recent string of losses" → ignored_no_hard_veto
 *   LOSING_STREAK_COHORT_NO_GLOBAL_VETO   = 504
 *   SENTIENCE_VETO_ADVISORY              = 470
 *   EMERGENT_LLM_BLOCK_ADVISORY          = 524
 *   LANE_BUY_INTENT_OVERRIDES_BASE_WAIT  = 565
 *
 * Each subsystem correctly identifies the toxic state but publishes as
 * an ADVISORY. Nobody escalates the collection of advisories into a
 * HARD authority verdict, so BUY/WAIT overrides win by default.
 *
 * This authority owns the single-source-of-truth answer for "is the
 * cumulative advisory posture bad enough to hard-veto?" Each advisory
 * subsystem calls `raise(signal)` to publish its verdict. The authority
 * counts concurrent bad signals and returns `hardVeto=true` when
 * QUORUM (>=3 out of the tracked signals) are simultaneously bad.
 *
 * Consumer wire: ExecutableOpenGate consults at admit path. When
 * hardVeto=true, admission blocks with ADAPTIVE_CONSENSUS_HARD_VETO_6728.
 * This is the mechanism operator asked for: the adaptive layer detects
 * a toxic state, and the admission path is required to honor it.
 *
 * Signals decay after DECAY_MS so a stale bad signal from an hour ago
 * cannot indefinitely brick admission after recovery.
 */
object AdaptiveVetoConsensusAuthority6728 {

    enum class Signal {
        BRAIN_CONSENSUS_SOFT_BLOCK,  // Brain Consensus recommends block
        UNIFIED_POLICY_BIAS_NEGATIVE, // Policy bias below strong-negative threshold
        LLM_BLOCK_ADVISORY,           // LLM says BLOCK
        SENTIENCE_VETO_ADVISORY,      // Sentience heuristic says veto
        LOSING_STREAK_COHORT,         // Recent-streak cohort tagged
        CAPITAL_CREED_BREACH,         // Streak/DD limits breached
        PERFORMANCE_BELOW_50_TARGET,  // PerformanceDoctrine6727 belowTarget
    }

    /** Minimum simultaneously-active signals to escalate to HARD veto. */
    private const val QUORUM = 3
    /** Age after which a raised signal is considered stale. */
    private const val DECAY_MS = 5 * 60_000L

    data class Verdict(
        val hardVeto: Boolean,
        val activeSignals: List<Signal>,
        val quorum: Int,
    )

    private val lastRaisedMs = java.util.concurrent.ConcurrentHashMap<Signal, AtomicLong>()

    private fun ts(sig: Signal): AtomicLong =
        lastRaisedMs.computeIfAbsent(sig) { AtomicLong(0L) }

    /**
     * Publish that the given advisory signal is currently bad. Called
     * by the corresponding advisory subsystem whenever it evaluates and
     * finds the toxic state present.
     */
    fun raise(signal: Signal) {
        ts(signal).set(System.currentTimeMillis())
        try { PipelineHealthCollector.labelInc("ADAPTIVE_VETO_SIGNAL_RAISED_6728_${signal.name}") } catch (_: Throwable) {}
    }

    /** Explicit clear — call when the subsystem now considers the state resolved. */
    fun clear(signal: Signal) {
        ts(signal).set(0L)
    }

    /**
     * Evaluate the current consensus. Returns a Verdict with hardVeto=true
     * iff >=QUORUM signals are currently active (raised within DECAY_MS).
     */
    fun evaluate(): Verdict {
        val now = System.currentTimeMillis()
        val active = Signal.values().filter { s ->
            val at = ts(s).get()
            at > 0L && (now - at) <= DECAY_MS
        }
        val hard = active.size >= QUORUM
        if (hard) {
            try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_HARD_VETO_6728") } catch (_: Throwable) {}
        }
        return Verdict(hard, active, active.size)
    }

    fun isHardVeto(): Boolean = evaluate().hardVeto

    /** Diagnostic snapshot for operator display. */
    fun diagnosticLine(): String {
        val v = evaluate()
        return "ADAPTIVE_CONSENSUS_6728 hardVeto=${v.hardVeto} quorum=${v.quorum}/$QUORUM active=[${v.activeSignals.joinToString(",") { it.name }}]"
    }

    internal fun resetForTest6728() {
        lastRaisedMs.clear()
    }
}
