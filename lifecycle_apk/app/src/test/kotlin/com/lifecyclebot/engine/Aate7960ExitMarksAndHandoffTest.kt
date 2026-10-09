package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419
import com.lifecyclebot.engine.truth.RiskClockMark7960
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7960 — stops always have a mark, SPL alts are priced, sealed entries revalidate, refused primaries hand off. */
class Aate7960ExitMarksAndHandoffTest {
    private fun src(rel: String): String {
        val roots = listOf("src/main/kotlin/com/lifecyclebot/engine", "app/src/main/kotlin/com/lifecyclebot/engine", "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    @Test fun riskClockFallsBackToAFreshRuntimePrice() {
        val now = 1_000_000L
        assertEquals(0.9, RiskClockMark7960.runtimeRiskMark7960(0.9, now - 3_000L, 1.0, now)!!, 1e-12)
        assertNull(RiskClockMark7960.runtimeRiskMark7960(0.9, now - 16_000L, 1.0, now))      // stale
        assertNull(RiskClockMark7960.runtimeRiskMark7960(150.0, now - 1_000L, 1.0, now))     // unit mix-up (SOL vs USD)
        assertNull(RiskClockMark7960.runtimeRiskMark7960(0.9, now - 1_000L, 0.0, now))       // no entry
        assertNull(RiskClockMark7960.runtimeRiskMark7960(Double.NaN, now, 1.0, now))
        // The 5.0.7958 dark positions: -42% on a live tape is a mark the stop can act on.
        assertEquals(0.58, RiskClockMark7960.runtimeRiskMark7960(0.58, now - 500L, 1.0, now)!!, 1e-12)
    }

    @Test fun riskClockUsesTheFallback() {
        val bot = src("BotService.kt")
        assertTrue(bot.contains("val rm7960 = riskClockMark7960(mint, ts6882, now7545)"))
        assertTrue(bot.contains("RISK_CLOCK_RUNTIME_MARK_FALLBACK_7960"))
    }

    @Test fun splAltPositionsArePricedFromSolanaVenues() {
        assertTrue(HeldHotMarkAuthority7419.solanaPricedClass7960(AssetClass.SOLANA_TOKEN, "anything"))
        assertTrue(HeldHotMarkAuthority7419.solanaPricedClass7960(AssetClass.CRYPTO_ALT, "7dCkdoXyZ1111111111111111111111111111111pump"))
        assertFalse(HeldHotMarkAuthority7419.solanaPricedClass7960(AssetClass.CRYPTO_ALT, "BTC"))
        assertFalse(HeldHotMarkAuthority7419.solanaPricedClass7960(AssetClass.CRYPTO_ALT, "binance|ETHUSDT"))
        assertFalse(HeldHotMarkAuthority7419.solanaPricedClass7960(AssetClass.STOCK, "AAPL"))
    }

    @Test fun sealedEntryRevalidatesOnAFreshRuntimePrice() {
        val now = 5_000_000L
        assertTrue(SealedEntryContinuity7863.runtimeUsable7960(1.0, 1.10, now - 5_000L, now))
        assertFalse(SealedEntryContinuity7863.runtimeUsable7960(1.0, 1.30, now - 5_000L, now))   // moved > 15%
        assertFalse(SealedEntryContinuity7863.runtimeUsable7960(1.0, 1.0, now - SealedEntryContinuity7863.RUNTIME_MAX_AGE_MS_7960 - 1, now))
        assertFalse(SealedEntryContinuity7863.runtimeUsable7960(1.0, 1.0, 0L, now))
    }

    @Test fun aRefusedPrimaryHandsTheTokenOn() {
        SpecialistOwnership7951.resetForTests7951()
        val now = 10_000_000L
        assertFalse(SpecialistOwnership7951.primaryRefused7960("MINT1", "CASHGEN", now))
        SpecialistOwnership7951.noteLaneRefused7960("MINT1", "CASHGEN", now)
        assertTrue(SpecialistOwnership7951.primaryRefused7960("MINT1", "CASHGEN", now + 30_000L))
        assertFalse(SpecialistOwnership7951.primaryRefused7960("MINT1", "CASHGEN", now + 91_000L))
        assertFalse(SpecialistOwnership7951.primaryRefused7960("MINT2", "CASHGEN", now))
        // The primary itself is never "handed off" to.
        assertFalse(SpecialistOwnership7951.handOffAllowed7960("CASHGEN", "MINT1", "CASHGEN", now))
        SpecialistOwnership7951.resetForTests7951()
        assertTrue(src("BotService.kt").contains("SpecialistOwnership7951.handOffAllowed7960(l, ts.mint, primaryLane)"))
    }

    @Test fun anOpenRowCoversItsWholeWalletBalance() {
        val gate = src("sell/LiveBuyAdmissionGate.kt")
        assertTrue(gate.contains("if (mint in openRowMints7960) return@filter false"))
    }

    @Test fun exitCortexPendingSurvivesRestart() {
        val ex = src("cortex/CortexExit7897.kt")
        assertTrue(ex.contains(".put(\"pending7960\", encodePending7960())"))
        assertTrue(ex.contains("o.optJSONArray(\"pending7960\")?.let { decodePending7960(it, System.currentTimeMillis()) }"))
    }
}
