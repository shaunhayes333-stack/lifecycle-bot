package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenMetaCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.7069 §STORED_ON_ARRIVAL, UPDATED IN REAL TIME, NEVER IMAGINED.
 *
 * Operator, after the CARDSc ladder: "all token metrics should be stored on
 * arrival with the metrics updating in real time. there should be no imagined
 * gains and bullshit profit. the entire system is built on real data and
 * forensic accounting."
 *
 * WHY EVERY PRICE FIX BEFORE THIS ONE WAS A PATCH
 * ===============================================
 * V5.0.6895 put a 10x band on cross-source marks. V5.0.7017 reconciled refused
 * ticks through market cap. V5.0.7059 made that reconciliation universal with a
 * 3x band. V5.0.7068 tightened the band to 1.25. Four builds, each one arguing
 * about HOW FAR WRONG a price may be before it is rejected.
 *
 * That whole argument is a symptom. A price is not a matter of degree — it is
 * either the number of dollars one token is worth or it is not, and the app has
 * a second, independent report of exactly that quantity sitting beside it in
 * every provider payload: the market cap. The reason those two could never be
 * made to check each other is that the quantity linking them was never stored.
 *
 *     marketCap = price x supply
 *
 * Supply is the link. It is fixed for an SPL mint, it is present implicitly in
 * the first observation that carries both a price and a cap, and it was thrown
 * away every single time. Without it, price and cap are two unrelated numbers
 * and a 2.47x move against a DEAD FLAT $674,010 cap is perfectly representable
 * — which is exactly what the operator's CARDSc rows are.
 *
 * WITH IT, THE SAME EVENT IS ARITHMETICALLY IMPOSSIBLE. Supply captured at
 * arrival, cap reported flat, therefore the price is flat. Not "within
 * tolerance of flat". Flat. There is no band here and there does not need to be
 * one, because this is an identity and not a judgement.
 *
 * WHICH SIDE WINS WHEN THEY DISAGREE, and why it is not a coin toss:
 * market cap has survived every pricing defect this app has had. V5.0.7016's
 * pump.fun bug broke the derived price by 10^decimals while `usd_market_cap`
 * stayed correct. V5.0.6895's cross-source jumps move the price and leave the
 * cap alone, because supply cancels out of a cap. V5.0.7057's USD/SOL crossing
 * is a price-side error. Across every incident on record the cap was right and
 * the price was wrong, so the price is the one recomputed. That is an empirical
 * claim, it is recorded here as one, and METRICS_IDENTITY_BROKEN_7069 measures
 * whether it stays true.
 *
 * RUNNERS ARE UNTOUCHED, and this is stronger than the band versions were: a
 * genuine 1000x raises the cap 1000x, so mcap/supply returns a 1000x price and
 * the runner is reported in full. There is no magnitude at which this object
 * caps anything. It cannot — it has no threshold to cap at.
 *
 * WHAT IT DOES NOT DO. It does not invent a price. When the cap is absent the
 * observation passes through untouched and is counted as unverifiable, because
 * a missing second opinion is not evidence of a wrong first one. When supply is
 * not yet known it is captured from this observation and the price stands.
 * Nothing here manufactures a number that was not measured.
 */
object TokenMetricsAuthority7069 {

    /**
     * Relative disagreement between the reported price and cap/supply above
     * which the identity is treated as broken.
     *
     * This is NOT a tolerance band in the sense the previous four builds used
     * one. Those decided how wrong a price could be and still be believed.
     * This decides only when floating-point and provider rounding stop
     * explaining the difference: price, cap and supply come from one payload
     * describing one instant, so they agree to several decimal places or
     * something is wrong. 0.5% is rounding; anything above it is a fault.
     */
    private const val IDENTITY_EPSILON = 0.005

    /** A supply below this is not a token supply; ignore rather than capture. */
    private const val MIN_SUPPLY = 1.0

