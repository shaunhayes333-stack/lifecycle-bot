package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.ExplorationBudget
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.MarkBars7948
import com.lifecyclebot.engine.truth.MarkBarsStore7951
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7951 — the meme pipeline's chokes ahead of capital (5.0.7949 raw diag, 146 s). */
class Aate7951MemeChokesTest {
    private val min = 60_000L
    private val t0 = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. V3 ZERO_LIQUIDITY: the curve's reserves are its liquidity ──

    @Test fun curveRealSolIsVirtualMinusTheThirtySolOfVirtualReserves() {
        assertEquals(0.0, CurveReserves7951.realSolFromVirtual7951(30.0), 1e-12)
        assertEquals(12.5, CurveReserves7951.realSolFromVirtual7951(42.5), 1e-12)
        assertEquals(0.0, CurveReserves7951.realSolFromVirtual7951(Double.NaN), 1e-12)
        // Spot price from the frame's own reserves; k/vSol when the token side is unknown.
        assertEquals(42.5 / 7.5e8, CurveReserves7951.priceSol7951(42.5, 7.5e8), 1e-18)
        val k = 30.0 * 1_073_000_000.0
        assertEquals(30.0 / (k / 30.0), CurveReserves7951.priceSol7951(30.0, 0.0), 1e-18)
    }

    @Test fun observedCurveLiquidityHasBoundedLife() {
        assertEquals(5.0, CurveReserves7951.liquiditySolFor7951(5.0, 59L * min, graduated = false), 1e-12)
        assertEquals(0.0, CurveReserves7951.liquiditySolFor7951(5.0, 61L * min, graduated = false), 1e-12)
        // What migrated to the pool stands in only briefly, until the pool is hydrated.
        assertEquals(80.0, CurveReserves7951.liquiditySolFor7951(80.0, 20L * min, graduated = true), 1e-12)
        assertEquals(0.0, CurveReserves7951.liquiditySolFor7951(80.0, 31L * min, graduated = true), 1e-12)
        assertEquals(0.0, CurveReserves7951.liquiditySolFor7951(0.0, 0L, graduated = false), 1e-12)
    }

    @Test fun framesAreKeptByMintBeforeAnyTokenRowExists() {
        CurveReserves7951.resetForTest7951()
        val mint = "Curve7951AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val now = System.currentTimeMillis()
        assertFalse(CurveReserves7951.known7951(mint))
        CurveReserves7951.noteFrame7951(mint, 34.0, 9.4e8, now - 1_000L)
        assertTrue(CurveReserves7951.known7951(mint))
        assertEquals(4.0, CurveReserves7951.liquiditySol7951(mint, now), 1e-9)
        // An older frame never overwrites a newer reading; the chain read's real reserves win.
        CurveReserves7951.noteFrame7951(mint, 31.0, 1.0e9, now - 5_000L)
        assertEquals(4.0, CurveReserves7951.liquiditySol7951(mint, now), 1e-9)
        CurveReserves7951.noteAccount7951(mint, 36.0, 9.0e8, 6.2, now)
        assertEquals(6.2, CurveReserves7951.liquiditySol7951(mint, now), 1e-9)
        assertEquals(36.0 / 9.0e8, CurveReserves7951.priceSolFor7951(mint, now, 10L * min), 1e-18)
        assertEquals(0.0, CurveReserves7951.priceSolFor7951(mint, now + 11L * min, 10L * min), 1e-18)
    }

    @Test fun curveReservesFeedObservedLiquidityAndV3() {
        val tma = src("engine/TokenMapAuthority.kt")
        assertTrue(tma.contains("?.takeIf { it.isFinite() && it > 0.0 } ?: curveLiquiditySol7951(ts)"))
        assertTrue(tma.contains("CurveReserves7951.liquiditySol7951(ts.mint)"))
        val ws = src("network/PumpFunWS.kt")
        assertEquals(2, Regex("CurveReserves7951\\.noteFrame7951\\(").findAll(ws).count())
        assertTrue(src("network/ParallelMarkFanout7088.kt").contains("CurveReserves7951.noteAccount7951("))
    }

    // ── 1b. V3 SIZE_ZERO / TOO_OLD do not terminate the ShitCoin lane ──

