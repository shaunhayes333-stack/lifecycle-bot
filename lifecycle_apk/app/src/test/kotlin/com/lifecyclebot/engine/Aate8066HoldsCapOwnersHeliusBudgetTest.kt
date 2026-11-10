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
        // 8521: live liquidation has its own helper; every unresolved row is persisted after it returns.
        val liveStart = b.indexOf("private fun closeLivePositionsOnStop8521()")
        val stopStart = b.indexOf("fun stopBot(", liveStart)
        assertTrue(liveStart >= 0 && stopStart > liveStart)
        val live = b.substring(liveStart, stopStart)
        assertTrue(live.contains(".filterValues { it.position.isOpen && !it.position.isPaperPosition }"))
        assertTrue(live.contains("val kept = tokens.filterValues { LifecycleHold8065.keepOnStop8066(it) }"))
        assertTrue(live.contains("executor.closeAllPositions(tokens - kept.keys, wallet, balance, paperMode = false)"))
        assertTrue(live.contains("executor.liveSweepWalletTokens(w, balance, kept.keys)"))
        val stop = b.substring(stopStart)
        val closeAt = stop.indexOf("closeLivePositionsOnStop8521()")
        val saveAt = stop.indexOf("PositionPersistence.saveAllPositions(remaining, force = true)")
        assertTrue("retained holds must be saved after liquidation", closeAt >= 0 && saveAt > closeAt)
        assertTrue(stop.substring(closeAt, saveAt).contains("val remaining = synchronized(status.tokens) { status.tokens.toMap() }"))
        assertTrue(src("ui/MainActivity.kt").contains("LifecycleHold8065.label8066(ts)"))
    }

    @Test fun heliusHardBudget() {
        assertFalse(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.EXECUTION))
        assertTrue(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.DECISION))
        assertTrue(HeliusCreditEconomy7881.budgetGated8066(HeliusCreditEconomy7881.Tier.ENRICHMENT))
        assertTrue(src("network/HeliusCreditMeterInterceptor7881.kt").contains("HeliusCreditEconomy7881.authorizeWire8397("))
        assertTrue(src("network/HeliusWebSocket.kt").contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 16"))  // 8666: adaptive 8..16, starts at 12
        assertTrue(src("network/CurveTicks7968.kt").contains("meterWs(\"CURVE_TICKS\""))
        assertTrue(src("network/GradTicks8018.kt").contains("meterWs(\"GRAD_TICKS\""))
    }
}