    /**
     * In-memory supply, authoritative for the running process.
     *
     * Deliberately NOT dependent on the durable cache being installed. Five
     * authorities this session were built correctly and never ran because they
     * waited on a wiring step that did not happen — 7032's scan, 7055's candle
     * binner, the five NO_CALLERS_7035 guards. This one works the moment it is
     * called and gets DURABILITY as an upgrade, not as a precondition.
     */
    private val supplyByMint = ConcurrentHashMap<String, Double>()

    /**
     * Durable store, installed once from a context-bearing caller. When absent
     * supply still holds for the session; when present it survives restart.
     */
    private val cacheRef = AtomicReference<TokenMetaCache?>(null)

    fun installCache7069(cache: TokenMetaCache) {
        cacheRef.set(cache)
        try {
            PipelineHealthCollector.labelInc("TOKEN_METRICS_CACHE_INSTALLED_7069")
        } catch (_: Throwable) {}
    }

    private fun storedSupplyOf(mint: String): Double {
        supplyByMint[mint]?.let { if (it.isFinite() && it >= MIN_SUPPLY) return it }
        val durable = try { cacheRef.get()?.supplyOf7069(mint) ?: 0.0 } catch (_: Throwable) { 0.0 }
        if (durable.isFinite() && durable >= MIN_SUPPLY) {
            supplyByMint[mint] = durable
            return durable
        }
        return 0.0
    }

    private fun captureSupply(mint: String, supply: Double) {
        val prior = supplyByMint.putIfAbsent(mint, supply)
        if (prior != null) {
            val ratio = if (prior > 0.0) supply / prior else -1.0
            if (ratio < 0.995 || ratio > 1.005) noteSupplyConflict7069(mint, prior, supply)
            return
        }
        supplyCaptured.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TOKEN_SUPPLY_CAPTURED_ON_ARRIVAL_7069") } catch (_: Throwable) {}
        try { cacheRef.get()?.upsertSupply7069(mint, supply) } catch (_: Throwable) {}
    }

    private val observed = AtomicLong(0L)
    private val supplyCaptured = AtomicLong(0L)
    private val identityHeld = AtomicLong(0L)
    private val identityBroken = AtomicLong(0L)
    private val priceRepaired = AtomicLong(0L)
    private val unverifiable = AtomicLong(0L)
    private val supplyConflicts = AtomicLong(0L)
    private val worstBreakMilli = AtomicLong(0L)

    data class Metrics7069(
        val priceUsd: Double,
        val mcapUsd: Double,
        val supplyTokens: Double,
        val repaired: Boolean,
        val verifiable: Boolean,
    )

