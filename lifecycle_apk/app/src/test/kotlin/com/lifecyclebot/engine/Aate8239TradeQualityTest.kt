package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.DiscoveryQuality8239
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.StrategyMove8153
import com.lifecyclebot.engine.truth.StrategyRank8153
import com.lifecyclebot.engine.truth.TradeShapeLearner7883
import com.lifecyclebot.v3.scoring.ShitCoinExpress
import org.junit.Assert.*
import org.junit.Test

/** Decision-time evidence must explain both why a token was hunted and how its trade could earn after costs. */
class Aate8239TradeQualityTest {
    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
    private val t0 = 1_900_000_000_000L
    private val observed = LaneInputTruth8236.Inputs8236(true, true, true, true, true)

    private fun candidate(lane: String = "MOONSHOT", cost: Double = 6.0, edge: Double = 12.0) = Candidate8136(
        mint = "quality8239pump", lane = lane, selectedSetup = "CONTINUATION", live = true,
        expectedGrossPct = Double.NaN, costPct = cost, costSizeSol = 0.03, genericScore = 95.0, genericFloor = 30.0,
        nativeQualified8213 = true, nativeSetup8213 = "CONTINUATION",
        setups = listOf(Evidence8136(CandidateAdmission8136.KIND_SETUP, "CONTINUATION", 40, edge, 2.0)),
    )

    @Test fun everyLaneNeedsObservedMovementOrItsOwnSupportedForecast() {
        for (lane in lanes) for (target in listOf(20.0, 50.0, 500.0)) {
            val absent = StrategyMove8153.expected8153(target, null, false, null, 0, Double.NaN, Double.NaN, 6.0, false)
            assertTrue(lane, absent.expectedGrossPct.isNaN())
            assertTrue(StrategyMove8153.needsDefer8153(absent, 0.1))
            val noEdge = candidate(lane).copy(expectedGrossPct = absent.expectedGrossPct, setups = emptyList())
            assertEquals(lane, Terminal8136.DEFER_DATA, CandidateAdmission8136.decide8136(noEdge).terminal)
        }
        assertTrue(NetEdge8030.expectedMove8035(50.0, Double.NaN).isNaN())
        assertTrue(NetEdge8030.expectedMove8035(50.0, -30.0).isNaN())
        assertEquals(0.0, NetEdge8030.expectedMove8035(50.0, 0.0), 0.0)
    }

    @Test fun sparseLargePrintsCannotFillInAnUnmeasuredForecast() {
        val sparse = StrategyMove8153.expected8153(500.0, 400.0, false, null, 0, Double.NaN, Double.NaN, 6.0, true)
        assertTrue(sparse.expectedGrossPct.isNaN())
        val dense = StrategyMove8153.expected8153(50.0, 25.0, true, null, 0, Double.NaN, Double.NaN, 6.0, true)
        assertEquals(25.0, dense.expectedGrossPct, 0.0)
        val observedHour = StrategyMove8153.expected8153(50.0, null, false, 18.0, 0, Double.NaN, Double.NaN, 6.0, false)
        assertEquals(18.0, observedHour.expectedGrossPct, 0.0)
    }

