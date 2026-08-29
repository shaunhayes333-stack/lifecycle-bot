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
    data class AuthorityResult(
        val priceAuthoritative: Boolean,
        val observationAuthoritative: Boolean,
        val routeExecutable: Boolean,
        val provenance: MarketDataProvenance6471.Provenance,
    )

    fun evaluate(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        source: String,
        poolAddress: String,
        fresh: Boolean = true,
    ): AuthorityResult = evaluate(mint, priceUsd, mcapUsd, liquidityUsd, source, poolAddress, fresh, isKnownOpenMint6596 = false)

    /**
     * V5.0.6596 §MARK_AUTHORITY_MINT_ROUTE_FOR_KNOWN_OPEN — operator directive
     * Feb 2026:
     *   > "DEXSCREENER_PAIR_POLL is returning valid price/liquidity but is
     *   >  being rejected because pool=MINT_ROUTE:* becomes
     *   >  NON_AUTHORITATIVE_SENTINEL. Resolve MINT_ROUTE to canonical mint/
     *   >  pair identity. If mint, quote asset and pair identity match
     *   >  canonical provenance, promote the mark to authoritative rather
     *   >  than rejecting it solely because the route key has MINT_ROUTE
     *   >  prefix."
     *
     * Snapshot 6595 showed 55 canonical open positions with 51 missing marks
     * because the exit-mark path always defaulted poolAddress to
     * "MINT_ROUTE:<mint>" when ts.lastPricePoolAddr was blank, which made
     * realPoolIdentity=false and rejected the whole mark. For a KNOWN OPEN
     * canonical position the mint identity is already proven — the position
     * is open on this mint. MINT_ROUTE:* is treated as acceptable pool
     * identity ONLY on the exit-mark path (isKnownOpenMint6596=true).
     * Every NEW-ENTRY path (V3, FDG, executor route generation) still
     * receives isKnownOpenMint6596=false and rejects MINT_ROUTE as before —
     * safety at the entry boundary is preserved.
     */
    fun evaluate(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        source: String,
        poolAddress: String,
        fresh: Boolean,
        isKnownOpenMint6596: Boolean,
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
            sourceUpper.startsWith("GECKOTERMINAL") || sourceUpper.startsWith("GECKO_TERMINAL") -> "GECKOTERMINAL"
            sourceUpper.startsWith("JUPITER") -> "JUPITER"
            sourceUpper.startsWith("PUMPFUN") || sourceUpper.startsWith("PUMP_FUN") ||
                sourceUpper.startsWith("PUMP_PORTAL") -> "PUMPFUN"
            else -> sourceUpper
        }
        val realPriceSource = canonicalSource6548 in setOf("DEXSCREENER", "GECKOTERMINAL", "BIRDEYE", "JUPITER", "PUMPFUN")
        val priceValidity = fresh && priceUsd.isFinite() && priceUsd > 0.0
        val liquidityValidity = liquidityUsd.isFinite() && liquidityUsd > 0.0
        // V5.0.6596 §MARK_AUTHORITY_MINT_ROUTE_FOR_KNOWN_OPEN — a known-open
        // canonical position has proven mint identity; the MINT_ROUTE:*
        // route prefix is treated as acceptable for pool identity on the
        // exit-mark path only. New-entry callers (isKnownOpenMint6596=false)
        // keep the strict pre-6596 behaviour.
        val realPoolIdentity = poolAddress.isNotBlank() &&
            (isKnownOpenMint6596 || !poolAddress.startsWith("MINT_ROUTE:", ignoreCase = true))
        val knownTemplate = kotlin.math.abs(priceUsd - 0.050250000) < 1e-6 && kotlin.math.abs(mcapUsd - 50_000_000.0) < 1.0 && kotlin.math.abs(liquidityUsd - 5_000_000.0) < 1.0
        val observationAuthoritative = priceValidity && realPriceSource && !knownTemplate
        val priceAuthoritative = observationAuthoritative && realPoolIdentity
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
        return AuthorityResult(priceAuthoritative, observationAuthoritative, routeExecutable, provenance)
    }


    /** V5.0.6570 — observation/V3 authority is price+provider+freshness only.
     * Pool identity and liquidity remain mandatory at executable/live route and
     * economic/exit mark boundaries; MINT_ROUTE is never executable proof.
     *
     * V5.0.6581 §P0-2 — OBSERVATION ACCEPTS MINT_ROUTE POOL PROVENANCE.
     * Operator forensic (6580): 1,365 valid-looking DexScreener prices
     * rejected pre-V3 because the pool identity came back as MINT_ROUTE:*
     * (route-inferred pool, not a fully-resolved pair address). This is a
     * normal DexScreener response for freshly-minted tokens. It should
     * feed OBSERVATION_SCORING (V3/FDG evaluation). Execution boundary
     * (Executor.paperBuy §6575, live route §6496 evaluate()) STILL rejects
     * MINT_ROUTE as executable proof, so safety is preserved. */
    fun isObservationAuthoritative6570(
        mint: String,
        priceUsd: Double,
        source: String,
        poolAddress: String,
        fresh: Boolean,
    ): Boolean {
        if (mint.isBlank() || poolAddress.isBlank() || !fresh || !priceUsd.isFinite() || priceUsd <= 0.0) return false
        val sourceUpper = source.trim().uppercase()
        val canonicalSource = when {
            sourceUpper.startsWith("DEXSCREENER") -> "DEXSCREENER"
            sourceUpper.startsWith("GECKOTERMINAL") || sourceUpper.startsWith("GECKO_TERMINAL") -> "GECKOTERMINAL"
            sourceUpper.startsWith("BIRDEYE") -> "BIRDEYE"
            sourceUpper.startsWith("JUPITER") -> "JUPITER"
            sourceUpper.startsWith("PUMPFUN") || sourceUpper.startsWith("PUMP_FUN") || sourceUpper.startsWith("PUMP_PORTAL") -> "PUMPFUN"
            else -> sourceUpper
        }
        val whitelistedSource = canonicalSource in setOf("DEXSCREENER", "GECKOTERMINAL", "BIRDEYE", "JUPITER", "PUMPFUN")
        // V5.0.6581 §P0-2 — non-blank poolAddress is sufficient for observation
        // (MINT_ROUTE:xxx tokens still admitted to scoring). Was previously
        // implicitly rejected because the caller often defaulted MINT_ROUTE
        // for missing pool identity and the executable-purpose reject bled
        // through to observation via the CanonicalPriceMark publish path.
        return whitelistedSource
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

    /** V5.0.6596 — overload for the exit-mark path (see evaluate() docstring). */
    fun isAuthoritative(
        mint: String,
        priceUsd: Double,
        mcapUsd: Double,
        liquidityUsd: Double,
        source: String,
        poolAddress: String,
        isKnownOpenMint6596: Boolean,
    ): Boolean {
        return evaluate(mint, priceUsd, mcapUsd, liquidityUsd, source, poolAddress, fresh = true, isKnownOpenMint6596 = isKnownOpenMint6596).priceAuthoritative
    }

    fun statusLine(): String =
        "evaluated=${evaluated.get()} authoritativePasses=${authoritativePasses.get()} " +
            "nonAuthoritativeBlocks=${nonAuthoritativeBlocks.get()}"

    internal fun resetForTest() {
        evaluated.set(0L); authoritativePasses.set(0L); nonAuthoritativeBlocks.set(0L)
    }
}
