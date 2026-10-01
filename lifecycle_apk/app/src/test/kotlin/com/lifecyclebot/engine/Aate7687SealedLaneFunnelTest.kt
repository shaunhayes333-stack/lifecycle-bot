package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import org.junit.Assert.*
import org.junit.Test

class Aate7687SealedLaneFunnelTest {
    @Test fun sealedApprovalsReachTheSameIntentRecordAcrossAllTwelveLanes() {
        ExecutableOpenGate.resetForTests()
        SpecialistCausalFunnel6625.resetForTest()
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
        val mint = "So11111111111111111111111111111111111111112"
        val seed = System.nanoTime()
        for ((index, lane) in lanes.withIndex()) {
            val version = seed + index
            val intent = ExecutableOpenGate.ExecutionIntent(
                attemptId = ExecutableOpenGate.canonicalExecutionKey(mint, mode = "PAPER",
                    lane = lane, candidateVersion = version),
                candidateId = "$mint:$version", candidateVersion = version,
                mint = mint, mode = "PAPER", canonicalLane = lane,
                fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = version,
                resolvedSize = 0.1, createdAt = System.currentTimeMillis(), symbol = "TEST",
                effectiveEntryScore7256 = 72,
                finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
                decisionAuthorityId6613 = "FDG:$version",
                fdgDecisionId6613 = "PAPER:$mint:$version:$lane",
                fdgEvidence6613 = "fdgCan=true;hardNo=0",
            )
            assertNotNull(lane, ExecutableOpenGate.registerCanonicalIntent6554(intent))
            assertNotNull(lane, ExecutableOpenGate.registerCanonicalIntent6554(intent))
            val counts = SpecialistCausalFunnel6625.stageCounts6625(lane)
            assertEquals(lane, 1, counts[SpecialistCausalFunnel6625.Stage.INTENT] ?: 0)
            assertEquals(lane, 1, counts[SpecialistCausalFunnel6625.Stage.FDG] ?: 0)
            // Sealing approval alone must not invent marks, size or execution.
            assertEquals(lane, 0, counts[SpecialistCausalFunnel6625.Stage.MARK] ?: 0)
            assertEquals(lane, 0, counts[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0)
            assertNull(lane, ExecutableOpenGate.registerCanonicalIntent6554(
                intent.copy(fdgAllowed = false),
            ))
        }
    }
}
