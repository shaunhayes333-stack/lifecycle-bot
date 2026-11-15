package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8777 — code audit (data P1-10): one coin decision is learned once by the global model and the cross-lane books. */
class Aate8777OneCoinOnceTest {
    @Test fun a_coin_judged_by_many_lanes_counts_once() {
        assertTrue(EdgeModel8537.onceAcrossLanes8776("coin8777", 5_000L))
        assertFalse("the second lane's label of the same decision", EdgeModel8537.onceAcrossLanes8776("coin8777", 5_000L))
        assertTrue("a later decision is new", EdgeModel8537.onceAcrossLanes8776("coin8777", 6_000L))
        assertTrue(ForwardReturnLabeler7731.firstCrossLane8776(60, "AGG|ADMITTED", "coin8777", 5_000L))
        assertFalse(ForwardReturnLabeler7731.firstCrossLane8776(60, "AGG|ADMITTED", "coin8777", 5_000L))
        assertTrue("another horizon is its own book", ForwardReturnLabeler7731.firstCrossLane8776(240, "AGG|ADMITTED", "coin8777", 5_000L))
    }
}
