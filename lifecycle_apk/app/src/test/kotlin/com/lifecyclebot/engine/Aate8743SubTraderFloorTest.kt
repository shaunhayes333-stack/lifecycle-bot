package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8743 — the sub-trader sweep stops at the lane's floor with the rapid monitor's confirmation, not a flat -15 %. */
class Aate8743SubTraderFloorTest {
    @Test fun theSweepUsesTheLaneFloorAndConfirmation() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val sweep = bs.substringAfter("V5.9.721 — SUB-TRADER EMERGENCY SL SWEEP.").substringBefore("Sub-trader rapid SL sweep error")
        assertTrue(sweep.contains("ExitDoctrine8169.rapidHardFloorMag8169(ts)"))
        assertTrue(sweep.contains("StopMarkConfirm8418.executableConfirms8419(ts, laneFloor8743)"))
        assertTrue(sweep.contains("if (pnlPct <= laneFloor8743 && confirmed8743) {"))
        assertTrue(!sweep.contains("if (pnlPct <= subFloor) {"))
    }
}
