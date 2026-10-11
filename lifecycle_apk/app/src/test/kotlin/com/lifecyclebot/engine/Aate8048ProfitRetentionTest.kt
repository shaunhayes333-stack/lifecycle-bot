package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8048 — give-back exits sell green; native refusals proven to refuse winners stand down; no invented curve depth. */
class Aate8048ProfitRetentionTest {
    @Test fun giveBackExitsAreNotHeld() {
        // MINERS: peak +15.8%, door floor ~6% -> a trail / lock / peak-capture exit protects what is left.
        assertTrue(NetEdge8030.giveBackReleased8048("PEAK_CAPTURE_TRAIL_6394_peak15_now3", 15.8, 6.0))
        assertTrue(NetEdge8030.giveBackReleased8048("TICK_PROFIT_LOCK_peak15_now3", 15.8, 6.0))
        // A first take-profit under cost is still a scalp: held.
        assertFalse(NetEdge8030.giveBackReleased8048("TAKE_PROFIT_TIER1", 15.8, 6.0))
        // A peak that never cleared the floor: the lock is still held.
        assertFalse(NetEdge8030.giveBackReleased8048("TICK_PROFIT_LOCK_peak4_now2", 4.0, 6.0))
    }

    @Test fun nativeSafetyRulesNeverRetire() {
        assertTrue(SpecialistBrainBridge7542.nativeRuleRetirable8048("NO_CONTINUATION: 1.2% < 3.0%"))
        assertFalse(SpecialistBrainBridge7542.nativeRuleRetirable8048("LIQ_TOO_LOW: \$167 < \$300"))
        assertFalse(SpecialistBrainBridge7542.nativeRuleRetirable8048("RUG_RISK high"))
    }

    @Test fun curveDepthFromCap() {
        // A fresh pump.fun curve (cap ~28 SOL) has ~0 SOL bought; ~34 SOL of cap is ~3 SOL bought.
        assertEquals(0.0, LiquidityTruth8037.curveRealSolFromCap8048(27.9), 0.05)
        assertEquals(3.0, LiquidityTruth8037.curveRealSolFromCap8048(33.83), 0.05)
        assertEquals(0.0, LiquidityTruth8037.curveRealSolFromCap8048(Double.NaN), 1e-9)
    }

    @Test fun wiring() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("NetEdge8030.exitFloorPct8030(com.lifecyclebot.engine.truth.NetEdge8030.costPct8030("))
        assertFalse(bs.contains("(mcapUsd * 0.85)"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(gate.contains("OnChainDepth8038.earlyLaunchRefusal8048(ts)"))
        val lease = File("src/main/kotlin/com/lifecyclebot/engine/sell/CloseLease.kt").readText()
        assertTrue(lease.contains("r.startsWith(\"TICK_PROFIT_LOCK\")"))
    }
}

/** V5.0.8058 — a peak past net break-even locks break-even even while the raw trail is not yet positive. */
class Aate8058BreakEvenLockTest {
    @Test fun peakLocksBreakEven() {
        // Hk5pde: peak +13%, cost 4.5% -> break-even 5.0%; raw trail -3% must not leave it unlocked.
        assertEquals(5.0, com.lifecyclebot.engine.truth.FieldManual7715.costArmedStop7928(-3.0, 13.0, 4.5) { -12.0 }, 1e-9)
        // Below the arm level the raw stop stands.
        assertEquals(-3.0, com.lifecyclebot.engine.truth.FieldManual7715.costArmedStop7928(-3.0, 4.0, 4.5) { -12.0 }, 1e-9)
        assertEquals(RejectTaxonomy.Category.HARD_SAFETY, RejectTaxonomy.classify("MAYHEM_MODE").category)
        assertEquals(RejectTaxonomy.Category.ADVISORY, RejectTaxonomy.classify("REBUY_LOCKOUT_8019").category)
    }
}
