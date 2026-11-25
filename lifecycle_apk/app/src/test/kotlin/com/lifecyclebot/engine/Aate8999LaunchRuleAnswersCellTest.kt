package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BaseBreakout8995
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8999 — an armed launch rule fire (forward-proven on the phone's replay) and an armed base breakout answer the lane's
 * losing cell prior as a proven pocket does; an unproven fire still does not, and the door's live record still governs it.
 */
class Aate8999LaunchRuleAnswersCellTest {
    private val adm = CandidateAdmission8136
    private val losingCell = CandidateAdmission8136.Evidence8136("CELL", "SRC|MOONSHOT|<10k|<1h", 200, -9.0, 1.5)
    private fun cand(market: CandidateAdmission8136.Evidence8136?) = CandidateAdmission8136.Candidate8136(
        mint = "m8999", lane = "MOONSHOT", selectedSetup = null, priceAgeMs = 500L, expectedGrossPct = Double.NaN,
        costPct = 3.0, live = true, moveWait8418 = NetEdge8030.MOVE_WAIT_HISTORY_8418, cell = losingCell, marketCell8723 = market)

    @Test fun anArmedLaunchFireIsNotRefusedOnTheLanesCell() {
        val armed = CandidateAdmission8136.Evidence8136(LaunchSniper8748.KIND_8748, "cap\$5k buys>=3 tp5x stop35%", 5366, 6.0, 1.7)
        val d = adm.decide8136(cand(armed))
        assertFalse(d.trace.joinToString("\n"), d.reason == "CELL_PROVEN_LOSING_8136")
        assertTrue(d.trace.any { it.contains("answeredByPocket8971=") })
        val bb = CandidateAdmission8136.Evidence8136(BaseBreakout8995.KIND_8995, "PUMPSWAP|B8994W", 120, 8.0, 2.0)
        assertFalse(adm.decide8136(cand(bb)).reason == "CELL_PROVEN_LOSING_8136")
    }

    @Test fun aLaunchRecordThatDoesNotClearItsCostIsNotCarried() {
        val proven = CandidateAdmission8136.Evidence8136(LaunchSniper8748.KIND_8748, "proven", 5366, 6.0, 1.7)
        val thin = CandidateAdmission8136.Evidence8136(LaunchSniper8748.KIND_8748, "thin", 40, 1.0, 3.0)
        assertTrue(adm.supported8136(proven, 3.0))
        assertFalse("not carried, so it answers nothing", adm.supported8136(thin, 3.0))
    }

    @Test fun wired() {
        assertTrue(adm.POCKET_KINDS_8971.containsAll(listOf(LaunchSniper8748.KIND_8748, BaseBreakout8995.KIND_8995)))
        assertTrue(adm.pocketCellLine8971().contains("an armed launch rule or an armed base breakout"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 184. A proven launch rule answers the cell prior (8999)"))
    }
}
