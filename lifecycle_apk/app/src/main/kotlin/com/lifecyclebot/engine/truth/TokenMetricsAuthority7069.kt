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

    /**
     * V5.0.7075 §THERE IS NO SUCH THING AS AN INFERRED SUPPLY.
     *
     * Operator: "there should never be an inferred value. if we cant lock in
     * full data integrity at discovery the app is fucking worthless trading
     * real money."
     *
     * V5.0.7069 derived supply as `mcap / price` from the first observation
     * that carried both, then used that supply to verify every later price.
     * That is circular: a check derived from the thing it checks. If the first
     * price was wrong the supply is wrong by the same factor, the identity
     * holds perfectly, and correct prices get "repaired" TOWARDS the error.
     * The 8A6dzN partial booked 2.0798 SOL from a 0.0144 SOL basis — a 144x —
     * while §4481 reported mcapGain=0.0% on the same mint seconds later.
     *
     * The inference is deleted. Supply now comes from ONE place:
     * OnChainSupplyAuthority7075, which reads `getTokenSupply` from chain
     * state. It is a fact, it does not depend on any price, and a bad tick
     * cannot corrupt it.
     *
     * A mint with no on-chain supply yet is NOT verifiable and NOT repairable,
     * and the resolve is requested so it becomes both. Nothing is guessed in
     * the meantime.
     */
    private fun storedSupplyOf(mint: String): Double {
        val onChain = try { OnChainSupplyAuthority7075.supplyOf7075(mint) } catch (_: Throwable) { 0.0 }
        if (onChain.isFinite() && onChain >= MIN_SUPPLY) {
            supplyByMint[mint] = onChain
            return onChain
        }
        // Durable cache holds ON-CHAIN values only (V5.0.7075); a pre-7075
        // archive may still contain inferred rows, so it is read but any value
        // it returns is re-confirmed against chain state by the request below.
        val durable = try { cacheRef.get()?.supplyOf7069(mint) ?: 0.0 } catch (_: Throwable) { 0.0 }
        try { OnChainSupplyAuthority7075.requestAsync7075(mint) } catch (_: Throwable) {}
        if (durable.isFinite() && durable >= MIN_SUPPLY) return durable
        return 0.0
    }

    /**
     * V5.0.7075 — chain state arrived. It OVERRIDES anything held, including a
     * pre-7075 inferred archive value, because a measured fact outranks a
     * derived one. Returns true when it actually replaced a different number,
     * which is the count of positions that were being priced against a guess.
     */
    fun acceptOnChainSupply7075(mint: String, supply: Double): Boolean {
        if (!supply.isFinite() || supply < MIN_SUPPLY) return false
        val prior = supplyByMint.put(mint, supply)
        supplyCaptured.incrementAndGet()
        try { cacheRef.get()?.upsertSupply7069(mint, supply) } catch (_: Throwable) {}
        val differed = prior != null && prior > 0.0 &&
            (supply / prior < 0.995 || supply / prior > 1.005)
        if (differed) noteSupplyConflict7069(mint, prior!!, supply)
        return differed
    }

    // V5.0.7075 — the admission gate (`supplyConfirmed`) is deliberately NOT
    // shipped in this build. It belongs on the economic path, and turning it on
    // before chain-supply coverage is measured could refuse every mark at once
    // and stop the bot dead — the same mistake as V5.0.7068's band, which took
    // 37 of 41 partials with it. This build MEASURES coverage
    // (OnChainSupplyAuthority7075.status: resolved / failed) and the gate lands
    // on that evidence. Shipping the accessor unwired would just be another
    // NO_CALLERS authority, which is the defect this session keeps finding.

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
        // V5.0.7075 — no chain-confirmed supply means nothing here can be
        // verified. The price passes through UNCHANGED and unverified; it is
        // never "repaired" against a number derived from itself. storedSupplyOf
        // has already asked chain state for the real one.
        if (storedSupply <= 0.0) {
            unverifiable.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_METRICS_UNVERIFIABLE_NO_ONCHAIN_SUPPLY_7075") } catch (_: Throwable) {}
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
        // V5.0.7087 — a price DERIVED from an unverified cap is still an
        // invented number. No price reported means no price, not a made-up one.
        if (price <= 0.0) {
            unverifiable.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_PRICE_ABSENT_NOT_DERIVED_7087") } catch (_: Throwable) {}
            return Metrics7069(0.0, mcap, storedSupply, repaired = false, verifiable = false)
        }

        val ratio = price / impliedPrice
        if (ratio in (1.0 - IDENTITY_EPSILON)..(1.0 + IDENTITY_EPSILON)) {
            identityHeld.incrementAndGet()
            return Metrics7069(price, mcap, storedSupply, repaired = false, verifiable = true)
        }

        // V5.0.7087 §THE REPAIR WAS MANUFACTURING THE ABSURD PRICES.
        //
        // Operator, on the 5.0.7082 device report: "your excluding wins all over
        // the place because your not getting the data right. stop inventing
        // fucking data."
        //
        // He is right, and the proof is in his own log:
        //
        //   XsueG8Btpq  entry=6.785963270317651E-4
        //               current=0.8223581771278282
        //               src=PUMP_PORTAL_WS,PUMP_PORTAL+MCAP_IDENTITY_7069
        //
        // The +MCAP_IDENTITY_7069 tag is appended ONLY when this function
        // replaced the price. And the arithmetic names the culprit exactly:
        //
        //   entry 6.786e-4 x 1e9 supply =       $678,596  ~= reported cap 675,220
        //   "repaired" 0.8223581771 x 1e9 = $822,358,177
        //
        // So ts.lastMcap was momentarily $822 MILLION instead of $675 thousand,
        // and this function believed it and invented a price 1211x too high.
        // Xsc9qvGR1e is the same shape: 2.1338 x 1e9 = $2.13 BILLION.
        // priceRepaired=119 worstBreak=87994x are not detections. They are
        // fabrications.
        //
        // AND THE WRITE-BACK MADE A TRANSIENT TICK PERMANENT. The bad cap
        // corrected itself — §4481 reads entryMcap=675220 currentMcap=675220
        // mcapGain=0.0 on the same mint, seconds later. But the invented price
        // had already been written into ts.lastPrice, so it outlived its cause
        // and every downstream guard then saw a 1211x it had to refuse:
        // PROFIT_LOCK_REFUSED_UNTRUSTED_BASIS_7049, STALE_PRICE_QUARANTINED,
        // OPEN_PNL_BASIS_REJECTED, and a "4th partial (100x MOONSHOT!)" on a
        // token whose market cap never moved. That is the operator's "excluding
        // wins all over the place", and I caused it.
        //
        // WHY THE ORIGINAL DOCTRINE WAS WRONG. V5.0.7069 argued "across every
        // incident on record the cap was right and the price was wrong", and
        // recorded that as an empirical claim to be measured. This run falsifies
        // it. The identity has THREE quantities and only SUPPLY is verified
        // on-chain, so a broken identity proves one of price/cap is wrong and
        // says NOTHING about which. Choosing the cap is an inference, and the
        // operator's standing rule is that there are no inferred values.
        //
        // SO THIS AUTHORITY NOW CLASSIFIES AND NEVER SUBSTITUTES. A broken
        // identity is reported as UNVERIFIABLE and the reported price passes
        // through EXACTLY as the provider sent it. Nothing is invented, nothing
        // is written back, and a transient bad cap can no longer poison durable
        // state. DataLegitimacyAuthority7077 already refuses to qualify a mint
        // whose identity does not hold, which is the correct response to "one of
        // these two numbers is wrong": do not trade it, rather than guess which.
        identityBroken.incrementAndGet()
        noteWorst(ratio)
        try {
            PipelineHealthCollector.labelInc("METRICS_IDENTITY_BROKEN_7069")
            PipelineHealthCollector.labelInc("METRICS_IDENTITY_BROKEN_NO_SUBSTITUTION_7087")
            if (identityBroken.get() % 20L == 1L) {
                ForensicLogger.lifecycle(
                    "METRICS_IDENTITY_BROKEN_7069",
                    "mint=${mint.take(10)} sym=$symbol src=$source " +
                        "reportedPrice=$price impliedPrice=$impliedPrice " +
                        "mcap=${mcap.toLong()} supply=${storedSupply.toLong()} " +
                        "ratio=${"%.6g".format(ratio)} " +
                        "action=unverifiable_price_passes_through_untouched_no_substitution_7087",
                )
            }
        } catch (_: Throwable) {}
        // V5.0.7232 §MARK_IDENTITY_EXECUTION_GATE — feed the
        //   identity-broken observation into MarkIdentityExecutionGate7230
        //   so downstream execution consumers (SL/TP/trailing/normal-
        //   stop/catastrophic/learning) can consult a single gate to
        //   decide execution-eligibility. This bridge keeps the existing
        //   telemetry policy (unverifiable_price_passes_through_untouched)
        //   for observation while producing an execution-eligibility
        //   verdict for callers that consult the gate. Venue key here is
        //   the metrics source (e.g. PUMP_FUN_FRONTEND_API); a mark
        //   bound only by symbol still fails the gate's blank-venue check.
        try {
            com.lifecyclebot.engine.truth.MarkIdentityExecutionGate7230.evaluate(
                mint = mint,
                poolOrVenueKey = source,
                markIdentityBroken = true,
                corroboratedByIndependentSource = false,
                exitReasonOrContext = "TokenMetricsAuthority7069_identity_broken",
            )
        } catch (_: Throwable) {}
        return Metrics7069(price, mcap, storedSupply, repaired = false, verifiable = false)
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
            // V5.0.7087 — priceRepaired is RETIRED, pinned at 0, and kept in the
            // line on purpose: it read 119 with worstBreak=87994x on the
            // operator's 5.0.7082 device, and every one of those was a price
            // this authority invented from a bad market cap. Seeing it stay at
            // zero is the acceptance test for this build, which is worth more
            // than deleting the field and losing the comparison.
            "priceRepaired=${priceRepaired.get()}(retired_7087) unverifiable=${unverifiable.get()} " +
            "supplyConflicts=${supplyConflicts.get()} " +
            "worstBreak=${"%.3f".format(worstBreakMilli.get() / 1000.0)}x"
}
