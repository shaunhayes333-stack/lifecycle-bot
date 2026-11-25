package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8979 — a doctrine position's card names the exit that fires (rider to 10x, the stop, the trail line), never the fluid
 * lock the doctrine positions do not use; any other lane shows what it showed before.
 */
class Aate8979DoctrineLockTextTest {
    private val d = RunnerDoctrine8584

    @Test fun aLaunchRiderBelowTenXShowsItsStopNotALock() {
        // superseded by V5.0.9011 — a rider's line arms at a 2x peak (was "rides to 10x")
        assertEquals("rides to 2x · SL -20%", d.lockText8979("MOONSHOT", rider = true, pnlPct = 19.3, peakPct = 65.0, stopPct = 20.0))
        assertEquals("rides to 2x · SL -15%", d.lockText8979("MOONSHOT", true, 19.3, 65.0, 20.0, laneStopPct = -15.0))
        assertTrue(d.lockText8979("MOONSHOT", true, 1_800.0, 1_900.0, 20.0)!!.startsWith("trail +"))
    }

    @Test fun anArmedDoctrinePositionShowsTheLineItsTrailSellsAt() {
        assertEquals("trail +23%", d.lockText8979("MOONSHOT", false, 19.3, 65.0, 25.0))   // 1.65 x 0.75 = 1.2375 (under half the gain)
        assertEquals("trail +32%", d.lockText8979("SHITCOIN", false, 30.0, 65.0, 15.0))   // half the gain: 1.325
        assertEquals("SL -25% · trail from +25%", d.lockText8979("MOONSHOT", false, 5.0, 10.0, 25.0))
    }

    @Test fun otherLanesKeepTheirCard() {
        assertNull(d.lockText8979("QUALITY", false, 19.3, 65.0, 10.0))
        assertNull(d.lockText8979("BLUECHIP", true, 19.3, 65.0, 10.0))
    }

    @Test fun wired() {
        val pop = File("src/main/kotlin/com/lifecyclebot/ui/MemePositionPopout.kt").readText()
        assertTrue(pop.contains("doctrine8979 != null -> doctrine8979"))
        val main = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue(main.contains("val slTxtR = holdLockText8080(ts) ?: doctrineR8979 ?: when {"))
        assertTrue(main.contains("if (doctrine8979 != null) \"TARGET \$peakTxt · \$doctrine8979\""))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 164. The card shows the exit that fires (8979)"))
    }
}
