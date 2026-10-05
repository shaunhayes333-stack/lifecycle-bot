package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass as TruthAssetClass
import com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551
import com.lifecyclebot.engine.truth.CanonicalAssetEntryResult6551
import com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540
import com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.FillLotLedger6504
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.7809 — AcceptanceLoop: acceptance invariants, LEARNING_DONE stall, supervisor leases. */
class Aate7809AcceptanceLoopTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun candidate(id: String, version: Long) = CanonicalAssetEntryCandidate6551(
        assetId = id, symbol = id, assetClass = TruthAssetClass.FOREX, mode = "PAPER", direction = "LONG",
        requestedVenue = TruthAssetClass.FOREX.tag, adapter = "test", source = "test", specialist = TruthAssetClass.FOREX.tag,
        score = 50.0, confidence = 1.0, requestedSizeSol = 0.1, price = 1.0, candidateVersion = version,
    )

    @Test fun rejected_paper_open_after_dispatch_is_terminally_failed() {
        FillLotLedger6504.setTestMemoryMode6641(true)
        CanonicalEntryAuthority6540.clearAllForTest()
        CanonicalPositionAuthority6441.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        val n = System.nanoTime()
        val first = CanonicalEntryAuthority6551.submit(candidate("FX7809A-$n", 78090001L)) as CanonicalAssetEntryResult6551.Allowed
        val pid = "p7809-$n"
        val opened = CanonicalPaperTransaction6486.open(
            positionId = pid, mint = "FX7809A-$n", symbol = "FX7809A", lane = "FOREX", source = "test7809",
            costSol = first.intent.resolvedSize, qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0,
            assetClass = TruthAssetClass.FOREX, entryPriceUsd = 1.0, executionIntent = first.intent,
        )
        assertTrue(opened.applied)
        assertFalse(CanonicalEntryAuthority6551.isDispatchedNonTerminal7809(first.intent.attemptId))

        // Same positionId -> POSITION_EXISTS, which happens AFTER markDispatch.
        val second = CanonicalEntryAuthority6551.submit(candidate("FX7809B-$n", 78090002L)) as CanonicalAssetEntryResult6551.Allowed
        val rejected = CanonicalPaperTransaction6486.open(
            positionId = pid, mint = "FX7809B-$n", symbol = "FX7809B", lane = "FOREX", source = "test7809",
            costSol = second.intent.resolvedSize, qtyRaw = BigInteger.ONE, decimals = 0, quantityScale = 0,
            assetClass = TruthAssetClass.FOREX, entryPriceUsd = 1.0, executionIntent = second.intent,
        )
        assertFalse(rejected.applied)
        assertEquals("POSITION_EXISTS", rejected.reason)
        assertFalse(
            "a dispatched-then-rejected paper open must not stay dispatched without a terminal",
            CanonicalEntryAuthority6551.isDispatchedNonTerminal7809(second.intent.attemptId),
        )
    }

    @Test fun phantom_window_counts_only_sized_records_missing_predecessors_inside_the_window() {
        SpecialistCausalFunnel6625.resetForTest()
        val lane = "TESTLANE7809"
        val phantom = SpecialistCausalFunnel6625.CausalKey("1", "PAPER", "mint7809p", lane, 7809L, "mint7809p:1:$lane")
        SpecialistCausalFunnel6625.stamp6625(phantom, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        val whole = SpecialistCausalFunnel6625.CausalKey("1", "PAPER", "mint7809w", lane, 7809L, "mint7809w:1:$lane")
        SpecialistCausalFunnel6625.stamp6625(whole, SpecialistCausalFunnel6625.Stage.DISCOVER, "POOL")
        SpecialistCausalFunnel6625.stamp6625(whole, SpecialistCausalFunnel6625.Stage.INTENT, "BUY_INTENT")
        SpecialistCausalFunnel6625.stamp6625(whole, SpecialistCausalFunnel6625.Stage.MARK, "MARK_READY")
        SpecialistCausalFunnel6625.stamp6625(whole, SpecialistCausalFunnel6625.Stage.SIZE, "SIZED_EXECUTABLE")
        val now = System.currentTimeMillis()
        assertEquals(1, SpecialistCausalFunnel6625.phantomSizedInWindow7809(lane, now - 60_000L, now + 1_000L))
        assertEquals(0, SpecialistCausalFunnel6625.phantomSizedInWindow7809(lane, now - 120_000L, now - 60_000L))
        SpecialistCausalFunnel6625.resetForTest()
    }

    @Test fun scoped_bus_parity_counts_positions_inside_scope_only() {
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalFinalizedTradeBus6464.registerConsumer("RewardPurity")
        fun env(t: String, p: String) = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = t, atMs = 1L, realizedPnlSol = 0.01, realizedReturnPct = 1.0,
            mint = "M", lane = "L", positionId = p,
        )
        CanonicalFinalizedTradeBus6464.publish(env("t7809a", "p7809a"))
        CanonicalFinalizedTradeBus6464.publish(env("t7809b", "p7809b"))
        CanonicalFinalizedTradeBus6464.deliverToConsumers(env("t7809a", "p7809a")) { _, _ -> true }
        val s = CanonicalFinalizedTradeBus6464.scopedParity7809("RewardPurity", setOf("p7809a"))
        assertEquals(1, s.busPositions)
        assertEquals(1, s.processed)
        assertEquals(0, s.excluded)
        CanonicalFinalizedTradeBus6464.resetForTest()
    }

    @Test fun acceptance_checkers_name_their_exclusions_and_keep_strict_parity() {
        val audit = src("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(audit.contains("busCanonical6699 == closedCount && rewardHandled6699 == closedCount"))
        assertTrue(audit.contains("HISTORICAL_EXCLUSIONS_7809"))
        val exclusions = audit.substringAfter("private val HISTORICAL_EXCLUSIONS_7809").substringBefore(")\n")
        assertFalse("repairable rows must stay in scope", exclusions.contains("BUS_PUBLISH_FAILED"))
        assertFalse("unexplained rows must stay in scope", exclusions.contains("UNKNOWN"))
        val spine = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(spine.contains("canonicalOpen = activeModeOpenCount7809(canonicalOpenPositions)"))
        assertTrue(spine.contains("phantomSizedInWindow7809"))
        val entry = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(entry.contains("LIVE_DISPATCH_IN_FLIGHT_WITHIN_CONFIRM_BUDGET_7809"))
        assertTrue(entry.contains("IN_FLIGHT_CONFIRM_BUDGET_MS_7809 = 60_000L"))
    }

    @Test fun universal_sl_sweep_counts_exit_evaluations_and_bot_loop_does_not_block_on_fee_rpc() {
        val bot = src("engine/BotService.kt")
        val sweep = bot.substringAfter("private fun runUniversalSlSafetyNetSweep").substringBefore("private fun runFallbackSafetyExit")
        assertTrue(sweep.contains("ExecutionSpineAcceptanceWindow6647.onExitEvaluation()"))
        val loop = bot.substringAfter("markProgress(\"LEARNING_DONE\")").substringBefore("markProgress(\"POST_LEARNING_SANITIZE\")")
        assertTrue(loop.contains("launchFeeDrain7809(wallet)"))
        assertTrue(loop.contains("launchPendingVerifyWatchdog7809(wallet)"))
        assertFalse(loop.contains("FeeAccumulator.tryFlush("))
        assertFalse(loop.contains("runPendingVerifyWatchdog(wallet)"))
    }

    @Test fun supervisor_watchdog_does_not_report_self_released_worker_as_forced() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("supervisorWatchdogRelease7809(released.get(), leaseId, mint, cancelAck6448, releaseSlot)"))
        val helper = bot.substringAfter("private fun supervisorWatchdogRelease7809").substringBefore("private fun supervisorClampIfNegative")
        assertTrue(helper.indexOf("if (selfReleased)") < helper.indexOf("SUPERVISOR_LEASE_FORCE_RELEASED"))
        assertTrue(helper.contains("releaseSlot()"))
    }
}
