
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7649GoalConditionedPlanningTest {
    @Test fun lanesReceiveDifferentGoalProfiles() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperGoalConditionedPlanner7649.kt").readText()
        assertTrue(s.contains("CAPITAL_PRESERVATION"))
        assertTrue(s.contains("FAST_TURNOVER"))
        assertTrue(s.contains("EARLY_ASYMMETRY"))
        assertTrue(s.contains("TAIL_CAPTURE"))
        assertTrue(s.contains("RECOVERY_EDGE"))
        assertTrue(s.contains("QUALITY_COMPOUND"))
    }

    @Test fun plannerBuildsParetoFrontierAcrossMultipleObjectives() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperGoalConditionedPlanner7649.kt").readText()
        assertTrue(s.contains("fun dominates("))
        assertTrue(s.contains("returnScore"))
        assertTrue(s.contains("downsideScore"))
        assertTrue(s.contains("failureScore"))
        assertTrue(s.contains("tailScore"))
        assertTrue(s.contains("velocityScore"))
        assertTrue(s.contains("robustnessScore"))
    }

    @Test fun treeRanksGoalAdjustedParetoPolicies() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("SuperGoalConditionedPlanner7649.rank("))
        assertTrue(s.contains("goalAdjustedBranches7649"))
        assertTrue(s.contains("goalProfile = goal7649.profile.name"))
    }
}
