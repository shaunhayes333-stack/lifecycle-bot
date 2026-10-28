package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8019 — the market cap a decision is filed under must agree with the price.
 *
 * 5.0.8018 live: Earth, ZION and Catmother were bought at the pump.fun curve's floor (about 960k
 * tokens for $3 — the launch price, a $3.1k cap) while the token row carried caps of $47k-$51k.
 * Those caps filed the three decisions in the bot's BEST cell (pump.fun | $10k-$100k | under 15
 * minutes, +44% on 17 labels) and watch-first cleared them on that record; their real cell (under
 * $10k) is the bot's WORST (27 live closes, -17.4%). Every gate that keys on the cap — the cell
 * record, the allocator, the tail hunter, the labels themselves — was reading a number the price
 * contradicted by 15x.
 *
 * The price is the hard fact: a curve print, a vault read or a fill. A pump.fun curve token has
 * exactly 1e9 tokens, so price x 1e9 IS its cap; any other mint's cap is price x its on-chain
 * supply when that supply is known. When the cap on the row disagrees with that by more than
 * [MAX_RATIO] either way, the price-implied cap replaces it (on the row, so every reader agrees)
 * and the event is counted. A price that is itself a cap-derived seed (PUMP_FUN_BC_SYNTHETIC)
 * proves nothing and is never used to "confirm" the cap it came from.
 *
 * V5.0.8418 — superseded "a pump.fun curve token has exactly 1e9 tokens": the supply is the mint account's
 * (MintSupply8418: getAccountInfo / getTokenSupply / DAS, decimals-adjusted). A *pump suffix proves nothing about the
 * supply; with no mint-account read the supply is UNKNOWN (0.0 here), a read is requested, and the row cap stands
 * unconfirmed rather than being "corrected" from a guess.
 */
object TrustedMcap8019 {
    /** V5.0.8418 — no longer used for any cap here (see the class note); kept only for LiquidityTruth8037's curve arithmetic. */
    const val PUMP_SUPPLY_8019 = 1_000_000_000.0
    const val MAX_RATIO_8019 = 3.0
    private val corrected = AtomicLong(0)
    private val agreed = AtomicLong(0)

    /** Pure: the cap to file under, given the row's cap and an independent price x supply; NaN when nothing is known. */
    fun reconcile8019(rowCap: Double, priceUsd: Double, supply: Double): Double {
        val implied = if (priceUsd.isFinite() && priceUsd > 0.0 && supply.isFinite() && supply > 0.0) priceUsd * supply else Double.NaN
        val row = if (rowCap.isFinite() && rowCap > 0.0) rowCap else Double.NaN
        if (implied.isNaN()) return row
        if (row.isNaN()) return implied
        val r = row / implied
        return if (r > MAX_RATIO_8019 || r < 1.0 / MAX_RATIO_8019) implied else row
    }

    /** Pure: is this price source an independent observation (a print, a quote, a fill), not a cap-derived seed? */
    fun independentPrice8019(source: String): Boolean {
        val s = source.uppercase()
        return s.isNotBlank() && !s.contains("SYNTHETIC") && !s.contains("CAP_SEED") && !s.contains("CAP_OVER")
    }

    /**
     * The supply [ts] is capped on: the mint account's verified whole-token supply, else 0.0 (UNKNOWN; a chain read is
     * requested). superseded by V5.0.8418 — was "1e9 for a pump.fun curve mint", a suffix guess.
     */
    fun supplyFor8019(ts: TokenState): Double {
        val verified = try { MintSupply8418.wholeOrRequest8418(ts.mint) } catch (_: Throwable) { 0.0 }
        if (verified > 0.0) {
            try { MintSupply8418.stamp8418(ts) } catch (_: Throwable) {}
            return verified
        }
        return 0.0
    }

    /**
     * The cap every cap-keyed reader uses for [ts]: the row's cap when the price agrees with it, else the
     * price-implied cap, written back to the row. Cheap; safe to call on every decision.
     */
    fun mcap8019(ts: TokenState): Double {
        val row = ts.lastMcap
        if (!independentPrice8019(ts.lastPriceSource)) return row
        val v = reconcile8019(row, ts.lastPrice, supplyFor8019(ts))
        if (v.isNaN()) return row
        if (v != row) {
            if (row.isFinite() && row > 0.0) {
                corrected.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("MCAP_CORRECTED_FROM_PRICE_8019")
                    if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("MCAP_8019", ts.mint)) {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "MCAP_CORRECTED_FROM_PRICE_8019",
                            "mint=${ts.mint.take(10)} sym=${ts.symbol} rowCap=${row.toLong()} impliedCap=${v.toLong()} px=${ts.lastPrice} src=${ts.lastPriceSource} action=file_under_price_implied_cap",
                        )
                    }
                } catch (_: Throwable) {}
            }
            ts.lastMcap = v
        } else agreed.incrementAndGet()
        return v
    }

    fun statusLine(): String = "capAgreed=${agreed.get()} capCorrected=${corrected.get()} rule=price x verified mint-account supply (unknown supply: no correction) replaces a row cap off by >${MAX_RATIO_8019.toInt()}x"
}
