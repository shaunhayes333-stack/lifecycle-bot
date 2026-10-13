package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8080 — a peak profit lock is never swallowed by a hold that has no give-back exit of its own. */
class Aate8080PeakLockTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun unbankedLifecycleHoldGivesBackAtHalfItsPeak() {
        // Bear 5.0.8080: peak +2872%, take never confirmed, now +9.65% -> must exit
        assertTrue(LifecycleHold8065.unbankedGiveback8080(2872.0, 9.65, 0))
        assertTrue(LifecycleHold8065.givebackDue8080(2872.0, 9.65, 0))
        assertTrue(LifecycleHold8065.unbankedGiveback8080(120.0, 55.0, 0))
        assertFalse(LifecycleHold8065.unbankedGiveback8080(120.0, 80.0, 0))     // still above half the peak
        assertFalse(LifecycleHold8065.unbankedGiveback8080(2872.0, 9.65, 1))    // banked: free-ride rules own it
        assertFalse(LifecycleHold8065.unbankedGiveback8080(80.0, 10.0, 0))      // never promoted-sized peak
        // banked hold still guarded by the multiple give-back
        assertTrue(LifecycleHold8065.givebackDue8080(2872.0, 9.65, 1))
        assertEquals(1436.0, LifecycleHold8065.givebackLevel8080(2872.0, 0)!!, 1e-6)
        assertEquals(1237.4, LifecycleHold8065.givebackLevel8080(2872.0, 1)!!, 0.1)
        assertNull(LifecycleHold8065.givebackLevel8080(150.0, 1))
    }

    @Test fun holdReleasesTheLockAndOwnsAnUnbankedExit() {
        val l = src("engine/LifecycleHold8065.kt")
        val defer = l.substringAfter("fun defer8065(").substringBefore("fun partialHeld8065(")
        assertTrue(defer.contains("givebackDue8080("))
        assertTrue(defer.contains("LIFECYCLE_GIVEBACK_RELEASED_8080"))
        val mark = l.substringAfter("fun onMark8065(").substringBefore("fun onConfirmedTake8076(")
        assertTrue(mark.contains("st.taken == 0 && givebackDue8080(st.peak, g, st.taken) ->"))
        assertTrue(mark.contains("LIFECYCLE_STOP_UNBANKED_GIVEBACK_8065_"))
    }

    @Test fun greenRunnerHoldNeedsTapeToDeferAGiveBack() {
        assertTrue(RunnerGrab7967.giveBackExit8080("TICK_PROFIT_LOCK_peak2872_now9"))
        assertTrue(RunnerGrab7967.giveBackExit8080("RAPID_TRAILING_STOP"))
        assertFalse(RunnerGrab7967.giveBackExit8080("FALLBACK_SHITCOIN_TIME_EXIT"))
        val g = src("engine/RunnerGrab7967.kt").substringAfter("fun deferSell7967(").substringBefore("private val peaks7980")
        assertTrue(g.contains("if (g >= 0.0 && giveBackExit8080(reason) && !runStanding8019(r60) && !runStanding8019(r15))"))
    }

    @Test fun cardsShowTheLockThatWillFire() {
        assertTrue(src("ui/MemePositionPopout.kt").contains("LifecycleHold8065.displayLock8080(t)"))
        assertEquals(2, src("ui/MainActivity.kt").split("LifecycleHold8065.displayLock8080(ts)").size - 1)
    }
}
