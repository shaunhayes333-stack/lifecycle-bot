package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ScalpExit8351
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8984 — the shadow's SCALP choice never moves a LIVE position onto the scalp ladder: it is counted, the position keeps
 * the lane doctrine. The ladder itself is unchanged (a position already on it still sells on it, the paper book still uses it).
 */
class Aate8984ShadowExitAdvisoryTest {
    @Test fun theShadowsChoiceDoesNotBindLive() {
        assertFalse(ScalpExit8351.liveScalp8984(true))
        assertFalse(ScalpExit8351.liveScalp8984(false))
    }

    @Test fun aFreshLivePositionIsNotOnTheLadder() {
        val now = System.currentTimeMillis()
        val ts = com.lifecyclebot.data.TokenState(mint = "Aate8984Scap${System.nanoTime()}", position = com.lifecyclebot.data.Position(
            qtyToken = 1000.0, entryPrice = 100.0, entryTime = now - 120_000L, tradingMode = "MOONSHOT", isPaperPosition = false))
        val offered = ArrayList<String>()
        ScalpExit8351.offer8351(ts, 145.0, now) { _, _, why -> offered += why }   // +45 %: the ladder would have sold it all
        ScalpExit8351.offer8351(ts, 80.0, now + 1_000L) { _, _, why -> offered += why }   // -20 %: the ladder's hard stop
        assertEquals(emptyList<String>(), offered)
    }

    @Test fun theLadderIsUnchanged() {
        assertEquals(0.5 to "SCALP_TAKE_PROFIT_8351_T1", ScalpExit8351.decide8351(21.0, 21.0, 5_000L, 0, 15.0, 6.0))
        assertEquals(1.0 to "SCALP_TIME_8351", ScalpExit8351.decide8351(1.0, 2.0, ScalpExit8351.CLOCK_MS_8351, 0, 15.0, 6.0))
        assertTrue(ScalpExit8351.advisoryLine8984().startsWith("scalpAdvisory8984["))
    }

    @Test fun wired() {
        val se = File("src/main/kotlin/com/lifecyclebot/engine/truth/ScalpExit8351.kt").readText()
        assertTrue(se.contains("val scalp = liveScalp8984(shadowScalp8984)"))
        assertTrue(se.contains("if (shadowScalp8984) noteAdvisory8984(lane)"))
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        assertTrue(ex.contains("val liveExit = if (ScalpExit8351.liveScalp8984(liveExitStyle8351(t.lane, EvidenceHold8336.startMs8336) == Exit8347.SCALP)) Exit8347.SCALP else Exit8347.RUNNER"))
        val paper = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemePaperTrader8544.kt").readText()
        assertTrue("paper still trades the shadow's choice", paper.contains("ExploreShadow8347.liveExitStyle8351(lane, EvidenceHold8336.startMs8336) == ExploreShadow8347.Exit8347.SCALP"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("ScalpExit8351.advisoryLine8984()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 169. The shadow does not choose live exits (8984)"))
    }
}
