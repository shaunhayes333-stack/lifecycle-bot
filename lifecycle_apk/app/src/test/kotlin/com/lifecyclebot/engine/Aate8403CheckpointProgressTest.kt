package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Aate8403CheckpointProgressTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun unacknowledgedRecordsDoNotKeepLaterTerminalsOutOfTheConsumerBatch() {
        val owner = ForwardReturnLabeler7731
        fun field(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val outbox = field("terminalOutbox8395").get(owner) as MutableMap<String, String>
        val ack = field("deliveredTerminals8395").get(owner) as MutableSet<String>
        val cursor = field("terminalCursor8403")
        val saved = HashMap(outbox); val savedAck = HashSet(ack); val oldCursor = cursor.get(owner)
        val batch = owner.javaClass.getDeclaredMethod("terminalBatch8401").apply { isAccessible = true }
        try {
            outbox.clear(); ack.clear(); cursor.set(owner, "")
            repeat(300) { outbox["terminal|$it"] = "{}" }
            val offered = HashSet<String>()
            repeat(5) {
                val rows = batch.invoke(owner) as List<Map.Entry<String, String>>
                assertTrue(rows.size <= 64)
                offered.addAll(rows.map { it.key })
            }
            assertEquals("the 256-record checkpoint can fill without waiting for a timeout", 300, offered.size)
            assertTrue("offered is never an ACK", ack.isEmpty())
            assertEquals("all remain durable until the consumer commits", 300, outbox.size)
            ack.addAll(offered.take(290))
            val retry = batch.invoke(owner) as List<Map.Entry<String, String>>
            assertEquals(10, retry.size); assertTrue(retry.none { it.key in ack })
        } finally {
            outbox.clear(); outbox.putAll(saved); ack.clear(); ack.addAll(savedAck); cursor.set(owner, oldCursor)
        }
    }

    @Test fun blockedCheckpointDoesNotBlockProducerAndOnlyOneFlushCanQueue() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val running = AtomicBoolean(false); val commits = AtomicInteger()
        try {
            assertTrue(Cortex7885.enqueueCheckpoint8403(executor, running) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                commits.incrementAndGet()
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(0, commits.get())
            assertTrue(running.get())
            repeat(100) { assertFalse(Cortex7885.enqueueCheckpoint8403(executor, running) { commits.incrementAndGet() }) }
            release.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, commits.get()); assertFalse(running.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun rejectedAndFailedCheckpointsReleaseOwnershipForRetry() {
        val running = AtomicBoolean(false)
        val rejected = Executor { throw RejectedExecutionException("test capacity") }
        assertFalse(Cortex7885.enqueueCheckpoint8403(rejected, running) {})
        assertFalse(running.get())
        val direct = Executor { it.run() }
        assertThrows(IllegalStateException::class.java) {
            Cortex7885.enqueueCheckpoint8403(direct, running) { error("test storage failure") }
        }
        assertFalse(running.get())
        var committed = false
        assertTrue(Cortex7885.enqueueCheckpoint8403(direct, running) { committed = true })
        assertTrue(committed); assertFalse(running.get())
    }
}
