package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6471 §P0 (items 16-20) — MARKET DATA PROVENANCE AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6470 evidence):
 *
 *   "6470 shows many unrelated entries sharing:
 *      price=0.050250000
 *      mcap=50000000
 *    and MINT_ENTRY_MARKET_SNAPSHOT_POOL_SENTINEL fired 18 times.
 *
 *    Any placeholder, sentinel, synthetic, guessed, fallback-default
 *    or cache-template price/liquidity/mcap MUST carry
 *    provenance=NON_AUTHORITATIVE.
 *
 *    NON_AUTHORITATIVE market data may keep token visible / feed
 *    shadow telemetry / request provider refresh, but MUST NOT
 *    satisfy liquidity proof / cause BLUECHIP or QUALITY
 *    classification / raise score / satisfy FDG / calculate
 *    executable quantity / create canonical entry snapshot / enter
 *    learner terminal truth."
 */
object MarketDataProvenance6471 {

    enum class Provenance {
        AUTHORITATIVE,
        NON_AUTHORITATIVE_SENTINEL,
        NON_AUTHORITATIVE_MISSING,
    }

    private val classified = AtomicLong(0L)
    private val sentinelHits = AtomicLong(0L)
    private val missingHits = AtomicLong(0L)
    private val executableBlocked = AtomicLong(0L)
    private val sentinelCoalesced6615 = AtomicLong(0L)
    private data class SentinelState6615(
        val mint: String,
        val sentinelFingerprint: String,
        val sentinelReason: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val lastProcessedAt: Long,
        val occurrenceCount: Long,
    )
    private val sentinelStates6615 = ConcurrentHashMap<String, SentinelState6615>()
    private const val SENTINEL_HEARTBEAT_MS_6615 = 5_000L

    private data class TemplateTuple(val price: Double, val mcap: Double, val liquidity: Double)
    private val KNOWN_TEMPLATES = listOf(
        TemplateTuple(0.050250000, 50_000_000.0, 5_000_000.0),
    )
    private const val TEMPLATE_EPSILON = 1e-6

    // V5.0.6658 §SENTINEL_PRICE_STANDALONE — observed template prices are
    // non-authoritative even when mcap/liquidity drift away from the original
    // template tuple. Keep this check independent of KNOWN_TEMPLATES so a
    // sentinel price cannot become executable merely because another field varied.
    private val SENTINEL_PRICES_STANDALONE_6658 = doubleArrayOf(
        0.050250000,
        0.000052530,
        0.000000589600,
        // V5.0.6911 — observed template. Operator 5.0.6909 shows this exact
        // double, to all 17 significant figures, as the entry price of at
        // least three unrelated mints simultaneously:
        //   E57LjeaJAp/NTDA entry=2.0137438370880698E-4 refusals=861
        //   j1AntPusR5/USPR entry=2.0137438370880698E-4 refusals=1041
        //   mzGEKC8bKm/ELON entry=2.0137438370880698E-4 refusals=1021
        // Unrelated tokens do not collide to 17 figures, and the §6895 mark
        // guard is spending thousands of refusals per position keeping these
        // basis values out of PnL — which leaves the positions open and
        // unpriceable rather than never opened. Neighbouring real entries in
        // the same snapshot (1.9746420E-4, 1.9941929E-4) differ well outside
        // the 1e-6 relative band, so a genuine price near this magnitude is
        // unaffected.
        2.0137438370880698E-4,
    )
    private const val SENTINEL_PRICE_RELATIVE_EPSILON_6658 = 1e-6

    /**
     * V5.0.6677 — one public, side-effect-free sentinel fingerprint authority.
     *
     * Cross-asset admission and persisted-position recovery previously had no
     * way to ask the provenance authority whether a standalone price was one of
     * the known template fingerprints. Re-declaring these values in CryptoAlt
     * would create exactly the source/patch duplication that has caused prior
     * regressions. Keep the values private here and expose only the predicate.
     */
    fun isKnownStandaloneSentinelPrice6658(price: Double): Boolean =
        price.isFinite() && price > 0.0 && SENTINEL_PRICES_STANDALONE_6658.any {
            it > 0.0 && kotlin.math.abs(price - it) <= it * SENTINEL_PRICE_RELATIVE_EPSILON_6658
        }

