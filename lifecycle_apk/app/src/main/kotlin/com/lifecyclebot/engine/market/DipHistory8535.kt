package com.lifecyclebot.engine.market

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8535 §P0-10 — DIP_HUNTER's history state.
 *
 * The hunter admitted a coin on the sweep row's 1 h / 24 h drawdown, the bridge then scored it on the bot's OWN price
 * history, falling back to `high = max(price, history.high ?: price)`: with no observed high the dip depth was 0 % by
 * construction and DipHunterAI refused NOT_A_DIP (8529: NATIVE_PRICE_HISTORY_UNOBSERVED 735, 0 allows). Unknown is not a
 * 0 % dip. The lane now has three states:
 *  - OBSERVED_HIGH: the bot's own tape holds a high and at least four bounce points — the native read as designed;
 *  - ROW_REFERENCE: no observed high yet, but the hunter's dated row implies a prior price (the price before its known
 *    1 h / 24 h drawdown): a lower-bound prior high with provenance, used with the bot's own bounce witness;
 *  - WAIT: no high and no reference, or fewer than four bounce points — a history-hydration state (the bridge asks for
 *    the history and publishes WAIT_FOR_DIP_HISTORY_8535), not a market judgement.
 */
object DipHistory8535 {

    enum class State { OBSERVED_HIGH, ROW_REFERENCE, WAIT }

    data class RowRef(val priceUsd: Double, val h1Pct: Double, val h24Pct: Double, val atMs: Long, val venue: String)

    const val WAIT_REASON = "WAIT_FOR_DIP_HISTORY_8535"
    /** The native bounce witness (NativeHistory8521.bounce8521) needs this many observed points. */
    const val MIN_BOUNCE_POINTS = 4
    private const val REF_TTL_MS = 30L * 60_000L
    private const val MAX_REFS = 4_096
    private val refs = ConcurrentHashMap<String, RowRef>()

    fun noteRow(mint: String, priceUsd: Double, h1Pct: Double, h24Pct: Double, atMs: Long, venue: String) {
        if (mint.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0) return
        if (refs.size >= MAX_REFS) refs.entries.removeIf { atMs - it.value.atMs > REF_TTL_MS }
        if (refs.size >= MAX_REFS) return
        val prior = refs[mint]
        if (prior == null || atMs >= prior.atMs) refs[mint] = RowRef(priceUsd, h1Pct, h24Pct, atMs, venue)
    }

    fun ref(mint: String, nowMs: Long): RowRef? = refs[mint]?.takeIf { nowMs - it.atMs in 0L..REF_TTL_MS }

    /**
     * Pure: the prior price the row implies — the row price before its larger known drawdown (1 h or 24 h). Null when the
     * row shows no drawdown, or the implied prior is not above [price] (nothing to reclaim from).
     */
    fun referenceHigh(ref: RowRef?, price: Double): Double? {
        if (ref == null || !price.isFinite() || price <= 0.0) return null
        val implied = listOf(ref.h24Pct, ref.h1Pct)
            .filter { it.isFinite() && it < 0.0 && it > -100.0 }
            .map { ref.priceUsd / (1.0 + it / 100.0) }
        val high = implied.maxOrNull() ?: return null
        return high.takeIf { it.isFinite() && it > price }
    }

    /** Pure: which read the lane gets. */
    fun stateOf(observedHigh: Double?, bouncePoints: Int, ref: RowRef?, price: Double): State = when {
        bouncePoints < MIN_BOUNCE_POINTS -> State.WAIT
        observedHigh != null && observedHigh.isFinite() && observedHigh > 0.0 -> State.OBSERVED_HIGH
        referenceHigh(ref, price) != null -> State.ROW_REFERENCE
        else -> State.WAIT
    }

    internal fun clearForTests() = refs.clear()
}
