package com.lifecyclebot.engine

import com.lifecyclebot.engine.FinalDecisionGate.GateCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8535 §N-03 — operator: "the meme trader does not trade at all in paper mode". PAPER's economic class demanded a
 * non-SKIP legacy edge letter LIVE never demands; the benchmark now reads the gate's own edge verdict.
 */
class Aate8535PaperBenchmarkTest {

    @Test fun the_benchmark_reads_the_edge_check_the_gate_itself_passed() {
        // a "C" EdgeOptimizer grade reads EdgeVerdict.SKIP; LIVE buys it on the 7431 evidence, so paper does too
        val override = listOf(GateCheck("rug", true, null), GateCheck("edge", true, "CANONICAL edge evidence override (score=41>=30)"))
        assertTrue(PaperBenchmark8535.edgePassedAsLive8535(override))
        val smartMoney = listOf(GateCheck("edge", true, "CANONICAL edge + smart-money risk shape: buy%=61>=45"))
        assertTrue(PaperBenchmark8535.edgePassedAsLive8535(smartMoney))
        assertTrue("a non-SKIP grade", PaperBenchmark8535.edgePassedAsLive8535(listOf(GateCheck("edge", true, null))))
        // a failed edge check (no 7431 evidence) is a block in both modes; a Cortex paper choice over it leaves no pass
        assertFalse(PaperBenchmark8535.edgePassedAsLive8535(listOf(GateCheck("edge", false, "edge=C | missing=buy_pressure,liquidity,score"))))
        assertFalse("no edge check at all is not a pass", PaperBenchmark8535.edgePassedAsLive8535(listOf(GateCheck("rug", true, null))))
        assertFalse(PaperBenchmark8535.edgePassedAsLive8535(emptyList()))
    }

    @Test fun paper_classifies_on_the_gates_verdict_and_no_longer_produces_a_shadow_probe_class() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(f.contains("val wouldPassLiveEdge = PaperBenchmark8535.edgePassedAsLive8535(checks)"))
        assertFalse("the raw edge letter is no longer the benchmark rule", f.contains("val wouldPassLiveEdge = edgeVerdict != EdgeVerdict.SKIP"))
        val c = f.substringAfter("§PAPER_ACCEPTS_WHAT_LIVE_ACCEPTS").substringBefore("tags.add(\"class:")
        assertFalse("the paper-only confidence probe class is gone: LIVE sizes low confidence, it does not refuse it", c.contains("confidenceProbe -> {"))
        assertFalse(c.contains("ApprovalClass.PAPER_PROBE to"))
        assertTrue(c.contains("wouldPassLiveEdge && wouldPassLiveQuality -> {"))
        // the live rules themselves are unchanged: the edge gate still maps a "C" grade to SKIP and admits it on 7431 evidence
        assertTrue(f.contains("\"WEAK\", \"B\", \"OK\" -> EdgeVerdict.WEAK"))
        assertTrue(f.contains("tags.add(\"canonical_edge_evidence_override_7431\")") && f.contains("tags.add(\"smart_money_early_risk_shape_7549\")"))
        assertTrue(f.contains("blockReason = \"EDGE_VETO_\${candidate.edgeQuality}\""))
        // the economic class is still the only executable paper class (7548)
        assertTrue(f.contains("TradeMode.PAPER -> approvalClass == ApprovalClass.PAPER_BENCHMARK"))
        // the grade a passing meme candidate usually carries
        val eo = File("src/main/kotlin/com/lifecyclebot/engine/EdgeOptimizer.kt").readText()
        assertTrue(eo.contains("else -> \"C\"") && eo.contains("quality = quality"))
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("edgeQuality = if (laneBase.edgeQuality == \"SKIP\") \"C\" else laneBase.edgeQuality,"))
    }
}
