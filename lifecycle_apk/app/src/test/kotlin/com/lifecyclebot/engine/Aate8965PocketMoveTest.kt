package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8965 / 8966 — a pocket coin whose own move is unread is priced by its proven pocket (8171 already does it when the cell
 * is the strongest net record; 8965 covers the rest); a known move and every door still bind; a coin that still waits names why.
 */
class Aate8965PocketMoveTest {
    private val adm = CandidateAdmission8136
    private val basePocket = CandidateAdmission8136.Evidence8136("MARKET", "BASE_POCKET_8904", 130, 42.1, 5.3, netOfCost = true)

    private fun cand(cell: CandidateAdmission8136.Evidence8136?, gross: Double = Double.NaN) = CandidateAdmission8136.Candidate8136(
        mint = "m8965", lane = "SHITCOIN", selectedSetup = null, priceAgeMs = 500L,
        expectedGrossPct = gross, costPct = 3.0, live = true,
        moveWait8418 = NetEdge8030.MOVE_WAIT_HISTORY_8418, marketCell8723 = cell,
    )

    private fun pricedByCell(d: CandidateAdmission8136.Decision8136) =
        d.trace.any { it.startsWith("grossFromProvenNetLower8171=") || it.startsWith("grossFromMarketCellLower8965=") }

    @Test fun anUnreadMoveOnAProvenPocketCoinTakesThePocketsLowerBound() {
        val withoutCell = adm.decide8136(cand(null))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, withoutCell.terminal)
        assertFalse(pricedByCell(withoutCell))

        val carried = adm.decide8136(cand(basePocket))
        assertTrue(carried.trace.joinToString("\n"), pricedByCell(carried))
        assertFalse(carried.trace.any { it.startsWith("missing=") && it.contains("economics") })
        assertEquals(42.1 - 5.3, carried.expectedNetPct, 1e-9)
    }

    @Test fun aKnownCurrentMoveStillWins() {
        val d = adm.decide8136(cand(basePocket, gross = 2.0))
        assertFalse(d.trace.any { it.startsWith("grossFromMarketCellLower8965=") })
    }

    @Test fun anUnsupportedRecordGivesNoMoveAndNamesWhy() {
        val losing = CandidateAdmission8136.Evidence8136("MARKET", "LOSING", 130, -4.0, 2.0, netOfCost = true)
        val d = adm.decide8136(cand(losing))
        assertFalse(pricedByCell(d))
        assertTrue(d.trace.joinToString("\n"), d.trace.contains("cellMoveGap8966=CELL_UNSUPPORTED_RAW"))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(src.contains("decisionGross = if (market8723.netOfCost) market8723.lowerPct + c.costPct else market8723.lowerPct"))
        // V5.0.8971: kept out on a losing lane cell unless the cell is a proven pocket (POCKET_KINDS_8971)
        assertTrue(src.contains("market8723 != null && (!losing8136(cellLoss) || pocketAnswersCell8971) &&"))
    }

    @Test fun theStillWaitingReasonsAreNamedNotGuessed() {
        val ok = basePocket
        assertEquals("NO_CELL", adm.cellMoveGap8966(null, null, false, null, 3.0))
        assertEquals("CELL_BELOW_LIVE_SHORTFALL", adm.cellMoveGap8966(ok, null, false, null, 3.0))
        assertEquals("LANE_CELL_LOSING", adm.cellMoveGap8966(ok, ok, true, null, 3.0))
        val gross = CandidateAdmission8136.Evidence8136("RUNNER", "GRAB", 40, 60.0, 5.0, netOfCost = false)
        assertEquals("EDGE_NOT_NET", adm.cellMoveGap8966(ok, ok, false, gross, 3.0))
        assertEquals("OTHER", adm.cellMoveGap8966(ok, ok, false, ok, 3.0))
    }

    @Test fun theFallingHoursProvenLosingRefusalStillBinds() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val fallback = src.indexOf("var grossFromCell8965 = false")
        val falling = src.indexOf("if ((!decisionGross.isFinite() || grossFromModel8868 || grossFromCell8965) && moveWaitChosen8418 == NetEdge8030.MOVE_WAIT_FALLING_8418")
        assertTrue(fallback > 0 && falling > fallback)
        assertTrue(src.indexOf("return done(Terminal8136.REFUSE, FALLING_HOUR_PROVEN_LOSING_8418, true, chosen)") > falling)
        assertTrue(src.indexOf("return done(Terminal8136.REFUSE, \"SAFETY_REFUSED_8136\", true, c.selectedSetup)") in 1 until fallback)
    }

    @Test fun wired() {
        assertTrue(adm.cellGrossLine8965().startsWith("cellGross8965["))
        assertTrue(adm.cellGrossLine8965().contains("stillWaiting8966{"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("CandidateAdmission8136.cellGrossLine8965()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 151. A pocket coin is priced by its pocket (8965)"))
    }
}
