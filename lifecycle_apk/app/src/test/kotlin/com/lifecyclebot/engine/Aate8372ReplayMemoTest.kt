package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ReconciliationPublication8372
import com.lifecyclebot.engine.truth.RevisionedReplayMemo8372
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual production cache/publication helpers; no copied replay or reconciliation algorithm. */
class Aate8372ReplayMemoTest {
    private data class Snapshot(val revision: Long, val cash: Double, val openCost: Double)

    private fun memo() = RevisionedReplayMemo8372<Snapshot> { it.revision }
    private fun key(revision: Long, startingCash: Double = 12.0, quarantine: String = "0:0") =
        "$revision|$startingCash|$quarantine"

    @Test fun a_result_cannot_be_published_under_another_journal_revision() {
        val memo = memo()
        val correct = Snapshot(101, 10.161408, 2.0)
        val stale = Snapshot(100, 11.161408, 1.0)
        assertTrue(memo.publish(key(101), 101, correct))

        // This is the invalid state reachable by the old independently published key/result references.
        assertFalse(memo.publish(key(101), 101, stale))
        assertSame(correct, memo.get(key(101), 101))
        assertSame(correct, memo.latest())
    }

    @Test fun a_late_old_completion_cannot_replace_the_newer_atomic_pair() {
        val memo = memo()
        val oldReady = CountDownLatch(1)
        val newPublished = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val stale = Snapshot(100, 11.161408, 1.0)
        val current = Snapshot(101, 10.161408, 2.0)
        try {
            val oldCompletion = workers.submit<Boolean> {
                oldReady.countDown()
                check(newPublished.await(5, TimeUnit.SECONDS)) { "New replay did not publish" }
                memo.publish(key(100), 100, stale)
            }
            val newCompletion = workers.submit<Boolean> {
                check(oldReady.await(5, TimeUnit.SECONDS)) { "Old replay was not ready" }
                try { memo.publish(key(101), 101, current) }
                finally { newPublished.countDown() }
            }
            assertTrue(newCompletion.get(5, TimeUnit.SECONDS))
            assertFalse(oldCompletion.get(5, TimeUnit.SECONDS))
            assertSame(current, memo.get(key(101), 101))
            assertNull(memo.get(key(100), 100))
            assertSame(current, memo.latest())
        } finally {
            newPublished.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun unchanged_revision_and_full_input_key_reuse_the_exact_result() {
        val memo = memo()
        val current = Snapshot(101, 10.161408, 2.0)
        assertNull(memo.get(key(101), 101))
        assertTrue(memo.publish(key(101), 101, current))
        repeat(20) { assertSame(current, memo.get(key(101), 101)) }
        assertNull(memo.get(key(101, startingCash = 24.0), 101))
        assertNull(memo.get(key(101, quarantine = "1:77"), 101))
        assertNull(memo.get(key(101), 102))
    }

    @Test fun same_revision_different_inputs_do_not_share_a_result() {
        val memo = memo()
        val fullScope = Snapshot(101, 10.161408, 2.0)
        val quarantineScope = Snapshot(101, 11.161408, 1.0)
        assertTrue(memo.publish(key(101), 101, fullScope))
        assertTrue(memo.publish(key(101, quarantine = "1:77"), 101, quarantineScope))
        assertNull(memo.get(key(101), 101))
        assertSame(quarantineScope, memo.get(key(101, quarantine = "1:77"), 101))
    }

    @Test fun clearing_invalidates_both_key_and_result_together() {
        val memo = memo()
        assertTrue(memo.publish(key(101), 101, Snapshot(101, 10.161408, 2.0)))
        memo.clear()
        assertNull(memo.get(key(101), 101))
        assertNull(memo.latest())
        assertTrue(memo.publish(key(1), 1, Snapshot(1, 12.0, 0.0)))
        assertNotNull(memo.get(key(1), 1))
    }

    @Test fun reconciliation_does_not_publish_its_key_before_the_pass_completes() {
        val completed = AtomicReference<String?>(null)
        val publication = ReconciliationPublication8372(completed)
        val first = requireNotNull(publication.claim("100|positions|receipts|cash"))
        assertTrue(publication.isBusy())
        assertNull(publication.completedKey())
        assertNull(completed.get())
        assertNull(publication.claim(first.key))
        assertNull(publication.claim("101|positions|receipts|cash"))
        assertTrue(publication.finish(first, first.key, coherent = true))
        assertFalse(publication.isBusy())
        assertEquals(first.key, publication.completedKey())
        assertEquals(first.key, completed.get())
        assertNull(publication.claim(first.key))
    }

    @Test fun a_state_change_during_reconciliation_is_not_cached_as_current() {
        val publication = ReconciliationPublication8372()
        val first = requireNotNull(publication.claim("100|p1|r1|cash1"))
        assertFalse(publication.finish(first, "101|p2|r2|cash2", coherent = true))
        assertFalse(publication.isBusy())
        assertNull(publication.completedKey())
        val retry = requireNotNull(publication.claim("101|p2|r2|cash2"))
        assertTrue(publication.finish(retry, retry.key, coherent = true))
        assertEquals(retry.key, publication.completedKey())
    }

    @Test fun repeated_old_completion_cannot_publish_or_release_a_new_lease() {
        val publication = ReconciliationPublication8372()
        val first = requireNotNull(publication.claim("100|p1|r1|cash1"))
        assertFalse(publication.finish(first, first.key, coherent = false))
        val second = requireNotNull(publication.claim("101|p2|r2|cash2"))
        assertFalse(publication.finish(first, first.key, coherent = true))
        assertTrue(publication.isBusy())
        assertNull(publication.completedKey())
        assertTrue(publication.finish(second, second.key, coherent = true))
        assertFalse(publication.finish(first, first.key, coherent = true))
        assertEquals(second.key, publication.completedKey())
    }

    @Test fun unknown_or_incoherent_snapshots_release_without_suppressing_the_next_check() {
        val publication = ReconciliationPublication8372()
        val unknown = requireNotNull(publication.claim(null))
        assertFalse(publication.finish(unknown, null, coherent = true))
        assertFalse(publication.isBusy())
        assertNull(publication.completedKey())
        val incoherent = requireNotNull(publication.claim("101|p|r|cash"))
        assertFalse(publication.finish(incoherent, incoherent.key, coherent = false))
        assertFalse(publication.isBusy())
        assertNull(publication.completedKey())
        assertNotNull(publication.claim(incoherent.key))
    }

    @Test fun changed_snapshot_never_publishes_its_accounting_figures() {
        val publication = ReconciliationPublication8372()
        val lease = requireNotNull(publication.claim("100|p1|r1|cash1"))
        var published = false
        assertFalse(publication.finish(lease, "101|p2|r2|cash2", coherent = true) { published = true })
        assertFalse(published)
        assertFalse(publication.isBusy())
        assertNull(publication.completedKey())
    }

    @Test fun a_publication_exception_does_not_mark_the_key_complete_or_leave_the_gate_busy() {
        val publication = ReconciliationPublication8372()
        val lease = requireNotNull(publication.claim("101|p|r|cash"))
        try {
            publication.finish(lease, lease.key, coherent = true) { error("publication failed") }
            fail("Publication failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("publication failed", expected.message)
        }
        assertFalse(publication.isBusy())
        assertNull(publication.completedKey())
        assertNotNull(publication.claim(lease.key))
    }

    @Test fun coherent_failure_status_is_published_before_its_completed_key_and_remains_failed() {
        val publication = ReconciliationPublication8372()
        val lease = requireNotNull(publication.claim("101|p|r|cash"))
        var status = "UNKNOWN"
        assertTrue(publication.finish(lease, lease.key, coherent = true) {
            assertTrue(publication.isBusy())
            assertNull(publication.completedKey())
            assertNull(publication.claim("102|newer"))
            // Coherence is not accounting agreement: an actual current-revision failure is still published.
            status = "FAILED"
        })
        assertEquals("FAILED", status)
        assertEquals(lease.key, publication.completedKey())
        assertFalse(publication.isBusy())
    }
}
