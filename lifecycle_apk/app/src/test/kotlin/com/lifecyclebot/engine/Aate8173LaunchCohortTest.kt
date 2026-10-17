package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.LaunchCohort8173
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8173 — the fresh PumpPortal launch cohort (the only strongly positive cohort in the forward labels) is fed real
 * data: curve depth that follows the curve, births from every direct witness, a 5 m change for a launch younger than
 * 5 minutes, a latency-critical buy tier with a fallback off a failing route, and a lane-wide stage prior that no longer
 * outranks a token's supported setup. Negative cohorts keep their refusals.
 */
class Aate8173LaunchCohortTest {
    private val c = LaunchCohort8173
    private val now = 1_800_000_000_000L

    // ── (1) curve liquidity truth ──

    @Test fun freshCurveReadingStandsWhateverThePriceSays() {
        val r = c.curveRead8173(1.5, now - 10_000L, 2e-7, now - 1_000L, now)!!
        assertEquals(1.5, r.realSol, 1e-12)
        assertEquals(LaunchCohort8173.CurveSource8173.RESERVES, r.source)
    }

    @Test fun staleCreateFrameReadingIsReplacedByTheNewerPricesCurveState() {
        // Create frame read 0.8 SOL (the seed buy) 6 minutes ago; the coin now prints at a 200 SOL cap (~$30k at $150).
        val priceSol = 200.0 / 1_000_000_000.0
        val r = c.curveRead8173(0.8, now - 6 * 60_000L, priceSol, now - 3_000L, now)!!
        assertEquals(LaunchCohort8173.CurveSource8173.PRICE_IMPLIED, r.source)
        // pump.fun invariant: cap = (30 + real)^2 / 32.19 -> real = sqrt(200 x 32.19) - 30 ≈ 50.2 SOL.
        assertEquals(LiquidityTruth8037.curveRealSolFromCap8048(200.0), r.realSol, 1e-9)
        assertEquals(50.24, r.realSol, 0.05)
        // The same number the 8164 cap path computes from a USD cap at $150/SOL.
        assertEquals(LiquidityTruth8037.curveDepthUsd8164(200.0 * 150.0, 150.0)!!, r.realSol * 150.0, 1e-6)
    }

    @Test fun priceImpliedReadingCanAlsoLowerDepthWhenTheCoinDumped() {
        val r = c.curveRead8173(40.0, now - 120_000L, 40.0 / 1_000_000_000.0, now - 1_000L, now)!!
        assertEquals(LaunchCohort8173.CurveSource8173.PRICE_IMPLIED, r.source)
        assertTrue(r.realSol < 10.0)
    }

    @Test fun olderPriceSmallDisagreementOrOutOfCurvePriceKeepsTheReading() {
        val stale = now - 120_000L
        // price observed BEFORE the reading: the reading is newer truth.
        assertEquals(LaunchCohort8173.CurveSource8173.RESERVES, c.curveRead8173(0.8, stale, 2e-7, stale - 5_000L, now)!!.source)
        // agreement within 25%: keep the account's own number.
        val agreeing = LiquidityTruth8037.curveRealSolFromCap8048(100.0)
        assertEquals(agreeing * 1.1, c.curveRead8173(agreeing * 1.1, stale, 100.0 / 1e9, now - 1_000L, now)!!.realSol, 1e-12)
        // below the curve's starting price, or past completion: not an active curve's price -> proves nothing.
        assertEquals(0.8, c.curveRead8173(0.8, stale, 10.0 / 1e9, now - 1_000L, now)!!.realSol, 1e-12)
        assertEquals(0.8, c.curveRead8173(0.8, stale, 900.0 / 1e9, now - 1_000L, now)!!.realSol, 1e-12)
        // unknown / non-finite price keeps the reading.
        assertEquals(0.8, c.curveRead8173(0.8, stale, Double.NaN, now, now)!!.realSol, 1e-12)
    }

    @Test fun noCurveRowStaysUnknownNeverEstimated() {
        assertNull(c.curveRead8173(null, 0L, 2e-7, now, now))
        assertNull(c.curveRead8173(Double.NaN, now - 120_000L, 2e-7, now, now))
    }

    // ── cohort membership ──

    @Test fun launchCohortIsAnActiveCurveBornInsideFifteenMinutes() {
        assertTrue(c.launchCohort8173(true, now - 5 * 60_000L, now))
        assertTrue(c.launchCohort8173(true, now - 14 * 60_000L, now))
        assertFalse(c.launchCohort8173(true, now - 16 * 60_000L, now))
        assertFalse(c.launchCohort8173(false, now - 60_000L, now))
        assertFalse(c.launchCohort8173(true, null, now))
        assertFalse(c.launchCohort8173(true, now + 10 * 60_000L, now))
    }

