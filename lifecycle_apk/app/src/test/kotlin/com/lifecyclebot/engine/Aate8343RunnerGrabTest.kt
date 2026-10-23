package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8343 — the owner's "if it sees a 100x opportunity, take it" door. 8341: 33 of 37 runners >= 4x died at native lane
 * MANDATES (mcap band, liquidity floor / unhydrated defer, score threshold, continuation, CORE yield-clear) before any
 * admission saw them. The grab lane admits a forming runner on its own legs; a hydrated deferral is re-queued at once with
 * its version; CORE's yield falls back to CORE when no specialist took the coin.
 */
class Aate8343RunnerGrabTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun fresh() { RunnerGrabLane8343.resetForTests8343(); HydrationReeval8343.resetForTests8343() }
    @After fun back() { RunnerGrabLane8343.resetForTests8343(); HydrationReeval8343.resetForTests8343() }

    private fun legs() = RunnerGrabLane8343.Legs8343(
        positionOpen = false, safety = null, mayhem = false, venue = RunnerGrabLane8343.VENUE_CURVE_8343,
        evidence = RunnerGrabLane8343.EVIDENCE_FORMING_8343, expectedGrossPct = 50.0, costPct = 8.0, movePct = Double.NaN,
        openGrabs = 0, liveHoldRefusal = null,
    )

    @Test fun everyLegIsRequiredAndAllPresentAdmits() {
        val g = RunnerGrabLane8343
        assertNull("all legs present: admit", g.refusal8343(legs()))
        assertEquals("POSITION_OPEN", g.refusal8343(legs().copy(positionOpen = true)))
        assertEquals("SAFETY_UNCHECKED", g.refusal8343(legs().copy(safety = "SAFETY_UNCHECKED")))
        assertEquals("MAYHEM", g.refusal8343(legs().copy(mayhem = true)))
        assertEquals("VENUE_NOT_EXIT_SAFE", g.refusal8343(legs().copy(venue = null)))
        assertEquals("VENUE_NOT_EXIT_SAFE", g.refusal8343(legs().copy(venue = "")))
        assertEquals("NO_FORMING_RUN", g.refusal8343(legs().copy(evidence = null)))
        assertEquals("economics at 1.5x the round trip at the grab size", "ECONOMICS_1_5X_COST", g.refusal8343(legs().copy(expectedGrossPct = 10.0, costPct = 8.0)))
        assertNull("12% clears 1.5 x 8%", g.refusal8343(legs().copy(expectedGrossPct = 12.0, costPct = 8.0)))
        assertEquals("the launch-breakout chase cap (8%) binds", "CHASE_OVER_CAP", g.refusal8343(legs().copy(movePct = 9.0)))
        assertEquals("a move inside the cap that eats the edge binds", "CHASE_EDGE_CONSUMED", g.refusal8343(legs().copy(movePct = 7.0, expectedGrossPct = 14.0, costPct = 8.0)))
        assertNull("a cheaper quote never refuses", g.refusal8343(legs().copy(movePct = -3.0)))
        assertEquals("OPEN_BOUND_3", g.refusal8343(legs().copy(openGrabs = 3)))
        assertEquals("LIVE_EVIDENCE_HOLD_8336_MOONSHOT", g.refusal8343(legs().copy(liveHoldRefusal = "EVIDENCE_HOLD_8336_MOONSHOT")))
        assertEquals("the safety leg is read before the venue and the run", "SAFETY_HARD_BLOCK",
            g.refusal8343(legs().copy(safety = "SAFETY_HARD_BLOCK", venue = null, evidence = null)))
    }

    @Test fun theSafetyLegNeedsAFullVerdictNeverACachedUnknown() {
        val now = 10_000_000L
        val g = RunnerGrabLane8343
        assertEquals("SAFETY_UNCHECKED", g.safetyLeg8343(0L, SafetyTier.SAFE, true, true, now))
        assertEquals("SAFETY_HARD_BLOCK", g.safetyLeg8343(now - 1_000L, SafetyTier.HARD_BLOCK, true, true, now))
        assertEquals("SAFETY_AUTHORITY_UNPROVEN", g.safetyLeg8343(now - 1_000L, SafetyTier.SAFE, null, true, now))
        assertEquals("SAFETY_AUTHORITY_UNPROVEN", g.safetyLeg8343(now - 1_000L, SafetyTier.SAFE, true, false, now))
        assertEquals("SAFETY_STALE", g.safetyLeg8343(now - RunnerGrabLane8343.SAFETY_MAX_AGE_MS_8343 - 1L, SafetyTier.SAFE, true, true, now))
        assertNull(g.safetyLeg8343(now - 1_000L, SafetyTier.SAFE, true, true, now))
        assertNull("CAUTION is a soft tier, not a wall", g.safetyLeg8343(now - 1_000L, SafetyTier.CAUTION, true, true, now))
    }

    @Test fun noLaneMandateBindsOnlyWalls() {
        val g = RunnerGrabLane8343
        // 8341's killers are mandates the grab lane looks past (its own venue leg re-tests the binding exit floor on chain truth).
        for (m in listOf("NATIVE_MCAP_TOO_LOW", "MCAP_TOO_LOW: $1 < $5000", "LANE_LIQ_BELOW_FLOOR_8140_MOONSHOT", "LANE_LIQ_UNHYDRATED_DEFER_8140_SHITCOIN",
            "THRESHOLD_FAIL_SCORE", "NO_CONTINUATION", "TREASURY_ROLE_FLOOR_8031", "CORE_YIELD_CLEAR_SPECIALIST_OWNER", "NATIVE_LIQ_TOO_LOW", "TOO_YOUNG: 0.2h"))
            assertTrue(m, g.mandateRefusal8343(m))
        // walls are never re-asked
        for (w in listOf("RUG_RISK", "SAFETY_HARD_BLOCK", "DEV_SOLD", "ZERO_LIQUIDITY", "MINT_AUTH_ENABLED", "HONEYPOT", "BUNDLE_HIGH", "FREEZE_AUTHORITY",
            "BLACKLIST", "BRAIN_ERROR_X", "TOXIC", "QUARANTINED", "CANNOT_SELL", "TOKEN_2022_HOSTILE", "", "   "))
            assertFalse(w, g.mandateRefusal8343(w))
        // a $1-mcap coin on a $600 exit-safe curve: the legs carry no mcap, no score, no continuation — only the walls above.
        val lane = src("engine/RunnerGrabLane8343.kt")
        val legsDecl = lane.substring(lane.indexOf("data class Legs8343("), lane.indexOf("data class Verdict8343("))
        for (banned in listOf("mcap", "Mcap", "score", "Score", "continuation", "threshold", "age", "Age"))
            assertFalse("the legs carry no $banned", legsDecl.contains(banned))
        assertNull(g.refusal8343(legs().copy(venue = RunnerGrabLane8343.VENUE_CURVE_8343, evidence = RunnerGrabLane8343.EVIDENCE_GRAB_8343)))
        assertEquals("RUNNER_GRAB_8343:GRAB_7967 venue=ACTIVE_CURVE_REAL_SOL_8319", g.grabReason8343(RunnerGrabLane8343.EVIDENCE_GRAB_8343, RunnerGrabLane8343.VENUE_CURVE_8343))
        assertTrue(g.grabReason8343("x", "y").startsWith(RunnerGrabLane8343.REASON_PREFIX_8343))
        assertEquals("the forming-runner setup CandidateAdmission8136 already reads", "RUNNER_FORMING_8260", RunnerGrabLane8343.SETUP_8343)
        assertEquals(listOf("MOONSHOT", "SHITCOIN", "EXPRESS"), RunnerGrabLane8343.GRAB_LANES_8343)
        assertEquals("RUNNER_GRAB", RunnerGrabLane8343.LANE_8343)
    }

    @Test fun theRuntimeVerdictNamesItsRefusalAndItsLabels() {
        val g = RunnerGrabLane8343
        val t0 = 7_000_000_000L
        assertEquals("NO_MINT", g.admit8343(com.lifecyclebot.data.TokenState(mint = "", symbol = "X"), "MOONSHOT", t0).why)
        val ts = com.lifecyclebot.data.TokenState(mint = "mint8343-no-run", symbol = "NORUN")
        val v = g.admit8343(ts, "MOONSHOT", t0)
        assertFalse(v.admitted)
        assertEquals("a coin with no forming run is no grab (and no refusal label is spent on it)", "NO_FORMING_RUN", v.why)
        assertTrue("the verdict is cached", g.admit8343(ts, "MOONSHOT", t0 + 1_000L) === v)
        assertEquals("OVER_CAP", g.chaseLeg8343(9.0, 50.0, 8.0))
        assertNull(g.chaseLeg8343(Double.NaN, 50.0, 8.0))
        assertTrue("the grab size is a positive route minimum or the default ticket", g.grabSizeSol8343() > 0.0)
        assertEquals(90_000L, RunnerGrabLane8343.ADMIT_TTL_MS_8343)
        assertEquals(6L * 3_600_000L, RunnerGrabLane8343.OPEN_WINDOW_MS_8343)
        assertEquals("RUNNER_GRAB_ADMIT_8343", RunnerGrabLane8343.ADMIT_LABEL_8343)
        assertEquals("RUNNER_GRAB_REFUSED_8343_", RunnerGrabLane8343.REFUSED_PREFIX_8343)
        assertEquals("POOL_EXIT_FLOOR_500", RunnerGrabLane8343.VENUE_POOL_8343)
        val st = g.statusLine8343(t0 + 2_000L)
        assertTrue(st, st.contains("detected=0 admitted=0") && st.contains("notForming=1"))
    }

    @Test fun atMostThreeGrabsOpenAndOnePerMint() {
        assertEquals(3, RunnerGrabLane8343.MAX_OPEN_8343)
        assertNull(RunnerGrabLane8343.refusal8343(legs().copy(openGrabs = 2)))
        assertEquals("OPEN_BOUND_3", RunnerGrabLane8343.refusal8343(legs().copy(openGrabs = 3)))
        assertEquals("OPEN_BOUND_3", RunnerGrabLane8343.refusal8343(legs().copy(openGrabs = 7)))
        assertEquals("POSITION_OPEN", RunnerGrabLane8343.refusal8343(legs().copy(positionOpen = true, openGrabs = 0)))
        assertEquals(0, RunnerGrabLane8343.openGrabs8343(1_000L))
        assertFalse(RunnerGrabLane8343.admitted8343("nobody", "MOONSHOT", 1_000L))
    }

    @Test fun hydrationRequeueCarriesTheOriginalVersion() {
        val h = HydrationReeval8343
        val t0 = 5_000_000_000L
        for (r in listOf("LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", "LIQ_UNHYDRATED_DEFER_8140", "NATIVE_MCAP_UNHYDRATED_DEFER_8236", "MCAP_UNHYDRATED_DEFER_8190", "DEFERRED:LANE_LIQ_UNHYDRATED_DEFER_8140_EXPRESS"))
            assertTrue(r, h.unhydratedDefer8343(r))
        for (r in listOf("MCAP_TOO_LOW", "LANE_LIQ_BELOW_FLOOR_8140_MOONSHOT", "ZERO_LIQUIDITY", null, ""))
            assertFalse(r.orEmpty(), h.unhydratedDefer8343(r))
        val d = h.remember8343(null, "m", "MOONSHOT", "LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", 77L, t0)
        val d2 = h.remember8343(d, "m", "shitcoin", "LANE_LIQ_UNHYDRATED_DEFER_8140_SHITCOIN", 99L, t0 + 1_000L)
        assertEquals(setOf("MOONSHOT", "SHITCOIN"), d2.lanes)
        assertEquals("the first version stands", 77L, d2.version)
        assertEquals(t0, d2.atMs)
        assertNull("liquidity still unread queues nothing", h.queue8343(d2, 0.0, 0.5, 360.0, t0 + 2_000L))
        assertNull("NaN queues nothing", h.queue8343(d2, Double.NaN, 0.5, 360.0, t0 + 2_000L))
        assertNull("past the launch window", h.queue8343(d2, 650.0, 400.0, 360.0, t0 + 2_000L))
        assertNull("a deferral older than the memory", h.queue8343(d2, 650.0, 0.5, 360.0, t0 + HydrationReeval8343.DEFER_MEMORY_MS_8343 + 1L))
        assertNull("no deferral", h.queue8343(null, 650.0, 0.5, 360.0, t0 + 2_000L))
        val q = h.queue8343(d2, 650.0, 0.5, 360.0, t0 + 2_000L, "CURVE_READ_7951")
        assertNotNull(q)
        assertEquals(77L, q!!.version)
        assertEquals(setOf("MOONSHOT", "SHITCOIN"), q.lanes)
        assertEquals(t0 + 2_000L + HydrationReeval8343.QUEUE_TTL_MS_8343, q.deadlineMs)
        assertEquals(650.0, q.liquidityUsd, 1e-9)
        assertNotNull("an unresolved birth is not 'old'", h.queue8343(d2, 650.0, null, 360.0, t0 + 2_000L))
        // runtime: deferred -> the depth read queues it -> the next cycle's priority targets carry it -> evaluated / admitted
        h.noteDeferred8343("mintH8343", "MOONSHOT", "LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", 5L, t0)
        h.onDepthRead8343("mintH8343", 700.0, t0 + 500L)
        assertEquals(listOf("mintH8343" to t0 + 500L + HydrationReeval8343.QUEUE_TTL_MS_8343), h.targets8343(t0 + 600L))
        assertEquals(5L, h.carriedVersion8343("mintH8343", t0 + 600L))
        assertNull(h.carriedVersion8343("mintH8343", t0 + 500L + HydrationReeval8343.QUEUE_TTL_MS_8343 + 1L))
        h.onEvaluated8343("mintH8343", true, t0 + 700L)
        assertTrue(h.targets8343(t0 + 800L).isEmpty())
        val line = h.statusLine8343(t0 + 800L)
        assertTrue(line, line.contains("hydrationReeval8343[queued=1 evaluated=1 admitted=1"))
        assertNull("a mint nobody deferred is not queued by a read", run { h.onDepthRead8343("other", 700.0, t0); h.carriedVersion8343("other", t0) })
        // the hand-off task waiting on the same deferral resumes with its version
        val task = HandoffReceiverTasks8153.Task8153("mintH8343", "MOONSHOT", "PROJECT_SNIPER", "RUN_TAPE_8293", 42L, t0, t0 + 30_000L, t0,
            lastBlock = "DEFERRED:LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT")
        assertTrue(HandoffReceiverTasks8153.awaitsHydration8343(task))
        assertFalse(HandoffReceiverTasks8153.awaitsHydration8343(task.copy(lastBlock = "DEFERRED:NO_TOKEN_STATE")))
        val resumed = HandoffReceiverTasks8153.resumeOnHydration8343(task, t0 + 10_000L)
        assertNotNull(resumed)
        assertEquals("", resumed!!.lastBlock)
        assertEquals(42L, resumed.version)
        assertEquals(t0 + 10_000L + HandoffReceiverTasks8153.TASK_TTL_MS_8153, resumed.validUntilMs)
        assertNull("past the max age nothing resumes", HandoffReceiverTasks8153.resumeOnHydration8343(task, t0 + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153))
    }

    private fun op(lane: String, eligible: Boolean, reason: String) =
        SpecialistBrainBridge7542.Opinion(lane, eligible, 10, 10, 0.0, reason, "NONE", "e", "x")

    @Test fun coreYieldFallsBackWhenNoSpecialistTookTheCoin() {
        val g = RunnerGrabLane8343
        assertTrue(g.coreYieldFalls8343(0, listOf("MCAP_TOO_LOW", "LANE_LIQ_BELOW_FLOOR_8140_SHITCOIN", "NO_CONTINUATION")))
        assertFalse("a specialist took it: a real yield", g.coreYieldFalls8343(1, listOf("MCAP_TOO_LOW")))
        assertFalse("a safety refusal is not a mandate", g.coreYieldFalls8343(0, listOf("MCAP_TOO_LOW", "RUG_RISK")))
        assertFalse("nobody refused anything", g.coreYieldFalls8343(0, emptyList()))
        val specialists = listOf(op("MOONSHOT", false, "MCAP_TOO_LOW"), op("SHITCOIN", false, "LANE_LIQ_BELOW_FLOOR_8140_SHITCOIN"))
        assertEquals(RunnerGrabLane8343.CORE_OWN_BAR_8343, g.coreYieldReason8343(true, specialists))
        assertEquals(1L, g.coreYieldFallbackCount8343())
        assertEquals("CORE_YIELD_CLEAR_SPECIALIST_OWNER", g.coreYieldReason8343(false, specialists + op("EXPRESS", true, "RIDE")))
        assertEquals("CORE_YIELD_CLEAR_SPECIALIST_OWNER", g.coreYieldReason8343(true, listOf(op("MOONSHOT", false, "DEV_SOLD"))))
        assertEquals(1L, g.coreYieldFallbackCount8343())
        assertTrue("CORE's own refusal is a judgement the veto audit may retire, never a wall", SpecialistBrainBridge7542.nativeRuleRetirable8048(RunnerGrabLane8343.CORE_OWN_BAR_8343))
        assertFalse("and it is not a lane-fit hand-off to a receiver that does not exist", SpecialistOwnership7951.laneFitRefusal8132(RunnerGrabLane8343.CORE_OWN_BAR_8343))
        val brain = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue("CORE's yield-clear is conditional on a specialist taking it", brain.contains("else->RunnerGrabLane8343.coreYieldReason8343(s.isEmpty(),out.values)"))
        assertTrue("the own-bar refusal is audited like the yield", brain.contains("o.reason != RunnerGrabLane8343.CORE_OWN_BAR_8343"))
        val reviewed = SpecialistBrainBridge7542.reviewCoreYield8077(op("CORE", false, RunnerGrabLane8343.CORE_OWN_BAR_8343)) { _, _ -> true }
        assertTrue(reviewed.eligible && reviewed.reason.startsWith("NATIVE_VETO_RETIRED_8048"))
    }

    @Test fun closesAndReportLine() {
        assertEquals("-", RunnerGrabLane8343.closesLine8343(emptyList()))
        val line = RunnerGrabLane8343.closesLine8343(listOf(Triple("paper", 0.0, -1.0), Triple("paper", 100.0, 2.0)))
        assertEquals("n2/+50.0%/pf2.00 (paper 2, live 0)", line)
        val st = RunnerGrabLane8343.statusLine8343(1_000L)
        assertTrue(st, st.contains("runnerGrab8343[detected=0 admitted=0 refused=[-] open=0/3 paper=0 live=0 closes=-"))
        assertTrue(st, st.contains("lane=RUNNER_GRAB@MOONSHOT|SHITCOIN|EXPRESS]") && st.contains("hydrationReeval8343["))
        val health = src("engine/PipelineHealthCollector.kt")
        assertTrue("the grab line sits beside the runner capture line", health.contains("RunnerGrabLane8343.statusLine8343()") &&
            health.indexOf("Runner grab lane (§8343)") > health.indexOf("Runner capture (§8017)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("RunnerGrabLane8343.onClose8343(env)"))
    }

    @Test fun theHooksAreWiredAndTheLiveDoorIsTheOwnersExceptionNotARewrite() {
        val lane = src("engine/RunnerGrabLane8343.kt")
        assertTrue("live goes through the evidence hold (which consults exceptional8336)", lane.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs)"))
        assertFalse("the exception door is called, never re-implemented", lane.contains("exceptionalReason8337(") || lane.contains("rateAllows8337("))
        assertTrue("the hold is consulted only after every other leg passed", lane.indexOf("if (why == null && live)") > lane.indexOf("var why = refusal8343(legs)"))
        assertTrue(lane.contains("LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(mint, nowMs)"))
        assertTrue(lane.contains("LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140"))
        assertTrue(lane.contains("if (!graduated && known) return null"))
        assertTrue(lane.contains("RunnerGrab7967.grab7967(ts.mint, nowMs)") && lane.contains("RunnerGrab7967.formingNative8260(ts, nowMs)"))
        assertTrue(lane.contains("NetEdge8030.preTradeCostPct8329(l, size, liq, mint, ts.lastMcap)") && lane.contains("NetEdge8030.entryClears8030(l.expectedGrossPct, l.costPct)"))
        assertTrue(lane.contains("EntryEconomics8329.CAP_LAUNCH_PCT_8329"))
        assertTrue("the hold is latched on the runner ladder", lane.contains("RunnerGrab7967.holdAsRunner7972(ts, nowMs)"))
        assertTrue("the grab size is the route minimum of the live wallet", lane.contains(".routableMinSol"))
        val brain = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(brain.contains("RunnerGrabLane8343.reviewNative8343(ts, out, now)"))
        assertTrue("the generic FDG floor stands aside for a grab as for a retired veto", brain.contains("r.startsWith(RunnerGrabLane8343.REASON_PREFIX_8343)"))
        assertTrue("the lane's buy door honours the admit", src("engine/BotService.kt").contains("RunnerGrabLane8343.mandateRefusal8343(reason) && RunnerGrabLane8343.admitted8343(mint, lane)"))
        assertTrue("the route-minimum promotion takes EARLY_RUNNER", src("engine/truth/EntryEconomics8329.kt").contains("RunnerGrabLane8343.admitted8343(mint, lane, nowMs)"))
        val liq = src("engine/truth/LiquidityTruth8037.kt")
        assertTrue("one hydration write", liq.contains("private fun write8343(ts: TokenState, usd: Double)") && liq.contains("HydrationReeval8343.onHydrated8343(ts, usd)"))
        assertFalse("no bare tick write left in hydrate8037", liq.substring(liq.indexOf("fun hydrate8037("), liq.indexOf("private fun curveDepthFromCap8164")).contains("ts.lastLiquidityUsd = "))
        assertTrue(src("engine/truth/CurveReserves7951.kt").contains("HydrationReeval8343.onCurveRead8343(m, real, atMs)"))
        assertTrue(src("engine/truth/OnChainDepth8038.kt").contains("HydrationReeval8343.onDepthRead8343(mint, depthUsd8038(read.quoteSol, solUsd()), read.atMs)"))
        assertTrue("a hydrated deferral rides the next cycle's priority window", src("engine/HandoffThroughput8190.kt").contains("+ hydrated8343"))
        assertTrue(src("engine/HydrationReeval8343.kt").contains("HandoffReceiverTasks8153.onHydrated8343(d.mint, nowMs)"))
        assertFalse("FinalDecisionGate.evaluate is untouched", src("engine/FinalDecisionGate.kt").contains("8343"))
    }
}
