package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Record8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Verdict8323
import com.lifecyclebot.engine.cortex.LaneEvidence8077
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.StrategyRank8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8323 — one tailored cheat sheet per lane (LaneCheatSheet8323), read by the scanner routing, the hunters, the
 * playbook's setup choice, CandidateAdmission8136, TacticSwitcher and one Cortex voter per lane.
 */
class Aate8323LaneCheatSheetTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun rec(id: String, n: Double, mean: Double, se: Double, prior: Double = 0.5, run: Double = 0.0, runAvg: Double = Double.NaN,
                    liveN: Double = 0.0, liveMean: Double = Double.NaN) =
        Record8323(id, prior, n, mean, se, run, runAvg, liveN, liveMean)

    // ── every lane has a sheet ──

    @Test fun everyLaneHasATailoredSheet() {
        val lanes = LaneCheatSheet8323.LANES_8323
        assertEquals(12, lanes.size)
        assertEquals("every hunter lane has a sheet", LaneHunter7297.profiles.map { it.lane }.toSet(), lanes.toSet())
        for (lane in lanes) {
            val m = LaneCheatSheet8323.mandateOf8323(lane)
            assertEquals(lane, m.lane)
            assertTrue("$lane cap band", m.mcapMax > m.mcapMin && m.mcapMin > 0.0)
            assertTrue("$lane venues", m.venues.isNotEmpty())
            assertTrue("$lane native", m.native.isNotBlank())
            assertTrue("$lane setup menu", LanePlaybook7907.menuIds(lane).isNotEmpty())
            val sheet = LaneCheatSheet8323.applyRecords8323(lane, listOf(rec("TEST_SHEET_8323_$lane", 0.0, 0.0, Double.POSITIVE_INFINITY)))
            assertNotNull(LaneCheatSheet8323.sheetOf8323(lane))
            assertEquals(lane, sheet.lane)
            assertTrue(sheet.exit.style.isNotBlank())
            assertTrue(sheet.timing.chaseRule.contains("timingBand8094"))
        }
        // the numbers are the traders' own (read, not copied)
        assertEquals(com.lifecyclebot.v3.scoring.QualityTraderAI.MIN_MARKET_CAP_USD, LaneCheatSheet8323.mcapMin8323("QUALITY"), 0.0)
        assertEquals(com.lifecyclebot.v3.scoring.ShitCoinTraderAI.MAX_MARKET_CAP_USD, LaneCheatSheet8323.mcapMax8323("SHITCOIN"), 0.0)
        assertEquals(com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169 / 60.0, LaneCheatSheet8323.ageBandMin8323("PROJECT_SNIPER").second, 1e-9)
        assertTrue(LaneCheatSheet8323.stagesOf8323("SHITCOIN").contains("FRESH_LAUNCH"))
        assertTrue(LaneCheatSheet8323.sourcesFor8323("SHITCOIN").contains("PUMP"))
        // the report line, one per lane, within the per-line budget
        val lines = LaneCheatSheet8323.statusLines8323()
        for (lane in lanes) assertTrue(lane, lines.contains("cheatSheet8323[$lane:"))
        assertTrue(lines.lines().all { it.length <= 1_500 })
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("LaneCheatSheet8323.statusLines8323()"))
    }

    // ── scanner / merge-queue routing ──

    @Test fun routingRefusesACoinOutsideALanesMandate() {
        // the 8319 defect: a sub-$10k launch offered to QUALITY (whose own rule wants $75k+)
        assertFalse(LaneCheatSheet8323.laneFits8323("QUALITY", 8_000.0, 10_000.0))
        assertTrue(LaneCheatSheet8323.laneFits8323("QUALITY", 200_000.0, 30_000.0))
        assertTrue(LaneCheatSheet8323.laneFits8323("SHITCOIN", 8_000.0, 10_000.0))
        assertFalse("a $50M coin is not SHITCOIN prey", LaneCheatSheet8323.laneFits8323("SHITCOIN", 50_000_000.0, 2_000_000.0))
        assertTrue("lanes without a sheet are never refused", LaneCheatSheet8323.laneFits8323("STANDARD", 8_000.0, 10_000.0))
        assertTrue("an unknown cap never refuses", LaneCheatSheet8323.laneFits8323("QUALITY", 0.0, 0.0))
        val offered = LaneCheatSheet8323.routeLanes8323(linkedSetOf("MOONSHOT", "PROJECT_SNIPER", "QUALITY", "BLUECHIP", "CORE"), 8_000.0, 10_000.0)
        assertFalse(offered.contains("QUALITY"))
        assertFalse(offered.contains("BLUECHIP"))
        assertTrue(offered.contains("MOONSHOT") && offered.contains("PROJECT_SNIPER") && offered.contains("CORE"))
        // every offered lane refuses: the lanes whose mandate does fit are offered instead
        val fallback = LaneCheatSheet8323.routeLanes8323(setOf("QUALITY"), 8_000.0, 10_000.0)
        assertFalse(fallback.contains("QUALITY"))
        assertTrue(fallback.contains("SHITCOIN"))
        val q = src("engine/TokenMergeQueue.kt")
        assertTrue(q.contains("LaneCheatSheet8323.routeLanes8323(out, marketCapUsd, liquidityUsd)"))
        assertTrue(q.contains("LaneCheatSheet8323.SOURCE_LANES_8323"))
        assertTrue("the 6596 BLUECHIP floor still guards the offer", q.contains("if (l == \"BLUECHIP\" && !meetsBluechipFloor6596) continue"))
    }

    // ── hunters ──

    @Test fun huntersUseTheSheetBandAndMandate() {
        for (p in LaneHunter7297.profiles) {
            assertEquals(p.lane, LaneCheatSheet8323.mcapMin8323(p.lane), p.baseMin, 0.0)
            assertEquals(p.lane, LaneCheatSheet8323.mcapMax8323(p.lane), p.baseMax, 0.0)
        }
        // a sniper row past the first minutes, a QUALITY row too young, a row under the lane's liquidity floor: outside the mandate
        val sniper = LaneCheatSheet8323.ageBandMin8323("PROJECT_SNIPER")
        assertFalse(LaneCheatSheet8323.hunterFitsPure8323(sniper, sniper.second, 2_000.0, 0.5, 10_000.0))
        assertTrue(LaneCheatSheet8323.hunterFitsPure8323(sniper, sniper.second, 2_000.0, 0.02, 10_000.0))
        val quality = LaneCheatSheet8323.ageBandMin8323("QUALITY")
        assertFalse(LaneCheatSheet8323.hunterFitsPure8323(quality, quality.second, 3_000.0, 2.0 / 60.0, 50_000.0))
        assertTrue(LaneCheatSheet8323.hunterFitsPure8323(quality, quality.second, 3_000.0, 3.0, 50_000.0))
        assertFalse(LaneCheatSheet8323.hunterFitsPure8323(quality, quality.second, 3_000.0, 3.0, 1_000.0))
        assertFalse(LaneCheatSheet8323.hunterFits8323("DIP_HUNTER", 0.5, 500_000.0))
        assertTrue(LaneCheatSheet8323.hunterFits8323("DIP_HUNTER", 5.0, 500_000.0))
        val h = src("engine/market/LaneHunter7297.kt")
        assertTrue(h.contains("LaneCheatSheet8323.hunterFits8323(p.lane, it.ageHours, it.liquidityUsd)"))
        assertTrue(h.contains("freshnessFit7797(r, com.lifecyclebot.engine.cortex.LaneCheatSheet8323.idealMaxAgeHours8323(lane))"))
        assertTrue(h.contains("commonSenseMult7797(p.lane, r) * adaptiveTimingMultiplier7812(p.lane, r)"))
        assertEquals(0.05, LaneCheatSheet8323.idealMaxAgeHours8323("PROJECT_SNIPER"), 0.0)
    }

    // ── rotation with hysteresis ──

    @Test fun rotationFavourNeutralAvoidWithHysteresis() {
        var v = Verdict8323.NEUTRAL
        v = LaneCheatSheet8323.judge8323(rec("X", 40.0, 10.0, 3.0), v); assertEquals(Verdict8323.FAVOUR, v)
        // the lower bound slips under the prior but the mean is still positive: favour holds (no flap)
        v = LaneCheatSheet8323.judge8323(rec("X", 45.0, 2.0, 3.0), v); assertEquals(Verdict8323.FAVOUR, v)
        // the same record from NEUTRAL does not earn favour
        assertEquals(Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(rec("X", 45.0, 2.0, 3.0), Verdict8323.NEUTRAL))
        // the mean turns negative, not yet a clear loss: neutral
        v = LaneCheatSheet8323.judge8323(rec("X", 50.0, -1.0, 3.0), v); assertEquals(Verdict8323.NEUTRAL, v)
        // a clear loss (mean + 2 SE < 0): avoid
        v = LaneCheatSheet8323.judge8323(rec("X", 60.0, -8.0, 3.0), v); assertEquals(Verdict8323.AVOID, v)
        // recovering but the mean still below cost: avoid holds
        v = LaneCheatSheet8323.judge8323(rec("X", 65.0, -1.0, 3.0), v); assertEquals(Verdict8323.AVOID, v)
        // the mean back above cost: neutral (favour must be earned again)
        v = LaneCheatSheet8323.judge8323(rec("X", 70.0, 1.0, 3.0), v); assertEquals(Verdict8323.NEUTRAL, v)

        // the registry counts each rotation and keeps the verdict between rebuilds
        val lane = "CYCLIC"; val id = "TEST_ROTATE_8323"
        val r0 = LaneCheatSheet8323.applyRecords8323(lane, listOf(rec(id, 40.0, 10.0, 3.0))).rotated
        assertEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.verdictOf8323(lane, id))
        assertTrue(LaneCheatSheet8323.favourIds8323(lane).contains(id))
        LaneCheatSheet8323.applyRecords8323(lane, listOf(rec(id, 45.0, 2.0, 3.0)))
        assertEquals("hysteresis across rebuilds", Verdict8323.FAVOUR, LaneCheatSheet8323.verdictOf8323(lane, id))
        LaneCheatSheet8323.applyRecords8323(lane, listOf(rec(id, 50.0, -1.0, 3.0)))
        assertEquals(Verdict8323.NEUTRAL, LaneCheatSheet8323.verdictOf8323(lane, id))
        val s = LaneCheatSheet8323.applyRecords8323(lane, listOf(rec(id, 60.0, -8.0, 3.0)))
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.verdictOf8323(lane, id))
        assertTrue(LaneCheatSheet8323.avoidIds8323(lane).contains(id))
        assertEquals(r0 + 2, s.rotated)
        assertTrue(s.lastRotation.contains("NEUTRAL->AVOID"))
    }

    // ── tail-aware favour, clear-loss avoid ──

    @Test fun aTailDependentSetupLandsInFavour() {
        // 150 labels, mean +12% net of cost, 12% of them runners averaging +150%: the non-runners (the median side) lose.
        val r = rec("LAUNCH_LADDER_PROVEN", 150.0, 12.0, 4.0, prior = 1.0, run = 0.12, runAvg = 150.0)
        assertTrue("the median side is negative", r.nonRunnerAvgPct < 0.0)
        assertEquals(Verdict8323.FAVOUR, LaneCheatSheet8323.judge8323(r, Verdict8323.NEUTRAL))
        assertTrue("ranked on the mean, not the median", LaneCheatSheet8323.expectancy8323(r) > 0.0)
        val rows = LaneCheatSheet8323.rows8323(listOf(rec("FLAT", 40.0, 0.5, 2.0), r, rec("LOSER", 40.0, -9.0, 2.0)), emptyMap())
        assertEquals("LAUNCH_LADDER_PROVEN", rows.first().record.setup)
        assertEquals(Verdict8323.AVOID, rows.last().verdict)
        // live closes losing keep it out of favour (and avoid it); paper never enters (the live record is LIVE-account only)
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.judge8323(r.copy(liveN = 3.0, liveMeanPct = -12.0), Verdict8323.FAVOUR))
        assertTrue(src("engine/cortex/LaneCheatSheet8323.kt").contains("val rr = if (live) (try { Cortex7885.setupRealised8187(lane, r.setup) }"))
    }

    @Test fun aClearlyLosingSetupLandsInAvoid() {
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.judge8323(rec("L", 40.0, -6.0, 1.5), Verdict8323.NEUTRAL))
        assertEquals("proven on 30+", Verdict8323.AVOID, LaneCheatSheet8323.judge8323(rec("L", 35.0, -2.0, 1.5), Verdict8323.NEUTRAL))
        assertEquals("live closes losing", Verdict8323.AVOID, LaneCheatSheet8323.judge8323(rec("L", 0.0, 0.0, Double.POSITIVE_INFINITY, liveN = 3.0, liveMean = -10.0), Verdict8323.NEUTRAL))
        assertEquals("a thin loss is not clear", Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(rec("L", 8.0, -3.0, 4.0), Verdict8323.NEUTRAL))
        assertEquals("a single live close is not a losing record", Verdict8323.NEUTRAL,
            LaneCheatSheet8323.judge8323(rec("L", 0.0, 0.0, Double.POSITIVE_INFINITY, liveN = 1.0, liveMean = -20.0), Verdict8323.NEUTRAL))
    }

    // ── avoid re-probes on the exploration slot ──

    @Test fun anAvoidedSetupReprobesOnTheExplorationSlot() {
        val now = 10_000_000L
        assertFalse(LaneCheatSheet8323.reprobeDecision8323(null, "m1", now, slotFree = false))
        assertTrue(LaneCheatSheet8323.reprobeDecision8323(null, "m1", now, slotFree = true))
        val g = LaneCheatSheet8323.Grant8323("m1", now)
        assertTrue("the same coin keeps its grant", LaneCheatSheet8323.reprobeDecision8323(g, "m1", now + 60_000L, slotFree = false))
        assertFalse("another coin waits the gap", LaneCheatSheet8323.reprobeDecision8323(g, "m2", now + 60_000L, slotFree = true))
        assertTrue(LaneCheatSheet8323.reprobeDecision8323(g, "m2", now + LaneCheatSheet8323.REPROBE_GAP_MS_8323, slotFree = true))

        val lane = "DIP_HUNTER"; val id = "TEST_REPROBE_8323"
        LaneCheatSheet8323.applyRecords8323(lane, listOf(rec(id, 40.0, -6.0, 1.5)))
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.verdictOf8323(lane, id))
        val t = System.currentTimeMillis()
        assertTrue(LaneCheatSheet8323.reprobe8323(lane, id, "mintA", t, slotFree = true))
        assertFalse(LaneCheatSheet8323.reprobe8323(lane, id, "mintB", t + 1_000L, slotFree = true))
        assertTrue(LaneCheatSheet8323.reprobe8323(lane, id, "mintA", t + 2_000L, slotFree = false))
        assertEquals("the probing coin reads NEUTRAL", Verdict8323.NEUTRAL, LaneCheatSheet8323.effectiveVerdict8323(lane, id, "mintA", t + 3_000L))
    }

    // ── consumers read the sheet ──

    @Test fun thePlaybookChoiceAndStrategyPickPutFavourFirstAndDropAvoid() {
        val a = LaneEvidence8077.Setup("A", 5.0, 40.0, 5.0, 40.0)
        val b = LaneEvidence8077.Setup("B", 2.0, 40.0, 2.0, 40.0)
        val verdicts = mapOf("A" to Verdict8323.AVOID, "B" to Verdict8323.FAVOUR)
        assertEquals("B", LaneCheatSheet8323.chooseWith8323(listOf(a, b), { verdicts[it] ?: Verdict8323.NEUTRAL }) { LaneEvidence8077.choose(it) }?.id)
        assertEquals("A", LaneCheatSheet8323.chooseWith8323(listOf(a, b), { Verdict8323.NEUTRAL }) { LaneEvidence8077.choose(it) }?.id)
        assertEquals("only avoided setups fired: unchanged", "A",
            LaneCheatSheet8323.chooseWith8323(listOf(a), { Verdict8323.AVOID }) { LaneEvidence8077.choose(it) }?.id)

        val oa = StrategyRank8153.Option8153("A", 40, 6.0, 1.0)
        val ob = StrategyRank8153.Option8153("B", 25, 4.0, 1.5)
        val base = StrategyRank8153.select8153("A", listOf(oa, ob), Double.NaN)
        assertEquals("A", base.id)
        val pick = LaneCheatSheet8323.pickWith8323(base, "A", listOf(oa, ob)) { verdicts[it] ?: Verdict8323.NEUTRAL }
        assertEquals("B", pick?.id)
        assertTrue(LaneCheatSheet8323.favourBasis8323(pick?.basis))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("LaneCheatSheet8323.choose8323(lane, fired.map { setupEvidence8077(lane, it) })"))
        assertTrue(pb.contains("LaneCheatSheet8323.pickWith8323("))
        assertTrue(pb.contains("LaneCheatSheet8323.noteLabel8323(lane, setup,"))
    }

    private fun cand(selected: String?, setups: List<Evidence8136>, sheet: Map<String, String>) = Candidate8136(
        mint = "MINT8323pump", lane = "SHITCOIN", selectedSetup = selected, regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = setups.associate { it.id to 30.0 }, setups = setups, genericScore = 12.0, genericFloor = 30.0,
        sheet8323 = sheet,
    )

    private fun ev(id: String, n: Int, mean: Double, se: Double) = Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)

    @Test fun admissionFavourSupportsAvoidRefusesNeutralFallsThrough() {
        // AVOID refuses the selected strategy when nothing else is supported
        val avoid = CandidateAdmission8136.decide8136(cand("A", listOf(ev("A", 8, 1.0, 3.0)), mapOf("A" to CandidateAdmission8136.SHEET_AVOID_8323)))
        assertEquals(Terminal8136.REFUSE, avoid.terminal)
        assertEquals("SHEET_AVOID_8323", avoid.reason)
        // FAVOUR supports below the 30-label SUPPORTED bar (25 labels, lower bound +2.5%): the edge, after economics
        val fav = CandidateAdmission8136.decide8136(cand("B", listOf(ev("B", 25, 4.0, 1.5)), mapOf("B" to CandidateAdmission8136.SHEET_FAVOUR_8323)))
        assertEquals(Terminal8136.ADMIT, fav.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", fav.reason)
        assertEquals("B", fav.chosenSetup)
        // an avoided selection rotates to the favoured alternative
        val rot = CandidateAdmission8136.decide8136(cand("A", listOf(ev("A", 8, 1.0, 3.0), ev("B", 25, 4.0, 1.5)),
            mapOf("A" to CandidateAdmission8136.SHEET_AVOID_8323, "B" to CandidateAdmission8136.SHEET_FAVOUR_8323)))
        assertEquals(Terminal8136.ADMIT, rot.terminal)
        assertEquals("B", rot.chosenSetup)
        // NEUTRAL: the existing rules decide (the same record without a sheet verdict is a thin positive -> generic prior refuses)
        val neutral = CandidateAdmission8136.decide8136(cand("B", listOf(ev("B", 25, 4.0, 1.5)), emptyMap()))
        assertEquals(Terminal8136.REFUSE, neutral.terminal)
        assertEquals("GENERIC_SCORE_FLOOR_8136", neutral.reason)
        // economics still decide a favoured setup
        val poor = CandidateAdmission8136.decide8136(cand("B", listOf(ev("B", 25, 4.0, 1.5)), mapOf("B" to CandidateAdmission8136.SHEET_FAVOUR_8323))
            .copy(expectedGrossPct = 6.5, expectedGrossBySetup = mapOf("B" to 6.5)))
        assertEquals("EXECUTION_ECONOMICS_8136", poor.reason)
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("LaneCheatSheet8323.admissionVerdicts8323(lane,"))
    }

    @Test fun tacticSwitcherRotatesTowardAFavouredShape() {
        val lane = "CORE"
        LaneCheatSheet8323.applyRecords8323(lane, listOf(rec("TEST_TACTIC_FAV_8323", 40.0, 10.0, 3.0), rec("TEST_TACTIC_AVOID_8323", 40.0, -9.0, 2.0)))
        val tacticOf = { s: String -> if (s == "TEST_TACTIC_FAV_8323") 3 else 1 }
        assertEquals(3, LaneCheatSheet8323.rotationTarget8323(lane, 0, 5, tacticOf))
        assertEquals("no sheet: the old +1", 1, LaneCheatSheet8323.rotationTarget8323("NOT_A_LANE_8323", 0, 5, tacticOf))
        assertTrue(src("engine/learning/TacticSwitcher.kt").contains("LaneCheatSheet8323.rotationTarget8323(lane, current, Tactic.values().size)"))
    }

    @Test fun oneCortexVoterPerLaneReadsTheSheetAsAnInput() {
        for (lane in LaneCheatSheet8323.LANES_8323) {
            val id = "SHEET_FAVOUR_$lane"
            assertTrue(id, CortexVoters7885.IDS.contains(id))
            assertNotNull(id, CortexVoters7885.edgesFor(id))
            assertFalse("lane-free read (the lane is the voter's own)", CortexVoters7885.LANE_DEPENDENT_8169.contains(id))
        }
        // Cortex7885 / CortexVoters7885 untouched by 8323 (another agent owns them): the seats live in the wide estate
        assertFalse(src("engine/cortex/CortexVoters7885.kt").contains("8323"))
        assertTrue(src("engine/cortex/CortexVotersWide8004.kt").contains("LaneCheatSheet8323.vote8323(ts, \"SHITCOIN\", now)"))
    }
}
