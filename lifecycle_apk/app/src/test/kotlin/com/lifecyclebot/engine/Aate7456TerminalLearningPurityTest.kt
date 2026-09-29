package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7456TerminalLearningPurityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun invalid_entry_tactic_is_never_credited_to_close_time_tactic() {
        val s = src("engine/learning/TacticSwitcher.kt")
        val fn = s.substringAfter("fun onCanonicalTradeClosed6486(").substringBefore("fun historicalOutcomeCount6486")
        assertTrue(fn.contains("TACTIC_INVALID_ENTRY_FORENSIC_ONLY_7456"))
        val invalid = fn.substringAfter("if (elected == null)").substringBefore("val histKey")
        assertFalse(invalid.contains("onTradeClosed(lane, scoreBand, pnlPct)"))
    }

    @Test fun clean_entry_writers_keep_style_separate_from_coarse_tactic() {
        val e = src("engine/Executor.kt")
        assertFalse(e.contains("entryTactic = entryDeskHypothesis6599?.entryStyle"))
        assertFalse(e.contains("entryTactic = liveDeskHypothesis6599?.entryStyle"))
        assertTrue(e.contains("policyField6568(paperPolicySnapshot, \"entryTactic\")"))
        assertTrue(e.contains("policyField6568(ts.position.entryPolicySnapshot, \"entryTactic\")"))
    }

    @Test fun mathematical_edge_drops_forensic_terminal_provenance() {
        val s = src("engine/MathematicalEdgeEngine.kt")
        val fn = s.substringAfter("fun captureTerminal(").substringBefore("fun captureExitDecision")
        assertTrue(fn.contains("MATHEDGE_TERMINAL_FORENSIC_ONLY_7456"))
        assertTrue(fn.contains("\"RESTORED\""))
        assertTrue(fn.contains("\"REPLAY\""))
        assertTrue(fn.contains("\"REBUILT_FROM_RECEIPT\""))
        assertTrue(fn.contains("\"CARRY_USD_BASIS\""))
        assertTrue(fn.contains("\"UNOBSERVED_FILL\""))
    }

    @Test fun forensic_filter_runs_before_terminal_submit() {
        val s = src("engine/MathematicalEdgeEngine.kt")
        val fn = s.substringAfter("fun captureTerminal(").substringBefore("fun captureExitDecision")
        assertTrue(fn.indexOf("forensicOnly7456 != null") < fn.indexOf("submit(EdgeEvent("))
    }
}
