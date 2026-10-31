package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitRegret7752
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8379 — owner option 1: launch-window stop; option 3: 5 / 15 / 60 min after-exit horizons. */
class Aate8379LaunchWindowTest {
    private val w = LaunchWindow8379
    private val min = 60_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun windowNeedsAKnownYoungBirth() {
        assertTrue(w.inWindow8379(5 * min, 8 * min, 3 * min))
        assertTrue("coin still under 30 min", w.inWindow8379(20 * min, 29 * min, 9 * min))
        assertTrue("first 10 min of a position bought young", w.inWindow8379(28 * min, 35 * min, 7 * min))
        assertFalse("bought young, coin aged out, held past 10 min", w.inWindow8379(28 * min, 45 * min, 17 * min))
        assertFalse("bought at 40 min old", w.inWindow8379(40 * min, 41 * min, 1 * min))
        assertFalse("unknown birth is never fresh", w.inWindow8379(null, null, 1 * min))
    }

    @Test fun ordinaryLossStopsAreHeldAboveTheFloor() {
        for (r in listOf("RAPID_CATASTROPHE_STOP", "RAPID_FLUID_STOP", "RAPID_HARD_FLOOR_STOP", "STRICT_SL_-8",
                "TICK_HARD_FLOOR_CATASTROPHIC_LANE_SHITCOIN_-22PCT_4588", "PROTECTIVE_EXIT_STOP_LOSS_6450_x", "STALE_FLAT_CULL_7353",
                "FALLBACK_SHITCOIN_TIME_EXIT", "CATASTROPHIC_HARD_BACKSTOP_-25")) {
            assertTrue(r, w.holds8379(r, -22.0, metricsCollapsed = false))
        }
    }

    @Test fun safetyFloorAndWinnersAreNeverHeld() {
        assertEquals("FLOOR", w.releaseCause8379("RAPID_CATASTROPHE_STOP", -35.0, false))
        assertEquals("METRICS_COLLAPSED", w.releaseCause8379("RAPID_FLUID_STOP", -12.0, true))
        assertEquals("PROFIT_SIDE", w.releaseCause8379("TAKE_PROFIT", 40.0, false))
        assertEquals("NO_PRICE", w.releaseCause8379("RAPID_FLUID_STOP", Double.NaN, false))
        for (r in listOf("RUG_DRAIN", "THIN_LIQ_EARLY_RUG_BACKSTOP_10", "HONEYPOT_DETECTED", "LIQUIDITY_COLLAPSE", "DEV_DUMP",
                "FREEZE_AUTH_ACTIVE", "DEAD_TOKEN_NO_PRICE_EXIT", "MANUAL", "KILL_SWITCH", "WALLET_DRAIN", "bot_shutdown",
                "EMERGENCY_LIQUIDATE", "OPPORTUNITY_ROTATION_8098_x", "LIFECYCLE_FLOOR_8065")) {
            assertNotNull(r, w.releaseCause8379(r, -20.0, false))
        }
        assertEquals("PROFIT_PROTECTION", w.releaseCause8379("TRAIL_STOP", -2.0, false))
    }

    @Test fun lanesAreTheLaunchLanes() {
        assertEquals(setOf("MOONSHOT", "SHITCOIN", "EXPRESS"), w.LANES_8379)
        assertEquals(35.0, w.FLOOR_PCT_8379, 0.0)
    }

    @Test fun bothSellDoorsAskTheWindow() {
        val exec = src("engine/Executor.kt")
        val core = exec.substringAfter("private fun requestSellCore7948(").substringBefore("RunnerGrab7967.deferSell7967")
        assertTrue(core.contains("LaunchWindow8379.defer8379(ts, reason)"))
        val low = exec.substringAfter("internal fun doSell(ts: TokenState, reason: String,").substringBefore("val paperCloseAuthorityActive")
        assertTrue(low.contains("LaunchWindow8379.defer8379(ts, reason)"))
        assertTrue("window after the lifecycle hold", low.indexOf("LifecycleHold8065.defer8065") < low.indexOf("LaunchWindow8379.defer8379"))
    }

    @Test fun afterExitHorizonsSplitTheCohorts() {
        assertEquals("LAUNCH_HELD", ExitRegret7752.cohort8379(5 * min, true))
        assertEquals("LAUNCH", ExitRegret7752.cohort8379(5 * min, false))
        assertEquals("OTHER", ExitRegret7752.cohort8379(45 * min, false))
        assertEquals("OTHER", ExitRegret7752.cohort8379(null, false))
        assertArrayEquals(intArrayOf(5, 15, 60), ExitRegret7752.HORIZONS_MIN_8379)
        val r = src("engine/truth/ExitRegret7752.kt")
        assertTrue(r.contains("trackHorizons8379(env, exitPx)"))
        assertTrue(r.contains("tickHorizons8379(priceFor, nowMs, unpriced)"))
        assertTrue("persisted", r.contains("LearningPersistence.save(PERSIST_KEY_8379"))
        assertTrue("reported", r.contains("afterExit8379: \${horizonLine8379()}"))
    }

    private val fr = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
    private val lad: (Double, Double) -> Double = { _, rest -> rest }

    @Test fun labelsGradeTheWindowAsLiveTradesIt() {
        // superseded (8480): the loss hold is on (its off-switch is removed), so labels grade the window live trades
        assertEquals(1_000L + 25 * min, fr.launchWindowEnd8379("SHITCOIN", 5 * min, 1_000L))
        assertEquals(1_000L + 10 * min, fr.launchWindowEnd8379("MOONSHOT", 25 * min, 1_000L))
        assertEquals(0L, fr.launchWindowEnd8379("QUALITY", 5 * min, 1_000L))
        assertEquals(0L, fr.launchWindowEnd8379("SHITCOIN", -1L, 1_000L))
        assertEquals(0L, fr.launchWindowEnd8379("SHITCOIN", 31 * min, 1_000L))
        // a -25% shakeout inside the window that recovers to +40%: not stopped (the lane's 15% stop would have sold it)
        assertEquals(40.0, fr.windowPlay8379(35.0, 15.0, 120.0, 500L, -25.0, -25.0, 40.0, Double.NaN, Double.NaN, Long.MAX_VALUE, lad), 1e-9)
        // through the window floor: stopped at its fill
        assertEquals(-38.0, fr.windowPlay8379(35.0, 15.0, 5.0, 500L, -38.0, -38.0, -50.0, -38.0, Double.NaN, Long.MAX_VALUE, lad), 1e-9)
        // after the window the lane stop binds again; a peak set after that stop was never reached
        assertEquals(-17.0, fr.windowPlay8379(35.0, 15.0, 80.0, 9_000L, -10.0, -17.0, 60.0, Double.NaN, -17.0, 5_000L, lad), 1e-9)
        assertEquals(-17.0, fr.windowPlay8379(35.0, 15.0, 80.0, 3_000L, -10.0, -17.0, 60.0, Double.NaN, -17.0, 5_000L, lad), 1e-9)
        val src = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(src.contains("playWindow8379(o, stop8029, gross, ladder8040)"))
        assertTrue("the label uses the live window's age rule", src.contains("CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs)"))
    }
}
