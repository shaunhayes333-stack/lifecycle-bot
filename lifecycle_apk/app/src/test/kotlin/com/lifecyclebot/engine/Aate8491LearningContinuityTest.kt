package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.lab.*
import com.lifecyclebot.v3.V3EngineManager
import com.lifecyclebot.v3.bridge.V3Adapter
import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import com.lifecyclebot.v3.scoring.ScoreComponent
import com.lifecyclebot.v4.meta.*
import org.junit.Assert.*
import org.junit.Test

class Aate8491LearningContinuityTest {
    @Test fun labExitKeepsExactMintCoverageAfterCandidateEviction() {
        val now = 100_000L
        val pos = LabPosition("lab8492", "strategy", "TOKEN", "heldMint", LabAssetClass.MEME, 1.0, 0.1, 1_000L)
        val mark = LabelMark8386(pos.mint, "", 1.2, now - 1_000L, now, "OFFWATCH")
        assertEquals(1.2, LlmLabEngine.exitPrice8492(pos, emptyList(), mark, now)!!, 0.0)
        assertNull(LlmLabEngine.exitPrice8492(pos, emptyList(), mark.copy(mint = "otherMint"), now))
        assertNull(LlmLabEngine.exitPrice8492(pos, emptyList(), mark.copy(observedAtMs = now - 15_001L), now))
        assertNull(LlmLabEngine.exitPrice8492(pos, emptyList(), mark.copy(observedAtMs = now + 1L), now))
        assertNull(LlmLabEngine.exitPrice8492(pos, emptyList(), mark.copy(source = "TOKEN_STATE"), now))
        val tick = LlmLabEngine.LabUniverseTick("TOKEN", pos.mint, LabAssetClass.MEME, 1.1, 70, "BULL", now)
        assertEquals(1.1, LlmLabEngine.exitPrice8492(pos, listOf(tick), null, now)!!, 0.0)
        assertNull(LlmLabEngine.exitPrice8492(pos, listOf(tick.copy(mint = "sameSymbolDifferentMint")), null, now))
        assertNull(LlmLabEngine.exitPrice8492(pos, listOf(tick.copy(observedAtMs8492 = 0L)), null, now))
    }

    private fun outcome(mint: String, position: String) = EducationSubLayerAI.TradeOutcomeData(
        mint = mint, symbol = "TOKEN", tokenName = "TOKEN", pnlPct = 20.0, holdTimeMinutes = 5.0,
        entryTimeMs = 1000L, exitReason = "TARGET", entryPhase = "BASE", tradingMode = "MOONSHOT",
        discoverySource = "CHAIN", setupQuality = "UNKNOWN", entryMcapUsd = 50000.0, exitMcapUsd = 60000.0,
        tokenAgeMinutes = 5.0, buyRatioPct = 50.0, volumeUsd = 1000.0, liquidityUsd = 10000.0,
        holderCount = 100, topHolderPct = 5.0, holderGrowthRate = 0.0, devWalletPct = 0.0,
        bondingCurveProgress = 0.0, rugcheckScore = 0.0, emaFanState = "UNKNOWN", entryScore = 70.0,
        priceFromAth = 0.0, maxGainPct = 24.0, maxDrawdownPct = 3.0, timeToPeakMins = 4.0,
        executionMode = "LIVE", positionId8491 = position,
    )

