package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8066 — Stop keeps labelled lifecycle holds, lanes own coins by market cap, Helius has a hard budget. */
class Aate8066HoldsCapOwnersHeliusBudgetTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun capOwnershipMovesUpOnly() {
        assertEquals("MOONSHOT", LaneTransitionManager.capTransfer8066("SHITCOIN", 70_000.0, 20_000.0))
        assertEquals("QUALITY", LaneTransitionManager.capTransfer8066("MOONSHOT", 150_000.0, 60_000.0))
        assertEquals("BLUECHIP", LaneTransitionManager.capTransfer8066("QUALITY", 900_000.0, 120_000.0))
        assertNull(LaneTransitionManager.capTransfer8066("QUALITY", 105_000.0, 60_000.0))        // hysteresis
        assertNull(LaneTransitionManager.capTransfer8066("BLUECHIP", 60_000.0, 600_000.0))       // never down
        assertNull(LaneTransitionManager.capTransfer8066("CASHGEN", 10_000_000.0, 9_000_000.0))  // bought big, not grown
        assertNull(LaneTransitionManager.capTransfer8066("CASHGEN", 10_000_000.0, 0.0))          // unknown entry cap
        assertFalse(src("engine/LaneTransitionManager.kt").contains("pct_treasury_bank_compound"))
        assertFalse(RunnerExitProfile7277.refusesLaneChange("MOONSHOT", "QUALITY"))
    }

    @Test fun stopKeepsLabelledHolds() {
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("tokens = tokensCopy - kept8066.keys,"))
        assertTrue(b.contains("executor.liveSweepWalletTokens(wallet!!, effectiveBalance, kept8066.keys)"))
        assertTrue(b.contains("PositionPersistence.savePosition(k)"))
        assertTrue(src("ui/MainActivity.kt").contains("LifecycleHold8065.label8066(ts)"))
    }

    @Test fun heliusHardBudget() {
        assertFalse(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.EXECUTION))
        assertTrue(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.DECISION))
        assertTrue(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.ENRICHMENT))
        assertTrue(src("network/HeliusCreditMeterInterceptor7881.kt").contains("HeliusCreditEconomy7881.authorizeWire8397("))
        assertTrue(src("network/HeliusWebSocket.kt").contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 8"))
        assertTrue(src("network/CurveTicks7968.kt").contains("meterWs(\"CURVE_TICKS\""))
        assertTrue(src("network/GradTicks8018.kt").contains("meterWs(\"GRAD_TICKS\""))
    }
}
