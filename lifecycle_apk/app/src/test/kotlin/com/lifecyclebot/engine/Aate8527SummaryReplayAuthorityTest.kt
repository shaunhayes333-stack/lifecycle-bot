package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.LaneExitTuner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8527SummaryReplayAuthorityTest {
    @Before fun resetBefore() { CounterfactualReplayEngine.reset(); LaneExitTuner.reset() }
    @After fun resetAfter() { CounterfactualReplayEngine.reset(); LaneExitTuner.reset() }

    @Test fun identicalExtremaCannotProveWhichExitFilledFirst() {
        val stopFirst = listOf(0.0, -20.0, 50.0, 0.0)
        val tpFirst = listOf(0.0, 50.0, -20.0, 0.0)
        // Fixture oracle, not a production fill model: even ideal fills depend on event order.
        fun firstTouch(path: List<Double>): Double =
            path.drop(1).first { it <= -10.0 || it >= 30.0 }.let { if (it < 0.0) -10.0 else 30.0 }
        assertEquals(stopFirst.maxOrNull(), tpFirst.maxOrNull())
        assertEquals(stopFirst.minOrNull(), tpFirst.minOrNull())
        assertEquals(stopFirst.last(), tpFirst.last(), 0.0)
        assertEquals(-10.0, firstTouch(stopFirst), 0.0)
        assertEquals(30.0, firstTouch(tpFirst), 0.0)
        val shape = LaneStrategyEvaluator.ExitProfile("both", -10.0, null, 30.0)
        val summary = shape.summarize8527(0.0, 50.0, -20.0)
        assertEquals(LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS, summary.evidence)
        assertEquals(summary, shape.summarize8527(tpFirst.last(), tpFirst.maxOrNull()!!, tpFirst.minOrNull()!!))
    }

    @Test fun existingChronologicalRunnerReplayStillDistinguishesOrder() {
        val stopFirst = listOf(1.0, 0.5, 2.0, 1.0)
        val peakFirst = listOf(1.0, 2.0, 0.5, 1.0)
        fun replay(prices: List<Double>) = RunnerReplay8153.replay8153(
            prices.map { RunnerReplay8153.Mark8153(it) }, sealedStyle = "SCALP", sellCostPct = 0.0,
        )
        val a = replay(stopFirst)
        val b = replay(peakFirst)
        assertTrue(a.exits.isNotEmpty())
        assertTrue(b.exits.isNotEmpty())
        assertTrue("Chronological fills must not collapse to the same unordered-extrema estimate", kotlin.math.abs(a.net - b.net) > 0.01)
    }

    @Test fun labelsCannotPromoteHypotheticalProfilesIntoSupportedRanking() {
        val fakeActual = LaneStrategyEvaluator.ExitProfile("CURRENT_ACTUAL", -5.0, null, null)
        val actual = LaneStrategyEvaluator.ExitProfile("unchanged", null, null, null)
        val noTrade = LaneStrategyEvaluator.ExitProfile("reference", null, null, null, noTrade = true)
        assertEquals(LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS, fakeActual.evidence8527)
        assertEquals(LaneStrategyEvaluator.Evidence8527.CANONICAL_REALIZED, actual.evidence8527)
        assertEquals(LaneStrategyEvaluator.Evidence8527.NO_TRADE_REFERENCE, noTrade.evidence8527)
        val rows = LaneStrategyEvaluator.evaluateOutcomes8527(
            (1..5).map { outcome(it, TradeEnvironment.PAPER, -50.0) },
            listOf(fakeActual, actual, noTrade),
        )
        assertTrue(rows.any { it.evidence8527 == LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS && it.avgPct > -50.0 })
        val supported = LaneStrategyEvaluator.supportedBaselines8527(rows).values.single()
        assertEquals("unchanged", supported.profile)
        assertEquals(-50.0, supported.avgPct, 0.0)
        assertTrue(LaneStrategyEvaluator.supportedBaselines8527(rows.filter { it.profile != "unchanged" }).isEmpty())
    }

    @Test fun summariesKeepEnvironmentAndActualCashEvidenceSeparate() {
        val input = (1..5).map { outcome(it, TradeEnvironment.PAPER, 10.0) } +
            (6..10).map { outcome(it, TradeEnvironment.LIVE, -20.0) }
        val rows = LaneStrategyEvaluator.evaluateOutcomes8527(input)
        val actual = LaneStrategyEvaluator.supportedBaselines8527(rows)
        assertEquals(setOf("PAPER|MOONSHOT", "LIVE|MOONSHOT"), actual.keys)
        assertEquals(5, actual.getValue("PAPER|MOONSHOT").n)
        assertEquals(10.0, actual.getValue("PAPER|MOONSHOT").avgPct, 0.0)
        assertEquals(-20.0, actual.getValue("LIVE|MOONSHOT").avgPct, 0.0)
        // A recorded cash amount remains authoritative; do not re-estimate it from percentage.
        val cash = LaneStrategyEvaluator.evaluateOutcomes8527(
            (1..5).map { outcome(it, TradeEnvironment.LIVE, 10.0).copy(realizedPnlSol = 0.0123) },
        ).single { it.evidence8527 == LaneStrategyEvaluator.Evidence8527.CANONICAL_REALIZED }
        assertEquals(0.0615, cash.netSol, 1e-12)
    }

    @Test fun nonTrainablePartialOrUnclosedRowsCannotBecomeRecordedBaselines() {
        val rejected = (1..5).map { outcome(it, TradeEnvironment.LIVE, 10.0).copy(isTrainable = false) } +
            (6..10).map { outcome(it, TradeEnvironment.LIVE, 10.0).copy(isPartial = true) } +
            (11..15).map { outcome(it, TradeEnvironment.LIVE, 10.0).copy(exitTimeMs = null) }
        assertTrue(LaneStrategyEvaluator.evaluateOutcomes8527(rejected).isEmpty())
    }

    @Test fun replayResearchIsRetainedButCoverageIsNotConfidenceOrAuthority() {
        seedResearch()
        val hint = CounterfactualReplayEngine.mctsExitPolicyHint("MOONSHOT")!!
        assertTrue(hint.meanHypotheticalDeltaPct > 0.0)
        assertEquals(1.0, hint.summarySupportShare, 0.0)
        assertEquals(8, hint.sampleCount)
        assertEquals(LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS, hint.evidence8527)
        assertEquals("UNORDERED_EXTREMA_ENVIRONMENT_UNKNOWN", hint.provenance8527)
        assertTrue(CounterfactualReplayEngine.bestMissedAlternatives("MOONSHOT").isNotEmpty())
        assertTrue(CounterfactualReplayEngine.policyHints("MOONSHOT").contains("authority=none"))
        assertFalse(CounterfactualReplayEngine.MctsExitPolicyHint::class.java.declaredFields.any { it.name == "confidence" })
    }

    @Test fun legacyPersistedResearchNeverRegainsExecutableAuthority() {
        seedResearch()
        val legacy = JSONObject(CounterfactualReplayEngine.exportState()).apply { remove("evidence8527") }.toString()
        CounterfactualReplayEngine.reset()
        CounterfactualReplayEngine.importState(legacy)
        val restored = CounterfactualReplayEngine.mctsExitPolicyHint("MOONSHOT")!!
        assertEquals(8, restored.sampleCount)
        assertEquals(LaneStrategyEvaluator.Evidence8527.SUMMARY_HYPOTHESIS, restored.evidence8527)
        assertTrue(restored.meanHypotheticalDeltaPct > 0.0)
    }

    @Test fun summaryCoverageCannotChangeAnyLivePlannerPriorOrEvidenceFamily() {
        val original = snapshot()
        val inflated = original.copy(summaryReplay8527 = CounterfactualReplayEngine.MctsExitPolicyHint(
            CounterfactualReplayEngine.AlternativeKind.TRAIL_RUNNER, 250_000.0, 1.0, 500,
        ))
        for (policy in SuperPolicyTree7638.Policy.values()) {
            assertEquals(original.evidenceTopology7651(policy), inflated.evidenceTopology7651(policy))
            assertEquals(original.policyPrior(policy), inflated.policyPrior(policy), 0.0)
            assertFalse(inflated.evidenceTopology7651(policy).familyUtility.containsKey(SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY))
        }
        assertTrue(inflated.contributionTag().contains("noAuthority"))
        // This is not a zeroed-intelligence stub: actual native and canonical expert evidence remain represented.
        val supported = original.copy(specialistEligible = true, specialistScore = 90, specialistConfidence = 90)
            .evidenceTopology7651(SuperPolicyTree7638.Policy.CONVICTION_RUNNER)
        assertTrue(supported.familyUtility.containsKey(SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST))
        assertTrue(supported.decorrelatedUtility > 0.0)
    }

    @Test fun paperTunerKeepsMatureCanonicalLearningWithoutSummaryBootstrapActuation() {
        seedResearch()
        LearningEnvironment7835.withMode("PAPER") {
            assertEquals(1.0, LaneExitTuner.getTpMult("MOONSHOT"), 0.0)
            assertEquals(1.0, LaneExitTuner.getSlMult("MOONSHOT"), 0.0)
        }
        // Persisted canonical evidence still controls the base after the unchanged eight-close maturity rule.
        val window = JSONArray()
        repeat(8) { window.put(JSONObject().put("p", 20.0).put("k", 25.0).put("w", true).put("s", false)) }
        val state = JSONObject().put("PAPER|MOONSHOT",
            JSONObject().put("v", 7835).put("tp", 1.2).put("sl", 1.1).put("life", 8).put("win", window))
        LaneExitTuner.importState(state.toString())
        LearningEnvironment7835.withMode("PAPER") {
            assertEquals(1.2, LaneExitTuner.getTpMult("MOONSHOT"), 0.0)
            assertEquals(1.1, LaneExitTuner.getSlMult("MOONSHOT"), 0.0)
        }
    }

    @Test fun canonicalDeliveriesStillTrainTheTuner() {
        LearningEnvironment7835.withMode("PAPER", canonical = true) {
            repeat(10) { LaneExitTuner.recordClose("MOONSHOT", 20.0, 120.0, "TAKE_PROFIT") }
            assertTrue("Real canonical give-back evidence must still tune exits", LaneExitTuner.getTpMult("MOONSHOT") > 1.0)
        }
        val trained = JSONObject(LaneExitTuner.exportState()).getJSONObject("PAPER|MOONSHOT")
        assertEquals(10, trained.getJSONArray("win").length())
        assertEquals(10L, trained.getLong("life"))
    }

    @Test fun sourceConsumersCannotRecreateTheRemovedUnsupportedAdapter() {
        val tuner = File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        assertFalse(tuner.contains("replayBiasByLane"))
        assertFalse(tuner.contains("refreshReplayBiasAsync"))
        assertFalse(tuner.contains("LaneStrategyEvaluator.bestPerLane("))
        val context = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        assertFalse(context.contains("mctsConfidence"))
        assertFalse(context.contains("Family.COUNTERFACTUAL_REPLAY"))
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/TuningActivity.kt").readText()
        assertTrue(ui.contains("Lane Exit-Shape Research"))
        assertTrue(ui.contains("RESEARCH ONLY"))
        assertFalse(ui.contains("STOP TRADING"))
        assertFalse(ui.contains("SWITCH →"))
    }

    private fun seedResearch() {
        repeat(8) {
            CounterfactualReplayEngine.recordTerminalTrade("MOONSHOT", "case$it", -20.0, 200.0, -30.0, 60L + it)
        }
    }

    private fun outcome(id: Int, environment: TradeEnvironment, pnl: Double) = CanonicalTradeOutcome(
        tradeId = "summary8527-$id", mint = "mint-$id", symbol = "T$id", assetClass = AssetClass.MEME,
        mode = TradeMode.MOONSHOT, source = TradeSource.MOONSHOT, environment = environment,
        entryTimeMs = id.toLong(), exitTimeMs = id + 1000L, entryPrice = 1.0, exitPrice = 1.0 + pnl / 100.0,
        entrySol = 1.0, exitSol = 1.0 + pnl / 100.0, realizedPnlSol = pnl / 100.0, realizedPnlPct = pnl,
        maxGainPct = 200.0, maxDrawdownPct = -60.0, holdSeconds = 60L,
        result = if (pnl > 0.0) TradeResult.WIN else TradeResult.LOSS,
        executionResult = ExecutionResult.EXECUTED, closeReason = "TAKE_PROFIT", isTrainable = true,
    )

    private fun snapshot() = ExistingIntelligenceContext7650.Snapshot(
        lane = "MOONSHOT", specialistEligible = null, specialistScore = null, specialistConfidence = null,
        ultimateScoreBias = 0, ultimateSizeMult = 1.0, legacyConsensusMult = 1.0,
        hypothesisSizeBias = 1.0, reviewedLabBias = 1.0, summaryReplay8527 = null,
        expertPrior7813 = ExpertTraderKnowledge7813.Prior.NEUTRAL, sources = emptySet(),
        estate7654 = SuperIntelligenceEstate7654.Snapshot(
            crossTalk = null, llm = null, arbScore = null, arbConfidence = null, arbExpectedMovePct = null,
            arbType = null, sourceLearning = null, layerEstate = LayerBrain.EstateSnapshot7654(0,0,0,0,0,0L,0,0),
            smartSystemsTotal = 0, smartSystemsActive = 0, smartSystemsInterfaceUsed = 0,
            coverageFamilies = 0, coverageDirectional = 0,
        ),
    )
}
