package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Real Cortex grading/capture and LearningPersistence transactions; only the Android SQLite API is adapted to JDBC. */
class Aate9045CortexCloseDeliveryTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T = field(owner, name).get(owner) as T
    private fun write(owner: Any, name: String, value: Any?) { field(owner, name).set(owner, value) }
    private fun receipt(id: String) = "CORTEX_CLOSE_RECEIPT_8198|$id"

    private class JdbcStore : AutoCloseable {
        private val file = Files.createTempFile("cortex-close-9045-", ".db")
        private val writer = DriverManager.getConnection("jdbc:sqlite:$file")
        private val reader: Connection
        private val writerLock = ReentrantLock()
        private val readerLock = Any()
        @Volatile private var owner: Thread? = null
        private var successful = false
        private val writtenKeys = linkedSetOf<String>()
        @Volatile var beforeCommit: ((Set<String>) -> Unit)? = null
        val failNextCommit = AtomicBoolean(false)
        val failedCommit = CountDownLatch(1)

        init {
            writer.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE kv (tracker TEXT NOT NULL, bucket TEXT NOT NULL, payload TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(tracker,bucket))")
            }
            reader = DriverManager.getConnection("jdbc:sqlite:$file")
        }

        val database: SQLiteDatabase = mock(SQLiteDatabase::class.java, Answer { call ->
            when (call.method.name) {
                "isOpen" -> true
                "inTransaction" -> owner === Thread.currentThread()
                "beginTransaction" -> {
                    writerLock.lock()
                    writer.autoCommit = false; owner = Thread.currentThread(); successful = false; writtenKeys.clear()
                    null
                }
                "setTransactionSuccessful" -> { successful = true; null }
                "endTransaction" -> {
                    try {
                        if (successful) {
                            beforeCommit?.invoke(writtenKeys.toSet())
                            if (failNextCommit.compareAndSet(true, false)) {
                                failedCommit.countDown(); throw SQLException("injected rollback before durable commit")
                            }
                            writer.commit()
                        } else writer.rollback()
                    } catch (failure: Throwable) { writer.rollback(); throw failure }
                    finally { writer.autoCommit = true; owner = null; writerLock.unlock() }
                    null
                }
                "execSQL" -> {
                    val sql = call.arguments[0] as String
                    val args = (call.arguments.getOrNull(1) as? Array<*>)?.toList().orEmpty()
                    writerLock.withLock {
                        writer.prepareStatement(sql).use { statement ->
                            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                            statement.executeUpdate()
                        }
                        if (sql.contains("VALUES ('BLOB',")) writtenKeys.add(args[0] as String)
                    }
                    null
                }
                "rawQuery" -> {
                    val sql = call.arguments[0] as String
                    val args = (call.arguments.getOrNull(1) as? Array<*>)?.toList().orEmpty()
                    val rows = if (owner === Thread.currentThread()) query(writer, sql, args)
                        else synchronized(readerLock) { query(reader, sql, args) }
                    cursor(rows)
                }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
            }
        })

        private fun query(db: Connection, sql: String, args: List<Any?>): List<List<Any?>> = db.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                val rows = ArrayList<List<Any?>>()
                while (result.next()) rows.add((1..result.metaData.columnCount).map { result.getObject(it) })
                rows
            }
        }
        private fun cursor(rows: List<List<Any?>>): Cursor {
            var at = -1
            return mock(Cursor::class.java, Answer { call ->
                when (call.method.name) {
                    "moveToFirst" -> { at = 0; rows.isNotEmpty() }
                    "moveToNext" -> { at++; at < rows.size }
                    "getCount" -> rows.size
                    "getString" -> rows[at][call.arguments[0] as Int]?.toString()
                    "getLong" -> (rows[at][call.arguments[0] as Int] as Number).toLong()
                    "getInt" -> (rows[at][call.arguments[0] as Int] as Number).toInt()
                    "isNull" -> rows[at][call.arguments[0] as Int] == null
                    else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
                }
            })
        }
        override fun close() {
            reader.close(); writer.close()
            Files.deleteIfExists(file); Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal")); Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
        }
    }

    private inner class Fixture : AutoCloseable {
        val store = JdbcStore()
        val book = read<CortexGradeBook8144>(Cortex7885, "gradeBook8144")
        val worker = read<ScheduledExecutorService>(Cortex7885, "persistPool8094")
        val persistLock = read<Any>(Cortex7885, "persistLock8325")
        private val oldDatabase = read<SQLiteDatabase?>(LearningPersistence, "db")
        private val savedBook = synchronized(Cortex7885) { book.detached8386() }
        private val board = read<CortexScoreboard7885>(Cortex7885, "board")
        private val savedBoard = synchronized(Cortex7885) { board.detached8386() }
        private val stateNames = listOf("loaded", "snapshotGeneration8386", "lastFullEncodeEndNanos9045",
            "snapshotEncodeMs8386", "snapshotDbMs8386", "lastPeriodicSnapshotMs8429", "lastPendingPersistMs", "lastExecRefresh8187")
        private val savedState = stateNames.associateWith { read<Any?>(Cortex7885, it) }
        private val setNames = listOf("uncommittedCloseGrades8198", "closeReceipts8198", "batchCommitted9031")
        private val savedSets = setNames.associateWith { read<MutableSet<String>>(Cortex7885, it).toSet() }
        private val refreshMaps = listOf("execOffsetByLane8187", "realisedBySetup8187", "realisedByLane8291", "lanePonds8325", "tailPonds8324")
            .associateWith { read<MutableMap<String, Any>>(Cortex7885, it).toMap() }
        private val offsets = listOf("jointBook7961", "vetoBook").flatMap {
            read<MutableMap<String, CortexLedger7885.Stat>>(Cortex7885, it).values.map { stat -> stat to stat.readOffset8187 }
        }
        private val caches = listOf("rankCache8325", "pondCache8325").map { name ->
            val cache = read<Any>(Cortex7885, name)
            val fields = cache.javaClass.declaredFields.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && !java.lang.reflect.Modifier.isFinal(it.modifiers)
            }.associateWith { it.isAccessible = true; it.get(cache) }
            Triple(cache, fields, read<AtomicLong>(cache, "computes").get())
        }
        private val oldImportHold = read<Boolean>(LearningPersistence, "importAwaitingRestart8086")
        private val unreadable = read<MutableSet<String>>(LearningPersistence, "unreadable8086")
        private val oldUnreadable = unreadable.toSet()
        private val readFailures = read<MutableMap<String, Long>>(LearningPersistence, "readFailAtMs8086")
        private val oldReadFailures = readFailures.toMap()
        private val ids = ArrayList<String>()

        init {
            worker.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue("the isolated store fixture must begin without another fixture's pending grades", savedSets.getValue("uncommittedCloseGrades8198").isEmpty())
            assertTrue("canceled close timers must leave the actual worker queue", (worker as ScheduledThreadPoolExecutor).removeOnCancelPolicy)
            write(LearningPersistence, "db", store.database)
            write(LearningPersistence, "importAwaitingRestart8086", false)
            unreadable.clear(); readFailures.clear()
            write(Cortex7885, "loaded", true)
            write(Cortex7885, "lastFullEncodeEndNanos9045", 0L)
            write(Cortex7885, "snapshotEncodeMs8386", 0L)
            write(Cortex7885, "snapshotDbMs8386", 0L)
        }

        fun prepare(label: String): CanonicalFinalizedTradeBus6464.Envelope {
            val id = "9045-$label-${System.nanoTime()}"; ids.add(id)
            val mint = "mint-$id"; val at = System.currentTimeMillis()
            val record = CortexGradeRecord8144('T', id, "$mint|CORE", at - 60_000L, "CORE", "BASE_BREAKOUT",
                "UPTREND", "EARLY", "C3_6", "SMALL", CortexScoreboard7885.Bucket.STRONG.ordinal, 8.0, true, true, false)
            record.mode = "PAPER"; record.scoreVersion8372 = CortexForwardEpoch8521.SCORE_VERSION
            synchronized(Cortex7885) { assertTrue(book.openTrade(record)) }
            return CanonicalFinalizedTradeBus6464.Envelope(tradeId = id, atMs = at, realizedPnlSol = 0.05,
                realizedReturnPct = 12.5, mint = mint, lane = "CORE", positionId = id, mode = "paper",
                proofState = "PAPER_SIMULATED", terminal = true, learningEligible = true)
        }

        fun awaitReceipt(env: CanonicalFinalizedTradeBus6464.Envelope) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (LearningPersistence.load(receipt(env.tradeId)) == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertNotNull("exact close receipt must become durable without another delivery", LearningPersistence.load(receipt(env.tradeId)))
            worker.submit {}.get(30, TimeUnit.SECONDS)
        }
        fun durableBook(): CortexGradeBook8144 = CortexGradeBook8144().also {
            it.decode(LearningPersistence.load(CortexForwardEpoch8521.GRADES) ?: error("no durable grade book"))
        }
        override fun close() {
            store.beforeCommit = null
            worker.submit {}.get(30, TimeUnit.SECONDS)
            // A queue barrier does not consume a future paced task. Retire this fixture's exact
            // obligations, request a real no-pending drain, and observe the coordinator idle
            // before restoring the prior database or books.
            synchronized(Cortex7885) { read<MutableSet<String>>(Cortex7885, "uncommittedCloseGrades8198").removeAll(ids.toSet()) }
            write(Cortex7885, "lastFullEncodeEndNanos9045", 0L)
            val drain = read<CortexCloseCheckpoint9045>(Cortex7885, "closeCheckpoint9045")
            drain.request()
            val gate = read<Any>(drain, "gate")
            fun idle() = synchronized(gate) { read<Any?>(drain, "queued") == null && !read<Boolean>(drain, "running") }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(31)
            while (!idle() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("no delayed close task may escape into the next fixture", idle())
            worker.submit {}.get(5, TimeUnit.SECONDS)
            synchronized(Cortex7885) {
                // These are owner-state restores, not persisted-input merges. An empty
                // GradeBook decode is a no-op and Scoreboard decode keeps absent keys;
                // both would leave this fixture's closes in the next test's model.
                // Detached state also preserves lastGraded and full numeric precision.
                read<MutableList<CortexGradeRecord8144>>(book, "decisions").apply {
                    clear(); addAll(read<List<CortexGradeRecord8144>>(savedBook, "decisions"))
                }
                for (name in listOf("byId", "trades")) {
                    read<MutableMap<String, CortexGradeRecord8144>>(book, name).apply {
                        clear(); putAll(read<Map<String, CortexGradeRecord8144>>(savedBook, name))
                    }
                }
                read<MutableMap<String, Long>>(book, "lastGraded").apply {
                    clear(); putAll(read<Map<String, Long>>(savedBook, "lastGraded"))
                }
                board.books.apply { clear(); putAll(savedBoard.books) }
                board.realized.apply { clear(); putAll(savedBoard.realized) }
                savedSets.forEach { (name, values) -> read<MutableSet<String>>(Cortex7885, name).apply { clear(); addAll(values) } }
                refreshMaps.forEach { (name, values) -> read<MutableMap<String, Any>>(Cortex7885, name).apply { clear(); putAll(values) } }
                offsets.forEach { (stat, offset) -> stat.readOffset8187 = offset }
                caches.forEach { (cache, fields, computes) -> fields.forEach { (field, value) -> field.set(cache, value) }; read<AtomicLong>(cache, "computes").set(computes) }
                savedState.forEach { (name, value) -> write(Cortex7885, name, value) }
            }
            write(LearningPersistence, "db", oldDatabase)
            write(LearningPersistence, "importAwaitingRestart8086", oldImportHold)
            unreadable.clear(); unreadable.addAll(oldUnreadable)
            readFailures.clear(); readFailures.putAll(oldReadFailures)
            store.close()
        }
    }

    @Test fun blockedPersistPipelineDoesNotBlockTheCloseCallerOrGradeARedeliveryTwice() {
        Fixture().use { f ->
            val env = f.prepare("blocked")
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val owner = Thread { synchronized(f.persistLock) { held.countDown(); release.await(20, TimeUnit.SECONDS) } }
            val callers = Executors.newSingleThreadExecutor()
            try {
                owner.start(); assertTrue(held.await(5, TimeUnit.SECONDS))
                val before = Cortex7885.counter8198("REALIZED_RESTORED_TRADE_CLOSED_8521")
                assertEquals(Cortex7885.CloseDelivery8198.RETRY,
                    callers.submit<Cortex7885.CloseDelivery8198> { Cortex7885.onCanonicalClose(env) }.get(5, TimeUnit.SECONDS))
                assertEquals(before + 1L, Cortex7885.counter8198("REALIZED_RESTORED_TRADE_CLOSED_8521"))
                assertEquals(Cortex7885.CloseDelivery8198.RETRY,
                    callers.submit<Cortex7885.CloseDelivery8198> { Cortex7885.onCanonicalClose(env) }.get(5, TimeUnit.SECONDS))
                assertEquals(before + 1L, Cortex7885.counter8198("REALIZED_RESTORED_TRADE_CLOSED_8521"))
                assertNull(LearningPersistence.load(receipt(env.tradeId)))
                assertEquals(12.5, f.book.trade(env.positionId)!!.realisedPct, 0.0)
            } finally { release.countDown(); owner.join(5_000); callers.shutdownNow() }
            f.awaitReceipt(env)
            assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(env))
            assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(env))
            assertEquals(12.5, f.durableBook().trade(env.positionId)!!.realisedPct, 0.0)
            val books = JSONObject(LearningPersistence.load(CortexForwardEpoch8521.BOOKS)!!)
            val pending = JSONObject(LearningPersistence.load(CortexForwardEpoch8521.PENDING)!!)
            assertEquals(books.getLong("snapshotGeneration8386"), pending.getLong("snapshotGeneration8386"))
        }
    }

    @Test fun aCloseArrivingAfterTheFirstCaptureNeedsItsOwnDurableReceipt() {
        Fixture().use { f ->
            val first = f.prepare("first"); val later = f.prepare("later")
            val firstCaptured = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
            val laterCaptured = CountDownLatch(1); val releaseLater = CountDownLatch(1)
            f.store.beforeCommit = { keys ->
                if (receipt(later.tradeId) in keys) { laterCaptured.countDown(); assertTrue(releaseLater.await(20, TimeUnit.SECONDS)) }
                else if (receipt(first.tradeId) in keys) { firstCaptured.countDown(); assertTrue(releaseFirst.await(20, TimeUnit.SECONDS)) }
            }
            try {
                assertEquals(Cortex7885.CloseDelivery8198.RETRY, Cortex7885.onCanonicalClose(first))
                assertTrue(firstCaptured.await(10, TimeUnit.SECONDS))
                assertEquals(Cortex7885.CloseDelivery8198.RETRY, Cortex7885.onCanonicalClose(later))
                assertNull(LearningPersistence.load(receipt(first.tradeId)))
                assertNull(LearningPersistence.load(receipt(later.tradeId)))
                releaseFirst.countDown()
                assertTrue(laterCaptured.await(30, TimeUnit.SECONDS))
                assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(first))
                assertEquals(Cortex7885.CloseDelivery8198.RETRY, Cortex7885.onCanonicalClose(later))
                assertTrue("the first snapshot holds the later trade open", f.durableBook().trade(later.positionId)!!.realisedPct.isNaN())
                releaseLater.countDown()
            } finally { releaseFirst.countDown(); releaseLater.countDown() }
            f.awaitReceipt(later)
            assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(later))
            assertEquals(12.5, f.durableBook().trade(first.positionId)!!.realisedPct, 0.0)
            assertEquals(12.5, f.durableBook().trade(later.positionId)!!.realisedPct, 0.0)
        }
    }

    @Test fun rolledBackCloseCommitRetriesWithoutAnotherDeliveryAndKeepsItsGradeOnce() {
        Fixture().use { f ->
            val env = f.prepare("rollback")
            f.store.failNextCommit.set(true)
            val before = Cortex7885.counter8198("REALIZED_RESTORED_TRADE_CLOSED_8521")
            assertEquals(Cortex7885.CloseDelivery8198.RETRY, Cortex7885.onCanonicalClose(env))
            assertTrue(f.store.failedCommit.await(10, TimeUnit.SECONDS))
            assertNull(LearningPersistence.load(receipt(env.tradeId)))
            // No market tick or redelivery follows the rollback; the scheduled drain owns recovery.
            f.awaitReceipt(env)
            assertEquals(before + 1L, Cortex7885.counter8198("REALIZED_RESTORED_TRADE_CLOSED_8521"))
            assertEquals(12.5, f.durableBook().trade(env.positionId)!!.realisedPct, 0.0)
            assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(env))
        }
    }

    @Test fun interruptedMainThreadFlushStillForcesPendingDurabilityPastThePaceDeadline() {
        Fixture().use { f ->
            val env = f.prepare("shutdown")
            write(Cortex7885, "snapshotEncodeMs8386", 60_000L)
            write(Cortex7885, "lastFullEncodeEndNanos9045", System.nanoTime())
            assertEquals(Cortex7885.CloseDelivery8198.RETRY, Cortex7885.onCanonicalClose(env))
            val held = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
            val owner = Thread { synchronized(f.persistLock) { held.countDown(); release.await(20, TimeUnit.SECONDS) } }
            val main = Thread({ Thread.currentThread().interrupt(); Cortex7885.persistNow7930(); returned.countDown() }, "main")
            try {
                owner.start(); assertTrue(held.await(5, TimeUnit.SECONDS)); main.start()
                assertTrue("interrupted caller returns while its queued durability continues", returned.await(5, TimeUnit.SECONDS))
                assertNull(LearningPersistence.load(receipt(env.tradeId)))
            } finally { release.countDown(); owner.join(5_000); main.join(5_000) }
            f.awaitReceipt(env)
            assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(env))
            assertEquals(12.5, f.durableBook().trade(env.positionId)!!.realisedPct, 0.0)
        }
    }
}
