package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8575 — a Cortex prediction evicted under CRITICAL heap is kept frozen (the forward outbox's own packed checkpoint)
 * and served when its outcome is staged. 8562: FORWARD_ORIGINAL_UNAVAILABLE_8404=410 outcomes graded against nothing.
 */
class Aate8575CortexPressureSpillTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()

    @Test fun an_evicted_prediction_is_frozen_before_it_goes_and_only_then_lost() {
        val cap = src.substringAfter("fun capPendingUnderPressure8524(").substringBefore("private val pressureEvictedPending8524")
        val freeze = cap.indexOf("ForwardCheckpoint8401.pack(synchronized(this) { pendingSnapshot8076(setOf(\"\$key|\${pd.atMs}\")) }())")
        val remove = cap.indexOf("if (pending.remove(key, pd)) {")
        assertTrue("frozen before the live object is removed", freeze in 0 until remove)
        assertTrue(cap.contains("if (packed8575 != null) spill8575(\"\$key|\${pd.atMs}\", packed8575)"))
        assertTrue("an unfreezable one is still recorded lost", cap.contains("else retainMissingOutcome8521(\"\$key|\${pd.atMs}\", pd)"))
        assertTrue(Cortex7885.SPILL_MAX_CHARS_8575 in 1L..8_000_000L)
    }

    @Test fun the_staged_outcome_is_served_the_frozen_copy_when_the_live_one_is_gone() {
        val fn = src.substringAfter("fun freezeForwardOutcome8404(").substringBefore("fun checkpointForward8401(")
        assertTrue(fn.contains("if (!live8575) takeSpilled8575(id8575)?.let { return it }"))
        assertTrue(fn.indexOf("takeSpilled8575(id8575)") < fn.indexOf("pendingSnapshot8076(setOf(id8575))"))
        // the spill monitor never takes the model lock (no inversion with freezeForwardOutcome8404, which holds it)
        val spill = src.substringAfter("private fun spill8575(").substringBefore("private fun takeSpilled8575(")
        assertTrue(!spill.contains("synchronized(this)"))
        assertTrue(Cortex7885.spillLine8575().startsWith("pressureSpill8575[held="))
    }
}