    private val SENTINEL_POOL_PREFIXES = listOf(
        "MINT_ROUTE:", "UNKNOWN", "PLACEHOLDER", "SENTINEL",
    )
    /**
     * V5.0.6908 §ARCHIVED_MARK_IS_NOT_A_LIVE_MARK.
     *
     * Operator directive: "price and things that move should always be checked
     * in a live state if being interacted with by aate."
     *
     * The single source label for a price restored from the durable token
     * archive (TokenMetaCache) or from another instance's archive via the hive
     * (collective_token_mints). It lives HERE, next to the sentinel list that
     * judges it, so no caller can invent a second spelling — vocabulary
     * mismatch between a writer and its gate is how these authorities have
     * gone quietly dead before.
     *
     * Before this existed, a restored price was copied onto the runtime row
     * still wearing the ORIGINAL provider's name, so classify() saw
     * "DEXSCREENER_PAIR_POLL" and returned AUTHORITATIVE — enough under §6658
     * to seal an immutable canonical paper entry from a number of unknown age
     * off local disk. The 6471 mandate already covers this case in words
     * ("cache-template price MUST carry provenance=NON_AUTHORITATIVE"); it
     * just had no way to recognise one.
     *
     * Consequence of being in SENTINEL_SOURCES: an archived mark keeps the
     * token visible, keeps feeding shadow telemetry and can request a provider
     * refresh, but cannot prove liquidity, cause BLUECHIP/QUALITY
     * classification, raise score, satisfy FDG, size an order, create a
     * canonical entry snapshot, or enter learner terminal truth. Exactly the
     * mandate, applied to the archive.
     */
    const val ARCHIVED_MARK_SOURCE_6908 = "TOKEN_META_ARCHIVE_6908"

    private val SENTINEL_SOURCES = setOf(
        "UNKNOWN", "FALLBACK", "CACHE_DEFAULT", "CACHE_TEMPLATE", "SYNTHETIC",
        ARCHIVED_MARK_SOURCE_6908,
        // The pre-existing warm-boot label written by Executor's cached-pool
        // recovery path. It was never in this set, and Executor simultaneously
        // counted it a real provider feed, so a disk-cached price passed as
        // live on both sides of the boundary. It is a sentinel HERE, on
        // freshness; Executor's priceBasisFamily7166 still reads it as the
        // market basis, which is a different question — a stale AMM price is
        // stale, not measured on some other scale.
        "TOKEN_META_CACHE",
    )

    private fun canonicalSource6674(source: String): String {
        val s = source.trim().uppercase()
        return when {
            s.startsWith("DEXSCREENER") -> "DEXSCREENER"
            s.startsWith("BIRDEYE") -> "BIRDEYE"
            s.startsWith("GECKOTERMINAL") || s.startsWith("GECKO_TERMINAL") -> "GECKOTERMINAL"
            s.startsWith("JUPITER") -> "JUPITER"
            s.startsWith("PUMPFUN") || s.startsWith("PUMP_FUN") || s.startsWith("PUMP_PORTAL") -> "PUMPFUN"
            else -> s
        }
    }

