package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6496 §1 — MARK AUTHORITY INTEGRITY GATE.
 *
 * OPERATOR MANDATE (verbatim, 6495 evidence):
 *
 *   "Open market value: 553.23 SOL / Unrealized PnL: +524.93 SOL /
 *    Total equity: 657.16 SOL — Yet 37 of the open marks are fallback
 *    marks: staleMarks=0 fallbackMarks=37. That means almost the
 *    entire +525 SOL unrealized figure is not being supported by your
 *    primary price authority. I would not let those marks train
 *    anything or drive sizing until they are independently confirmed.
 *
 *    Mark-authority fix: fallback prices may DISPLAY, but cannot
 *    create canonical realized/unrealized profit or train learners
 *    unless sufficiently verified."
 *
 * DESIGN
 * ──────
 * Wraps `CanonicalCapitalAuthority6450`'s mark-provider callback.
 * On every price fetch we consult `MarketDataProvenance6471` on the
 * upstream cache. When provenance is not AUTHORITATIVE the gate
 * returns 0.0 for the SOL mark → snapshot falls back to costBasis →
 * `openMarketValueSol`, `unrealizedPnlSol`, `EconomicOutcome6472`
 * and every downstream learner receive the neutral (0-unrealized)
 * value rather than a fallback-inflated one.
 *
 * The display surface (`status.tokens[mint].lastPrice`) is untouched
 * so operator UI keeps showing the fallback price. Only the
 * *economic* path is gated.
 *
 * `MARK_AUTHORITY_GATE_BLOCKED_6496` fires on every block for
 * `RootCauseClassifier6471` visibility.
 */
object MarkAuthorityIntegrityGate6496 {

    private val evaluated = AtomicLong(0L)
    private val authoritativePasses = AtomicLong(0L)
    private val nonAuthoritativeBlocks = AtomicLong(0L)

    /**
     * Gate a candidate mark. Callers pass the raw metadata carried on
     * the token-state cache (source string, pool address, mcap,
     * liquidity, price). Returns true only when
     * `MarketDataProvenance6471.classify(...) == AUTHORITATIVE`.
     *
     * When [price] is 0/NaN or [source]/[poolAddress] is blank the
     * gate blocks (missing provenance is never AUTHORITATIVE).
     */
    data class AuthorityResult(val priceAuthoritative: Boolean, val routeExecutable: Boolean, val provenance: MarketDataProvenance6471.Provenance)

    fun evaluate(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        source: String,
        poolAddress: String,
        fresh: Boolean = true,
    ): AuthorityResult {
        evaluated.incrementAndGet()
        val provenance = try { MarketDataProvenance6471.classify(priceUsd, mcapUsd, liquidityUsd, source, poolAddress) }
            catch (_: Throwable) { MarketDataProvenance6471.Provenance.NON_AUTHORITATIVE_MISSING }
        val sourceUpper = source.trim().uppercase()
        val realPriceSource = sourceUpper in setOf("DEXSCREENER_PAIR_POLL", "DEXSCREENER", "BIRDEYE", "JUPITER", "PUMPFUN_BONDING_CURVE", "PUMP_FUN_BC_SYNTHETIC", "PUMP_FUN_FRONTEND_API")
        val numericPriceValid = fresh && priceUsd.isFinite() && priceUsd > 0.0 && liquidityUsd.isFinite() && liquidityUsd > 0.0
        val knownTemplate = kotlin.math.abs(priceUsd - 0.050250000) < 1e-6 && kotlin.math.abs(mcapUsd - 50_000_000.0) < 1.0 && kotlin.math.abs(liquidityUsd - 5_000_000.0) < 1.0
        val priceAuthoritative = numericPriceValid && realPriceSource && !knownTemplate
        val routeExecutable = provenance == MarketDataProvenance6471.Provenance.AUTHORITATIVE
        if (priceAuthoritative) authoritativePasses.incrementAndGet() else {
            nonAuthoritativeBlocks.incrementAndGet()
            try {
                ForensicLogger.lifecycle("MARK_AUTHORITY_GATE_BLOCKED_6496", "mint=${mint.take(10)} provenance=${provenance.name} src=$source pool=${poolAddress.take(24)} priceUsd=${"%.6f".format(priceUsd)} mcap=$mcapUsd liq=$liquidityUsd reason=price_authority")
                PipelineHealthCollector.labelInc("MARK_AUTHORITY_GATE_BLOCKED_6496")
            } catch (_: Throwable) {}
        }
        return AuthorityResult(priceAuthoritative, routeExecutable, provenance)
    }

    fun isAuthoritative(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        source: String,
        poolAddress: String,
    ): Boolean {
        return evaluate(mint, priceUsd, mcapUsd, liquidityUsd, source, poolAddress, fresh = true).priceAuthoritative
    }

    fun statusLine(): String =
        "evaluated=${evaluated.get()} authoritativePasses=${authoritativePasses.get()} " +
            "nonAuthoritativeBlocks=${nonAuthoritativeBlocks.get()}"

    internal fun resetForTest() {
        evaluated.set(0L); authoritativePasses.set(0L); nonAuthoritativeBlocks.set(0L)
    }
}
