package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.LlmBands8875
import com.lifecyclebot.engine.truth.Mind8746
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8875 — owner, 20 Nov: "the llms are fine. but they needs to be proving a much much bigger boost all round and should
 * be helping a lot more than they do. especially live." The analyst's answer is graded per lane and per quarter of the
 * probability scale; a band that proves to pay admits like a paying cell, and every dataset row carries the answer.
 */
class Aate8875LlmBandsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() = LlmBands8875.resetForTest8875()
    @After fun tearDown() = LlmBands8875.resetForTest8875()

    @Test fun theAnswerIsFiledByItsQuarterOfTheScale() {
        assertEquals(0, LlmBands8875.band8875(0.0))
        assertEquals(0, LlmBands8875.band8875(0.249))
        assertEquals(1, LlmBands8875.band8875(0.25))
        assertEquals(2, LlmBands8875.band8875(0.5))
        assertEquals(3, LlmBands8875.band8875(0.75))
        assertEquals(3, LlmBands8875.band8875(1.0))
        assertNull("no answer, no band", LlmBands8875.band8875(null))
        assertNull(LlmBands8875.band8875(Double.NaN))
        assertNull(LlmBands8875.band8875(1.2))
        assertEquals("CORE_LLM3", LlmBands8875.key8875(" core ", 3))
        // the probability the mind froze with the decision: features8746 stores (p - 0.5) x 100 and a presence flag
        assertEquals(0.8, LlmBands8875.frozenP8875(Mind8746.features8746(1.0, 2.0, Double.NaN, 0.8)), 1e-12)
        assertTrue("no answer frozen", LlmBands8875.frozenP8875(Mind8746.features8746(1.0, 2.0, Double.NaN, Double.NaN)).isNaN())
        assertTrue(LlmBands8875.frozenP8875(null).isNaN())
    }

    @Test fun onlyABandThatProvesToPayAdmitsAndOnlyInItsOwnLane() {
        repeat(40) { i -> LlmBands8875.addForTest8875("EXPRESS", 0.8, "E$i", if (i % 2 == 0) 80.0 else -10.0, 1_000L + i) }
        repeat(40) { i -> LlmBands8875.addForTest8875("EXPRESS", 0.1, "L$i", -12.0, 1_000L + i) }
        val yes = LlmBands8875.evidenceOf8875("EXPRESS_LLM3", LlmBands8875.recordForTest8875("EXPRESS", 3))
        assertNotNull(yes)
        assertEquals(LlmBands8875.KIND_8875, yes!!.kind)
        assertTrue(CandidateAdmission8136.supported8136(yes, 3.0))
        assertNull("the band that loses admits nothing", LlmBands8875.evidenceOf8875("EXPRESS_LLM0", LlmBands8875.recordForTest8875("EXPRESS", 0)))
        assertNull("another lane's record is not this lane's", LlmBands8875.recordForTest8875("MOONSHOT", 3))
        val line = LlmBands8875.statusLine8875()
        assertTrue(line, line.contains("EXPRESS_LLM0=n36/-12.0%/run0%,") && line.contains("EXPRESS_LLM3=n36/+34.9%/run0%*"))
        assertTrue("a proven band answers the lane-wide priors as a paying cell does", LaneAuthority8577.answersPriors8874(LlmBands8875.KIND_8875))
    }

    @Test fun theAnswerTravelsInTheDatasetAndSeedsTheBands() {
        val raw = EdgeModel8537.Raw("SHITCOIN", "SRC", 20_000.0, 50_000.0, 3.0, 10.0, 0.9, 5.0, 60.0, 2.0, 5.0, 1.2, 12.0, "EARLY", 30.0, 3.0)
        val s = EdgeModel8537.Snap(raw, EdgeModel8537.vectorOf(raw), 2.5, "GLOBAL", 1_700_000_000_000L, "MintX", true)
        assertTrue(JSONObject(EdgeModel8537.datasetLine(s, -4.25, llmP8875 = 0.62)).getDouble("llmP") in 0.619..0.621)
        assertTrue("no answer is written as null", JSONObject(EdgeModel8537.datasetLine(s, -4.25)).isNull("llmP"))
        LlmBands8875.seedRow8863(JSONObject().put("lane", "SHITCOIN").put("mint", "A").put("t", 10L).put("net", 30.0).put("llmP", 0.62))
        LlmBands8875.seedRow8863(JSONObject().put("lane", "SHITCOIN").put("mint", "B").put("t", 20L).put("net", 30.0))   // an older row: no answer
        assertEquals(1.0, LlmBands8875.recordForTest8875("SHITCOIN", 2)!![0], 1e-9)
        assertTrue(src("engine/truth/DatasetReplay8863.kt").contains("{ o -> LlmBands8875.seedRow8863(o) },"))
    }

    @Test fun itIsWiredBesideTheOtherMarketEvidence() {
        val edge = src("engine/truth/EdgeModel8537.kt").substringAfter("fun learn(s: Snap, netPct: Double").substringBefore("fun verdict(")
        val read = edge.indexOf("val llmP8875 = try { LlmBands8875.frozenP8875(Mind8746.frozenFor8767(s.mint, s.raw.lane, s.atMs)) }")
        assertTrue("read before the mind consumes the frozen voters", read in 0 until edge.indexOf("Mind8746.learn8746(s.mint, s.raw.lane, s.atMs, y)"))
        assertTrue(edge.contains("appendDataset(s, netPct, witnessed = chainWitness, llmP8875 = llmP8875)"))
        assertTrue(edge.contains("LlmBands8875.learn8875(s.mint, s.raw.lane, llmP8875, netPct, s.atMs)"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val chain = adm.substringAfter("marketCell8723 = selectMarketEvidence8961(", "").substringBefore("regimeCold8546 =")
        val providers = Regex("[A-Za-z0-9_]+\\.evidence[0-9]+\\(").findAll(chain).map { it.value }.toList()
        assertEquals("last in the supported market-evidence order", "LlmBands8875.evidence8875(", providers.lastOrNull())
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("LlmBands8875.statusLine8875()"))
        val lab = src("engine/truth/LlmBands8875.kt")
        assertTrue(lab.contains("ready = DatasetReplay8863.await8863()") && !lab.contains("datasetFile()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 64. The LLM analyst earns a seat on its own graded record (8875)"))
    }
}