    @Test fun runtimeCohortReadsTheCreateFrameAndCurveRowByMint() {
        val t = System.currentTimeMillis()
        val mint = "LaunchCohortTestMint8173aaaaaaaaaaaaaaapump"
        CurveReserves7951.noteFrame7951(mint, 32.0, 1_000_000_000.0, t - 60_000L)
        AdmissionBirth8144.noteCreateFrame8144(mint, t - 90_000L)
        assertTrue(c.launchCohortMint8173(mint, t))
        assertEquals(t - 60_000L, CurveReserves7951.readingAtMs8173(mint))
        // 20 minutes later it is past the cohort's age band.
        assertFalse(c.launchCohortMint8173(mint, t + 20 * 60_000L))
        // a mint with no curve row and no create witness is not a launch.
        assertFalse(c.launchCohortMint8173("NoCurveNoBirthMint8173bbbbbbbbbbbbbbbbbbbbb", t))
    }

    // ── (2) birth truth ──

    @Test fun canonicalBirthReadsAPumpMintsOwnCurvePairNotedOnlyInAdmissionBirth() {
        val t = System.currentTimeMillis()
        val mint = "CurvePairBirthMint8173ccccccccccccccccccpump"
        AdmissionBirth8144.notePairCreated8169(mint, "pumpfun", t - 4 * 60_000L)
        val r = CanonicalTokenBirthTime7440.resolve(mint, t)
        assertNotNull(r)
        assertEquals(t - 4 * 60_000L, r!!.birthMs)
        assertEquals(CanonicalTokenBirthTime7440.Source.PUMP_CREATE_EVENT, r.source)
    }

    @Test fun aGraduatedPoolIsNeverReadAsTheLaunchBirth() {
        val t = System.currentTimeMillis()
        val mint = "GraduatedPoolMint8173ddddddddddddddddddpump"
        AdmissionBirth8144.notePairCreated8169(mint, "pumpswap", t - 2 * 60_000L)
        // a later pool is a lower bound only: never the curve-pair birth the canonical resolver now reads.
        assertNull(AdmissionBirth8144.curvePairMs8169(mint))
        assertFalse(c.launchCohortMint8173(mint, t))
    }

    // ── (3) launch features from real trades ──

    @Test fun windowBaseUsesLaunchPriceOnlyForACoinBornInsideTheWindow() {
        assertEquals(2.0, c.windowBase8173(2.0, 1.0, now - 60_000L, now, 300_000L)!!, 1e-12)
        assertEquals(1.0, c.windowBase8173(null, 1.0, now - 60_000L, now, 300_000L)!!, 1e-12)
        assertNull(c.windowBase8173(null, 1.0, now - 400_000L, now, 300_000L))
        assertNull(c.windowBase8173(null, null, now - 60_000L, now, 300_000L))
        assertNull(c.windowBase8173(null, 1.0, null, now, 300_000L))
    }

    @Test fun tapeStatesA5mChangeForATwoMinuteOldLaunchFromItsOwnTrades() {
        val t = System.currentTimeMillis()
        val mint = "TapeLaunchMint8173eeeeeeeeeeeeeeeeeeeeeeepump"
        com.lifecyclebot.network.PumpCurveKeys7269.rememberCreate7280(mint, 1.0e-7, t - 120_000L)
        TradeTape8100.note8100(mint, 1.2e-7, 0.5, true, t - 90_000L)
        TradeTape8100.note8100(mint, 1.5e-7, 0.4, true, t - 30_000L)
        val r = TradeTape8100.read8100(mint, t)
        assertNotNull(r)
        val chg = r!!.chg
        assertNotNull(chg)
        assertEquals(50.0, chg!!.first, 1e-6)    // 1.5e-7 over the 1.0e-7 launch price
        assertEquals(50.0, chg.second, 1e-6)
    }

    @Test fun tapeStaysSilentWithoutARealBase() {
        val t = System.currentTimeMillis()
        val mint = "TapeNoBaseMint8173fffffffffffffffffffffffpump"
        TradeTape8100.note8100(mint, 1.5e-7, 0.4, true, t - 30_000L)
        assertNull(TradeTape8100.read8100(mint, t)?.chg)
    }

    // ── (4) launch buy landing ──

    @Test fun aFailedPumpBuyRoutesTheNextBuyAwayUntilOneLands() {
        assertFalse(c.pumpRouteFailing8173(null, now))
        val failed = c.nextMemo8173(null, landed = false, nowMs = now - 30_000L)
        assertTrue(c.pumpRouteFailing8173(failed, now))
        assertFalse(c.pumpRouteFailing8173(failed, now + LaunchCohort8173.ROUTE_FAIL_WINDOW_MS_8173))
        val landed = c.nextMemo8173(failed, landed = true, nowMs = now - 10_000L)
        assertFalse(c.pumpRouteFailing8173(landed, now))
        assertTrue(c.pumpRouteFailing8173(c.nextMemo8173(landed, landed = false, nowMs = now - 1_000L), now))
    }

