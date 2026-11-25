package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.PaperMirror8757
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8757 — owner (8756: FDG 0 / 163, no live trades): live MOONSHOT mirrors the paper MOONSHOT book (n176 PF 1.46
 * +1.389 SOL), past learned doors only, at normal sizing, with an automatic stop at -0.1 SOL of live MOONSHOT closes.
 */
class Aate8757PaperMirrorTest {
    @Test fun only_learned_doors_are_mirrored_past() {
        for (learned in listOf("EDGE_MODEL_NEGATIVE_8537", "LIVE_ORIGIN_COHORT_LOSES_8703", "CELL_PROVEN_LOSING_8136", "LIVE_STAGE_LOSES_8626",
                "MEME_BUYS_THE_HIGH_8582", "SETUP_RECORD_LOSING_8136"))
            assertFalse(learned, PaperMirror8757.hard8757(learned))
        for (hard in listOf("SAFETY_REFUSED_8136", "STRUCTURAL_RISK_8136", "DATA_INCOMPLETE_DEFER_8136", "EXECUTION_ECONOMICS_8136",
                "KILL_SWITCH_HOLD", "REBUY_LOCKOUT_8019", "BIG_LOSER_8130", "LANE_LIQ_BELOW_FLOOR", "WALLET_BLIND", "", null))
            assertTrue("$hard", PaperMirror8757.hard8757(hard))
        assertTrue(PaperMirror8757.mirrorLane8757("MOONSHOT"))
        assertTrue(PaperMirror8757.mirrorLane8757("moonshot_lunar"))
        assertFalse(PaperMirror8757.mirrorLane8757("EXPRESS"))
        assertTrue(PaperMirror8757.enabled8757)
        org.junit.Assert.assertEquals(0.1, PaperMirror8757.LOSS_STOP_SOL_8757, 0.0)
        assertFalse(PaperMirror8757.lossStopHit8757(-0.05))
        assertTrue(PaperMirror8757.lossStopHit8757(-0.1))
        assertTrue(PaperMirror8757.statusLine8757().startsWith("paperMirror8757[on="))
    }

    @Test fun a_mirrored_admit_passes_the_gate_only_past_learned_doors() {
        val mirrored = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, PaperMirror8757.REASON_8757, false,
            "X", Double.NaN, emptyList(), laneQualified8577 = true)
        assertTrue(PaperMirror8757.passesAtGate8757("LIVE_ORIGIN_COHORT_LOSES_8703", mirrored))
        assertFalse(PaperMirror8757.passesAtGate8757("SAFETY_REFUSED_8136", mirrored))
        val other = mirrored.copy(reason = "NATIVE_QUALIFIED_ADMIT_8259")
        assertFalse(PaperMirror8757.passesAtGate8757("LIVE_ORIGIN_COHORT_LOSES_8703", other))
        val auth = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        // superseded by V5.0.9022: a model pick's learned short-term refusal is advisory at the gate too
        // superseded by V5.0.9025 — an armed launch fire inside its graded window passes the same way (the line continues)
        assertTrue(auth.contains("(!binding8577(reason) || PaperMirror8757.passesAtGate8757(reason, admission) || pickPassesAtGate9022(reason, admission) ||"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("PaperMirror8757.statusLine8757()"))
    }

    @Test fun the_mirror_keeps_the_cost_check_the_price_bar_and_a_rate_cap() {
        assertTrue(PaperMirror8757.economicsClear8758(10.0, 4.0, 1.5))
        assertFalse("gross below 1.5x cost", PaperMirror8757.economicsClear8758(1.0, 4.0, 1.5))
        assertFalse("unknown net", PaperMirror8757.economicsClear8758(Double.NaN, 4.0, 1.5))
        org.junit.Assert.assertEquals(4, PaperMirror8757.MAX_PER_HOUR_8758)
        for (hard in listOf("CURVE_NOBODY_BOUGHT_8046", "AT_LAUNCH_CAP", "NET_EDGE_BELOW_8030", "PRICE_COLLAPSED_ONCHAIN"))
            assertTrue(hard, PaperMirror8757.hard8757(hard))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperMirror8757.kt").readText()
        assertTrue(src.contains("if (CandidateAdmission8136.priceStale8136(c.priceAgeMs, hotLaunch = false, live = true)) return d"))
        assertTrue(src.contains("if (try { FdgGate8365.shadowOnly8393() } catch (_: Throwable) { true }) return d"))
    }
}
