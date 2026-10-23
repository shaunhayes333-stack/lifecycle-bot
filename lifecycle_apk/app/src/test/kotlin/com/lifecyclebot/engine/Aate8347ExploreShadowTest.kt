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
        val hits = (0 until 2_000).count { ExploreShadow8347.sampled8347("mint$it", "SHITCOIN") }
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
}
