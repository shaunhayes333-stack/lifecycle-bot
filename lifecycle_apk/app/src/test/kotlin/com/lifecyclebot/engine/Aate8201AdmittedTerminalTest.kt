package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * 5.0.8200: 200 candidate-specific ADMITs, 0 FDG allows, 1,950 candle seeds skipped on provider cooldown. 8201: the live
 * edge gate does not re-veto an admitted candidate on lane-prior setup/timing/tactic re-reads, every admitted candidate's
 * terminal is named, and a short provider cooldown is waited out instead of becoming "no bars".
 */
class Aate8201AdmittedTerminalTest {
    private val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()

    @Test fun admittedCandidatesAreNotReVetoedByLanePriorSetupReads() {
        val body = gate.substringAfter("val admittedJudged8201 = candidateSpecificAdmission8136")
        // each 8094 lane-prior re-read is guarded by the admission judgement
        assertTrue(body.contains("if (!admittedJudged8201 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.timingNegative8094"))
        assertTrue(body.contains("if (proven8059 == null && !admittedJudged8201 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.pickedSetupNegative8094"))
        assertTrue(body.contains("if (proven8059 == null && !admittedJudged8201 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094"))
        assertTrue(body.contains("ADMITTED_SPECIFIC_DUPLICATE_VETO_STOOD_DOWN_8201_"))
        // the judgement is the candidate-specific admission only; a generic-prior ADMIT still meets every later gate
        assertEquals(1, gate.split("val admittedJudged8201 = candidateSpecificAdmission8136").size - 1)
        // hard safety and realised live-close evidence stay ahead of it
        assertTrue(gate.indexOf("earlyLaunchRefusal8048(ts)") < gate.indexOf("val admittedJudged8201"))
        assertTrue(gate.indexOf("LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs)") < gate.indexOf("val admittedJudged8201"))
        assertTrue(gate.indexOf("val admittedJudged8201") < gate.indexOf("CHASING_SPIKE_8065_\$l8059"))
    }

    @Test fun everyAdmittedCandidateTerminalIsNamed() {
        val terminals = Regex("admittedTerminal8201\\(ts, lane, ").findAll(gate).count()
        // V5.0.8243 — the spike heuristic no longer ends an admitted candidate (it stands down, counted); setup refusal, watch-first and the core verdict name the terminal.
        assertTrue("setup refusal, watch-first and the core verdict each name the terminal", terminals >= 3)
        // Superseded by V5.0.8290/8298: the spike-chase rule names the admitted terminal again; the CHASE stand-down holds
        // only for a lane whose realised live record earned it (RegressionRestore8298).
        assertTrue(gate.contains("val admittedJudged8201 = candidateSpecificAdmission8136 && RegressionRestore8298.specificStandDownEarned8298(l8059, nowMs)"))
        assertTrue(gate.contains("admittedTerminal8201(ts, lane, \"CHASING_SPIKE_8065\", candidateVersion)"))
        assertTrue(gate.contains("""if (!paper && candidateSpecificAdmission8136) admittedTerminal8201(ts, lane, why ?: "ALLOW", candidateVersion)"""))
        assertTrue(gate.contains("""PipelineHealthCollector.labelInc("ADMITTED_THEN_${'$'}{terminal.take(48)}_8201_${'$'}l")"""))
    }

    @Test fun shortProviderCooldownIsWaitedNotConvertedToNoBars() {
        val orch = java.io.File("src/main/kotlin/com/lifecyclebot/engine/DataOrchestrator.kt").readText()
        val block = orch.substringAfter("if (slot.cooldown) {").substringBefore("return emptyList()")
        assertTrue(block.contains("if (tries == 1 && slot.waitMs in 1L..SEED_COOLDOWN_WAIT_MAX_MS_8201)"))
        assertTrue(block.contains("OHLCV_SEED_WAITED_SHORT_COOLDOWN_8201"))
        assertTrue(block.contains("continue"))
        assertTrue(orch.contains("private val SEED_COOLDOWN_WAIT_MAX_MS_8201 = 15_000L"))
        assertTrue(orch.contains("OHLCV_SEED_SKIPPED_PROVIDER_COOLDOWN_7809"))           // a long cooldown still ends the attempt
    }
}
