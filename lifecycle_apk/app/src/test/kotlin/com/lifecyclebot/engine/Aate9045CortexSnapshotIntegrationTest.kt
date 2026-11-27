package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test

/** Real Cortex capture paths; array identity sharing is restricted to one detached generation. */
class Aate9045CortexSnapshotIntegrationTest {
    @Suppress("UNCHECKED_CAST") private fun <T> value(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner) as T
    @Suppress("UNCHECKED_CAST") private fun <T> captured(encoder: () -> String, name: String): T =
        encoder.javaClass.declaredFields.single { it.name.endsWith(name) }.also { it.isAccessible = true }.get(encoder) as T

    private fun assessment(edges: DoubleArray, at: Long) = Cortex7885.Assessment(
        "CORE", false, doubleArrayOf(2.0), listOf("snapshot-fixture-9045"), listOf(edges), "UPTREND",
        CortexLedger7885.Fused(8.0, 0.1, 1.0, 30.0, 1.0, 1.0, 0.0, listOf("fixture")),
        8.0, CortexScoreboard7885.Bucket.STRONG, 0, false, at,
        CortexLedger7885.PredictionSnapshot8076(doubleArrayOf(8.0), doubleArrayOf(0.6), 1.0, 0.5))

    @Test fun ordinaryAssessmentDetachKeepsIndependentEdgesRawsAndPredictions() {
        val live = assessment(doubleArrayOf(-5.0, 0.0, 12.0), System.currentTimeMillis())
        val method = Cortex7885::class.java.getDeclaredMethod("detachAssessment8386", Cortex7885.Assessment::class.java,
            CortexSnapshotArrays9045::class.java).also { it.isAccessible = true }
        val a = method.invoke(Cortex7885, live, null) as Cortex7885.Assessment
        val b = method.invoke(Cortex7885, live, null) as Cortex7885.Assessment
        assertNotSame(a.edges[0], b.edges[0]); assertNotSame(live.edges[0], a.edges[0])
        assertNotSame(a.raws, b.raws); assertNotSame(a.prediction8076!!.preds, b.prediction8076!!.preds)
        live.edges[0][0] = 90.0; live.raws[0] = 77.0; live.prediction8076!!.preds[0] = 66.0
        assertEquals(-5.0, a.edges[0][0], 0.0); assertEquals(2.0, a.raws[0], 0.0)
        assertEquals(8.0, a.prediction8076!!.preds[0], 0.0)
    }

    @Test fun pendingGradedAndBoundReadsShareOneDetachedEdgeCopyOnlyForTheirGeneration() {
        val at = System.currentTimeMillis()
        val keyA = "snapshot9045-A-${System.nanoTime()}|CORE"
        val keyB = "snapshot9045-B-${System.nanoTime()}|CORE"
        val gradeKey = "$keyA|$at"; val pid = "snapshot9045-bound-${System.nanoTime()}"
        val sourceEdges = doubleArrayOf(-5.0, 0.0, 12.0)
        val a = assessment(sourceEdges, at); val b = assessment(sourceEdges, at)
        val pendingType = Cortex7885::class.java.declaredClasses.single { it.simpleName == "Pending" }
        val pendingConstructor = pendingType.declaredConstructors.single { it.parameterCount == 7 }.also { it.isAccessible = true }
        fun pending(read: Cortex7885.Assessment) = pendingConstructor.newInstance(read, true, at, null, "fixture9045", "BASE_BREAKOUT", emptyList<String>())
        val pa = pending(a); val pb = pending(b)
        val trace = CortexLedger7885.GradeTrace8006(doubleArrayOf(8.0), doubleArrayOf(0.6), 1.0, 0.5)
        val scales = doubleArrayOf(1.0)
        val gradedType = Cortex7885::class.java.declaredClasses.single { it.simpleName == "Graded8006" }
        val gradedConstructor = gradedType.declaredConstructors.single { it.parameterCount == 8 }.also { it.isAccessible = true }
        val grade = gradedConstructor.newInstance(pa, trace, 12.0, 15.0, at, scales, doubleArrayOf(2.0), 1.0)
        val pending = value<MutableMap<String, Any>>(Cortex7885, "pending")
        val graded = value<MutableMap<String, Any>>(Cortex7885, "graded8006")
        val bound = value<CortexEntryReadBook8076<Cortex7885.Assessment>>(Cortex7885, "positionReads8076")
        val capture = Cortex7885::class.java.getDeclaredMethod("pendingSnapshot8076", Set::class.java, java.lang.Long::class.java)
            .also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") fun take(generation: Long): () -> String =
            synchronized(Cortex7885) { capture.invoke(Cortex7885, null, generation) as () -> String }
        fun reads(encode: () -> String): List<Cortex7885.Assessment> {
            val rows = captured<List<Triple<String, Any, Boolean>>>(encode, "rows8372")
            val ga = captured<List<Pair<String, Any>>>(encode, "gradedRows").single { it.first == gradeKey }.second
            val ba = captured<List<Any>>(encode, "bounds").single { value<String>(it, "positionId") == pid }
            return listOf(value(rows.single { it.first == keyA }.second, "a"), value(rows.single { it.first == keyB }.second, "a"),
                value(value<Any>(ga, "p"), "a"), value(ba, "read"))
        }
        try {
            synchronized(Cortex7885) {
                pending[keyA] = pa; pending[keyB] = pb; graded[gradeKey] = grade
                assertTrue(bound.bind(pid, "snapshot9045-bound", "PAPER", a))
            }
            val first = take(9045L)
            val detached = reads(first)
            detached.forEach { assertSame(detached[0].edges[0], it.edges[0]) }
            assertNotSame(sourceEdges, detached[0].edges[0])
            assertNotSame(detached[0].raws, detached[2].raws)
            assertNotSame(detached[0].prediction8076!!.preds, detached[2].prediction8076!!.preds)
            val detachedGrade = captured<List<Pair<String, Any>>>(first, "gradedRows").single { it.first == gradeKey }.second
            assertNotSame(trace.preds, value<CortexLedger7885.GradeTrace8006>(detachedGrade, "trace").preds)
            assertNotSame(scales, value<DoubleArray>(detachedGrade, "bookScales"))
            assertFalse("encoder must not retain memo keys/live edge arrays",
                first.javaClass.declaredFields.any { it.type == CortexSnapshotArrays9045::class.java })
            val encodedFirst = first()
            sourceEdges[0] = -90.0; a.raws[0] = 77.0; a.prediction8076!!.preds[0] = 66.0; trace.preds[0] = 55.0; scales[0] = 44.0
            assertEquals("live mutations cannot rewrite the captured bytes", encodedFirst, first())
            val second = take(9046L)
            val newer = reads(second)
            assertNotSame(detached[0].edges[0], newer[0].edges[0])
            newer.forEach { assertSame(newer[0].edges[0], it.edges[0]); assertEquals(-90.0, it.edges[0][0], 0.0) }
            assertEquals(-5.0, detached[0].edges[0][0], 0.0)
        } finally {
            synchronized(Cortex7885) {
                pending.remove(keyA); pending.remove(keyB); graded.remove(gradeKey)
                bound.take(pid, "snapshot9045-bound", "PAPER")
            }
        }
    }
}