    private fun entry(mode: String = "LIVE") = EntryStrategySnapshot6450.Snapshot(
        positionId = "position8491", mint = "mint8491", entryLane = "MOONSHOT", entryStrategyPid = "setup",
        entryTactic = "BREAKOUT", entryRiskProfile = "", entryExitProfile = "runner", entrySource = "CHAIN",
        entryScore = 70, entryLiquiditySol = 20.0, entryMarketCapUsd = 50_000.0,
        entryTimestampMs = 1_000L, entryThresholdSnapshot = "", mode8090 = mode,
        educationScores8491 = "{\"MomentumPredictorAI\":8,\"LiquidityDepthAI\":-4}",
        v4EntryContext8491 = "{\"capturedAt\":1000,\"symbol\":\"TOKEN\",\"regime\":\"RISK_OFF\",\"session\":\"ASIA\",\"trust\":0.6,\"fragility\":0.2,\"narrative\":0.4,\"heat\":0.1,\"execution\":0.7}",
    )
    private fun event(mode: String = "LIVE") = CanonicalTradeFinalizedBus6450.Event(
        positionId = "position8491", mint = "mint8491", outcome = CanonicalTradeFinalizedBus6450.Outcome.WIN,
        netRealizedPnlSol = 0.01, grossRealizedPnlSol = 0.012, returnFraction = 0.2, netReturnPct = 20.0,
        feesSol = 0.002, entryLane = "MOONSHOT", entryStrategyPid = "setup", entryTactic = "BREAKOUT",
        exitReason = "TARGET", holdingTimeMs = 300_000, dataQuality = "confirmed_signature",
        priceIntegrity = "confirmed_signature", mode = mode, settledAtMs = 301_000,
        assetClassTag = "SOLANA_TOKEN", producedByVersion7103 = "5.0.8491",
        economicFinality = if (mode == "LIVE") "LIVE_FINALIZED" else "PAPER_SIMULATED",
        mfePct = 24.0, maePct = -3.0,
    )

    @Test fun entryComponentsAndV4ContextSurviveRestartWithoutLatestTokenState() {
        val original = entry()
        val restored = EntryStrategySnapshot6450.decodeSnapshot(EntryStrategySnapshot6450.encodeSnapshot(original).toString(), original.positionId)
        assertEquals(original, restored)
        assertEquals(mapOf("MomentumPredictorAI" to 8, "LiquidityDepthAI" to -4), EducationSubLayerAI.decodeEntryScores8491(restored.educationScores8491))
        val lesson = TradeLessonRecorder.canonicalMemeLesson8491(event(), restored)!!
        assertEquals(GlobalRiskMode.RISK_OFF, lesson.entryRegime)
        assertEquals(SessionContext.ASIA, lesson.entrySession)
        assertEquals(20.0, lesson.outcomePct, 0.0)
        assertEquals(24.0, lesson.mfePct, 0.0)
        assertTrue(lesson.entryContextObserved8491)
        assertEquals("MEME:LIVE:position8491", lesson.id)
    }

    @Test fun paperAndLiveLessonsCannotBorrowIdentityOrUnverifiedEconomics() {
        assertNull(TradeLessonRecorder.canonicalMemeLesson8491(event("PAPER"), entry("LIVE")))
        assertNull(TradeLessonRecorder.canonicalMemeLesson8491(event().copy(positionId = "other"), entry()))
        assertNull(TradeLessonRecorder.canonicalMemeLesson8491(event().copy(economicFinality = "LIVE_UNVERIFIED"), entry()))
        val paper = TradeLessonRecorder.canonicalMemeLesson8491(event("PAPER"), entry("PAPER"))!!
        assertEquals("PAPER", paper.executionRoute)
        assertEquals("MEME:PAPER:position8491", paper.id)
    }

    @Test fun retryAndPersistedReplayDoNotTeachTheSameMemePositionTwice() {
        TradeLessonRecorder.clear()
        StrategyTrustAI.clear()
        try {
            val lesson = TradeLessonRecorder.canonicalMemeLesson8491(event("PAPER"), entry("PAPER"))!!
            TradeLessonRecorder.record(lesson)
            TradeLessonRecorder.record(lesson.copy(outcomePct = 19.5))
            assertEquals(1, TradeLessonRecorder.getTotalLessons())
            val json = TradeLessonRecorder.exportState()
            TradeLessonRecorder.importState(json)
            assertEquals(1, TradeLessonRecorder.getTotalLessons())
            assertEquals(1, TradeLessonRecorder.getStrategyLessons("PAPER::MOONSHOT").size)
            val live = CrossTalkFusionEngine.trustForMode8491(StrategyTrustAI.getAllTrustScores(), "LIVE")
            val paper = CrossTalkFusionEngine.trustForMode8491(StrategyTrustAI.getAllTrustScores(), "PAPER")
            assertEquals(0, TradeLessonRecorder.getStrategyLessons("MOONSHOT").size)
            assertFalse(live.keys.any { it.startsWith("PAPER::") })
            assertTrue(paper.containsKey("MOONSHOT"))
        } finally { TradeLessonRecorder.clear(); StrategyTrustAI.clear(); CrossTalkFusionEngine.clear() }
    }

