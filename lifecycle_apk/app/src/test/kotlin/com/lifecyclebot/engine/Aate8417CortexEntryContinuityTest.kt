package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexEntrySeal8395
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production capture callback, including the pre-confirmation race. */
class Aate8417CortexEntryContinuityTest {
    private fun field(name: String) = Cortex7885.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    @Test fun predictionArrivingBeforeChainConfirmationFillsItsPendingIntent() {
        val cortex = Cortex7885
        val binds = field("deferredBinds8187").get(cortex) as MutableMap<String, Any>
        val savedBinds = binds.toMap()
        val reads = field("entryReads").get(cortex) as MutableMap<String, Any>
        val readTimes = field("entryReadAt8187").get(cortex) as MutableMap<String, Long>
        val seals = field("entrySeals8395").get(cortex) as CortexEntrySeal8395<Cortex7885.Assessment>
        val mint = "entry-continuity-8417-${System.nanoTime()}"
        val key = "$mint|LIVE|MOONSHOT"
        val at = System.currentTimeMillis()
        fun assessment(edge: Double, time: Long) = Cortex7885.Assessment(
            "MOONSHOT", true, doubleArrayOf(edge), listOf("ENTRY_CONTINUITY_8417"),
            listOf(doubleArrayOf(0.0)), "TEST",
            CortexLedger7885.Fused(edge, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()),
            edge, CortexScoreboard7885.Bucket.STRONG, 0, false, time,
        )
        val capture = cortex.javaClass.getDeclaredMethod(
            "noteEntryRead", String::class.java, Boolean::class.javaPrimitiveType,
            Cortex7885.Assessment::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        try {
            binds.clear() // No confirmed position exists yet.
            assertTrue(cortex.sealEntryRead8395(mint, "LIVE", "MOONSHOT", 8417L, at))
            assertEquals(CortexEntrySeal8395.State.PENDING, seals.resolve(key, 8417L).state)
            capture.invoke(cortex, mint, false, assessment(12.0, at - 100), at - 100)
            val sealed = seals.resolve(key, 8417L)
            assertEquals("the fill must already find its original prediction", CortexEntrySeal8395.State.SEALED, sealed.state)
            assertEquals(12.0, sealed.read!!.calibratedEdge, 0.0)
            assertEquals(at - 100, sealed.read!!.atMs)
            assertTrue("capturing a prediction must not invent a position", binds.isEmpty())

            capture.invoke(cortex, mint, false, assessment(90.0, at + 10_000), at + 10_000)
            assertEquals("reassessment cannot replace the approving prediction", 12.0,
                seals.resolve(key, 8417L).read!!.calibratedEdge, 0.0)

            seals.seal(key, 8418L, null, at)
            capture.invoke(cortex, mint, false, assessment(95.0, at + 20_000), at + 20_000)
            assertEquals("a future reassessment cannot fill an earlier missing decision",
                CortexEntrySeal8395.State.PENDING, seals.resolve(key, 8418L).state)
        } finally {
            binds.clear(); binds.putAll(savedBinds)
            seals.release(key, 8417L); seals.release(key, 8418L)
            reads.remove(key); readTimes.remove(key)
        }
    }
}
