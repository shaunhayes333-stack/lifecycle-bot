package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8769 — code audit (exits F4, F5, F6, F7, F8, F10, F11, F12, F13). */
class Aate8769ExitRepairsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()

    @Test fun the_doctrine_line_is_continuous_at_2x_for_every_stop() {
        for (stop in listOf(15.0, 25.0, 28.5, 40.0))
            assertEquals("stop $stop", RunnerDoctrine8584.lineWithStop8719(1.9999, stop), RunnerDoctrine8584.lineWithStop8719(2.0, stop), 0.001)
        assertEquals(2.75, RunnerDoctrine8584.lineWithStop8719(5.0, 40.0), 1e-9)
    }

    @Test fun an_oracle_print_is_not_this_bags_venue() {
        assertFalse(StopMarkConfirm8418.onBasis8418("DANGER_ZONE_FRESH", "PoolA", "PoolA"))
    }

    @Test fun a_refused_capture_slice_keeps_its_tier_and_a_restart_remembers_the_sold_share() {
        val m = MoonbagRunner7322
        assertEquals(0.25, m.nextCaptureFraction("r8769", "MOONSHOT", 150.0, 20.0)!!, 0.0)
        assertNull(m.nextCaptureFraction("r8769", "MOONSHOT", 160.0, 20.0))
        m.releaseCaptureTier8769("r8769")
        assertEquals("the refused slice is offered again", 0.25, m.nextCaptureFraction("r8769", "MOONSHOT", 160.0, 20.0)!!, 0.0)
        assertEquals(-1, m.seededTaken8769(0.0))
        assertEquals(0, m.seededTaken8769(25.0))
        assertEquals(1, m.seededTaken8769(43.75))
        assertNull("after a restart, a +150 % position that sold one slice does not sell it again",
            m.nextCaptureFraction("restart8769", "MOONSHOT", 150.0, 20.0, partialSoldPct = 25.0))
        val sold = TokenState(mint = "b8769", position = Position(qtyToken = 1.0, entryPrice = 1.0, entryTime = 1L, tradingMode = "MOONSHOT",
            partialSoldPct = 95.0, peakGainPct = 400.0))
        assertNotNull(m.bankedPeakFor8769(sold))
        assertNull(m.bankedPeakFor8769(sold.copy(position = sold.position.copy(partialSoldPct = 0.0))))
    }

    @Test fun the_wiring() {
        assertTrue(src("SpikeCapture7943.kt").contains("LifecycleHold8065.holding8065(ts) && !LifecycleHold8065.doctrineLadder8769(ts)"))
        val hold = src("LifecycleHold8065.kt")
        assertTrue(hold.contains("if (reason.startsWith(\"SPIKE_CAPTURE_7943\") && doctrineLadder8769(ts)) return false"))
        assertTrue(hold.contains("fresh8136 && !doctrineLadder8769(ts) && cumulativeTakeDue8079(g, st.taken) != null"))
        val ex = src("Executor.kt")
        val partial = ex.substringAfter("private fun requestPartialSellWithDelivery8527(").substringBefore("rehydratePositionFromSubTraders(ts)")
        assertTrue(partial.indexOf("if (requested8337 >= 1.0 - 1e-9) { fullExitFromPartialDoor8769(") in 0 until partial.indexOf("runnerDoctrineHolds8584(ts, reason, \"PARTIAL\")"))
        val floor = ex.substringAfter("val partialFloor6920 = LiveStrategyTuner.partialProfitFloorPct6920(\n                positionSizeSol")
        assertTrue("the -15 % collapse is reachable", floor.indexOf("PARTIAL_COLLAPSED_TO_RISK_EXIT") < floor.indexOf("PARTIAL_BLOCKED_BELOW_BREAKEVEN"))
        assertTrue(ex.contains("if (!terminal) resumedSlicePosted8769(ts, mint, sig, canon.positionId, soldRaw, proceedsSol, sliceCost)"))
        assertTrue(src("TradeVerifier.kt").contains("sigTrigger8769[sig] = reason to now"))
        val bs = src("BotService.kt")
        assertTrue(bs.contains("val runnerLockDeferred7277 = planTrailsRemainder7935(ts) || doctrineOwns8769"))
        assertTrue(bs.contains("if (pnlPct > laneFloor8743) continue"))
        assertTrue(bs.contains("requestSellOffLoop7288(ts, \"RAPID_SUB_TRADER_HARD_FLOOR\", wallet, effectiveBalance)"))
        assertTrue(bs.contains("requestSellOffLoop7288(ts, \"STALE_PRICE_RUG_ESCAPE\", wallet, effectiveBalance)"))
        assertTrue(bs.contains("requestSellOffLoop7288(ts, \"STALE_LIVE_PRICE_RUG_ESCAPE\", wallet, effectiveBalance)"))
        assertTrue(bs.contains("requestSellOffLoop7288(ts, \"ZOMBIE_CATASTROPHE_PENDING_RETRY\", wallet, effectiveBalance)"))
        assertTrue(bs.contains("MoonbagRunner7322.releaseCaptureTier8769("))
    }
}
