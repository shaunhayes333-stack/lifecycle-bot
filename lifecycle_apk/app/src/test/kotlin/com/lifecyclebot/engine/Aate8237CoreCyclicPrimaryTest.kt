package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CORE's ensemble/generalist and CYCLIC's meme-native assessment feed the common
 * primary FDG/authorizer path in BotService. CYCLIC's separate $5k compound-ring
 * tick is not that path. Native decisions and admission are real here; the final
 * authorization is PAPER so a regression test cannot broadcast a transaction.
 */
class Aate8237CoreCyclicPrimaryTest {
    private val election = SpecialistElection8136
    private var enabledBefore = emptySet<EnabledTraderAuthority.Trader>()
    private var cyclicBefore = false

    private fun mode(paper: Boolean) {
        RuntimeModeAuthority.publishConfig(paper, true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }

    @Before fun setUp() {
        enabledBefore = EnabledTraderAuthority.snapshot()
        cyclicBefore = CyclicTradeEngine.isEnabled()
        EnabledTraderAuthority.publish(setOf(EnabledTraderAuthority.Trader.MEME))
        CyclicTradeEngine.setEnabled(true)
        mode(true)
        ExecutableOpenGate.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()
        LaneExecutionCoordinator.resetForTests()
        TradeAuthorizer.reset()
        RuntimeConfigOverlay.resetForTests()
        election.resetForTests8136()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        ExecutableEntryAuthority6450.resetForTest6487()
        ToxicModeCircuitBreaker.resetForTests()
    }

    @After fun tearDown() {
        mode(true)
        EnabledTraderAuthority.publish(enabledBefore)
        CyclicTradeEngine.setEnabled(cyclicBefore)
        ExecutableOpenGate.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()
        LaneExecutionCoordinator.resetForTests()
        TradeAuthorizer.reset()
        election.resetForTests8136()
        PaperAccountLedger6430.resetForTest()
        ExecutableEntryAuthority6450.resetForTest6487()
        ToxicModeCircuitBreaker.resetForTests()
    }

    private fun token(lane: String) = TokenState("Primary8237_$lane", symbol = "PRIMARY",
        source = "DEXSCREENER", pairAddress = "Pool8237_$lane", lastPricePoolAddr = "Pool8237_$lane",
        lastPriceSource = "DEXSCREENER", lastPriceDex = "RAYDIUM", lastPrice = 0.01,
        lastPriceUpdate = System.currentTimeMillis(), lastLiquidityUsd = 250_000.0, lastMcap = 10_000_000.0,
        lastV3Score = 95, lastV3Confidence = 85, entryScore = 95.0, lastBuyPressurePct = 65.0,
        safety = SafetyReport(tier = SafetyTier.SAFE, rugcheckScore = 90,
            mintAuthorityDisabled = true, freezeAuthorityDisabled = true, lpLockPct = 98.0,
            topHolderPct = 12.0, tokenAgeMinutes = 60.0, checkedAt = System.currentTimeMillis(),
            rugcheckStatus = "CONFIRMED", liqConfirmed = true, bundleRisk = "LOW"))

    private fun specialist(lane: String, score: Int, setup: String) = SpecialistBrainBridge7542.Opinion(
        lane, eligible = true, score = score, confidence = score, requestedSizeSol = 0.08,
        reason = "NATIVE_QUALIFIED", setup = setup, entryStyle = "native", exitStyle = "native")

    /** Carry an actual native verdict through the production election/admission and common authorizer. */
    private fun authorize(ts: TokenState, lane: String, eligible: Boolean, score: Int, confidence: Int,
                          nativeReason: String, setup: String) {
        assertTrue(nativeReason, eligible)
        assertTrue(ExecutableOpenGate.isRealExecutionLaneForTests7117(lane))
        val now = System.currentTimeMillis()
        val version = LaneExecutionCoordinator.candidateVersionFor(ts.mint)
        election.registerNative8136(ts.mint, version, now,
            mapOf(lane to SpecialistElection8136.NativeView8136(eligible, score.toDouble(), nativeReason)), now)
        assertEquals(lane, election.cyclePrimary8136(ts.mint, version, lane, false, null, now))
        assertTrue(election.mayOwn8136(ts.mint, lane))
        val size = maxOf(0.08, com.lifecyclebot.engine.truth.OrderSizeResolver6441.paperExecutableMinimumSol() + 0.01)
        val admitted = CandidateAdmission8136.decide8136(CandidateAdmission8136.Candidate8136(
            mint = ts.mint, lane = lane, selectedSetup = null, candidateVersion = version,
            priceAgeMs = now - ts.lastPriceUpdate, missingInputs = listOf("shape:UNKNOWN"),
            expectedGrossPct = 25.0, costPct = 4.0, costSizeSol = size,
            costBreakdown = "observed depth=250000USD size=$size SOL roundTrip=4%",
            genericScore = score.toDouble(), genericFloor = 15.0,
            nativeQualified8213 = eligible, nativeSetup8213 = setup,
        ))
        assertEquals(admitted.reason, CandidateAdmission8136.Terminal8136.ADMIT, admitted.terminal)
        assertEquals(setup, admitted.chosenSetup)

        // The FDG result is the boundary input the trunk gives TradeAuthorizer; no
        // fabricated execution intent is registered by this fixture.
        val fdg = FinalDecisionGate.FinalDecision(
            shouldTrade = true, mode = FinalDecisionGate.TradeMode.PAPER,
            approvalClass = FinalDecisionGate.ApprovalClass.PAPER_BENCHMARK,
            quality = "A", confidence = confidence.toDouble(), edge = FinalDecisionGate.EdgeVerdict.STRONG,
            blockReason = null, blockLevel = null, sizeSol = size, tags = emptyList(),
            mint = ts.mint, symbol = ts.symbol, approvalReason = admitted.reason, gateChecks = emptyList(),
            effectiveEntryScore7687 = score, candidateVersion7835 = version, canonicalLane7835 = lane,
        )
        election.onFdgVerdict8136(ts.mint, lane, version, true, null, false, true, score.toDouble(), now)
        val result = TradeAuthorizer.authorize(ts.mint, ts.symbol, score, confidence.toDouble(), "A", true,
            TradeAuthorizer.ExecutionBook.valueOf(lane), rugcheckScore = ts.safety.rugcheckScore,
            liquidity = ts.lastLiquidityUsd, preResolvedSizeSol = size, fdgDecision7835 = fdg, tokenState7835 = ts)
        assertTrue("$lane: ${result.reason}", result.isExecutable())
        val intent = result.executionIntent7835
        assertNotNull(lane, intent)
        assertEquals(lane, intent!!.canonicalLane)
        assertEquals(version, intent.candidateVersion)
        assertEquals(size, intent.resolvedSize, 1e-9)
        assertEquals(result.attemptId, intent.attemptId)
        assertSame(intent, ExecutableOpenGate.ticketForAttempt(result.attemptId))
        assertEquals(SpecialistElection8136.Terminal8136.ELECTED_EVALUATED,
            election.terminalOf8136(ts.mint, lane)?.first)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test fun aQualifiedCoreEnsembleReachesItsOwnPrimaryIntent() {
        val ts = token("CORE")
        val evidence = listOf(specialist("QUALITY", 68, "LIQUIDITY_DEPTH_QUALITY"),
            specialist("BLUECHIP", 65, "MAINSTREAM_CRYPTO_SWING"))
        val core = SpecialistBrainBridge7542.coreOpinion7542(ts, evidence, 95, 85, ts.lastBuyPressurePct)
        assertEquals("CORE_ENSEMBLE_QUALITY+BLUECHIP", core.reason)
        assertEquals(66, core.score)
        authorize(ts, core.lane, core.eligible, core.score, core.confidence, core.reason, core.setup)
    }

    @Test fun aQualifiedCyclicNativeReachesItsOwnPrimaryIntent() {
        val ts = token("CYCLIC")
        // Exercise live-mode native freshness/sellability, independently of the ring bankroll.
        val native = CyclicTradeEngine.evaluateCandidate7542(ts, isLive = true)
        assertTrue(native.reason, native.eligible)
        assertTrue(native.reason.startsWith("CYCLIC_NATIVE_SCORE_95_FLOOR_"))
        authorize(ts, "CYCLIC", native.eligible, native.score, native.confidence, native.reason, "CYCLIC_COMPOUND")
    }

    @Test fun nativeCounterexamplesCannotBorrowTheQualifiedContext() {
        val ts = token("NATIVE_REFUSED")
        val clearSpecialist = SpecialistBrainBridge7542.coreOpinion7542(ts,
            listOf(specialist("QUALITY", 85, "LIQUIDITY_DEPTH_QUALITY"), specialist("BLUECHIP", 65, "MAINSTREAM_CRYPTO_SWING")),
            95, 85, ts.lastBuyPressurePct)
        assertFalse(clearSpecialist.eligible)
        assertEquals("CORE_YIELD_CLEAR_SPECIALIST_OWNER", clearSpecialist.reason)
        val unsafe = ts.copy(mint = "Primary8237_unsafe", safety = ts.safety.copy(tier = SafetyTier.HARD_BLOCK,
            hardBlockReasons = listOf("CONFIRMED_RUG")))
        assertEquals("SELLABILITY_REJECT", CyclicTradeEngine.evaluateCandidate7542(unsafe, isLive = true).reason)
        val unknownSellability = ts.copy(mint = "Primary8237_unknown", lastPriceSource = "")
        assertFalse(CyclicTradeEngine.evaluateCandidate7542(unknownSellability, isLive = true).eligible)
        val generalist = SpecialistBrainBridge7542.coreOpinion7542(ts, emptyList(), 60, 60, 65.0)
        assertTrue(generalist.eligible)
        assertEquals("CORE_GENERALIST_NO_SPECIALIST_FIT", generalist.reason)
        assertFalse(SpecialistBrainBridge7542.coreOpinion7542(unsafe, emptyList(), 60, 60, 65.0).eligible)
        assertFalse(SpecialistBrainBridge7542.coreOpinion7542(ts, emptyList(), 39, 60, 65.0).eligible)
    }
}