    @Test fun learnedAliasAndPaperNamespaceUseTheSameTrustRecord() {
        StrategyTrustAI.clear()
        try {
            val base = TradeLessonRecorder.canonicalMemeLesson8491(event(), entry())!!
            repeat(30) { i ->
                StrategyTrustAI.recordTrade(base.copy(id = "alias$i", strategy = "PAPER::BLUECHIP", timestamp = 1_000L + i))
            }
            assertEquals(StrategyTrustAI.getTrustScore("PAPER::BLUE_CHIP"), StrategyTrustAI.getTrustScore("PAPER::BLUECHIP"), 0.0)
            assertNotNull(StrategyTrustAI.getTrustRecord("PAPER::BLUECHIP"))
            assertEquals(0.5, StrategyTrustAI.getTrustScore("BLUECHIP"), 0.0)
        } finally { StrategyTrustAI.clear(); CrossTalkFusionEngine.clear() }
    }

    @Test fun unknownHistoryDoesNotBecomePerfectExecutionOrObservedExcursions() {
        val lesson = TradeLessonRecorder.canonicalMemeLesson8491(
            event().copy(mfePct = Double.NaN, maePct = Double.NaN), entry().copy(v4EntryContext8491 = ""))!!
        assertFalse(lesson.entryContextObserved8491)
        assertFalse(lesson.slippageObserved8491)
        assertFalse(lesson.mfeObserved8491)
        assertFalse(lesson.maeObserved8491)
        assertNull(TradeLessonRecorder.observedSlippage8491(null, 2.0))
        assertNull(TradeLessonRecorder.observedSlippage8491(0.0, 2.0))
        assertEquals(1.0, TradeLessonRecorder.observedSlippage8491(2.0, 1.98)!!, 1e-10)
    }

    @Test fun cortexProofRetainsExactFractionalOutcomeAcrossPersistence() {
        val id = "mint|MOONSHOT|1000"
        val b = CortexGradeBook8144()
        b.addDecision(CortexGradeRecord8144('D', id, "mint|MOONSHOT", 1000, "MOONSHOT", "BASE", "TREND", "EARLY", "LOW", "SMALL", 2, 30.123456789, true, true, false))
        val net = 12.3456789012345
        val gross = net + 5.0
        val proof = CortexOutcomeProof8395(id, 300000, 301000, net, gross,
            LabelMark8386("mint", "curve", 1.0, 1000, 1000, "CHAIN", decimals = 6, independentBasis = true),
            LabelMark8386("mint", "curve", 1 + gross / 100, 301000, 301000, "CHAIN", decimals = 6, independentBasis = true))
        assertTrue(b.labelOutcome8395(id, net, gross, net, true, 300000, 301000, 301000, proof).accepted)
        val restored = CortexGradeBook8144().apply { decode(b.encode()) }.decision(id)!!
        assertEquals(net.toBits(), restored.executablePct.toBits())
        val restoredProof = CortexOutcomeProof8395.decode8395(restored.firstOutcomeProof8395)!!
        assertTrue(restoredProof.matches8395(id, 300000, 301000, restored.executablePct, restored.firstGross8324))
    }