    @Test fun supportedNetEvidenceStillClearsOptionalHistoryWithoutBorrowingATarget() {
        for (lane in lanes) {
            val c = candidate(lane).copy(missingInputs = listOf("shape:UNKNOWN", "tape:UNKNOWN"))
            val d = CandidateAdmission8136.decide8136(c)
            assertEquals(lane, Terminal8136.ADMIT, d.terminal)
            assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
            assertEquals(10.0, d.expectedNetPct, 0.0)
            assertTrue(d.trace.any { it.startsWith("grossFromProvenNetLower8171=") })
            assertEquals(Terminal8136.DEFER_DATA,
                CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf("route:UNKNOWN"))).terminal)
        }
    }

    @Test fun aBarelyPositiveCellDoesNotPayAnExpensiveRoute() {
        val c = candidate(cost = 13.5).copy(selectedSetup = null, setups = emptyList(),
            cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "CELL", 40, 3.0, 1.0))
        val d = CandidateAdmission8136.decide8136(c)
        assertEquals(Terminal8136.REFUSE, d.terminal)
        assertEquals("EXECUTION_ECONOMICS_8136", d.reason)
        assertEquals(2.0, d.expectedNetPct, 0.0)
        assertEquals(Terminal8136.ADMIT,
            CandidateAdmission8136.decide8136(c.copy(costPct = 3.0)).terminal)
    }

    @Test fun anUnsupportedLiveRefusalBindsBeforeOlderColdAndValidDoorsInEveryLane() {
        val thin = Evidence8136(CandidateAdmission8136.KIND_SETUP, "CONTINUATION", 12, 12.0, 2.0)
        // This was the escape: the older doors accept n12, but canonical live admission requires supported evidence.
        assertTrue(LanePlaybook7907.coldStartPositive8080(thin.n.toDouble(), thin.meanPct,
            thin.sePct * thin.sePct * thin.n))
        val option = StrategyRank8153.Option8153(thin.id, thin.n, thin.meanPct, thin.sePct)
        val tier = StrategyRank8153.tier8153(option, 6.0)
        assertEquals(StrategyRank8153.Tier8153.VALID, tier)
        assertTrue(StrategyRank8153.evidenced8153(tier))
        for (lane in lanes) {
            val c = candidate(lane).copy(expectedGrossPct = 18.0, setups = listOf(thin))
            val d = CandidateAdmission8136.decide8136(c)
            assertEquals(lane, Terminal8136.REFUSE, d.terminal)
            assertEquals("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", d.reason)
            assertFalse(d.specific)
            // LiveEdge consumes this binding verdict before its soft doors; FDG consumes the same contract.
            assertTrue(lane, CandidateAdmission8136.bindingRefusal8239(d))
            assertEquals(false, CandidateAdmission8136.floorVerdict8136(d))
        }
    }

    @Test fun supportedLiveEvidenceAndPaperPriorsKeepTheirCallerPaths() {
        for (lane in lanes) {
            val c = candidate(lane).copy(expectedGrossPct = 18.0,
                setups = listOf(Evidence8136(CandidateAdmission8136.KIND_SETUP, "CONTINUATION", 30, 12.0, 2.0)))
            val supported = CandidateAdmission8136.decide8136(c)
            assertEquals(lane, Terminal8136.ADMIT, supported.terminal)
            assertTrue(supported.specific)
            assertFalse(CandidateAdmission8136.bindingRefusal8239(supported))
            assertEquals(true, CandidateAdmission8136.floorVerdict8136(supported))

            val paper = CandidateAdmission8136.decide8136(c.copy(live = false,
                setups = listOf(c.setups.single().copy(n = 12))))
            assertEquals(lane, Terminal8136.ADMIT, paper.terminal)
            assertFalse(paper.specific)
            assertFalse(CandidateAdmission8136.bindingRefusal8239(paper))
            assertNull(CandidateAdmission8136.floorVerdict8136(paper))

            val unknown = CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = Double.NaN, setups = emptyList()))
            assertEquals(Terminal8136.DEFER_DATA, unknown.terminal)
            assertFalse(CandidateAdmission8136.bindingRefusal8239(unknown))
            assertNull(CandidateAdmission8136.floorVerdict8136(unknown))
        }
        assertFalse(CandidateAdmission8136.bindingRefusal8239(null))
        assertNull(CandidateAdmission8136.floorVerdict8136(null))
    }

    @Test fun everyLaneKeepsAnUnfinishedRefusalIneligibleEvenWhenAnAuditLikesIt() {
        for (lane in lanes) {
            val partial = SpecialistBrainBridge7542.Opinion(lane, false, 0, 0, 0.0,
                "NO_CONTINUATION", "NONE", "NONE", "NONE")
            val after = SpecialistBrainBridge7542.reviewNativeOpinion8237(partial, observed) { _, _ -> true }
            assertSame(lane, partial, after)
            assertFalse(after.eligible)
            assertEquals(0, after.score)
            assertEquals(0.0, after.requestedSizeSol, 0.0)
        }
    }

    @Test fun retiredNativeRulesRequireObservationsAndExcludeHardOrScoreFailures() {
        assertTrue(NativeRuleReview8239.mayRetire8239("NO_CONTINUATION", true) { it == "NATIVE_NO_CONTINUATION" })
        assertFalse(NativeRuleReview8239.mayRetire8239("NO_CONTINUATION", false) { throw AssertionError("No observation") })
        assertFalse(NativeRuleReview8239.mayRetire8239("NO_CONTINUATION", true) { false })
        for (reason in listOf("RUG_RISK", "LIQ_TOO_LOW", "SCORE_TOO_LOW", "WEAK_BUYS", "SELLABILITY_REJECT",
            "NATIVE_MOMENTUM_UNOBSERVED_DEFER_8236", "DAILY_LIMIT", "RECENT_RIDE", "TOKEN_SAFETY_BLOCKED")) {
            assertFalse(reason, NativeRuleReview8239.mayRetire8239(reason, true) { true })
        }
    }

    private fun express(suffix: String, bp: Double, liq: Double = 20_000.0, retired: Boolean) = ShitCoinExpress.evaluate(
        mint = "native8239$suffix", symbol = "NATIVE", currentPrice = 0.01, marketCapUsd = 30_000.0,
        liquidityUsd = liq, momentum = 0.0, buyPressurePct = bp, volumeChange = 1.1,
        priceChange5Min = 4.0, isTrending = false, isBoosted = false, tokenAgeMinutes = 30.0,
        retiredRule8239 = { retired && it == "NO_CONTINUATION" },
    )

    @Test fun theRealExpressScorerContinuesThroughRemainingChecksAfterARuleRetires() {
        ShitCoinExpress.resetDaily()
        val ordinary = express("ordinary", 65.0, retired = false)
        assertFalse(ordinary.shouldRide)
        assertTrue(ordinary.reason.startsWith("NO_CONTINUATION"))
        val weak = express("weak", 20.0, retired = true)
        assertFalse(weak.shouldRide)
        assertTrue(weak.reason.startsWith("WEAK_BUYS"))
        val illiquid = express("illiquid", 65.0, 100.0, retired = true)
        assertFalse(illiquid.shouldRide)
        assertTrue(illiquid.reason.contains("LIQ"))
        val completed = express("completed", 65.0, retired = true)
        assertTrue(completed.reason, completed.shouldRide)
        assertTrue(completed.confidence > 0)
        assertTrue(completed.positionSizeSol > 0.0)
        assertTrue(completed.rideType != ShitCoinExpress.RideType.NO_RIDE)
    }

    @Test fun exactNegativeShapeEvidenceStillBindsButABroadPopulationCannotEraseASupportedSetup() {
        val x = TradeShapeLearner7883
        assertNull(x.bindingScope8239(broadLosing = true, exactLosing = false, supportedSetup = true))
        assertEquals("LANE_FEATURE", x.bindingScope8239(true, false, false))
        assertEquals("EXACT_SETUP", x.bindingScope8239(true, true, true))
        assertEquals("EXACT_SETUP", x.bindingScope8239(false, true, false))
        assertNull(x.bindingScope8239(false, false, false))
        val f = TradeShapeLearner7883.Feature.BUY_PRESSURE
        val keys = listOf(x.scopedKey8239("LIVE", "QUALITY", "A", f, 1),
            x.scopedKey8239("PAPER", "QUALITY", "A", f, 1), x.scopedKey8239("LIVE", "MOONSHOT", "A", f, 1),
            x.scopedKey8239("LIVE", "QUALITY", "B", f, 1), x.scopedKey8239("LIVE", "QUALITY", "", f, 1))
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(keys.contains("QUALITY|BUY_PRESSURE|1"))
    }

    private fun row(admission: CandidateAdmission8136.Ordering8239? = null) = DiscoveryQuality8239.Input(
        t0, 0.01, 20_000.0, 4.0, 65.0, 1.0, admission = admission)

    @Test fun discoveryUsesTheAdmissionsConservativeNetAfterItsSizedCosts() {
        for (lane in lanes) {
            val c = candidate(lane)
            val d = CandidateAdmission8136.decide8136(c)
            val proof = CandidateAdmission8136.ordering8239(c, d, t0)
            assertEquals(10.0, proof.netLowerPct, 0.0)
            val best = DiscoveryQuality8239.priority(row(proof), t0 + 1L)
            assertEquals(lane, 3, best.tier)
            val loser = candidate(lane, edge = -8.0)
            val rejected = CandidateAdmission8136.ordering8239(loser, CandidateAdmission8136.decide8136(loser), t0)
            assertEquals(0, DiscoveryQuality8239.priority(row(rejected), t0 + 1L).tier)
            assertTrue(best.tier > DiscoveryQuality8239.priority(row().copy(change5mPct = 200.0), t0).tier)
        }
    }

    @Test fun staleOrChangedObservationsCannotKeepTheSupportedDiscoveryBoost() {
        val c = candidate()
        val proof = CandidateAdmission8136.ordering8239(c, CandidateAdmission8136.decide8136(c), t0)
        assertEquals(2, DiscoveryQuality8239.priority(row(proof.copy(atMs = t0 - 30_001L)), t0).tier)
        assertEquals(2, DiscoveryQuality8239.priority(row(proof.copy(atMs = t0 + 1L)), t0).tier)
        assertEquals(0, DiscoveryQuality8239.priority(row(proof).copy(observedAtMs = t0 - 90_001L), t0).tier)
        assertEquals(0, DiscoveryQuality8239.priority(row(proof).copy(observedAtMs = t0 + 1L), t0).tier)
        assertEquals(0, DiscoveryQuality8239.priority(row(proof).copy(change5mPct = -9.0, buyPressurePct = 20.0), t0).tier)
        assertEquals(0, DiscoveryQuality8239.priority(row(proof).copy(liquidityDeltaPct = -20.0), t0).tier)
        assertEquals(0, DiscoveryQuality8239.priority(row(proof).copy(nativeJudgement = true), t0).tier)
        assertEquals(1, DiscoveryQuality8239.priority(row().copy(change5mPct = Double.NaN, buyPressurePct = Double.NaN), t0).tier)
    }

    @Test fun discoveryNoveltyDoesNotPushSupportedCandidatesBelowUnknownOrLosingOnes() {
        val supported = (1..6).map { "supported$it" }
        val fresh = (1..8).map { "fresh$it" }
        val input = supported + fresh + "loser"
        val tiers = input.associateWith { if (it in supported) 3 else if (it == "loser") 0 else 1 }
        val selected = LaneHunter7297.selectForDiscovery8239(input, { tiers.getValue(it) }, { it in supported }, { false }, 8)
        assertEquals(supported, selected.take(6))
        assertEquals(8, selected.size)
        assertFalse(selected.contains("loser"))
        val cooling = LaneHunter7297.selectForDiscovery8239(input, { tiers.getValue(it) }, { it in supported }, { it in supported }, 3)
        assertEquals(fresh.take(3), cooling)
        assertTrue(LaneHunter7297.selectForDiscovery8239(input, { 1 }, { false }, { false }, 0).isEmpty())
    }

    private fun entry() = EntryStrategySnapshot6450.Snapshot(
        positionId = "position8239", mint = "quality8239pump", entryLane = "QUALITY", entryStrategyPid = "quality",
        entryTactic = "PULLBACK", entryRiskProfile = "STANDARD", entryExitProfile = "RUNNER",
        entrySource = "JUP_ORGANIC", entryScore = 70, entryLiquiditySol = 100.0, entryMarketCapUsd = 250_000.0,
        entryTimestampMs = t0, entryThresholdSnapshot = "observed", entryTokenAgeMs = 3_600_000L,
        entrySetup = "MOMENTUM_PULLBACK_5M", mode8090 = "LIVE", candidateVersion8090 = 42L,
    )

    private fun close() = CanonicalFinalizedTradeBus6464.Envelope(
        tradeId = "trade8239", positionId = "position8239", mint = "quality8239pump", atMs = t0 + 60_000L,
        lane = "CASHGEN", mode = "live", realizedPnlSol = 0.002, realizedReturnPct = 10.0, entrySetup = "EXIT_LABEL",
    )

    @Test fun closeAttributionKeepsTheEntryLaneCellAndSetupAfterOtherLanesReadTheMint() {
        val snap = entry()
        val ref = CellAllocator7962.entryRef8239(close(), snap)
        assertEquals("QUALITY", ref.lane)
        assertEquals("MOMENTUM_PULLBACK_5M", ref.setup)
        assertEquals(ForwardReturnLabeler7731.cellKey("JUP_ORGANIC", "QUALITY", 250_000.0, 3_600_000L), ref.cell)
        assertEquals(ref, CellAllocator7962.entryRef8239(close().copy(lane = "MOONSHOT", entrySetup = "LATER_SETUP"), snap))
    }

    @Test fun anotherPositionMintOrModeCannotSupplyTheCloseCell() {
        val env = close()
        for (bad in listOf(null, entry().copy(positionId = "other"), entry().copy(mint = "otherMint"),
            entry().copy(mode8090 = "PAPER"), entry().copy(mode8090 = "unknown"))) {
            val ref = CellAllocator7962.entryRef8239(env, bad)
            assertEquals("", ref.cell)
            assertEquals("", ref.band)
            assertEquals(env.lane, ref.lane)
            assertEquals(env.entrySetup, ref.setup)
        }
    }
}
