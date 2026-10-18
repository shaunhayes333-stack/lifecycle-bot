package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * 5.0.8202 live: FDG 0 allowed / 3,404 blocked; 36 candidate-specific floor admissions, every one then hard-blocked on the
 * generic "Signal is WAIT, not BUY". 8203: one sealed executable signal, and no denied decision without a terminal.
 */
class Aate8203ExecutableSignalTest {
    private val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

    @Test fun specificAdmitForElectedOwnerSupersedesGenericWait() {
        val block = fdg.substringAfter("private fun baseSignalVerdict8203(").substringBefore("private fun unnamedBaseSignalTerminal8203(")
        // the supersession needs ALL of: stale generic WAIT, an elected specialist owner, a candidate-SPECIFIC ADMIT with a basis
        assertTrue(block.contains("val specificAdmitSupersedes8203 = staleBaseSignalBlock && specialistLane?.isNotBlank() == true &&"))
        assertTrue(block.contains("candidateAdmission8136?.let { it.specific && it.admitted && it.basis.isNotBlank() } == true"))
        assertTrue(block.contains("return null to true"))
        assertTrue(block.contains("FDG_BASE_SIGNAL_SUPERSEDED_BY_SPECIFIC_ADMIT_8203_"))
        // the generic-prior ADMIT path is unchanged and still hard-blocks, counted
        assertTrue(block.contains("if (staleBaseSignalBlock && laneNameForBaseSignal != null) {"))
        assertTrue(block.contains("return candidate.blockReason to false"))
        assertTrue(fdg.contains("if (baseSignal8203.first != null) { blockReason = baseSignal8203.first; blockLevel = BlockLevel.HARD }"))
        assertTrue(block.contains("FDG_BASE_SIGNAL_BLOCK_AFTER_"))
        // HARD_NO_BUY stays a terminal return before any of this
        assertTrue(fdg.indexOf("PREFDG_HARD_NO_BUY_TERMINAL_7433") < fdg.indexOf("val baseSignal8203 = baseSignalVerdict8203("))
        // admission (safety > specific negative > specific positive) is assessed before the base-signal comparison
        assertTrue(fdg.indexOf("val candidateAdmission8136 = try {") < fdg.indexOf("val baseSignal8203 = baseSignalVerdict8203("))
        // the final executable signal consumes the superseded flag
        assertTrue(fdg.contains("var shouldTrade = (blockReason == null || blockReason == \"PROBE_ONLY\") && (candidate.shouldTrade || baseSignalMismatchIgnoredForLane || \"cortex_choice_7915\" in tags)"))
    }

    @Test fun noDeniedDecisionWithoutATerminal() {
        val after = fdg.substringAfter("var shouldTrade = (blockReason == null")
        assertTrue(after.contains("if (!shouldTrade && blockReason == null) {"))
        assertTrue(after.contains("blockReason = unnamedBaseSignalTerminal8203(candidate, candidateAdmission8136, laneName, checks)"))
        assertTrue(after.contains("blockLevel = BlockLevel.EDGE"))
        assertTrue(fdg.contains("return \"BASE_SIGNAL_NOT_EXECUTABLE_8203:"))
        assertTrue(fdg.contains("FDG_BASE_SIGNAL_NOT_EXECUTABLE_8203_"))
        assertTrue(after.indexOf("if (!shouldTrade && blockReason == null) {") < after.indexOf("if (!shouldTrade && blockReason != null) {"))
        val fin = fdg.substringAfter("return rememberFdgVerdict(fdgCacheKey, FinalDecision(")
        assertTrue(fin.contains("!shouldTradeFinal -> \"FINAL_NOT_EXECUTABLE_UNNAMED_8203\""))
        assertTrue(fin.indexOf("SIZE_NOT_EXECUTABLE_7835") < fin.indexOf("FINAL_NOT_EXECUTABLE_UNNAMED_8203"))
    }
}
