package com.lifecyclebot.engine.cortex

/** Learning provenance only. Resolving this seal cannot grant an order or change the canonical execution owner. */
internal object CrossAssetLearningSeal8372 {
    data class Seal(val mint: String, val mode: String, val candidateVersion: Long, val assetClass: String,
                    val lane: String, val createdAtMs: Long, val direction: String,
                    val learningIdentity: String = "", val attemptId: String = "", val symbol: String = "")
    data class ConfirmedFill(val positionId: String, val targetMint: String, val signature: String,
        val route: String, val proofState: String, val positiveQuantity: Boolean, val positivePrincipal: Boolean,
        val flashPositionKey: String, val attemptId: String, val candidateVersion: Long, val symbol: String)
    data class ConfirmedPosition(val positionId: String, val mint: String, val signature: String,
        val mode: String, val assetClass: String, val symbol: String, val openedAtMs: Long)
    data class Binding(val canonicalMint: String, val learningIdentity: String, val lane: String)
    private val cryptoLanes = setOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP",
        "SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "CYCLIC", "MANIPULATED", "ALT")

    private fun identity(raw: String): String =
        if (raw.startsWith("solana|", true)) raw.substringAfter('|') else raw

    private fun laneMatches(assetClass: String, lane: String): Boolean = when (assetClass) {
        "CRYPTO_ALT" -> lane.startsWith("CRYPTO_") && lane.removePrefix("CRYPTO_") in cryptoLanes
        "STOCK" -> lane == "MKT_STOCK"
        "FOREX" -> lane == "MKT_FOREX"
        "COMMODITY" -> lane == "MKT_COMMODITY"
        "METAL" -> lane == "MKT_METAL"
        "PERPS" -> lane == "MKT_PERPS"
        else -> false
    }

    /**
     * A supplied candidate version must match exactly. Legacy opens without that argument may use only ONE matching
     * immutable pending seal; ambiguity is a missing learning binding, never an arbitrary latest-version selection.
     * SHORT remains unsupported by the long-only cross-asset observation bridge and is not mislabeled as LONG.
     */
    fun resolve(mint: String, mode: String, assetClass: String, candidateVersion: Long, openedAtMs: Long,
                seals: Collection<Seal>): String? {
        val m = mode.uppercase()
        if (mint.isBlank() || m !in setOf("LIVE", "PAPER") || openedAtMs <= 0L) return null
        return seals.filter { s ->
            s.candidateVersion > 0L && (candidateVersion <= 0L || s.candidateVersion == candidateVersion) &&
                identity(s.mint) == identity(mint) && s.mode.uppercase() == m && s.assetClass == assetClass &&
                s.createdAtMs > 0L && openedAtMs - s.createdAtMs in 0L..900_000L &&
                s.direction.uppercase() in setOf("LONG", "BUY") && laneMatches(assetClass, s.lane)
        }.singleOrNull()?.lane
    }

    /** An alias is proved by this exact sealed order's confirmed fill, not by matching a ticker in a registry. */
    fun confirmedMarketBinding(seal: Seal, fill: ConfirmedFill, p: ConfirmedPosition): Binding? {
        if (seal.assetClass !in setOf("STOCK", "FOREX", "COMMODITY", "METAL", "PERPS")) return null
        if (seal.mode.uppercase() != "LIVE" || p.mode.uppercase() != "LIVE" || p.assetClass != seal.assetClass ||
            seal.candidateVersion <= 0L || seal.candidateVersion != fill.candidateVersion ||
            seal.attemptId.isBlank() || seal.attemptId != fill.attemptId || seal.learningIdentity.isBlank() ||
            seal.symbol.isBlank() || !seal.symbol.equals(fill.symbol, true) || !seal.symbol.equals(p.symbol, true) ||
            seal.direction.uppercase() !in setOf("LONG", "BUY") || !laneMatches(seal.assetClass, seal.lane) ||
            seal.createdAtMs <= 0L || p.openedAtMs - seal.createdAtMs !in 0L..900_000L) return null
        if (fill.positionId.isBlank() || fill.positionId != p.positionId || fill.targetMint.isBlank() ||
            identity(fill.targetMint) != identity(p.mint) || fill.signature.isBlank() || fill.signature != p.signature ||
            !fill.positiveQuantity || !fill.positivePrincipal) return null
        val proved = when (fill.route) {
            "SPOT" -> fill.proofState in setOf("TX_META_TARGET_DELTA", "WALLET_TARGET_DELTA")
            "FLASH_PERPS" -> seal.assetClass == "PERPS" && fill.proofState == "FLASH_POSITION_ACCOUNT_CONFIRMED" &&
                fill.flashPositionKey.isNotBlank()
            else -> false
        }
        return if (proved) Binding(p.mint, seal.learningIdentity, seal.lane) else null
    }
}
