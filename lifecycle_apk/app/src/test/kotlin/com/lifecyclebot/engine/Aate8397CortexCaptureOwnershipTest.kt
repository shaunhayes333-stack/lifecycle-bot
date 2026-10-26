package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCaptureOwnership8397
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8397CortexCaptureOwnershipTest {
    @Test fun runningOlderCaptureCannotOverwriteNewerDecisionOrItsContext() {
        val owner = CortexCaptureOwnership8397()
        val old = owner.claim("mint|MOONSHOT", 1_000)!!
        val entered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var pending: Pair<Long, String>? = null
        var context: String? = null
        try {
            val slow = pool.submit<Boolean> {
                val frozen = "original-no"
                entered.countDown()
                check(releaseOld.await(5, TimeUnit.SECONDS))
                owner.publish(old) { pending = old.atMs to frozen; context = "old-context" }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val newer = owner.claim("mint|MOONSHOT", 2_000)!!
            assertTrue(pool.submit<Boolean> {
                owner.publish(newer) { pending = newer.atMs to "new-yes"; context = "new-context" }
            }.get(5, TimeUnit.SECONDS))
            releaseOld.countDown()
            assertFalse(slow.get(5, TimeUnit.SECONDS))
            assertEquals(2_000L to "new-yes", pending)
            assertEquals("new-context", context)
            assertFalse("Publishing twice cannot repeat side effects", owner.publish(newer) { fail("duplicate") })
        } finally { releaseOld.countDown(); pool.shutdownNow() }
    }

    @Test fun unavailableTerminalPreventsResurrectionButDoesNotCloseANewerEpisode() {
        val owner = CortexCaptureOwnership8397()
        val old = owner.claim("mint|CORE", 1_000)!!
        owner.terminal(old.key, old.atMs)
        assertFalse(owner.publish(old) { fail("resurrected terminal") })
        assertNull(owner.claim(old.key, old.atMs))
        val newer = owner.claim(old.key, 2_000)!!
        owner.terminal(old.key, old.atMs) // durable old outbox retry
        assertTrue(owner.publish(newer) {})
    }

    @Test fun admissionAndPublicationShareTheLockEvenWhenAnOlderFreezeEnqueuesLate() {
        val owner = CortexCaptureOwnership8397()
        val old = owner.claim("mint|CORE", 1_000)!!
        val newer = owner.claim("mint|CORE", 2_000)!!
        assertFalse(owner.current(old))
        assertTrue(owner.current(newer))
        assertNull(owner.claim("mint|CORE", 1_500))
        assertNull(owner.claim("restored|CORE", 3_000, restoredAtMs = 3_001))
    }

    @Test fun boundedOwnersNeverEvictUnfinishedCapturesAndUseGenerationAfterRetirement() {
        val owner = CortexCaptureOwnership8397(capacity = 2)
        val a = owner.claim("a", 1_000)!!
        val b = owner.claim("b", 1_000)!!
        assertNull(owner.claim("c", 2_000))
        assertTrue(owner.current(a)); assertTrue(owner.current(b))
        owner.terminal(a.key, a.atMs)
        assertNotNull(owner.claim("c", 2_000))
        assertEquals(2, owner.size())
        assertFalse(owner.publish(a) { fail("retired generation") })
    }
}
