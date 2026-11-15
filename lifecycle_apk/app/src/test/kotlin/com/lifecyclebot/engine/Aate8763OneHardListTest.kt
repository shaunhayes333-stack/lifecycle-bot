package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.PaperMirror8757
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8763 — code audit (buy 4, 14): the mirror never overrides a final-gate hard marker or a door its record proves. */
class Aate8763OneHardListTest {
    @Test fun every_final_gate_hard_marker_is_hard_for_the_mirror() {
        for (r in listOf("MINT_AUTHORITY_PRESENT", "COST_REQUOTE_ABOVE_8391", "CANONICAL_ENTRY_EVAL_FAILED_8392", "NET_EDGE_BELOW_2X_COST_8030")) {
            assertTrue(r, FdgGate8365.hardSafety8392(r))
            assertTrue(r, PaperMirror8757.hard8757(r))
        }
    }

    @Test fun a_door_whose_record_proves_it_protects_is_hard_and_an_unproven_one_is_not() {
        val reason = "LIVE_TEST_DOOR_PROTECTS_8763"
        assertFalse("unproven: mirrored past (the owner's choice)", PaperMirror8757.hard8757(reason, "MOONSHOT"))
        val gate = FdgGate8365.gateKey8392(reason)
        for (i in 0 until 45) GateOutcome8418.book8418(gate, "MOONSHOT", -30.0 + (i % 3), runner = false, mint = "m8763_$i", nowMs = 1_000L + i)
        assertTrue(GateOutcome8418.doorProtects8715(reason, "MOONSHOT"))
        assertTrue("proven by its own record: never overridden", PaperMirror8757.hard8757(reason, "MOONSHOT"))
        assertFalse("the edge model is still mirrored past", PaperMirror8757.hard8757("EDGE_MODEL_NEGATIVE_8537", "MOONSHOT"))
    }
}
