package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.7819 — QUALITY/BLUECHIP never recorded a MARK stage because the cycle
 * PRIMARY specialist's sealed BUY could not replace a non-primary specialist's
 * intent for the same mode/mint/version, while the election followed the
 * primary. TradeAuthorizer 7812 then deferred the primary forever
 * (AWAIT_FDG_SEAL_7812) and the rescue lane lost the election.
 */
class Aate7819QualityBluechipMarkTest {

    private fun intent(
        mint: String, lane: String, version: Long, withMark: Boolean = false,
    ) = ExecutableOpenGate.ExecutionIntent(
        attemptId = ExecutableOpenGate.canonicalExecutionKey(mint, mode = "PAPER", lane = lane, candidateVersion = version),
        candidateId = "$mint:$version", candidateVersion = version,
        mint = mint, mode = "PAPER", canonicalLane = lane,
        fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = version,
        resolvedSize = 0.1, createdAt = System.currentTimeMillis(), symbol = "T7819",
        effectiveEntryScore7256 = 70,
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
        decisionAuthorityId6613 = "FDG:$version",
        fdgDecisionId6613 = "PAPER:$mint:$version:$lane",
        fdgEvidence6613 = "fdgCan=true;hardNo=0",
        executableMarkSource6613 = if (withMark) "DEX_POOL" else "",
        executableMarkTimestampMs6613 = if (withMark) System.currentTimeMillis() else 0L,
        executableMarkPriceUsd6613 = if (withMark) 0.0123 else 0.0,
    )

    private fun primary(mint: String, version: Long, lane: String) =
        ExecutableOpenGate.recordEntryAuthority6487(
            mint, version,
            ExecutableEntryAuthority6450.Decision(ExecutableEntryAuthority6450.Verdict.ALLOW, 0.1, "test7819"),
            primaryLane7189In = lane,
        )

    @Test fun primaryBluechipSealReplacesEarlierRescueLaneIntent() {
        ExecutableOpenGate.resetForTests()
        SpecialistCausalFunnel6625.resetForTest()
        val mint = "B1ue7819chipPrimaryMintAAAAAAAAAAAAAAAAAAA"
        val v = System.nanoTime()
        primary(mint, v, "BLUECHIP")
        val rescue = intent(mint, "QUALITY", v)
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(rescue))
        val owner = intent(mint, "BLUECHIP", v, withMark = true)
        val sealed = ExecutableOpenGate.registerCanonicalIntent6554(owner)
        assertEquals("BLUECHIP", sealed?.canonicalLane)
        assertNotNull(ExecutableOpenGate.activeExecutionIntentForLane7809("PAPER", mint, "BLUECHIP"))
        assertNull(ExecutableOpenGate.activeExecutionIntentForLane7809("PAPER", mint, "QUALITY"))
        assertNull(ExecutableOpenGate.ticketForAttempt(rescue.attemptId))
        // The sealed executable mark now reaches the BLUECHIP funnel.
        val counts = SpecialistCausalFunnel6625.stageCounts6625("BLUECHIP")
        assertEquals(1, counts[SpecialistCausalFunnel6625.Stage.MARK] ?: 0)
    }

    @Test fun primaryQualitySealReplacesTreasuryRescueIntent() {
        ExecutableOpenGate.resetForTests()
        val mint = "Qua1ity7819PrimaryMintBBBBBBBBBBBBBBBBBBBBB"
        val v = System.nanoTime()
        primary(mint, v, "QUALITY")
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "TREASURY", v)))
        assertEquals("QUALITY", ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "QUALITY", v))?.canonicalLane)
        assertNotNull(ExecutableOpenGate.activeExecutionIntentForLane7809("PAPER", mint, "QUALITY"))
    }

    @Test fun withoutRecordedPrimaryFirstSpecialistSealIsKept() {
        ExecutableOpenGate.resetForTests()
        val mint = "NoPrimary7819MintCCCCCCCCCCCCCCCCCCCCCCCCCC"
        val v = System.nanoTime()
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "SHITCOIN", v)))
        assertEquals("SHITCOIN", ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "QUALITY", v))?.canonicalLane)
        assertNull(ExecutableOpenGate.activeExecutionIntentForLane7809("PAPER", mint, "QUALITY"))
    }

    @Test fun nonPrimaryLaneNeverDisplacesThePrimarySeal() {
        ExecutableOpenGate.resetForTests()
        val mint = "KeepPrimary7819MintDDDDDDDDDDDDDDDDDDDDDDDD"
        val v = System.nanoTime()
        primary(mint, v, "BLUECHIP")
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "BLUECHIP", v)))
        assertEquals("BLUECHIP", ExecutableOpenGate.registerCanonicalIntent6554(intent(mint, "QUALITY", v))?.canonicalLane)
    }

    @Test fun bluechipPostAuthNoLongerStampsAnUnsealedMark() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val auth = b.substringAfter("val blueChipAuth6494 = TradeAuthorizer.authorize(")
            .substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertFalse(auth.contains("recordDeskStage(\"BLUECHIP\", \"MARK_READY\""))
        assertTrue(auth.contains("stampBlueChipSealedMark7819(blueChipAuth6494.attemptId)"))
        val helper = b.substringAfter("private fun stampBlueChipSealedMark7819(").substringBefore("private fun processTokenCycle(")
        assertTrue(helper.contains("executableMarkTimestampMs6613 > 0L"))
        assertTrue(helper.contains("executableMarkSource6613.isNotBlank()"))
    }
}
