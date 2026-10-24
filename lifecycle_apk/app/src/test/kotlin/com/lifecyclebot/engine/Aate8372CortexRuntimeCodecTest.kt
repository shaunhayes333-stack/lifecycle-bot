package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Android unit-suite tests of the actual runtime checkpoint functions. These do not run in the
 * standalone model harness: no replacement Cortex implementation is used to make them pass.
 * Only private codec functions are invoked; no assess, persistence, trade or service entry point runs.
 */
class Aate8372CortexRuntimeCodecTest {
    private fun assessment() = Cortex7885.Assessment(
        lane = "QUALITY", runnerLane = false,
        raws = doubleArrayOf(0.987654321098765, Double.NaN, -0.123456789012345),
        ids = listOf("FROZEN_CUSTOM_A_8372", "UNOBSERVED_8372", "FROZEN_CUSTOM_B_8372"),
        edges = listOf(doubleArrayOf(0.123456789012345, 0.8), doubleArrayOf(5.0), doubleArrayOf(-0.9, -0.2)),
        regime = "RANGE",
        fused = CortexLedger7885.Fused(5.123456789012345, 0.0, 3.456789012345678,
            81.0, 2.0, 2.0, 0.0, emptyList(), laneMeanAll8325 = -4.567890123456789),
        calibratedEdge = 7.123456789012345,
        bucket = CortexScoreboard7885.Bucket.STRONG,
        unknownFeatures = 1, staleMark = false, atMs = 1_000L,
        prediction8076 = CortexLedger7885.PredictionSnapshot8076(
            doubleArrayOf(8.123456789012345, Double.NaN, -2.345678901234567),
            doubleArrayOf(0.712345678901234, Double.NaN, 0.312345678901234),
            -3.123456789012345, 0.412345678901234,
        ),
        scoreVersion8372 = 1, calibrationInput8372 = -2.901222346790122,
    )

    private fun call(name: String, vararg args: Any): Any? = Cortex7885::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(Cortex7885, *args)

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun put(instance: Any, name: String, value: Any?) = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(instance, value)

    private fun assertScoreMetadata(before: Cortex7885.Assessment, after: Cortex7885.Assessment) {
        assertEquals(before.lane, after.lane)
        assertEquals(before.runnerLane, after.runnerLane)
        assertEquals(before.regime, after.regime)
        assertEquals(before.bucket, after.bucket)
        assertEquals(before.atMs, after.atMs)
        assertEquals(before.fused.edgePct, after.fused.edgePct, 0.0)
        assertEquals(before.fused.laneMean, after.fused.laneMean, 0.0)
        assertEquals(before.fused.laneMeanAll8325, after.fused.laneMeanAll8325, 0.0)
        assertEquals(before.calibrationInput8372, after.calibrationInput8372, 0.0)
        assertEquals(before.calibratedEdge, after.calibratedEdge, 0.0)
        assertEquals(before.scoreVersion8372, after.scoreVersion8372)
    }

    @Test fun actualEntryCodecRestoresFrozenScoreBinsAndPredictionArraysWithoutLiveReaders() {
        val before = assessment()
        val encoded = call("encodeEntryRead8076", before) as JSONObject
        val after = call("decodeEntryRead8076", JSONObject(encoded.toString())) as Cortex7885.Assessment
        assertScoreMetadata(before, after)
        assertEquals(before.ids, after.ids)
        assertArrayEquals(before.raws, after.raws, 0.0)
        before.edges.zip(after.edges).forEach { (a, b) -> assertArrayEquals(a, b, 0.0) }
        val beforePrediction = before.prediction8076!!
        val afterPrediction = after.prediction8076!!
        assertArrayEquals(beforePrediction.preds, afterPrediction.preds, 0.0)
        assertArrayEquals(beforePrediction.pWins, afterPrediction.pWins, 0.0)
        assertEquals(beforePrediction.base, afterPrediction.base, 0.0)
        assertEquals(beforePrediction.baseWin, afterPrediction.baseWin, 0.0)
    }

    @Test fun anOldEntryReadKeepsItsOriginalBucketAndExplicitLegacyScoreSemantics() {
        val before = assessment()
        val old = call("encodeEntryRead8076", before) as JSONObject
        old.remove("provenance8372")
        val after = call("decodeEntryRead8076", JSONObject(old.toString())) as Cortex7885.Assessment
        assertEquals(before.bucket, after.bucket)
        assertEquals(before.atMs, after.atMs)
        assertEquals(0, after.scoreVersion8372)
        assertTrue(after.calibrationInput8372.isNaN())
        assertTrue(after.fused.laneMeanAll8325.isNaN())
    }

