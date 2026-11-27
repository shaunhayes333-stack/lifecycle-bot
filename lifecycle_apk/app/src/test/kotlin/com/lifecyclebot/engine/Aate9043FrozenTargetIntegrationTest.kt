package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.FrozenEdgeTarget9043
import com.lifecyclebot.engine.truth.ForwardLabelRecipe9043
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate9043FrozenTargetIntegrationTest {
    private val raw = EdgeModel8537.Raw("CORE", "TEST", 25_000.0, 50_000.0, 15.0, 1.0, 2.0, -1.0,
        0.6, 1.0, 2.0, 1.2, 20.0, "BASE", 60.0, 2.987654321)
    private fun snap(stop: Double = 20.123456789, recipe: String = FrozenEdgeTarget9043.RECIPE) = EdgeModel8537.Snap(
        raw, EdgeModel8537.vectorOf(raw), 12.0, "CORE", 1_795_736_000_000L, "Frozen9043AAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump",
        true, PocketLedger8610.targetHorizon("CORE"), targetStopPct9043 = stop,
        targetRecipe9043 = recipe, targetStopBasis9043 = "LANE_POLICY")

    @Test fun pendingCheckpointAndDatasetReplayKeepTheSameTarget() {
        val before = snap()
        val after = EdgeModel8537.decodeSnap8571(JSONObject(EdgeModel8537.encodeSnap8571(before).toString()))!!
        assertEquals(before.targetStopPct9043, after.targetStopPct9043, 0.0)
        assertEquals(before.targetRecipe9043, after.targetRecipe9043)
        assertEquals(before.targetStopBasis9043, after.targetStopBasis9043)
        val original = JSONObject(EdgeModel8537.datasetLine(before, -25.123456789))
        val resumed = JSONObject(EdgeModel8537.datasetLine(after, -25.123456789))
        assertEquals(-23.11111111, EdgeModel8537.trainingTarget9043(original), 0.000000000001)
        assertEquals(EdgeModel8537.trainingTarget9043(original), EdgeModel8537.trainingTarget9043(resumed), 0.0)
        assertEquals(FrozenEdgeTarget9043.DECISION_FROZEN, EdgeModel8537.targetClassification9043(resumed))
        assertEquals(before.targetStopPct9043, EdgeModel8537.frozenStop9043(resumed)!!, 0.0)
    }

    @Test fun damagedNewReceiptIsRejectedByAllDatasetConsumersAcceptanceRule() {
        val row = JSONObject(EdgeModel8537.datasetLine(snap(), 10.0, witnessed = true))
        assertTrue(EdgeModel8537.replayAccept8642(row))
        row.put("trainingTarget9043", 200.0)
        assertFalse(EdgeModel8537.replayAccept8642(row))
        assertTrue(EdgeModel8537.trainingTarget9043(row).isNaN())
        assertEquals(FrozenEdgeTarget9043.INVALID_RECEIPT, EdgeModel8537.targetClassification9043(row))
    }

    @Test fun oldPendingSnapshotDoesNotAcquireFalseDecisionTimeProvenance() {
        val checkpoint = EdgeModel8537.encodeSnap8571(snap())
        for (field in listOf("targetStopPct9043", "targetRecipe9043", "targetStopBasis9043")) checkpoint.remove(field)
        val oldPending = EdgeModel8537.decodeSnap8571(checkpoint)!!
        assertTrue(oldPending.targetStopPct9043.isNaN())
        val row = JSONObject(EdgeModel8537.datasetLine(oldPending, 10.0))
        assertEquals(FrozenEdgeTarget9043.LEGACY_LABEL_TIME_STOP, EdgeModel8537.targetClassification9043(row))
        assertNull(EdgeModel8537.frozenStop9043(row))
        assertTrue(EdgeModel8537.trainingTarget9043(row).isFinite())
    }

    @Test fun unsupportedPendingRecipeCannotSilentlyBecomeALegacyTarget() {
        val row = JSONObject(EdgeModel8537.datasetLine(snap(recipe = "FUTURE_UNKNOWN"), 10.0))
        assertEquals(FrozenEdgeTarget9043.INVALID_RECEIPT, EdgeModel8537.targetClassification9043(row))
        assertTrue(EdgeModel8537.trainingTarget9043(row).isNaN())
        assertFalse(EdgeModel8537.replayAccept8642(row))
        val missingStop = JSONObject(EdgeModel8537.datasetLine(snap(stop = Double.NaN), 10.0))
        assertEquals(FrozenEdgeTarget9043.INVALID_RECEIPT, EdgeModel8537.targetClassification9043(missingStop))
    }

    @Test fun versionedUpstreamDoctrineMatchesTheCurrentProductionLineAtAllBoundaryRegions() {
        val play = ForwardLabelRecipe9043.Play(20.0, listOf(100.0 to 0.25))
        for (stop in listOf(3.0, 10.0, 20.0, 30.0, 40.0)) for (peak in listOf(9.0, 10.0, 50.0, 79.9, 80.0, 90.0, 99.9, 100.0, 900.0, 10_000.0)) {
            val recipe = ForwardLabelRecipe9043(1000L, play, play, play, true, true, 10_000L, 5000L, 35.0, stop, RunnerDoctrine8584.KEEP_8584)
            val expected = if (peak >= 10.0) maxOf(-90.0, RunnerDoctrine8584.paperStopLevel8584(stop, peak)) - 3.0 else -93.0
            assertEquals("stop=$stop peak=$peak", expected, recipe.edgeNet9043(-90.0, 0.0, peak, 3.0, -93.0), 0.000000001)
        }
    }
}
