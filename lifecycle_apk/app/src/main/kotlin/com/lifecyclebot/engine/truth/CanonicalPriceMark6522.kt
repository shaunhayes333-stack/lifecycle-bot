package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

data class CanonicalPriceMark6522(
    val mint: String,
    val pairId: String,
    val baseMint: String,
    val quoteMint: String,
    val source: String,
    val timestampMs: Long,
    val priceUsd: PriceUsd,
    val liquidityUsd: java.math.BigDecimal?,
)

object CanonicalPriceMarkRegistry6522 {
    private val marks = ConcurrentHashMap<String, CanonicalPriceMark6522>()

    fun publish(mark: CanonicalPriceMark6522): Boolean {
        if (mark.mint.isBlank() || mark.baseMint != mark.mint) return false
        if (mark.pairId.isBlank() || mark.pairId.startsWith("MINT_ROUTE:", true)) return false
        if (mark.quoteMint.isBlank() || mark.priceUsd.value.signum() <= 0 || mark.timestampMs <= 0L) return false
        marks.compute(mark.mint) { _, current -> if (current == null || mark.timestampMs >= current.timestampMs) mark else current }
        return marks[mark.mint] == mark
    }

    fun get(mint: String): CanonicalPriceMark6522? = marks[mint]
    internal fun resetForTest() = marks.clear()
}
