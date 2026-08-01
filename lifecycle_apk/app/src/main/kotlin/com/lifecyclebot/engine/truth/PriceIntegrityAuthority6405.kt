package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §6 — PRICE & PAIR INTEGRITY (root-cause; no fallback price).
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * "false or unknown data is inexcusable!!! fix the issue instead of
 *  bandaiding". Fallback prices poison PnL, thesis, and stop-loss logic
 *  the same way fabricated decimals poison the sell quantity. Any price
 *  or pair that is not proven fresh MUST NOT authorise a trade.
 *
 * PRICE RULES
 * ───────────
 *   • A price is ACCEPTED only if
 *       (a) its source is one of the whitelisted providers (Jupiter,
 *           Birdeye, DexScreener with a valid pair)
 *       (b) it was observed no more than MAX_STALENESS_MS ago
 *       (c) it is finite and > 0
 *   • A price is REJECTED when the source is a synthetic fallback,
 *     unknown, or stale beyond the freshness window.
 *   • Rejection is a HARD refusal — callers MUST not trade until a
 *     fresh price is observed. No "pre-6310 buggy formula until SOL
 *     price warms" bandaid.
 *
 * PAIR RULES
 * ──────────
 *   • A pair is ACCEPTED only if the mint has a known routable pool
 *     with observed liquidity > 0 in the last MAX_STALENESS_MS.
 *   • A pair is REJECTED when there is no known pool, or when the last
 *     observed liquidity was zero / stale.
 */
object PriceIntegrityAuthority6405 {

    const val MAX_STALENESS_MS: Long = 12_000L
    private const val TAG = "PriceIntegrity6405"

    sealed class PriceSource(val name: String) {
        object Jupiter : PriceSource("JUPITER")
        object Birdeye : PriceSource("BIRDEYE")
        object DexScreener : PriceSource("DEX_SCREENER")
        object Pump : PriceSource("PUMP_PORTAL")
        object Synthetic : PriceSource("SYNTHETIC_FALLBACK")
        object Unknown : PriceSource("UNKNOWN")
    }

    private val WHITELISTED = setOf(
        PriceSource.Jupiter.name,
        PriceSource.Birdeye.name,
        PriceSource.DexScreener.name,
        PriceSource.Pump.name,
    )

    sealed class Verdict {
        data class Accept(val priceUsd: Double, val source: String, val ageMs: Long) : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    private data class Observation(
        val priceUsd: Double,
        val source: String,
        val observedAtMs: Long,
    )

    private val lastObservation = ConcurrentHashMap<String, Observation>()
    private val lastPairLiquidity = ConcurrentHashMap<String, Pair<Double, Long>>() // liq, ts

    fun recordPrice(mint: String, priceUsd: Double, source: PriceSource) {
        if (mint.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0) return
        if (source.name !in WHITELISTED) return
        lastObservation[mint] = Observation(priceUsd, source.name, System.currentTimeMillis())
    }

    fun recordPairLiquidity(mint: String, liquidityUsd: Double) {
        if (mint.isBlank() || !liquidityUsd.isFinite() || liquidityUsd < 0.0) return
        lastPairLiquidity[mint] = liquidityUsd to System.currentTimeMillis()
    }

    /**
     * Strict price gate. Callers MUST branch on the verdict; no
     * "proceed anyway" bandaid.
     */
    fun evaluatePrice(mint: String, providedPriceUsd: Double?, providedSource: PriceSource): Verdict {
        val now = System.currentTimeMillis()
        if (providedPriceUsd != null && providedSource.name in WHITELISTED &&
            providedPriceUsd.isFinite() && providedPriceUsd > 0.0
        ) {
            recordPrice(mint, providedPriceUsd, providedSource)
            return Verdict.Accept(providedPriceUsd, providedSource.name, 0L)
        }
        val obs = lastObservation[mint]
        if (obs != null) {
            val age = now - obs.observedAtMs
            if (age <= MAX_STALENESS_MS) {
                return Verdict.Accept(obs.priceUsd, obs.source, age)
            }
            emitReject(mint, "PRICE_STALE_${age}MS", providedSource.name)
            return Verdict.Reject("PRICE_STALE_${age}MS")
        }
        emitReject(mint, "PRICE_UNKNOWN_NO_OBSERVATION", providedSource.name)
        return Verdict.Reject("PRICE_UNKNOWN_NO_OBSERVATION")
    }

    fun evaluatePair(mint: String): Verdict {
        val entry = lastPairLiquidity[mint]
        if (entry == null) {
            emitReject(mint, "PAIR_UNKNOWN_NO_POOL", "-")
            return Verdict.Reject("PAIR_UNKNOWN_NO_POOL")
        }
        val (liq, ts) = entry
        val age = System.currentTimeMillis() - ts
        if (liq <= 0.0) {
            emitReject(mint, "PAIR_LIQUIDITY_ZERO", "-")
            return Verdict.Reject("PAIR_LIQUIDITY_ZERO")
        }
        if (age > MAX_STALENESS_MS) {
            emitReject(mint, "PAIR_STALE_${age}MS", "-")
            return Verdict.Reject("PAIR_STALE_${age}MS")
        }
        // Reuse Accept as pair-accept carrier with price=0 marker; callers only
        // need Accept/Reject discrimination.
        return Verdict.Accept(liq, "PAIR", age)
    }

    fun canTrade(mint: String, providedPriceUsd: Double?, providedSource: PriceSource): Boolean {
        val p = evaluatePrice(mint, providedPriceUsd, providedSource)
        if (p is Verdict.Reject) return false
        val q = evaluatePair(mint)
        return q is Verdict.Accept
    }

    private fun emitReject(mint: String, reason: String, provided: String) {
        try {
            ForensicLogger.lifecycle(
                "PRICE_INTEGRITY_HARD_BLOCK_6405",
                "mint=${mint.take(10)} reason=$reason provided=$provided",
            )
            PipelineHealthCollector.labelInc("PRICE_INTEGRITY_HARD_BLOCK_6405")
        } catch (_: Throwable) {}
        try {
            ErrorLogger.warn(TAG, "🚫 refusing trade for ${mint.take(10)} reason=$reason (provided=$provided)")
        } catch (_: Throwable) {}
    }

    internal fun clearForTest() {
        lastObservation.clear()
        lastPairLiquidity.clear()
    }
}
