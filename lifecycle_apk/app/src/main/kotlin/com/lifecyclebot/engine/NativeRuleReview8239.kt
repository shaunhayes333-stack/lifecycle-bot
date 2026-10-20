package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** Audited soft rules stand down where they fire, before the remaining native checks run. */
object NativeRuleReview8239 {
    private data class Retired(val atMs: Long, val rules: Set<String>)
    private val retired = ConcurrentHashMap<String, Retired>()
    private val softRules = setOf("MCAP_TOO_LOW", "MCAP_TOO_HIGH", "MCAP_TOO_HIGH_FOR", "TOO_OLD", "TOKEN_TOO_OLD",
        "TOO_NEW", "TOO_YOUNG", "NO_CONTINUATION", "CHASE_EXTENDED")
    private fun key(mint: String, lane: String) = "${if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"}|$mint|${lane.uppercase()}"

    /** No safety, missing-input, score, confidence or wallet rule can become an exception here. */
    internal fun mayRetire8239(reason: String, observed: Boolean, audit: (String) -> Boolean): Boolean {
        val rule = DecisionReasons8031.rule8031(reason)
        if (!observed || rule !in softRules || !SpecialistBrainBridge7542.nativeRuleRetirable8048(reason)) return false
        return try { audit("NATIVE_$rule") } catch (_: Throwable) { false }
    }

    fun begin8239(mint: String, lane: String) { retired.remove(key(mint, lane)) }

    fun retires8239(mint: String, lane: String, reason: String): Boolean {
        val ts = BotService.status.tokens[mint] ?: return false
        val now = System.currentTimeMillis()
        val historyN = synchronized(ts.history) {
            ts.history.count { !it.synthetic && it.priceUsd.isFinite() && it.priceUsd > 0.0 && now - it.ts in 0L..120_000L }
        }
        val inputs = LaneInputTruth8236.observed8236(ts, historyN).copy(
            momentum = historyN >= 2 || (ts.priceChangeObservedAtMs8084 > 0L &&
                now - ts.priceChangeObservedAtMs8084 in 0L..120_000L),
            buyPressure = ts.buyPressureObservedAtMs8084 > 0L &&
                now - ts.buyPressureObservedAtMs8084 in 0L..120_000L,
        )
        val movementRule = DecisionReasons8031.rule8031(reason) in setOf("NO_CONTINUATION", "CHASE_EXTENDED")
        val observed = LaneInputTruth8236.deferFor8236(lane, reason, inputs) == null &&
            (!movementRule || inputs.momentum || inputs.buyPressure) &&
            ts.lastPrice.isFinite() && ts.lastPrice > 0.0 && now - ts.lastPriceUpdate in 0L..15_000L &&
            ts.lastMcap.isFinite() && ts.lastMcap > 0.0
        val ok = mayRetire8239(reason, observed) { rule ->
            com.lifecyclebot.engine.cortex.Cortex7885.ownLaneVetoRefusesWinners8076(rule, lane)
        }
        if (ok) {
            val k = key(mint, lane)
            retired.compute(k) { _, prior -> Retired(now, (prior?.rules ?: emptySet()) + DecisionReasons8031.rule8031(reason)) }
            if (retired.size > 4096) retired.entries.removeIf { now - it.value.atMs !in 0L..15_000L }
            try { PipelineHealthCollector.labelInc("NATIVE_RULE_CONTINUED_8239_$lane") } catch (_: Throwable) {}
        }
        return ok
    }

    /** Only read after a scorer returns its final eligible signal. */
    fun completedReason8239(mint: String, lane: String, eligible: Boolean, reason: String): String {
        if (!eligible) return reason
        val r = retired[key(mint, lane)] ?: return reason
        if (System.currentTimeMillis() - r.atMs !in 0L..5_000L) return reason
        return "NATIVE_VETO_RETIRED_8048:COMPLETED:${r.rules.joinToString("+")}:$reason"
    }
}
