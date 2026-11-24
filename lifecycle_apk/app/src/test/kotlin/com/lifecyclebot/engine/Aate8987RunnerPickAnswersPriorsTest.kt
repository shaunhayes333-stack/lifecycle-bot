package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.RunnerModel8861
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8987 — the runner model's armed pick answers the lane-wide entry-state priors, as the pockets and the launch rule do. */
class Aate8987RunnerPickAnswersPriorsTest {
    @Test fun theArmedPickAnswersThePriors() {
        assertEquals("RUNNER_MODEL_8861", RunnerModel8861.KIND_8861)
        assertTrue(LaneAuthority8577.answersPriors8874(RunnerModel8861.KIND_8861))
        assertFalse("unproven kinds keep their standing", LaneAuthority8577.answersPriors8874("LAB_STRATEGY_8755"))
        assertFalse(LaneAuthority8577.answersPriors8874("MARKET_CELL"))
    }

    @Test fun itIsServedOnlyWhileArmedAndWired() {
        val m = File("src/main/kotlin/com/lifecyclebot/engine/truth/RunnerModel8861.kt").readText()
        assertTrue(m.contains("if (!armed) return null"))
        assertTrue(m.contains("CandidateAdmission8136.Evidence8136(KIND_8861, "))
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("pocket8874 = LaneAuthority8577.answersPriors8874(candidate.marketCell8723?.kind)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 172. The armed runner pick answers the lane priors (8987)"))
    }
}
