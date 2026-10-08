package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.sell.EmergencyExitDispatcher7807
import com.lifecyclebot.engine.sell.ExitHotPath7809
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.ExitTelemetryStamper6732
import com.lifecyclebot.engine.truth.PeakAdaptiveTrail6390
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.7809 — ExitPath: hot-exit units, exit scope, canonical peak, quarantine audit, latency gate. */
class Aate7809ExitPathTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun row(
        id: String,
        mint: String,
        lifecycle: CanonicalPositionAuthority6441.Lifecycle,
        qty: Long,
        mode: String = "live",
        reason: String = "",
        mutatedAt: Long = 1L,
    ) = CanonicalPositionAuthority6441.Position(
        positionId = id, mode = mode, mint = mint, symbol = "SYM", lane = "SHITCOIN", runId = "run",
        openedAtMs = 1L, entryCostSol = 0.1,
        remainingQtyRaw = BigInteger.valueOf(qty), originalQtyRaw = BigInteger.valueOf(qty),
        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = 0.0,
        tokenDecimals = 6, lifecycle = lifecycle, lastMutationMs = mutatedAt, quarantineReason = reason,
    )

    // ── items 4 / 5 — hot-exit units ─────────────────────────────────────────
    @Test fun hot_exit_unit_is_single_flight_with_stuck_fallback() {
        ExitHotPath7809.resetForTest()
        val k = "MintUnit7809"
        assertTrue(ExitHotPath7809.tryBegin(k, 1_000L))
        assertFalse("second tick coalesces behind the in-flight unit", ExitHotPath7809.tryBegin(k, 2_000L))
        assertEquals(1, ExitHotPath7809.inFlightCount())
        val replacedAt = 1_000L + ExitHotPath7809.UNIT_STUCK_MS_7809
        assertTrue("a wedged unit is replaced", ExitHotPath7809.tryBegin(k, replacedAt))
        ExitHotPath7809.end(k, 1_000L)  // the old unit's release must not drop the replacement
        assertEquals(1, ExitHotPath7809.inFlightCount())
        ExitHotPath7809.end(k, replacedAt)
        assertEquals(0, ExitHotPath7809.inFlightCount())
        assertTrue(ExitHotPath7809.statusLine().contains("stuckReplaced=1"))
    }

    @Test fun moonshot_hard_stops_bypass_strategy_holds() {
        assertTrue(ProtectiveExitClass7807.bypassesHolds("STRICT_SL_-6", "MOONSHOT"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "MOONSHOT"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("RAPID_CATASTROPHE_STOP", "MOONSHOT"))
        assertFalse(ProtectiveExitClass7807.bypassesHolds("PROTECTIVE_EXIT_TRAILING_STOP_6450", "MOONSHOT"))
    }

    @Test fun normal_exits_never_use_dispatchers_io() {
        val normal = EmergencyExitDispatcher7807.forReason("PROTECTIVE_EXIT_TRAILING_STOP_6450_RISKCLOCK")
        assertSame(ExitHotPath7809.normalExitDispatcher, normal)
        assertNotSame(kotlinx.coroutines.Dispatchers.IO, normal)
        assertNotSame(normal, EmergencyExitDispatcher7807.forReason("STRICT_SL_-8"))
    }

    @Test fun hot_loop_dispatches_units_on_the_held_pool() {
        val bot = src("engine/BotService.kt")
        val loop = bot.substringAfter("managedThisTick6663.forEach { ts ->").substringBefore("// V5.0.5999")
        assertTrue(loop.contains("dispatchHotExitUnit7809(ts, curWallet, curSol)"))
        assertFalse(loop.contains("executor.runManageOnly("))
        assertTrue(bot.contains("hotExitJob = scope.launch(hotPathDispatcher7807)"))
        assertTrue(bot.contains("job7809.invokeOnCompletion { com.lifecyclebot.engine.sell.ExitHotPath7809.end(key7809, startedAt7809) }"))
    }

    // ── items 6 / 22 — exit scope = protective inventory ─────────────────────
    @Test fun exit_scope_includes_one_funded_quarantine_per_unopened_mint() {
        val Q = CanonicalPositionAuthority6441.Lifecycle.QUARANTINED
        val O = CanonicalPositionAuthority6441.Lifecycle.OPEN
        val scope = CanonicalPositionAuthority6441.exitScopeOf7809(listOf(
            row("a-open", "A", O, 100),
            row("a-q", "A", Q, 100, reason = "BASIS_UNCERTAIN_7807:x"),
            row("b-old", "B", Q, 100, reason = "BASIS_UNCERTAIN_7807:x", mutatedAt = 5L),
            row("b-new", "B", Q, 100, reason = "BASIS_UNCERTAIN_7807:x", mutatedAt = 9L),
        ))
        assertEquals(listOf("a-open", "b-new"), scope.map { it.positionId })
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("CanonicalPositionAuthority6441.protectiveExitScope7809(activeExitMode7254)"))
    }

    @Test fun quarantine_audit_splits_funded_from_historical() {
        val Q = CanonicalPositionAuthority6441.Lifecycle.QUARANTINED
        val rows = listOf(
            row("f1", "F1", Q, 100, reason = "BASIS_UNCERTAIN_7807:x"),
            row("f2", "F2", Q, 100, reason = "CANONICAL_COMMIT_REJECTED"),
            row("p", "P", Q, 100, mode = "paper", reason = "REPLAY_UNIT_MISMATCH"),
            row("z", "Z", Q, 100, reason = "CONFIRMED_ZERO_NO_TRACKER_ROW_7736"),
            row("d", "D", Q, 0, reason = "BASIS_UNCERTAIN_7807:x"),
        )
        val a = CanonicalPositionAuthority6441.quarantineAuditOf7809(rows, scopeMints = setOf("F1"))
        assertEquals(5, a.total)
        assertEquals(2, a.funded)
        assertEquals("F2 is funded live inventory outside the scope", 1, a.fundedOutsideExitScope)
        assertEquals(3, a.unfunded)
        assertEquals(1, a.unfundedByKind["paper"])
        assertEquals(1, a.unfundedByKind["zeroQty"])
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Quarantine audit (§7809)"))
    }

    @Test fun catastrophe_corrob_cannot_hold_emergency_dispatch_on_sync_provider() {
        val bot = src("engine/BotService.kt")
        val checker = bot.substringAfter("private suspend fun catastropheContradictedBounded7877(")
            .substringBefore("private fun dispatchProtectiveExit7176(")
        assertTrue(checker.contains("scope.async(Dispatchers.IO"))
        assertTrue(checker.contains("withTimeoutOrNull(1_500L) { task.await() }"))
        assertTrue(checker.contains("catch (cancel: CancellationException)"))
        assertTrue(checker.contains("task.cancel()"))
        assertTrue(bot.contains("catastropheContradictedBounded7877(ts, markPx)"))
    }

    // ── item 21 — one canonical peak ─────────────────────────────────────────
    @Test fun canonical_peak_receives_lost_ticks_and_reentries_start_clean() {
        PeakAdaptiveTrail6390.clearForTest()
        val k1 = PeakAdaptiveTrail6390.canonicalKey7809("MintPk", 1_000L)
        assertNull(PeakAdaptiveTrail6390.reconcileCanonical7809(k1, 40.0, 40.0))
        // a concurrent position copy lost the +40 peak; the canonical peak gets it back
        assertEquals(40.0, PeakAdaptiveTrail6390.reconcileCanonical7809(k1, 12.0, 12.0)!!, 1e-9)
        // another writer ratcheted the canonical higher: the tracker adopts it, no repair
        assertNull(PeakAdaptiveTrail6390.reconcileCanonical7809(k1, 50.0, 60.0))
        // re-entry on the same mint: new lifetime, no inherited peak
        val k2 = PeakAdaptiveTrail6390.canonicalKey7809("MintPk", 2_000L)
        assertNull(PeakAdaptiveTrail6390.reconcileCanonical7809(k2, 3.0, 3.0))
        // an intentional rebase (OpenPnlSanity passes the mint) clears lifetime keys
        PeakAdaptiveTrail6390.onPositionRebased7803("MintPk", 0.0)
        assertNull(PeakAdaptiveTrail6390.reconcileCanonical7809(k1, 0.0, 0.0))
        assertTrue(src("engine/BotService.kt").contains("peakAuthorityTick7809(ts, pnlPct)"))
    }

    // ── item 27 — latency gate ───────────────────────────────────────────────
    @Test fun percentile_is_nearest_rank() {
        val v = longArrayOf(500, 100, 300, 200, 400)
        assertEquals(300L, StopLatencyClasses6464.percentile7809(v, 0.50))
        assertEquals(500L, StopLatencyClasses6464.percentile7809(v, 0.95))
        assertEquals(0L, StopLatencyClasses6464.percentile7809(LongArray(0), 0.95))
    }

    @Test fun gate_counts_one_sample_per_condition_and_drops_deferred_stamps() {
        StopLatencyClasses6464.resetForTest()
        ExitTelemetryStamper6732.resetForTest()
        val m = "MintGate7809${System.nanoTime()}"
        val latch = System.currentTimeMillis() - 2_000L
        ExitTelemetryStamper6732.noteTrigger7807(m, "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", latch)
        ExitTelemetryStamper6732.noteBroadcast7807(m)
        // the risk clock redispatches with the same latch time: not a second sample
        ExitTelemetryStamper6732.noteTrigger7807(m, "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", latch)
        ExitTelemetryStamper6732.noteBroadcast7807(m)
        val hard = StopLatencyClasses6464.gateSnapshot7809().getValue(StopLatencyClasses6464.Class.HARD_STOP)
        assertEquals(1, hard.first)
        assertTrue(hard.second >= 2_000L)
        // a deferred non-emergency request leaves no stamp behind
        val m2 = "MintDefer7809${System.nanoTime()}"
        ExitTelemetryStamper6732.noteTrigger7807(m2, "TAKE_PROFIT_25", System.currentTimeMillis() - 60_000L)
        ExitTelemetryStamper6732.withdrawDeferred7809(m2, "")
        ExitTelemetryStamper6732.noteBroadcast7807(m2)
        assertEquals(0, StopLatencyClasses6464.gateSnapshot7809().getValue(StopLatencyClasses6464.Class.NORMAL_STOP).first)
        // broadcast -> finality closes on the canonical terminal
        ExitTelemetryStamper6732.noteExitCompleted("pid-gate", "STRICT_SL_-8", m)
        assertTrue(StopLatencyClasses6464.gateLine7809().contains("broadcast->finality(n=1"))
        assertTrue(StopLatencyClasses6464.statusLine().contains("STOP_LATENCY_GATE_7809"))
    }
}