    @Test fun v3CapitalAndTrunkAgeVerdictsAreRoutingNotTokenTruth() {
        assertTrue(MemeChokes7951.v3RejectIsLaneRouting7951("SIZE_ZERO"))
        // V5.0.7952 review — TOO_OLD (true on-chain age) stays fatal.
        assertFalse(MemeChokes7951.v3RejectIsLaneRouting7951("TOO_OLD"))
        assertTrue(MemeChokes7951.v3RejectIsLaneRouting7951("SHITCOIN_CANDIDATE"))
        assertTrue(MemeChokes7951.v3RejectIsLaneRouting7951("MCAP_TOO_LOW"))
        for (hard in listOf("ZERO_LIQUIDITY", "LOW_LIQUIDITY", "COOLDOWN", "ALREADY_OPEN", "SCORE_TOO_LOW", "GLOBAL_EXPOSURE_MAX_SLOTS(5/5)")) {
            assertFalse(hard, MemeChokes7951.v3RejectIsLaneRouting7951(hard))
        }
        assertTrue(src("engine/BotService.kt").contains("MemeChokes7951.v3RejectIsLaneRouting7951(reason)"))
    }

    // ── 2. TOO_FEW_BARS after a restart ──

    @Test fun onlyCompletedMissingMinutesAreSeeded() {
        val bars = (0 until 4).map { TradePlan7739.Bar(t0 + it * min, 1.0, 1.2, 0.9, 1.1) }
        val now = t0 + 3 * min + 20_000L
        val missing = MarkBars7948.missingBars7951(bars, setOf((t0 + min) / min), now)
        // Minute 1 is already on the tape; minute 3 is still forming.
        assertEquals(listOf(t0, t0 + 2 * min), missing.map { it.startMs })
        // Outside the keep window, or malformed: dropped.
        assertTrue(MarkBars7948.missingBars7951(bars, emptySet(), t0 + 90L * min).isEmpty())
        val bad = listOf(TradePlan7739.Bar(t0, 1.0, Double.NaN, 0.9, 1.1))
        assertTrue(MarkBars7948.missingBars7951(bad, emptySet(), t0 + 5 * min).isEmpty())
    }

    @Test fun seededBarsReachThePlanAndLiveMinutesWin() {
        MarkBars7948.resetForTest7948()
        val mint = "Seed7951BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val now = t0 + 8 * min + 10_000L
        MarkBars7948.note7948(mint, 5.0, t0 + 2 * min + 30_000L)
        val known = (0 until 8).map { TradePlan7739.Bar(t0 + it * min, 1.0 + it, 1.5 + it, 0.5 + it, 1.2 + it) }
        assertEquals(7, MarkBars7948.seedBars7951(mint, known, now))
        val tape = MarkBars7948.bars7948(mint, now, 30L * min)
        assertEquals(8, tape.size)
        val b0 = tape.first()
        assertEquals(1.0, b0.open, 1e-12); assertEquals(1.5, b0.high, 1e-12)
        assertEquals(0.5, b0.low, 1e-12); assertEquals(1.2, b0.close, 1e-12)
        // The live print's minute was not overwritten by the seed.
        assertEquals(5.0, tape[2].close, 1e-12)
        // Seeding again adds nothing.
        assertEquals(0, MarkBars7948.seedBars7951(mint, known, now))
    }

