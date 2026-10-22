package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Miss8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Trace8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.StrategyIdentity8153
import com.lifecyclebot.engine.truth.StrategyMove8153
import com.lifecyclebot.engine.truth.StrategyRank8153
import com.lifecyclebot.engine.truth.StrategyRank8153.Option8153
import com.lifecyclebot.engine.truth.StrategyRank8153.Tier8153
import com.lifecyclebot.engine.truth.StrategyWatch8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8153 — the best executable strategy per token with one identity end to end (ranked on each fired setup's own
 * record and timing band; the admission's choice is the identity sizing, seal and exit read), and false runner refusals
 * removed without admitting bad trades (too-slow judged on the selected strategy, fresh launches defer on sparse history,
 * unread liquidity is unknown, watch-first releases on candidate-specific evidence, the binding gate is traced).
 */
class Aate8153StrategyTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 1_900_000_000_000L

    private fun ev(id: String, n: Int, mean: Double, se: Double, run: Double = 0.0) =
        Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se, run)

    /** A clean, freshly priced candidate whose economics clear 2x cost (gross 30%, cost 6%). */
    private fun cand(selected: String?, setups: List<Evidence8136>, timings: Map<String, Evidence8136> = emptyMap()) = Candidate8136(
        mint = "STRAT8153pump", lane = "SHITCOIN", selectedSetup = selected, priceAgeMs = 5_000L,
        expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0, setups = setups,
        expectedGrossBySetup = setups.associate { it.id to 30.0 } + listOfNotNull(selected).associateWith { 30.0 },
        genericScore = 12.0, genericFloor = 30.0, setupTimings = timings,
    )

    // ── P1: every fired setup ranked on its own record ────────────────────────

    @Test fun negativeDefaultYieldsToAPositiveAlternative() {
        val def = Option8153("LAUNCH_CONTINUATION", 25, -23.6, 5.0)
        val alt = Option8153("ACCELERATING_TAPE", 61, 23.1, 6.0, runnerRate = 0.18)
        assertEquals(Tier8153.REJECTED, StrategyRank8153.tier8153(def, Double.NaN))
        assertEquals(Tier8153.SUPPORTED, StrategyRank8153.tier8153(alt, Double.NaN))
        val pick = StrategyRank8153.select8153("LAUNCH_CONTINUATION", listOf(def, alt), Double.NaN)
        assertEquals("ACCELERATING_TAPE", pick.id)
        assertEquals(Tier8153.SUPPORTED, pick.tier)
        assertTrue(pick.overDefault)
        assertEquals(listOf("LAUNCH_CONTINUATION:LOSING"), pick.rejected)
        assertTrue(pick.basis, pick.basis.startsWith("SUPPORTED:ACCELERATING_TAPE n61"))
        // The admission record, given the ranked strategy, admits it on its own record (the proven loser rejects nothing).
        val d = CandidateAdmission8136.decide8136(cand(pick.id, listOf(ev("LAUNCH_CONTINUATION", 25, -23.6, 5.0), ev("ACCELERATING_TAPE", 61, 23.1, 6.0, 0.18))))
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertEquals(pick.id, d.chosenSetup)
        assertEquals("SETUP:ACCELERATING_TAPE n61 +23.1±+6.0", d.basis)
        // A cold-start-valid alternative (n20, 10 ± 3: 10 - 4.5 > 0) beats an unproven default (n5).
        val valid = StrategyRank8153.select8153("PRE_IGNITION_BASE",
            listOf(Option8153("PRE_IGNITION_BASE", 5, 2.0, 4.0), Option8153("RS_LEADER", 20, 10.0, 3.0)), Double.NaN)
        assertEquals("RS_LEADER", valid.id)
        assertEquals(Tier8153.VALID, valid.tier)
        // With no evidenced alternative the losing default stands — and so does its refusal.
        val stands = StrategyRank8153.select8153("LAUNCH_CONTINUATION", listOf(def, Option8153("THIN_SETUP", 8, 5.0, 3.0)), Double.NaN)
        assertEquals("LAUNCH_CONTINUATION", stands.id)
        assertEquals("DEFAULT_REJECTED", stands.basis)
        assertEquals("SETUP_RECORD_LOSING_8136",
            CandidateAdmission8136.decide8136(cand("LAUNCH_CONTINUATION", listOf(ev("LAUNCH_CONTINUATION", 25, -23.6, 5.0)))).reason)
    }

    @Test fun positiveDefaultIsKeptOverANegativeAlternative() {
        val def = Option8153("PRE_IGNITION_BASE", 40, 9.0, 3.0)
        val alt = Option8153("LAUNCH_CONTINUATION", 30, -8.0, 2.0)
        val pick = StrategyRank8153.select8153("PRE_IGNITION_BASE", listOf(def, alt), Double.NaN)
        assertEquals("PRE_IGNITION_BASE", pick.id)
        assertFalse(pick.overDefault)
        assertEquals(listOf("LAUNCH_CONTINUATION:LOSING"), pick.rejected)
        val d = CandidateAdmission8136.decide8136(cand("PRE_IGNITION_BASE", listOf(ev("PRE_IGNITION_BASE", 40, 9.0, 3.0), ev("LAUNCH_CONTINUATION", 30, -8.0, 2.0))))
        assertEquals(Terminal8136.ADMIT, d.terminal)
        assertEquals("PRE_IGNITION_BASE", d.chosenSetup)
        // NO_TRIGGER is never a ranked strategy.
        assertTrue(StrategyRank8153.rank8153(listOf(Option8153("NO_TRIGGER", 500, 3.0, 0.5)), Double.NaN).isEmpty())
    }

    @Test fun aSetupLosingAtThisTimingBandIsNotTheStrategy() {
        val a = Option8153("A_SETUP", 40, 9.0, 3.0, timingN = 15, timingMeanPct = -6.0, timingSePct = 2.0)
        val b = Option8153("B_SETUP", 35, 5.0, 2.0)
        val pick = StrategyRank8153.select8153("A_SETUP", listOf(a, b), Double.NaN)
        assertEquals("B_SETUP", pick.id)
        assertEquals(listOf("A_SETUP:TIMING_LOSING"), pick.rejected)
        // A measured band caps the conservative net: A (lower +6, band lower +1) ranks under B (lower +4).
        val a2 = Option8153("A_SETUP", 40, 9.0, 3.0, timingN = 20, timingMeanPct = 2.0, timingSePct = 1.0)
        val b2 = Option8153("B_SETUP", 40, 5.0, 1.0)
        assertEquals(1.0, StrategyRank8153.score8153(a2, Double.NaN), 1e-9)
        assertEquals("B_SETUP", StrategyRank8153.rank8153(listOf(a2, b2), Double.NaN).first().option.id)
        // Gross (not net-of-cost) evidence must clear the round trip itself.
        assertEquals(Tier8153.UNPROVEN, StrategyRank8153.tier8153(Option8153("G", 40, 7.0, 2.0, netOfCost = false), 6.0))
        assertEquals(Tier8153.UNPROVEN, StrategyRank8153.tier8153(Option8153("G", 40, 7.0, 2.0, netOfCost = false), Double.NaN))
        // The admission record ranks the same way when it carries each setup's band, and agrees with the pick.
        val setups = listOf(ev("A_SETUP", 40, 9.0, 3.0), ev("B_SETUP", 35, 5.0, 2.0))
        val band = mapOf("A_SETUP" to Evidence8136(CandidateAdmission8136.KIND_TIMING, "A_SETUP@PEAK", 15, -6.0, 2.0))
        val ranked = CandidateAdmission8136.decide8136(cand("B_SETUP", setups, band))
        assertEquals(Terminal8136.ADMIT, ranked.terminal)
        assertEquals(pick.id, ranked.chosenSetup)
        // The classifier default with its adverse band and no per-setup bands (the pre-8153 input) was refused outright.
        val old = CandidateAdmission8136.decide8136(cand("A_SETUP", setups).copy(timing = band.getValue("A_SETUP")))
        assertEquals("TIMING_ADVERSE_8136", old.reason)
        val fixed = CandidateAdmission8136.decide8136(cand("A_SETUP", setups, band).copy(timing = band.getValue("A_SETUP")))
        assertEquals("B_SETUP", fixed.chosenSetup)
        assertEquals(Terminal8136.ADMIT, fixed.terminal)
    }

    @Test fun supportedAlternativeCannotBorrowThinDefaultsEconomics() {
        val c = cand("A_SETUP", listOf(ev("A_SETUP", 2, 3.0, 2.0), ev("B_SETUP", 40, 10.0, 2.0)))
            .copy(expectedGrossPct = 30.0, expectedGrossBySetup = mapOf("B_SETUP" to 8.0))
        val refused = CandidateAdmission8136.decide8136(c)
        assertEquals("B_SETUP", refused.chosenSetup)
        assertEquals("EXECUTION_ECONOMICS_8136", refused.reason)
        assertEquals(2.0, refused.expectedNetPct, 1e-9)
        assertEquals(Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = emptyMap())).terminal)
        val admitted = CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = mapOf("B_SETUP" to 30.0)))
        assertEquals(Terminal8136.ADMIT, admitted.terminal)
        assertEquals("B_SETUP", admitted.chosenSetup)
        assertEquals(24.0, admitted.expectedNetPct, 1e-9)
    }

    @Test fun alternativeSetupMustClearEconomicsOnItsOwnExecutableMove() {
        val c = cand("A_SETUP", listOf(ev("A_SETUP", 40, -12.0, 2.0), ev("B_SETUP", 40, 10.0, 2.0)))
            .copy(expectedGrossPct = 30.0, expectedGrossBySetup = mapOf("A_SETUP" to 30.0, "B_SETUP" to 8.0))
        val d = CandidateAdmission8136.decide8136(c)
        assertEquals("B_SETUP", d.chosenSetup)
        assertEquals("EXECUTION_ECONOMICS_8136", d.reason)
        assertEquals(2.0, d.expectedNetPct, 1e-9)
        assertTrue(d.trace.any { it == "economicsSetup=B_SETUP gross=+8.0 cost=+6.0 net=+2.0" })
        val unmeasuredAlternative = CandidateAdmission8136.decide8136(c.copy(expectedGrossBySetup = emptyMap()))
        assertEquals(Terminal8136.DEFER_DATA, unmeasuredAlternative.terminal)
        assertTrue(CandidateAdmission8136.missingOf8144(unmeasuredAlternative).contains("economics"))
    }

    @Test fun missingDataDefersInsteadOfRefusing() {
        val d = CandidateAdmission8136.decide8136(cand(null, emptyList()).copy(missingInputs = listOf("liquidity:UNKNOWN")))
        assertEquals(Terminal8136.DEFER_DATA, d.terminal)
        assertEquals(listOf("liquidity:UNKNOWN"), CandidateAdmission8136.missingOf8144(d))
        assertNull(CandidateAdmission8136.floorVerdict8136(d))
        // An unknown expected move (a fresh launch's sparse history) defers on economics.
        val econ = CandidateAdmission8136.decide8136(cand(null, emptyList()).copy(expectedGrossPct = Double.NaN))
        assertEquals(Terminal8136.DEFER_DATA, econ.terminal)
        assertTrue(CandidateAdmission8136.missingOf8144(econ).contains("economics"))
    }

    @Test fun aStrategySwitchKeepsOneIdentityThroughAdmissionSizingSealAndExit() {
        val lane = "SHITCOIN"
        val mint = "IDENT8153pump"
        val fired = listOf("LAUNCH_CONTINUATION", "PRE_IGNITION_BASE")
        // admission: the default is losing, the alternative is supported -> the alternative is chosen
        val d = CandidateAdmission8136.decide8136(cand("PRE_IGNITION_BASE",
            listOf(ev("LAUNCH_CONTINUATION", 25, -23.6, 5.0), ev("PRE_IGNITION_BASE", 40, 9.0, 3.0))))
        assertEquals("PRE_IGNITION_BASE", d.chosenSetup)
        // identity: held for every later reader of selectedSetup8094 while fresh and still fired
        StrategyIdentity8153.record8153(mint, lane, d.chosenSetup, d.basis, t0)
        assertEquals("PRE_IGNITION_BASE", StrategyIdentity8153.heldFor8153(mint, "shitcoin", t0 + 30_000L, fired))
        assertNull(StrategyIdentity8153.heldFor8153(mint, lane, t0 + StrategyIdentity8153.TTL_MS_8153 + 1L, fired))
        assertNull(StrategyIdentity8153.heldFor8153(mint, lane, t0 + 1_000L, listOf("LAUNCH_CONTINUATION")))
        assertTrue(StrategyIdentity8153.valid8153(StrategyIdentity8153.Held8153("PRE_IGNITION_BASE", "b", t0), t0, fired))
        assertFalse(StrategyIdentity8153.valid8153(StrategyIdentity8153.Held8153("PRE_IGNITION_BASE", "b", t0 + 5_000L), t0, fired))   // clock moved back
        assertFalse(StrategyIdentity8153.valid8153(null, t0, fired))
        assertTrue(StrategyIdentity8153.basisFor8153(mint, lane, t0 + 1_000L).startsWith("PRE_IGNITION_BASE(SETUP:PRE_IGNITION_BASE"))
        val held = StrategyIdentity8153.heldFor8153(mint, lane, t0 + 1_000L, fired)
        // seal: the entry tag (StrategyHypothesisEngine -> AateEntryIdentity8136 / EntryStrategySnapshot6450) leads with it
        val c = LanePlaybook7907.decide8084(lane, fired, emptyList(), "LAUNCH_CONTINUATION", emptyList(), emptyList(), 42, t0)
        val tag = LanePlaybook7907.primaryTag8153(c, held)
        assertEquals("PRE_IGNITION_BASE;LAUNCH_CONTINUATION#O=MATCHED#V=42", tag)
        // the realised close files under the same primary (LiveTruth8064) and the label grades the same decision
        assertEquals("PRE_IGNITION_BASE", tag!!.substringBefore(';').substringBefore('#'))
        assertTrue(LanePlaybook7907.sameDecision8085(tag, tag))
        // the default is kept when it IS the strategy, or the identity did not fire
        val base = "LAUNCH_CONTINUATION;PRE_IGNITION_BASE#O=MATCHED#V=42"
        assertEquals(base, LanePlaybook7907.primaryTag8153(c, "LAUNCH_CONTINUATION"))
        assertEquals(base, LanePlaybook7907.primaryTag8153(c, "NEVER_FIRED"))
        assertEquals(base, LanePlaybook7907.primaryTag8153(c, null))
        // tactic -> entry / exit style follow the identity, not the default
        assertEquals(TacticSwitcher.Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049(lane, held))
        assertTrue(TacticSwitcher.seedTactic8049(lane, "LAUNCH_CONTINUATION") != TacticSwitcher.Tactic.REACCUMULATION)
        // wiring: every consumer reads the one identity
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        val sel = pb.substringAfter("fun selectedSetup8094(").substringBefore("fun validAlternative8144(")
        assertTrue(sel.indexOf("admittedSetup8153(ts, laneRaw, nowMs)") in 0 until sel.indexOf("rankedSetup8153(ts, laneRaw, nowMs)"))
        assertTrue(sel.indexOf("rankedSetup8153(ts, laneRaw, nowMs)") < sel.indexOf("provenSetup8056(ts, laneRaw, nowMs)"))
        assertTrue(pb.contains("primaryTag8153(c, try { selectedSetup8094(ts, c.lane, nowMs) } catch (_: Throwable) { null })"))
        // superseded by V5.0.8326 — outside Entry8114Mode8326's legacy scope the strategy the coin trades as is still strategyFor8153
        assertTrue(pb.contains("c8084.tag else strategyFor8153(ts, lane, c8084, nowMs)) ?: return null"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("StrategyIdentity8153.record8153(mint, lane, d.chosenSetup, d.basis, nowMs,"))
        assertTrue(src("engine/learning/TacticSwitcher.kt").contains("LanePlaybook7907.selectedSetup8094(ts, lane)"))
        assertTrue(src("engine/AgenticStyleRouter.kt").contains("LanePlaybook7907.selectedSetup8094(ts, laneHint)"))
        assertTrue(src("engine/ExitProfile7955.kt").contains("LanePlaybook7907.selectedSetup8094(ts, canon(lane), nowMs)"))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("strategy = (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, System.currentTimeMillis())"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.indexOf("CandidateAdmission8136.assess8136(ts, l8059, nowMs, candidateVersion)") in 0 until gate.indexOf("val setup8083 = admittedSetup(admission8136)"))
    }

    // ── P2: false runner refusals ─────────────────────────────────────────────

    @Test fun unknownLiquidityIsNeverLow() {
        assertEquals("UNKNOWN", StrategyMove8153.liquidityRead8153(0.0))
        assertEquals("UNKNOWN", StrategyMove8153.liquidityRead8153(Double.NaN))
        assertEquals("UNKNOWN", StrategyMove8153.liquidityRead8153(-1.0))
        assertEquals("KNOWN", StrategyMove8153.liquidityRead8153(4_500.0))
        // A launch probe on unread depth is costed without impact; the pre-8153 $10 probe charged the 20% maximum.
        assertEquals(0.0, StrategyMove8153.probeSizeUsd8153(0.0), 0.0)
        assertEquals(10.0, StrategyMove8153.probeSizeUsd8153(8_000.0), 0.0)
        assertEquals(0.0, FieldManual7715.impactRoundTripPct(StrategyMove8153.probeSizeUsd8153(0.0), 0.0), 0.0)
        assertEquals(FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715, FieldManual7715.impactRoundTripPct(10.0, 0.0), 0.0)
        // The decision-time evidence names an unread pool UNKNOWN, a read one by its value.
        assertEquals("lane=SHITCOIN liq=UNKNOWN setup=- tier=- move=-", AdmissionRunnerTrace8144.evidenceLine8153("SHITCOIN", 0.0, "", "", ""))
        assertTrue(AdmissionRunnerTrace8144.evidenceLine8153("SHITCOIN", 8_471.4, "", "", "").contains("liq=8471"))
        // A liquidity refusal on an unread pool replays as wrong even when the first read saw depth.
        val r = AdmissionRunnerTrace8144.classify8144(trace.copy(binding = "LOW_LIQ_7300",
            bindingEvidence = AdmissionRunnerTrace8144.evidenceLine8153("MOONSHOT", 0.0, "", "", "")))
        assertEquals(Miss8144.WRONGLY_REFUSED, r.miss)
        assertEquals("LIQ_UNREAD_REFUSED_AS_LOW:LOW_LIQ_7300", r.gate)
        assertTrue(src("engine/truth/FreshLaunchSelector7737.kt").contains("allInCostPct(StrategyMove8153.probeSizeUsd8153(ts.lastLiquidityUsd), ts.lastLiquidityUsd)"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("listOf(\"liquidity:UNKNOWN\")"))
    }

    @Test fun tooSlowJudgesTheSelectedStrategysMove() {
        // A supported strategy (n40, +12 ± 3 net) expects its own move (net + the round trip), not the coin's 4% hour.
        val strat = StrategyMove8153.expected8153(50.0, 4.0, true, null, 40, 12.0, 3.0, 6.0, false)
        assertEquals("STRATEGY", strat.basis)
        assertEquals(18.0, strat.expectedGrossPct, 1e-9)
        assertTrue(NetEdge8030.entryClears8030(strat.expectedGrossPct, 6.0))
        // Without a supported strategy the lane mandate is capped by the coin's dense range (BONK stays refused).
        val legacy = StrategyMove8153.expected8153(50.0, 4.0, true, null, 0, Double.NaN, Double.NaN, 6.0, false)
        assertEquals("RANGE", legacy.basis)
        assertEquals(4.0, legacy.expectedGrossPct, 1e-9)
        assertFalse(NetEdge8030.entryClears8030(legacy.expectedGrossPct, 6.0))
        assertEquals("RANGE", StrategyMove8153.expected8153(50.0, 4.0, true, null, 40, 2.0, 3.0, 6.0, false).basis)
        // A supported strategy whose own net is under half the round trip still does not clear 1.5x cost (8197).
        val small = StrategyMove8153.expected8153(50.0, 4.0, true, null, 40, 2.5, 1.0, 6.0, false)
        assertEquals(8.5, small.expectedGrossPct, 1e-9)
        assertFalse(NetEdge8030.entryClears8030(small.expectedGrossPct, 6.0))
        // The strategy's move is not capped by a launch's sparse range either.
        val launch = StrategyMove8153.expected8153(50.0, 2.0, false, null, 40, 12.0, 3.0, 6.0, true)
        assertEquals("STRATEGY", launch.basis)
        assertFalse(launch.deferForHistory)
        val ne = src("engine/truth/NetEdge8030.kt")
        assertTrue(ne.contains("val move = executableMove8153(ts, lane, mandate, cost)"))
        assertTrue(ne.contains("LanePlaybook7907.selectedRecord8153(ts, lane, setup, nowMs)"))
    }

    @Test fun aFreshLaunchWithSparseHistoryDefersInsteadOfBeingRefused() {
        assertFalse(StrategyMove8153.dense8153(2, 3L * 60_000L))
        assertFalse(StrategyMove8153.dense8153(10, 5L * 60_000L))
        assertTrue(StrategyMove8153.dense8153(6, 15L * 60_000L))
        assertTrue(StrategyMove8153.fresh8153(null))
        assertTrue(StrategyMove8153.fresh8153(30.0))
        assertFalse(StrategyMove8153.fresh8153(600.0))
        // Two candles of a fresh launch "moved 5%": that is not a measurement — defer for evidence.
        val sparse = StrategyMove8153.expected8153(50.0, 5.0, false, null, 0, Double.NaN, Double.NaN, 6.0, true)
        assertEquals("RANGE_SPARSE", sparse.basis)
        assertTrue(sparse.deferForHistory)
        assertTrue(StrategyMove8153.needsDefer8153(sparse, 6.0))
        // The deferral is a data gap (bounded by the admission's coalesced window), never a verdict on the coin.
        assertEquals(Terminal8136.DEFER_DATA, CandidateAdmission8136.terminalOf8136("DEFER", StrategyMove8153.DEFER_REASON_8153))
        // An older coin with sparse history keeps the old rule; a measured 1h change decides; a clearing move needs no wait.
        val old = StrategyMove8153.expected8153(50.0, 5.0, false, null, 0, Double.NaN, Double.NaN, 6.0, false)
        assertEquals("RANGE_SPARSE", old.basis)
        assertFalse(StrategyMove8153.needsDefer8153(old, 6.0))
        val h1 = StrategyMove8153.expected8153(50.0, 5.0, false, 30.0, 0, Double.NaN, Double.NaN, 6.0, true)
        assertEquals("H1", h1.basis)
        assertEquals(30.0, h1.expectedGrossPct, 1e-9)
        assertFalse(StrategyMove8153.needsDefer8153(StrategyMove8153.expected8153(50.0, 40.0, false, null, 0, Double.NaN, Double.NaN, 6.0, true), 6.0))
        assertTrue(src("engine/truth/NetEdge8030.kt").contains("CandidateAdmission8136.deferBudgetFor8144(ts.mint, listOf(StrategyMove8153.DEFER_FEATURE_8153))"))
        // The deferral asks for its evidence through the existing throttled re-fetch (candle seed, forced poll, tape).
        assertTrue(src("engine/truth/NetEdge8030.kt").contains("LanePlaybook7907.requestEvidence8153(ts, listOf(\"shape:HISTORY_SPARSE\", \"chg:UNOBSERVED\", \"liq:REFRESH\"))"))
        val defer = src("engine/truth/LiveEdgeGate7877.kt")
            .substringAfter("CandidateAdmission8136.Terminal8136.DEFER_DATA -> {").substringBefore("CandidateAdmission8136.Terminal8136.REFUSE -> {")
        assertTrue(defer.contains("val missing = CandidateAdmission8136.missingOf8144(d)"))
        assertTrue(defer.contains("LanePlaybook7907.requestEvidence8153(ts, missing.map { it.replace(\"liquidity:\", \"liq:\") }, nowMs)"))
        assertTrue(defer.contains("CandidateAdmission8136.deferBudgetFor8144(ts.mint, missing, nowMs)"))
    }

    @Test fun structurallyUnsafeIsStillRefusedAndWatchFirstReleasesOnlyThinCells() {
        val good = cand("PRE_IGNITION_BASE", listOf(ev("PRE_IGNITION_BASE", 200, 30.0, 2.0, 0.5)))
        assertEquals("STRUCTURAL_RISK_8136", CandidateAdmission8136.decide8136(good.copy(structuralRisk = "DEV_SELLING")).reason)
        assertEquals("SAFETY_REFUSED_8136", CandidateAdmission8136.decide8136(good.copy(safetyRefusal = "HARD_BLOCK:FREEZE_AUTHORITY")).reason)
        assertEquals("EXECUTION_ECONOMICS_8136", CandidateAdmission8136.decide8136(good.copy(expectedGrossPct = 8.0)).reason)
        val w = StrategyWatch8153
        assertNull(w.release8153("WATCH_FIRST_8019_BAND_NEGATIVE", Tier8153.SUPPORTED, true, true, true, true))
        assertNull(w.release8153("WATCH_FIRST_7994_CELL_NEGATIVE", Tier8153.SUPPORTED, true, true, true, true))
        assertEquals("STRATEGY_VALID", w.release8153(w.THIN_CELL_8153, Tier8153.VALID, false, false, false, false))
        assertEquals("KIND_PROVEN", w.release8153(w.THIN_CELL_8153, Tier8153.UNPROVEN, true, false, false, false))
        assertEquals("RUNNER_FORMING", w.release8153(w.THIN_CELL_8153, null, false, true, false, false))
        assertEquals("ADMISSION_SPECIFIC_EDGE", w.release8153(w.THIN_CELL_8153, null, false, false, false, true))
        assertNull(w.release8153(w.THIN_CELL_8153, Tier8153.UNPROVEN, false, false, false, false))
        assertNull(w.release8153(w.THIN_CELL_8153, Tier8153.REJECTED, false, false, false, false))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val wf = gate.substringAfter("private fun watchFirst7994(").substringBefore("private val planSkips7995")
        val release = wf.indexOf("specificRelease8153(ts, l, why, nowMs, candidateVersion, candidateAdmission)")
        assertTrue(release in 0 until wf.indexOf("watched7994.incrementAndGet()"))
        assertTrue(wf.indexOf("bandNegative -> \"WATCH_FIRST_8019_BAND_NEGATIVE\"") in 0 until release)
        // A pooled cohort never outweighs the strategy's own losing record.
        assertTrue(gate.contains("LanePlaybook7907.selectedLosing8153(ts, lane, nowMs)"))
    }

    // ── replay: the binding gate with its decision-time evidence ───────────────

    private val trace = Trace8144(
        mint = "RUN8153pump", symbol = "RUNR", lane = "MOONSHOT", peakPct = 900.0, detectedAtMs = t0 + 3_600_000L,
        firstSeenMs = t0, source = "PUMP_FUN_NEW", birthKind = "KNOWN", birthSource = "CREATE_FRAME", birthAgeMs = 45_000L,
        liquidityUsd = 6_000.0, nativeAtMs = t0 + 5_000L, natives = "MOONSHOT:Y:62:MOONSHOT_NATIVE_ALLOW",
        qualified = "MOONSHOT", setup = "PRE_IGNITION_BASE", admission = "", fdgLane = "", fdgVerdict = "", handoff = "",
        refusedBy = "", downstream = "", markPx = 1.2e-5, windowAtMs = t0 + 5_000L, windowCostPct = 6.5,
        windowCostMeasured = true, route = "CURVE", held = false,
    )

    private fun evidence(liq: Double, tier: String, move: String) =
        AdmissionRunnerTrace8144.evidenceLine8153("MOONSHOT", liq, "PRE_IGNITION_BASE(VALID:PRE_IGNITION_BASE n20 +10.0±3.0)", tier, move)

    @Test fun syntheticEternalEiFomoTracesReplayByTheirBindingGate() {
        val r = AdmissionRunnerTrace8144
        // ETERNAL-like: an unread pool refused as low liquidity.
        val eternal = trace.copy(symbol = "ETERNAL", liquidityUsd = 0.0, binding = "LOW_LIQ_7300", bindingEvidence = evidence(0.0, "UNPROVEN", "MANDATE:50.0/bars1"))
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(eternal).miss)
        assertEquals("LIQ_UNREAD_REFUSED_AS_LOW:LOW_LIQ_7300", r.classify8144(eternal).gate)
        val shallow = eternal.copy(liquidityUsd = 900.0, bindingEvidence = evidence(900.0, "UNPROVEN", "-"))
        assertEquals("LIQ_REAL_BELOW_FLOOR:LOW_LIQ_7300", r.classify8144(shallow).gate)
        // EI-like: stopped at watch-first's thin cell while its own strategy was valid on its own record.
        val ei = trace.copy(symbol = "EI", binding = "WATCH_FIRST_7994_THIN_CELL", bindingEvidence = evidence(6_000.0, "VALID", "MANDATE:50.0/bars9"))
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(ei).miss)
        assertEquals("WATCH_FIRST_OVER_SPECIFIC_EVIDENCE:WATCH_FIRST_7994_THIN_CELL", r.classify8144(ei).gate)
        val eiThin = ei.copy(qualified = "", bindingEvidence = evidence(6_000.0, "UNPROVEN", "-"))
        assertEquals("NO_LANE_QUALIFIED:WATCH_FIRST_7994_THIN_CELL", r.classify8144(eiThin).gate)
        // FOMO-like: refused as too slow on two candles; the same refusal on the strategy's own move was correct.
        val fomo = trace.copy(symbol = "FOMO", binding = "NET_EDGE_COIN_TOO_SLOW_8035", bindingEvidence = evidence(6_000.0, "UNPROVEN", "RANGE_SPARSE:5.0/bars2"))
        assertEquals(Miss8144.WRONGLY_REFUSED, r.classify8144(fomo).miss)
        assertEquals("TOO_SLOW_ON_SPARSE_RANGE:NET_EDGE_COIN_TOO_SLOW_8035", r.classify8144(fomo).gate)
        val slow = fomo.copy(bindingEvidence = evidence(6_000.0, "SUPPORTED", "STRATEGY:9.0/bars20"))
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(slow).miss)
        assertEquals("PROVEN_NEGATIVE:NET_EDGE_COIN_TOO_SLOW_8035", r.classify8144(slow).gate)
        // The new deferral is a data gap; hard safety stays correct; the binding gate outranks the labeler's guess.
        assertEquals(Miss8144.DATA_GAP, r.classify8144(trace.copy(binding = StrategyMove8153.DEFER_REASON_8153)).miss)
        assertEquals(Miss8144.CORRECT_AT_DECISION, r.classify8144(trace.copy(binding = "SAFETY_REFUSED_8136")).miss)
        val bound = r.classify8144(trace.copy(refusedBy = "GENERIC_SCORE_FLOOR_8136", binding = "SETUP_RECORD_LOSING_8136"))
        assertEquals("PROVEN_NEGATIVE:SETUP_RECORD_LOSING_8136", bound.gate)
        // Summary and export carry the binding gate.
        val list = listOf(eternal, ei, fomo, trace.copy(held = true))
        val line = r.summary8144(list)
        assertTrue(line, line.startsWith("runners=4 held_entry=1 correct_at_decision=0 wrongly_refused=3"))
        assertTrue(line, line.contains("LIQ_UNREAD_REFUSED_AS_LOW=1") && line.contains("WATCH_FIRST_OVER_SPECIFIC_EVIDENCE=1") && line.contains("TOO_SLOW_ON_SPARSE_RANGE=1"))
        val text = list.joinToString("\n") { r.toJson8144(it).toString() }
        assertEquals(list, r.parseJsonl8144(text))
        // Wiring: the live edge gate's every verdict and the FDG's verdict file the binding gate.
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("AdmissionRunnerTrace8144.noteBinding8153(ts, lane, reason, nowMs)"))
        assertTrue(src("engine/truth/AdmissionRunnerTrace8144.kt").contains("noteBindingOf8153(e, tokenOf8144(mint), lane, if (allow) null else reason.orEmpty().ifBlank { \"FDG_REFUSED\" }, atMs)"))
    }
}
