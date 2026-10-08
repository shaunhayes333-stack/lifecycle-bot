package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7915 — Cortex v13 paper choice overrides only soft blocks.
 * V5.0.7916 — Cortex v14 planner and LLM voters are registered.
 * V5.0.7917 — Cortex v15 stops an unskilled size stack shrinking a proven STRONG read.
 */
class Aate7915CortexChoiceTest {
    @Test fun paperChoiceNeverOverridesHardBlocks() {
        assertTrue(Cortex7885.softBlock(null, false))
        assertTrue(Cortex7885.softBlock("LOW_CONFIDENCE_42", false))
        assertTrue(Cortex7885.softBlock("EDGE_SKIP", false))
        assertFalse(Cortex7885.softBlock("LOW_CONFIDENCE_42", true))
        assertFalse(Cortex7885.softBlock("CONFIRMED_RUG", false))
        assertFalse(Cortex7885.softBlock("NO_EXECUTABLE_ROUTE", false))
        assertFalse(Cortex7885.softBlock("HARD_SAFETY", false))
        assertFalse(Cortex7885.softBlock("freeze_authority_retained", false))
        assertFalse(Cortex7885.softBlock("DUPLICATE_OPEN", false))
    }

    @Test fun plannerAndLlmVotersRegistered() {
        val ids = CortexVoters7885.IDS
        for (id in listOf("SUPER_PLAN_EXPOSURE", "SUPER_WORLD_TACTICAL_EV", "SUPER_CRITIC_FRAGILITY", "SUPER_ARBITER_META_CONF",
            "LLM_VIRAL_POTENTIAL", "LLM_SCAM_CONFIDENCE", "LLM_RECOMMENDATION", Cortex7885.LEGACY_SIZE_SHAPE)) {
            assertTrue(id, id in ids)
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun stackShrinkOverruledOnlyWhenBothProven() {
        assertEquals(1.0, Cortex7885.shapeAfterAuthority(0.4, strongProven = true, stackMeasuredNoSkill = true), 1e-9)
        assertEquals(0.4, Cortex7885.shapeAfterAuthority(0.4, strongProven = false, stackMeasuredNoSkill = true), 1e-9)
        assertEquals(0.4, Cortex7885.shapeAfterAuthority(0.4, strongProven = true, stackMeasuredNoSkill = false), 1e-9)
        assertEquals(1.3, Cortex7885.shapeAfterAuthority(1.3, strongProven = true, stackMeasuredNoSkill = true), 1e-9)
    }
}
