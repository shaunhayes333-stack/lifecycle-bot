package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8256 — 5.0.8255 runtime (433 s): admission8136 ADMIT=244, FDG allow=0, ownerSelected=0 in every lane. 116 of the
 * admits died in LiveEdgeGate7877 on lane-wide shape bins (EDGE_7877_SHAPE_7883_AGE_MIN_60-240_n107 ...) and 18 on the
 * watch-first cell — generic lane priors ranked above the candidate's own supported setup, the precedence 8240 had
 * already settled inside the admission.
 */
class Aate8256GenericPriorYieldsTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aLaneShapeBinStandsDownForTheCandidatesSupportedSetup() {
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val shape = gate.substringAfter("val shape = try { TradeShapeLearner7883.shapeRefusal(ts, l) }").substringBefore("val v = verdictFor(ts, l, nowMs)")
        // the Cortex overrule stays first, then the supported admission, then (and only then) the refusal
        val overrule = shape.indexOf("Cortex7885.overrulesEdgeRefusal(ts, l, shape)) return null")
        val yields = shape.indexOf("if (shape != null && candidateSpecificAdmission8136) {")
        val refuse = shape.indexOf("return \"EDGE_7877_\$shape\"")
        assertTrue(overrule in 0 until yields)
        assertTrue(yields < refuse)
        assertTrue(shape.contains("LIVE_EDGE_SHAPE_YIELDS_TO_CANDIDATE_ADMISSION_8256"))
        // the refusal branch is the else of the yield: no supported admission, the shape still refuses and is counted
        assertTrue(shape.contains("} else if (shape != null) {\n            TradeShapeLearner7883.noteRefusal(l, shape)"))
        // the supported admission is the record's own specific edge, never a generic native pass
        assertTrue(gate.contains("CandidateAdmission8136.Terminal8136.ADMIT -> candidateSpecificAdmission8136 = admission8136?.specific == true"))
    }

    @Test fun theWatchFirstCellYieldsOnlyToASupportedSpecificAdmission() {
        val fn = src("engine/truth/LiveEdgeGate7877.kt").substringAfter("private fun specificRelease8153(").substringBefore("private val standDown8006")
        assertTrue(fn.contains("if (why != StrategyWatch8153.THIN_CELL_8153 && why != \"WATCH_FIRST_7994_CELL_NEGATIVE\") return null"))
        val cell = fn.substringAfter("if (why == \"WATCH_FIRST_7994_CELL_NEGATIVE\") {").substringBefore("return null\n        }")
        assertTrue(cell.contains("admission != null && admission.admitted && admission.specific"))
        assertTrue(cell.contains("return \"SUPPORTED_SETUP_8256\""))
        // a band proven negative (20+ labels across every source and age) is not released here
        assertTrue(!fn.contains("WATCH_FIRST_8019_BAND_NEGATIVE"))
    }
}
