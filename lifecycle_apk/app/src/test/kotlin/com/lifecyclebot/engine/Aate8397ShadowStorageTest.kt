package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.truth.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** Production migration, SQL transactions, cohort cache and writer queue against a real on-disk SQLite database. */
class Aate8397ShadowStorageTest {
    private lateinit var directory: File
    private lateinit var connection: Connection
    private lateinit var db: SQLiteDatabase

    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        directory = Files.createTempDirectory("shadow8397").toFile()
        connect()
        ShadowColdStore8397.createSchema(db)
    }
    private fun connect() {
        connection = DriverManager.getConnection("jdbc:sqlite:${File(directory, "shadow.db")}")
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
            mock(Cursor::class.java).also { cursor ->
                `when`(cursor.moveToFirst()).thenAnswer { index = 0; rows.isNotEmpty() }
                `when`(cursor.moveToNext()).thenAnswer { index++; index < rows.size }
                `when`(cursor.getString(anyInt())).thenAnswer { rows[index][it.getArgument<Int>(0)]?.toString() }
            }
        }.`when`(db).rawQuery(anyString(), any())
        var successful = false
        doAnswer { connection.autoCommit = false; successful = false; null }.`when`(db).beginTransaction()
        doAnswer { successful = true; null }.`when`(db).setTransactionSuccessful()
        doAnswer { if (successful) connection.commit() else connection.rollback(); connection.autoCommit = true; null }
            .`when`(db).endTransaction()
    }
    @After fun cleanup() { connection.close(); directory.deleteRecursively() }
    private fun cold(name: String = "explorer") = ShadowColdStore8397(db, name)

    @Test fun upgradeRestoresBothLanesAndRetiresLegacyKeysAfterObservedExit8417() {
        val prefsField = EvidenceShadow8344::class.java.getDeclaredField("openPrefs8355").apply { isAccessible = true }
        val previous = prefsField.get(EvidenceShadow8344)
        val epoch = LearningEpoch8164.epochMs8164()
        EvidenceShadow8344.resetForTests8345()
        val at = maxOf(System.currentTimeMillis(), epoch, EvidenceHold8336.startMs8336) + 1_000
        val mint = "upgrade-8417"
        val moon = ShadowPersist8355.PosRec8355(mint, "MOONSHOT", 1.0, at, 3.0, 15.0, 20.0,
            3_600_000, 1.0, 0.0, 0, 0.0, 1.0, at,
            contractVersion8394 = ShadowPersist8355.TRADE_CONTRACT_8394,
            exitAt8394 = at + 1_000, exitWhy8394 = "STOP")
        try {
            val store = cold("evidence-upgrade")
            assertTrue(store.write(mapOf(mint to ShadowPersist8355.encodePos8355(moon),
                "QUALITY|$mint" to ShadowPersist8355.encodePos8355(moon.copy(lane = "QUALITY")))))
            connection.close(); connect()
            assertTrue(EvidenceShadow8344.restoreOpen8417(cold("evidence-upgrade"), at + 2_000))
            assertTrue(EvidenceShadow8344.statusLine8344(at).contains("open=2/120"))
            assertEquals(2L, EvidenceShadow8344.restoreCounts8355().first)
            assertTrue(EvidenceShadow8344.restoreOpen8417(cold("evidence-upgrade"), at + 3_000))
            assertEquals("a retry cannot duplicate restored episodes", 2L, EvidenceShadow8344.restoreCounts8355().first)
            val fillAt = at + 1_001 + ShadowAlign8366.sellLatencyMs8366(false)
            val ts = com.lifecyclebot.data.TokenState(mint = mint, symbol = "TEST").also {
                it.lastPrice = 0.8; it.lastPriceUpdate = fillAt
            }
            EvidenceShadow8344.tick8344(mapOf(mint to ts), fillAt)
            assertEquals(1, EvidenceShadow8344.proof8344("MOONSHOT", at).n)
            assertEquals(1, EvidenceShadow8344.proof8344("QUALITY", at).n)
            assertTrue("closing removes the exact old and new storage identities", cold("evidence-upgrade").keys().isEmpty())
        } finally {
            EvidenceShadow8344.resetForTests8345()
            prefsField.set(EvidenceShadow8344, previous)
        }
    }

    @Test fun streamsEveryLegacyCohortThenReadsColdKeysWithTheSameProof() {
        val legacy = File(directory, "explorer.xml")
        val payload = (1..60).joinToString(";") { "$it,${if (it % 10 == 0) -4 else 18},8394,$it" }
        legacy.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><map>")
            repeat(256) { w.write("<string name=\"C|MOONSHOT|NOW|RUNNER|S:setup$it\">$payload</string>") }
            w.write("<string name=\"escaped&amp;key\">${payload.replace(",", "&#44;")}</string></map>")
        }
        val store = cold(); store.migrate(legacy)
        assertFalse(legacy.exists()); assertTrue(File(legacy.path + ".migrated8397").exists())
        assertEquals(257, store.keys().size)
        val book = ShadowCohortBook8397(4, hotCapacity = 8); book.attach(store)
        assertEquals(0, book.hotCount())
        val expected = payload.split(';').map { it.split(',')[1].toDouble() }
        for (key in book.keys) {
            val actual = book[key]!!.map { it[1] }
            assertEquals(expected, actual)
            assertEquals(ExploreShadow8347.cohortProof8351(expected), ExploreShadow8347.cohortProof8351(actual))
            assertTrue(book.hotCount() <= 8)
        }
        book.trim(); assertEquals(0, book.hotCount())
        assertEquals(expected, book["C|MOONSHOT|NOW|RUNNER|S:setup0"]!!.map { it[1] })
        assertEquals(payload, store.read("escaped&key"))
        assertEquals(257, book.keys.size) // evicting parsed rows never changes the durable universe
    }

    @Test fun malformedMigrationRollsBackAndPreservesOriginalUntilSuccessfulRetry() {
        val legacy = File(directory, "broken.xml")
        legacy.writeText("<map><string name=\"first\">one</string><string name=\"broken\">unfinished")
        val store = cold()
        assertThrows(Exception::class.java) { store.migrate(legacy) }
        assertTrue(legacy.exists()); assertNull(store.read("first"))
        legacy.writeText("<map><string name=\"first\">one</string><string name=\"second\">two</string></map>")
        store.migrate(legacy)
        assertEquals(setOf("first", "second"), store.keys())
        store.write(mapOf("first" to "newer receipt"))
        // Retrying init after archive preserves new data; it cannot import the old value over it.
        store.migrate(legacy)
        assertEquals("newer receipt", store.read("first"))
    }

    @Test fun cohortReceiptIsAtomicAndRestartCannotDoubleContribute() {
        var book = ShadowCohortBook8397(4, maxRows = 3, hotCapacity = 1)
        book.attach(cold())
        book.append(listOf("variant", "cohort"), doubleArrayOf(100.0, 18.0, 8394.0, 1.0), "episode-one")
        connection.close(); connect()
        book = ShadowCohortBook8397(4, maxRows = 3, hotCapacity = 1); book.attach(cold())
        book.append(listOf("variant", "cohort"), doubleArrayOf(200.0, -80.0, 8394.0, 1.0), "episode-one")
        assertEquals(listOf(18.0), book["variant"]!!.map { it[1] })
        assertEquals(listOf(18.0), book["cohort"]!!.map { it[1] })
        db.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON shadow_receipts WHEN NEW.event='fail' BEGIN SELECT RAISE(ABORT,'disk failure'); END")
        assertThrows(ShadowStorageUnavailable8397::class.java) {
            book.append(listOf("variant", "cohort"), doubleArrayOf(300.0, 5.0, 8394.0, 2.0), "fail")
        }
        book.trim()
        assertEquals(listOf(18.0), book["cohort"]!!.map { it[1] }) // first key's SQL update also rolled back
        db.execSQL("DROP TRIGGER fail_receipt")
        book.append(listOf("variant", "cohort"), doubleArrayOf(300.0, 5.0, 8394.0, 2.0), "fail")
        assertEquals(listOf(18.0, 5.0), book["variant"]!!.map { it[1] })
        repeat(4) { book.append(listOf("variant"), doubleArrayOf(400.0 + it, it.toDouble(), 8394.0, 3.0), "later-$it") }
        assertEquals(listOf(1.0, 2.0, 3.0), book["variant"]!!.map { it[1] })
    }

    @Test fun writerRetainsFailedReceiptsAndReleasesSerializedValuesAfterFlush() {
        val writes = ShadowWrites8386(8, byteCapacity = 50_000, batchCapacity = 2)
        assertTrue(writes.stage("ticket", 1, "x".repeat(20_000)))
        assertFalse(writes.stage("another", 1, "y".repeat(20_000)))
        assertFalse(writes.flush { false }); assertEquals(1, writes.size())
        val durable = HashMap<String, String?>()
        assertTrue(writes.flush { rows -> assertTrue(rows.size <= 2); rows.forEach { durable[it.key] = it.value.value }; true })
        assertEquals(20_000, durable["ticket"]!!.length)
        assertEquals(0, writes.size()); assertTrue(writes.bytes() < 1_000) // metadata cannot keep the flushed 40KB string alive
        assertTrue(writes.stage("ticket", 1, null)); assertTrue(writes.flush { true })
        assertFalse(writes.stage("ticket", 1, "stale receipt"))
        assertTrue(writes.stage("ticket", 2, "new episode"))
    }

    @Test fun earlyClosesAttachWithTheirReceiptAndRemainIdempotentAfterRestart() {
        val early = ShadowCohortBook8397(4)
        early.append(listOf("cohort"), doubleArrayOf(10.0, 18.0, 8394.0, 1.0), "before-init")
        early.attach(cold())
        connection.close(); connect()
        val restored = ShadowCohortBook8397(4); restored.attach(cold())
        restored.append(listOf("cohort"), doubleArrayOf(20.0, 18.0, 8394.0, 1.0), "before-init")
        assertEquals(1, restored["cohort"]!!.size)
        assertEquals(10.0, restored["cohort"]!!.single()[0], 0.0)
    }

    /** V5.0.8398 — `keys` / `containsKey` answer from the resident key index; the key query runs once, at attach. */
    @Test fun keysAreIndexedAtAttachAndMaintainedByAppendWithoutRequeryingTheStore() {
        val book = ShadowCohortBook8397(4); book.attach(cold())
        book.append(listOf("C|CORE|NOW|SCALP|S:dip", "CORE|NOW|SCALP"), doubleArrayOf(10.0, 2.0, 8394.0, 1.0), "e1")
        assertEquals(setOf("C|CORE|NOW|SCALP|S:dip", "CORE|NOW|SCALP"), book.keys)
        assertTrue(book.containsKey("CORE|NOW|SCALP")); assertFalse(book.containsKey("MOONSHOT|NOW|SCALP"))
        connection.close(); connect()
        val restored = ShadowCohortBook8397(4); restored.attach(cold())
        assertEquals(setOf("C|CORE|NOW|SCALP|S:dip", "CORE|NOW|SCALP"), restored.keys)
        assertTrue(restored.containsKey("C|CORE|NOW|SCALP|S:dip"))
        // keys stay answerable after the hot set is trimmed: the index, not the 64-entry hot cache, is the authority
        restored.trim()
        assertEquals(2, restored.keys.size)
        assertTrue(restored.containsKey("CORE|NOW|SCALP"))
        // a key the index does not hold never reaches the disk; a held one is read cold once, then served hot
        assertNull(restored["MOONSHOT|NOW|SCALP"]); assertEquals(1, restored["CORE|NOW|SCALP"]!!.size); restored["CORE|NOW|SCALP"]
        assertTrue(restored.stats8398(), restored.stats8398().contains("coldReads=1"))
        assertTrue(restored.stats8398().startsWith("keys=2 hot=1"))
        // report builder contract: the watchdog can ship the partial full report with the stalled section named
        val ui = java.io.File("src/main/kotlin/com/lifecyclebot/ui/PipelineHealthActivity.kt").readText()
        assertTrue(ui.contains("PipelineHealthCollector.inFlightDumpPartial8398()"))
        assertTrue(ui.contains("UNIFIED_REPORT_PARTIAL_SHIPPED_8398"))
        val coll = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(coll.indexOf("inFlightDump8398 = sb") < coll.indexOf("===== AATE Pipeline Health Snapshot ====="))
        // Reports read a completed snapshot and return immediately while a background refresh is busy.
        // The old per-report memo retained every cold cohort and is deliberately no longer populated.
        val explore = com.lifecyclebot.engine.truth.ExploreShadow8347
        val busyField = explore.javaClass.getDeclaredField("reportBusy8401").apply { isAccessible = true }
        val busy = busyField.get(explore) as java.util.concurrent.atomic.AtomicBoolean
        val oldBusy = busy.getAndSet(true)
        try {
            assertTrue(explore.statusLine8347(Long.MAX_VALUE).contains("summary pending"))
        } finally { busy.set(oldBusy) }
    }
}