    @Test fun tapeRoundTripsThroughStorageInsideItsWindow() {
        val now = t0 + 40L * min
        val rows = listOf(
            "MintA" to listOf(TradePlan7739.Bar(now - 10 * min, 1.0, 2.0, 0.5, 1.5), TradePlan7739.Bar(now - 9 * min, 1.5, 1.6, 1.4, 1.45)),
            "MintB" to listOf(TradePlan7739.Bar(now - 50 * min, 1.0, 1.0, 1.0, 1.0)),
        )
        val back = MarkBarsStore7951.decode7951(MarkBarsStore7951.encode7951(rows), now)
        assertEquals(1, back.size)
        assertEquals("MintA", back[0].first)
        assertEquals(rows[0].second, back[0].second)
        assertTrue(MarkBarsStore7951.decode7951("garbage\nMintC\tx,1,2\n", now).isEmpty())
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.indexOf("MarkBars7948.seedBars7951(ts.mint, hist, nowMs)") < plan.indexOf("if (hist.size > MIN_BARS_7739) return hist"))
        assertTrue(src("engine/BotService.kt").contains("MarkBarsStore7951.start7951(applicationContext)"))
    }

    // ── 3. exploration budget counts distinct probes ──

    @Test fun reAskingTheSameMintDoesNotSpendTheProbeBudget() {
        ExplorationBudget.resetProbesForTest7951()
        val lane = "TEST_LANE_7951"
        val ceiling = ExplorationBudget.budgetFor(lane).maxPaperMicroTradesPerHour
        assertTrue(ExplorationBudget.allowProbe7951(lane, "MintX"))
        repeat(ceiling * 3) { assertTrue(ExplorationBudget.allowProbe7951(lane, "MintX")) }
        // Distinct mints still spend the ceiling.
        var admitted = 1
        for (i in 0 until ceiling * 2) if (ExplorationBudget.allowProbe7951(lane, "Mint$i")) admitted++
        assertEquals(ceiling, admitted)
        assertTrue(ExplorationBudget.probeReaskFree7951(1_000L, 1_000L + 59L * min))
        assertFalse(ExplorationBudget.probeReaskFree7951(1_000L, 1_000L + 61L * min))
        assertFalse(ExplorationBudget.probeReaskFree7951(null, 1_000L))
        assertEquals(2, Regex("ExplorationBudget\\.allowProbe7951\\(lane, mintForProbe\\)").findAll(src("engine/BotService.kt")).count())
    }

    // ── 4. FDG: zero confidence, specialist capital refusals, unfundable size ──

    @Test fun unpopulatedConfidenceCarriesTheLanesOwn() {
        val c = com.lifecyclebot.data.CandidateDecision(
            entryScore = 0.0, exitScore = 0.0, phase = "UNKNOWN", signal = "WAIT",
            setupQuality = "C", edgeQuality = "C", finalQuality = "C", edgePhase = "UNKNOWN",
            edgeConfidence = 0.0, isOptimalEntry = false, edgeVeto = false, shouldTrade = false,
            finalSignal = "WAIT", blockReason = "", qualityPenalty = 1.0, aiConfidence = 0.0,
            meta = com.lifecyclebot.data.StrategyMeta(),
        )
        val filled = MemeChokes7951.laneConfidence7951(c, 38.0)
        assertEquals(38.0, filled.aiConfidence, 1e-12)
        assertEquals(0.0, filled.entryScore, 1e-12)
        val populated = c.copy(aiConfidence = 12.0)
        assertSame(populated, MemeChokes7951.laneConfidence7951(populated, 90.0))
        assertSame(c, MemeChokes7951.laneConfidence7951(c, 0.0))
        assertTrue(src("engine/BotService.kt").contains("val base = MemeChokes7951.laneConfidence7951(candidate7951, confidenceFloor)"))
    }

    @Test fun aWalletTooSmallIsNotASpecialistConvictionRefusal() {
        assertTrue(MemeChokes7951.isCapitalArtefactReason7951("REJECTED: LIVE_WALLET_TOO_SMALL (0.0400◎)"))
        assertTrue(MemeChokes7951.isCapitalArtefactReason7951("CAPITAL_BELOW_MIN_EXECUTABLE_6490"))
        assertFalse(MemeChokes7951.isCapitalArtefactReason7951("REJECTED: S0_10_BLEED_GUARD"))
        assertFalse(MemeChokes7951.isCapitalArtefactReason7951("MCAP_TOO_LOW: \$48K < \$1M"))
        val doc = src("engine/truth/LiveConcentrationDoctrine7697.kt")
        assertTrue(doc.contains("if (!com.lifecyclebot.engine.MemeChokes7951.isCapitalArtefactReason7951(opinion.reason)) {"))
    }

    @Test fun chartBuyStarvedOfCapitalIsDemandForRotation() {
        assertTrue(MemeChokes7951.capitalStarved7951(paper = false, freeSol = 0.028, minimumSol = 0.0453))
        assertFalse(MemeChokes7951.capitalStarved7951(paper = true, freeSol = 0.0, minimumSol = 0.0453))
        assertFalse(MemeChokes7951.capitalStarved7951(paper = false, freeSol = 0.06, minimumSol = 0.0453))
        assertTrue(MemeChokes7951.demandCurrent7951(1_000L, 1_000L + 2L * min))
        assertFalse(MemeChokes7951.demandCurrent7951(1_000L, 1_000L + 4L * min))
        assertFalse(MemeChokes7951.demandCurrent7951(0L, 1_000L))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("MemeChokes7951.noteCapitalStarved7951(ts.mint, lane,"))
        assertTrue(src("engine/Executor.kt").contains("MemeChokes7951.provenDemandWaiting7951()"))
    }

    // ── 5. fresh curve tokens do not wait on DEX pair hydration ──

    @Test fun theCurveIsTheVenueUntilItGraduates() {
        assertTrue(MemeChokes7951.isCurveVenue7951(pumpSuffix = true, curveKeyKnown = false, reservesKnown = false, graduated = true))
        assertTrue(MemeChokes7951.isCurveVenue7951(pumpSuffix = false, curveKeyKnown = true, reservesKnown = false, graduated = false))
        assertTrue(MemeChokes7951.isCurveVenue7951(pumpSuffix = false, curveKeyKnown = false, reservesKnown = true, graduated = false))
        assertFalse(MemeChokes7951.isCurveVenue7951(pumpSuffix = false, curveKeyKnown = true, reservesKnown = true, graduated = true))
        assertFalse(MemeChokes7951.isCurveVenue7951(pumpSuffix = false, curveKeyKnown = false, reservesKnown = false, graduated = false))
        assertTrue(src("engine/BotService.kt").contains("MemeChokes7951.curveVenueSeeded7951(ts)"))
    }
}
