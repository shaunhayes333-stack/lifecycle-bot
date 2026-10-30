package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8449 — supply asks went out at 1.6 mints a call on 8446 because the batch read whatever one mint had queued; it now
 * gathers into full batches. (8449's one-call terminal drain was withdrawn in 8451 after its runtime run ANR'd.)
 */
class Aate8449DrainAndBatchTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun supplyAsksAreGatheredIntoFullBatches() {
        assertEquals(2_000L, OnChainSupplyAuthority7075.BATCH_GATHER_MS_8449)
        val o = src("engine/truth/OnChainSupplyAuthority7075.kt")
        val drain = o.substring(o.indexOf("private fun drainBatch8445()"), o.indexOf("fun accountResult8445("))
        assertTrue(drain.contains("while (batch8445.size < BATCH_KEYS_8445 && System.currentTimeMillis() < gatherUntil)"))
        assertTrue(drain.indexOf("gatherUntil") < drain.indexOf("val mints = batch8445.keys.take(BATCH_KEYS_8445)"))
    }
}
