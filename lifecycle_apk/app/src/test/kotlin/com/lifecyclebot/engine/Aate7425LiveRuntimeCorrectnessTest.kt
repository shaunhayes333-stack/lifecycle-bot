package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7425LiveRuntimeCorrectnessTest {
    @Test fun cryptoEconomicModeReadsCanonicalRuntimeAuthority() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(src.contains("authoritativePaperMode7425()"))
        assertTrue(src.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue(src.contains("mode = if (authoritativePaperMode7425()) \"PAPER\" else \"LIVE\""))
        assertTrue(src.contains("routeAvailable = authoritativePaperMode7425() || candidate.executionAdapter != \"NONE\""))
        assertTrue(src.contains("CRYPTO_MODE_MIRROR_HEALED_7425"))
    }

    @Test fun retryableCryptoObservationReleasesLeaseWithoutFakeTerminal() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val progress = src.substringAfter("fun markEvaluationProgress6570").substringBefore("fun markEvaluationDisposition6567")
        assertTrue(progress.contains("releaseAtPassBoundary7425(key)"))
        assertTrue(progress.contains("releaseEvaluationForRetry7418(tok, key)"))
        assertTrue(progress.contains("CRYPTO_EVAL_RETRYABLE_PROGRESS_RELEASED_7425"))
        assertTrue(src.contains("SHARED_INTELLIGENCE_BACKLOG_COALESCED"))
        assertTrue(src.contains("OBSERVE is deliberately"))
        assertTrue(progress.contains("if (!releaseAtPassBoundary7425(key))"))
        assertTrue(src.contains("retryReleased_is_nonterminal_completed_pass_not_missing"))
        assertFalse(progress.contains("markEvaluationDisposition6567(tok, key)"))
    }

    @Test fun protectiveGivebackExitsBypassRecoveryHydrationLock() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = src.substringAfter("// V5.0.7425 — a profit-lock/trailing/giveback exit is protective")
            .substringBefore("// V5.9.495z39 — operator spec item 1: amount-violation lock.")
        assertTrue(block.contains("rLock.contains(\"PROFIT_LOCK\")"))
        assertTrue(block.contains("rLock.contains(\"TRAIL\")"))
        assertTrue(block.contains("rLock.contains(\"GIVEBACK\")"))
        assertTrue(block.contains("RECOVERY_LOCK_PROTECTIVE_EXIT_PUNCH_THROUGH_7425"))
        assertFalse(block.contains("rLock.contains(\"TAKE_PROFIT\")"))
    }

    @Test fun healthReportNeverCallsLandedLiveTradingFullyBlocked() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(src.contains("FDG_EXEC_TELEMETRY_POPULATION_MISMATCH_7425"))
        assertTrue(src.contains("LIVE is NOT fully blocked"))
        assertFalse(src.contains("FDG_LIVE_ALLOW=0 — live trading is fully blocked"))
    }
}
