package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * Scoped advisory consensus. A repeated opinion is not independent evidence.
 * Legacy unscoped publishers remain diagnostic and cannot veto scoped orders.
 * Deterministic safety, capital, price and execution gates remain independent.
 */
object AdaptiveVetoConsensusAuthority6728 {
    enum class Signal {
        BRAIN_CONSENSUS_SOFT_BLOCK, UNIFIED_POLICY_BIAS_NEGATIVE,
        LLM_BLOCK_ADVISORY, SENTIENCE_VETO_ADVISORY, LOSING_STREAK_COHORT,
        CAPITAL_CREED_BREACH, PERFORMANCE_BELOW_50_TARGET,
    }
    private enum class Family { POLICY, ADVISOR, OUTCOME, CAPITAL }
    private fun family(signal: Signal): Family = when (signal) {
        Signal.BRAIN_CONSENSUS_SOFT_BLOCK, Signal.UNIFIED_POLICY_BIAS_NEGATIVE -> Family.POLICY
        Signal.LLM_BLOCK_ADVISORY, Signal.SENTIENCE_VETO_ADVISORY -> Family.ADVISOR
        Signal.LOSING_STREAK_COHORT, Signal.PERFORMANCE_BELOW_50_TARGET -> Family.OUTCOME
        Signal.CAPITAL_CREED_BREACH -> Family.CAPITAL
    }
    private const val QUORUM = 3
    private const val DECAY_MS = 5 * 60_000L
    private const val MAX_SIGNALS = 4096
    private data class Key(val mode: String, val lane: String, val mint: String, val signal: Signal)
    private data class Evidence(val id: String, val atMs: Long)
    private val observations = ConcurrentHashMap<Key, Evidence>()
    private fun normaliseLane(raw: String): String = when (val n = raw.trim().uppercase().replace('-', '_')) {
        "BLUE_CHIP" -> "BLUECHIP"
        "PRESALE_SNIPE" -> "PROJECT_SNIPER"
        else -> n
    }
    data class Verdict(val hardVeto: Boolean, val activeSignals: List<Signal>, val quorum: Int)

    /** Re-reading the same evidence never refreshes its expiry. */
    fun raise(signal: Signal, mode: String = "", lane: String = "", mint: String = "",
              evidenceId: String = signal.name, observedAtMs: Long = System.currentTimeMillis()) {
        val now = System.currentTimeMillis()
        if (observedAtMs <= 0L || observedAtMs > now + 5_000L) return
        val key = Key(mode.trim().uppercase(), normaliseLane(lane), mint.trim(), signal)
        observations.compute(key) { _, old ->
            when {
                old == null -> Evidence(evidenceId, observedAtMs)
                old.id == evidenceId -> old
                observedAtMs >= old.atMs -> Evidence(evidenceId, observedAtMs)
                else -> old
            }
        }
        // Bound memory without allowing repeated polling to keep a veto alive.
        if (observations.size > MAX_SIGNALS) {
            observations.entries.removeIf { now - it.value.atMs > DECAY_MS }
            if (observations.size > MAX_SIGNALS) observations.entries.sortedBy { it.value.atMs }
                .take(observations.size - MAX_SIGNALS).forEach { observations.remove(it.key, it.value) }
        }
        try { PipelineHealthCollector.labelInc("ADAPTIVE_VETO_SIGNAL_RAISED_6728_${signal.name}") } catch (_: Throwable) {}
    }

    fun clear(signal: Signal, mode: String = "", lane: String = "", mint: String = "") {
        observations.remove(Key(mode.trim().uppercase(), normaliseLane(lane), mint.trim(), signal))
    }

    /** No PAPER-to-LIVE, cross-lane or cross-mint vote contamination. */
    fun evaluate(mode: String = "", lane: String = "", mint: String = "",
                 nowMs: Long = System.currentTimeMillis()): Verdict {
        val m = mode.trim().uppercase(); val l = normaliseLane(lane); val token = mint.trim()
        val active = observations.entries.asSequence().filter { (k, v) ->
            k.mode == m && (k.lane == l || (k.lane.isBlank() && k.signal == Signal.CAPITAL_CREED_BREACH)) &&
                (k.mint.isBlank() || k.mint == token) && nowMs - v.atMs in -5_000L..DECAY_MS
        }.map { it.key.signal }.distinct().sortedBy { it.ordinal }.toList()
        val votes = active.map(::family).distinct().size
        val hard = votes >= QUORUM
        if (hard) try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_HARD_VETO_6728") } catch (_: Throwable) {}
        else if (active.size >= QUORUM) try {
            PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_CORRELATED_SOFT_ONLY_6734")
        } catch (_: Throwable) {}
        return Verdict(hard, active, votes)
    }
    fun isHardVeto(): Boolean = evaluate().hardVeto
    fun diagnosticLine(): String {
        val v = evaluate()
        return "ADAPTIVE_CONSENSUS_6728 hardVeto=${v.hardVeto} quorum=${v.quorum}/$QUORUM " +
            "rawSignals=${v.activeSignals.size} scope=LEGACY_DIAGNOSTIC active=[${v.activeSignals.joinToString(",") { it.name }}]"
    }
    internal fun resetForTest6728() { observations.clear() }
}
