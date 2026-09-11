package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionSpineAcceptance6647
import com.lifecyclebot.engine.truth.ExecutionSpineAcceptanceWindow6647
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger

class Aate6735AcceptanceWitnessTest {
    @Test fun acceptance_reads_cannot_replay_or_heal_the_account() {
        val root = File("src/main/kotlin/com/lifecyclebot/engine/truth")
        val witness = File(root, "ExecutionSpineAcceptance6647.kt").readText()
        val audit = File(root, "AcceptanceInvariantAudit6441.kt").readText()
        assertFalse(witness.contains("reconcileForensicBoundary6666()"))
        assertTrue(witness.contains("ForensicReconciliation6635.deltas6647()"))
        assertTrue(audit.contains("ExecutionSpineAcceptanceWindow6647.lastCompletedResult6735()"))
        assertFalse(audit.contains("ExecutionSpineAcceptanceWindow6647.closeCompletedWindow()"))
    }

    @Test fun the_completed_witness_is_not_dependent_on_forensic_queue_capacity() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        val emit = source.substringAfter("private fun emitResult6735").substringBefore("private fun emitFailure6689")
        assertTrue(emit.contains("android.util.Log.i(\"AATE.ACCEPTANCE\""))
        assertTrue(emit.indexOf("android.util.Log.i") < emit.indexOf("PipelineHealthCollector.labelInc"))
        assertTrue(source.contains("windowStartMs=\${start.atMs}"))
    }

    @Test fun no_evidence_and_unknown_accounting_remain_failures() {
        val missing = ExecutionSpineAcceptance6647.Observation(
            durationMs = 120_000L, safety = 0L, v3 = 0L,
            bgSplitRuntimeIntakeZombie = 0L, configuredWorkers = 0,
            currentWorkerHeartbeats = 0, phantomSizedOnly = 0L, sizePending = 0L,
            fdgAllowWithoutIntent = 0L, dispatches = -1L,
            immutableIntentsForDispatches = -2L, terminalResultsForDispatches = -3L,
            cryptoOpenConfirmed = 0L, maxExitStartDelayCycles = 0L,
            exitStart = 0L, exitDone = 0L, canonicalOpen = 1L, exitEvaluations = 0L,
            supervisorForcedLeaseReleases = 0L, cashDeltaSol = Double.NaN,
            basisDeltaSol = Double.NaN, realizedDeltaSol = Double.NaN,
            quantityDeltaRaw = BigInteger.ONE, heroJournalParityFail = 0L,
            invalidGrowthOrLearningUpdates = 0L,
        )
        val result = ExecutionSpineAcceptance6647.evaluate(missing)
        assertFalse(result.passed)
        for (failure in listOf("CASH_DELTA", "BASIS_DELTA", "REALIZED_DELTA", "QUANTITY_DELTA",
            "DISPATCH_INTENT_CARDINALITY", "DISPATCH_TERMINAL_CARDINALITY")) {
            assertTrue(failure, failure in result.failures)
        }
    }
}
