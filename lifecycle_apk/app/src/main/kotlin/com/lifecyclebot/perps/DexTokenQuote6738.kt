package com.lifecyclebot.perps

/** An indivisible provider observation; priceUsd always denotes the base token. */
data class DexTokenQuote6738(
    val chainId: String, val baseMint: String, val quoteMint: String,
    val pairId: String, val dexId: String, val priceUsd: Double,
    val liquidityUsd: Double, val marketCapUsd: Double, val observedAtMs: Long,
) {
    companion object {
        fun select(address: String, expectedChain: String?, rows: List<DexTokenQuote6738>): DexTokenQuote6738? {
            val candidates = rows.filter {
                it.baseMint == address && it.pairId.isNotBlank() && it.quoteMint.isNotBlank() &&
                    it.chainId.isNotBlank() && (expectedChain == null || it.chainId.equals(expectedChain, true)) &&
                    it.priceUsd.isFinite() && it.priceUsd > 0.0 &&
                    it.liquidityUsd.isFinite() && it.liquidityUsd >= 0.0 && it.observedAtMs > 0L
            }
            // An unqualified multi-chain address is ambiguous, not a cross-chain price oracle.
            if (candidates.map { it.chainId.lowercase() }.distinct().size != 1) return null
            return candidates.maxByOrNull { it.liquidityUsd }
        }
    }
}
