package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.KeylessMarks8359
import com.lifecyclebot.engine.truth.LearningEpoch8164
import com.lifecyclebot.engine.truth.ShadowColdStore8397
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.After
import org.junit.Assert.assertEquals
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
 * V5.0.8418 — runtime 8416 restored8355=45 lostOnRestore8355=27: a restored evidence-shadow position keeps its entry
 * price, clock and checkpoints, asks the keyless feed for a mark, and is not closed LOST_MARK by the restart gap itself.
 */
class Aate8418ShadowRestoreTest {
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

    @Test fun restoredEvidencePositionSurvivesTheRestartGapAndKeepsGrading() {
        val prefsField = EvidenceShadow8344::class.java.getDeclaredField("openPrefs8355").apply { isAccessible = true }
        val previous = prefsField.get(EvidenceShadow8344)
        EvidenceShadow8344.resetForTests8345()
        val at = maxOf(System.currentTimeMillis(), LearningEpoch8164.epochMs8164(), EvidenceHold8336.startMs8336) + 1_000
        val mint = "Restore8418Mint11111111111111111111111111111"
        val rec = ShadowPersist8355.PosRec8355(mint, "MOONSHOT", 1.0, at, 3.0, 15.0, 20.0,
            3_600_000, 0.75, 4.0, 1, 18.0, 1.1, at,
            contractVersion8394 = ShadowPersist8355.TRADE_CONTRACT_8394)
        try {
            assertTrue(cold("evidence-8418").write(mapOf("MOONSHOT|$mint" to ShadowPersist8355.encodePos8355(rec))))
            connection.close(); connect()
            val restoredAt = at + 15 * 60_000L   // the process was down for 15 minutes
            assertTrue(EvidenceShadow8344.restoreOpen8417(cold("evidence-8418"), restoredAt))
            assertEquals(1L, EvidenceShadow8344.restoreCounts8355().first)
            assertTrue("the restored mint is registered for keyless marks", KeylessMarks8359.isWanted8359(mint))
            assertTrue(EvidenceShadow8344.wantCalls8418() >= 1L)
            // one minute after the restore, still no mark: pre-8418 this closed LOST_MARK (last mark 16 minutes old)
            EvidenceShadow8344.tick8344(emptyMap(), restoredAt + 60_000L)
            assertTrue(EvidenceShadow8344.statusLine8344(at, restoredAt + 60_000L).contains("open=1/"))
            assertEquals(0L, EvidenceShadow8344.restoreCounts8355().second)
            // a mark arrives: grading continues from the persisted checkpoints (held 0.75, one tier, peak 18 %)
            val ts = com.lifecyclebot.data.TokenState(mint = mint, symbol = "R8418").also {
                it.lastPrice = 1.05; it.lastPriceUpdate = restoredAt + 120_000L
            }
            EvidenceShadow8344.tick8344(mapOf(mint to ts), restoredAt + 120_000L)
            assertTrue(EvidenceShadow8344.statusLine8344(at, restoredAt + 120_000L).contains("open=1/"))
            assertTrue("the persisted checkpoint is what is stored again, not a re-priced entry",
                cold("evidence-8418").entries().any { ShadowPersist8355.decodePos8355(it.second)?.entryPx == 1.0 })
        } finally {
            EvidenceShadow8344.resetForTests8345()
            prefsField.set(EvidenceShadow8344, previous)
        }
    }

    @Test fun restoredPositionWithNoMarkForAWholeWindowStillClosesLostMark() {
        val prefsField = EvidenceShadow8344::class.java.getDeclaredField("openPrefs8355").apply { isAccessible = true }
        val previous = prefsField.get(EvidenceShadow8344)
        EvidenceShadow8344.resetForTests8345()
        val at = maxOf(System.currentTimeMillis(), LearningEpoch8164.epochMs8164(), EvidenceHold8336.startMs8336) + 1_000
        val mint = "Restore8418Lost1111111111111111111111111111"
        val rec = ShadowPersist8355.PosRec8355(mint, "QUALITY", 1.0, at, 3.0, 15.0, 20.0,
            3_600_000, 1.0, 0.0, 0, 0.0, 1.0, at, contractVersion8394 = ShadowPersist8355.TRADE_CONTRACT_8394)
        try {
            assertTrue(cold("evidence-8418b").write(mapOf("QUALITY|$mint" to ShadowPersist8355.encodePos8355(rec))))
            connection.close(); connect()
            val restoredAt = at + 5 * 60_000L
            assertTrue(EvidenceShadow8344.restoreOpen8417(cold("evidence-8418b"), restoredAt))
            EvidenceShadow8344.tick8344(emptyMap(), restoredAt + 10 * 60_000L)
            assertEquals("unknown stays unknown: no mark for a full window after the restore is a lost mark", 1L,
                EvidenceShadow8344.restoreCounts8355().second)
            assertTrue(EvidenceShadow8344.statusLine8344(at, restoredAt + 10 * 60_000L).contains("open=0/"))
        } finally {
            EvidenceShadow8344.resetForTests8345()
            prefsField.set(EvidenceShadow8344, previous)
        }
    }
}
