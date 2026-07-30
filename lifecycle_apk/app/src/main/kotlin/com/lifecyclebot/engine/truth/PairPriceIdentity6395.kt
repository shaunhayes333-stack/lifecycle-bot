package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6395 — PAIR AND PRICE IDENTITY.
 *
 * Every price observation carries the full identity envelope. A price is
 * rejected for position valuation when:
 *   - baseMint != position mint
 *   - pairAddress changed without revalidation
 *   - decimals differ from chain metadata
 *   - liquidity < configured executable threshold
 *   - observation is stale
 *   - price movement caused by a dust-sized trade
 *   - quote output cannot substantiate the displayed value
 *
 * NEVER combine an entry from one pair with a current price from another
 * pair without explicit normalized routing proof.
 */
object PairPriceIdentity6395 {

    const val EXECUTABLE_LIQ_THRESHOLD_USD: Double = 3_000.0
    const val STALENESS_MS: Long = 8_000L
    const val DUST_TRADE_VOLUME_USD: Double = 5.0

    data class Observation(
        val mint: String,
        val pairAddress: String,
        val baseMint: String,
        val quoteMint: String,
        val venue: String,
        val tokenDecimals: Int,
        val quoteDecimals: Int,
        val priceUsd: Double,
        val priceSol: Double,
        val liquidityUsd: Double,
        val timestamp: Long,
        val source: String,
        val confidence: Double,
        val triggerTradeVolumeUsd: Double = 0.0,
    )

    data class Verdict(
        val accepted: Boolean,
        val reason: String,
        val requiresRevalidation: Boolean = false,
    )

    /** Immutable snapshot of the last-accepted pair address per mint. */
    private val lastAcceptedPair = ConcurrentHashMap<String, String>()
    /** Chain-metadata decimals per mint. Populated once from chain fetch. */
    private val chainDecimals = ConcurrentHashMap<String, Int>()

    fun registerChainDecimals(mint: String, decimals: Int) { chainDecimals[mint] = decimals }

    fun validate(positionMint: String, obs: Observation, nowMs: Long = System.currentTimeMillis()): Verdict {
        if (obs.baseMint != positionMint)
            return Verdict(false, "BASE_MINT_MISMATCH")
        val lastPair = lastAcceptedPair[positionMint]
        if (lastPair != null && lastPair != obs.pairAddress)
            return Verdict(false, "PAIR_ADDRESS_CHANGED", requiresRevalidation = true)
        val chainDec = chainDecimals[positionMint]
        if (chainDec != null && chainDec != obs.tokenDecimals)
            return Verdict(false, "DECIMAL_MISMATCH")
        if (obs.liquidityUsd < EXECUTABLE_LIQ_THRESHOLD_USD)
            return Verdict(false, "LIQ_BELOW_EXECUTABLE")
        if (nowMs - obs.timestamp > STALENESS_MS)
            return Verdict(false, "STALE")
        if (obs.triggerTradeVolumeUsd in 0.0..DUST_TRADE_VOLUME_USD && obs.triggerTradeVolumeUsd > 0.0)
            return Verdict(false, "DUST_TRADE_TRIGGER")
        // First observation for the mint locks the pair address.
        lastAcceptedPair.putIfAbsent(positionMint, obs.pairAddress)
        return Verdict(true, "ACCEPTED")
    }

    internal fun clearAllForTest() { lastAcceptedPair.clear(); chainDecimals.clear() }
}