    /**
     * Record an observation and return the authoritative price for it.
     *
     * [rawPriceUsd] and [rawMcapUsd] are as reported. Either may be 0 when the
     * provider did not supply it. Returns the price every consumer should use;
     * when the identity is broken that is cap/supply, otherwise it is the
     * reported price unchanged.
     */
    fun observe(
        mint: String,
        symbol: String,
        rawPriceUsd: Double,
        rawMcapUsd: Double,
        source: String,
    ): Metrics7069 {
        observed.incrementAndGet()
        val price = if (rawPriceUsd.isFinite() && rawPriceUsd > 0.0) rawPriceUsd else 0.0
        val mcap = if (rawMcapUsd.isFinite() && rawMcapUsd > 0.0) rawMcapUsd else 0.0

        val storedSupply = storedSupplyOf(mint)

        // ARRIVAL — no supply on file yet. Capture it from this observation and
        // let the price stand; there is nothing to check it against yet, and
        // inventing a check would be inventing data.
        if (storedSupply <= 0.0) {
            if (price > 0.0 && mcap > 0.0) {
                val supply = mcap / price
                if (supply.isFinite() && supply >= MIN_SUPPLY) {
                    captureSupply(mint, supply)
                    return Metrics7069(price, mcap, supply, repaired = false, verifiable = false)
                }
            }
            unverifiable.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_METRICS_UNVERIFIABLE_NO_SUPPLY_7069") } catch (_: Throwable) {}
            return Metrics7069(price, mcap, 0.0, repaired = false, verifiable = false)
        }

        // No cap on this observation — nothing to verify against. The price
        // passes through. A missing second opinion is not evidence against the
        // first one.
        if (mcap <= 0.0) {
            unverifiable.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_METRICS_UNVERIFIABLE_NO_MCAP_7069") } catch (_: Throwable) {}
            return Metrics7069(price, 0.0, storedSupply, repaired = false, verifiable = false)
        }

        val impliedPrice = mcap / storedSupply
        if (!impliedPrice.isFinite() || impliedPrice <= 0.0) {
            unverifiable.incrementAndGet()
            return Metrics7069(price, mcap, storedSupply, repaired = false, verifiable = false)
        }

        // Cap present, supply known, but the feed gave no price. The identity
        // SUPPLIES one — this is a measurement, not a guess: the cap is real and
        // the supply is on file.
        if (price <= 0.0) {
            priceRepaired.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_PRICE_DERIVED_FROM_MCAP_7069") } catch (_: Throwable) {}
            return Metrics7069(impliedPrice, mcap, storedSupply, repaired = true, verifiable = true)
        }

        val ratio = price / impliedPrice
        if (ratio in (1.0 - IDENTITY_EPSILON)..(1.0 + IDENTITY_EPSILON)) {
            identityHeld.incrementAndGet()
            return Metrics7069(price, mcap, storedSupply, repaired = false, verifiable = true)
        }

        identityBroken.incrementAndGet()
        priceRepaired.incrementAndGet()
        noteWorst(ratio)
        try {
            PipelineHealthCollector.labelInc("METRICS_IDENTITY_BROKEN_7069")
            if (identityBroken.get() % 20L == 1L) {
                ForensicLogger.lifecycle(
                    "METRICS_IDENTITY_BROKEN_7069",
                    "mint=${mint.take(10)} sym=$symbol src=$source " +
                        "reportedPrice=$price impliedPrice=$impliedPrice " +
                        "mcap=${mcap.toLong()} supply=${storedSupply.toLong()} " +
                        "ratio=${"%.6g".format(ratio)} " +
                        "action=market_cap_and_stored_supply_win_price_recomputed",
                )
            }
        } catch (_: Throwable) {}
        return Metrics7069(impliedPrice, mcap, storedSupply, repaired = true, verifiable = true)
    }

    /**
     * Report a supply that a later observation implies, for the integrity
     * counter only. Supply is immutable; this never overwrites.
     */
    fun noteSupplyConflict7069(mint: String, archived: Double, offered: Double) {
        supplyConflicts.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("TOKEN_SUPPLY_CONFLICT_7069")
            if (supplyConflicts.get() % 20L == 1L) {
                ForensicLogger.lifecycle(
                    "TOKEN_SUPPLY_CONFLICT_7069",
                    "mint=${mint.take(10)} archived=${archived.toLong()} offered=${offered.toLong()} " +
                        "ratio=${"%.6g".format(if (archived > 0.0) offered / archived else -1.0)} " +
                        "action=keep_archived_supply_is_immutable",
                )
            }
        } catch (_: Throwable) {}
    }

    private fun noteWorst(ratio: Double) {
        if (!ratio.isFinite() || ratio <= 0.0) return
        val magnitude = if (ratio >= 1.0) ratio else 1.0 / ratio
        if (!magnitude.isFinite()) return
        val milli = (magnitude * 1000.0).toLong()
        while (true) {
            val prev = worstBreakMilli.get()
            if (milli <= prev || worstBreakMilli.compareAndSet(prev, milli)) break
        }
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "observed=${observed.get()} supplyCaptured=${supplyCaptured.get()} " +
            "identityHeld=${identityHeld.get()} identityBroken=${identityBroken.get()} " +
            "priceRepaired=${priceRepaired.get()} unverifiable=${unverifiable.get()} " +
            "supplyConflicts=${supplyConflicts.get()} " +
            "worstBreak=${"%.3f".format(worstBreakMilli.get() / 1000.0)}x"
}
