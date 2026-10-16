package com.lifecyclebot.engine.market

import com.lifecyclebot.network.PairInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 §A_SCOUT_CANDIDATE_FAILS_BY_NAME.
 *
 * Device 5.0.8168: the Telegram call sweeper extracted 26 contract addresses and verified 0 (21 "unverifiable"); the
 * Groq web scout returned 5 candidates and verified 0. Both verifiers answered one bit, so nothing said why. Read in
 * code, the shared DexScreener check refused by construction the very coins the scouts exist to find:
 *  - a fresh pump.fun coin's only pair is its bonding curve (DexScreener dexId "pumpfun"), which DexScreener serves
 *    with a price and NO liquidity figure — read as liquidity 0, it failed "liquidity > 0" every time;
 *  - a coin DexScreener has not indexed yet (a call posted seconds after launch), or a lookup the shared DexScreener
 *    rate limiter refused locally, returned no pair and was dropped for good on that single attempt.
 * Now every candidate ends with a named verdict ([Verdict8169]); a live curve pair with a price verifies (its liquidity
 * is left unknown, never invented: the ordinary intake — passesFilter, rugcheck, every gate — still decides); and the
 * Telegram sweeper retries a NO_PAIR once after [NO_PAIR_RETRY_MS_8169] while the call is still fresh.
 * The verdict is identity / liveness only: authority stays candidates_only.
 */
object ScoutPairVerdict8169 {

    enum class Verdict8169(val verified: Boolean) {
        VERIFIED(true),
        /** The pump.fun bonding-curve pair: live price, liquidity not reported by DexScreener (left unknown). */
        VERIFIED_CURVE(true),
        NO_PAIR(false),
        WRONG_BASE(false),
        NOT_SOLANA(false),
        QUOTE_TOKEN(false),
        NO_PRICE(false),
        NO_LIQUIDITY(false),
    }

    /** A NO_PAIR is asked again once, this long after the first attempt (DexScreener indexing lag / local rate refusal). */
    const val NO_PAIR_RETRY_MS_8169 = 60_000L

    /** Pure: DexScreener's dexId for a pump.fun bonding curve. */
    fun curvePair8169(dexId: String): Boolean = dexId.trim().equals("pumpfun", ignoreCase = true)

    /**
     * Pure: does [pair] verify [mint] as a live Solana token? The base must be the mint itself, the chain Solana (or
     * unstated), not a quote token, with a finite positive price; then real liquidity, or a pump.fun curve pair.
     */
    fun verdict8169(mint: String, pair: PairInfo?, quoteSymbols: Set<String> = emptySet()): Verdict8169 {
        if (pair == null) return Verdict8169.NO_PAIR
        val base = pair.tokenAddress.ifBlank { pair.baseTokenAddress }
        if (base.isNotBlank() && base != mint) return Verdict8169.WRONG_BASE
        if (pair.chainId.isNotBlank() && !pair.chainId.equals("solana", ignoreCase = true)) return Verdict8169.NOT_SOLANA
        if (quoteSymbols.isNotEmpty() && pair.baseSymbol.uppercase() in quoteSymbols) return Verdict8169.QUOTE_TOKEN
        if (!(pair.candle.priceUsd.isFinite() && pair.candle.priceUsd > 0.0)) return Verdict8169.NO_PRICE
        if (pair.liquidity.isFinite() && pair.liquidity > 0.0) return Verdict8169.VERIFIED
        return if (curvePair8169(pair.dexId)) Verdict8169.VERIFIED_CURVE else Verdict8169.NO_LIQUIDITY
    }

    /** Pure: a NO_PAIR on the first attempt of a still-fresh call is asked once more. */
    fun retryNoPair8169(verdict: Verdict8169, attempt: Int, postAgeMs: Long, freshMaxMs: Long): Boolean =
        verdict == Verdict8169.NO_PAIR && attempt == 0 && postAgeMs + NO_PAIR_RETRY_MS_8169 <= freshMaxMs

    /** A per-scout tally of verdicts, printed as "by{VERDICT=n,...}". */
    class Tally8169 {
        private val counts = ConcurrentHashMap<String, AtomicLong>()
        fun note(key: String) { counts.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet() }
        fun line(): String = counts.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
    }
}
