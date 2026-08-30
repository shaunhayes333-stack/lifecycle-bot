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
    // V5.0.6575 — P0-2: split storage per purpose so publishing an OBSERVATION
    // mark cannot overwrite a valid EXECUTABLE_ENTRY_QUOTE mark for the same
    // mint (and vice-versa). Executors read the strict-purpose slot; scoring
    // reads whichever slot has fresher data.
    private val marks = ConcurrentHashMap<Pair<String, CanonicalMarkPurpose6570>, CanonicalPriceMark6522>()

    fun publish(mark: CanonicalPriceMark6522): Boolean {
        if (mark.mint.isBlank() || mark.baseMint != mark.mint) return false
        if (mark.pairId.isBlank()) return false
        if (mark.quoteMint.isBlank() || mark.priceUsd.value.signum() <= 0 || mark.timestampMs <= 0L) return false
        val mintRoute = mark.pairId.startsWith("MINT_ROUTE:", true)
        val sourceGroundedMintIdentity6613 = mintRoute &&
            mark.pairId.equals("MINT_ROUTE:${mark.mint}", true) &&
            mark.identityProof6613 == "CANONICAL_MINT_SOURCE_MARK_6613"
        if (mark.purpose != CanonicalMarkPurpose6570.OBSERVATION_SCORING && mintRoute && !sourceGroundedMintIdentity6613) return false
        if (mark.purpose == CanonicalMarkPurpose6570.OBSERVATION_SCORING) {
            val ageMs = System.currentTimeMillis() - mark.timestampMs
            if (ageMs !in -5_000L..120_000L) return false
            val observationOk = MarkAuthorityIntegrityGate6496.isObservationAuthoritative6570(
                mint = mark.mint, priceUsd = mark.priceUsd.value.toDouble(), source = mark.source,
                poolAddress = mark.pairId, fresh = true,
            )
            if (!observationOk) return false
        }
        val key = mark.mint to mark.purpose
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
        val sourceOk = MarkAuthorityIntegrityGate6496.isObservationAuthoritative6570(
            mint, price, obs.source, obs.pairId, age in -5_000L..300_000L,
        )
        val liquidityOk = obs.liquidityUsd?.let { it.signum() > 0 } == true
        val reason = when {
            !exactIdentity -> "IDENTITY_MISMATCH"
            age !in -5_000L..300_000L -> "STALE_SOURCE_MARK"
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
        return if (publish(promoted)) PromotionResult6613(promoted, reason, obs.source, price, age, "${obs.baseMint}->${obs.quoteMint}@${obs.pairId}", "scale=${obs.priceUsd.value.scale()}")
        else PromotionResult6613(null, "REGISTRY_PUBLISH_REJECTED", obs.source, price, age, "${obs.baseMint}->${obs.quoteMint}@${obs.pairId}", "scale=${obs.priceUsd.value.scale()}")
    }

    /** V5.0.6575 — purpose-aware lookup. Executors MUST use
     *  purpose = EXECUTABLE_ENTRY_QUOTE. Scoring/observation callers may
     *  fall back to OBSERVATION_SCORING when the strict mark is absent. */
    fun get(mint: String, purpose: CanonicalMarkPurpose6570): CanonicalPriceMark6522? =
        marks[mint to purpose]

    /** Back-compat: prefer strict executable mark, then exit-economic, then observation. */
    fun get(mint: String): CanonicalPriceMark6522? =
        marks[mint to CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE]
            ?: marks[mint to CanonicalMarkPurpose6570.EXIT_ECONOMIC]
            ?: marks[mint to CanonicalMarkPurpose6570.OBSERVATION_SCORING]

    internal fun resetForTest() = marks.clear()
}