    /**
     * V5.0.6674 §CANONICALLY_PROVEN_MINT_ROUTE_EXECUTABLE.
     *
     * A MINT_ROUTE alias is still NON_AUTHORITATIVE by default. The only
     * exception is when CanonicalPriceMarkRegistry6522 already contains an
     * EXECUTABLE_ENTRY_QUOTE for the exact mint, produced by the existing
     * source-evidence promotion path, and the immutable price/liquidity/source
     * tuple reaching the executor matches that mark.
     *
     * This closes the source/patch contradiction where the registry had already
     * proved the executable quote but Executor.mintEntryMarketSnapshot later
     * rendered a temporary `MINT_ROUTE:<mint-prefix>` alias and the generic
     * provenance classifier rejected the same trade. No unproven sentinel is
     * promoted; absence or mismatch remains fail-closed.
     */
    private fun canonicallyProvenMintRoute6674(
        identity: String,
        pool: String,
        source: String,
        price: Double,
        liquidity: Double,
    ): Boolean {
        if (identity.isBlank() || !pool.startsWith("MINT_ROUTE:", ignoreCase = true)) return false
        val routeId = pool.substringAfter(':').trim()
        if (routeId.isBlank()) return false
        val identityMatchesAlias = identity.equals(routeId, ignoreCase = true) ||
            identity.startsWith(routeId, ignoreCase = true)
        if (!identityMatchesAlias) return false

        val mark = try {
            CanonicalPriceMarkRegistry6522.get(identity, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        } catch (_: Throwable) { null } ?: return false
        if (mark.mint != identity || mark.baseMint != identity) return false
        if (!mark.pairId.equals("MINT_ROUTE:$identity", ignoreCase = true)) return false
        if (mark.identityProof6613 != "CANONICAL_MINT_SOURCE_MARK_6613") return false
        if (canonicalSource6674(mark.source) != canonicalSource6674(source)) return false

        val markPrice = try { mark.priceUsd.value.toDouble() } catch (_: Throwable) { return false }
        if (!markPrice.isFinite() || markPrice <= 0.0) return false
        val priceTol = maxOf(1e-18, kotlin.math.abs(markPrice) * 1e-9)
        if (kotlin.math.abs(markPrice - price) > priceTol) return false

        val markLiq = try { mark.liquidityUsd?.toDouble() ?: return false } catch (_: Throwable) { return false }
        if (!markLiq.isFinite() || markLiq <= 0.0 || !liquidity.isFinite() || liquidity <= 0.0) return false
        val liqTol = maxOf(0.01, kotlin.math.abs(markLiq) * 1e-6)
        if (kotlin.math.abs(markLiq - liquidity) > liqTol) return false

        return true
    }

    fun classify(
        price: Double,
        mcap: Double,
        liquidity: Double,
        source: String,
        poolAddress: String,
        identity: String = "",
        /**
         * V5.0.7199 — true only when the caller holds an OPEN canonical
         * position on this mint, which proves mint identity independently of
         * the route key. Defaults to false so every entry-side caller is
         * unchanged. See the §7199 note at the sentinel-pool check.
         */
        isKnownOpenMint6596: Boolean = false,
    ): Provenance {
        classified.incrementAndGet()
        val pool = poolAddress.trim()
        val identityKey6615 = identity.trim().ifBlank {
            if (pool.startsWith("MINT_ROUTE:", ignoreCase = true)) pool.substringAfter(':').trim()
            else pool.ifBlank { source.trim().uppercase().ifBlank { "UNKNOWN" } }
        }
        if (!price.isFinite() || price <= 0.0) return recordMissing("price_invalid")
        if (!liquidity.isFinite() || liquidity < 0.0) return recordMissing("liquidity_invalid")
        if (KNOWN_TEMPLATES.any {
                kotlin.math.abs(price - it.price) < TEMPLATE_EPSILON &&
                    kotlin.math.abs(mcap - it.mcap) < 1.0 &&
                    kotlin.math.abs(liquidity - it.liquidity) < 1.0
            }) return recordSentinel(identityKey6615, "template_tuple($price/$mcap/$liquidity)")
        if (isKnownStandaloneSentinelPrice6658(price))
            return recordSentinel(identityKey6615, "sentinel_price_standalone($price)")
        if (pool.isBlank()) return recordMissing("pool_blank")

        // V5.0.6674 — consult existing executable mark proof BEFORE the generic
        // MINT_ROUTE sentinel rule. This is a proof lookup, not a relaxation.
        if (canonicallyProvenMintRoute6674(identity.trim(), pool, source, price, liquidity)) {
            clearSentinel6615(identityKey6615)
            try {
                PipelineHealthCollector.labelInc("CANONICAL_MINT_ROUTE_EXECUTABLE_ADMITTED_6674")
                ForensicLogger.lifecycle(
                    "CANONICAL_MINT_ROUTE_EXECUTABLE_ADMITTED_6674",
                    "mint=${identity.take(18)} pool=${pool.take(24)} src=$source price=$price liq=$liquidity action=admit_existing_executable_quote_proof",
                )
            } catch (_: Throwable) {}
            return Provenance.AUTHORITATIVE
        }

        // V5.0.7199 §6596_TAUGHT_THE_GATE_AND_NOT_THE_CLASSIFIER.
        //
        // V5.0.6596 established that for a mint with an OPEN canonical
        // position the mint identity is already proven, so a "MINT_ROUTE:"
        // route key is acceptable pool identity. It applied that to
        // MarkAuthorityIntegrityGate6496.realPoolIdentity and stopped there.
        // This classifier never learned it, and the gate never passed the flag
        // down — so every exit-path mark arrived here, matched the sentinel
        // prefix on the line below, and came back NON-AUTHORITATIVE.
        //
        // That silently killed V5.0.7148. 7148 added `provenanceVouched7148`
        // precisely so an honest new provider name could be admitted on the
        // strength of its provenance instead of a hard-coded list, and its
        // worked example was `src=FANOUT_CORROBORATED_7088_x2` — a mark backed
        // by TWO independent feeds agreeing. But provenanceVouched7148
        // requires provenance == AUTHORITATIVE, and the exit path always
        // supplies MINT_ROUTE, so that provenance was never obtainable there.
        // The escape hatch could not fire on the one path it was written for.
        //
        // Measured on 5.0.7197: MARK_QUOTE_7060_UNAVAILABLE_NOT_AUTHORITATIVE_6496
        // = 99,031, with 41 of 59 open positions unable to price, cash at
        // 0.0000 SOL and 2,591 entries refused for capital. Those marks had
        // already been certified usable by CanonicalMarkResolution7059 one line
        // earlier — two authorities disagreeing about the same number, because
        // one of them was judging the route key instead of the price.
        //
        // NARROW BY CONSTRUCTION. The parameter defaults to false, so every
        // entry-side caller (V3, FDG, executor route generation) keeps the
        // strict pre-7199 behaviour byte-for-byte. Only "MINT_ROUTE:" is
        // forgiven, and only when the caller can prove the mint is open —
        // UNKNOWN / PLACEHOLDER / SENTINEL pools are still refused here, a
        // blank pool is still refused above, and SENTINEL_SOURCES below is
        // untouched, so a price with no honest provenance still cannot pass.
        val sentinelPool7199 = SENTINEL_POOL_PREFIXES.any { prefix ->
            if (isKnownOpenMint6596 && prefix.equals("MINT_ROUTE:", ignoreCase = true)) false
            else pool.startsWith(prefix, ignoreCase = true)
        }
        if (sentinelPool7199) return recordSentinel(identityKey6615, "pool_prefix($pool)")
        if (isKnownOpenMint6596 && pool.startsWith("MINT_ROUTE:", ignoreCase = true)) {
            try {
                PipelineHealthCollector.labelInc("MINT_ROUTE_ADMITTED_FOR_KNOWN_OPEN_7199")
            } catch (_: Throwable) {}
        }
        val src = source.trim().uppercase()
        if (src.isBlank()) return recordMissing("source_blank")
        if (src in SENTINEL_SOURCES) return recordSentinel(identityKey6615, "source($src)")
        clearSentinel6615(identityKey6615)
        return Provenance.AUTHORITATIVE
    }

    private fun recordSentinel(identity: String, reason: String): Provenance {
        sentinelHits.incrementAndGet()
        try { HotLabelCoalescer6626.inc6626("MARKET_DATA_SENTINEL_6471") } catch (_: Throwable) {}
        val now = System.currentTimeMillis()
        val fingerprint = "$identity|$reason"
        var transition = false
        var heartbeatCount = 0L
        var firstAt = now
        sentinelStates6615.compute(identity) { _, prior ->
            if (prior == null || prior.sentinelFingerprint != fingerprint) {
                transition = true
                SentinelState6615(identity, fingerprint, reason, now, now, now, 1L)
            } else {
                val count = prior.occurrenceCount + 1L
                firstAt = prior.firstSeenAt
                if (now - prior.lastProcessedAt >= SENTINEL_HEARTBEAT_MS_6615) heartbeatCount = count
                prior.copy(
                    lastSeenAt = now,
                    lastProcessedAt = if (heartbeatCount > 0L) now else prior.lastProcessedAt,
                    occurrenceCount = count,
                )
            }
        }
        if (!transition) sentinelCoalesced6615.incrementAndGet()
        try {
            when {
                transition -> ForensicLogger.lifecycle(
                    "MARKET_DATA_SENTINEL_6471",
                    "mint=${identity.take(18)} reason=$reason transition=ACTIVE occurrences=1",
                )
                heartbeatCount > 0L -> ForensicLogger.lifecycle(
                    "MARKET_DATA_SENTINEL_COALESCED_6615",
                    "mint=${identity.take(18)} reason=$reason occurrences=$heartbeatCount windowMs=${now - firstAt}",
                )
            }
        } catch (_: Throwable) {}
        return Provenance.NON_AUTHORITATIVE_SENTINEL
    }

    private fun clearSentinel6615(identity: String) {
        val prior = sentinelStates6615.remove(identity) ?: return
        try {
            ForensicLogger.lifecycle(
                "MARKET_DATA_SENTINEL_CLEARED_6615",
                "mint=${identity.take(18)} reason=${prior.sentinelReason} occurrences=${prior.occurrenceCount}",
            )
        } catch (_: Throwable) {}
    }

    private fun recordMissing(reason: String): Provenance {
        missingHits.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MARKET_DATA_MISSING_6471") } catch (_: Throwable) {}
        return Provenance.NON_AUTHORITATIVE_MISSING
    }

    fun isExecutable(p: Provenance): Boolean {
        val ok = p == Provenance.AUTHORITATIVE
        if (!ok) {
            executableBlocked.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARKET_DATA_EXECUTABLE_BLOCKED_6471") } catch (_: Throwable) {}
        }
        return ok
    }

    fun isExecutable(
        price: Double, mcap: Double, liquidity: Double,
        source: String, poolAddress: String,
    ): Boolean = isExecutable(classify(price, mcap, liquidity, source, poolAddress))

    fun statusLine(): String =
        "classified=${classified.get()} sentinelHits=${sentinelHits.get()} " +
            "missingHits=${missingHits.get()} executableBlocked=${executableBlocked.get()} " +
            "sentinelActive=${sentinelStates6615.size} sentinelCoalesced=${sentinelCoalesced6615.get()}"

    internal fun resetForTest() {
        classified.set(0L); sentinelHits.set(0L)
        missingHits.set(0L); executableBlocked.set(0L)
        sentinelCoalesced6615.set(0L); sentinelStates6615.clear()
    }
}
