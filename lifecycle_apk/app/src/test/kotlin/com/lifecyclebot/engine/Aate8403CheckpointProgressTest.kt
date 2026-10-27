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
    @Test fun actualFlushCallerReturnsWhilePersistenceIsBusyAndFailedCommitKeepsReceiptPending() {
        val owner = Cortex7885
        fun field(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val loaded = field("loaded"); val oldLoaded = loaded.get(owner)
        val lastFlush = field("shadowFlushedAtMs8377"); val oldLastFlush = lastFlush.get(owner)
        val pool = field("persistPool8094").get(owner) as ExecutorService
        val terminals = field("observationTerminals8395").get(owner) as MutableMap<String, String>
        val dbField = LearningPersistence.javaClass.getDeclaredField("db").apply { isAccessible = true }
        val oldDb = dbField.get(LearningPersistence)
        val db = org.mockito.Mockito.mock(android.database.sqlite.SQLiteDatabase::class.java)
        org.mockito.Mockito.`when`(db.inTransaction()).thenReturn(true) // a real commit must return RETRY
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        val receipt = "audit8403-terminal"
        try {
            pool.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            loaded.set(owner, true); lastFlush.set(owner, 0L); dbField.set(LearningPersistence, db)
            terminals[receipt] = "{}"
            assertFalse("scheduled is not acknowledged", caller.submit<Boolean> { owner.flushShadowOutcomes8372() }.get(2, TimeUnit.SECONDS))
            assertTrue(terminals.containsKey(receipt))
            release.countDown()
            pool.submit {}.get(10, TimeUnit.SECONDS)
            org.mockito.Mockito.verify(db).inTransaction()
            assertTrue("a refused database commit cannot discard the pending receipt", terminals.containsKey(receipt))
        } finally {
            release.countDown()
            try { pool.submit {}.get(10, TimeUnit.SECONDS) } finally {
                caller.shutdownNow(); terminals.remove(receipt); loaded.set(owner, oldLoaded)
                lastFlush.set(owner, oldLastFlush); dbField.set(LearningPersistence, oldDb)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun wideFrozenPredictionCheckpointStreamsGenerationAndAllVoterValues() {
        val owner = Cortex7885
        val field = owner.javaClass.getDeclaredField("pending").apply { isAccessible = true }
        val pending = field.get(owner) as MutableMap<String, Any>
        val saved = HashMap(pending)
        val ctor = Class.forName("${owner.javaClass.name}\$Pending").declaredConstructors
            .single { it.parameterCount == 7 }.apply { isAccessible = true }
        val method = owner.javaClass.declaredMethods.single { it.name == "pendingSnapshot8076" && it.parameterCount == 2 }
            .apply { isAccessible = true }
        val now = System.currentTimeMillis()
        val episodes = LinkedHashSet<String>()
        try {
            pending.clear()
            repeat(500) { row ->
                val at = now - 1000 + row; val key = "wide8403-$row|MOONSHOT"
                val a = Cortex7885.Assessment("MOONSHOT", true, DoubleArray(303) { row + it / 1000.0 },
                    List(303) { "VOTER_$it" }, List(303) { doubleArrayOf(-1.0, 0.0, 1.0) }, "TEST",
                    com.lifecyclebot.engine.cortex.CortexLedger7885.Fused(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()),
                    1.0, com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG, 0, false, at)
                pending[key] = ctor.newInstance(a, false, at, null, "TEST", "SETUP", emptyList<String>())
                episodes += "$key|$at"
            }
            val encode = synchronized(owner) { method.invoke(owner, episodes, 42L) as () -> String }
            pending.clear()
            val root = org.json.JSONObject(encode())
            assertEquals(42L, root.getLong("snapshotGeneration8386"))
            assertEquals(303, root.getJSONArray("dict").length())
            assertEquals(0, root.getJSONArray("g8013").length())
            assertEquals(0, root.getJSONArray("unpaired8401").length())
            val rows = root.getJSONArray("p")
            assertEquals(500, rows.length())
            repeat(500) { row ->
                val xs = rows.getJSONObject(row).getJSONArray("x")
                assertEquals(303, xs.length())
                assertEquals(row + 0.302, xs.getDouble(302), 0.0)
                assertEquals(303, rows.getJSONObject(row).getJSONArray("ee8372").length())
            }
        } finally { pending.clear(); pending.putAll(saved) }
    }

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
