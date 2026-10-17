package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.StrategyIdentity8153
import com.lifecyclebot.engine.truth.StrategyRank8153
import org.junit.Assert.*
import org.junit.Test

class Aate8198RapidPivotTest {
    private val at = 1_800_000_000_000L
    private val fired = listOf("FAILING", "PULLBACK")

    @Test fun refusal_and_defer_do_not_repin_the_rejected_setup() {
        val b = StrategyIdentity8153
        b.record8153("pivot-rejected8198", "MOONSHOT", "FAILING", "ADMIT", at, "LIVE", 10, 1)
        assertEquals("FAILING", b.heldFor8153("pivot-rejected8198", "MOONSHOT", at + 1, fired, "LIVE", 10, 1))
        for (basis in listOf("REFUSE", "DEFER_DATA")) {
            b.record8153("pivot-rejected8198", "MOONSHOT", "FAILING", basis, at + 2, "LIVE", 10, 1, false)
            assertNull(b.heldFor8153("pivot-rejected8198", "MOONSHOT", at + 3, fired, "LIVE", 10, 1))
        }
    }

    @Test fun learning_revision_reopens_selection_immediately_not_after_ninety_seconds() {
        val b = StrategyIdentity8153
        b.record8153("pivot-revision8198", "MOONSHOT", "FAILING", "old", at, "LIVE", 11, 1)
        assertNull(b.heldFor8153("pivot-revision8198", "MOONSHOT", at + 1, fired, "LIVE", 11, 2))
        val choices = listOf(
            StrategyRank8153.Option8153("FAILING", 20, -8.0, 1.0),
            StrategyRank8153.Option8153("PULLBACK", 12, 8.0, 1.0),
        )
        val selected = StrategyRank8153.select8153("FAILING", choices, 1.0)
        assertEquals("PULLBACK", selected.id)
        assertTrue(selected.overDefault)
        b.record8153("pivot-revision8198", "MOONSHOT", selected.id, selected.basis, at + 1, "LIVE", 11, 2)
        assertEquals("PULLBACK", b.heldFor8153("pivot-revision8198", "MOONSHOT", at + 2, fired, "LIVE", 11, 2))
    }

    @Test fun paper_live_generations_and_specialist_lanes_do_not_share_a_strategy_pin() {
        val b = StrategyIdentity8153
        b.record8153("pivot-scope8198", "MOONSHOT", "PULLBACK", "live", at, "LIVE", 12, 1)
        assertNull(b.heldFor8153("pivot-scope8198", "MOONSHOT", at + 1, fired, "PAPER", 12, 1))
        assertNull(b.heldFor8153("pivot-scope8198", "MOONSHOT", at + 1, fired, "LIVE", 13, 1))
        assertNull(b.heldFor8153("pivot-scope8198", "QUALITY", at + 1, fired, "LIVE", 12, 1))
        assertEquals("PULLBACK", b.heldFor8153("pivot-scope8198", "MOONSHOT", at + 1, fired, "LIVE", 12, 1))
    }

    @Test fun sealed_execution_evidence_remains_immutable_while_unsealed_choices_reopen() {
        val h = StrategyIdentity8153.Held8153("PULLBACK", "ADMIT", at, "LIVE", 14, 1)
        assertTrue(StrategyIdentity8153.freshEvidence8198(h, 1, false))
        assertFalse(StrategyIdentity8153.freshEvidence8198(h, 2, false))
        assertTrue(StrategyIdentity8153.freshEvidence8198(h, 2, true))
        assertEquals("PULLBACK", h.setup)
    }

    @Test fun old_refusal_cannot_clear_a_newer_generation_or_another_mode() {
        val b = StrategyIdentity8153
        b.record8153("pivot-clear8198", "EXPRESS", "PULLBACK", "new", at, "LIVE", 16, 2)
        b.record8153("pivot-clear8198", "EXPRESS", "FAILING", "old refusal", at + 1, "LIVE", 15, 1, false)
        b.record8153("pivot-clear8198", "EXPRESS", "FAILING", "paper refusal", at + 1, "PAPER", 16, 2, false)
        assertEquals("PULLBACK", b.heldFor8153("pivot-clear8198", "EXPRESS", at + 2, fired, "LIVE", 16, 2))
    }
}
