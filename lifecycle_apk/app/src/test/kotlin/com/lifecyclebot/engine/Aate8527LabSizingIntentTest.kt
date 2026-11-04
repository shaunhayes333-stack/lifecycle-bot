package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8527LabSizingIntentTest {
    @Before fun reset() = AsyncStrategyLab.reset()
    @After fun cleanup() = AsyncStrategyLab.reset()

    private fun bias(mode: String = "PAPER") = LearningEnvironment7835.withMode(mode) {
        AsyncStrategyLab.reviewedSizeBias("MOONSHOT", 70, "TREND")
    }

    private fun submit(proposal: String, metric: String = "net after-cost return", reviewed: Boolean = true): Boolean =
        AsyncStrategyLab.submitBackgroundHypothesis(AsyncStrategyLab.Provider.LOCAL_ONLY,
            "MOONSHOT", metric, proposal, "revert if after-cost expectancy deteriorates", reviewed)

    @Test fun proseCannotBecomeASizingCommandThroughSubstringMatches() {
        for (note in listOf("bounded compressed entry comparison", "soft runner_capture hold bias",
            "bounded focus on exits", "avoid unnecessary size reductions",
            "bounded drawdown observation", "do not SIZE_UP", "SIZE_DOWN then SIZE_UP")) {
            reset()
            assertTrue(submit(note))
            assertEquals(note, 1.0, bias(), 0.0)
            assertEquals(note, 1.0, bias("LIVE"), 0.0)
            assertEquals(1, AsyncStrategyLab.pendingForReview().size)
        }
    }

    @Test fun MetricWordsAreNotPositionSizeActions() {
        assertTrue(submit("bounded exit time experiment", "PRESS COMPOUND RUNNER_CAPTURE DRAWDOWN AVOID"))
        assertEquals(1.0, bias(), 0.0)
    }

    @Test fun ExplicitReviewedCommandsKeepTheirExistingModeAndMagnitudeLimits() {
        assertTrue(submit("  size_up  "))
        assertEquals(1.25, bias(), 0.0)
        assertEquals(1.25, bias("SHADOW"), 0.0)
        assertEquals(1.0, bias("LIVE"), 0.0)
        assertEquals(1.0, bias("UNKNOWN"), 0.0)
        reset()
        assertTrue(submit("RISK_DOWN"))
        assertEquals(0.80, bias(), 0.0)
        assertEquals(0.80, bias("LIVE"), 0.0)
    }

    @Test fun UnreviewedTypedIntentIsStoredWithoutActuationAndReviewPromotesOnlyThatIntent() {
        assertTrue(submit("SIZE_DOWN", reviewed = false))
        assertEquals(1.0, bias("LIVE"), 0.0)
        assertTrue(AsyncStrategyLab.markSymbolicReviewed(AsyncStrategyLab.pendingForReview().single().id))
        assertEquals(0.80, bias("LIVE"), 0.0)
        reset()
        assertTrue(submit("bounded compressed runner hold", reviewed = false))
        assertTrue(AsyncStrategyLab.markSymbolicReviewed(AsyncStrategyLab.pendingForReview().single().id))
        assertEquals(1.0, bias(), 0.0)
    }

    @Test fun CriticWrapperPreservesExplicitActionButCannotCreateOneFromItsSummary() {
        assertTrue(MultiAgentCriticStack.reviewAndSubmit("MOONSHOT",
            "Avoid drawdown; compressed runner notes, not a sizing instruction", "SIZE_UP",
            "lower drawdown", "rollback after loss"))
        assertEquals(1.25, bias(), 0.0)
        reset()
        assertTrue(MultiAgentCriticStack.reviewAndSubmit("MOONSHOT",
            "SIZE_UP historical proposal", "bounded exit time experiment",
            "RUNNER_CAPTURE", "rollback after loss"))
        assertEquals(1.0, bias(), 0.0)
    }

    @Test fun StructuredIntentSurvivesRoundTripWithoutReinterpretingResearchText() {
        assertTrue(AsyncStrategyLab.submitBackgroundHypothesis(AsyncStrategyLab.Provider.LOCAL_ONLY,
            "MOONSHOT", "drawdown", "bounded experiment explanation with no command",
            "rollback", true, AsyncStrategyLab.SizingAction8527.SIZE_UP))
        val state = AsyncStrategyLab.exportState()
        reset()
        AsyncStrategyLab.importState(state)
        assertEquals(AsyncStrategyLab.SizingAction8527.SIZE_UP,
            AsyncStrategyLab.pendingForReview().single().sizingAction8527)
        assertEquals(1.25, bias(), 0.0)
        assertEquals(1.0, bias("LIVE"), 0.0)
    }

    private fun legacy(proposal: String, action: String? = null): String {
        val row = JSONObject().put("id", "legacy").put("provider", "LOCAL_ONLY")
            .put("lane", "MOONSHOT").put("metric", "DRAWDOWN")
            .put("proposal", proposal).put("rollback", "rollback")
            .put("backgroundOnly", true).put("symbolicChecked", true)
        if (action != null) row.put("sizingAction8527", action)
        return JSONObject().put("accepted", JSONArray().put(row)).toString()
    }

    @Test fun LegacyResearchIsPreservedButOnlyExactCommandsMigrateAsSizingIntent() {
        AsyncStrategyLab.importState(legacy("bounded compressed launch research"))
        assertEquals(1, AsyncStrategyLab.pendingForReview().size)
        assertEquals("bounded compressed launch research", AsyncStrategyLab.pendingForReview().single().proposal)
        assertEquals(1.0, bias(), 0.0)
        AsyncStrategyLab.importState(legacy("SIZE_UP"))
        assertEquals(1.25, bias(), 0.0)
        AsyncStrategyLab.importState(legacy("SIZE_UP", "UNRECOGNIZED"))
        assertEquals(1.0, bias(), 0.0)
        AsyncStrategyLab.importState(legacy("SIZE_DOWN", "NONE"))
        assertEquals(1.0, bias("LIVE"), 0.0)
    }

    @Test fun MultipleExplicitActionsCannotEscapeExistingNonzeroBounds() {
        for (i in 1..8) assertTrue(AsyncStrategyLab.submitBackgroundHypothesis(
            AsyncStrategyLab.Provider.LOCAL_ONLY, "MOONSHOT", "net return",
            "bounded experiment $i", "rollback", true, AsyncStrategyLab.SizingAction8527.SIZE_DOWN))
        assertEquals(0.60, bias("LIVE"), 0.0)
        reset()
        for (i in 1..8) assertTrue(AsyncStrategyLab.submitBackgroundHypothesis(
            AsyncStrategyLab.Provider.LOCAL_ONLY, "MOONSHOT", "net return",
            "bounded experiment $i", "rollback", true, AsyncStrategyLab.SizingAction8527.SIZE_UP))
        assertEquals(1.55, bias(), 0.0)
        assertEquals(1.0, bias("LIVE"), 0.0)
    }
}
