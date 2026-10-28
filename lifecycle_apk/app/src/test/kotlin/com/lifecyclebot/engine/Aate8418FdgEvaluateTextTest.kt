package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — owner: "fdg was never meant to be retouched, only tweaked so the lanes had clean admission".
 * FinalDecisionGate.evaluate()'s sealed-economics line and return block are the pre-8395 text (03a800b4e^) exactly; the
 * stricter 8395 final-economics verdict lives outside evaluate() (sealedCostHolds8209 -> FdgGate8365 -> rememberFdgVerdict).
 */
class Aate8418FdgEvaluateTextTest {
    private val pre8395Block = """        // V5.0.8209 — the admission's net-of-cost proof was quoted at proposedSizeSol; a materially different sealed size is re-quoted here.
        val sealedCostOk8209 = executableSize7835 <= 0.0 || sealedCostHolds8209(ts, canonicalPrimaryLane6658, candidateAdmission8136, executableSize7835, checks)
        if (!sealedCostOk8209 && blockReason == null) blockReason = "ADMISSION_COST_REQUOTE_FAILED_8209"

        return rememberFdgVerdict(fdgCacheKey, FinalDecision(
            shouldTrade = shouldTradeFinal &&
                canonicalEconomicApproval7548 &&
                policyAllows7848 && executableSize7835 > 0.0 && sealedCostOk8209,
            mode = mode,
            approvalClass = approvalClass,
            quality = candidate.finalQuality,
            confidence = adjustedConfidence,
            edge = edgeVerdict,
            blockReason = blockReasonFinal ?: when {
                !policyAllows7848 -> "CANONICAL_ENVELOPE_BLOCK_7835"
                shouldTradeFinal && !canonicalEconomicApproval7548 -> "NON_ECONOMIC_APPROVAL_7835"
                shouldTradeFinal && executableSize7835 <= 0.0 -> "SIZE_NOT_EXECUTABLE_7835"
                !shouldTradeFinal -> "FINAL_NOT_EXECUTABLE_UNNAMED_8203"  // V5.0.8203 — never a denied decision without a terminal
                else -> null
            },
            blockLevel = blockLevelFinal,
            sizeSol = executableSize7835,
            tags = tags,
            mint = ts.mint,
            symbol = ts.symbol,
            approvalReason = approvalReason,
            gateChecks = checks,
            effectiveEntryScore7687 = effectiveGateScore6025.toInt().coerceIn(0, 100),
            candidateVersion7835 = candidateVersion7623,
            canonicalLane7835 = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(canonicalPrimaryLane6658),
        ))
    }

"""

    @Test fun evaluateReturnBlockIsThePre8395Text() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val start = src.indexOf("        // V5.0.8209 — the admission's net-of-cost proof was quoted at proposedSizeSol")
        val end = src.indexOf("    private fun resolveExecutableSize7835(")
        assertTrue(start > 0 && end > start)
        assertTrue("the block sits inside evaluate()", src.lastIndexOf("    fun evaluate(", start) > 0 &&
            src.indexOf("    fun evaluate(", start) !in (start + 1) until end)
        assertEquals(pre8395Block, src.substring(start, end))
    }
}
