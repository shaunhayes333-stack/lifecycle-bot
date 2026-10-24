package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexFlatMark8322
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.Kelly8365
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.RegressionRestore8298
import com.lifecyclebot.engine.truth.Rehearsal8339
import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8367 — DIRECTIVE REGRESSION GUARD (lifecycle_apk/DIRECTIVE_LEDGER_8367.md).
 *
 * Owner: "all of this has worked before and then undone later." One behaviour test per directive in the ledger: each
 * calls the pure function (or reads the default) that carries the directive and asserts what the owner asked for, so a
 * later change that silently undoes one fails here by name. A deliberate change must supersede the assertion in place
 * with `// superseded by V5.0.<build> — reason`, never delete it.
 */
class Aate8367DirectiveRegressionTest {

    // ── 8337 FIX 1 / 8350 — curve impact on VIRTUAL reserves ──

    @Test fun d01_activeCurveImpactIsPricedOnVirtualReserves() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val solUsd = 150.0
        val curve = "Directive8367CurveAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteAccount7951(curve, 30.02, 1_072_000_000.0, 0.02, now)
        // $3 of real SOL is not the depth: 30 virtual + real SOL, both sides.
        assertEquals(2.0 * 30.02 * solUsd, FieldManual7715.depthUsdForImpact8337(curve, 3.0, solUsd, now), 1e-9)
        // A non-curve pool passes through unchanged.
        assertEquals(5_000.0, FieldManual7715.depthUsdForImpact8337("Directive8367PoolBBBBBBBBBBBBBBBBBBBBBBBB", 5_000.0, solUsd, now), 0.0)
        CurveReserves7951.resetForTest7951()
    }

    // ── 8337 FIX 2 / 8342 — a flat mark is a LOST mark, never a -cost label ──

    @Test fun d02_flatMarkIsLostNotACostLabel() {
        assertTrue(CortexFlatMark8322.flatMark8322(0.0, 0.0, 0.0))
        assertFalse(CortexFlatMark8322.flatMark8322(12.0, 15.0, -3.0))
    }

    // ── 8337 FIX 3 / 8363 — profit protection the cost model cannot switch off ──

    @Test fun d03_giveBackLocksAreProtectiveAndTheFloorKeepsPartOfThePeak() {
        assertTrue(ProfitLock8363.profitLock8363("LIFECYCLE_PROFIT_GIVEBACK_8065_80PK_40PCT"))
        assertFalse(ProfitExitTruth8163.profitTaking("LIFECYCLE_PROFIT_GIVEBACK_8065_80PK_40PCT"))
        // The 8114 runner give-back (35 % under its peak) is never refused as "not profitable".
        assertFalse(ProfitExitTruth8163.profitTaking("RUNNER_PEAK_CAPTURE_7980_250PK_120PCT"))
        assertFalse(ProfitExitTruth8163.profitTaking("RUNNER_FROM_ENTRY_GIVEBACK_8104_80PK_30PCT"))
        // Selling into strength is still checked on its executable quote.
        assertTrue(ProfitExitTruth8163.profitTaking("SPIKE_CAPTURE_7943_T1_42PCT"))
        // FIX 3a: a +22 % peak keeps at least 40 % of the peak even when the cost model reads 50 %.
        val stop = FieldManual7715.runnerAwareStop7935(-10.0, 22.0, 50.0, true) { -10.0 }
        assertTrue("stop=$stop", stop >= 22.0 * 0.4 - 1e-9)
        val floor = ExitDoctrine8169.runnerProtectFloor8169(22.0, 50.0)
        assertTrue("floor=$floor", floor >= 22.0 * 0.4 - 1e-9)
        // 8363 green floor: a +60 % hold is released at no less than +30 %.
        assertTrue(GreenFloor8363.floor8363(60.0, 6.0) >= 30.0 - 1e-9)
        assertTrue(GreenFloor8363.breached8363(29.0, 60.0, 6.0))
        assertFalse(GreenFloor8363.breached8363(45.0, 60.0, 6.0))
    }

    // ── 8337 FIX 3b — the runner give-back lock arms at +25 ──

    @Test fun d04_giveBackLockArmsAtTwentyFive() {
        assertEquals(25.0, RunnerExitProfile7277.MIN_PEAK_FOR_GIVEBACK_LOCK_PCT, 1e-9)
        assertTrue(RunnerExitProfile7277.deferGiveBackLock("MOONSHOT", 24.0))
        assertFalse(RunnerExitProfile7277.deferGiveBackLock("MOONSHOT", 26.0))
    }

    // ── 8337 FIX 4 — runner hard stop keeps its 60 s grace ──

    @Test fun d05_runnerHardStopKeepsItsSixtySecondGrace() {
        assertTrue(RunnerGrab7967.hardStopWaits8337(-36.0, 10_000L, false))
        assertFalse(RunnerGrab7967.hardStopWaits8337(-36.0, 10_000L, true))     // a fresh executable quote confirms it
        assertFalse(RunnerGrab7967.hardStopWaits8337(-85.0, 10_000L, false))    // past -80 % never waits
        assertFalse(RunnerGrab7967.hardStopWaits8337(-36.0, 60_000L, false))    // past the minute it sells
    }

    // ── 8337 FIX 5 — entries pay to land (private: read from source; the window constants are behaviour) ──

    @Test fun d06_entriesPayToLand() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("private fun entryOrdinary8151(mint: String): Boolean = false"))
        assertEquals(120L * 60_000L, PumpLanding8288.WINDOW_MS_8288)
        assertEquals(3, PumpLanding8288.MIN_RESOLVED_8288)
    }

    // ── 8337 FIX 10a + 8114 runner capture — scalp tiers until the run is confirmed; ladders unchanged ──

    @Test fun d07_runnerCaptureLaddersAndScalpTierUntilConfirmed() {
        assertEquals(listOf(40.0 to 0.60, 120.0 to 0.70, 400.0 to 0.70), SpikeCapture7943.TIERS)
        assertEquals(listOf(100.0 to 0.50, 400.0 to 0.35, 1_000.0 to 0.35), RunnerGrab7967.TIERS_7967)
        assertTrue(RunnerGrab7967.scalpTiersUntilConfirmed8337(true, false, false))
        assertTrue(RunnerGrab7967.scalpTiersUntilConfirmed8337(true, true, false))
        assertFalse(RunnerGrab7967.scalpTiersUntilConfirmed8337(true, true, true))
        // 8114 give-backs: +200 % peak sold 35 % under it; a +40..+199 % peak keeps half.
        assertTrue(RunnerGrab7967.peakGiveback7980(300.0, 150.0))
        assertFalse(RunnerGrab7967.peakGiveback7980(300.0, 200.0))
        assertTrue(RunnerGrab7967.fromEntryGiveback8104(80.0, 40.0))
        assertFalse(RunnerGrab7967.fromEntryGiveback8104(80.0, 41.0))
    }

    // ── 8337 FIX 10b — RAPID_FLUID_STOP on the normal loss path ──

    @Test fun d08_rapidFluidStopIsNotAnEmergency() {
        assertFalse(ProtectiveExitClass7807.isEmergency("RAPID_FLUID_STOP"))
        assertTrue(ProtectiveExitClass7807.isEmergency("RAPID_CATASTROPHE_STOP"))
        assertTrue(ProtectiveExitClass7807.isEmergency("HARD_RUG_DETECTED"))
    }

    // ── 8357 — 1m, 5m, then 15m, 1h, 4h, 24h (48h / 72h / 7d on tokenomics): seven checkpoints ──

    @Test fun d09_fifteenMinuteCheckpointIsInTheSchedule() {
        val h = 3_600_000L
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(0, 10L * 60_000L))
        // Seven checkpoints (15m first): index 6 (7 d) is a real checkpoint, index 7 is past the end.
        assertTrue(ForwardReturnLabeler7731.scheduleOpen8013(6, 6L * 24L * h))
        assertFalse(ForwardReturnLabeler7731.scheduleOpen8013(7, 2L * h))
    }

    // ── 8354 — PumpPortal per-token trade stream OFF by default ──

    @Test fun d10_pumpPortalTradeStreamOffByDefault() {
        assertFalse(PumpFunWS.TRADE_STREAM_DEFAULT_8354)
        assertFalse(BotConfig().pumpPortalTradeStreamEnabled)
    }

    // ── 8353 / 8363 — minLiquidityUsd, walletReserveSol, slippageBps are owner-only ──

    @Test fun d11_ownerOnlyKeysAreNeverTunable() {
        for (k in listOf("minLiquidityUsd", "walletReserveSol", "slippageBps")) {
            assertTrue(k, AdvisorKeyAuthority8353.isAdvisoryOnly8353(k))
            assertFalse(k, LlmParameterTuner.isAllowedKey(k))
            assertFalse(k, LlmParameterTuner.allowedKeys().contains(k))
        }
    }

    // ── 8324 (owner choice) / 8117 / 8197 — 8114 entry mode on, loss-streak cooldown off, entry edge 1.5x ──

    @Test fun d12_ownerChosenDefaults() {
        assertTrue(BotConfig().entry8114ModeEnabled)
        assertFalse(BotConfig().lossStreakCooldownEnabled)
        assertEquals(1.5, NetEdge8030.EDGE_MULT, 0.0)
        assertTrue(NetEdge8030.entryClears8030(15.0, 10.0))
        assertFalse(NetEdge8030.entryClears8030(14.9, 10.0))
    }

    // ── 8365 — log-optimal fractional Kelly; 0 without an edge ──

    @Test fun d13_logOptimalKelly() {
        val book = List(95) { -10.0 } + List(5) { 500.0 }
        assertEquals(0.31, Kelly8365.fullKelly8365(book.map { it / 100.0 }.toDoubleArray()), 0.005)
        assertEquals(0.0, Kelly8365.shareFromReturnsPct8365(List(40) { -5.0 }).frac, 0.0)
    }

    // ── 8366 — one walk-forward Cortex authority test ──

    @Test fun d14_cortexSingleAuthorityTest() {
        assertEquals(CortexAuthority8366.Verdict8366.INSUFFICIENT_OOS, CortexAuthority8366.test8366(emptyList(), 3.0).verdict)
        assertEquals(0.0, CortexAuthority8366.graduated8366(2.9, 3.0), 0.0)
        assertEquals(0.5, CortexAuthority8366.graduated8366(4.5, 3.0), 1e-12)
    }

    // ── 8348 — a momentum refusal on an unobserved read defers; an observed one refuses; safety is never mapped ──

    @Test fun d15_unobservedMomentumDefers() {
        val d = ContinuationRead8348.deferMomentum8348("NO_MOMENTUM_5M", false)
        assertNotNull(d)
        assertTrue(ContinuationRead8348.dataDefer8348(d))
        assertNull(ContinuationRead8348.deferMomentum8348("NO_MOMENTUM_5M", true))
        assertNull(ContinuationRead8348.deferMomentum8348("RUG_NO_MOMENTUM", false))
    }

    // ── 8298 — the 8114 loss backstops bind until a lane earns the widened ones on realised live closes ──

    @Test fun d16_the8114BackstopsBindUntilEarned() {
        assertEquals(9.0, RegressionRestore8298.backstopMag8298(25.0, RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, false), 0.0)
        assertEquals(25.0, RegressionRestore8298.backstopMag8298(25.0, RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, true), 0.0)
        assertEquals(15.0, RegressionRestore8298.BACKSTOP_PCT_8298, 0.0)
        assertFalse(RegressionRestore8298.standDownEarned8298(null))
    }

    // ── 8339 / 8342 — rehearsal never relaxes LIVE: every evidence door and the wallet door bind a live entry ──

    @Test fun d17_rehearsalNeverRelaxesLive() {
        assertTrue(Rehearsal8339.walletDoorBinds8339(true))
        assertTrue(Rehearsal8339.evidenceDoorBinds8342(false, true, "TIMING_RECORD_NEGATIVE_8094"))
        assertTrue(Rehearsal8339.evidenceDoorBinds8342(false, true, "LIVE_PROVEN_SETUP_ONLY_8059"))
        assertEquals("LIVE", Rehearsal8339.assessmentSource8342(false))
    }

    // ── 8327 / 8330 — ticket retry holds a fresh ticket; a resume never buys a mint already held ──

    @Test fun d18_ticketRetryAndResume() {
        assertTrue(TicketRetry8327.mayHold8327(null, System.currentTimeMillis()))
        assertEquals("ALREADY_HELD", TicketRetry8327.resumeRefusal8330(true, false, true, true, false, 1.0, 1.0))
    }
}
