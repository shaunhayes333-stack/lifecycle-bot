package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.truth.HiveEdge8000
import com.lifecyclebot.engine.truth.HiveMemoryRows8681
import com.lifecyclebot.engine.truth.HiveSqlRows8681
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * V5.0.8681 — the hive network book on disk (5.0.8680: hiveBook +35.6 MB resident over 97 minutes). Real SQLite through
 * sqlite-jdbc behind the Android SQLiteDatabase calls the store makes: rows persist across a reopen, the "network less
 * this install" prefix scan matches HiveEdge8000.net8000, and the heap holds no book rows.
 */
class Aate8681HiveBookTest {
    private lateinit var directory: File
    private lateinit var connection: Connection
    private lateinit var db: SQLiteDatabase

    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        directory = Files.createTempDirectory("hive8681").toFile()
        connect()
        HiveSqlRows8681.createSchema(db)
    }

    private fun connect() {
        connection = DriverManager.getConnection("jdbc:sqlite:${File(directory, HiveSqlRows8681.DB_NAME)}")
        db = mock(SQLiteDatabase::class.java)
        doAnswer { a -> connection.createStatement().use { it.execute(a.getArgument<String>(0)) }; null }
            .`when`(db).execSQL(anyString())
        doAnswer { a ->
            connection.prepareStatement(a.getArgument<String>(0)).use { p ->
                a.getArgument<Array<Any?>>(1).forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.execute()
            }; null
        }.`when`(db).execSQL(anyString(), any())
        doAnswer { a ->
            val rows = connection.prepareStatement(a.getArgument<String>(0)).use { p ->
                a.getArgument<Array<String>?>(1)?.forEachIndexed { i, v -> p.setString(i + 1, v) }
                p.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getObject(it) }) } }
            }
            var index = -1
            mock(Cursor::class.java).also { c ->
                `when`(c.moveToFirst()).thenAnswer { index = 0; rows.isNotEmpty() }
                `when`(c.moveToNext()).thenAnswer { index++; index < rows.size }
                `when`(c.getString(anyInt())).thenAnswer { rows[index][it.getArgument<Int>(0)]?.toString() }
                `when`(c.getDouble(anyInt())).thenAnswer { (rows[index][it.getArgument<Int>(0)] as Number).toDouble() }
                `when`(c.getInt(anyInt())).thenAnswer { (rows[index][it.getArgument<Int>(0)] as Number).toInt() }
            }
        }.`when`(db).rawQuery(anyString(), any())
        var successful = false
        doAnswer { connection.autoCommit = false; successful = false; null }.`when`(db).beginTransaction()
        doAnswer { successful = true; null }.`when`(db).setTransactionSuccessful()
        doAnswer { if (successful) connection.commit() else connection.rollback(); connection.autoCommit = true; null }
            .`when`(db).endTransaction()
    }

    @After fun cleanup() { connection.close(); directory.deleteRecursively() }

    @Test fun networkAndUploadLedgerPersistAcrossAReopen() {
        val book = HiveSqlRows8681(db)
        assertTrue(book.putAggregate(listOf("FRL8527|a" to doubleArrayOf(12.0, 60.0, 300.0, 6.0, 1.0, 40.0),
            "PB|x" to doubleArrayOf(3.0, 3.0, 3.0, 1.0, 0.0, 2.0))))
        assertTrue(book.putUploaded(listOf("FRL8527|a" to doubleArrayOf(2.0, 10.0, 50.0, 1.0, 0.0, 40.0))))
        assertTrue(book.putMeta("last_pull_ms", "1794411628402"))
        connection.close(); connect()
        val again = HiveSqlRows8681(db)
        assertArrayEquals(doubleArrayOf(12.0, 60.0, 300.0, 6.0, 1.0, 40.0), again.aggregate("FRL8527|a"), 0.0)
        assertArrayEquals(doubleArrayOf(2.0, 10.0, 50.0, 1.0, 0.0, 40.0), again.uploaded("FRL8527|a"), 0.0)
        assertNull(again.aggregate("FRL8527|missing"))
        assertEquals(2, again.aggregateCount()); assertEquals(1, again.uploadedCount())
        assertEquals("1794411628402", again.meta("last_pull_ms"))
        assertEquals("the disk book holds nothing on the heap", 0, again.residentRows())
    }

    @Test fun replacingARowKeepsOneRowPerKey() {
        val book = HiveSqlRows8681(db)
        book.putAggregate(listOf("TAIL|k" to doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.0, 1.0)))
        book.putAggregate(listOf("TAIL|k" to doubleArrayOf(9.0, 9.0, 9.0, 9.0, 0.0, 9.0)))
        assertEquals(1, book.aggregateCount())
        assertEquals(9.0, book.aggregate("TAIL|k")!![0], 0.0)
    }

    @Test fun prefixScanCountsOnlyTheRestOfTheNetwork() {
        val book = HiveSqlRows8681(db)
        book.putAggregate(listOf(
            "SPEC8663|both" to doubleArrayOf(5.0, 5.0, 5.0, 5.0, 0.0, 1.0),
            "SPEC8663|mineOnly" to doubleArrayOf(3.0, 3.0, 3.0, 3.0, 0.0, 1.0),
            "SPEC8663|others" to doubleArrayOf(2.0, 2.0, 2.0, 2.0, 0.0, 1.0),
            "SPEC8664|other-book" to doubleArrayOf(9.0, 9.0, 9.0, 9.0, 0.0, 1.0),
            "TAIL|P|x" to doubleArrayOf(4.0, 4.0, 4.0, 4.0, 0.0, 1.0)))
        book.putUploaded(listOf("SPEC8663|both" to doubleArrayOf(2.0, 2.0, 2.0, 2.0, 0.0, 1.0),
            "SPEC8663|mineOnly" to doubleArrayOf(3.0, 3.0, 3.0, 3.0, 0.0, 1.0)))
        assertEquals(listOf("SPEC8663|both", "SPEC8663|others"), book.netKeys("SPEC8663|").sorted())
        assertEquals(listOf("TAIL|P|x"), book.netKeys("TAIL|P|"))
        assertTrue(book.netKeys("NOPE|").isEmpty())
        // the heap stand-in answers the same question the same way
        val mem = HiveMemoryRows8681()
        mem.putAggregate(listOf("SPEC8663|both" to doubleArrayOf(5.0, 5.0, 5.0, 5.0, 0.0, 1.0),
            "SPEC8663|mineOnly" to doubleArrayOf(3.0, 3.0, 3.0, 3.0, 0.0, 1.0)))
        mem.putUploaded(listOf("SPEC8663|mineOnly" to doubleArrayOf(3.0, 3.0, 3.0, 3.0, 0.0, 1.0)))
        assertEquals(listOf("SPEC8663|both"), mem.netKeys("SPEC8663|"))
    }

    @Test fun theHeapStandInIsBounded() {
        val mem = HiveMemoryRows8681(maxKeys = 100)
        mem.putAggregate((1..250).map { "FRL8527|$it" to doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.0, 1.0) })
        assertEquals(100, mem.aggregateCount())
        assertEquals(100, mem.residentRows())
    }

    @Test fun hiveReadsComeThroughTheBoundedCacheAndRaceSafeInvalidation() {
        val key = "CX|cache8681-" + System.nanoTime()
        try {
            assertNull(HiveEdge8000.net8000(key))                       // a miss is cached as a miss ...
            HiveEdge8000.putAggregateForTest8681(key, doubleArrayOf(4.0, 8.0, 20.0, 2.0, 0.0, 3.0))
            assertEquals(8.0, HiveEdge8000.net8000(key)!![1], 0.0)      // ... and a write clears it
            val read = HiveEdge8000.net8000(key)!!
            read[1] = -999.0
            assertEquals("callers get a copy", 8.0, HiveEdge8000.net8000(key)!![1], 0.0)
            assertTrue(HiveEdge8000.residentCount8681() >= 1)
            HiveEdge8000.capAggregate8144(0)
            assertEquals(8.0, HiveEdge8000.net8000(key)!![1], 0.0)      // evicted from the cache, still in the book
        } finally { HiveEdge8000.removeAggregateForTest8681(listOf(key)) }
        assertNull(HiveEdge8000.net8000(key))
    }

    @Test fun theHiveNoLongerHoldsOrRewritesTheWholeBook() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/truth/HiveEdge8000.kt").readText()
        assertFalse(h.contains("private val aggregate = ConcurrentHashMap"))
        assertFalse(h.contains("private val uploaded = ConcurrentHashMap"))
        assertFalse("the upload ledger is not rewritten whole each sync", h.contains("writeTextAtomic8086"))
        assertTrue(h.contains("val from8681 = resumeFrom8681()"))
        assertTrue(src("engine/MemoryGuard7977.kt").contains("HiveEdge8000.residentCount8681()"))
        assertFalse(HiveEdge8000.javaClass.declaredFields.any { it.name == "aggregate" || it.name == "uploaded" })
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
