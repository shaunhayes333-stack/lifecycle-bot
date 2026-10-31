package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8056 — a proven-paying setup is bought at first sight and a runner setup is held as a runner. */
class Aate8056ProvenRunnerSetupTest {
    private fun stat(xs: List<Double>) = CortexLedger7885.Stat().also { s -> xs.forEach { s.n += 1.0; s.sum += it; s.sumSq += it * it } }

    @Test fun provenBar() {
        // 61 labels averaging +23% with a fat tail: proven.
        assertTrue(LanePlaybook7907.setupProvenPositive7948(stat(List(55) { 5.0 } + List(6) { 180.0 })))
        // 20 labels: not enough to prove.
        assertFalse(LanePlaybook7907.setupProvenPositive7948(stat(List(20) { 15.0 })))
    }

    @Test fun wired() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(g.contains("LanePlaybook7907.provenSetup8056(ts, l, nowMs)"))
        assertTrue(g.contains("RunnerGrab7967.holdAsRunner7972(ts, nowMs)"))
        val c = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        assertTrue(c.contains("C3_STOOD_DOWN_LANE_SETUP_PROVEN_8056"))
    }
}

/** V5.0.8059 — live money only goes into a coin showing a proven setup; everything else is watched and graded. */
class Aate8059ProvenOnlyLiveTest {
    @Test fun gateIsFirstAndLiveOnly() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        // superseded (8480, owner directive "no evidence is not proven bad"): the door is removed; live is still gated by the measured-negative doors
        assertFalse(g.contains("return \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        assertTrue(g.indexOf("watchFirst7994(ts, lane, nowMs, candidateVersion, admission8136)") > 0)
        assertTrue(g.contains("if (!paper || rehearsal8393) {\n            val l8059"))
        assertTrue(g.contains("val r8094 = \"TACTIC_RECORD_LOSING_8094\""))
    }
}

/** V5.0.8060 — any measured edge (proven cell, runner cell, specialist, tail ticket) carries live money, not only a named setup. */
class Aate8060MeasuredEdgeTest {
    @Test fun cellBar() {
        assertTrue(com.lifecyclebot.engine.truth.LiveEdgeGate7877.cellProven8060(211, 5.0, 1.5))
        assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.cellProven8060(20, 9.0, 1.0))
        assertFalse(com.lifecyclebot.engine.truth.LiveEdgeGate7877.cellProven8060(100, 1.0, 2.0))
        // superseded (8480): the proven-only door that read this bar is removed; the measured edge still feeds the gate
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
            .contains("measuredEdge8060(ts, l8059, nowMs) || (try { com.lifecyclebot.engine.RunnerTicket8073.admits8073(ts, nowMs) }"))
    }
}
