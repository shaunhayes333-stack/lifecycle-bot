package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.GateOutcome8418
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8696 — one rule for every selection gate: it binds until its own graded refusals prove it refuses winners
 * (GateOutcome8418.selectionGateRetired8696). The structural stage, the entry-state doors, the 24 h loss lock and the
 * consensus veto all read it; top-10 concentration left the structural stage.
 */
class Aate8696OneRuleForSelectionGatesTest {
    @Before fun setup() { GateOutcome8418.resetForTests8418() }
    @After fun cleanup() { GateOutcome8418.resetForTests8418() }

    @Test fun aGateWithNoRecordOrAShortRecordStillBinds() {
        assertFalse(GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", "MOONSHOT"))
        repeat(60) { GateOutcome8418.book8418("ADMISSION8136_STRUCTURAL_RISK_8136", "MOONSHOT", 30.0, false) }
        assertFalse("60 graded refusals is under the audit bar", GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", "MOONSHOT"))
    }

    @Test fun aGateWhoseRefusalsLoseKeepsBinding() {
        repeat(200) { i -> GateOutcome8418.book8418("ADMISSION8136_STRUCTURAL_RISK_8136", "MOONSHOT", if (i % 20 == 0) 150.0 else -30.0, i % 20 == 0) }
        assertFalse(GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", "MOONSHOT"))
    }

    @Test fun everySelectionGateReadsTheOneRule() {
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("GateOutcome8418.selectionGateRetired8696(\"CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136\", c.lane)"))
        assertTrue(src("engine/truth/LaneAuthority8577.kt").contains("GateOutcome8418.selectionGateRetired8696(\"CANDIDATE_ADMISSION_8136_\$why\", lane)"))
        assertTrue(src("engine/RebuyLockout8019.kt").contains("GateOutcome8418.selectionGateRetired8696(\"BIG_LOSER_8130\")"))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("GateOutcome8418.selectionGateRetired8696(consensusVetoReason8690(objections), lane)"))
        assertFalse(src("engine/TokenMetricStageRouter.kt").contains("topHeavy ||"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
