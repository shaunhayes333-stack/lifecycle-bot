package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.9.1395 — P1-6 Canonical liquidity model.
 *
 * Spec mandate (V5.9.1386 P1-6):
 *   "One canonical liquidity model (normalized liquidity USD, freshness
 *    timestamp, confidence) used universally by scanner filters, protected
 *    intake, safety, V3, FDG, and EXEC_GATE."
 *
 * Previously every consumer read [TokenState.lastLiquidityUsd] directly
 * and applied its own freshness/confidence interpretation — some treated
 * 0.0 as "unknown", others as "zero-liq reject", some honored the field
 * even if it was 30 minutes stale. That inconsistency was the root of
 * the "SantaHat $13,928 wrongly blocked" symptom: the gate saw a stale
 * 0.0 and rejected while the scanner saw a live $13.9k and admitted.
 *
 * This helper unifies the reading:
 *
 *   val liq = CanonicalLiquidity.read(ts)
 *   if (liq.isUsableForLiveGate()) { ... }
 *
 * Writers call [stamp] when they receive fresh liquidity from a source
 * (HIGH/MEDIUM/LOW confidence). All other state changes leave the
 * freshness fields untouched so the reader can detect staleness.
 *
 * Doctrine:
 *  - Pure read/write helper. Doesn't gate anything itself.
 *  - Fail-open: missing/zero/blank inputs return a Reading with
 *    confidence=UNKNOWN, ageMs=Long.MAX_VALUE so consumers naturally
 *    fall back to their own defaults rather than getting a false-high
 *    signal.
 *  - Confidence ladder: HIGH (real on-chain pool quote, e.g. direct
 *    Raydium/Bonk pool read) > MEDIUM (DEX aggregator, e.g.
 *    DexScreener token-pairs API) > LOW (synthetic/derived, e.g.
 *    mcap-divided or graduation-curve estimate) > UNKNOWN (never set).
 */
object CanonicalLiquidity {

    enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

    /**
     * Hard freshness ceiling. Anything older than this is treated as
     * stale regardless of recorded confidence — providers that don't
     * stamp freshness shouldn't pin a long-dead reading.
     */
    private const val MAX_USABLE_AGE_MS = 5L * 60_000L  // 5 min

    /**
     * Minimum live-gate floor (USD). Liquidity below this is rejected
     * by gates that consult [isUsableForLiveGate]. Aligned with the
     * operator-mandated $2000 LIQUIDITY floor in TradeAuthorizer GATE 3.
     */
    private const val LIVE_GATE_FLOOR_USD = 2_000.0

    data class Reading(
        val usd: Double,
        val ageMs: Long,
        val confidence: Confidence,
    ) {
        val isFresh: Boolean get() = ageMs in 0..MAX_USABLE_AGE_MS

        /**
         * True if this reading is good enough to drive a live execution
         * gate. Requires both freshness AND a non-trivial dollar value
         * AND at least MEDIUM confidence — UNKNOWN confidence on a 0.0
         * USD field is the typical "scanner never populated it" case
         * which a live gate must NOT trust.
         */
        fun isUsableForLiveGate(): Boolean =
            isFresh &&
                usd >= LIVE_GATE_FLOOR_USD &&
                (confidence == Confidence.HIGH || confidence == Confidence.MEDIUM)

        fun summary(): String = "%.0fusd age=%dms conf=%s".format(usd, ageMs, confidence.name)
    }

    fun read(ts: TokenState?): Reading {
        if (ts == null) return Reading(0.0, Long.MAX_VALUE, Confidence.UNKNOWN)
        val usd = ts.lastLiquidityUsd
        val tsMs = ts.lastLiquidityUsdMs
        val age = if (tsMs <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - tsMs).coerceAtLeast(0L)
        val conf = parseConfidence(ts.lastLiquidityUsdConfidence)
        return Reading(usd = usd, ageMs = age, confidence = conf)
    }

    fun stamp(ts: TokenState, usd: Double, confidence: Confidence) {
        if (usd.isNaN() || usd.isInfinite()) return
        ts.lastLiquidityUsd = usd.coerceAtLeast(0.0)
        ts.lastLiquidityUsdMs = System.currentTimeMillis()
        ts.lastLiquidityUsdConfidence = confidence.name
    }

    private fun parseConfidence(s: String): Confidence = when (s.trim().uppercase()) {
        "HIGH" -> Confidence.HIGH
        "MEDIUM", "MED" -> Confidence.MEDIUM
        "LOW" -> Confidence.LOW
        else -> Confidence.UNKNOWN
    }
}
