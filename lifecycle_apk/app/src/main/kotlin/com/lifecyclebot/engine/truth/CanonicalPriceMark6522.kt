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
    private val marks = ConcurrentHashMap<String, CanonicalPriceMark6522>()

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
        marks.compute(mark.mint) { _, current -> if (current == null || mark.timestampMs >= current.timestampMs) mark else current }
        return marks[mark.mint] == mark
    }

    fun get(mint: String): CanonicalPriceMark6522? = marks[mint]
    internal fun resetForTest() = marks.clear()
}
