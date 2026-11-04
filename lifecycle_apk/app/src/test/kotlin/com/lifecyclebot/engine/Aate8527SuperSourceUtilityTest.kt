package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class Aate8527SuperSourceUtilityTest {
    private fun snapshot(wr: Double, mean: Double, n: Long = 200L) =
        SuperIntelligenceEstate7654.Snapshot(
            crossTalk = null, llm = null, arbScore = null, arbConfidence = null,
            arbExpectedMovePct = null, arbType = null,
            sourceLearning = ScannerSourceBrain.SourceSnapshot7658(
                source = "SOURCE_8527", samples = n, winRate = wr,
                avgPnlPct = mean, authority = ScannerSourceBrain.AuthorityTier.LEARNED),
            layerEstate = LayerBrain.EstateSnapshot7654(0, 0, 0, 0, 0, 0L, 0, 0),
            smartSystemsTotal = 0, smartSystemsActive = 0, smartSystemsInterfaceUsed = 0,
            coverageFamilies = 0, coverageDirectional = 0,
        )

    @Test fun rareProfitableRunnersKeepTheirPositiveSignInEveryEntryPolicy() {
        val state = snapshot(0.10, 1.10)
        for (policy in SuperPolicyTree7638.Policy.entries) {
            val utility = state.sourceLearningUtility(policy)
            if (policy == SuperPolicyTree7638.Policy.WAIT_REASSESS) assertTrue(utility < 0.0)
            else assertTrue("Positive net source must not become bearish for " + policy, utility > 0.0)
        }
    }

    @Test fun frequentWinsWithNegativeMeanCannotBecomePositiveEntryEvidence() {
        val state = snapshot(0.90, -1.10)
        for (policy in SuperPolicyTree7638.Policy.entries) {
            val utility = state.sourceLearningUtility(policy)
            if (policy == SuperPolicyTree7638.Policy.WAIT_REASSESS) assertTrue(utility > 0.0)
            else assertTrue("Negative net source must not become bullish for " + policy, utility < 0.0)
        }
    }

    @Test fun invalidThinAndBreakEvenSourcesRemainNeutral() {
        val states = listOf(
            snapshot(0.90, 0.0), snapshot(0.10, 0.0),
            snapshot(Double.NaN, 10.0), snapshot(0.90, Double.NaN),
            snapshot(Double.POSITIVE_INFINITY, 10.0),
            snapshot(0.90, Double.NEGATIVE_INFINITY), snapshot(0.90, 10.0, 19L),
        )
        for (state in states) for (policy in SuperPolicyTree7638.Policy.entries) {
            assertEquals(0.0, state.sourceLearningUtility(policy), 0.0)
        }
    }

    @Test fun strongerNetEvidenceRemainsMonotoneWhenWinRateAgrees() {
        for (policy in SuperPolicyTree7638.Policy.entries) {
            val mild = snapshot(0.60, 1.0).sourceLearningUtility(policy)
            val strong = snapshot(0.60, 10.0).sourceLearningUtility(policy)
            if (policy == SuperPolicyTree7638.Policy.WAIT_REASSESS) assertTrue(strong < mild)
            else assertTrue(strong > mild)
        }
    }
}