    @Test fun actualGradedCodecKeepsExactEpisodeSparseWeightsAndOwnLaneProvenance() {
        val a = assessment()
        val pendingClass = Class.forName("${Cortex7885::class.java.name}\$Pending")
        val p = pendingClass.declaredConstructors.single { it.parameterCount == 7 }.apply { isAccessible = true }
            .newInstance(a, false, 1_100L, "FDG_SETUP_RECORD_LOSING", "DEX_TRENDING", "IGNITION", listOf("FLOW", "ACCELERATION"))
        put(p, "decisionMode8196", "LIVE")
        put(p, "decisionReason8196", "CORTEX_SHADOW_STRONG")
        put(p, "selected8153", "IGNITION")
        val model = CortexLedger7885()
        val trace = model.grade(a.lane, a.ids, a.edges, a.raws, -12.123456789012345, -9.123456789012345,
            a.regime, a.prediction8076)!!
        val bookScales = doubleArrayOf(-0.123456789012345, -0.234567890123456, -0.345678901234567)
        val auxScales = doubleArrayOf(-0.456789012345678, Double.NaN, -0.567890123456789)
        val gradedClass = Class.forName("${Cortex7885::class.java.name}\$Graded8006")
        val graded = gradedClass.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            .newInstance(p, trace, -12.123456789012345, -9.123456789012345, 1_100L, bookScales, auxScales, -0.876543210987654)
        put(graded, "witnessed8324", true)
        val contributionClass = Class.forName("${Cortex7885::class.java.name}\$JointContribution8372")
        val contributionConstructor = contributionClass.declaredConstructors.single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        val generation = 9_007_199_254_740_993L
        val selected = contributionConstructor.newInstance("QUALITY|STRONG|IGNITION", generation, -0.123456789012345)
        val alternative = contributionConstructor.newInstance("QUALITY|STRONG|FLOW", generation + 1L, -0.987654321098765)
        put(graded, "jointTrace8372", listOf(selected, alternative))
        val dict = LinkedHashMap<String, Int>()
        val row = call("encodeGraded8372", "MintA|QUALITY|1100", graded, dict) as JSONObject
        val root = JSONObject().put("dict", JSONArray(dict.keys.toList())).put("row", row)
        val restoredRoot = JSONObject(root.toString())
        val jsonDict = restoredRoot.getJSONArray("dict")
        val keys = List(jsonDict.length()) { jsonDict.getString(it) }
        val restored = call("decodeGraded8372", restoredRoot.getJSONObject("row"), keys, 100_000L) as Pair<*, *>
        assertEquals("MintA|QUALITY|1100", restored.first)
        val g2 = restored.second!!
        val p2 = field(g2, "p")!!
        val a2 = field(p2, "a") as Cortex7885.Assessment
        assertScoreMetadata(a, a2)
        assertEquals(1_100L, field(p2, "atMs"))
        assertEquals("LIVE", field(p2, "decisionMode8196"))
        assertEquals("CORTEX_SHADOW_STRONG", field(p2, "decisionReason8196"))
        assertEquals("DEX_TRENDING", field(p2, "source"))
        assertEquals("IGNITION", field(p2, "setup7961"))
        assertEquals("IGNITION", field(p2, "selected8153"))
        assertEquals(listOf("FLOW", "ACCELERATION"), field(p2, "fired8094"))
        assertEquals(listOf(a.ids[0], a.ids[2]), a2.ids)
        assertArrayEquals(doubleArrayOf(a.raws[0], a.raws[2]), a2.raws, 0.0)
        assertArrayEquals(a.edges[0], a2.edges[0], 0.0)
        assertArrayEquals(a.edges[2], a2.edges[1], 0.0)
        val t2 = field(g2, "trace") as CortexLedger7885.GradeTrace8006
        assertArrayEquals(doubleArrayOf(trace.preds[0], trace.preds[2]), t2.preds, 0.0)
        assertArrayEquals(doubleArrayOf(trace.pWins[0], trace.pWins[2]), t2.pWins, 0.0)
        assertEquals(trace.base, t2.base, 0.0)
        assertEquals(trace.baseWin, t2.baseWin, 0.0)
        val sourceScoreAt = trace.scoreAt!!
        assertArrayEquals(intArrayOf(sourceScoreAt[0], sourceScoreAt[2]), t2.scoreAt)
        val sourceScales = trace.scales!!
        val expectedScales = (sourceScales.slice(0..3) + sourceScales.slice(8..11) + sourceScales.slice(12..14)).toDoubleArray()
        assertArrayEquals(expectedScales, t2.scales, 0.0)
        assertArrayEquals(bookScales, field(g2, "bookScales") as DoubleArray, 0.0)
        assertArrayEquals(auxScales, field(g2, "auxScales") as DoubleArray, 0.0)
        assertEquals(-0.876543210987654, field(g2, "calStamp8322") as Double, 0.0)
        assertEquals(true, field(g2, "witnessed8324"))
        val joint = field(g2, "jointTrace8372") as List<*>
        assertEquals(2, joint.size)
        assertEquals("QUALITY|STRONG|IGNITION", field(joint[0]!!, "key"))
        assertEquals(generation, field(joint[0]!!, "generation"))
        assertEquals(-0.123456789012345, field(joint[0]!!, "scale") as Double, 0.0)
        assertEquals("QUALITY|STRONG|FLOW", field(joint[1]!!, "key"))
        assertEquals(generation + 1L, field(joint[1]!!, "generation"))
        assertEquals(-0.987654321098765, field(joint[1]!!, "scale") as Double, 0.0)

        // Old graded rows have no generation identity. Preserve the row without inventing one.
        val oldRow = JSONObject(row.toString()).also { it.remove("jointTrace8372") }
        val oldRestored = call("decodeGraded8372", oldRow, keys, 100_000L) as Pair<*, *>
        assertEquals("MintA|QUALITY|1100", oldRestored.first)
        assertTrue((field(oldRestored.second!!, "jointTrace8372") as List<*>).isEmpty())
    }
}
