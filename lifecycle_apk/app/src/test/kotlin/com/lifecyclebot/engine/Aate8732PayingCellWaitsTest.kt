package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LpStateRecord8725
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8732 — a paying-cell coin the buy door holds is filed by the proof it waits on and by what the admission decided. */
class Aate8732PayingCellWaitsTest {
    @Test fun aPendingDoorNamesItsProofFamilies() {
        val d = "LIVE_CRITICAL_PROOF_PENDING_8373|RUGCHECK_PENDING_OR_UNKNOWN:PENDING|MINT_AUTHORITY_UNKNOWN|HOLDER_DATA_UNKNOWN|" +
            "LP_OWNERSHIP_PROOF_PENDING_8522:selected pool has no complete ownership proof; pool=abc"
        assertEquals(listOf("RUGCHECK", "MINT_AUTHORITY", "HOLDERS", "LP_NO_POOL_PROOF"), LpStateRecord8725.proofFamilies8732(d))
        assertEquals(listOf("SAFETY_REPORT_STALE"),
            LpStateRecord8725.proofFamilies8732("LIVE_SAFETY_REPORT_NOT_FRESH_8269|SAFETY_PROOF_STALE_OR_MISSING:checkedAt=0 ageMs=5"))
        assertEquals(listOf("LP_LOCK_UNKNOWN"),
            LpStateRecord8725.proofFamilies8732("X|LP_OWNERSHIP_PROOF_PENDING_8522:LP ownership unknown; pool=p"))
        assertEquals(listOf("UNNAMED"), LpStateRecord8725.proofFamilies8732(""))
    }

    @Test fun theRecordIsReportedAndActsOnNothing() {
        LpStateRecord8725.noteAdmission8732("DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", listOf("lane=CORE", "missing=economics"))
        val line = LpStateRecord8725.waitLine8732()
        assertTrue(line, line.contains("DEFER_DATA:DATA_INCOMPLETE_DEFER_8136=") && line.contains("missing=economics"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("LpStateRecord8725.noteAdmission8732(it.terminal.name, it.reason, it.trace)"))
        val rep = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(rep.contains("Paying-cell waits (§8732): "))
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LiquidityOwnership8522.kt").readText()
        assertTrue("the LP rule is unchanged", lp.contains("private const val MIN_LOCKED_PCT = 70.0"))
    }
}
