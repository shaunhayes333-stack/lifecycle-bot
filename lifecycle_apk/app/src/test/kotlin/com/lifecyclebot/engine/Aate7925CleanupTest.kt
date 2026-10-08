package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.StopAuthority7887
import com.lifecyclebot.engine.truth.ExitRegret7752
import com.lifecyclebot.engine.truth.FreshLaunchSelector7737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7925 — double scoring, inversions and blockers from the 5.0.7914 audit. */
class Aate7925CleanupTest {
    @Test fun stopMultiplierReadsStopExitsOnly() {
        assertTrue(ExitRegret7752.isStopFamily7925("HARD_STOP"))
        assertTrue(ExitRegret7752.isStopFamily7925("STRICT_SL"))
        assertTrue(ExitRegret7752.isStopFamily7925("TICK_FLOOR"))
        assertTrue(ExitRegret7752.isStopFamily7925("RAPID_CATASTROPHE_STOP"))
        assertFalse(ExitRegret7752.isStopFamily7925("TICK_PROFIT_LOCK"))
        assertFalse(ExitRegret7752.isStopFamily7925("TRAILING_TAKE_PROFIT"))
    }

    @Test fun runnerFloorHoldsForPlanStops() {
        assertEquals(15.0, StopAuthority7887.compose(-5.0, 20.0, 1.0, runnerLane = true), 1e-9)
        assertEquals(9.0, StopAuthority7887.compose(-9.0, 12.0, 1.5, runnerLane = false), 1e-9)
    }

    @Test fun aSecondsOldLaunchIsNotAOneWalletPump() {
        assertEquals("CONC_EARLY", FreshLaunchSelector7737.concentrationBucket(100.0, 1))
        assertEquals("CONC_ONE", FreshLaunchSelector7737.concentrationBucket(70.0, 2))
        val strong = FreshLaunchSelector7737.Cell().apply { n = 18; tpFirst = 12; stopFirst = 6 }
        assertTrue(FreshLaunchSelector7737.cellOverturns(strong))
        val weak = FreshLaunchSelector7737.Cell().apply { n = 18; tpFirst = 8; stopFirst = 6 }
        assertFalse(FreshLaunchSelector7737.cellOverturns(weak))
    }

    @Test fun learnedRefusalsAreTrainable() {
        val c = RejectTaxonomy.classify("SELECTION_QUALITY_FLOOR_6830 lane=SHITCOIN conf=35% floor=38.1", null)
        assertEquals(RejectTaxonomy.Category.ADVISORY, c.category)
        assertTrue(c.trainable)
        assertTrue(RejectTaxonomy.classify("PLAYBOOK_NO_TRIGGER_7907_SHITCOIN", null).trainable)
        // Hard rejects stay hard.
        assertTrue(RejectTaxonomy.classify("CONFIRMED_RUG", null).hardSafety)
        assertTrue(RejectTaxonomy.classify("LOW_CONFIDENCE", TradeAuthorizer.BlockLevel.HARD).hardSafety)
    }
}
