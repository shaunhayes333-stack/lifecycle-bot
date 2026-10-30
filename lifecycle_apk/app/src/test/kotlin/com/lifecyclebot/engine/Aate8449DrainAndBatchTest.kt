package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8449 — repairs to 8445 from the 8446 phone run (1.9 h): the label clock ticked once per ~16 s instead of every 5 s
 * because each drain tick paid up to 192 verified SQLite commits; supply asks went out at 1.6 mints a call because the
 * batch read whatever one mint had queued.
 */
class Aate8449DrainAndBatchTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theDrainHandsTheWholeBatchToOneDurableWrite() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val drain = l.substring(l.indexOf("private fun drainTerminals8395()"), l.indexOf("private fun deadline8395("))
        assertTrue(drain.contains("Cortex7885.observationUnavailableBatch8449(items)"))
        assertFalse("no per-terminal Cortex call left in the drain", drain.contains("Cortex7885.observationUnavailable8395("))
        val cx = src("engine/cortex/Cortex7885.kt")
        val batch = cx.substring(cx.indexOf("fun observationUnavailableBatch8449("), cx.indexOf("private const val FORWARD_RECEIPT_8404"))
        assertEquals("one verified write for the batch", 1, Regex("LearningPersistence\\.saveTogetherVerified8386\\(").findAll(batch).count())
        assertTrue("the release is applied per terminal", batch.contains("onLabelLost8187(t.mint, t.lane, t.why, t.atMs)"))
        assertTrue("a refused write takes the checkpoint path", batch.contains("observationTerminals8395.putIfAbsent(receipt"))
    }

    @Test fun nothingIsAcknowledgedWithoutDurableStorage() {
        // JVM: LearningPersistence is not open, so no receipt can be durable and no terminal may be acknowledged.
        val acked = Cortex7885.observationUnavailableBatch8449(listOf(
            Cortex7885.Terminal8449("terminal8395|m|MOONSHOT|1", "m", "MOONSHOT", 1L, "OBSERVATION_DEADLINE_TEST")))
        assertTrue(acked.isEmpty())
        assertTrue(Cortex7885.observationUnavailableBatch8449(emptyList()).isEmpty())
    }

    @Test fun supplyAsksAreGatheredIntoFullBatches() {
        assertEquals(2_000L, OnChainSupplyAuthority7075.BATCH_GATHER_MS_8449)
        val o = src("engine/truth/OnChainSupplyAuthority7075.kt")
        val drain = o.substring(o.indexOf("private fun drainBatch8445()"), o.indexOf("fun accountResult8445("))
        assertTrue(drain.contains("while (batch8445.size < BATCH_KEYS_8445 && System.currentTimeMillis() < gatherUntil)"))
        assertTrue(drain.indexOf("gatherUntil") < drain.indexOf("val mints = batch8445.keys.take(BATCH_KEYS_8445)"))
    }
}
