package com.lifecyclebot.engine

/** The learning door is independent of the live ownership/intent door. Pure so YES/NO parity is executable in tests. */
internal object NativeDecisionEnrollment8372 {
    data class Observation(val lane: String, val admitted: Boolean, val score: Int, val reason: String)
    const val CRYPTO_DESK_PRICE_SOURCE = "CRYPTO_ALT_DESK_7391"
    private val cryptoLanes = setOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP",
        "SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "CYCLIC", "MANIPULATED")

    fun isCryptoDesk(priceSource: String): Boolean = priceSource == CRYPTO_DESK_PRICE_SOURCE

    fun cryptoLearningLane(lane: String): String? = lane.trim().uppercase().removePrefix("CRYPTO_")
        .takeIf { it in cryptoLanes }?.let { "CRYPTO_$it" }

    /** Preserve chain identity: identical EVM addresses on different chains are different assets. */
    fun learningIdentity(assetId: String, symbol: String, routedMint: String?): String {
        val raw = if (assetId.startsWith("solana|", true)) assetId.substringAfter('|') else assetId
        return routedMint?.takeIf { it.isNotBlank() } ?: raw.ifBlank { symbol.uppercase() }
    }

    /** A metadata update or carried number is not a new price observation. */
    fun observedPriceAt(price: Double, observedPrice: Double, observedAtMs: Long, nowMs: Long,
                        maxAgeMs: Long = 120_000L): Long =
        observedAtMs.takeIf { price.isFinite() && price > 0.0 && observedPrice == price &&
            it > 0L && nowMs - it in 0L..maxAgeMs } ?: 0L

    fun rule(reason: String): String =
        reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").split('_')
            .takeWhile { w -> w.isNotEmpty() && w.none { it.isDigit() } }.take(4).joinToString("_").ifBlank { "UNKNOWN" }

    fun observation(lane: String, eligible: Boolean, gradeable: Boolean, score: Int, reason: String): Observation? {
        if (!gradeable || lane.isBlank()) return null
        return Observation(lane.trim().uppercase(), eligible, score,
            "NATIVE_${if (eligible) "READY" else rule(reason)}")
    }

    /** Native READY describes a prediction; only downstream admission may stamp a fill/chase reference price. */
    fun executionAdmission(admitted: Boolean, reason: String?): Boolean =
        admitted && reason?.startsWith("NATIVE_", ignoreCase = true) != true

    fun bindExecutionWithoutReplacingObservation(admitted: Boolean, reason: String?, priorReason: String?): Boolean =
        executionAdmission(admitted, reason) && priorReason?.startsWith("NATIVE_", ignoreCase = true) == true
}
