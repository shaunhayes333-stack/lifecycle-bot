package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8980 — a crypto candidate's hour is its pool's provider-dated h1 when it is fresh; a stale or missing h1 writes
 * nothing (the desk candles alone, as before); the crypto lanes' defer causes are named on their own.
 */
class Aate8980CryptoHourTest {
    private val now = 1_800_000_000_000L

    private fun token(mint: String, h1: Double, at: Long) = DynamicAltTokenRegistry.DynToken(
        mint = mint, tokenAddress = mint, chainId = "solana", symbol = "HR", name = "HR",
        price = 2.0, priceObservedAtMs8372 = now - 2_000L,
        change1hPct8886 = h1, trendObservedAtMs8886 = at,
    )

    @Test fun aFreshProviderHourIsTheCandidatesHour() {
        val mint = "Aate898HourAAAAAAAAAAAAAAAAAAAAAA"
        val state = TokenState(mint = mint, symbol = "HR", name = "HR")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(state, token(mint, 4.2, now - 60_000L), 2.0, 50_000.0, 900_000.0, now)
        assertEquals(4.2, state.lastPriceChange1h, 1e-9)
        assertEquals(now - 60_000L, state.hourChangeObservedAtMs8506)
        assertEquals(TokenFacts.Rank.PROVIDER, TokenFacts.snapshotFact(state, TokenFacts.Field.CHANGE_1H_PCT)?.rank)
        assertTrue(CrossAssetCortex7931.hourLine8980().startsWith("cryptoHour8980[bridged="))
    }

    @Test fun aStaleOrMissingHourWritesNothing() {
        val stale = TokenState(mint = "Aate898HourBBBBBBBBBBBBBBBBBBBBBB", symbol = "HR", name = "HR")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(stale, token(stale.mint, 4.2, now - 20L * 60_000L), 2.0, 50_000.0, 900_000.0, now)
        assertNull(TokenFacts.snapshotFact(stale, TokenFacts.Field.CHANGE_1H_PCT))
        assertEquals(0L, stale.hourChangeObservedAtMs8506)
        val none = TokenState(mint = "Aate898HourCCCCCCCCCCCCCCCCCCCCCC", symbol = "HR", name = "HR")
        CrossAssetCortex7931.bridgeCryptoObservedContext8834(none, token(none.mint, Double.NaN, 0L), 2.0, 50_000.0, 900_000.0, now)
        assertNull(TokenFacts.snapshotFact(none, TokenFacts.Field.CHANGE_1H_PCT))
    }

    @Test fun theReadableRule() {
        val c = CrossAssetCortex7931
        assertTrue(c.cryptoHourReadable8980(-3.0, now - 60_000L, now))
        assertFalse(c.cryptoHourReadable8980(Double.NaN, now - 60_000L, now))
        assertFalse(c.cryptoHourReadable8980(-100.0, now - 60_000L, now))
        assertFalse(c.cryptoHourReadable8980(2.0, 0L, now))
        assertFalse(c.cryptoHourReadable8980(2.0, now + 60_000L, now))
        assertFalse(c.cryptoHourReadable8980(2.0, now - 16L * 60_000L, now))
    }

    @Test fun cryptoDeferCausesAreNamedOnTheirOwn() {
        val out = CandidateAdmission8136.cryptoDeferCauses8980(mapOf(
            "MOONSHOT|PRICE_STALE" to 90L, "CRYPTO_MOONSHOT|MOVE_HISTORY_SPARSE" to 40L,
            "CRYPTO_CORE|PRICE_STALE" to 7L, "EVM_BSC|MOVE_UNREAD" to 3L))
        assertEquals(listOf("CRYPTO_MOONSHOT|MOVE_HISTORY_SPARSE" to 40L, "CRYPTO_CORE|PRICE_STALE" to 7L, "EVM_BSC|MOVE_UNREAD" to 3L), out)
    }

    @Test fun wired() {
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("CrossAssetCortex7931.hourLine8980()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText().contains("\" crypto8980{\""))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 165. A crypto coin's hour is its pool's hour (8980)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 166. 8980's test on a real mint (8981)"))
    }
}
