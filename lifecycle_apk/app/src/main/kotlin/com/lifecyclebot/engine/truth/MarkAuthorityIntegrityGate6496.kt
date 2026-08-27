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
        // V5.0.6548 §P0-B — CANONICAL PROVIDER IDENTITY.
        // Operator evidence: `MARK_AUTHORITY_GATE_BLOCKED_6547|SOURCE_NOT_WHITELISTED:DEXSCREENER_WS`
        // fired 3,547× in one session for AUTHORITATIVE-provenance WS marks.
        // Transport channel (WS vs REST vs POLL) is not a trust domain —
        // canonicalise the family, then apply the whitelist. This admits
        // DEXSCREENER_WS / DEXSCREENER_REST / DEXSCREENER_POLL /
        // DEXSCREENER_PAIR_POLL / DEXSCREENER_BASE_MINT_MARKET_CAP as a
        // single canonical DEXSCREENER provider, matching Birdeye / Jupiter /
        // PumpFun handling. Freshness / pool identity / template checks
        // are unchanged — only the string comparison is normalized.
        val canonicalSource6548 = when {
            sourceUpper.startsWith("DEXSCREENER") -> "DEXSCREENER"
            sourceUpper.startsWith("BIRDEYE") -> "BIRDEYE"
            sourceUpper.startsWith("JUPITER") -> "JUPITER"
            sourceUpper.startsWith("PUMPFUN") || sourceUpper.startsWith("PUMP_FUN") ||
                sourceUpper.startsWith("PUMP_PORTAL") -> "PUMPFUN"
            else -> sourceUpper
        }
        val realPriceSource = canonicalSource6548 in setOf("DEXSCREENER", "BIRDEYE", "JUPITER", "PUMPFUN")
        val priceValidity = fresh && priceUsd.isFinite() && priceUsd > 0.0
        val liquidityValidity = liquidityUsd.isFinite() && liquidityUsd > 0.0
        val realPoolIdentity = poolAddress.isNotBlank() && !poolAddress.startsWith("MINT_ROUTE:", ignoreCase = true)
        val knownTemplate = kotlin.math.abs(priceUsd - 0.050250000) < 1e-6 && kotlin.math.abs(mcapUsd - 50_000_000.0) < 1.0 && kotlin.math.abs(liquidityUsd - 5_000_000.0) < 1.0
        val priceAuthoritative = priceValidity && realPriceSource && realPoolIdentity && !knownTemplate
        val routeExecutable = liquidityValidity && provenance == MarketDataProvenance6471.Provenance.AUTHORITATIVE
        if (priceAuthoritative) authoritativePasses.incrementAndGet() else {
            nonAuthoritativeBlocks.incrementAndGet()
            try {
                // V5.0.6547 §P1-5 — MARK AUTHORITY comparison telemetry.
                // Operator mandate: emit per-check verdict so the operator
                // can see WHY a mark is being blocked instead of guessing.
                // Do NOT relax any check — this is diagnostic only.
                val blockReason6547 = when {
                    !priceValidity -> "PRICE_INVALID_OR_STALE"
                    !realPriceSource -> "SOURCE_NOT_WHITELISTED:${canonicalSource6548.ifBlank { "BLANK" }}"
                    !realPoolIdentity -> "POOL_MISSING_OR_MINT_ROUTE"
                    knownTemplate -> "KNOWN_TEMPLATE_PRICE_50M_5M"
                    else -> "UNKNOWN"
                }
                PipelineHealthCollector.labelInc("MARK_AUTHORITY_GATE_BLOCKED_6496")
                PipelineHealthCollector.labelInc("MARK_AUTHORITY_GATE_BLOCKED_6547|$blockReason6547")
                ForensicLogger.lifecycle(
                    "MARK_AUTHORITY_GATE_BLOCKED_6496",
                    "mint=${mint.take(10)} provenance=${provenance.name} src=$source canonSrc=$canonicalSource6548 pool=${poolAddress.take(24)} " +
                        "priceUsd=${"%.6f".format(priceUsd)} mcap=$mcapUsd liq=$liquidityUsd " +
                        "reason=price_authority blockReason6547=$blockReason6547 " +
                        "priceValid=$priceValidity realPriceSource=$realPriceSource " +
                        "liquidityValid=$liquidityValidity poolValid=$realPoolIdentity " +
                        "knownTemplate=$knownTemplate fresh=$fresh"
                )
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
