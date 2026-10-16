package com.lifecyclebot.engine

import com.lifecyclebot.engine.ExitTruthArbiter8144.Inputs8144
import com.lifecyclebot.engine.ExitTruthArbiter8144.Intent8144
import com.lifecyclebot.engine.ExitTruthArbiter8144.Rank8144
import com.lifecyclebot.engine.ExitTruthProceedsBook8144.Note8144
import com.lifecyclebot.engine.MoonbagRunner7322.Action as MbAction
import com.lifecyclebot.engine.sell.ExitTruthFrozenLedger8144
import com.lifecyclebot.engine.sell.ExitTruthFrozenLedger8144.FreezeStep8144
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.State8141
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.Transition8141
import com.lifecyclebot.engine.truth.PositionTerminalOnce8136
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8144 — exit truth: a frozen holding is impaired inventory in a persisted sub-ledger (never a finalised close),
 * confirmed partial proceeds survive a restart and are never counted twice, and one arbitration authority with a
 * per-position ticket decides which exit sells (emergency first, no tranche sold twice).
 */
class Aate8144ExitTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000L
    private val frozenMint = "frz8144mint"
    private val frozenPid = "LIVE:frz8144#run1"

    @Before fun setUp() {
        FrozenHeldUnsellable8141.clearForTest8141()
        RecoveryFallbackLedger8141.clearForTest8141()
    }

    @After fun tearDown() {
        FrozenHeldUnsellable8141.clearForTest8141()
        RecoveryFallbackLedger8141.clearForTest8141()
    }

    private fun frozenEntry(realised: Double = 0.6, remaining: Double = 0.5) = ExitTruthFrozenLedger8144.Entry8144(
        positionId = frozenPid, mint = frozenMint, symbol = "FRZ", lane = "MOONSHOT", entryStrategy = "CHART_BREAKOUT",
        entryTimeMs = t0 - 60_000L, entryPrice = 0.001, qtyToken = 1_000.0, entryCostSol = 1.0,
        remainingCostBasisSol = remaining, realisedProceedsSol = realised, feesSol = 0.01, recoverableValueSol = 0.0,
        partialSoldPct = 50.0, peakGainPct = 80.0, entryPriceSource = "PUMP_FUN_BC", entryPoolAddress = "pool8144",
        entryDex = "PUMP_FUN", desiredExitReason = "TOKEN_FROZEN", frozenAtMs = t0,
    )

    private fun runnerInputs(remainingShare: Double = 1.0, continuation: Boolean = false, pnl: Double = 20.0, recovered: Boolean = false) =
        Inputs8144(sealedStrategy = "", entryStyle = "DIAMOND_HANDS_RUNNER", laneHostsRunners = true, confirmedPeakPct = 60.0,
            capitalRecoveredConfirmed = recovered, pnlPct = pnl, sellCostPct = 3.0, remainingShare = remainingShare,
            continuationConfirmed = continuation)

    // ── A. frozen holding: economic finality ──

    @Test fun freezeRestartThawConfirmedSaleIsOneLifetimePositionAndOneReward() {
        val L = ExitTruthFrozenLedger8144
        assertEquals(Transition8141.SETTLE, FrozenHeldUnsellable8141.enter8141(frozenMint, frozenPid, "FRZ", "TOKEN_FROZEN", t0))
        assertEquals(FreezeStep8144.FIRST_IMPAIRMENT, L.onFreeze8144(frozenEntry(), heldInventory = true, nowMs = t0))
        // publishTerminal8141's gate: an asset still held publishes no terminal; learning gets ONE impairment instead.
        assertFalse(L.terminalClosed8144(frozenPid))
        assertEquals(1, L.impairmentSignals8144().size)
        assertEquals(0.5, L.impairmentSignals8144().single().impairmentSol, 1e-12)

        // Process death: memory is gone, the sub-ledger file is what survives.
        val disk = L.encode8144()
        FrozenHeldUnsellable8141.clearForTest8141()
        assertNull(L.entryOf8144(frozenPid))
        assertEquals(0, FrozenHeldUnsellable8141.heldCount8141())
        assertEquals(1, L.restore8144(disk))
        for (e in L.heldEntries8144()) FrozenHeldUnsellable8141.restore8144(e.mint, e.positionId, e.symbol, e.desiredExitReason, e.frozenAtMs)
        assertEquals(State8141.FROZEN_HELD_UNSELLABLE, FrozenHeldUnsellable8141.stateOf8141(frozenMint))
        assertTrue(FrozenHeldUnsellable8141.refusesSell8141(frozenMint, frozenNow = true))

        // Still frozen after the restart: no second settlement, no second impairment.
        assertEquals(Transition8141.NONE, FrozenHeldUnsellable8141.enter8141(frozenMint, frozenPid, "FRZ", "TOKEN_FROZEN", t0 + 5))
        assertEquals(FreezeStep8144.ALREADY_HELD, L.onFreeze8144(frozenEntry().copy(frozenAtMs = t0 + 5), true, t0 + 5))
        assertEquals(1, L.impairmentSignals8144().size)

        // Thaw: the SAME position comes back, once (same positionId, entry time, quantity and basis).
        assertNotNull(FrozenHeldUnsellable8141.thaw8141(frozenMint))
        val restored = L.onThaw8144(frozenPid, frozenMint, t0 + 10)!!
        assertEquals(frozenPid, restored.positionId)
        assertEquals(t0 - 60_000L, restored.entryTimeMs)
        assertEquals(1_000.0, restored.qtyToken, 0.0)
        assertEquals(0.5, restored.remainingCostBasisSol, 0.0)
        assertEquals(1, restored.restoredCount)
        assertNull(L.onThaw8144(frozenPid, frozenMint, t0 + 11))

        // The confirmed sale closes it: one terminal, and no frozen terminal ever took the position's slot.
        assertTrue(L.onTerminal8144(frozenPid))
        assertFalse(L.onTerminal8144(frozenPid))
        assertTrue(L.terminalClosed8144(frozenPid))
        val once = PositionTerminalOnce8136()
        assertTrue(once.admit(frozenPid, "sale-sig-8144"))
        assertFalse(once.admit(frozenPid, "${FrozenHeldUnsellable8141.EXIT_REASON_8141}:$frozenPid"))
        assertNull(L.onThaw8144(frozenPid, frozenMint, t0 + 20))   // a closed row is never restored again
        assertTrue(L.impairmentSignals8144().isEmpty())
    }

    @Test fun aThawedHoldingFrozenAgainIsReleasedWithoutASecondImpairment() {
        val L = ExitTruthFrozenLedger8144
        assertEquals(FreezeStep8144.FIRST_IMPAIRMENT, L.onFreeze8144(frozenEntry(), true, t0))
        assertNotNull(L.onThaw8144(frozenPid, frozenMint, t0 + 1))
        assertEquals(FreezeStep8144.REFROZEN_NO_NEW_SIGNAL, L.onFreeze8144(frozenEntry(remaining = 0.3), true, t0 + 2))
        val row = L.entryOf8144(frozenPid)!!
        assertEquals(2, row.freezeCount)
        assertEquals(0.3, row.remainingCostBasisSol, 0.0)
        assertEquals(1, L.heldEntries8144().size)
        assertEquals(frozenPid, L.heldForMint8144(frozenMint)?.positionId)
        assertEquals(FreezeStep8144.REFUSED, L.onFreeze8144(frozenEntry().copy(positionId = ""), true, t0))
    }

    @Test fun aFreezeWithConfirmedPartialProceedsSplitsRealisedFromUnsellableInventory() {
        // 0.4 SOL came back through the canonical book, 0.2 SOL through a confirmed legacy-fallback slice.
        RecoveryFallbackLedger8141.stageFillQty8144("sigF8144", BigInteger.valueOf(400L))
        assertTrue(RecoveryFallbackLedger8141.noteLegacyFallback8141(frozenPid, "sigF8144", 0.2, confirmed = true))
        val realised = RecoveryFallbackLedger8141.effectiveProceeds8141(0.4, RecoveryFallbackLedger8141.confirmedFallbackSol8141(frozenPid))
        assertEquals(0.6, realised, 1e-12)
        val s = ExitTruthFrozenLedger8144.split8144(1.0, realised, 0.01, 0.5, 0.0, heldInventory = true)
        assertEquals(0.6, s.realisedProceedsSol, 1e-12)
        assertEquals(0.09, s.realisedPnlSol, 1e-12)          // 0.6 back on the 0.5 of basis sold, less 0.01 fees
        assertEquals(0.5, s.unsellableCostBasisSol, 1e-12)
        assertEquals(0.0, s.recoverableValueSol, 0.0)
        assertEquals(0.5, s.impairmentSol, 1e-12)            // a write-down, not a realised sale
        assertFalse(s.terminal)
        // 8141 booked the same holding as a realised whole-position -41% loss.
        assertEquals(-0.41, FrozenHeldUnsellable8141.economics8141(1.0, realised, 0.01)!!.netPnlSol, 1e-12)
        // A recoverable estimate reduces the impairment; nothing held = terminal, no impairment.
        assertEquals(0.3, ExitTruthFrozenLedger8144.split8144(1.0, realised, 0.01, 0.5, 0.2, true).impairmentSol, 1e-12)
        val closed = ExitTruthFrozenLedger8144.split8144(1.0, 1.3, 0.01, 0.5, 0.2, heldInventory = false)
        assertTrue(closed.terminal)
        assertEquals(0.0, closed.impairmentSol, 0.0)
        assertEquals(0.29, closed.realisedPnlSol, 1e-12)
        // The ledger: a row the canonical book already fully sold is closed (publishable), never impaired.
        assertEquals(FreezeStep8144.TERMINAL_NOTHING_HELD, ExitTruthFrozenLedger8144.onFreeze8144(frozenEntry(), false, t0))
        assertTrue(ExitTruthFrozenLedger8144.terminalClosed8144(frozenPid))
        assertTrue(ExitTruthFrozenLedger8144.impairmentSignals8144().isEmpty())
        assertEquals(0.6, frozenEntry().split.realisedProceedsSol, 1e-12)
    }

    @Test fun frozenSettlementPublishesNoTerminalForAHeldAssetAndRestoresAfterRestart() {
        val live = src("engine/FrozenHeldUnsellableLive8141.kt")
        val gate = live.indexOf("if (!ExitTruthFrozenLedger8144.terminalClosed8144(pid))")
        assertTrue(gate >= 0)
        assertTrue(gate < live.indexOf("CanonicalTradeFinalizedBus6450.publish("))
        assertTrue(live.contains("val step8144 = recordFrozen8144(ts, pos, canon, pid, reason)"))
        assertTrue(live.contains("restoreFromSubLedger8144(mint, row8144)"))
        assertTrue(live.contains("FrozenHeldUnsellable8141.restore8144(e.mint, e.positionId, e.symbol, e.desiredExitReason, e.frozenAtMs)"))
        val poller = src("engine/sell/BalanceProofPoller.kt")
        assertTrue(poller.contains("FrozenHeldUnsellableLive8141.onBalanceProven8144(entry.mint)"))
        assertTrue(poller.contains("FrozenHeldUnsellableLive8141.heldUnproven8144(entry.mint)"))
        assertTrue(src("engine/TailBag8034.kt").contains("FrozenHeldUnsellableLive8141.onTerminal8144(env.positionId)"))
        assertTrue(FrozenHeldUnsellableLive8141.statusLine8141().startsWith("frozenHeld=0"))
    }

    // ── B. confirmed partial proceeds across restarts ──

    @Test fun partialSellProcessDeathRestartRebuildSizesTheNextTrancheCorrectly() {
        val pid = "LIVE:part8144#run1"
        val book = ExitTruthProceedsBook8144()
        assertEquals(Note8144.COUNTED, book.note8144(pid, "sigA8144", BigInteger.valueOf(500L), 0.6, confirmed = true, atMs = t0))
        assertEquals(Note8144.UNCONFIRMED, book.note8144(pid, "sigB8144", null, 0.3, confirmed = false, atMs = t0))
        // Process death, then the rebuild from disk.
        val rebuilt = ExitTruthProceedsBook8144()
        assertEquals(1, rebuilt.restore8144(book.encode8144()))
        assertEquals(0.6, rebuilt.unreconciledSol8144(pid), 1e-12)
        assertEquals("500", rebuilt.fillsOf8144(pid).single().tokenQtyRaw)
        assertEquals(Note8144.DUPLICATE_SIGNATURE, rebuilt.note8144(pid, "sigA8144", null, 0.6, true, t0 + 1))
        // The next tranche targets only what is still unrecovered (0.4 of the 1.0 SOL cost).
        val unrecovered = RecoveryFallbackLedger8141.unrecoveredSol8141(1.0, 0.0, rebuilt.unreconciledSol8144(pid), 0.0)
        assertEquals(0.4, unrecovered, 1e-12)
        val next = RunnerExitContract8136.recoveryFraction8136(unrecovered, 2.0, 3.0)
        assertEquals(0.4 / (2.0 * 0.97), next, 1e-9)
        assertTrue(next < RunnerExitContract8136.recoveryFraction8136(1.0, 2.0, 3.0) * 0.5)   // what a forgetful ledger sold
        // A later canonical backfill of the same fill: canonical 0.6 + fallback 0, never 1.2.
        assertEquals(1, rebuilt.reconcile8144(pid, setOf("live|$pid|1|PARTIAL_CLOSE|sigA8144", "sigA8144")))
        assertEquals(0.0, rebuilt.unreconciledSol8144(pid), 0.0)
        assertEquals(0.4, RecoveryFallbackLedger8141.unrecoveredSol8141(1.0, 0.6, rebuilt.unreconciledSol8144(pid), 0.0), 1e-12)
        val again = ExitTruthProceedsBook8144()
        again.restore8144(rebuilt.encode8144())
        assertEquals(0.0, again.unreconciledSol8144(pid), 0.0)
        assertTrue(again.counted8144("sigA8144"))
        assertTrue(again.hasFills8144(pid))
    }

    @Test fun aSignatureIsNeverCountedTwiceAfterCapacity() {
        val b = ExitTruthProceedsBook8144(maxFills = 2, maxRetired = 4)
        assertEquals(Note8144.COUNTED, b.note8144("pidA", "s1", null, 0.1, true, t0))
        assertEquals(Note8144.COUNTED, b.note8144("pidA", "s2", null, 0.2, true, t0))
        assertEquals(Note8144.FULL, b.note8144("pidA", "s3", null, 0.3, true, t0))         // forgets nothing to make room
        assertEquals(Note8144.DUPLICATE_SIGNATURE, b.note8144("pidA", "s1", null, 0.1, true, t0))
        assertEquals(1, b.reconcile8144("pidA", setOf("s1")))
        assertEquals(Note8144.COUNTED, b.note8144("pidA", "s3", null, 0.3, true, t0))      // the reconciled row made room
        assertEquals(2, b.size8144())
        assertEquals(Note8144.DUPLICATE_SIGNATURE, b.note8144("pidA", "s1", null, 0.1, true, t0))   // 8141 cleared its set and re-counted here
        assertTrue(b.counted8144("s1"))
        assertEquals(0.5, b.unreconciledSol8144("pidA"), 1e-12)
        assertEquals(2, b.retire8144("pidA"))
        assertEquals(0, b.size8144())
        assertEquals(Note8144.DUPLICATE_SIGNATURE, b.note8144("pidB", "s2", null, 0.2, true, t0))   // under any position
        assertEquals(Note8144.RETIRED_POSITION, b.note8144("pidA", "s9", null, 0.2, true, t0))
        assertEquals(Note8144.INVALID, b.note8144("pidB", " ", null, 0.2, true, t0))
        // The live ledger keeps the 8141 contract on top of the durable book.
        val L = RecoveryFallbackLedger8141
        assertTrue(L.noteLegacyFallback8141("LIVE:cap8144", "sigCap8144", 0.25, confirmed = true))
        assertFalse(L.noteLegacyFallback8141("LIVE:cap8144", "sigCap8144", 0.25, confirmed = true))
        assertEquals(0.25, L.confirmedFallbackSol8141("LIVE:cap8144"), 1e-12)
        L.retire8144("LIVE:cap8144")
        assertEquals(0.0, L.confirmedFallbackSol8141("LIVE:cap8144"), 0.0)
        assertFalse(L.noteLegacyFallback8141("LIVE:cap8144", "sigCap8144", 0.25, confirmed = true))
        assertTrue(L.statusLine8144().startsWith("fills=0"))
    }

    // ── C. one final exit arbitration authority ──

    @Test fun theArbitrationTicketPreventsADoubleTranche() {
        val book = ExitTruthArbiter8144.TicketBook8144(inFlight = { true })
        val m = "mintT8144"
        val et = 1_000L
        assertTrue(book.admit8144(m, et, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0))
        assertTrue(book.admit8144(m, et, "SPIKE_CAPTURE_7943_T1_47PCT", 0.0, t0 + 1))   // the same rung at its execution door
        assertFalse(book.admit8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + 2))         // a second ladder on the same move waits
        assertFalse(book.admit8144(m, et, "CAPITAL_RECOVERY_8136", 0.0, t0 + 2))
        assertTrue(book.heldByOther8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + 2))
        assertFalse(book.heldByOther8144(m, et, "SPIKE_CAPTURE_7943_T1_50PCT", 0.0, t0 + 2))
        // The rung filled (the sold share moved): the ticket is spent, the next tranche is sized on confirmed state.
        assertTrue(book.admit8144(m, et, "PROFIT_LOCK_5X_8136", 60.0, t0 + 3))
        assertFalse(book.admit8144(m, et, "SPIKE_CAPTURE_7943_T2_130PCT", 60.0, t0 + 4))
        // A protective exit always takes the ticket; a lower rank never overrides it.
        assertTrue(book.admit8144(m, et, "RUNNER_STRUCTURE_BREAK_7967_1M_20PCT", 60.0, t0 + 5))
        assertFalse(book.admit8144(m, et, "PROFIT_LOCK_5X_8136", 60.0, t0 + 6))
        assertTrue(book.admit8144(m, et, "SPIKE_CAPTURE_7943_T2_130PCT", 60.0, t0 + 6 + ExitTruthArbiter8144.TICKET_TTL_MS_8144))
        assertTrue(book.admit8144(m, 2_000L, "PROFIT_LOCK_5X_8136", 0.0, t0 + 7))     // a new position, a new ticket

        // One decision per tick: the tick's ticket is consumed once.
        val tick = ExitTruthArbiter8144.TicketBook8144(inFlight = { true })
        assertTrue(tick.consumeTick8144(m, et, 7L, "RUNNER_PEAK_CAPTURE_7980_250PK_100PCT", 0.0, t0))
        assertFalse(tick.consumeTick8144(m, et, 7L, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0))
        assertFalse(tick.consumeTick8144(m, et, 8L, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0 + 1))
        assertTrue(tick.consumeTick8144(m, et, 9L, "LIQUIDITY_PULLED", 0.0, t0 + 2))

        // A claim whose later door refused it (nothing in flight) lapses after the grace, so it cannot stall others.
        val grace = ExitTruthArbiter8144.TicketBook8144(inFlight = { false })
        assertTrue(grace.admit8144(m, et, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0))
        assertFalse(grace.admit8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + 1_000L))
        assertTrue(grace.admit8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + ExitTruthArbiter8144.CLAIM_GRACE_MS_8144 + 1L))
        // A tick's losing rung hands back the ticket it claimed during the tick; an earlier in-flight claim is kept.
        val hand = ExitTruthArbiter8144.TicketBook8144(inFlight = { true })
        assertTrue(hand.admit8144(m, et, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0 + 100))
        hand.releaseIfClaimedSince8144(m, "SPIKE_CAPTURE_7943_T1_45PCT", t0 + 200)
        assertFalse(hand.admit8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + 201))
        hand.releaseIfClaimedSince8144(m, "PROFIT_LOCK_5X_8136", t0 + 50)       // not the holder: no-op
        hand.releaseIfClaimedSince8144(m, "SPIKE_CAPTURE_7943_T1_45PCT", t0 + 50)
        assertTrue(hand.admit8144(m, et, "PROFIT_LOCK_5X_8136", 0.0, t0 + 202))
        hand.release8144(m, "PROFIT_LOCK_5X_8136")
        assertEquals(0, hand.size8144())

        // The same tick's spike rung and structure break: the structure break sells, the rung waits.
        val d = ExitTruthArbiter8144.decide8144(listOf(Intent8144("SPIKE_CAPTURE_7943_T1_45PCT", 0.6),
            Intent8144("RUNNER_STRUCTURE_BREAK_7967_1M_45PCT", 1.0)), runnerInputs())
        assertEquals("RUNNER_STRUCTURE_BREAK_7967_1M_45PCT", d.act?.reason)
        assertEquals(Rank8144.RUNNER_BREAKDOWN, d.rank)
        assertEquals(listOf("SPIKE_CAPTURE_7943_T1_45PCT"), d.suppressed.map { it.reason })
        assertEquals("SPIKE_CAPTURE_7943_T1", ExitTruthArbiter8144.trancheKey8144("SPIKE_CAPTURE_7943_T1_45PCT"))
        assertEquals("CAPITAL_RECOVERY", ExitTruthArbiter8144.trancheKey8144("capital_recovery_2.1x"))
        assertEquals("RUNNER_PEAK_CAPTURE_7980", ExitTruthArbiter8144.trancheKey8144("RUNNER_PEAK_CAPTURE_7980_250PK_100PCT"))
    }

    @Test fun emergencyBeatsEveryHold() {
        // A confirmed runner continuation, a protected residual, an unfunded moonbag set-aside and a soft trail: all hold...
        val holds = listOf(Intent8144("RAPID_TRAILING_STOP", 1.0), Intent8144("TAIL_SET_ASIDE_8034_RAPID_TRAILING_STOP", 0.75),
            Intent8144("SPIKE_CAPTURE_7943_T1_45PCT", 0.6))
        val held = ExitTruthArbiter8144.decide8144(holds, runnerInputs(remainingShare = 0.2, continuation = true, pnl = -10.0))
        assertNull(held.act)
        assertTrue(held.refused.any { it.startsWith("UNFUNDED_MOONBAG_8144") })
        assertTrue(held.refused.any { it.startsWith("RUNNER_PROTECTED_SHARE_8144") })
        // ...and none of them holds a tier-1 exit, which sells the whole holding.
        val d = ExitTruthArbiter8144.decide8144(holds + Intent8144("LIQUIDITY_PULLED", 1.0),
            runnerInputs(remainingShare = 0.2, continuation = true, pnl = -10.0))
        assertEquals("LIQUIDITY_PULLED", d.act?.reason)
        assertEquals(1.0, d.act!!.fraction, 0.0)
        assertEquals(Rank8144.EMERGENCY_UNSELLABLE, d.rank)
        assertTrue(ExitTruthArbiter8144.protective8144("LIQUIDITY_PULLED"))
        assertEquals(Rank8144.EMERGENCY_UNSELLABLE, ExitTruthArbiter8144.rankOf8144(FrozenHeldUnsellable8141.thawExitReason8141("TOKEN_FROZEN")))
        assertEquals(Rank8144.HARD_LOSS, ExitTruthArbiter8144.rankOf8144("STRICT_SL_-20_-21PCT"))
        assertEquals(Rank8144.CAPITAL_RECOVERY, ExitTruthArbiter8144.rankOf8144("CAPITAL_RECOVERY_8136"))
        assertEquals(Rank8144.PROFIT_PARTIAL, ExitTruthArbiter8144.rankOf8144("PROFIT_LOCK_5X_8136"))
        assertEquals(Rank8144.RUNNER_MILESTONE, ExitTruthArbiter8144.rankOf8144("RUNNER_MILESTONE_8136_T2_400PCT"))
        assertEquals(Rank8144.FUNDED_MOONBAG, ExitTruthArbiter8144.rankOf8144("TAIL_SET_ASIDE_8034_RAPID_TRAILING_STOP"))
        assertEquals(Rank8144.TIME_BASED, ExitTruthArbiter8144.rankOf8144("TIME_EXIT_15M"))
        // A tier-1 exit takes the ticket from an in-flight discretionary tranche.
        val book = ExitTruthArbiter8144.TicketBook8144(inFlight = { true })
        assertTrue(book.admit8144("mintE8144", 1L, "SPIKE_CAPTURE_7943_T1_45PCT", 0.0, t0))
        assertTrue(book.admit8144("mintE8144", 1L, "LIQUIDITY_PULLED", 0.0, t0 + 1))
        assertTrue(book.consumeTick8144("mintE8144", 1L, 3L, "LIQUIDITY_PULLED", 0.0, t0 + 2))
        // The overlays agree: no hold acts on tier 1.
        assertFalse(RunnerGrab7967.deferrable7967("LIQUIDITY_PULLED", 20.0))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "LIQUIDITY_PULLED", 30.0, null, t0))
        assertTrue(LifecycleHold8065.passes8065("LIQUIDITY_PULLED"))
    }

    @Test fun runnerEligibilityComesFromTheSealedSetupOrAProvenPeakAndTheMoonbagMustBeFunded() {
        val scalp = Inputs8144(sealedStrategy = "", entryStyle = "QUICK_FLIP", laneHostsRunners = true, confirmedPeakPct = 50.0,
            capitalRecoveredConfirmed = false, pnlPct = 50.0, sellCostPct = 3.0, remainingShare = 1.0)
        assertEquals(RunnerExitContract8136.RunnerBasis8136.NONE, ExitTruthArbiter8144.runnerBasis8144(scalp))
        val milestone = listOf(Intent8144("RUNNER_MILESTONE_8136_T2_400PCT", 0.35))
        val refused = ExitTruthArbiter8144.decide8144(milestone, scalp)
        assertNull(refused.act)
        assertTrue(refused.refused.single().startsWith("NOT_A_RUNNER_8144"))
        val proven = scalp.copy(confirmedPeakPct = 150.0)
        assertEquals(RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION, ExitTruthArbiter8144.runnerBasis8144(proven))
        assertEquals(0.35, ExitTruthArbiter8144.decide8144(milestone, proven).act!!.fraction, 1e-12)
        assertEquals(RunnerExitContract8136.RunnerBasis8136.SEALED_SETUP,
            ExitTruthArbiter8144.runnerBasis8144(scalp.copy(entryStyle = "", sealedStrategy = "DIAMOND_HANDS_RUNNER")))
        // A runner's protected residual is not sold by a discretionary ladder (20% of the original stays).
        val clamped = ExitTruthArbiter8144.decide8144(listOf(Intent8144("SPIKE_CAPTURE_7943_T1_45PCT", 0.6)), runnerInputs(remainingShare = 0.25))
        assertEquals(0.2, clamped.act!!.fraction, 1e-9)
        // Moonbag funding: a bank that leaves an unfunded bag sells everything instead; an unfunded held bag is released.
        assertEquals(MbAction.PASS, MoonbagRunner7322.fundedAction8144(MbAction.BANK_PARTIAL, 50.0, false, 3.0, 0.4))
        assertEquals(MbAction.BANK_PARTIAL, MoonbagRunner7322.fundedAction8144(MbAction.BANK_PARTIAL, 100.0, false, 3.0, 0.4))
        assertEquals(MbAction.BANK_PARTIAL, MoonbagRunner7322.fundedAction8144(MbAction.BANK_PARTIAL, 10.0, true, 3.0, 0.4))
        assertEquals(MbAction.PASS, MoonbagRunner7322.fundedAction8144(MbAction.HOLD_MOONBAG, 300.0, false, 3.0, 0.4))
        assertEquals(MbAction.HOLD_MOONBAG, MoonbagRunner7322.fundedAction8144(MbAction.HOLD_MOONBAG, 300.0, true, 3.0, 0.4))
        assertEquals(MbAction.PASS, MoonbagRunner7322.fundedAction8144(MbAction.PASS, 300.0, true, 3.0, 0.4))
    }

    @Test fun mfeConfirmedPeakRealisedCaptureAndPostExitOpportunityAreTrackedApart() {
        val c = ExitTruthArbiter8144.CaptureBook8144()
        val k = "mintC8144|1"
        c.noteMark8144(k, 300.0, 120.0)        // a phantom print: MFE moves, the confirmed peak does not pass the verified one
        c.noteMark8144(k, 90.0, 120.0)
        c.noteRealised8144(k, 1.2, 0.5)
        c.noteRealised8144(k, 1.0, 0.4)        // a lagging read never erases a fill already seen
        c.noteExit8144("mintC8144", k, 80.0, 1.0, t0)
        c.notePostExitPx8144("mintC8144", 2.5, t0 + 1_000L)
        c.notePostExitPx8144("mintC8144", 5.0, t0 + 31L * 60_000L)   // outside the window: not counted
        val cap = c.of8144(k)!!
        assertEquals(300.0, cap.mfePct, 0.0)
        assertEquals(120.0, cap.confirmedPeakPct, 0.0)
        assertEquals(140.0, cap.realisedCapturePct, 1e-9)
        assertEquals(150.0, cap.postExitBestPct, 1e-9)
        assertEquals(70.0, cap.postExitOpportunityPp, 1e-9)
        assertEquals(1, c.all8144().size)
    }

    @Test fun theArbiterIsWiredIntoEveryOverlayDoor() {
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue(spike.contains("val tick8144 = ExitTruthArbiterLive8144.Tick8144(ts, sell)"))
        assertTrue(spike.contains("tick8144.dispatch8144(px8144)"))
        assertTrue(spike.contains("chartExit7950(ts, px, nowMs, tick8144.tier1Only8144)"))
        assertTrue(spike.contains("ExitTruthArbiterLive8144.ticketHeldByOther8144(ts, "))
        assertTrue(src("engine/RunnerExitLive8136.kt").contains("ExitTruthArbiterLive8144.admitTranche8144(ts, reason)"))
        assertTrue(src("engine/RunnerExitLive8136.kt").contains("ExitTruthArbiterLive8144.admitTranche8144(ts, RECOVERY_TRANCHE_8144)"))
        assertTrue(src("engine/RunnerGrab7967.kt").contains("ExitTruthArbiterLive8144.claimProtective8144(ts, why, nowMs)"))
        assertTrue(src("engine/RunnerPlay8018.kt").contains("ExitTruthArbiter8144.runnerBasis8144(ExitTruthArbiterLive8144.inputsFor8144(ts, null).copy(laneHostsRunners = true))"))
        val bot = src("engine/BotService.kt")
        val held = bot.indexOf("RunnerGrab7967.heldExit7967(ts, px, now) { t, frac, reason -> sellIntoSpike7943(t, frac, reason) }")
        assertTrue(held in 1 until bot.indexOf("SpikeCapture7943.onMark(ts, px) { t, frac, reason -> sellIntoSpike7943(t, frac, reason) }"))
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("RecoveryFallbackLedger8141.stageFillQty8144(proof?.sig.orEmpty(), proof?.rawTokenConsumed)"))
        assertTrue(e.contains("MoonbagRunner7322.decideFunded8144(ts, mbLane7322, requestReason, mbPnl7322, mbPeak7322,"))
        assertTrue(src("engine/TailBag8034.kt").contains("RecoveryFallbackLedger8141.retire8144(env.positionId)"))
    }
}
