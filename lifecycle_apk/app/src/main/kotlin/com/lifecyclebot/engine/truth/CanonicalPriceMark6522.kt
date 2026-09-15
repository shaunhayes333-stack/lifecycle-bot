package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

enum class CanonicalMarkPurpose6570 { OBSERVATION_SCORING, EXIT_ECONOMIC, EXECUTABLE_ENTRY_QUOTE }

data class CanonicalPriceMark6522(
    val mint: String,
    val pairId: String,
    val baseMint: String,
    val quoteMint: String,
    val source: String,
    val timestampMs: Long,
    val priceUsd: PriceUsd,
    val liquidityUsd: java.math.BigDecimal?,
    val purpose: CanonicalMarkPurpose6570 = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
    val identityProof6613: String = "",
)

object CanonicalPriceMarkRegistry6522 {
    private val marks = ConcurrentHashMap<Pair<String, CanonicalMarkPurpose6570>, CanonicalPriceMark6522>()

    data class SourceEvidence6734(
        val baseMint: String, val pair: String, val quoteMint: String, val source: String,
        val priceUsd: Double, val liquidityUsd: Double, val timestampMs: Long,
    )

    /** Strict executable-entry freshness. Never widen this for live execution. */
    const val MARK_FRESHNESS_WINDOW_MS_6739 = 120_000L

    /** Observation/scoring evidence may remain useful for paper learning for 300s. */
    const val OBSERVATION_FRESHNESS_WINDOW_MS_6743 = 300_000L

    fun getFresh6734(mint: String, purpose: CanonicalMarkPurpose6570,
                     nowMs: Long = System.currentTimeMillis()): CanonicalPriceMark6522? {
        val window = if (purpose == CanonicalMarkPurpose6570.OBSERVATION_SCORING)
            OBSERVATION_FRESHNESS_WINDOW_MS_6743 else MARK_FRESHNESS_WINDOW_MS_6739
        return marks[mint to purpose]?.takeIf {
            it.baseMint == mint && it.priceUsd.value.toDouble().isFinite() &&
                it.priceUsd.value.signum() > 0 && nowMs - it.timestampMs in -5_000L..window
        }
    }

