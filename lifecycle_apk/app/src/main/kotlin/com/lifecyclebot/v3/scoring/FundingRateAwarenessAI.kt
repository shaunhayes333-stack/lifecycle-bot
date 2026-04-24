package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — FundingRateAwarenessAI
 *
 * For leveraged/perps trades (CryptoAltTrader, TokenizedStockTrader).
 * Reads perps funding rate per symbol from the existing PerpsData /
 * CoinGecko poller and emits a bias:
 *
 *   Very positive funding (longs paying shorts heavily) = crowded longs,
 *   reversion likely. LONG entries penalized; SHORT entries bonused.
 *   Very negative funding = crowded shorts, reversion up. Inverse.
 *
 * The funding poll runs in PerpsData's existing 5-minute tick; this
 * module is a cheap adapter reading from a published map. Score only
 * emitted for symbols that have funding data.
 */
object FundingRateAwarenessAI {

    private const val TAG = "FundRate"

    // symbol → annualised funding rate (0.10 = 10% APR)
    private val fundingRatesApr = ConcurrentHashMap<String, Double>()

    fun updateFundingRate(symbol: String, apr: Double) {
        fundingRatesApr[symbol.uppercase()] = apr
    }

    fun getFundingApr(symbol: String): Double? = fundingRatesApr[symbol.uppercase()]

    /**
     * Directional bias score. Side = +1 for LONG, -1 for SHORT.
     * Returns positive value when funding supports the side, negative when
     * funding fights it.
     */
    fun bias(symbol: String, side: Int): Int {
        val apr = fundingRatesApr[symbol.uppercase()] ?: return 0
        return when {
            apr >  0.50 && side >  0 -> -6      // extreme long crowding, reversion risk
            apr >  0.20 && side >  0 -> -3
            apr >  0.50 && side <  0 -> +5
            apr >  0.20 && side <  0 -> +2
            apr < -0.50 && side <  0 -> -6      // extreme short crowding
            apr < -0.20 && side <  0 -> -3
            apr < -0.50 && side >  0 -> +5
            apr < -0.20 && side >  0 -> +2
            else -> 0
        }
    }

    /**
     * Candidate-style score (assumes LONG entry). Markets traders that
     * take either side should call bias() directly with their intended side.
     */
    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val apr = fundingRatesApr[candidate.symbol.uppercase()]
            ?: return ScoreComponent("FundingRateAwarenessAI", 0, "💸 no funding data")
        val v = bias(candidate.symbol, side = +1)
        return ScoreComponent("FundingRateAwarenessAI", v,
            "💸 fundingAPR=${"%.2f".format(apr)} (long bias=$v)")
    }

    /**
     * V5.9.212 — Composite aggression multiplier (0.0–1.0) used by SymbolicContext.
     * Derived from the average funding bias across all tracked symbols.
     * High positive bias → high aggression. Very negative (crowded shorts) → reduced.
     */
    fun getAggression(): Double {
        if (fundingRatesApr.isEmpty()) return 0.8  // Neutral-ish when no data
        val avgBias = fundingRatesApr.keys.map { bias(it, side = +1) }.average()
        // bias() returns -6 to +5 range; map to 0.0–1.0
        return ((avgBias + 6.0) / 11.0).coerceIn(0.0, 1.0)
    }
}
