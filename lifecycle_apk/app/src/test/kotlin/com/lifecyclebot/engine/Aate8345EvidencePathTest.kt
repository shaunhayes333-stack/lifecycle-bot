package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.EvidenceShadow8344
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
 * V5.0.8345 — the evidence-recovery path, answering the 5.0.8344 audit (9 min LIVE: 288 hold refusals -> 7 shadow opens,
 * 136 hand-offs / 0 resumed after hydration, exceptions granted 0 in 431 min, runner grab detected 8 admitted 0).
 *  1. every hold refusal is accounted against the shadow book (opened, or skipped by name); the grab lane's hold-only
 *     refusal opens a shadow on its host lane; the hold line projects closesPerHour / etaToBarMin per lane;
 *  2. every tick-liquidity write site is a hydration (resumes the deferred hand-off task), the TokenState install site
 *     resumes a DEFERRED_NO_TOKEN_STATE task, and a found-but-not-resumed task says why;
 *  3. the exception door opens on the grab lane's own forming-run legs (a)+(c); venue, lane-open and the rate bounds stand.
 */
class Aate8345EvidencePathTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun fresh() {
        EvidenceShadow8344.resetForTests8345(); EvidenceHold8336.resetForTests8337()
        HandoffReceiverTasks8153.resetForTests8153(); HydrationReeval8343.resetForTests8343()
    }

    @After fun back() {
        EvidenceShadow8344.resetForTests8345(); EvidenceHold8336.resetForTests8337()
        HandoffReceiverTasks8153.resetForTests8153(); HydrationReeval8343.resetForTests8343()
    }

    // ── 1. shadow throughput: the hold -> shadow gap is explained ──

    @Test fun everyHoldRefusalIsOpenedOrSkippedByName() {
        val t0 = 1_700_000_000_000L
        EvidenceShadow8344.noteHoldRefusal8345("MOONSHOT", legacyRefused = true, hardSafetyBinds = false)
        EvidenceShadow8344.noteHoldRefusal8345("MOONSHOT", legacyRefused = true, hardSafetyBinds = false)
        EvidenceShadow8344.noteHoldRefusal8345("MOONSHOT", legacyRefused = false, hardSafetyBinds = true)
        EvidenceShadow8344.noteHoldRefusal8345("SHITCOIN", legacyRefused = false, hardSafetyBinds = false)
        // the decision admitted but the book has nothing to price it on
        val noPrice = TokenState(mint = "mint8345-noprice", symbol = "NP")
        EvidenceShadow8344.open8344(noPrice, "SHITCOIN", t0)
        val stale = TokenState(mint = "mint8345-stale", symbol = "ST").also { it.lastPrice = 0.001; it.lastPriceUpdate = t0 - 10 * 60_000L }
        EvidenceShadow8344.open8344(stale, "SHITCOIN", t0)
        val before = EvidenceShadow8344.gapCounts8345()
        assertEquals(3L, before.first["MOONSHOT"]); assertEquals(1L, before.first["SHITCOIN"])
        assertEquals(2L, before.second["MOONSHOT:${EvidenceShadow8344.SKIP_LEGACY_REFUSED_8345}"])
        assertEquals(1L, before.second["MOONSHOT:${EvidenceShadow8344.SKIP_HARD_SAFETY_8345}"])
        assertEquals(1L, before.second["SHITCOIN:${EvidenceShadow8344.SKIP_NO_PRICE_8345}"])
        assertEquals(1L, before.second["SHITCOIN:${EvidenceShadow8344.SKIP_STALE_PRICE_8345}"])
        assertTrue(before.third.isEmpty())
        // a fresh price opens; the same mint again is ALREADY_OPEN
        val freshTs = TokenState(mint = "mint8345-fresh", symbol = "FR").also { it.lastPrice = 0.002; it.lastPriceUpdate = t0 - 5_000L }
        EvidenceShadow8344.open8344(freshTs, "SHITCOIN", t0)
        EvidenceShadow8344.open8344(freshTs, "SHITCOIN", t0 + 1_000L)
        val after = EvidenceShadow8344.gapCounts8345()
        assertEquals(1L, after.third[EvidenceShadow8344.SOURCE_DECISION_8345])
        assertEquals(1L, after.second["SHITCOIN:${EvidenceShadow8344.SKIP_ALREADY_OPEN_8345}"])
        val line = EvidenceShadow8344.statusLine8344(t0 - 60_000L, t0 + 2_000L)
        assertTrue(line, line.contains("holdRefused{MOONSHOT=3,SHITCOIN=1}") && line.contains("openedBy{DECISION=1}") &&
            line.contains("skipped{MOONSHOT:LEGACY_8114_REFUSED=2,") && line.contains("recovery8345{"))
        // the decision accounts the refusal before the open
        val mode = src("engine/truth/Entry8114Mode8326.kt")
        assertTrue(mode.indexOf("EvidenceShadow8344.noteHoldRefusal8345(l, old.reason != null, now != null && hardSafety8326(now))") <
            mode.indexOf("EvidenceShadow8344.open8344(ts, l, nowMs)"))
    }

    @Test fun theEntryMarkFallsBackToTheTapeMarkTheTickReads() {
        val t0 = 1_700_000_000_000L
        assertEquals(0.5 to false, EvidenceShadow8344.entryMark8345(0.5, t0 - 10_000L, t0, 0.7))
        assertEquals(0.7 to true, EvidenceShadow8344.entryMark8345(0.5, t0 - 60_000L, t0, 0.7))
        assertEquals(0.7 to true, EvidenceShadow8344.entryMark8345(0.0, 0L, t0, 0.7))
        assertNull("a stale tick and no tape mark is not an entry", EvidenceShadow8344.entryMark8345(0.5, t0 - 60_000L, t0, null))
        assertNull(EvidenceShadow8344.entryMark8345(0.5, t0 - 60_000L, t0, 0.0))
        assertNull(EvidenceShadow8344.entryMark8345(Double.NaN, t0, t0, Double.NaN))
        val shadow = src("engine/truth/EvidenceShadow8344.kt")
        assertTrue("open reads the mark the way the tick does", shadow.contains("ForwardReturnLabeler7731.markFor7948(mint, { null }, nowMs)") &&
            shadow.contains("entryMark8345(ts.lastPrice, ts.lastPriceUpdate, nowMs, tape)"))
        assertEquals("the tick's own freshness bar", 45_000L, EvidenceShadow8344.PRICE_FRESH_MS_8345)
        assertTrue("a tape-mark open is counted", shadow.contains("\"EVIDENCE_SHADOW_\${OPEN_FROM_TAPE_MARK_8345}_8345\"") && EvidenceShadow8344.OPEN_FROM_TAPE_MARK_8345 == "OPENED_ON_TAPE_MARK")
        // every skip in open8344 is named
        for (k in listOf(EvidenceShadow8344.SKIP_ALREADY_OPEN_8345, EvidenceShadow8344.SKIP_REOPEN_GAP_8345, EvidenceShadow8344.SKIP_FULL_8345,
            EvidenceShadow8344.SKIP_NO_PRICE_8345, EvidenceShadow8344.SKIP_STALE_PRICE_8345)) {
            val c = shadow.indexOf("= \"$k\"")
            assertTrue(k, c > 0 && shadow.indexOf("skip8345(lane, SKIP_", c) > 0)
        }
        assertTrue(shadow.contains("skip8345(lane, SKIP_REOPEN_GAP_8345)") && shadow.contains("skip8345(lane, SKIP_FULL_8345)"))
    }

    @Test fun theGrabLanesHoldOnlyRefusalOpensAShadowOnTheHostLane() {
        val t0 = 1_700_000_000_000L
        val ts = TokenState(mint = "mint8345-grab", symbol = "GR").also { it.lastPrice = 0.003; it.lastPriceUpdate = t0 - 1_000L }
        EvidenceShadow8344.open8344(ts, "MOONSHOT", t0, EvidenceShadow8344.SOURCE_GRAB_8345)
        assertEquals(1L, EvidenceShadow8344.gapCounts8345().third[EvidenceShadow8344.SOURCE_GRAB_8345])
        assertTrue(EvidenceShadow8344.statusLine8344(t0 - 1L, t0).contains("open=1/120 (MOONSHOT=1)"))
        val lane = src("engine/RunnerGrabLane8343.kt")
        assertTrue("the hold is asked with the grab lane's own legs", lane.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, grabLegs8345 = grabLegs8345(evidence, venue))"))
        assertTrue("refused by the hold alone -> the host lane's shadow", lane.contains("if (hold != null && hold.startsWith(\"EVIDENCE_HOLD_8336_\"))") &&
            lane.contains("EvidenceShadow8344.open8344(ts, l, nowMs, com.lifecyclebot.engine.truth.EvidenceShadow8344.SOURCE_GRAB_8345)"))
        assertEquals("GRAB_7967 venue=POOL_EXIT_FLOOR_500 safety=FULL economics=1.5x chase=CAP_LAUNCH", RunnerGrabLane8343.grabLegs8345("GRAB_7967", "POOL_EXIT_FLOOR_500"))
        assertTrue(RunnerGrabLane8343.grabLegs8345("FORMING_8260", null)!!.contains("venue=-"))
        assertNull(RunnerGrabLane8343.grabLegs8345(null, "x")); assertNull(RunnerGrabLane8343.grabLegs8345("", "x"))
    }

    @Test fun theRecoveryProjectionIsBounded() {
        val t0 = 1_700_000_000_000L
        assertEquals(3.0, EvidenceShadow8344.closesPerHour8345(6, 2 * 3_600_000L), 1e-9)
        assertEquals(0.0, EvidenceShadow8344.closesPerHour8345(0, 3_600_000L), 1e-9)
        assertEquals(0.0, EvidenceShadow8344.closesPerHour8345(5, 0L), 1e-9)
        assertEquals(480L, EvidenceShadow8344.etaToBarMin8345(6, 3.0))
        assertEquals(0L, EvidenceShadow8344.etaToBarMin8345(30, 0.0))
        assertNull("a zero rate has no eta", EvidenceShadow8344.etaToBarMin8345(6, 0.0))
        assertNull(EvidenceShadow8344.etaToBarMin8345(6, Double.NaN))
        EvidenceShadow8344.noteCloseForTests8345("MOONSHOT", 4.0, t0 + 10 * 60_000L)
        EvidenceShadow8344.noteCloseForTests8345("MOONSHOT", -3.0, t0 + 20 * 60_000L)
        EvidenceShadow8344.noteCloseForTests8345("MOONSHOT", 2.0, t0 + 30 * 60_000L)
        EvidenceShadow8344.noteHoldRefusal8345("EXPRESS", false, false)
        val line = EvidenceShadow8344.recoveryLine8345(t0, t0 + 3_600_000L)
        assertTrue(line, line.contains("MOONSHOT n3/30 closesPerHour=3.00 etaToBarMin=540") && line.contains("EXPRESS n0/30 closesPerHour=0.00 etaToBarMin=-"))
        EvidenceShadow8344.resetForTests8345()
        assertEquals(" recovery8345{-}", EvidenceShadow8344.recoveryLine8345(0L, t0))
        assertTrue("the hold line carries the projection", src("engine/truth/EvidenceHold8336.kt").contains("EvidenceShadow8344.recoveryLine8345(startMs8336, nowMs)"))
        assertTrue(EvidenceHold8336.statusLine8336(4_000_000_000L).contains("grabDoor8345=0"))
    }

    // ── 2. hand-off resume after hydration ──

    @Test fun aLiquidityReadingAnswersTheDeferralsItCanAnswer() {
        val t0 = 30_000_000L
        val base = HandoffReceiverTasks8153.Task8153("m", "MOONSHOT", "PROJECT_SNIPER", "r", 1L, t0, t0 + 30_000L, t0)
        for (b in listOf("DEFERRED:DEFERRED_NATIVE:NATIVE_LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", "DEFERRED:DEFERRED_NATIVE:DATA_INCOMPLETE",
            "DEFERRED:DEFERRED_GATE:PRE_FDG_GATE:X:UNKNOWN_ECONOMICS", "DEFERRED:LANE_LIQ_BELOW_FLOOR_WAIT", "DEFERRED:DEFERRED_RECEIVER_DATA_MISSING"))
            assertTrue(b, HandoffReceiverTasks8153.awaitsHydration8343(base.copy(lastBlock = b)))
        for (b in listOf("DEFERRED:DEFERRED_NO_TOKEN_STATE", "DEFERRED:DEFERRED_NATIVE:PRICE_STALE", "DEFERRED:DEFERRED_NATIVE:METADATA_HYDRATING",
            "DEFERRED:TOO_FEW_BARS", "", "SLOT_HELD_BY_OTHER"))
            assertFalse(b, HandoffReceiverTasks8153.awaitsHydration8343(base.copy(lastBlock = b)))
        assertFalse("a settled task awaits nothing", HandoffReceiverTasks8153.awaitsHydration8343(base.copy(lastBlock = "DEFERRED:DATA_INCOMPLETE", outcome = HandoffReceiverTasks8153.Outcome8153.REFUSED)))
        assertTrue(HandoffReceiverTasks8153.hydrationAnswers8345("deferred_native:data incomplete"))
        assertFalse(HandoffReceiverTasks8153.hydrationAnswers8345("DEFERRED:DEFERRED_NO_TOKEN_STATE liq:UNKNOWN"))
        assertEquals("BLOCK_DEFERRED_NATIVE:PRICE_STALE", HandoffReceiverTasks8153.blockKey8345("DEFERRED:DEFERRED_NATIVE:PRICE_STALE_8190"))
        assertEquals("NOT_BLOCKED", HandoffReceiverTasks8153.blockKey8345(""))
    }

    @Test fun aTickLiquidityWriteResumesTheDeferredTaskAndSaysWhyNot() {
        val t0 = 40_000_000L
        val m = "L".repeat(44)
        HandoffReceiverTasks8153.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", "RUN_TAPE_8293", 7L, t0, t0)!!
        HandoffReceiverTasks8153.blockTask8153(m, "MOONSHOT", Block8153.DATA, "DEFERRED_NATIVE:NATIVE_LANE_LIQ_UNHYDRATED_DEFER_8140_MOONSHOT", t0 + 1_000L)
        assertTrue(HandoffReceiverTasks8153.awaitsHydration8343(HandoffReceiverTasks8153.taskOf8153(m, t0 + 2_000L)!!))
        // the write that fills the tick (no native deferral remembered: the task is still asked directly)
        val ts = TokenState(mint = m, symbol = "LQ")
        assertTrue(HydrationReeval8343.writeTick8345(ts, 650.0, "INTAKE", t0 + 5_000L))
        assertEquals(650.0, ts.lastLiquidityUsd, 1e-9)
        val r = HandoffReceiverTasks8153.taskOf8153(m, t0 + 5_001L)!!
        assertTrue(r.pending); assertEquals("", r.lastBlock); assertEquals(7L, r.version)
        assertEquals(t0 + 5_000L + HandoffReceiverTasks8153.TASK_TTL_MS_8153, r.validUntilMs)
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("hydrationResumed8343=1"))
        assertEquals(1L, HydrationReeval8343.hydrationCounts8345().first["INTAKE"])
        // a later write to an already-read tick is not a hydration
        assertTrue(HydrationReeval8343.writeTick8345(ts, 700.0, "DEX_PAIR_POLL", t0 + 6_000L))
        assertEquals(1L, HydrationReeval8343.hydrationCounts8345().first.values.sum())
        assertFalse("a lower intake figure does not write (max-take)", HydrationReeval8343.intakeLiquidity8345(ts, 100.0))
        assertEquals(700.0, ts.lastLiquidityUsd, 1e-9)
        // a 0 write hydrates nothing
        val zero = TokenState(mint = "Z".repeat(44), symbol = "Z0")
        assertFalse(HydrationReeval8343.writeTick8345(zero, 0.0, "DEXSCREENER_WS", t0))
        assertFalse(HydrationReeval8343.firstFill8345(0.0, Double.NaN)); assertTrue(HydrationReeval8343.firstFill8345(0.0, 1.0)); assertFalse(HydrationReeval8343.firstFill8345(5.0, 9.0))
        // the curve-keep rule still holds the last positive figure on a zero poll
        HydrationReeval8343.pollLiquidity8345(ts, 0.0, curveKeep7323 = true)
        assertEquals(700.0, ts.lastLiquidityUsd, 1e-9)
        HydrationReeval8343.pollLiquidity8345(ts, 0.0, curveKeep7323 = false)
        assertEquals(0.0, ts.lastLiquidityUsd, 1e-9)
        // a task deferred for something liquidity cannot answer is found and NOT resumed, by name
        val p = "P".repeat(44)
        HandoffReceiverTasks8153.openTask8153(p, "SHITCOIN", "PROJECT_SNIPER", "RUN_TAPE_8293", 8L, t0, t0)!!
        HandoffReceiverTasks8153.blockTask8153(p, "SHITCOIN", Block8153.DATA, "DEFERRED_NATIVE:PRICE_STALE", t0 + 1_000L)
        assertFalse(HandoffReceiverTasks8153.onHydrated8343(p, t0 + 2_000L))
        assertEquals(1L, HandoffReceiverTasks8153.whyNotResumedCounts8345()["BLOCK_DEFERRED_NATIVE:PRICE_STALE"])
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("whyNotResumed8345{BLOCK_DEFERRED_NATIVE:PRICE_STALE=1}"))
        assertFalse("no task, nothing counted", HandoffReceiverTasks8153.onHydrated8343("N".repeat(44), t0))
        assertEquals(1L, HandoffReceiverTasks8153.whyNotResumedCounts8345().values.sum())
        assertTrue(HydrationReeval8343.statusLine8343(t0).contains("tickWrites8345{INTAKE=1}"))
    }

    @Test fun theInstalledStateResumesTheTaskAtTheInstallSiteAndInThePool() {
        val t0 = 50_000_000L
        val m = "S".repeat(44)
        HandoffReceiverTasks8153.openTask8153(m, "QUALITY", "MOONSHOT", "MCAP_TOO_LOW", 3L, t0, t0)!!
        HandoffReceiverTasks8153.blockTask8153(m, "QUALITY", Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", t0 + 1_000L)
        // the mint is ALREADY in the pool (intake installed it last cycle): 8330 skipped it, 8345 resumes it
        val pool = HandoffPriority8190.poolWithLiveTargets8216(listOf(m to t0 + 40_000L), listOf(m, "other"), t0 + 3_000L, tokenFor = { true }, rehydrate = { })
        assertEquals(setOf(m, "other"), pool.toSet())
        val r = HandoffReceiverTasks8153.taskOf8153(m, t0 + 3_001L)!!
        assertTrue(r.pending); assertEquals("", r.lastBlock)
        assertTrue(HandoffReceiverTasks8153.resumeLine8330().contains("handoffResumed8330=1 "))
        // a pool member WITHOUT a state is left alone (no false resume)
        HandoffReceiverTasks8153.blockTask8153(m, "QUALITY", Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", t0 + 4_000L)
        HandoffPriority8190.poolWithLiveTargets8216(listOf(m to t0 + 40_000L), listOf(m), t0 + 5_000L, tokenFor = { false }, rehydrate = { })
        assertTrue(HandoffReceiverTasks8153.awaitsState8330(HandoffReceiverTasks8153.taskOf8153(m, t0 + 5_001L)!!))
        // the install site itself
        assertTrue(src("engine/IntakeFirstSight8317.kt").contains("HandoffReceiverTasks8153.onStateInstalled8330(fresh.mint)"))
        assertTrue(src("engine/BotService.kt").contains("IntakeFirstSight8317.firstSight8317(fresh, laneAffinity, toolAffinity)"))
        assertTrue(src("engine/HandoffThroughput8190.kt").contains("if (m in set) { if (tokenFor(m)) try { HandoffReceiverTasks8153.onStateInstalled8330(m, nowMs) } catch (_: Throwable) {}; continue }"))
    }

    @Test fun everyTickLiquidityWriteSiteIsAHydration() {
        val bot = src("engine/BotService.kt")
        assertTrue("intake, fresh state (max-take)", bot.contains("HydrationReeval8343.intakeLiquidity8345(ts, liquidityUsd)"))
        assertTrue("intake, existing state", bot.contains("if (HydrationReeval8343.intakeLiquidity8345(existing, liquidityUsd)) tokenEvidenceImproved7475 = true"))
        assertTrue("DexScreener pair poll", bot.contains("HydrationReeval8343.pollLiquidity8345(ts, pair.liquidity, curveKeep7323)"))
        assertFalse(bot.contains("ts.lastLiquidityUsd = pair.liquidity"))
        assertTrue("Birdeye overview fallback", bot.contains("HydrationReeval8343.writeTick8345(ts, ov.liquidity, \"BIRDEYE_OVERVIEW\")"))
        assertTrue("DexScreener WS frame", src("engine/DataOrchestrator.kt").contains("HydrationReeval8343.writeTick8345(ts, liquidity, \"DEXSCREENER_WS\")"))
        assertTrue("Raydium TVL fill", src("engine/LaneAgeLiquidityTruth8140.kt").contains("HydrationReeval8343.writeTick8345(ts, tvl, \"RAYDIUM_TVL_8128\")"))
        assertTrue("verified chain depth", src("engine/truth/OnChainDepth8038.kt").contains("HydrationReeval8343.writeTick8345(ts, chainUsd, \"CHAIN_VERIFIED_8038\")"))
        val h = src("engine/HydrationReeval8343.kt")
        assertTrue("one arrival path asks the task whether or not a native deferral was remembered",
            h.contains("if (!queuedNow && liquidityUsd.isFinite() && liquidityUsd > 0.0)") && h.contains("HandoffReceiverTasks8153.onHydrated8343(mint, nowMs)"))
        assertTrue("the 8343 hooks stay", src("engine/truth/LiquidityTruth8037.kt").contains("HydrationReeval8343.onHydrated8343(ts, usd)") &&
            src("engine/truth/CurveReserves7951.kt").contains("HydrationReeval8343.onCurveRead8343(m, real, atMs)") &&
            src("engine/truth/OnChainDepth8038.kt").contains("HydrationReeval8343.onDepthRead8343(mint, depthUsd8038(read.quoteSol, solUsd()), read.atMs)"))
        assertFalse("FinalDecisionGate.evaluate is untouched", src("engine/FinalDecisionGate.kt").contains("8345"))
    }

    // ── 3. the exception door via the grab lane ──

    @Test fun theGrabLanesLegsSatisfyLegsAAndCWithTheBoundsIntact() {
        val venue = EvidenceHold8336.VENUE_POOL_FLOOR_8337
        val legs = "FORMING_8260 venue=POOL_EXIT_FLOOR_500 safety=FULL economics=1.5x chase=CAP_LAUNCH"
        assertNull("no grab legs", EvidenceHold8336.exceptionalReason8345(null, venue, EvidenceHold8336.ODDS_GRAB_LANE_8345, true))
        assertNull(EvidenceHold8336.exceptionalReason8345("", venue, EvidenceHold8336.ODDS_GRAB_LANE_8345, true))
        assertNull("venue (b) still binds", EvidenceHold8336.exceptionalReason8345(legs, null, EvidenceHold8336.ODDS_GRAB_LANE_8345, true))
        assertNull("lane shut by its live losses (d) still binds", EvidenceHold8336.exceptionalReason8345(legs, venue, EvidenceHold8336.ODDS_GRAB_LANE_8345, false))
        val r = EvidenceHold8336.exceptionalReason8345(legs, venue, EvidenceHold8336.ODDS_GRAB_LANE_8345, true)
        assertNotNull(r)
        assertTrue(r!!, r.contains("grab=${EvidenceHold8336.GRAB_LANE_LEGS_8345}:FORMING_8260") && r.contains("venue=$venue") && r.contains("odds=GRAB_LANE_FORMING_RUN_8343") && r.contains("lane=OPEN"))
        assertEquals("GRAB_LANE_8343", EvidenceHold8336.GRAB_LANE_LEGS_8345)
        // the 8337 door is unchanged: no proven grab cell, no grant
        assertNull(EvidenceHold8336.exceptionalReason8337(false, venue, EvidenceHold8336.ODDS_GRAB_LANE_8345, true))
        // the bounds are the same numbers and the same code
        assertEquals(30 * 60_000L, EvidenceHold8336.LANE_GAP_MS_8337); assertEquals(3, EvidenceHold8336.MAX_PER_HOUR_8337)
        val hold = src("engine/truth/EvidenceHold8336.kt")
        val core = hold.indexOf("private fun exceptionalCore8345(")
        assertTrue(core > 0)
        assertTrue("one core for both doors", hold.contains("fun exceptional8336(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): String? = exceptionalCore8345(ts, lane, nowMs, null)"))
        assertTrue("the rate bound is read inside the core", hold.indexOf("if (!rateAllows8337(l, nowMs))", core) > core)
        assertTrue("venue leg (b) and lane leg (d) are read for both doors", hold.indexOf("val venue = venueLeg8337(ts, l, nowMs)", core) > core &&
            hold.indexOf("EntryEconomics8329.laneRealisedRefusal8329(l, nowMs)", core) > core)
        assertTrue("counted apart", hold.contains("\"EVIDENCE_HOLD_EXCEPTION_8345_GRAB_$" + "l\"") && hold.contains("\"EVIDENCE_HOLD_EXCEPTION_8337_$" + "l\""))
        assertTrue("the grab lane's hold call carries its legs", hold.contains("if (grabLegs8345 == null) exceptional8336(ts, l, nowMs) else exceptionalViaGrab8345(ts, l, grabLegs8345, nowMs)"))
        // runtime: without an exit-safe venue the grab door stays shut (nothing granted, nothing counted)
        val ts = TokenState(mint = "mint8345-novenue", symbol = "NV")
        assertNull(EvidenceHold8336.exceptionalViaGrab8345(ts, "MOONSHOT", legs, 4_000_000_000L))
        assertEquals(0L, EvidenceHold8336.exceptionCount8337()); assertEquals(0L, EvidenceHold8336.grabExceptionCount8345())
        assertNull("blank legs are no legs", EvidenceHold8336.exceptionalViaGrab8345(ts, "MOONSHOT", "", 4_000_000_000L))
    }
}
