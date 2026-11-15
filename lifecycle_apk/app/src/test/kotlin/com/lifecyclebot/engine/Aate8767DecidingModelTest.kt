package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.Mind8746
import com.lifecyclebot.engine.truth.PocketLedger8610
import com.lifecyclebot.engine.truth.TreeRanker8737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8767 — code audit (learning 1, 4, 5; data P0-2): the model that refuses live coins learns from buyable rows. */
class Aate8767DecidingModelTest {
    private fun row(lane: String, fill: Boolean, horizon: Long = PocketLedger8610.targetHorizon(lane)) =
        """{"t":1000,"mint":"AbcPUMPpump","lane":"$lane","fill":$fill,"horizonMs":$horizon,"liq":20000,"mcap":40000,"age":3,"cost":2,"net":5}"""

    @Test fun the_tree_trains_on_buyable_rows_of_real_lanes_at_their_own_horizon() {
        assertEquals(1, TreeRanker8737.rowsOf8737(sequenceOf(row("MOONSHOT", true))).size)
        assertEquals("an entry that was never buyable", 0, TreeRanker8737.rowsOf8737(sequenceOf(row("MOONSHOT", false))).size)
        assertEquals("another horizon", 0, TreeRanker8737.rowsOf8737(sequenceOf(row("MOONSHOT", true, 3_600_000L + 1L))).size)
        assertEquals("a pseudo-lane", 0, TreeRanker8737.rowsOf8737(sequenceOf(row("PLANADMIT_MOONSHOT", true))).size)
        assertEquals("TREE8767", TreeRanker8737.KEY_8737)
        assertEquals("MIND8767", Mind8746.KEY_8746)
    }

    @Test fun one_lanes_frozen_voters_do_not_overwrite_anothers() {
        val f = Mind8746.features8746(4.0, 0.0, Double.NaN, Double.NaN)
        val g = Mind8746.features8746(-4.0, 0.0, Double.NaN, Double.NaN)
        Mind8746.freeze8746("c8767", "MOONSHOT", 77L, f)
        Mind8746.freeze8746("c8767", "EXPRESS", 77L, g)
        assertEquals(f.toList(), Mind8746.frozenFor8767("c8767", "MOONSHOT", 77L)!!.toList())
        assertEquals(g.toList(), Mind8746.frozenFor8767("c8767", "EXPRESS", 77L)!!.toList())
        Mind8746.learn8746("c8767", "MOONSHOT", 77L, 3.0)
        assertNull(Mind8746.frozenFor8767("c8767", "MOONSHOT", 77L))
        assertNotNull("the other lane's label still finds its voters", Mind8746.frozenFor8767("c8767", "EXPRESS", 77L))
        Mind8746.restoreFrozen8767("r8767", "SHITCOIN", 5L, f)
        assertNotNull(Mind8746.frozenFor8767("r8767", "SHITCOIN", 5L))
    }

    @Test fun the_brief_names_the_model_that_decides_and_the_checkpoint_carries_the_voters() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(src.contains("val o = oos[recordKey(l, key8767)]"))
        assertTrue(src.contains("\"\$l[by=\$key8767 n"))
        assertTrue(src.contains("put(\"mind8767\""))
        assertTrue(src.contains("Mind8746.restoreFrozen8767("))
    }
}
