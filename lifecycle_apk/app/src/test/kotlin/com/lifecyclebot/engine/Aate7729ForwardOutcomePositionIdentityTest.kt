package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Aate7729ForwardOutcomePositionIdentityTest {
    @Before fun setUp() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = true)
        ForwardOutcomeModel.reset()
    }

    @After fun tearDown() {
        ForwardOutcomeModel.reset()
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
    }

    @Test fun rescans_cannot_replace_the_executed_candidates_training_signature() {
        ForwardOutcomeModel.stampDecision("mint7729", 100L, "MOONSHOT", 25, "A", "NORMAL", "LAUNCH", false)
        // A later rescan of the same mint sees a different score/setup.
        ForwardOutcomeModel.stampDecision("mint7729", 101L, "MOONSHOT", 85, "C", "NORMAL", "LATE", false)

        assertTrue(ForwardOutcomeModel.bindExecutedPosition("position7729", "mint7729", 100L, "MOONSHOT", false))
        assertTrue(ForwardOutcomeModel.recordOutcomeForPosition("position7729", -6.9))
        assertFalse("one canonical close must credit the position once", ForwardOutcomeModel.recordOutcomeForPosition("position7729", 40.0))

        val executed = ForwardOutcomeModel.cohortEvidence6911("MOONSHOT", 25, "NORMAL")
        val rescanned = ForwardOutcomeModel.cohortEvidence6911("MOONSHOT", 85, "NORMAL")
        assertEquals(1L, executed.samples)
        assertEquals(0L, rescanned.samples)
        assertEquals(-6.9, executed.expectedPnlPct, 0.0001)
    }

    @Test fun repeated_open_callback_is_idempotent_for_the_same_position() {
        ForwardOutcomeModel.stampDecision("mint7729", 100L, "MOONSHOT", 25, "A", "NORMAL", "LAUNCH", false)
        assertTrue(ForwardOutcomeModel.bindExecutedPosition("position7729", "mint7729", 100L, "MOONSHOT", false))
        assertTrue(ForwardOutcomeModel.bindExecutedPosition("position7729", "mint7729", 100L, "MOONSHOT", false))
        assertTrue(ForwardOutcomeModel.recordOutcomeForPosition("position7729", -6.9))
        assertEquals(1L, ForwardOutcomeModel.cohortEvidence6911("MOONSHOT", 25, "NORMAL").samples)
    }
}
