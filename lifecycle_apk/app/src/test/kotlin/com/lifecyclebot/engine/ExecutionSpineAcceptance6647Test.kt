package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionSpineAcceptance6647
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class ExecutionSpineAcceptance6647Test {
    private fun clean() = ExecutionSpineAcceptance6647.Observation(
        durationMs = 120_000L, safety = 1L, v3 = 1L,
        bgSplitRuntimeIntakeZombie = 0L,
        configuredWorkers = 12, currentWorkerHeartbeats = 12,
        phantomSizedOnly = 0L, sizePending = 0L, fdgAllowWithoutIntent = 0L,
        dispatches = 1L, immutableIntentsForDispatches = 1L, terminalResultsForDispatches = 1L,
        cryptoOpenConfirmed = 1L, maxExitStartDelayCycles = 2L,
        exitStart = 1L, exitDone = 1L, canonicalOpen = 1L, exitEvaluations = 1L,
        supervisorForcedLeaseReleases = 0L,
        cashDeltaSol = 0.0, basisDeltaSol = 0.0, realizedDeltaSol = 0.0,
        quantityDeltaRaw = BigInteger.ZERO, heroJournalParityFail = 0L,
        invalidGrowthOrLearningUpdates = 0L,
    )

    @Test fun complete_120_second_tape_passes() {
        assertTrue(ExecutionSpineAcceptance6647.evaluate(clean()).passed)
    }

    @Test fun one_exit_sweep_may_be_in_flight_at_sampling_boundary() {
        assertTrue(ExecutionSpineAcceptance6647.evaluate(clean().copy(exitStart = 2L, exitDone = 1L)).passed)
        assertFalse(ExecutionSpineAcceptance6647.evaluate(clean().copy(exitStart = 3L, exitDone = 1L)).passed)
    }

    @Test fun every_mandatory_fault_fails_the_build_contract() {
        val bad = clean().copy(
            durationMs = 119_999L, safety = 0L, v3 = 0L,
            currentWorkerHeartbeats = 11, phantomSizedOnly = 1L, sizePending = 1L,
            fdgAllowWithoutIntent = 1L, terminalResultsForDispatches = 0L,
            cryptoOpenConfirmed = 0L, maxExitStartDelayCycles = 3L,
            exitDone = 0L, exitEvaluations = 0L, supervisorForcedLeaseReleases = 1L,
            cashDeltaSol = 0.01, basisDeltaSol = 0.01, realizedDeltaSol = 0.01,
            quantityDeltaRaw = BigInteger.ONE, heroJournalParityFail = 1L,
            invalidGrowthOrLearningUpdates = 1L,
        )
        assertFalse(ExecutionSpineAcceptance6647.evaluate(bad).passed)
        var threw = false
        try { ExecutionSpineAcceptance6647.requirePassing(bad) } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test fun source_contracts_remove_rejected_spine_patterns() {
        val root = File("src/main/kotlin/com/lifecyclebot")
        val bot = File(root, "engine/BotService.kt").readText()
        val crypto = File(root, "perps/CryptoAltTrader.kt").readText()
        val replay = File(root, "engine/truth/JournalEconomicReplay6619.kt").readText()
        val unified = File(root, "engine/truth/UnifiedAccountSnapshot6635.kt").readText()
        val authorizer = File(root, "engine/TradeAuthorizer.kt").readText()
        val supervisor = bot.substringAfter("private fun fireSupervisorWorkers(").substringBefore("private fun launchUniversalSlSweepAsync")
        assertFalse(supervisor.contains("GlobalScope.launch"))
        assertTrue(bot.contains("CanonicalPositionAuthority6441.openPositions().size"))
        assertTrue(bot.contains("CANONICAL_OPEN_ROUTED_DIRECT_TO_EXIT_6647"))
        assertFalse(crypto.contains("positionCounter"))
        assertFalse(crypto.contains("TradeAuthorizer.authorize("))
        assertTrue(crypto.contains("id             = \"ALT:${'$'}{canonicalCryptoIntent6565.attemptId}\""))
        assertTrue(authorizer.contains("!finality.allowed || finality.shadowOnly"))
        assertFalse(replay.contains("gross - t.pnlSol"))
        assertFalse(replay.contains("if (openCost < 0.0) openCost = 0.0"))
        assertTrue(unified.contains("RETAIN_LAST_RECONCILED"))
    }
}
