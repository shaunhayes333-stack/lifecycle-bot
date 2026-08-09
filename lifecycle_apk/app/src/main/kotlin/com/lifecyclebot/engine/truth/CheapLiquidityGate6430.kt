package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6430 §G — CHEAP LIQUIDITY PRE-CHECK.
 *
 * OPERATOR (V5.0.6424 spec §G):
 *   V3 rejects: ZERO_LIQUIDITY=169 LOW_LIQUIDITY=22.
 *   'Do a cheap canonical liquidity sanity check before expensive V3
 *    scoring when reliable liquidity data is already available. Do NOT
 *    weaken safety. This is workload shaping.'
 *
 * DESIGN
 * ──────
 * quickCheck(...) returns a Decision:
 *   OBVIOUS_ZERO   — liquidityUsd == 0 with high provider confidence
 *   OBVIOUS_LOW    — liquidityUsd below floor with high confidence
 *   UNKNOWN        — data missing/conflicting; do NOT reject, request
 *                     alternate evidence and continue to V3
 *   OK             — proceed to V3
 *
 * Callers should skip expensive scoring when Decision is OBVIOUS_ZERO
 * or OBVIOUS_LOW and emit the forensic reason instead.
 */
object CheapLiquidityGate6430 {

    private const val OBVIOUS_LOW_FLOOR_USD = 1_500.0

    enum class Verdict { OBVIOUS_ZERO, OBVIOUS_LOW, UNKNOWN, OK }

    data class Decision(val verdict: Verdict, val reason: String)

    fun quickCheck(
        mint: String,
        symbol: String,
        liquidityUsd: Double?,
        providerConfidence: Int,  // 0..3 — number of independent providers agreeing
    ): Decision {
        if (liquidityUsd == null || !liquidityUsd.isFinite()) {
            return Decision(Verdict.UNKNOWN, "no_liquidity_data")
        }
        if (providerConfidence < 1) {
            return Decision(Verdict.UNKNOWN, "no_provider_confidence")
        }
        if (liquidityUsd <= 0.0 && providerConfidence >= 2) {
            emit("CHEAP_LIQ_GATE_ZERO_6430", mint, symbol, liquidityUsd, providerConfidence)
            return Decision(Verdict.OBVIOUS_ZERO, "obvious_zero_confirmed_by_${providerConfidence}_providers")
        }
        if (liquidityUsd < OBVIOUS_LOW_FLOOR_USD && providerConfidence >= 2) {
            emit("CHEAP_LIQ_GATE_LOW_6430", mint, symbol, liquidityUsd, providerConfidence)
            return Decision(Verdict.OBVIOUS_LOW, "obvious_low_${"%.0f".format(liquidityUsd)}_confirmed_by_${providerConfidence}")
        }
        return Decision(Verdict.OK, "ok")
    }

    private fun emit(label: String, mint: String, symbol: String, liq: Double?, conf: Int) {
        try {
            ForensicLogger.lifecycle(
                label,
                "mint=${mint.take(10)} sym=$symbol liqUsd=$liq providerConf=$conf",
            )
            PipelineHealthCollector.labelInc(label)
        } catch (_: Throwable) {}
    }
}