    @Test fun freshFlatReadIsDataButDefaultAndStalePressureAreNot() {
        val now = 1_000_000L
        val ts = TokenState("unique8491")
        assertNull(LaneInputTruth8236.buyPressure8491(ts, now))
        ts.lastBuyPressurePct = 50.0
        ts.buyPressureObservedAtMs8084 = now
        ts.lastPriceChange5m = 0.0
        ts.priceChangeObservedAtMs8084 = now
        assertEquals(50.0, LaneInputTruth8236.buyPressure8491(ts, now)!!, 0.0)
        assertEquals(0.0, LaneInputTruth8236.change5m8491(ts, now)!!, 0.0)
        assertTrue(V3Adapter.dataKnowledge7327(ts, now).momentum)
        assertNull(LaneInputTruth8236.buyPressure8491(ts, now + 120_001))
        assertFalse(LaneInputTruth8236.fresh8491(now + 1, now))
        assertFalse(ToolkitSignalSheet.reusableDuringRefresh8491(17_000))
        assertFalse(ToolkitSignalSheet.reusableDuringRefresh8491(-1))
        assertTrue(ToolkitSignalSheet.reusableDuringRefresh8491(1_000))
    }

    @Test fun payoffUsesActualLossMagnitudeAndEntryKeysKeepAccountsApart() {
        assertEquals(3.0, V3EngineManager.observedPayoff8491(30.0, -10.0), 0.0)
        assertEquals(1.0, V3EngineManager.observedPayoff8491(30.0, Double.NaN), 0.0)
        assertNotEquals(EducationSubLayerAI.entrySnapshotKey8491("mint", "live"), EducationSubLayerAI.entrySnapshotKey8491("mint", "paper"))
    }

    @Test fun lateSettlementCannotReplaceTheNextTradesEntryVotes() {
        val mint = "lateSettlement8491"
        val sealed = entry().copy(positionId = "oldPosition8491", mint = mint)
        EntryStrategySnapshot6450.setEntry(sealed)
        EducationSubLayerAI.recordEntryScores(mint, listOf(ScoreComponent("MomentumPredictorAI", -9, "new decision")), null, "LIVE")
        val newerVotes = EducationSubLayerAI.entryScoresJson8491(mint, "LIVE")
        val oldOutcome = outcome(mint, sealed.positionId)
        EducationSubLayerAI.restoreSealedScores8491(oldOutcome)
        assertEquals(8, EducationSubLayerAI.outcomeEntryScores8491(oldOutcome)["MomentumPredictorAI"])
        assertEquals("restoring an old close must leave the next entry untouched", newerVotes,
            EducationSubLayerAI.entryScoresJson8491(mint, "LIVE"))
        assertTrue("a missing seal must not borrow the latest mint scores",
            EducationSubLayerAI.outcomeEntryScores8491(outcome(mint, "missingPosition8491")).isEmpty())
    }

    @Test fun cortexSymbolReaderKeepsPaperSignalsOutOfLiveDecisions() {
        val oldMode = RuntimeModeAuthority.current()
        CrossTalkFusionEngine.clear()
        try {
            val now = System.currentTimeMillis()
            val signal = AATESignal(source = "test8491", market = "MEME", symbol = "TOKEN8491",
                confidence = 0.8, direction = "LONG", horizonSec = 60, timestamp = now, evidenceMode8491 = "PAPER")
            CrossTalkFusionEngine.publish(signal)
            RuntimeModeAuthority.publishConfig(false, false)
            assertTrue(CrossTalkFusionEngine.getSignalsForSymbol("TOKEN8491").isEmpty())
            assertTrue(CrossTalkFusionEngine.getSignalsForMarket("MEME").isEmpty())
            assertNull(CrossTalkFusionEngine.getLatestSignal("test8491"))
            val live = signal.copy(evidenceMode8491 = "LIVE", direction = "SHORT")
            CrossTalkFusionEngine.publish(live)
            CrossTalkFusionEngine.publish(signal)
            assertEquals("SHORT", CrossTalkFusionEngine.getLatestSignal("test8491")?.direction)
            RuntimeModeAuthority.publishConfig(true, false)
            assertEquals(2, CrossTalkFusionEngine.getSignalsForSymbol("TOKEN8491").size)
            assertFalse(CrossTalkFusionEngine.signalVisible8491(signal.copy(timestamp = now + 1), "PAPER", now))
            assertTrue(CrossTalkFusionEngine.signalVisible8491(signal.copy(evidenceMode8491 = ""), "LIVE", now))
        } finally {
            RuntimeModeAuthority.publishConfig(oldMode.authority == RuntimeModeAuthority.Mode.PAPER, oldMode.autoTrade)
            CrossTalkFusionEngine.clear()
        }
    }

