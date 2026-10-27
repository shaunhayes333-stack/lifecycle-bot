package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.RunnerDefer8297
import com.lifecyclebot.engine.truth.RunnerFunnel8297
import com.lifecyclebot.engine.truth.RunnerFunnel8297.Stage8297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8297RunnerCaptureTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun missed(binding: String, downstream: String = "", evaluated: Boolean = true) =
        RunnerFunnel8297.attribute8297(false, Double.NaN, 2000.0, Double.NaN, 0L, 0L, 0L, evaluated, binding, downstream)

    @Test fun everyMissedRunnerIsAttributedToItsLosingStage() {
        assertEquals(Stage8297.NOT_EVALUATED, missed("", evaluated = false).stage)
        val data = missed("DATA_INCOMPLETE_DEFER_8136")
        assertEquals(Stage8297.DATA_DEFER, data.stage)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", data.gate)
        assertEquals(Stage8297.DATA_DEFER, missed("DATA_DEFER_FETCH_NEVER_COMPLETED_8204").stage)
        assertEquals(Stage8297.SAFETY, missed("CANDIDATE_ADMISSION_8136_SAFETY_REFUSED_8136").stage)
        val gate = missed("LIVE_PROVEN_SETUP_ONLY_8059")
        assertEquals(Stage8297.GATE_REFUSED, gate.stage)
        assertEquals("LIVE_PROVEN_SETUP_ONLY_8059", gate.gate)
        assertEquals("EXECUTION_ECONOMICS_8136", missed("EXECUTION_ECONOMICS_8136: gross=+3 cost=4").gate)
        val admitted = missed("", downstream = "ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188")
        assertEquals(Stage8297.ADMITTED_NOT_BOUGHT, admitted.stage)
        assertEquals("ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188", admitted.gate)
        assertEquals("NO_BUY_ATTEMPT", missed("").gate)
    }

    @Test fun aBoughtRunnerIsNotCaughtUnlessItBankedTheRun() {
        val close = 10_000_000L
        val hold = 600_000L
        // the run's peak came before the entry: a pump bought at its top
        assertEquals(Stage8297.BOUGHT_AFTER_PEAK, RunnerFunnel8297.attribute8297(true, -30.0, 800.0, 700.0,
            close - hold - 1, close, hold, true, "", "").stage)
        // the run peaked after the close: an exit capped it
        assertEquals(Stage8297.SOLD_BEFORE_PEAK, RunnerFunnel8297.attribute8297(true, 20.0, 2000.0, 0.0,
            close + 60_000L, close, hold, true, "", "").stage)
        // peak inside the hold, banked +100% of a +800% run (12.5%): sold early
        assertEquals(Stage8297.SOLD_EARLY, RunnerFunnel8297.attribute8297(true, 100.0, 800.0, 0.0,
            close - 1_000L, close, hold, true, "", "").stage)
        assertEquals(Stage8297.CAPTURED, RunnerFunnel8297.attribute8297(true, 400.0, 800.0, 0.0,
            close - 1_000L, close, hold, true, "", "").stage)
        assertEquals(Stage8297.HOLDING, RunnerFunnel8297.attribute8297(true, Double.NaN, 800.0, 0.0, 0L, 0L, 0L, true, "", "").stage)
    }

    @Test fun summaryCountsStagesAndNamesTheGates() {
        val s = RunnerFunnel8297.summary8297(listOf(missed("DATA_INCOMPLETE_DEFER_8136"), missed("DATA_INCOMPLETE_DEFER_8136"),
            missed("LIVE_PROVEN_SETUP_ONLY_8059")))
        assertTrue(s, s.contains("runners=3"))
        assertTrue(s, s.contains("data_defer=2"))
        assertTrue(s, s.contains("gate_refused=1"))
        assertTrue(s, s.contains("DATA:DATA_INCOMPLETE_DEFER_8136=2"))
        assertEquals("funnel8297{runners=0}", RunnerFunnel8297.summary8297(emptyList()))
    }

    @Test fun runnerAdmitOutcomesSeparateTopBuysFromCappedWins() {
        assertEquals(RunnerFunnel8297.Outcome8297.LOSS_BOUGHT_TOP, RunnerFunnel8297.outcome8297(-25.0, 2.0))
        assertEquals(RunnerFunnel8297.Outcome8297.LOSS_GAVE_BACK, RunnerFunnel8297.outcome8297(-10.0, 60.0))
        assertEquals(RunnerFunnel8297.Outcome8297.WIN_CAPPED, RunnerFunnel8297.outcome8297(30.0, 400.0))
        assertEquals(RunnerFunnel8297.Outcome8297.WIN, RunnerFunnel8297.outcome8297(150.0, 400.0))
        assertEquals(RunnerFunnel8297.Outcome8297.WIN, RunnerFunnel8297.outcome8297(10.0, Double.NaN))
        assertEquals(RunnerFunnel8297.Outcome8297.UNKNOWN, RunnerFunnel8297.outcome8297(Double.NaN, 10.0))
    }

    @Test fun aConfirmedRunPassesOnlyAnOptionalDataDefer() {
        val defer = CandidateAdmission8136.Terminal8136.DEFER_DATA
        assertTrue(RunnerDefer8297.releases8297(defer, listOf("bp:DEFAULT_UNOBSERVED", "chg:DEFAULT_UNOBSERVED", "shape:HISTORY_SPARSE"), true))
        // no confirmed run: the deferral stands
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("bp:DEFAULT_UNOBSERVED"), false))
        // a critical input is never waved through: liquidity, price, economics, route, sellability
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("bp:DEFAULT_UNOBSERVED", "liquidity:UNKNOWN"), true))
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("chg:UNOBSERVED", "price"), true))
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("economics"), true))
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("route:NONE"), true))
        assertFalse(RunnerDefer8297.releases8297(defer, listOf("sellability:UNKNOWN"), true))
        assertFalse(RunnerDefer8297.releases8297(defer, emptyList(), true))
        // only a deferral is ever released: a refusal or an admit is untouched
        assertFalse(RunnerDefer8297.releases8297(CandidateAdmission8136.Terminal8136.REFUSE, listOf("bp:X"), true))
        assertFalse(RunnerDefer8297.releases8297(CandidateAdmission8136.Terminal8136.ADMIT, listOf("bp:X"), true))
    }

    @Test fun aLapsedTicketWindowReArmsBoundedAndNeverAfterAClose() {
        val armedAt = 1_000_000L
        val due = armedAt + 3L * 60_000L + RunnerPlay8018.REARM_GAP_MS_8297 + 1L
        assertTrue(RunnerPlay8018.rearms8297(1, armedAt, due, false))
        assertFalse("still inside the window + gap", RunnerPlay8018.rearms8297(1, armedAt, armedAt + 60_000L, false))
        assertFalse("window budget spent", RunnerPlay8018.rearms8297(RunnerPlay8018.MAX_WINDOWS_8297, armedAt, due, false))
        assertFalse("the bot traded it since the first ticket", RunnerPlay8018.rearms8297(1, armedAt, due, true))
    }

    @Test fun wiredIntoTheLiveDoorTheEscalationTheCycleAndTheReport() {
        val door = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(door.contains("when (RunnerDefer8297.terminalFor8297(ts, admission8136, nowMs) {"))
        // V5.0.8399 — the candidate's own measured edge / lifecycle runner ticket passes the optional-only deferral too (P1 precedence).
        assertTrue(door.contains("measuredEdge8060(ts, l8059, nowMs) || (try { com.lifecyclebot.engine.RunnerTicket8073.admits8073(ts, nowMs) } catch (_: Throwable) { false })"))
        val funnel = src("engine/truth/RunnerFunnel8297.kt")
        assertTrue(funnel.contains("val positive = run || (try { candidatePositive8399() } catch (_: Throwable) { false })"))
        assertTrue(funnel.contains("if (!releases8297(t, missing, positive)) return t"))
        assertTrue("critical misses still defer", funnel.contains("if (missing.isEmpty() || missing.any { CandidateAdmission8136.criticalMissing8170(it) }) return t"))
        assertTrue(src("engine/Executor.kt").contains("val runner = com.lifecyclebot.engine.sell.ProfitEscalation8189.runnerProtected8297(ts)"))
        val esc = src("engine/sell/ProfitEscalation8189.kt")
        assertTrue(esc.contains("RunnerGrab7967.holdingRunner8018(ts)"))
        assertTrue(esc.contains("TradeExitContract8077.longHold(ts) == true"))
        assertTrue(src("engine/HandoffThroughput8190.kt").contains("RunnerPlay8018.activeTicketTargets8297(nowMs)"))
        val cap = src("engine/truth/RunnerCapture8017.kt")
        assertTrue(cap.contains("RunnerFunnel8297.onAnyClose8297(env)"))
        assertTrue(cap.contains("funnelLine8297(recent)"))
        // the runner set the escalation protects is the residual budget's own set
        assertTrue(src("engine/RunnerExitLive8136.kt").contains(
            "RunnerGrab7967.holdingRunner8018(ts) || TradeExitContract8077.longHold(ts) == true || LifecycleHold8065.holding8065(ts)"))
    }
}