    @Test fun runtimeLedgerOutcomeDrivesTheFallbackAndTheLaunchLandingTable() {
        val t = System.currentTimeMillis()
        val mint = "RouteFailMint8173gggggggggggggggggggggggpump"
        CurveReserves7951.noteFrame7951(mint, 35.0, 900_000_000.0, t - 5_000L)
        AdmissionBirth8144.noteCreateFrame8144(mint, t - 60_000L)
        assertFalse(c.pumpFallback8173(mint, t))
        c.noteBuyOutcome8173(mint, "PUMPPORTAL", "EXPIRED_PROVEN", t)
        assertTrue(c.pumpFallback8173(mint, t + 1_000L))
        c.noteBuyOutcome8173(mint, "JUPITER", "CONFIRMED", t + 2_000L)
        assertTrue("a Jupiter landing does not clear PumpPortal's failure", c.pumpFallback8173(mint, t + 3_000L))
        c.noteBuyOutcome8173(mint, "PUMPPORTAL", "SUBMIT_ATTEMPTED", t + 4_000L)   // not chain truth: ignored
        assertTrue(c.pumpFallback8173(mint, t + 5_000L))
        val line = c.statusLine8173()
        assertTrue(line, line.contains("PUMPPORTAL landed=0 expired=1 failed=0"))
        assertTrue(line, line.contains("JUPITER landed=1 expired=0 failed=0 landRate=100%"))
        assertTrue(c.latencyCriticalBuy8173(mint, t))
    }

    @Test fun landingLineFormatsPerRoute() {
        val line = c.landingLine8173(mapOf("PUMPPORTAL" to intArrayOf(5, 6, 4), "JUPITER" to intArrayOf(17, 0, 0)))
        assertEquals("JUPITER landed=17 expired=0 failed=0 landRate=100% · PUMPPORTAL landed=5 expired=6 failed=4 landRate=33%", line)
        assertEquals("none", c.landingLine8173(emptyMap()))
    }

    // ── (5) admission on the cohort's own record ──

    private fun launchCandidate(
        setups: List<CandidateAdmission8136.Evidence8136>,
        cell: CandidateAdmission8136.Evidence8136? = null,
        gross: Double = 120.0,
    ) = CandidateAdmission8136.Candidate8136(
        mint = "m8173", lane = "SHITCOIN", selectedSetup = setups.firstOrNull()?.id,
        stage = "FRESH_LAUNCH", priceAgeMs = 2_000L,
        expectedGrossPct = gross, costPct = 9.0,
        setups = setups, cell = cell,
        stageRecord = CandidateAdmission8136.Evidence8136("STAGE", "FRESH_LAUNCH", 80, -12.0, 3.0),
    )

    private val ladder = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "LAUNCH_LADDER_PROVEN", 210, 236.0, 40.0, 0.4)

    @Test fun supportedSetupOutranksALaneWideStageLoss() {
        val d = CandidateAdmission8136.decide8136(launchCandidate(listOf(ladder)))
        assertEquals(d.compact, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertEquals("LAUNCH_LADDER_PROVEN", d.chosenSetup)
        assertTrue(d.trace.any { it.contains("carried by setup") })
    }

    @Test fun stageLossStillRefusesWithoutSupportedSetupOrWithALosingCell() {
        val thin = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "VOLUME_IGNITION", 14, 20.0, 15.0)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(launchCandidate(listOf(thin))).reason)
        val losingCell = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_CELL, "PUMP_PORTAL_WS|SHITCOIN|MC_LT10K", 20, -6.0, 2.0)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(launchCandidate(listOf(ladder), losingCell)).reason)
    }

    @Test fun carriedStageStillAnswersToTheCostRule() {
        // Gross 15% against a 9% round trip does not clear 2x cost: refused on economics, never admitted on the setup alone.
        val d = CandidateAdmission8136.decide8136(launchCandidate(listOf(ladder), gross = 15.0))
        assertEquals("EXECUTION_ECONOMICS_8136", d.reason)
    }

    @Test fun hardSafetyStillOutranksEverything() {
        val d = CandidateAdmission8136.decide8136(launchCandidate(listOf(ladder)).copy(safetyRefusal = "HARD_BLOCK:FREEZE_AUTHORITY"))
        assertEquals("SAFETY_REFUSED_8136", d.reason)
    }
}
