package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import org.junit.Assert.*
import org.junit.Test

class Aate8509BirthPriorityTest {
    @Test fun latestBirthWalkIsQueuedAheadOfOlderUnstartedWalks() {
        val q = TokenBirthHydrator7441.NewestFirstQueue8509(3)
        val old = Runnable {}
        val mid = Runnable {}
        val fresh = Runnable {}
        assertTrue(q.offer(old))
        assertTrue(q.offer(mid))
        assertTrue(q.offer(fresh))
        assertSame(fresh, q.poll())
        assertSame(mid, q.poll())
        assertSame(old, q.poll())
    }

    @Test fun fullQueueCanDiscardOldestWithoutDroppingNewArrival() {
        val q = TokenBirthHydrator7441.NewestFirstQueue8509(2)
        val old = Runnable {}
        val mid = Runnable {}
        val fresh = Runnable {}
        assertTrue(q.offer(old))
        assertTrue(q.offer(mid))
        assertFalse(q.offer(fresh))
        assertSame(old, q.pollLast())
        assertTrue(q.offer(fresh))
        assertSame(fresh, q.poll())
        assertSame(mid, q.poll())
    }
}
