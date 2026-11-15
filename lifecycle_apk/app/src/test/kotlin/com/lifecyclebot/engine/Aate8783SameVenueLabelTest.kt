package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.8783 — code audit (data P1-3): a label compares one venue with itself, or the outcome is a chain read. */
class Aate8783SameVenueLabelTest {
    private fun m(venue: String, px: Double, at: Long, src: String = "DEX_PAIR") = LabelMark8386("M8783", venue, px, at, at, src)

    @Test fun a_move_read_on_another_venue_is_not_booked_unless_chain_read() {
        assertEquals(LabelEvidence8386.VENUE_MISMATCH_8783, LabelEvidence8386.status(m("poolA", 1.0, 1_000), m("poolB", 1.6, 60_000)))
        assertEquals("VALID_8386", LabelEvidence8386.status(m("poolA", 1.0, 1_000), m("poolA", 1.6, 60_000)))
        assertEquals("a chain read carries a migration", "VALID_8386",
            LabelEvidence8386.status(m("pump.curve", 1.0, 1_000), m("poolB", 1.6, 60_000, "CURVE_ACCOUNT")))
        assertEquals("an unnamed venue proves nothing either way", "VALID_8386", LabelEvidence8386.status(m("", 1.0, 1_000), m("poolB", 1.6, 60_000)))
    }
}
