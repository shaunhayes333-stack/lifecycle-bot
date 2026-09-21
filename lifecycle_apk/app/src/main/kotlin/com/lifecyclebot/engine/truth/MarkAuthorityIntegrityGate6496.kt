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
    private val markCoalesced6615 = AtomicLong(0L)
    // V5.0.7198 — per-reason block tally. See the note at the increment site.
    private val blockReasons7198 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private data class MarkState6615(
        val lastMarkAttemptAt: Long,
        val lastGoodMarkAt: Long,
        val lastGoodPrice: Double,
        val lastObservedPriceVersion: Long,
        val nextMarkEligibleAt: Long,
        val markState: String,
        val fingerprint: String,
        val result: AuthorityResult,
    )
    private val markStates6615 = java.util.concurrent.ConcurrentHashMap<String, MarkState6615>()

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
        val now6615 = System.currentTimeMillis()
        val fingerprint6615 = listOf(
            priceUsd.toBits(), mcapUsd.toBits(), liquidityUsd.toBits(), source.trim().uppercase(),
            poolAddress.trim(), fresh, isKnownOpenMint6596,
        ).joinToString("|")
        val prior6615 = markStates6615[mint]
        if (prior6615 != null && prior6615.fingerprint == fingerprint6615 && now6615 < prior6615.nextMarkEligibleAt) {
            markCoalesced6615.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PAPER_MARK_UNCHANGED_COALESCED_6615") } catch (_: Throwable) {}
            return prior6615.result
        }
        evaluated.incrementAndGet()
        val provenance = try { MarketDataProvenance6471.classify(priceUsd, mcapUsd, liquidityUsd, source, poolAddress, identity = mint) }
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
        // V5.0.7004 §THE_SECOND_ALLOW_LIST.
        //
        // Operator 5.0.7003 snapshot:
        //
        //   MARK_AUTHORITY_GATE_BLOCKED_6496: 47
        //   mint=2zMMhcVQEX provenance=AUTHORITATIVE src=KEYLESS_BATCH_6996
        //   liq=3431008 priceUsd=0.007698 reason=price_authority
        //   blockReason6547=SOURCE_NOT_WHITELISTED
        //
        // Provenance AUTHORITATIVE, real liquidity, real price — blocked purely
        // because the source STRING was not in a hard-coded set.
        //
        // This is mine. Until V5.0.6999 the open-position loop stamped every
        // mark "DEXSCREENER_WS" regardless of which provider actually answered,
        // so DefiLlama's and Jupiter's rescues sailed through this gate wearing
        // DexScreener's name. 6999 started recording the truth, and the truth
        // was not on the list. V5.0.7001 caught the same collision in the
        // provenance classifier and I fixed that one without checking whether a
        // second allow-list existed. It did.
        //
        // The lesson worth keeping: making a label honest is only half a
        // change. Every consumer that pattern-matches that label has to learn
        // the new vocabulary in the same commit, or the honest label is just a
        // new way to fail.
        //
        // KEYLESS_ is a transport/acquisition prefix, not a trust domain —
        // exactly the argument 6548 made for stripping WS/REST/POLL. Strip it
        // and canonicalise whatever provider is underneath.
        val deKeylessed7004 = sourceUpper.removePrefix("KEYLESS_")
        val canonicalSource7004 = when {
            // The 6996 batch rescue is DefiLlama-then-Jupiter; BotService now
            // labels which one answered, but older rows carry the blob.
            deKeylessed7004.startsWith("BATCH_6996") -> "DEFILLAMA"
            else -> deKeylessed7004
        }
        val canonicalSource6548 = when {
            canonicalSource7004.startsWith("DEFILLAMA") || canonicalSource7004.startsWith("LLAMA") -> "DEFILLAMA"
            canonicalSource7004.startsWith("RAYDIUM") -> "RAYDIUM"
            canonicalSource7004.startsWith("DEXSCREENER") -> "DEXSCREENER"
            canonicalSource7004.startsWith("BIRDEYE") -> "BIRDEYE"
            canonicalSource7004.startsWith("GECKOTERMINAL") || canonicalSource7004.startsWith("GECKO_TERMINAL") -> "GECKOTERMINAL"
            canonicalSource7004.startsWith("JUPITER") -> "JUPITER"
            canonicalSource7004.startsWith("PUMPFUN") || canonicalSource7004.startsWith("PUMP_FUN") ||
                canonicalSource7004.startsWith("PUMP_PORTAL") -> "PUMPFUN"
            else -> canonicalSource7004
        }
        // V5.0.7004 — DefiLlama and Raydium added. Both are first-class price
        // authorities this app already calls and already trusts elsewhere:
        // DefiLlama ran at sr=100% in the same snapshot that blocked its marks,
        // and Raydium is a primary Solana AMM. Neither is a weaker observation
        // than the five already here; they were simply never written down.
        val knownProvider7148 = canonicalSource6548 in setOf(
            "DEXSCREENER", "GECKOTERMINAL", "BIRDEYE", "JUPITER", "PUMPFUN", "DEFILLAMA", "RAYDIUM",
        )
        // V5.0.7148 §THE_THIRD_TIME_THIS_LIST_HAS_BLOCKED_A_REAL_MARK.
        //
        // CI runtime smoke, 5.0.7145:
        //
        //   MARK_AUTHORITY_GATE_BLOCKED_6496 provenance=AUTHORITATIVE
        //   src=FANOUT_CORROBORATED_7088_x2 priceUsd=0.000336 mcap=293254
        //   liq=29325 blockReason6547=SOURCE_NOT_WHITELISTED
        //   priceValid=true liquidityValid=true poolValid=true fresh=true
        //
        // Every substantive check passed. The mark was discarded because a
        // string was missing from a set — and the string in question,
        // FANOUT_CORROBORATED, denotes agreement between two independent
        // providers, which is STRONGER evidence than any single name already
        // on the list.
        //
        // 6548 fixed this for transport suffixes. 7004 fixed it for the
        // KEYLESS_ prefix and DefiLlama, and wrote the lesson down: "making a
        // label honest is only half a change. Every consumer that
        // pattern-matches that label has to learn the new vocabulary in the
        // same commit, or the honest label is just a new way to fail." That
        // lesson was correct and it does not scale — a fourth label will be
        // coined and this list will silently reject it too, because the
        // failure is the SHAPE. An allow-list of provider names answers "have
        // I seen this word before", when the question Executor's route-lock
        // actually poses is "is this a market observation, or our own
        // accounting handed back to us". (V5.0.7166 applied this same
        // reshaping there: Executor.priceBasisFamily7166.)
        //
        // So ask that question. The symbolic bases are a small, stable,
        // enumerable family — cost basis, rehydrated basis, restored basis,
        // synthetic cost/qty, blank, unknown. Everything else is admitted
        // ONLY when MarketDataProvenance6471, which inspects the actual
        // numbers rather than the label, independently rates the observation
        // AUTHORITATIVE. A new honest provider works the day it is named; a
        // cost-basis fake still cannot pass, because it fails both tests.
        val symbolicBasis7148 = canonicalSource6548.isBlank() ||
            canonicalSource6548 == "UNKNOWN" ||
            canonicalSource6548.contains("COST_BASIS") ||
            canonicalSource6548.contains("BASIS_UNKNOWN") ||
            canonicalSource6548.contains("SYNTH_COST") ||
            canonicalSource6548.contains("REHYDRATE") ||
            canonicalSource6548.contains("RESTORED") ||
            canonicalSource6548.contains("ENTRY_PRICE") ||
            canonicalSource6548.contains("LAST_KNOWN") ||
            canonicalSource6548.contains("PLACEHOLDER")
        val provenanceVouched7148 = !knownProvider7148 && !symbolicBasis7148 &&
            provenance == MarketDataProvenance6471.Provenance.AUTHORITATIVE
        if (provenanceVouched7148) {
            try {
                PipelineHealthCollector.labelInc("MARK_ADMITTED_ON_PROVENANCE_NOT_NAME_7148")
                PipelineHealthCollector.labelInc("MARK_ADMITTED_ON_PROVENANCE_NOT_NAME_7148|$canonicalSource6548")
            } catch (_: Throwable) {}
        }
        val realPriceSource = knownProvider7148 || provenanceVouched7148
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
                // V5.0.7198 — tally the reason INSIDE the gate as well.
                //
                // 6547 has emitted this breakdown as a labelInc since it was
                // written, and the operator has never once been able to read
                // it: the report prints the top counters and ends with
                // "(+1901 more non-pinned counters above 0, not shown)", and
                // every MARK_AUTHORITY_GATE_BLOCKED_6547|* key is inside that
                // truncation. A per-reason tally held here rides out on
                // statusLine7198 instead, which is pinned.
                blockReasons7198
                    .computeIfAbsent(blockReason6547.substringBefore(':')) { AtomicLong(0L) }
                    .incrementAndGet()
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
        val result6615 = AuthorityResult(priceAuthoritative, observationAuthoritative, routeExecutable, provenance)
        val previousGoodAt6615 = prior6615?.lastGoodMarkAt ?: 0L
        val previousGoodPrice6615 = prior6615?.lastGoodPrice ?: 0.0
        markStates6615[mint] = MarkState6615(
            lastMarkAttemptAt = now6615,
            lastGoodMarkAt = if (priceAuthoritative) now6615 else previousGoodAt6615,
            lastGoodPrice = if (priceAuthoritative) priceUsd else previousGoodPrice6615,
            lastObservedPriceVersion = fingerprint6615.hashCode().toLong(),
            nextMarkEligibleAt = now6615 + if (priceAuthoritative) 5_000L else 15_000L,
            markState = if (priceAuthoritative) "GOOD" else "DEGRADED",
            fingerprint = fingerprint6615,
            result = result6615,
        )
        return result6615
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

    /**
     * V5.0.7198 §THE_GATE_HOLDING_THE_CAPITAL_HAD_NO_SCOREBOARD.
     *
     * This function existed with ZERO callers. Nothing has ever printed it.
     *
     * On the 5.0.7197 device run that mattered, because this gate decides
     * whether an open position can be priced, and therefore whether it can
     * take a price-based exit and give its capital back:
     *
     *   MARK_QUOTE_7060_UNAVAILABLE_NOT_AUTHORITATIVE_6496:  99,031
     *   MARK_AUTHORITY_GATE_BLOCKED_6496:                    10,199
     *   staleMarks=41 of 59 open positions
     *   CASH 0.0000 SOL · capitalRefusals7194=2591
     *
     * 41 of 59 positions could not price, so they could not close, so cash
     * stayed at zero and 2,591 entries were refused for capital. The whole
     * bot's throughput was downstream of this gate, and the gate reported
     * nothing.
     *
     * Note the two counters disagree by 88,832. The difference is the
     * markStates6615 coalescer at the top of evaluate(): when every input is
     * byte-identical it returns the PRIOR verdict without re-evaluating or
     * re-logging. That is correct memoisation — identical inputs cannot
     * produce a different answer — but it means a negative verdict on a feed
     * that has gone flat is replayed for its whole 15s TTL, so 10,199 real
     * decisions present as 99,031 refusals. coalesced= is printed here so
     * that ratio is visible instead of being inferred.
     *
     * DIAGNOSTIC ONLY. No check is relaxed and no threshold moves. The four
     * reasons are already classified by 6547; this just carries the tally out
     * past the report's truncation.
     */
    fun statusLine7198(): String {
        val reasons = try {
            blockReasons7198.entries
                .sortedByDescending { it.value.get() }
                .take(4)
                .joinToString(",") { "${it.key}=${it.value.get()}" }
                .ifBlank { "none" }
        } catch (_: Throwable) { "unavailable" }
        return "MARK_AUTHORITY(§6496): evaluated=${evaluated.get()} " +
            "pass=${authoritativePasses.get()} blocked=${nonAuthoritativeBlocks.get()} " +
            "coalesced=${markCoalesced6615.get()} tracked=${markStates6615.size} " +
            "why7198=[$reasons]"
    }

    fun statusLine(): String =
        "evaluated=${evaluated.get()} authoritativePasses=${authoritativePasses.get()} " +
            "nonAuthoritativeBlocks=${nonAuthoritativeBlocks.get()} markCoalesced=${markCoalesced6615.get()} " +
            "markStates=${markStates6615.size}"

    internal fun resetForTest() {
        evaluated.set(0L); authoritativePasses.set(0L); nonAuthoritativeBlocks.set(0L)
        markCoalesced6615.set(0L); markStates6615.clear(); blockReasons7198.clear()
    }
}
