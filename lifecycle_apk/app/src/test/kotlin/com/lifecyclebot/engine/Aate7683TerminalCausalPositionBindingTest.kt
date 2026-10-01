package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7683TerminalCausalPositionBindingTest {
    @Test fun terminalPositionStagesBindToOpenCausalRecordNotCurrentCandidateVersion() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(s.contains("terminalPositionStage7683"))
        assertTrue(s.contains("latestUnfinalizedOpenKey6713(mint, lane)"))
        assertTrue(s.contains("SPECIALIST_TERMINAL_POSITION_REBOUND_7683"))
        val terminal = s.substringAfter("if (terminalPositionStage7683)")
            .substringBefore("else if (mint.isNotBlank() && candidateVersion6647 > 0L)")
        assertFalse(terminal.contains("LaneExecutionCoordinator.candidateVersionFor"))
        assertFalse(terminal.contains("activeExecutionIntent6519"))
    }

    @Test fun legacyOrRestoredTerminalWithoutOpenRecordIsNamedNotRebound() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(s.contains("SPECIALIST_TERMINAL_NO_OPEN_CAUSAL_RECORD_7683"))
        assertTrue(s.contains("forensic-only instead of poisoning the generic"))
    }

    @Test fun genericUnresolvedCounterNowRemainsEntryLineageSignal() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val terminalBranch = s.substringAfter("if (terminalPositionStage7683)")
            .substringBefore("else if (mint.isNotBlank() && candidateVersion6647 > 0L)")
        assertFalse(terminalBranch.contains("SPECIALIST_CAUSAL_UNRESOLVED_ID_REJECTED_6647"))
    }
}