    @Test fun restoredCloudAggregateCannotUndoSettledLocalLearning() {
        val oldMode = RuntimeModeAuthority.current()
        StrategyTrustAI.clear()
        try {
            RuntimeModeAuthority.publishConfig(false, false)
            val lesson = TradeLessonRecorder.canonicalMemeLesson8491(event(), entry())!!
            repeat(30) { i -> StrategyTrustAI.recordTrade(lesson.copy(id = "restore$i", strategy = "BLUECHIP", timestamp = 1000L + i)) }
            val learned = StrategyTrustAI.getTrustRecord("BLUECHIP")!!
            StrategyTrustAI.restoreTrustRecord(learned.copy(strategyName = "BLUECHIP", trustScore = 0.0, trustLevel = TrustLevel.DISTRUSTED))
            assertEquals(learned, StrategyTrustAI.getTrustRecord("BLUE_CHIP"))
            CrossTalkFusionEngine.init(strategyTrust = StrategyTrustAI)
            CrossTalkFusionEngine.fuse()
            assertEquals(CrossTalkFusionEngine.memeShapeMultiplier6878(null, "BLUE_CHIP"),
                CrossTalkFusionEngine.memeShapeMultiplier6878(null, "BLUECHIP"), 0.0)
        } finally {
            StrategyTrustAI.clear(); CrossTalkFusionEngine.clear()
            RuntimeModeAuthority.publishConfig(oldMode.authority == RuntimeModeAuthority.Mode.PAPER, oldMode.autoTrade)
        }
    }

    @Test fun replacingReviewedHypothesisRefreshesItsAppliedSizeBias() {
        AsyncStrategyLab.reset()
        try {
            assertTrue(AsyncStrategyLab.submitBackgroundHypothesis(AsyncStrategyLab.Provider.LOCAL_ONLY,
                "MOONSHOT", "RUNNER_CAPTURE", "SIZE_UP", "revert on loss", symbolicChecked = true))
            assertEquals(1.25, AsyncStrategyLab.reviewedSizeBias("MOONSHOT", 70, "TREND"), 0.0)
            assertTrue(AsyncStrategyLab.submitBackgroundHypothesis(AsyncStrategyLab.Provider.LOCAL_ONLY,
                "MOONSHOT", "RUNNER_CAPTURE", "SIZE_UP", "revert on loss", symbolicChecked = false))
            assertEquals(1.0, AsyncStrategyLab.reviewedSizeBias("MOONSHOT", 70, "TREND"), 0.0)
        } finally { AsyncStrategyLab.reset() }
    }

    @Test fun failedLessonCheckpointRetriesWithoutTeachingTwice() {
        TradeLessonRecorder.clear(); StrategyTrustAI.clear()
        try {
            val e = event("PAPER")
            val seal = entry("PAPER")
            assertFalse(TradeLessonRecorder.acceptCanonicalMeme8492(e, seal) { false })
            assertEquals(1, TradeLessonRecorder.getTotalLessons())
            var persisted = ""
            assertTrue(TradeLessonRecorder.acceptCanonicalMeme8492(e, seal) { id -> persisted = id; true })
            assertEquals("MEME:PAPER:position8491", persisted)
            assertEquals(1, TradeLessonRecorder.getTotalLessons())
            val state = TradeLessonRecorder.exportState()
            TradeLessonRecorder.clear(); StrategyTrustAI.clear()
            TradeLessonRecorder.importState(state)
            assertTrue(TradeLessonRecorder.acceptCanonicalMeme8492(e, seal) { true })
            assertEquals(1, TradeLessonRecorder.getTotalLessons())
        } finally { TradeLessonRecorder.clear(); StrategyTrustAI.clear(); CrossTalkFusionEngine.clear() }
    }
}
