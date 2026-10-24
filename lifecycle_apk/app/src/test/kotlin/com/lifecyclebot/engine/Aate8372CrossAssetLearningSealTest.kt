package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetLearningSeal8372
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8372CrossAssetLearningSealTest {
    @Test fun unresolvedCrossAssetSealCannotFallBackToGuessedLane() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val binding = src.substringAfter("private fun bindOpenLearning7863").substringBefore("fun promotePendingToOpen")
        assertTrue(binding.contains("p.assetClass == AssetClass.SOLANA_TOKEN"))
        assertTrue(binding.contains("learningLaneForOpen8372"))
        assertTrue(binding.contains("if (cortexLane8076 != null)"))
        assertFalse(binding.contains("laneFor8107"))
        assertFalse(binding.contains("laneFor(p.assetClass)"))
    }
    private fun seal(version: Long = 7L, lane: String = "CRYPTO_EXPRESS", mint: String = "solana|mintA",
                     mode: String = "LIVE", asset: String = "CRYPTO_ALT", direction: String = "LONG") =
        CrossAssetLearningSeal8372.Seal(mint, mode, version, asset, lane, 1000L, direction)
    private fun resolve(seals: List<CrossAssetLearningSeal8372.Seal>, version: Long = 7L,
                        mint: String = "mintA", mode: String = "LIVE", asset: String = "CRYPTO_ALT", at: Long = 2000L) =
        CrossAssetLearningSeal8372.resolve(mint, mode, asset, version, at, seals)

    @Test fun allCryptoDeskOwnersBindTheirOwnLearningLaneNotGenericAlt() {
        for (desk in listOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP",
            "SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "CYCLIC", "MANIPULATED")) {
            assertEquals("CRYPTO_$desk", resolve(listOf(seal(lane = "CRYPTO_$desk"))))
        }
    }

    @Test fun versionMintModeClassDirectionAndTimeAreExact() {
        assertNull(resolve(listOf(seal(version = 8))))
        assertNull(resolve(listOf(seal(mint = "mintB"))))
        assertNull(resolve(listOf(seal(mode = "PAPER"))))
        assertNull(resolve(listOf(seal(asset = "STOCK"))))
        assertNull(resolve(listOf(seal(direction = "SHORT"))))
        assertNull(resolve(listOf(seal()), at = 999L))
        assertNull(resolve(listOf(seal()), at = 902_000L))
        assertNull(resolve(listOf(seal(lane = "EXPRESS"))))
        assertNull(resolve(listOf(seal(version = 0))))
    }

    @Test fun anUnknownLegacyVersionNeverSelectsAnArbitraryLatestIntent() {
        assertEquals("CRYPTO_EXPRESS", resolve(listOf(seal()), version = 0L))
        assertNull(resolve(listOf(seal(), seal(version = 8, lane = "CRYPTO_QUALITY")), version = 0L))
        assertEquals("CRYPTO_EXPRESS", resolve(listOf(seal(), seal(version = 8, lane = "CRYPTO_QUALITY"))))
    }

    @Test fun evmChainsAreNotStrippedIntoTheSameAssetIdentity() {
        assertNull(resolve(listOf(seal(mint = "eth|0x123")), mint = "bsc|0x123"))
        assertEquals("CRYPTO_EXPRESS", resolve(listOf(seal(mint = "eth|0x123")), mint = "eth|0x123"))
    }

    private val marketSeal = CrossAssetLearningSeal8372.Seal("AAPL", "LIVE", 7L, "STOCK", "MKT_STOCK",
        1000L, "LONG", "original-quote-key", "attempt-7", "AAPL")
    private val marketFill = CrossAssetLearningSeal8372.ConfirmedFill("position-7", "confirmed-spl-mint", "signature-7",
        "SPOT", "TX_META_TARGET_DELTA", true, true, "", "attempt-7", 7L, "AAPL")
    private val marketPosition = CrossAssetLearningSeal8372.ConfirmedPosition("position-7", "confirmed-spl-mint", "signature-7",
        "LIVE", "STOCK", "AAPL", 2000L)

    @Test fun confirmedSameOrderAliasLooksUpOriginalDecisionButBindsCanonicalMint() {
        val b = CrossAssetLearningSeal8372.confirmedMarketBinding(marketSeal, marketFill, marketPosition)!!
        assertEquals("confirmed-spl-mint", b.canonicalMint)
        assertEquals("original-quote-key", b.learningIdentity)
        assertEquals("MKT_STOCK", b.lane)
        // No inferred ticker->mint mapping can pass the ordinary exact-identity resolver.
        assertNull(CrossAssetLearningSeal8372.resolve(marketPosition.mint, "LIVE", "STOCK", 7L, 2000L, listOf(marketSeal)))
    }

    @Test fun wrongTickerVersionSignatureMintOrUnprovedFillCannotAlias() {
        fun rejects(fill: CrossAssetLearningSeal8372.ConfirmedFill) =
            assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(marketSeal, fill, marketPosition))
        rejects(marketFill.copy(symbol = "MSFT"))
        rejects(marketFill.copy(candidateVersion = 8L))
        rejects(marketFill.copy(attemptId = "another-attempt"))
        rejects(marketFill.copy(targetMint = "different-spl-mint"))
        rejects(marketFill.copy(signature = "another-signature"))
        rejects(marketFill.copy(positionId = "another-position"))
        rejects(marketFill.copy(proofState = "SIGNATURE_ONLY_PENDING"))
        rejects(marketFill.copy(positiveQuantity = false))
        rejects(marketFill.copy(positivePrincipal = false))
        assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(marketSeal.copy(direction = "SHORT"), marketFill, marketPosition))
        assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(marketSeal, marketFill, marketPosition.copy(mode = "PAPER")))
        assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(marketSeal, marketFill, marketPosition.copy(symbol = "MSFT")))
    }

    @Test fun flashAliasNeedsItsSameConfirmedPositionAccount() {
        val s = marketSeal.copy(mint = "BTC", assetClass = "PERPS", lane = "MKT_PERPS", symbol = "BTC", learningIdentity = "BTC")
        val f = marketFill.copy(targetMint = "flash:BTC:LONG", route = "FLASH_PERPS", symbol = "BTC",
            proofState = "FLASH_POSITION_ACCOUNT_CONFIRMED", flashPositionKey = "confirmed-position-account")
        val p = marketPosition.copy(mint = "flash:BTC:LONG", assetClass = "PERPS", symbol = "BTC")
        assertEquals("BTC", CrossAssetLearningSeal8372.confirmedMarketBinding(s, f, p)!!.learningIdentity)
        assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(s, f.copy(flashPositionKey = ""), p))
        assertNull(CrossAssetLearningSeal8372.confirmedMarketBinding(s, f.copy(targetMint = "flash:BTC:SHORT"), p))
    }
}
