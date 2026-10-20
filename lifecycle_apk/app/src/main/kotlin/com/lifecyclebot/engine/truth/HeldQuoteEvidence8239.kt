package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** A holding valuation retains its quantity, observation clock and type of evidence. */
object HeldQuoteEvidence8239 {
    enum class Kind { ROUTED_QUOTE, RESERVE_MODEL }

    data class Holding(
        val mint: String,
        val positionId: String,
        val mode: String,
        val canonicalRaw: BigInteger,
        val rawQty: BigInteger,
        val decimals: Int,
        val venueContext: String,
    )

    data class Observation(
        val priceUsd: Double,
        val outSol: Double,
        val rawQty: BigInteger,
        val decimals: Int,
        val kind: Kind,
        val venue: String,
        val pool: String,
        val observedAtMs: Long,
        val requestedAtMs: Long,
        val feeAssumptionPct: Double = Double.NaN,
    ) {
        val source: String get() = if (kind == Kind.ROUTED_QUOTE) "JUPITER_HELD_SELL_QUOTE_8189" else "HELD_VENUE_RESERVE_MODEL_8239"
    }

    fun matches(expected: Holding, actual: Holding): Boolean =
        expected.positionId.isNotBlank() && expected.mode.equals("live", true) &&
            expected == actual && expected.canonicalRaw.signum() > 0 && expected.rawQty.signum() > 0 &&
            expected.rawQty <= expected.canonicalRaw && expected.decimals in 0..18

    /** A modeled pool payout is valuation evidence; it never authorizes a transaction. */
    fun fresh(observation: Observation, nowMs: Long): Boolean {
        val ttl = if (observation.kind == Kind.ROUTED_QUOTE) 4_000L else 20_000L
        return observation.priceUsd.isFinite() && observation.priceUsd > 0.0 &&
            observation.outSol.isFinite() && observation.outSol > 0.0 &&
            observation.rawQty.signum() > 0 && observation.decimals in 0..18 &&
            observation.observedAtMs > 0L && observation.observedAtMs <= observation.requestedAtMs &&
            nowMs - observation.observedAtMs in 0L..ttl
    }

    /** Rejection must release the coalescing key exactly as completion does. Never runs rejected work on the caller. */
    internal fun enqueue(executor: Executor, keys: MutableSet<String>, key: String, work: () -> Unit): Boolean {
        if (!keys.add(key)) return false
        return try {
            executor.execute { try { work() } finally { keys.remove(key) } }
            true
        } catch (_: RejectedExecutionException) {
            keys.remove(key)
            false
        } catch (t: Throwable) {
            keys.remove(key)
            throw t
        }
    }
}
