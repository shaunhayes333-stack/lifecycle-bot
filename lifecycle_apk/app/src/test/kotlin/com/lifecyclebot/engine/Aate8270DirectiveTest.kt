package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.truth.OpportunityRotation8098
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8270 — directive §2/§4/§5/§6: no invented native proof, nonterminal safety-proof waiting, structure must be
 * observed to witness a forming runner, and rotation is a lifecycle confirmed by the victim's finalized sell.
 */
class Aate8270DirectiveTest {
    private fun read(hhHl: Int, hl: Boolean, share: Double, close: Double, broke: Boolean) =
        StructureTracker7962.Read7962(30, hhHl, 1.0, 1.2, true, hl, false, false, share, close, broke)

    @Test fun missingStructureCannotProveAnUnlearnedFormingRunner() {
        val g = RunnerGrab7967
        assertFalse(g.formingStructure8270(null, null))                                  // nothing observed
        assertTrue(g.formingStructure8270(null, read(1, false, 0.7, 1.1, false)))         // observed 60 m run standing
        assertTrue(g.formingStructure8270(read(2, true, 0.7, 1.1, false), null))          // 15 m firming
        assertTrue(g.formingStructure8270(null, read(2, true, 0.7, 1.1, false)))          // 60 m firming
        assertFalse(g.formingStructure8270(null, read(2, true, 0.7, 1.1, true)))          // broke structure
        assertFalse(g.formingStructure8270(null, read(2, true, 0.3, 1.1, false)))         // sellers in control
        val src = File("src/main/kotlin/com/lifecyclebot/engine/RunnerGrab7967.kt").readText()
        assertTrue(src.contains("if (!formingStructure8270(r15, r60)) return null"))
    }

    @Test fun rotationIsConfirmedByTheVictimsFinalizedSellNotByRequestSell() {
        val r = OpportunityRotation8098
        val now = System.currentTimeMillis() + 36_000_000L
        r.noteCapitalDemand8262("SUCC8270", now)
        val held = listOf(OpportunityRotation8098.Held("VICT8270", -6.0), OpportunityRotation8098.Held("OK8270", 3.0))
        assertTrue(r.shouldRotate8098("VICT8270", r.BOOK_8103, held, now + 1_000L))
        assertFalse(r.shouldRotate8098("VICT8270", r.BOOK_8103, held, now + 1_500L))      // one live rotation per victim
        var line = r.statusLine8098(now + 1_500L)
        assertTrue(line, line.contains("VICT82>SUCC82:RESERVED") && line.contains("rotations=0"))
        // the sell was refused: demand restored, nothing counted as a rotation
        r.sellRequested8270("VICT8270", accepted = false, nowMs = now + 2_000L)
        line = r.statusLine8098(now + 2_000L)
        assertTrue(line, line.contains("retries=1") && line.contains("rotations=0") && r.bookDemand8103(now + 2_000L))
        // second try: accepted, pending; capital confirmed only by the finalized sell
        assertTrue(r.shouldRotate8098("VICT8270", r.BOOK_8103, held, now + 3_000L))
        r.sellRequested8270("VICT8270", accepted = true, nowMs = now + 3_500L)
        assertTrue(r.statusLine8098(now + 3_500L).contains("VICT82>SUCC82:SELL_PENDING"))
        assertFalse(r.successorReevaluationDue8270("SUCC8270", now + 3_600L))
        r.capitalConfirmed8270("VICT8270", now + 9_000L)
        line = r.statusLine8098(now + 9_000L)
        assertTrue(line, line.contains("rotations=1") && !line.contains("VICT82>"))
        assertTrue(r.successorReevaluationDue8270("SUCC8270", now + 9_500L))
        assertFalse(r.successorReevaluationDue8270("SUCC8270", now + 9_600L))              // consumed on read
        r.capitalConfirmed8270("VICT8270", now + 10_000L)                                  // duplicate delivery: no second count
        assertTrue(r.statusLine8098(now + 10_000L).contains("rotations=1"))
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("OpportunityRotation8098.sellRequested8270(ts.mint,"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt").readText()
            .contains("OpportunityRotation8098.capitalConfirmed8270(intent.mint)"))
    }

    @Test fun pendingSafetyProofIsNonterminalAndNativeReadinessIsNotInvented() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse(ex.contains("buyTerminalFail(\"BUY_TERMINAL_ABORTED:\$abortReason\")"))
        assertTrue(ex.contains("if (!exhausted8270) ExecutionAttemptLease.releaseNonTerminal(buyLease.key, \"BUY\", ts.mint, ts.symbol, reason8270)"))
        assertTrue(ex.contains("if (exhausted8270) buyTerminalFail(reason8270) else buyTerminalRecorded = true"))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertFalse(adm.contains("\"NATIVE_BOOK_8221\""))
        assertTrue(adm.contains("it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY &&"))
        assertFalse(adm.contains("it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.QUALIFIED ||"))
        assertTrue(adm.contains("nowMs - it.updatedAtMs in 0L..NATIVE_READY_FRESH_MS_8270"))
        assertEquals(10L * 60_000L, com.lifecyclebot.engine.truth.CandidateAdmission8136.NATIVE_READY_FRESH_MS_8270)
    }
}
