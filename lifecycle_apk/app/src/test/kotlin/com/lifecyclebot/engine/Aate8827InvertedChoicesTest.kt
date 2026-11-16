package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.SignalTruth8825
import com.lifecyclebot.engine.truth.SignalTruth8825.Choice
import com.lifecyclebot.engine.truth.SignalTruth8825.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8827 — what the bot chooses against what pays, measured; one model refusal for paper and live. */
class Aate8827InvertedChoicesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun verdict(p: Double, refuses: Boolean) =
        EdgeModel8537.Verdict(p, false, "SHITCOIN", 900L, 0, Double.NaN, Double.NaN, -8.0, refuses8715 = refuses)

    @Test fun theAdmissionsLeanIsReadAgainstTheOutcome() {
        assertEquals(Choice.INVERTED, SignalTruth8825.choice8827(Way.HIGHER_WORSE, Way.HIGHER_BETTER))
        assertEquals(Choice.INVERTED, SignalTruth8825.choice8827(Way.HIGHER_BETTER, Way.HIGHER_WORSE))
        assertEquals(Choice.ALIGNED, SignalTruth8825.choice8827(Way.HIGHER_BETTER, Way.HIGHER_BETTER))
        assertEquals(Choice.IGNORED, SignalTruth8825.choice8827(Way.HIGHER_BETTER, Way.NOISE))
        assertEquals(Choice.UNREAD, SignalTruth8825.choice8827(Way.NOISE, Way.HIGHER_BETTER))
        assertEquals(Choice.UNREAD, SignalTruth8825.choice8827(Way.HIGHER_WORSE, Way.THIN))
    }

    @Test fun aGateThatAdmitsMoreOfWhatDoesWorseIsNamedInverted() {
        SignalTruth8825.resetForTest8825()
        for (i in 0 until 200) {
            val burst = (i % 20).toDouble()
            SignalTruth8825.note8825("Inv$i", 0.4, -3.0 * burst, burst >= 10.0) { k -> if (k == "runup") burst else Double.NaN }
        }
        val r = SignalTruth8825.reads8825().first { it.signal == "runup" && it.band == "<1m" }
        assertEquals(Way.HIGHER_WORSE, r.way); assertEquals(Choice.INVERTED, r.choice)
        assertTrue(SignalTruth8825.choiceLine8827().startsWith("choice8827[inverted=1{runup@<1m outcome -1.00/-1.00 admit "))
        SignalTruth8825.resetForTest8825()
        // nothing admitted at all is no lean, not a blind spot
        for (i in 0 until 200) SignalTruth8825.note8825("Flat$i", 0.4, -3.0 * (i % 20), false) { k -> if (k == "runup") (i % 20).toDouble() else Double.NaN }
        assertEquals(Choice.UNREAD, SignalTruth8825.reads8825().first { it.signal == "runup" && it.band == "<1m" }.choice)
        SignalTruth8825.resetForTest8825()
        assertTrue(SignalTruth8825.SIGNALS.containsAll(listOf("v3", "pred", "age")))
    }

    @Test fun anEarnedRefusalBindsPaperAndWhatGetsThroughIsCounted() {
        val base = CandidateAdmission8136.Candidate8136("m8827", "SHITCOIN", null,
            expectedGrossPct = 20.0, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0)
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = verdict(-12.0, refuses = true)), null, mutableListOf()))
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = verdict(-12.0, refuses = false)), null, mutableListOf()))
        assertTrue(CandidateAdmission8136.edgeLetThrough8827().contains("UNEARNED_PAPER:SHITCOIN="))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue("the live-only exemption is gone", !adm.contains("edge8537RefusalLiveOnly8715\"; return null"))
        val rep = src("engine/PipelineHealthCollector.kt")
        assertTrue(rep.contains("SignalTruth8825.choiceLine8827()") && rep.contains("CandidateAdmission8136.edgeLetThrough8827()"))
        val em = src("engine/truth/EdgeModel8537.kt")
        assertTrue(em.contains("{ k -> if (k == \"pred\") s.pred else signal8825(s.raw, k) }") && em.contains("\"v3\" -> r.v3"))
    }
}
