package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7513PaperQualityShadowEvidenceTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    // superseded: learning_quality_reject_still_blocks_canonical_open_but_feeds_shadow and existing_6663_thresholds_are_not_relaxed —
    // V5.0.8536 removes the paper-only fixed score gate (66 / 70 / 11) the owner called too high and not fluid. Paper is admitted
    // on the same fluid floors as LIVE; the shadow book keeps its own observation path below.
    // The baseline names stay (the full-suite delta reads a vanished test as a regression); their bodies pin the 8536 contract.
    @Test fun learning_quality_reject_still_blocks_canonical_open_but_feeds_shadow() {
        // superseded: the paper-only quality reject (`PAPER_QUALITY_REJECTED_SHADOWED_7513`) diverted paper buys to the shadow book.
        val s = src()
        assertFalse(s.contains("shouldSuppressPaperLearningEntry(ts, score, layerTag, identity)?.let"))
        assertFalse(s.contains("markPaperBuyNotOpened(\"LEARNING_QUALITY_REJECTED_6663\")"))
    }

    @Test fun existing_6663_thresholds_are_not_relaxed() {
        // superseded: SHITCOIN >= 66 / BLUECHIP >= 70 & $25k / EXPRESS >= 11 — removed in V5.0.8536 (owner: not fluid, too high).
        val s = src()
        assertFalse(s.contains("private fun shouldSuppressPaperLearningEntry"))
    }

    @Test fun the_paper_only_fixed_score_gate_is_gone() {
        val s = src()
        assertFalse(s.contains("private fun shouldSuppressPaperLearningEntry"))
        assertFalse(s.contains("PAPER_QUALITY_REJECTED_SHADOWED_7513"))
        assertFalse(s.contains("minScore=66"))
        assertTrue(s.contains("V5.0.8536 — the paper-only fixed score gate"))
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
