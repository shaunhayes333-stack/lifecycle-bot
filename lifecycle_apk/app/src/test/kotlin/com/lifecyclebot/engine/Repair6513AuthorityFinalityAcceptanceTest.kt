package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Repair6513AuthorityFinalityAcceptanceTest {
    @Before fun reset() {
        RuntimeConfigOverlay.resetForTests()
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()
        ExecutableEntryAuthority6450.resetForTest6487()
        ToxicModeCircuitBreaker.resetForTests()
        BirdeyeBudgetGate.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
    }

    @Test fun projectSniperAuthorityRejectsStaleStandardAndSealsOnlySameLaneTicket() {
        val mint = "Authority6513${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        ExecutableOpenGate.recordEntryAuthority6487(mint, cv, ExecutableEntryAuthority6450.gate("PROJECT_SNIPER", mint, 1.0))
        ExecutableOpenGate.recordFdg(mint, "RICK", "PROJECT_SNIPER", true, null,
            signal = "WATCH", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 5_000.0,
            preFdgVerdict = "BUY", candidateVersion = cv, entryScore = 84)

        val staleVerdict = ExecutableOpenGate.canOpenExecutablePosition(
            mint, "RICK", 90, "PAPER", "STANDARD", "test.stale.standard.dispatch",
            liveLiquidityUsd = 5_000.0, liveSafetyTier = "SAFE", preResolvedSizeSol6490 = 0.05,
        )
        assertFalse(staleVerdict.allowed)
        assertEquals("SPECIALIST_NOT_ELECTED", staleVerdict.reason)
        val verdict = ExecutableOpenGate.canOpenExecutablePosition(
            mint, "RICK", 90, "PAPER", "PROJECT_SNIPER", "test.same.lane.dispatch",
            liveLiquidityUsd = 5_000.0, liveSafetyTier = "SAFE", preResolvedSizeSol6490 = 0.05,
        )
        assertTrue("${verdict.reason} active=${ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv)}", verdict.allowed)
        val ticket = requireNotNull(ExecutableOpenGate.ticketForAttempt(verdict.attemptId))
        assertEquals("PROJECT_SNIPER", ticket.primaryLane)
        assertEquals(ticket.primaryLane, ticket.lane)
        assertEquals("BUY", ticket.fdgVerdict)
        assertEquals("BUY", ticket.authoritativeSignal)
        assertEquals(cv, ticket.candidateVersion)
        assertTrue(ticket.authorityVersion > 0L)
    }

    @Test fun shadowStandardCannotPublishTicketWithoutPrimaryAuthority() {
        val mint = "Shadow6513${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        ExecutableOpenGate.recordFdg(mint, "SHADOW", "STANDARD", true, null,
            signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 5_000.0,
            preFdgVerdict = "BUY", candidateVersion = cv)
        val verdict = ExecutableOpenGate.canOpenExecutablePosition(
            mint, "SHADOW", 90, "PAPER", "STANDARD", "test.shadow.read.only",
            liveLiquidityUsd = 5_000.0, liveSafetyTier = "SAFE", preResolvedSizeSol6490 = 0.05,
        )
        assertFalse(verdict.allowed)
        assertNull(ExecutableOpenGate.ticketForAttempt(verdict.attemptId))
    }

    @Test fun paperFinalityBeginsAtTransactionAndReservePrecedesEconomicDebit() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val begin = src.indexOf("PaperEntryFinalityAuthority6497.beginAttempt(entryFinalityId6497")
        val reserve = src.indexOf("canonicalCreated6485 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.mirrorBuyAttempt(", begin)
        val debit = src.indexOf("PaperAccountLedger6430.onBuy(actualSol, fee6485)", reserve)
        val fill = src.indexOf("ExecutorCanonicalMirror6442.mirrorBuyFill(", debit)
        val journal = src.indexOf("recordTrade(ts, trade)", fill)
        val terminal = src.indexOf("PaperEntryFinalityAuthority6497.markOk(entryFinalityId6497)", journal)
        assertTrue(begin > 0 && reserve > begin && debit > reserve && fill > debit && journal > fill && terminal > journal)
        assertTrue(src.contains("PAPER_BUY_TERMINAL_REPLAY_RECOVERED_6513"))
        assertTrue(src.contains("IdempotencyKeyStore6437.terminalFor(existingIdem6513)"))
    }

    @Test fun canonicalExitHydrationUsesCanonicalEntryAndFreshTokenMapThenQueuesMissingMark() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val canon = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val map = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        assertTrue(canon.contains("val entryPriceUsd: Double = 0.0"))
        assertTrue(map.contains("fun cachedForExit6513"))
        assertTrue(bot.contains("TokenMapAuthority.cachedForExit6513(cp.mint)"))
        assertTrue(bot.contains("entryPrice = old.entryPrice.takeIf { it > 0.0 } ?: canonicalEntryPrice6513"))
        assertTrue(canon.contains("entryPriceUsd = repairedPrice6519"))
        assertTrue(bot.contains("CANONICAL_EXIT_MARK_REFRESH_QUEUED_6513"))
        assertTrue(bot.contains("scope.launch(kotlinx.coroutines.Dispatchers.IO)"))
    }

    @Test fun authorityMismatchOutranksProviderDegradation() {
        val root = File("src/main/kotlin/com/lifecyclebot/engine/truth/RootCauseClassifier6471.kt").readText()
        assertTrue(root.indexOf("EXEC_AUTHORITY_STATE_MISMATCH") < root.indexOf("DATA_PROVIDER_AUTH_LOCKOUT_6468"))
    }
}
