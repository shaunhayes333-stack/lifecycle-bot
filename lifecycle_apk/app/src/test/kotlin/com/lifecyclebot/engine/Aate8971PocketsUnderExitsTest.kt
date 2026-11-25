package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.LanePocket8934
import com.lifecyclebot.engine.truth.NetEdge8030
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8971 — lane pockets are graded under the lane's own exits; a proven pocket answers the lane's own cell prior. */
class Aate8971PocketsUnderExitsTest {
    private val adm = CandidateAdmission8136

    @Test fun aMemeLanesPocketIsGradedUnderItsExits() {
        LanePocket8934.resetForTest8934()
        // a coin that peaked +60 % and closed the hour at -5 %: held, -5 - cost; under the exits, the doctrine line sold it in profit
        LanePocket8934.fileForTest8934(JSONObject().put("t", 1L).put("horizonMs", 3_600_000L).put("mint", "coinA")
            .put("lane", "CORE").put("mcap", 50_000.0).put("liq", 25_000.0).put("stage", "BASE_START").put("setup", "NONE")
            .put("v3", 30.0).put("pred", 12.0).put("gross", -5.0).put("minPct", -8.0).put("heldPeakPct", 60.0).put("cost", 3.0).put("net", -8.0))
        assertTrue(LanePocket8934.statusLine8934().contains("exitsGraded8971=1"))
        // a crypto lane keeps the held net
        LanePocket8934.fileForTest8934(JSONObject().put("t", 2L).put("horizonMs", 3_600_000L).put("mint", "coinB")
            .put("lane", "CRYPTO_CORE").put("mcap", 5.0e7).put("liq", 2.0e6).put("stage", "UNKNOWN").put("setup", "NONE")
            .put("v3", 50.0).put("pred", 1.0).put("gross", -1.0).put("minPct", -2.0).put("heldPeakPct", 15.0).put("cost", 2.6).put("net", -3.6))
        assertTrue(LanePocket8934.statusLine8934().contains("exitsGraded8971=1"))
        LanePocket8934.resetForTest8934()
    }

    @Test fun aProvenPocketAnswersTheLanesLosingCellAndALaunchFireDoesNot() {
        val losingCell = CandidateAdmission8136.Evidence8136("CELL", "SRC|MOONSHOT|10-20k|<1h", 200, -9.0, 1.5)
        val pocket = CandidateAdmission8136.Evidence8136(LanePocket8934.KIND_8934, "LANE8934:MOONSHOT:pred=10+", 95, 54.4, 18.0)
        val launch = CandidateAdmission8136.Evidence8136("LAUNCH_RULE_8748", "cap\$5k", 3104, 6.5, 2.2)
        fun cand(cell: CandidateAdmission8136.Evidence8136) = CandidateAdmission8136.Candidate8136(
            mint = "m8971", lane = "MOONSHOT", selectedSetup = null, priceAgeMs = 500L, expectedGrossPct = Double.NaN,
            costPct = 3.0, live = true, moveWait8418 = NetEdge8030.MOVE_WAIT_HISTORY_8418, cell = losingCell, marketCell8723 = cell)
        val p = adm.decide8136(cand(pocket))
        assertFalse(p.trace.joinToString("\n"), p.reason == "CELL_PROVEN_LOSING_8136")
        assertTrue(p.trace.any { it.contains("answeredByPocket8971=") })
        // superseded by V5.0.8999: an armed launch fire answers the cell prior too (its door's live record governs it, 8985)
        val l = adm.decide8136(cand(launch))
        assertFalse(l.trace.joinToString("\n"), l.reason == "CELL_PROVEN_LOSING_8136")
        assertTrue(adm.POCKET_KINDS_8971.containsAll(listOf("AGREEMENT_POCKET", "BASE_POCKET", "LANE_POCKET")))
        assertTrue(adm.POCKET_KINDS_8971.contains("LAUNCH_RULE_8748"))
    }

    @Test fun wired() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(a.contains("market8723?.takeIf { !losing8136(cellLoss) || pocketAnswersCell8971 })"))
        assertTrue(a.contains("market8723 != null && (!losing8136(cellLoss) || pocketAnswersCell8971) &&"))
        assertTrue(adm.pocketCellLine8971().startsWith("pocketAnswersCell8971{"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("CandidateAdmission8136.pocketCellLine8971()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 156. Every pocket graded under its lane's exits (8971)"))
    }
}
