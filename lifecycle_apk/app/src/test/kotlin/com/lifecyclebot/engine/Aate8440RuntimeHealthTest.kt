package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LearnerAttribution8418
import com.lifecyclebot.network.KeylessRpc8359
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8440 — the 8439 report (3.2 h): heap 506/512 MB, one OutOfMemoryError, the loop at 24 s a cycle, zero buys. The
 * learner-attribution prior is bounded and streamed and counted in the census; the lifecycle map is concurrent; the brief
 * opens with the runtime and names the restored states by size; the birth fault names the unanswered walks, not the sniper.
 */
class Aate8440RuntimeHealthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theLifecycleMapToleratesConcurrentAddAndPrune() {
        val t = src("engine/TradeLifecycle.kt")
        assertTrue(t.contains("private val lifecycles = java.util.concurrent.ConcurrentHashMap<String, Lifecycle>()"))
        assertFalse(t.contains("private val lifecycles = mutableMapOf<String, Lifecycle>()"))
    }

    @Test fun theAttributionPriorIsStreamedBoundedAndCensused() {
        val a = src("engine/truth/LearnerAttribution8418.kt")
        assertTrue(a.contains("f.useLines { seq ->"))
        assertFalse("never read whole", a.contains("f.readLines()"))
        assertTrue(a.contains("if (q.size > PRIOR_PER_LEARNER_8440) q.removeFirst()"))
        assertEquals(10_000, LearnerAttribution8418.PRIOR_PER_LEARNER_8440)
        val m = src("engine/MemoryGuard7977.kt")
        assertTrue(m.contains("register8144(\"learnerAttribution8440\""))
        assertTrue(MemoryGuard7977.blobs8440().startsWith("blobs8440["))
    }

    @Test fun theBriefFlagsADegradedRuntime() {
        assertTrue("8439: heap 99%", DiagnosisBrief8438.runtimeFault8440(98.8, 3_000L, false))
        assertTrue("8439: 24 s a cycle", DiagnosisBrief8438.runtimeFault8440(60.0, 24_000L, false))
        assertTrue("last crash OOM", DiagnosisBrief8438.runtimeFault8440(40.0, 2_000L, true))
        assertFalse(DiagnosisBrief8438.runtimeFault8440(70.0, 3_000L, false))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("[FAULT runtime degraded]"))
    }

    @Test fun theBirthFaultNamesUnansweredWalksNotTheSniper() {
        assertTrue("8439: 3379 of 5335 unanswered", DiagnosisBrief8438.birthFault8440(5335, 31, 3379))
        assertFalse("walks that end established are answers", DiagnosisBrief8438.birthFault8440(5335, 31, 400))
        assertFalse("resolving walks", DiagnosisBrief8438.birthFault8440(1000, 400, 600))
        assertFalse("too few walks to judge", DiagnosisBrief8438.birthFault8440(50, 0, 50))
        val b = src("engine/DiagnosisBrief8438.kt")
        assertFalse(b.contains("PROJECT_SNIPER cannot judge a launch without it"))
        assertEquals(3, com.lifecyclebot.engine.truth.LaunchBirth8169.scope8440().size)
    }

    @Test fun keylessFailuresAreNamed() {
        assertEquals("transport", KeylessRpc8359.failKey8440(0, 0))
        assertEquals("rpc-32601", KeylessRpc8359.failKey8440(200, -32601))
        assertEquals("http403", KeylessRpc8359.failKey8440(403, 0))
        KeylessRpc8359.resetForTests8359()
        KeylessRpc8359.afterCall8359("https://solana-rpc.publicnode.com", 403)
        assertTrue(KeylessRpc8359.statusLine8359().contains("fails[http403=1]"))
        KeylessRpc8359.resetForTests8359()
    }
}
