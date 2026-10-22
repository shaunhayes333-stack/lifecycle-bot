package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.RunnerFunnel8331.Stage8331
import com.lifecyclebot.engine.RunnerPlan8153.Plan8153
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Miss8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Trace8144
import com.lifecyclebot.engine.truth.RunnerTraceReclass8331
import com.lifecyclebot.engine.truth.RunnerTraceReclass8331.Reclass8331
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8331 — runner monetisation: the rung reads the authoritative mark, urgent exits pre-empt the hold, the runner
 * identity (ladder and plan) survives for the position's life, "wrongly refused" needs decision-time evidence, and the
 * funnel counts where a held runner stops.
 */
class Aate8331RunnerTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun token(mint: String, style: String, entryMs: Long, lane: String = "MOONSHOT", peakPct: Double = 0.0) =
        TokenState(mint = mint, position = Position(qtyToken = 1_000_000.0, entryPrice = 1.0, entryTime = entryMs,
            tradingMode = lane, isPaperPosition = false, peakGainPct = peakPct, entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))

    @Test fun rungTriggerReadsTheAuthoritativeMark() {
        val f = RunnerFunnel8331
        // A coin still on its curve: only its own curve / chain print may trigger a rung.
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = true, source = "PUMP_PORTAL_TRADE_WS_7278"))
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = true, source = "PUMP_CURVE_RPC"))
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = true, source = "PUMP_CURVE_RESERVES_SEED_7951"))
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = true, source = "PUMPSWAP_VAULT_WS_8018"))
        assertFalse(f.rungMarkAuthoritative8331(curveCoin = true, source = "DEXSCREENER_POLL"))
        assertFalse(f.rungMarkAuthoritative8331(curveCoin = true, source = "BIRDEYE_PRICE_FALLBACK"))
        assertFalse(f.rungMarkAuthoritative8331(curveCoin = true, source = ""))
        for (s in f.CURVE_SOURCES_8331) assertTrue(s, f.rungMarkAuthoritative8331(curveCoin = true, source = s))
        assertTrue(f.CURVE_SOURCES_8331.containsAll(listOf("PUMP_CURVE", "PUMP_PORTAL_TRADE_WS_7278", "PUMPSWAP_VAULT_WS_8018")))
        // A graduated / DEX coin's freshest row mark stands.
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = false, source = "DEXSCREENER_POLL"))
        assertTrue(f.rungMarkAuthoritative8331(curveCoin = false, source = ""))
        // Wiring: the 500 ms sweep asks before it hands the row mark to the ladder; the curve print path sells on the print.
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue(spike.contains("if (rungMarkAuthoritative8331(ts)) try { onMark(ts, px, sell) } catch (_: Throwable) {}"))
        assertTrue(spike.contains("RunnerFunnel8331.rungMarkAuthoritative8331(curve, ts.lastPriceSource)"))
        assertTrue(spike.indexOf("nowMs - ts.lastPriceUpdate <= FRESH_MARK_MS_7944") in 1 until spike.indexOf("if (rungMarkAuthoritative8331(ts))"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("try { SpikeCapture7943.onMark(ts, px) { t, frac, reason -> sellIntoSpike7943(t, frac, reason) } } catch (_: Throwable) {}"))
        // The rung sells through the normal partial path: quote at the rung size, slippage ladder, chain-verified finality.
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("applied = executor.requestPartialSellConfirmed6566(ts, fraction, reason, wallet, bal).applied") || bot.contains("applied = executor.requestPartialSellConfirmed6566(ts, fraction, reason, wallet, bal).applied"))
        assertTrue(exec.indexOf("val settled7835 = commitVerifiedLiveSlice7835(ts, proof7835, reason) ?: return") > 0)
        assertTrue(exec.contains("try { RunnerLive8153.bookSlice8153(ts, reason, proof.sig, result.actualConsumedRaw, result.solReceived, result.realizedPnl.proportionalCostBasisSol) } catch (_: Throwable) {}"))
    }

    @Test fun urgentExitPreemptsTheRunnerHold() {
        val f = RunnerFunnel8331
        assertTrue(f.urgentPreemptsHold8331("TICK_HARD_FLOOR_-38PCT"))
        for (r in listOf("RUG_PULL_DETECTED_-60PCT", "CATASTROPHIC_-40PCT", "RUNNER_HARD_STOP_7967_-36PCT", "REFLEX_LIQ_DRAIN_7807",
            "LP_PULL_DETECTED", "CANNOT_SELL_HONEYPOT", "WALLET_DRAIN_GUARD")) {
            assertTrue(r, f.urgentPreemptsHold8331(r))
            // The runner hold never defers them (RunnerGrab7967.deferSell7967 reads deferrable7967) ...
            assertFalse(r, RunnerGrab7967.deferrable7967(r, 120.0))
            // ... and a sealed / promoted runner with a confirmed continuation never holds them as a scalp partial.
            assertFalse(r, RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_SEALED, true, r, 60.0))
        }
        // Soft profit exits are what the hold is for.
        assertFalse(f.urgentPreemptsHold8331("PROFIT_LOCK_TRAIL_7928"))
        assertFalse(f.urgentPreemptsHold8331("THESIS_TIME_STOP_7739_LAUNCH_EARLY_12PCT"))
        assertFalse(f.urgentPreemptsHold8331(""))
        assertTrue(RunnerGrab7967.deferrable7967("PROFIT_LOCK_TRAIL_7928", 120.0))
        assertTrue(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_SEALED, true, "PROFIT_LOCK_TRAIL_7928", 60.0))
        // The lifecycle hold lets tier 1 through as well.
        assertTrue(LifecycleHold8065.passes8065("RUG_PULL_DETECTED_-60PCT"))
        assertTrue(LifecycleHold8065.passes8065("CATASTROPHIC_-40PCT"))
        // The runner's own hard stop is evaluated before the first-minute grace (8271) and claims the ticket first (8144).
        val grab = src("engine/RunnerGrab7967.kt")
        assertTrue(grab.contains("if (nowMs - pos.entryTime < 60_000L && !peakGiveback7980(peak, g) && g > HARD_STOP_PCT) return"))
        assertTrue(grab.contains("try { ExitTruthArbiterLive8144.claimProtective8144(ts, why, nowMs) } catch (_: Throwable) {}"))
    }

    @Test fun runnerIdentitySurvivesForThePositionsLife() {
        RunnerFunnel8331.resetForTest8331()
        val sevenHoursAgo = System.currentTimeMillis() - 7L * 3_600_000L
        val ts = token("sealed8331", "BREAKOUT_RUNNER", sevenHoursAgo)
        // The one plan still says RUNNER_SEALED ...
        assertEquals(Plan8153.RUNNER_SEALED, RunnerLive8153.plan8153(ts))
        // ... the 6 h hold window has lapsed (holding7967 is false) ...
        assertFalse(RunnerGrab7967.holdingRunner8018(ts))
        // ... and the rung ladder now follows the plan instead of falling back to the scalp ladder.
        assertEquals(RunnerGrab7967.TIERS_7967, RunnerGrab7967.tiersFor7967(ts))
        assertEquals(RunnerGrab7967.TIERS_7967, RunnerFunnel8331.ladderForPlan8331(Plan8153.RUNNER_SEALED))
        assertEquals(RunnerGrab7967.TIERS_7967, RunnerFunnel8331.ladderForPlan8331(Plan8153.RUNNER_PROMOTED))
        assertNull(RunnerFunnel8331.ladderForPlan8331(Plan8153.SCALP))
        // A QUICK_FLIP scalp keeps its scalp ladder (8136 / 8153 pins stand).
        assertNull(RunnerGrab7967.tiersFor7967(token("quick8331", "QUICK_FLIP", sevenHoursAgo)))
        // The funnel counted both live positions in a runner-hosting lane as held (once each) and one plan as sealed.
        RunnerLive8153.plan8153(ts)
        assertEquals(2L, RunnerFunnel8331.count8331(Stage8331.HELD))
        assertEquals(1L, RunnerFunnel8331.count8331(Stage8331.PLAN_SEALED))
        // A fill confirms the ladder its tranche was requested on, never a ladder the request did not use.
        val live = src("engine/RunnerLive8153.kt")
        assertTrue(live.contains("val ladder8331 = if (pending8331 != null && pending8331.tier == tier) pending8331.ladder else ladderOf8153(plan8153(ts))"))
        assertTrue(live.contains("if (book.confirm8153(key8153(ts), ladder8331, tier, signature, proceedsSol, soldCostSol)) {"))
        // The sealed entry identity reaches the plan through the position's own snapshot (mint-checked) and sealed style.
        assertTrue(live.contains("val runnerAdmit = snap?.takeIf { it.mint == ts.mint }?.runnerEntry == true"))
        assertTrue(src("engine/RunnerGrab7967.kt").contains("else RunnerFunnel8331.ladderForPlan8331(try { RunnerLive8153.plan8153(ts) } catch (_: Throwable) { RunnerPlan8153.Plan8153.SCALP })"))
    }

    @Test fun launchLadderPlanIsSealedToThePositionAndReported() {
        RunnerFunnel8331.resetForTest8331()
        val plan = TradePlan7739.Plan(TradePlan7739.Setup.LAUNCH_EARLY, -15.0, 50.0, 100.0, 1_000L)
        // The LAUNCH_EARLY plan arms its half at +50% and (off a runner lane) its target at +100%.
        val half = TradePlan7739.exitFor(plan, 52.0, 52.0, 60_000L, false, 6.0, runner = true)
        assertEquals(TradePlan7739.ExitKind.HALF, half?.kind)
        assertEquals("firstHit", TradePlan7739.launchStage8331(plan, half!!))
        TradePlan7739.onExit(plan, half)
        assertTrue(plan.firstTargetTaken)
        val target = TradePlan7739.exitFor(plan, 101.0, 101.0, 120_000L, false, 6.0, runner = false)
        assertEquals(TradePlan7739.ExitKind.FULL, target?.kind)
        assertEquals("targetHit", TradePlan7739.launchStage8331(plan, target!!))
        TradePlan7739.onExit(plan, target)
        assertNull(TradePlan7739.launchStage8331(plan, TradePlan7739.Exit(TradePlan7739.ExitKind.FULL, "STRUCTURE_STOP_7739_LAUNCH_EARLY_-16PCT")))
        assertNull(TradePlan7739.launchStage8331(TradePlan7739.Plan(TradePlan7739.Setup.BASE_BREAKOUT, -10.0, 20.0, 40.0, 1_000L), half))
        assertNull(TradePlan7739.launchStage8331(null, half))
        assertEquals(1L, RunnerFunnel8331.launchCount8331("firstHit"))
        assertEquals(1L, RunnerFunnel8331.launchCount8331("targetHit"))
        assertTrue(RunnerFunnel8331.launchLine8331().startsWith("launchLadder8331[armed=0 firstHit=1 targetHit=1]"))
        // The plan is sealed to the position (mint|entryTime) the first time it is read: a later re-plan of the mint no
        // longer makes planFor return null for the open position.
        val tp = src("engine/truth/TradePlan7739.kt")
        assertTrue(tp.contains("sealed8331[sealKey]?.let { return it }"))
        assertTrue(tp.indexOf("sealed8331[sealKey]?.let { return it }") in 1 until tp.indexOf("val p = plans[mint] ?: return null"))
        assertTrue(tp.contains("if (sealed8331.putIfAbsent(sealKey, p) == null && p.setup == Setup.LAUNCH_EARLY) {"))
        assertTrue(tp.contains("com.lifecyclebot.engine.RunnerFunnel8331.launchLine8331()"))
        // The plan tick still banks the half through the partial path and the rest through the full sell door.
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("if (exit.kind == com.lifecyclebot.engine.truth.TradePlan7739.ExitKind.HALF) {"))
        assertTrue(bot.contains("try { executor.requestPartialSellConfirmed6566(ts, 0.5, exit.reason, walletP, balP) } catch (_: Throwable) {}"))
    }

    private val t0 = 1_700_000_000_000L
    private val base = Trace8144(
        mint = "RUN8331pump", symbol = "RUNR", lane = "MOONSHOT", peakPct = 918.0, detectedAtMs = t0 + 3_600_000L,
        firstSeenMs = t0, source = "PUMP_FUN_NEW", birthKind = "KNOWN", birthSource = "PUMP_CREATE_EVENT", birthAgeMs = 45_000L,
        liquidityUsd = 6_000.0, nativeAtMs = t0 + 5_000L, natives = "MOONSHOT:Y:62:MOONSHOT_NATIVE_ALLOW",
        qualified = "MOONSHOT", setup = "LAUNCH_LADDER_PROVEN", admission = "", fdgLane = "", fdgVerdict = "", handoff = "",
        refusedBy = "SOME_OTHER_GATE_STOPPED_IT", downstream = "", markPx = 1.2e-5, windowAtMs = t0 + 5_000L, windowCostPct = 6.5,
        windowCostMeasured = true, route = "CURVE", held = false,
    )

    @Test fun wronglyRefusedNeedsDecisionTimeEvidence() {
        val r = RunnerTraceReclass8331
        // The 8144 rule: a qualified lane stopped by an unnamed gate reads WRONGLY_REFUSED (the raw count stays visible).
        val raw = AdmissionRunnerTrace8144.classify8144(base)
        assertEquals(Miss8144.WRONGLY_REFUSED, raw.miss)
        assertTrue(raw.gate.startsWith("QUALIFIED_LANE_STOPPED"))
        // Without the evidence fields it is UNPROVEN: qualified elsewhere only.
        assertEquals(Reclass8331.UNPROVEN, r.reclassify8331(base))
        // With every piece of evidence in favour it is a WRONG_REFUSAL.
        val proven = base.copy(exitDepthUsd = 2_400.0, expectedNetPct = 18.0, hardSafetyPassed = true)
        assertEquals(Reclass8331.WRONG_REFUSAL, r.reclassify8331(proven))
        // Any failing condition keeps it UNPROVEN: safety not passed, depth under the live floor, no positive net.
        assertEquals(Reclass8331.UNPROVEN, r.reclassify8331(proven.copy(hardSafetyPassed = false)))
        assertEquals(Reclass8331.UNPROVEN, r.reclassify8331(proven.copy(exitDepthUsd = 400.0)))
        assertEquals(Reclass8331.UNPROVEN, r.reclassify8331(proven.copy(expectedNetPct = -1.0)))
        assertEquals(Reclass8331.UNPROVEN, r.reclassify8331(proven.copy(expectedNetPct = Double.NaN)))
        // No window observed at all: the data path failed first.
        assertEquals(Reclass8331.DATA_GAP, r.reclassify8331(proven.copy(windowAtMs = 0L)))
        assertEquals(Reclass8331.DATA_GAP, r.reclassify8331(proven.copy(route = "")))
        // Verdicts the 8144 rule did not call wrong are untouched.
        assertNull(r.reclassify8331(base.copy(held = true)))
        assertNull(r.reclassify8331(base.copy(qualified = "")))
        assertNull(r.reclassify8331(base.copy(admission = "REFUSE:SAFETY_REFUSED_8136@MOONSHOT")))
        assertFalse(r.evidenceFavours8331(true, "POOL", RunnerTraceReclass8331.LIVE_EXIT_FLOOR_USD_8331 - 0.1, 5.0))
        assertTrue(r.evidenceFavours8331(true, "POOL", RunnerTraceReclass8331.LIVE_EXIT_FLOOR_USD_8331, 0.1))
        assertEquals(500.0, RunnerTraceReclass8331.LIVE_EXIT_FLOOR_USD_8331, 1e-9)   // PreTradeHardGate MIN_LIVE_LIQ_HARD_FLOOR_USD
        assertTrue(src("engine/PreTradeHardGate.kt").contains("private const val MIN_LIVE_LIQ_HARD_FLOOR_USD = 500.0"))
        assertFalse(r.carriesEvidence8331(base))
        assertTrue(r.carriesEvidence8331(proven))
        assertTrue(r.carriesEvidence8331(base.copy(hardSafetyPassed = true)))
        assertEquals("reclass8331[rawWrong=3 wrongRefusal=1 unproven=1 dataGap=1 withEvidence=2/4]",
            r.summary8331(listOf(base, proven, proven.copy(windowAtMs = 0L), base.copy(held = true))))
        // The fields round-trip through the JSONL export and are recorded at the window.
        val back = AdmissionRunnerTrace8144.fromJson8144(AdmissionRunnerTrace8144.toJson8144(proven))
        assertEquals(proven, back)
        val old = AdmissionRunnerTrace8144.fromJson8144(AdmissionRunnerTrace8144.toJson8144(base))
        assertFalse(old.exitDepthUsd.isFinite())
        assertFalse(old.hardSafetyPassed)
        val trace = src("engine/truth/AdmissionRunnerTrace8144.kt")
        assertTrue(trace.contains("e.exitDepthUsd = try { LiquidityTruth8037.hydrate8037(ts).takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN } catch (_: Throwable) { Double.NaN }"))
        assertTrue(trace.contains("if (cost.isFinite()) NetEdge8030.expectedGrossFor8153(ts, lane, e.setup.ifBlank { null }, cost) - cost else Double.NaN"))
        assertTrue(trace.contains("e.hardSafetyPassed = try { ts.safety.tier != com.lifecyclebot.engine.SafetyTier.HARD_BLOCK } catch (_: Throwable) { false }"))
        assertTrue(trace.contains("${'$'}{RunnerTraceReclass8331.summary8331(list)}"))
    }

    @Test fun funnelCountsEachStageOncePerPositionAndNamesTheBlock() {
        val f = RunnerFunnel8331
        f.resetForTest8331()
        assertTrue(f.stage8331(Stage8331.HELD, "m|1"))
        assertFalse(f.stage8331(Stage8331.HELD, "m|1"))        // once per position
        assertTrue(f.stage8331(Stage8331.HELD, "m|2"))
        assertTrue(f.stage8331(Stage8331.RUNG_TRIGGERED, "m|1|T1"))
        assertTrue(f.stage8331(Stage8331.RUNG_TRIGGERED, "m|1|T2"))   // per tier
        assertFalse(f.stage8331(Stage8331.RUNG_TRIGGERED, "m|1|T2"))
        assertTrue(f.blocked8331("BUDGET_REFUSED", "m|1|T1"))
        assertFalse(f.blocked8331("BUDGET_REFUSED", "m|1|T1"))
        assertTrue(f.blocked8331("TICKET_HELD"))
        assertTrue(f.blocked8331("TICKET_HELD"))                 // keyless: every call counts
        assertFalse(f.blocked8331(""))
        assertEquals(2L, f.count8331(Stage8331.HELD))
        assertEquals(2L, f.count8331(Stage8331.RUNG_TRIGGERED))
        assertEquals(0L, f.count8331(Stage8331.PROCEEDS_BOOKED))
        assertEquals(1L, f.blockedCount8331("BUDGET_REFUSED"))
        assertEquals(2L, f.blockedCount8331("TICKET_HELD"))
        val line = f.statusLine8331()
        assertTrue(line, line.startsWith("runnerFunnel8331[held=2 planSealed=0 rungArmed=0 rungTriggered=2 sellSent=0 sellLanded=0 proceedsBooked=0 blockedWhy={"))
        assertTrue(line, line.contains("TICKET_HELD=2") && line.contains("BUDGET_REFUSED=1") && line.endsWith("}]"))
        f.resetForTest8331()
        assertTrue(f.statusLine8331().endsWith("blockedWhy={-}]"))
        // Wiring: every stage has its hook, and the line is on the runner report, the bank line and the plan line.
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue(spike.contains("if (runnerRung8331) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.RUNG_TRIGGERED, \"${'$'}funnelKey8331|T${'$'}reached\")"))
        assertTrue(spike.contains("if (runnerRung8331) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.RUNG_ARMED, \"${'$'}funnelKey8331|T${'$'}reached\")"))
        assertTrue(spike.contains("if (runnerRung8331) RunnerFunnel8331.blocked8331(\"TICKET_HELD\", \"${'$'}funnelKey8331|T${'$'}reached\")"))
        assertTrue(spike.contains("if (runnerRung8331) RunnerFunnel8331.blocked8331(\"PENDING_OR_BACKOFF\", \"${'$'}funnelKey8331|T${'$'}reached\")"))
        assertTrue(spike.contains("if (runnerRung8331) RunnerFunnel8331.blocked8331(\"BUDGET_REFUSED\", \"${'$'}funnelKey8331|T${'$'}reached\")"))
        assertTrue(spike.contains("RunnerFunnel8331.blocked8331(\"SELL_NOT_APPLIED\""))
        assertTrue(spike.contains("RunnerFunnel8331.blocked8331(\"ARBITER_SUPERSEDED\")"))
        val live = src("engine/RunnerLive8153.kt")
        assertTrue(live.contains("RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.HELD, key8153(ts))"))
        assertTrue(live.contains("if (plan != RunnerPlan8153.Plan8153.SCALP) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.PLAN_SEALED, key8153(ts))"))
        assertTrue(live.contains("RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.SELL_SENT, \"${'$'}key|T${'$'}{p.tier}\")"))
        assertTrue(live.contains("RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.SELL_LANDED, \"${'$'}{key8153(ts)}|T${'$'}tier\")"))
        assertTrue(live.contains("RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.PROCEEDS_BOOKED, \"${'$'}{key8153(ts)}|T${'$'}tier\")"))
        assertTrue(live.contains("RunnerFunnel8331.statusLine8331()"))
        assertTrue(src("engine/truth/RunnerCapture8017.kt").contains("val funnel8331 = try { com.lifecyclebot.engine.RunnerFunnel8331.statusLine8331() } catch (_: Throwable) { \"runnerFunnel8331 unavailable\" }"))
    }
}
