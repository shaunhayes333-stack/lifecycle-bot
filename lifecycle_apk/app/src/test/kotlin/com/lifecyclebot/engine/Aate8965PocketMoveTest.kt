package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8965 — a pocket coin whose own move is unread is priced by its proven pocket; a known move and every door still bind. */
class Aate8965PocketMoveTest {
    private val adm = CandidateAdmission8136
    private val basePocket = CandidateAdmission8136.Evidence8136("MARKET", "BASE_POCKET_8904", 130, 42.1, 5.3, netOfCost = true)

    private fun cand(cell: CandidateAdmission8136.Evidence8136?, gross: Double = Double.NaN) = CandidateAdmission8136.Candidate8136(
        mint = "m8965", lane = "SHITCOIN", selectedSetup = null, priceAgeMs = 500L,
        expectedGrossPct = gross, costPct = 3.0, live = true,
        moveWait8418 = NetEdge8030.MOVE_WAIT_HISTORY_8418, marketCell8723 = cell,
    )

    @Test fun anUnreadMoveOnAProvenPocketCoinTakesThePocketsLowerBound() {
        val withoutCell = adm.decide8136(cand(null))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, withoutCell.terminal)
        assertFalse(withoutCell.trace.any { it.startsWith("grossFromMarketCellLower8965=") })

        val carried = adm.decide8136(cand(basePocket))
        assertTrue(carried.trace.joinToString("\n"), carried.trace.any { it.startsWith("grossFromMarketCellLower8965=") })
        assertFalse("no longer waits on the move it cannot have", carried.reason == "DATA_INCOMPLETE_DEFER_8136" &&
            carried.trace.any { it.startsWith("missing=") && it.contains("economics") })
        assertEquals(42.1 - 5.3, carried.expectedNetPct, 1e-9)
    }

    @Test fun aKnownCurrentMoveStillWins() {
        val d = adm.decide8136(cand(basePocket, gross = 2.0))
        assertFalse(d.trace.any { it.startsWith("grossFromMarketCellLower8965=") })
    }

    @Test fun anUnsupportedOrGrossRecordIsNeverCountedAsANet() {
        val losing = CandidateAdmission8136.Evidence8136("MARKET", "LOSING", 130, -4.0, 2.0, netOfCost = true)
        assertFalse(adm.decide8136(cand(losing)).trace.any { it.startsWith("grossFromMarketCellLower8965=") })
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(src.contains("decisionGross = if (market8723.netOfCost) market8723.lowerPct + c.costPct else market8723.lowerPct"))
    }

    @Test fun theFallingHoursProvenLosingRefusalStillBinds() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val fallback = src.indexOf("var grossFromCell8965 = false")
        val falling = src.indexOf("if ((!decisionGross.isFinite() || grossFromModel8868 || grossFromCell8965) && moveWaitChosen8418 == NetEdge8030.MOVE_WAIT_FALLING_8418")
        assertTrue(fallback > 0 && falling > fallback)
        assertTrue(src.indexOf("return done(Terminal8136.REFUSE, FALLING_HOUR_PROVEN_LOSING_8418, true, chosen)") > falling)
        // safety and structure are decided before the cell is read
        assertTrue(src.indexOf("return done(Terminal8136.REFUSE, \"SAFETY_REFUSED_8136\", true, c.selectedSetup)") in 1 until fallback)
    }

    @Test fun wired() {
        assertTrue(adm.cellGrossLine8965().startsWith("cellGross8965["))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("CandidateAdmission8136.cellGrossLine8965()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 151. A pocket coin is priced by its pocket (8965)"))
    }
}