    /** Try complete provider tuples newest-first. Rejection may try another real provider,
     * never splice its timestamp/source onto the rejected provider's price. */
    fun resolveBestSourceEvidence6734(mint: String, evidence: List<SourceEvidence6734>,
                                     nowMs: Long = System.currentTimeMillis()): PromotionResult6613 {
        var last = PromotionResult6613(null, "NO_FRESH_SOURCE_EVIDENCE_6734", identity = mint)
        for (e in evidence.sortedByDescending { it.timestampMs }) {
            val ageMs = nowMs - e.timestampMs
            if (e.source.isBlank()) continue
            if (ageMs !in -5_000L..OBSERVATION_FRESHNESS_WINDOW_MS_6743) continue
            if (ageMs in -5_000L..MARK_FRESHNESS_WINDOW_MS_6739) {
                last = resolveExecutableFromSourceEvidence6616(
                    mint, e.baseMint, e.pair, e.quoteMint, e.source, e.priceUsd,
                    e.liquidityUsd, e.timestampMs, nowMs,
                )
                if (last.promoted) return last
            } else {
                // V5.0.6743 — 121-300s evidence is observation-only. This is
                // deliberately useful for paper/scoring but cannot become a
                // strict executable quote until fresh provider evidence lands.
                last = resolveObservationFromSourceEvidence6628(
                    mint = mint, observedBaseMint = e.baseMint,
                    pairOrPool = e.pair, quoteMint = e.quoteMint,
                    source = e.source, priceUsd = e.priceUsd,
                    evidenceTimestampMs = e.timestampMs, nowMs = nowMs,
                )
                if (last.promoted) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_OBSERVATION_ROUTED_STALE_EVIDENCE_6743")
                    } catch (_: Throwable) {}
                    return last
                }
            }
        }
        return last
    }

    fun publish(mark: CanonicalPriceMark6522): Boolean {
        if (mark.mint.isBlank() || mark.baseMint != mark.mint) return false
        if (mark.pairId.isBlank()) return false
        if (mark.quoteMint.isBlank() || mark.priceUsd.value.signum() <= 0 || mark.timestampMs <= 0L) return false

        val rawPrice6697 = try { mark.priceUsd.value.toDouble() } catch (_: Throwable) { Double.NaN }
        if (!rawPrice6697.isFinite() || rawPrice6697 < 1e-18 || rawPrice6697 > 1e12) return false
        if (MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(rawPrice6697)) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_SENTINEL_REJECTED_6697")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_MARK_SENTINEL_REJECTED_6697",
                    "mint=${mark.mint.take(18)} purpose=${mark.purpose} price=$rawPrice6697 source=${mark.source.take(40)} pair=${mark.pairId.take(32)} action=reject_before_registry_write",
                )
            } catch (_: Throwable) {}
            return false
        }

        val liq6728 = try { mark.liquidityUsd?.toDouble() ?: 0.0 } catch (_: Throwable) { 0.0 }
        val price6728 = rawPrice6697
        val roundPrice6728 = price6728 > 0.0 && price6728.isFinite() && (
            price6728 == 0.05 || price6728 == 0.10 || price6728 == 0.50 ||
            price6728 == 1.00 || price6728 == 5.00 || price6728 == 10.00
        )
        val roundLiq6728 = liq6728 > 0.0 && (
            liq6728 == 1_000_000.0 || liq6728 == 5_000_000.0 ||
            liq6728 == 10_000_000.0 || liq6728 == 50_000_000.0 ||
            liq6728 == 100_000_000.0
        )
        // V5.0.6788 §CANONICAL_MARK_AUTHORITY — the sentinel-shape quarantine
        // only fires on marks WITHOUT identity provenance. A mark that carries
        // a canonical identity proof (TOKEN_MAP-verified DEX/pump route) has
        // already proved routable; rejecting it because its price+liquidity
        // pair happens to hit a round-number fingerprint is a false positive
        // that starves execution of legitimate executable marks. Directive:
        //   "A fresh, identity-valid DEX/pump executable route with nonzero
        //    price/liquidity MUST produce an executable mark."
        val identityProven6788 = mark.identityProof6613.isNotBlank() &&
            mark.identityProof6613 != "UNKNOWN"
        if (roundPrice6728 && roundLiq6728 && !identityProven6788) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_SENTINEL_SHAPE_QUARANTINE_6728")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_MARK_SENTINEL_SHAPE_QUARANTINE_6728",
                    "mint=${mark.mint.take(18)} purpose=${mark.purpose} price=$price6728 liq=$liq6728 source=${mark.source.take(40)} pair=${mark.pairId.take(32)} action=reject_fallback_economic_shape_no_identity",
                )
            } catch (_: Throwable) {}
            return false
        }
        if (roundPrice6728 && roundLiq6728 && identityProven6788) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_SENTINEL_SHAPE_ADMITTED_IDENTITY_PROVEN_6788")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_MARK_SENTINEL_SHAPE_ADMITTED_IDENTITY_PROVEN_6788",
                    "mint=${mark.mint.take(18)} purpose=${mark.purpose} price=$price6728 liq=$liq6728 identityProof=${mark.identityProof6613} action=admit_route_proven",
                )
            } catch (_: Throwable) {}
        }

        val mintRoute = mark.pairId.startsWith("MINT_ROUTE:", true)
        val sourceGroundedMintIdentity6613 = mintRoute &&
            mark.pairId.equals("MINT_ROUTE:${mark.mint}", true) &&
            mark.identityProof6613 == "CANONICAL_MINT_SOURCE_MARK_6613"
        if (mark.purpose != CanonicalMarkPurpose6570.OBSERVATION_SCORING && mintRoute && !sourceGroundedMintIdentity6613) return false

        if (mark.purpose == CanonicalMarkPurpose6570.OBSERVATION_SCORING) {
            val ageMs = System.currentTimeMillis() - mark.timestampMs
            // V5.0.6756 — remove patch rot: 6743 widened the observation
            // contract to 300s but this barrier retained the old 120s literal.
            if (ageMs !in -5_000L..OBSERVATION_FRESHNESS_WINDOW_MS_6743) return false
            val observationOk = MarkAuthorityIntegrityGate6496.isObservationAuthoritative6570(
                mint = mark.mint, priceUsd = mark.priceUsd.value.toDouble(), source = mark.source,
                poolAddress = mark.pairId, fresh = true,
            )
            if (!observationOk) return false
        }

        val key = mark.mint to mark.purpose
        val currentMark6727 = marks[key]
        if (currentMark6727 != null) {
            val currentP6727 = try { currentMark6727.priceUsd.value.toDouble() } catch (_: Throwable) { 0.0 }
            val newP6727 = rawPrice6697
            if (currentP6727 > 0.0 && newP6727 > 0.0 && newP6727.isFinite()) {
                val ratio6727 = kotlin.math.max(newP6727 / currentP6727, currentP6727 / newP6727)
                if (ratio6727 > 100.0) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_RATIO_QUARANTINE_6727")
                        val bucket6727 = when {
                            ratio6727 < 1_000.0 -> "100X"
                            ratio6727 < 10_000.0 -> "1000X"
                            ratio6727 < 100_000.0 -> "10000X"
                            ratio6727 < 1_000_000.0 -> "100000X"
                            else -> "GT_1M_X"
                        }
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_RATIO_QUARANTINE_BUCKET_6727_$bucket6727")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "CANONICAL_MARK_RATIO_QUARANTINE_6727",
                            "mint=${mark.mint.take(18)} purpose=${mark.purpose} currentP=$currentP6727 newP=$newP6727 ratio=${"%.1f".format(ratio6727)}x bucket=$bucket6727 source=${mark.source.take(40)} action=reject_absurd_ratio",
                        )
                    } catch (_: Throwable) {}
                    return false
                }
            }
        }
        marks.compute(key) { _, current -> if (current == null || mark.timestampMs >= current.timestampMs) mark else current }
        return marks[key] == mark
    }

    data class PromotionResult6613(
        val mark: CanonicalPriceMark6522?, val reason: String,
        val source: String = "", val price: Double = 0.0, val ageMs: Long = -1L,
        val identity: String = "", val unitState: String = "",
    ) { val promoted: Boolean get() = mark != null }

    /** Promote one already-validated observation into the executable price slot.
     * Route/sellability remain independent live-execution requirements. */
    fun promoteObservationToExecutable6613(mint: String, nowMs: Long = System.currentTimeMillis()): PromotionResult6613 {
        val obs = marks[mint to CanonicalMarkPurpose6570.OBSERVATION_SCORING]
            ?: return PromotionResult6613(null, "NO_OBSERVATION", identity = mint)
        val price = obs.priceUsd.value.toDouble()
        val age = nowMs - obs.timestampMs
        val exactIdentity = obs.baseMint == mint && (
            !obs.pairId.startsWith("MINT_ROUTE:", true) || obs.pairId.equals("MINT_ROUTE:$mint", true)
        )
        val unitOk = price.isFinite() && price > 0.0 && price >= 1e-18 && price <= 1e12 && obs.priceUsd.value.scale() <= 30
        // Observation may exist for 300s, but executable promotion is still
        // strictly 120s. Paper may consume the observation slot separately.
        val executableFresh = age in -5_000L..MARK_FRESHNESS_WINDOW_MS_6739
        val sourceOk = MarkAuthorityIntegrityGate6496.isObservationAuthoritative6570(
            mint, price, obs.source, obs.pairId, executableFresh,
        )
        val liquidityOk = obs.liquidityUsd?.let { it.signum() > 0 } == true
        val reason = when {
            !exactIdentity -> "IDENTITY_MISMATCH"
            !executableFresh -> "STALE_SOURCE_MARK"
            !unitOk -> "PRICE_UNIT_DECIMAL_INVALID"
            !sourceOk -> "SOURCE_PROVENANCE_REJECTED"
            !liquidityOk -> "LIQUIDITY_MISSING"
            obs.quoteMint.isBlank() -> "QUOTE_IDENTITY_MISSING"
            else -> "PROMOTED"
        }
        if (reason != "PROMOTED") return PromotionResult6613(null, reason, obs.source, price, age, "${obs.baseMint}->${obs.quoteMint}@${obs.pairId}", "scale=${obs.priceUsd.value.scale()}")
        val promoted = obs.copy(
            purpose = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
            identityProof6613 = if (obs.pairId.startsWith("MINT_ROUTE:", true)) "CANONICAL_MINT_SOURCE_MARK_6613" else obs.identityProof6613,
        )
        val published = publish(promoted)
        val admitted = if (published) promoted else
            getFresh6734(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE, nowMs)
                ?.takeIf { it.timestampMs >= promoted.timestampMs }
        return if (admitted != null) PromotionResult6613(admitted, reason, admitted.source,
            admitted.priceUsd.value.toDouble(), nowMs - admitted.timestampMs,
            "${admitted.baseMint}->${admitted.quoteMint}@${admitted.pairId}", "scale=${admitted.priceUsd.value.scale()}")
        else PromotionResult6613(null, "REGISTRY_PUBLISH_REJECTED", obs.source, price, age,
            "${obs.baseMint}->${obs.quoteMint}@${obs.pairId}", "scale=${obs.priceUsd.value.scale()}")
    }

    /** V5.0.6616 — canonical source-evidence resolver before V3/execution. */
    fun resolveExecutableFromSourceEvidence6616(
        mint: String,
        observedBaseMint: String,
        pairOrPool: String,
        quoteMint: String,
        source: String,
        priceUsd: Double,
        liquidityUsd: Double,
        evidenceTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): PromotionResult6613 {
        if (mint.isBlank()) return PromotionResult6613(null, "SOURCE_MINT_MISSING")
        if (observedBaseMint.isNotBlank() && observedBaseMint != mint)
            return PromotionResult6613(null, "SOURCE_BASE_IDENTITY_MISMATCH", source, priceUsd, identity = "$observedBaseMint!=$mint")
        val ageMs = nowMs - evidenceTimestampMs
        if (evidenceTimestampMs <= 0L || ageMs !in -5_000L..OBSERVATION_FRESHNESS_WINDOW_MS_6743)
            return PromotionResult6613(null, "SOURCE_EVIDENCE_STALE", source, priceUsd, ageMs = ageMs, identity = mint)
        if (!priceUsd.isFinite() || priceUsd <= 0.0 || priceUsd < 1e-18 || priceUsd > 1e12)
            return PromotionResult6613(null, "SOURCE_PRICE_INVALID", source, priceUsd, ageMs = ageMs, identity = mint)
        if (MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(priceUsd))
            return PromotionResult6613(null, "SOURCE_PRICE_SENTINEL_6697", source, priceUsd, ageMs = ageMs, identity = mint)

        if (!liquidityUsd.isFinite() || liquidityUsd <= 0.0) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_MARK_FALLBACK_OBSERVATION_6732")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_MARK_FALLBACK_OBSERVATION_6732",
                    "mint=${mint.take(18)} price=$priceUsd source=${source.take(40)} action=publish_observation_only reason=SOURCE_LIQUIDITY_INVALID",
                )
            } catch (_: Throwable) {}
            return resolveObservationFromSourceEvidence6628(
                mint = mint, observedBaseMint = observedBaseMint,
                pairOrPool = pairOrPool, quoteMint = quoteMint,
                source = source, priceUsd = priceUsd,
                evidenceTimestampMs = evidenceTimestampMs, nowMs = nowMs,
            )
        }
        val normalizedPair = pairOrPool.ifBlank { "MINT_ROUTE:$mint" }
        val normalizedQuote = quoteMint.ifBlank { "USD" }
        val observation = CanonicalPriceMark6522(
            mint = mint,
            pairId = normalizedPair,
            baseMint = mint,
            quoteMint = normalizedQuote,
            source = source,
            timestampMs = evidenceTimestampMs,
            priceUsd = PriceUsd(java.math.BigDecimal.valueOf(priceUsd)),
            liquidityUsd = java.math.BigDecimal.valueOf(liquidityUsd),
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
            identityProof6613 = if (normalizedPair.startsWith("MINT_ROUTE:", true)) "CANONICAL_MINT_SOURCE_MARK_6613" else "",
        )
        if (!publish(observation)) {
            val newer = getFresh6734(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING, nowMs)
                ?.takeIf { it.timestampMs >= observation.timestampMs }
            if (newer == null) return PromotionResult6613(
                null, "SOURCE_OBSERVATION_REJECTED", source, priceUsd, ageMs,
                "$mint->$normalizedQuote@$normalizedPair", "scale=${observation.priceUsd.value.scale()}",
            )
        }
        return promoteObservationToExecutable6613(mint, nowMs)
    }

    /** V5.0.6614 — materialize a current executable mark directly from the
     * existing canonical TokenMap when route, pair/pool, price and liquidity are
     * already proven. */
    fun refreshFromExecutableTokenMap6614(
        mint: String, pairOrPool: String, quoteMint: String, source: String,
        priceUsd: Double, liquidityUsd: Double, routeStatus: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PromotionResult6613 {
        if (routeStatus.uppercase() !in setOf("PUMPFUN_BONDING_CURVE_EXECUTABLE", "DEX_ROUTABLE"))
            return PromotionResult6613(null, "TOKEN_MAP_ROUTE_NOT_EXECUTABLE", source, priceUsd, identity = mint)
        return resolveExecutableFromSourceEvidence6616(
            mint = mint, observedBaseMint = mint, pairOrPool = pairOrPool,
            quoteMint = quoteMint, source = source, priceUsd = priceUsd,
            liquidityUsd = liquidityUsd, evidenceTimestampMs = nowMs, nowMs = nowMs,
        )
    }

    fun get(mint: String, purpose: CanonicalMarkPurpose6570): CanonicalPriceMark6522? =
        marks[mint to purpose]

    fun get(mint: String): CanonicalPriceMark6522? =
        marks[mint to CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE]
            ?: marks[mint to CanonicalMarkPurpose6570.EXIT_ECONOMIC]
            ?: marks[mint to CanonicalMarkPurpose6570.OBSERVATION_SCORING]

    /** Publish observation-only evidence without claiming executable liquidity. */
    fun resolveObservationFromSourceEvidence6628(
        mint: String,
        observedBaseMint: String,
        pairOrPool: String,
        quoteMint: String,
        source: String,
        priceUsd: Double,
        evidenceTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): PromotionResult6613 {
        if (mint.isBlank()) return PromotionResult6613(null, "SOURCE_MINT_MISSING")
        if (observedBaseMint.isNotBlank() && observedBaseMint != mint)
            return PromotionResult6613(null, "SOURCE_BASE_IDENTITY_MISMATCH", source, priceUsd, identity = "$observedBaseMint!=$mint")
        val ageMs = nowMs - evidenceTimestampMs
        if (evidenceTimestampMs <= 0L || ageMs !in -5_000L..OBSERVATION_FRESHNESS_WINDOW_MS_6743)
            return PromotionResult6613(null, "SOURCE_EVIDENCE_STALE", source, priceUsd, ageMs = ageMs, identity = mint)
        if (!priceUsd.isFinite() || priceUsd <= 0.0 || priceUsd < 1e-18 || priceUsd > 1e12)
            return PromotionResult6613(null, "SOURCE_PRICE_INVALID", source, priceUsd, ageMs = ageMs, identity = mint)
        if (MarketDataProvenance6471.isKnownStandaloneSentinelPrice6658(priceUsd))
            return PromotionResult6613(null, "SOURCE_PRICE_SENTINEL_6697", source, priceUsd, ageMs = ageMs, identity = mint)
        val normalizedPair = pairOrPool.ifBlank { "MINT_ROUTE:$mint" }
        val normalizedQuote = quoteMint.ifBlank { "USD" }
        val observation = CanonicalPriceMark6522(
            mint = mint,
            pairId = normalizedPair,
            baseMint = mint,
            quoteMint = normalizedQuote,
            source = source,
            timestampMs = evidenceTimestampMs,
            priceUsd = PriceUsd(java.math.BigDecimal.valueOf(priceUsd)),
            liquidityUsd = null,
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
            identityProof6613 = if (normalizedPair.startsWith("MINT_ROUTE:", true)) "CANONICAL_MINT_SOURCE_MARK_6613" else "",
        )
        if (!publish(observation)) {
            val newer = getFresh6734(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING, nowMs)
                ?.takeIf { it.timestampMs >= observation.timestampMs }
            if (newer == null) return PromotionResult6613(
                null, "SOURCE_OBSERVATION_REJECTED", source, priceUsd, ageMs,
                "$mint->$normalizedQuote@$normalizedPair", "scale=${observation.priceUsd.value.scale()}",
            )
        }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_PRICE_MARK_OBSERVATION_ADMITTED_6628") } catch (_: Throwable) {}
        val admitted = getFresh6734(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING, nowMs)
            ?: return PromotionResult6613(null, "OBSERVATION_EXPIRED_6734", identity = mint)
        return PromotionResult6613(
            admitted, "OBSERVATION_ADMITTED_6628", admitted.source, admitted.priceUsd.value.toDouble(),
            nowMs - admitted.timestampMs, "${admitted.baseMint}->${admitted.quoteMint}@${admitted.pairId}",
            "scale=${admitted.priceUsd.value.scale()}",
        )
    }

    internal fun resetForTest() = marks.clear()
}
