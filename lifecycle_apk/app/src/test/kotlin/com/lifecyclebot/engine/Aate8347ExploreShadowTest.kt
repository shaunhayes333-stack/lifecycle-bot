package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreShadow8347
import org.junit.Assert.*
import org.junit.Test

/** 8347 — free exploration in shadow: pullback depth, reject sampling, the stricter six-variant proof. */
class Aate8347ExploreShadowTest {
    @Test fun pullbackIsHalfTheStopBetweenFourAndTwelve() {
        assertEquals(4.0, ExploreShadow8347.pullbackPct8347(5.0), 1e-9)
        assertEquals(7.5, ExploreShadow8347.pullbackPct8347(15.0), 1e-9)
        assertEquals(12.0, ExploreShadow8347.pullbackPct8347(40.0), 1e-9)
    }

    @Test fun rejectSampleIsDeterministicAndRoughlyTenPercent() {
        // superseded by V5.0.8360 — every non-safety reject opens (REJECT_SAMPLE_PCT_8347 = 100); the sampler itself is
        // unchanged and still deterministic at any percentage, so the 10 % behaviour is pinned with an explicit pct.
        assertEquals(100, ExploreShadow8347.REJECT_SAMPLE_PCT_8347)
        assertTrue((0 until 2_000).all { ExploreShadow8347.sampled8347("mint$it", "SHITCOIN") })
        val hits = (0 until 2_000).count { ExploreShadow8347.sampled8347("mint$it", "SHITCOIN", 10) }
        assertTrue("$hits", hits in 120..280)
        assertEquals(ExploreShadow8347.sampled8347("abc", "MOONSHOT"), ExploreShadow8347.sampled8347("abc", "MOONSHOT"))
    }

    @Test fun variantProofNeedsFortyClosesAndMeanMinusOneAndAHalfSe() {
        assertFalse(ExploreShadow8347.variantProof8347(List(39) { 20.0 }).proven)
        val clear = List(60) { if (it % 4 == 0) -8.0 else 15.0 }
        assertTrue(ExploreShadow8347.variantProof8347(clear).proven)
        val thin = List(60) { if (it % 2 == 0) 9.0 else -4.0 }   // mean +2.5, se ~0.85 -> 2.5 - 1.3 < 2
        assertFalse(ExploreShadow8347.variantProof8347(thin).proven)
    }

    @Test fun v8399CohortChoiceNeverScansOnTheCallersThreadAfterItsFirstRead() {
        // V5.0.8399 — the lane choice is computed once on first read, then served from cache and refreshed on its own thread;
        // the status line carries the accounting so a stale serve / cold miss is visible in the report.
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        val body = src.substringAfter("fun bestFor8351(lane: String, sinceMs: Long): Choice8351? {").substringBefore("private fun computeBest8351(")
        // superseded by V5.0.8418 — freshness is the lane's book revision (choiceFreshness8418), not a bare 30 s stamp
        assertTrue(body.contains("if (fresh == ChoiceFreshness8418.FRESH) { choiceFresh8418.incrementAndGet(); return hit?.choice }"))
        assertTrue(body.contains("if (choiceRefreshing8399.add(cacheKey8394))"))
        assertTrue(body.contains("choiceExecutor8399.execute {"))
        // superseded by V5.0.8418 — a stale answer is served only inside the grace window, never a scan on the caller's thread
        assertTrue("a stale answer is served, never a scan", body.contains("return choiceServed8418(fresh, hit?.choice)"))
        assertTrue(ExploreShadow8347.choiceStats8399().startsWith("choice8399[staleServed="))
        assertTrue(src.contains("opened=${'$'}{opened.get()} ${'$'}{choiceStats8399()} "))
    }
}
