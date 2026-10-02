package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7513PaperQualityShadowEvidenceTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test fun learning_quality_reject_still_blocks_canonical_open_but_feeds_shadow() {
        val s = src()
        val gate = s.substringAfter("shouldSuppressPaperLearningEntry(ts, score, layerTag, identity)?.let")
            .substringBefore("// V5.9.1129")
        assertTrue(gate.contains("runShadowPaperBuy("))
        assertTrue(gate.contains("PAPER_QUALITY_REJECTED_SHADOWED_7513"))
        assertTrue(gate.contains("markPaperBuyNotOpened(\"LEARNING_QUALITY_REJECTED_6663\")"))
        assertTrue(gate.contains("return"))
    }

    @Test fun existing_6663_thresholds_are_not_relaxed() {
        val s = src()
        val fn = s.substringAfter("private fun shouldSuppressPaperLearningEntry")
            .substringBefore("private fun buildTradePolicySnapshot")
        assertTrue(fn.contains("score < 70.0"))
        assertTrue(fn.contains("liq < 25_000.0"))
        assertTrue(fn.contains("score < 11.0"))
        assertTrue(fn.contains("score < 66.0"))
        assertTrue(fn.contains("liq < 3_000.0"))
    }

    @Test fun shadow_book_remains_noncanonical_observation_path() {
        val s = src()
        val fn = s.substringAfter("private fun runShadowPaperBuy")
            .substringBefore("fun checkShadowPositions")
        assertTrue(fn.contains("shadowPositions[ts.mint] = shadowPos"))
        assertTrue(fn.contains("ShadowBookTelemetry7215.onOpen7215"))
        assertFalse(fn.contains("CanonicalPaperTransaction6486.open"))
        assertFalse("shadow observation must never authorize or dispatch a live buy", fn.contains("liveBuy("))
        assertFalse("shadow observation must never turn into a live handoff", fn.contains("SHADOW_TO_LIVE_HANDOFF"))
    }

    @Test fun live_shadow_observation_has_one_shared_executor_seam() {
        val s = src()
        val fn = s.substringAfter("private fun liveBuy(")
        assertTrue(fn.contains("runShadowPaperBuy(ts, sol, score, quality, \"live_prebroadcast_observation\")"))
        assertFalse("the outer doBuy path must not double-record the common live seam", s.contains("runShadowPaperBuy(ts, effSol, score, quality, \"parallel\""))
    }
}
