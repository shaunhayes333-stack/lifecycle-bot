package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.QuietCurve8750
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8766 — code audit (data P0-1, P0-4, P0-5): labels carry what was read, at the horizon they were read at. */
class Aate8766LabelTruthTest {
    @Test fun a_quiet_curve_stands_only_on_its_own_account_read() {
        val t = 1_800_000_000_000L
        assertTrue(QuietCurve8750.accountConfirms8766(t, 1.0e-7, t + 20_000L, 1.004e-7))
        assertFalse("an account read before the print proves nothing", QuietCurve8750.accountConfirms8766(t, 1.0e-7, t - 1L, 1.0e-7))
        assertFalse("a different price: somebody traded", QuietCurve8750.accountConfirms8766(t, 1.0e-7, t + 20_000L, 1.05e-7))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/QuietCurve8750.kt").readText()
        assertTrue("the mark carries the read's own time", src.contains("return tp.first to acct.observedAtMs"))
        assertFalse(src.contains("return tp.first to nowMs"))
    }

    @Test fun an_hour_target_is_not_booked_into_the_cross_lane_five_minute_books() {
        val keys = listOf("CELL|MOONSHOT|MC_SMALL", "LANE|MOONSHOT", "SRC|PUMP_PORTAL", "AGG|ADMITTED")
        assertEquals("a 5-minute target is unchanged", keys, ForwardReturnLabeler7731.horizonScoped8766(keys, 60, 5L * 60_000L))
        assertEquals(listOf("CELL|MOONSHOT|MC_SMALL", "LANE|MOONSHOT", "SRC|PUMP_PORTAL@15M", "AGG|ADMITTED@15M"),
            ForwardReturnLabeler7731.horizonScoped8766(keys, 60, 15L * 60_000L))
        assertEquals("the 1-minute slot is one horizon already", keys, ForwardReturnLabeler7731.horizonScoped8766(keys, 15, 15L * 60_000L))
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("val keys = horizonScoped8766(keysOf7928(o), horizon, o.targetHorizon8521)"))
        assertTrue(lab.contains("o.mint, allLanes8766 = allLanes8766)"))
    }

    @Test fun a_large_cap_needs_a_witness_past_4x() {
        assertTrue(EdgeModel8537.plausible8617(50_000_000.0, 250.0))
        assertFalse("a $50 M coin does not 13x unwitnessed", EdgeModel8537.plausible8617(50_000_000.0, 1_200.0))
        assertTrue(EdgeModel8537.plausible8617(50_000_000.0, 1_200.0, chainWitness = true))
        assertTrue("the $2 M 11x runner stays", EdgeModel8537.plausible8617(2_000_000.0, 1_100.0))
        assertEquals(300.0, EdgeModel8537.LARGE_CAP_UNWITNESSED_PCT_8766, 0.0)
        assertTrue(EdgeModel8537.LARGE_CAP_USD_8766 > 2_000_000.0)
    }
}
