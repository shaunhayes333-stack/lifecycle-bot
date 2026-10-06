package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.MissingMarkExitVeto6835
import com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450
import com.lifecyclebot.engine.truth.WholePositionEconomics7835
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Aate7835AuditBoundaryTest {
    @Test fun partial_winner_and_losing_runner_are_one_profitable_position() {
        // Buy 1 SOL + 0.01 fee. Sell half for 1 SOL, then half for 0.45 SOL.
        val position = CanonicalPositionAuthority6441.Position(
            positionId = "whole7835", mode = "paper", mint = "mint", symbol = "TEST", lane = "CORE",
            runId = "run", openedAtMs = 1L, entryCostSol = 1.0,
            remainingQtyRaw = BigInteger.ZERO, originalQtyRaw = BigInteger.valueOf(100),
            soldCostBasisSol = 1.0, realizedPnlSol = 0.43, realizedProceedsSol = 1.45,
            feesSol = 0.03, tokenDecimals = 0, lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            lastMutationMs = 2L, quarantineReason = "",
        )
        val outcome = WholePositionEconomics7835.from(position)
        assertEquals(0.42, outcome.netSol, 1e-9)
        assertEquals(42.0, outcome.returnPct, 1e-9)
        assertEquals(0.45, outcome.grossSol, 1e-9)
    }

    @Test fun stale_and_nonfinite_marks_cannot_create_a_new_protective_trigger() {
        val scheduler = ProtectiveExitScheduler6450
        assertNull(scheduler.evaluate("stale7835", "M", 1.0, 2.0, 1.5, 3.0, 2.0, 120_001L))
        assertNull(scheduler.evaluate("nan7835", "M", Double.NaN, 2.0, 1.5, 3.0, 2.0, 0L))
        assertNull(scheduler.latch("stale7835"))
    }

    @Test fun catastrophe_upgrades_a_latched_profit_exit_and_cannot_be_downgraded() {
        val scheduler = ProtectiveExitScheduler6450
        scheduler.latchTrigger("upgrade7835", schedulerKind("TAKE_PROFIT"), 1.2)
        assertEquals(schedulerKind("CATASTROPHE"), scheduler.evaluate("upgrade7835", "M", 0.5, 0.9, 0.75, 1.2, 0.95, 0))
        assertEquals(schedulerKind("CATASTROPHE"), scheduler.latchTrigger("upgrade7835", schedulerKind("TAKE_PROFIT"), 1.3).kind)
    }
    private fun schedulerKind(name: String) = ProtectiveExitScheduler6450.TriggerKind.valueOf(name)

    @Test fun all_price_based_sibling_exits_share_freshness_while_manual_exit_remains_available() {
        MissingMarkExitVeto6835.clearForTest()
        try {
            listOf("STRICT_SL_-8", "STRICT_SL_-8_CACHED", "LANE_HARD_15PCT_SL_CORE", "SL_-8", "SL",
                "CYCLIC_STRICT_SL_-8", "SNIPER_STRICT_SL_-8",
                "RAPID_CATASTROPHE", "TRAILING_STOP", "UNIVERSAL_HARD_FLOOR_-25").forEachIndexed { i, reason ->
                assertFalse(reason, MissingMarkExitVeto6835.evaluate("staleExit7835_$i", 1.0, 1L, reason).allow)
                assertTrue(reason, MissingMarkExitVeto6835.evaluate("staleExit7835_$i", 1.0, System.currentTimeMillis(), reason).allow)
                assertFalse(reason, MissingMarkExitVeto6835.evaluate("invalidExit7836_$i", Double.NaN, System.currentTimeMillis(), reason).allow)
            }
            listOf("MANUAL_CLOSE", "RUG_CONFIRMED", "LIQ_DRAIN", "THIN_LIQ").forEach { reason ->
                assertTrue(reason, MissingMarkExitVeto6835.evaluate("independent7836_$reason", 0.0, 0L, reason).allow)
            }
        } finally { MissingMarkExitVeto6835.clearForTest() }
    }

    @Test fun a_reusable_close_lease_has_exactly_one_concurrent_owner() {
        val mint = "leaseRace7835"
        CloseLease.release(mint, "TEST_RESET")
        val lease = requireNotNull(CloseLease.acquire(mint, "TEST", "TAKE_PROFIT"))
        CloseLease.recordRetry(mint, "TEST_RETRY")
        lease.nextEligibleMs = 0L
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..16).map { pool.submit<Boolean> { start.await(); CloseLease.acquire(mint, "TEST", "STOP_LOSS") != null } }
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            CloseLease.raiseIntent(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", 100)
            assertTrue(lease.inFlight)
            assertTrue(lease.emergencyReason7807.orEmpty().contains("CATASTROPH"))
        } finally { pool.shutdownNow(); CloseLease.release(mint, "TEST_END") }
    }

    @Test fun paper_model_training_and_persistence_never_change_live_weights() {
        UnifiedPolicyHead.resetAllLearning7535()
        val features = UnifiedPolicyHead.Signals(0.8, 0.3, 0.7, 0.6, 0.75, 0.9)
        LearningEnvironment7835.withMode("PAPER", canonical = true) {
            UnifiedPolicyHead.stamp("model7835", "CORE", features)
            assertTrue(UnifiedPolicyHead.bindPosition6681("paperPosition7835", "model7835", "CORE"))
            assertTrue(UnifiedPolicyHead.recordOutcome6681("paperPosition7835", "model7835", "CORE", 20.0))
            assertEquals(1L, UnifiedPolicyHead.trainedCount())
        }
        LearningEnvironment7835.withMode("LIVE") { assertEquals(0L, UnifiedPolicyHead.trainedCount()) }
        val saved = UnifiedPolicyHead.exportState()
        UnifiedPolicyHead.resetAllLearning7535()
        UnifiedPolicyHead.importState(saved)
        LearningEnvironment7835.withMode("PAPER") { assertEquals(1L, UnifiedPolicyHead.trainedCount()) }
        LearningEnvironment7835.withMode("LIVE") { assertEquals(0L, UnifiedPolicyHead.trainedCount()) }
        UnifiedPolicyHead.resetAllLearning7535()
    }

    @Test fun score_expectancy_requires_canonical_delivery_and_stays_in_its_environment() {
        ScoreExpectancyTracker.reset()
        LearningEnvironment7835.withMode("PAPER") { ScoreExpectancyTracker.record("QUALITY", 80, -20.0) }
        assertTrue(ScoreExpectancyTracker.exportState().isEmpty())
        LearningEnvironment7835.withMode("PAPER", canonical = true) { ScoreExpectancyTracker.record("QUALITY", 80, -20.0) }
        LearningEnvironment7835.withMode("PAPER") { assertEquals(-20.0, ScoreExpectancyTracker.bucketRawMean6715("QUALITY", 80)!!, 1e-9) }
        LearningEnvironment7835.withMode("LIVE") { assertNull(ScoreExpectancyTracker.bucketRawMean6715("QUALITY", 80)) }
        ScoreExpectancyTracker.reset()
    }

    @Test fun exact_sealed_amount_cannot_be_raised_or_shrunk_at_execution() {
        assertNull(SealedExecutionSize7835.boundsRefusal(0.08, 0.10, 0.04))
        assertEquals("SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835", SealedExecutionSize7835.boundsRefusal(0.02, 0.10, 0.04))
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", SealedExecutionSize7835.boundsRefusal(0.08, 0.06, 0.04))
        assertNotNull(SealedExecutionSize7835.boundsRefusal(Double.NaN, 0.10, 0.04))
    }
    @Test fun direct_paper_sell_guards_before_pricing_but_after_closed_reconciliation() {
        val code = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(code.contains("paperSellWithFreshness7836(ts, reason, identity, freshnessChecked7836 = false)"))
        assertTrue(code.contains("return paperSellWithFreshness7836(ts, reason, tradeId, freshnessChecked7836 = true)"))
        val entry = code.substringAfter("private fun paperSellWithFreshness7836(")
            .substringBefore("val price = getActualPrice(ts)")
        val healed = entry.indexOf("if (reconcileCanonicalClosed6509()) return SellResult.ALREADY_CLOSED")
        val guarded = entry.indexOf("freshExitReason7835(ts, reason) ?: return SellResult.FAILED_RETRYABLE")
        assertTrue(healed >= 0 && guarded > healed)
        assertTrue(entry.contains("val reason = if (freshnessChecked7836) reason else"))
    }

}
