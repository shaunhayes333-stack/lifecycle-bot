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
        if (mark.purpose != CanonicalMarkPurpose6570.OBSERVATION_SCORING && mintRoute) return false
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
