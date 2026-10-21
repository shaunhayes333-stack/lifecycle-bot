package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8272 — directive R10 decisive test: decision at t0, five-minute loss, four-hour revision becomes a gain,
 * validation fold at t0+30 min. The gain must not change that fold's series.
 */
class Aate8272WalkForwardLeakTest {
    @Test fun aLaterHorizonCannotRewriteTheFiveMinuteTarget() {
        val book = CortexGradeBook8144()
        val t0 = 1_700_000_000_000L
        val id = "MINT|MOONSHOT|$t0"
        assertTrue(book.addDecision(CortexGradeRecord8144('D', id, "MINT|MOONSHOT", t0, "MOONSHOT", "S", "BULL", "MID", "C3_6", "MC_10K_100K", 3, 4.5, true, true, false)))
        // five-minute label: a loss, available at t0+5m
        assertTrue(book.label(id, -6.0, -4.0, -6.0, true, availableAtMs = t0 + 5 * 60_000L))
        val r = book.decision(id)!!
        val fiveMin = r.executablePct
        assertTrue(fiveMin.isFinite() && fiveMin < 0.0)
        val foldBefore = book.executableTimed8196("MOONSHOT", null, null)
        assertEquals(1, foldBefore.size)
        // four-hour revision: a gain, knowable only at t0+4h
        assertTrue(book.label(id, +80.0, +90.0, +80.0, true, availableAtMs = t0 + 4 * 3_600_000L))
        assertEquals(fiveMin, r.executablePct, 1e-12)                                 // immutable first target
        assertTrue(r.executableRevisedPct > 0.0)                                       // stored apart
        assertEquals(t0 + 4 * 3_600_000L, r.revisedAvailableAtMs)
        assertEquals(1, r.revisions8272)
        assertEquals(80.0, r.observedNetPct, 1e-12)                                    // the observed record follows every horizon
        val foldAfter = book.executableTimed8196("MOONSHOT", null, null)
        assertEquals(foldBefore.map { it.netPct }, foldAfter.map { it.netPct })         // the t0+30 min fold is unchanged
        assertEquals(foldBefore.map { it.atMs }, foldAfter.map { it.atMs })
    }
}

/** V5.0.8272 — directive R1: lockouts are considered before the attempt budget; a local decline is not a 90 s provider fact. */
class Aate8272SupplyLadderTest {
    @Test fun lockedRungsAreSkippedBeforeTheBudgetAndLocalDeclineRetriesSoon() {
        val a = com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
        val ladder = listOf("https://a.rpc", "https://b.rpc", "https://c.rpc", "https://d.rpc")
        val locked = setOf("rpc_a.rpc", "rpc_b.rpc", "rpc_c.rpc")
        assertEquals(listOf("https://d.rpc"), a.eligibleRungs8272(ladder) { it in locked })        // the healthy fourth is reached
        assertEquals(ladder, a.eligibleRungs8272(ladder) { false })
        assertTrue(a.eligibleRungs8272(listOf("", "https://a.rpc")) { it in locked }.isEmpty())
        assertEquals(10_000L, a.retryDelayMs8272(com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.Outcome7116.DECLINED))
        assertEquals(90_000L, a.retryDelayMs8272(com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.Outcome7116.RATE_LIMITED))
        assertEquals(90_000L, a.retryDelayMs8272(com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.Outcome7116.TRANSPORT))
    }
}
