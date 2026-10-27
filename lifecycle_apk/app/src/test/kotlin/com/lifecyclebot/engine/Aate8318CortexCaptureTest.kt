package com.lifecyclebot.engine

import com.lifecyclebot.engine.ProofDataFeatureCoverage8144.Feature8144
import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8318 — directive P5: Cortex capture lag (5.0.8314 capture meanLagMs 111,764: one capture worker behind a queue of
 * up to 416 cold captures, each re-running a full assessment because the assessment cache lived 20 s) and the mislabelled
 * "tape" feature counter (the 45-minute launch tape, not the local trade tape).
 */
class Aate8318CortexCaptureTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── capture pool, assessment pool, assessment TTL ──

    @Test fun captureAndAssessmentPoolsWidenAndTheAssessmentCacheLivesAMinute() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("private const val ASSESS_TTL_MS = 60_000L"))
        assertFalse(cx.contains("private const val ASSESS_TTL_MS = 20_000L"))
        val capture = cx.substringAfter("private val capturePool8171").substringBefore("// ── V5.0.8187 §ACCOUNTABLE_CAPTURE_QUEUE")
        assertTrue("three capture workers (V5.0.8399)", capture.contains("3, 3, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(CAPTURE_QUEUE_HARD_8187 + 32)"))
        val assess = cx.substringAfter("private val pool: java.util.concurrent.ThreadPoolExecutor").substringBefore("private const val CAPTURE_QUEUE_HARD_8187")
        assertTrue("assessment pool max 4", assess.contains("2, 4, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(256)"))
    }

    @Test fun theCaptureQueueHardCapIsDeclaredBeforeThePoolThatSizesFromIt() {
        // An earlier compile break: a const read by a property initializer must be declared above it.
        val cx = src("engine/cortex/Cortex7885.kt")
        val cap = cx.indexOf("private const val CAPTURE_QUEUE_HARD_8187 = 416")
        val pool = cx.indexOf("private val capturePool8171: java.util.concurrent.ThreadPoolExecutor")
        assertTrue(cap > 0 && pool > 0 && cap < pool)
    }

    @Test fun memoryStaysBoundedAroundTheWiderPools() {
        val cx = src("engine/cortex/Cortex7885.kt")
        // queue admission is still the keyed 8187 accounting; the executor never evicts a queued decision
        assertTrue(cx.contains("queued < (if (admitted) CAPTURE_QUEUE_HARD_8187 else CAPTURE_QUEUE_SOFT_8187)"))
        assertTrue(Cortex7885.captureAdmits8187(415, true))
        assertFalse(Cortex7885.captureAdmits8187(416, true))
        // the assessment cache keeps its size cap and its TTL eviction
        assertTrue(cx.contains("private const val MAX_ASSESS_CACHE = 4_000"))
        assertTrue(cx.contains("if (assessCache.size >= MAX_ASSESS_CACHE) assessCache.entries.removeIf { nowMs - it.value.atMs > ASSESS_TTL_MS }"))
        // a capture only reuses an assessment inside the TTL and never one from the future
        assertTrue(cx.contains("assessCache[\"${'$'}{ts.mint}|${'$'}{canon(labelLane)}\"]?.takeIf { nowMs - it.atMs in 0L..ASSESS_TTL_MS }"))
        // the voter time budget still bounds each assessment
        val voters = src("engine/cortex/CortexVoters7885.kt")
        assertTrue(voters.contains("const val ASSESS_BUDGET_MS_8187 = 35.0"))
        assertTrue(com.lifecyclebot.engine.cortex.CortexVoters7885.servedFromCache8187(0.5, 36.0))
    }

    // ── "tape" → "launchTape" in the feature counters ──

    @Test fun theLaunchTapeCounterIsNamedForWhatItReads() {
        assertEquals("launchTape", Feature8144.LAUNCH_TAPE.short)
        assertTrue(Feature8144.values().none { it.name == "TAPE" || it.short == "tape" })
        val F = ProofDataFeatureCoverage8144
        F.clearForTest8144()
        assertEquals(listOf(Feature8144.LAUNCH_TAPE),
            F.missing8144(bp = true, chg = true, launchTape = false, structure = true, context = true, liquidity = true, birth = true, swaps = true))
        F.observe8144("m8318", 1_000L, bp = true, chg = true, launchTape = false, structure = true, context = true, liquidity = true, birth = true, swaps = true)
        assertEquals(1L, F.missingCount8144(Feature8144.LAUNCH_TAPE))
        val line = F.statusLine8144(1_001L)
        assertTrue(line, line.contains("launchTape=1/1"))
        assertFalse(line, line.contains(" tape="))
        F.clearForTest8144()
    }

    @Test fun theSetupFeatureMissingCounterSaysLaunchTapeButTheMissingInputKeepsItsKey() {
        assertEquals("launchTape", LanePlaybook7907.counterFeature8318("tape"))
        assertEquals("bp", LanePlaybook7907.counterFeature8318("bp"))
        assertEquals("shape", LanePlaybook7907.counterFeature8318("shape"))
        assertEquals("tape8100", LanePlaybook7907.counterFeature8318("tape8100"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("val feat = counterFeature8318(m.substringBefore(':'))"))
        assertTrue(pb.contains("PipelineHealthCollector.labelInc(\"SETUP_FEATURE_MISSING_8084_${'$'}feat\")"))
        // the launch tape is what the playbook observes under "tape" (LaunchTape7921), and it is not a core input
        assertTrue(pb.contains("val tape = try { com.lifecyclebot.engine.market.LaunchTape7921.features(ts.mint, nowMs) }"))
        assertTrue(pb.contains("\"tape\" to (if (tape != null) Obs8084.OBSERVED else Obs8084.UNKNOWN)"))
        assertTrue(pb.contains("launchTape = tape != null"))
        assertFalse(LanePlaybook7907.CORE_8084.contains("tape"))
        // the local trade-tape counters keep their own name
        assertTrue(pb.contains("val tape8100 = try { com.lifecyclebot.engine.truth.TradeTape8100.read8100(ts.mint, nowMs) }"))
        assertTrue(src("engine/truth/SetupFeatureResolver8169.kt").contains("inc(bySource, \"launchTape:launchTape7921\") else inc(missing, \"launchTape|${'$'}launchWhy\")"))
    }
}
