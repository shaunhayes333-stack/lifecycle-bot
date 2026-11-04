package com.lifecyclebot.engine

import android.content.SharedPreferences
import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class Aate8527ForwardAuthorityEpochTest {
    @Test fun syntheticCheckpointIsArchivedNotReinterpretedAsObservedProfit() {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getString("cells_8521", null)).thenReturn("SRC|TEST=old_synthetic_positive")
        `when`(p.getString("pending_8521", null)).thenReturn("frozen_synthetic_return_and_source_proof")
        `when`(p.getString("paired_cortex_8521", null)).thenReturn("old_prediction")
        val restored = ForwardReturnLabeler7731.modelledCheckpoint8527(p)
        assertNull(restored.cells)
        assertNull(restored.pending)
        assertNull(restored.paired)
        verify(p, never()).getString("cells_8521", null)
        verify(p, never()).getString("pending_8521", null)
        verify(p, never()).getString("paired_cortex_8521", null)
        verify(p, never()).edit()
    }

    @Test fun currentCheckpointAndItsFrozenPredictionRestoreTogetherWithoutLegacyFallback() {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getString("cells_8527", null)).thenReturn("")
        `when`(p.getString("pending_8527", null)).thenReturn("observed_price_and_immutable_basis")
        `when`(p.getString("paired_cortex_8527", null)).thenReturn("matching_prediction")
        `when`(p.getString("cells_8521", null)).thenReturn("positive_but_synthetic")
        val restored = ForwardReturnLabeler7731.modelledCheckpoint8527(p)
        assertEquals("", restored.cells)
        assertEquals("observed_price_and_immutable_basis", restored.pending)
        assertEquals("matching_prediction", restored.paired)
        verify(p, never()).getString("cells_8521", null)
        verify(p, never()).edit()
    }

    @Test fun oldDurableOutcomeCannotReenterTheCurrentQueueAndArchivesAreUnchanged() {
        val rows = linkedMapOf(
            "forward8404|mint|MOONSHOT|1" to "old_half_peak_profit",
            "forward8527|mint|MOONSHOT|2" to "new_observed_return",
            "forward85270|mint|MOONSHOT|3" to "wrong_prefix",
            "terminal8395|mint|MOONSHOT|1" to "unavailable_not_a_loss",
        )
        val before = rows.toMap()
        val current = ForwardReturnLabeler7731.forwardPayloads8527(rows)
        assertEquals(mapOf("mint|MOONSHOT|2" to "new_observed_return"), current)
        assertEquals(before, rows)
        val oldOnly = ForwardOutcomeOutbox8404(8)
        oldOnly.restore(ForwardReturnLabeler7731.forwardPayloads8527(mapOf(
            "forward8404|mint|MOONSHOT|1" to "old_half_peak_profit",
        )))
        assertEquals(0, oldOnly.size())
    }

    @Test fun allocatorPreservesCompatibleRealisedHistoryAndDropsOnlyIncompatibleEvidence() {
        val valid = listOf(
            "V|G=20,100,500,20,40,0",
            "P|LANE|MOONSHOT=10,-100,1000,10,20,0",
            "V8527|CELL|SRC|MOONSHOT|MC_10K_100K|AGE_UNKNOWN=3,15,75,3,9,0",
            "P8527|BAND|SRC|MC_10K_100K|AGE_UNKNOWN=4,20,100,4,12,0",
            "L8527|LANE|MOONSHOT=5,-50,500,5,300,0",
        )
        val incompatible = listOf(
            "L|G=600,60000,6000000,600,3000,0",
            "L8521|G=600,60000,6000000,600,3000,0",
            "V|CELL|SRC|MOONSHOT|MC_10K_100K|AGE_LT15M=60,6000,600000,60,60,0",
        )
        val raw = (valid + incompatible).joinToString(";")
        val current = CellAllocator7962.currentNodes8527(raw)
        assertEquals(valid.joinToString(";"), current)
        assertEquals(current, CellAllocator7962.currentNodes8527(current))
        assertTrue(raw.contains("L8521|G="))
    }

    @Test fun allocatorUpgradeCopiesRealisedNodesWithoutOverwritingPriorStores() {
        val p = mock(SharedPreferences::class.java)
        val actual = "V|LANE|MOONSHOT=20,100,500,20,40,0"
        `when`(p.getString("nodes_birth_8527", null)).thenReturn("$actual;L8521|LANE|MOONSHOT=100,30000,9000000,100,500,0")
        assertEquals(actual, CellAllocator7962.restoreNodes8527(p))
        verify(p, never()).edit()
        `when`(p.getString("nodes_observed_8527", null)).thenReturn("")
        assertEquals("", CellAllocator7962.restoreNodes8527(p))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun syntheticWinsCannotOverrideCurrentLossesOrMixLiveAndPaperOutcomes() {
        val owner = CellAllocator7962
        val field = owner.javaClass.getDeclaredField("nodes").apply { isAccessible = true }
        val nodes = field.get(owner) as MutableMap<String, CellAllocator7962.Stat7962>
        val saved = HashMap(nodes)
        val read = owner.javaClass.getDeclaredMethod("statFor", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun stat(n: Int, pct: Double) = CellAllocator7962.Stat7962().apply { repeat(n) { add(pct, 1.0) } }
        try {
            nodes.clear()
            nodes["L8521|LANE|MOONSHOT"] = stat(600, 300.0)
            nodes["L8527|LANE|MOONSHOT"] = stat(10, -20.0)
            nodes["V|LANE|MOONSHOT"] = stat(2, 5.0)
            nodes["P|LANE|MOONSHOT"] = stat(3, -5.0)
            val live = read.invoke(owner, "LANE|MOONSHOT", true) as CellAllocator7962.Stat7962
            val paper = read.invoke(owner, "LANE|MOONSHOT", false) as CellAllocator7962.Stat7962
            assertEquals(12.0, live.w, 0.0)
            assertEquals(-190.0, live.sx, 0.0)
            assertEquals(13.0, paper.w, 0.0)
            assertEquals(-215.0, paper.sx, 0.0)
        } finally { nodes.clear(); nodes.putAll(saved) }
    }

    @Test fun productionReadsWritesAndHiveLookupUseTheSameObservedOnlyContract() {
        val labeler = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(labeler.contains("val checkpoint8527 = modelledCheckpoint8527(p)"))
        assertTrue(labeler.contains("forwardOutbox8404.restore(forwardPayloads8527(rows8409))"))
        assertTrue(labeler.contains("putString(CELLS_KEY_7946, enc).putString(PENDING_KEY_7946, encodePending7735"))
        assertTrue(labeler.contains("HiveEdge8000.net8000(\"FRL8527|\$key\")"))
        assertTrue(labeler.contains("private const val TERMINAL_PREFIX_8395 = \"terminal8395|\""))
        for (archive in listOf("cells_8521", "pending_8521", "paired_cortex_8521")) {
            assertFalse(labeler.contains("remove(\"$archive\")"))
        }
        val allocator = File("src/main/kotlin/com/lifecyclebot/engine/CellAllocator7962.kt").readText()
        assertTrue(allocator.contains("val raw = restoreNodes8527(p)"))
        assertTrue(allocator.contains("putString(\"nodes_observed_8527\", enc)"))
        assertTrue(allocator.contains("private const val EV_LIVE = \"V\""))
        assertTrue(allocator.contains("private const val EV_PAPER = \"P\""))
    }
}
