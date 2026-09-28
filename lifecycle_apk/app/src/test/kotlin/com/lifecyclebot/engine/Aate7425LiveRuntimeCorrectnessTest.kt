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
        assertTrue(src.contains("retryReleased_is_nonterminal_completed_pass_not_missing"))
        assertFalse(progress.contains("markEvaluationDisposition6567(tok, key)"))
    }

    @Test fun healthReportNeverCallsLandedLiveTradingFullyBlocked() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(src.contains("FDG_EXEC_TELEMETRY_POPULATION_MISMATCH_7425"))
        assertTrue(src.contains("LIVE is NOT fully blocked"))
        assertFalse(src.contains("FDG_LIVE_ALLOW=0 — live trading is fully blocked"))
    }
}
