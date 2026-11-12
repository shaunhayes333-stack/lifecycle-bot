package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LpStateRecord8725
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8725 — what the LP rule turns away in the paying cell is measured; the rule itself and the buy door are unchanged. */
class Aate8725LpStateRecordTest {
    @Test fun theDoorsAnswerIsFiledUnderOneOfFourStates() {
        assertEquals("LP_BLOCKED", LpStateRecord8725.stateOf8725(false, "LP_LOCK_BELOW_REQUIRED_8522", null))
        assertEquals("LP_BLOCKED", LpStateRecord8725.stateOf8725(false, "LP_UNLOCKED_CONFIRMED_8522", null))
        assertEquals("OTHER_BLOCKED", LpStateRecord8725.stateOf8725(false, "MINT_AUTHORITY_ACTIVE", null))
        assertEquals("PENDING", LpStateRecord8725.stateOf8725(false, null, "LP_OWNERSHIP_PROOF_PENDING_8522"))
        assertEquals("PROVEN", LpStateRecord8725.stateOf8725(true, null, null))
        assertEquals("a refusal with no name is still a refusal", "OTHER_BLOCKED", LpStateRecord8725.stateOf8725(false, null, null))
        assertEquals(4, LpStateRecord8725.STATES_8725.size)
    }

    @Test fun nothingAboutTheLiveDoorChanged() {
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LiquidityOwnership8522.kt").readText()
        assertTrue(lp.contains("private const val MIN_LOCKED_PCT = 70.0"))
        assertTrue(lp.contains("return Result(Status.BLOCKED, \"LP_UNLOCKED_CONFIRMED_8522\""))
        assertTrue(lp.contains("return Result(Status.BLOCKED, \"LP_LOCK_BELOW_REQUIRED_8522\""))
        val rec = File("src/main/kotlin/com/lifecyclebot/engine/truth/LpStateRecord8725.kt").readText()
        assertTrue("filed as a refused label, through the probe that spends nothing",
            rec.contains("ForwardReturnLabeler7731.observe(ts, \"\$PREFIX_8725\$state\", false,") && rec.contains("PreTradeHardGate.probeDoor8717(ts)"))
        assertFalse(rec.contains("requireLiveBuyAllowed(ts,"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("if (mode == \"LIVE\" && candidate.marketCell8723 != null && !FdgGate8365.shadowOnly8393()) LpStateRecord8725.note8725(ts, nowMs)"))
        // the admitted coin still meets the door before a ticket: a block refuses, a pending proof defers
        assertTrue(adm.contains("return doorBlocked8717(d, block)"))
    }
}
