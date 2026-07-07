package com.lifecyclebot.perps.crypto

import com.lifecyclebot.perps.PerpsDirection

/**
 * V5.9.1159 — canonical Crypto Universe pre-FDG buy contract.
 *
 * Mirrors the Meme Trader final candidate contract at pipeline level, but uses
 * crypto-native identity (universe + chain + venue + assetKey + assetType) so
 * Crypto Universe cannot leak into Meme Trader symbol/mint state.
 */
data class CryptoFinalBuyCandidate(
    val universe: String = "CRYPTO",
    val assetKey: String,
    val symbol: String,
    val chain: String,
    val venue: String,
    val assetType: AssetType,
    val direction: PerpsDirection,
    val marketCapLane: MarketCapLane,
    val selectedLane: String,
    val selectedSpecialist: String,
    val preFdgVerdict: PreFdgVerdict,
    val score: Int,
    val confidence: Int,
    val safetyTier: String,
    val liquidityUsd: Double,
    val routeQuality: String,
    val sourceFamily: String = "CRYPTO_UNIVERSE",
    val venueFamily: String = "UNKNOWN",
    val routeTruthKey: String = "UNKNOWN",
    val strategyTruthKey: String = "UNKNOWN",
    val routeCostBps: Double = 0.0,
    val routeExpectancyMultiplier: Double = 1.0,
    val spread: Double,
    val slippageEstimate: Double,
    val hardNoReasons: List<String>,
    val softWarnings: List<String>,
    val finalSize: Double,
    val executionAdapter: String,
    val candidateVersion: Long,
) {
    enum class AssetType { SPOT, PERP, TOKENIZED, PAPER_ONLY }
    enum class PreFdgVerdict { BUY, WATCH, NO_BUY, HARD_NO_BUY }
    enum class MarketCapLane { MEGA_CAP, MAJOR, LARGE_CAP, MID_CAP, LOW_CAP, MICRO_CAP }

    val canEnterFdg: Boolean
        get() = preFdgVerdict == PreFdgVerdict.BUY && hardNoReasons.isEmpty()

    fun normalizedContext6148(): String =
        "sourceFamily=$sourceFamily venueFamily=$venueFamily routeTruth=$routeTruthKey strategyTruth=$strategyTruthKey routeCostBps=${"%.1f".format(routeCostBps)} routeCostMult=${"%.2f".format(routeExpectancyMultiplier)} chain=$chain venue=$venue specialist=$selectedSpecialist"
}
