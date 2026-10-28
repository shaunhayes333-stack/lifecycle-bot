package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.truth.ShadowColdStore8397
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** V5.0.8409 — the labeler's outcome / terminal rows are SQLite rows, not entries in the checkpoint preferences file. */
class Aate8409LabelerRowStoreTest {
    private lateinit var directory: File
    private lateinit var connection: Connection
    private lateinit var db: SQLiteDatabase

    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        directory = Files.createTempDirectory("rows8409").toFile()
        connect()
        ShadowColdStore8397.createSchema(db)
    }
    private fun connect() {
        connection = DriverManager.getConnection("jdbc:sqlite:${File(directory, "rows.db")}")
        db = mock(SQLiteDatabase::class.java)
        doAnswer { a -> connection.createStatement().use { it.execute(a.getArgument<String>(0)) }; null }.`when`(db).execSQL(anyString())
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
        doAnswer { if (successful) connection.commit() else connection.rollback(); connection.autoCommit = true; null }.`when`(db).endTransaction()
    }
    @After fun cleanup() { connection.close(); directory.deleteRecursively() }

    @Test fun rows_write_restore_acknowledge_and_survive_a_reconnect_without_touching_other_namespaces() {
        val rows = ShadowColdStore8397(db, "labeler_rows_8409")
        val other = ShadowColdStore8397(db, "explorer")
        other.write(mapOf("k" to "v"))
        // 300 forward rows and 300 terminal rows, each its own row transaction
        for (i in 0 until 300) {
            assertTrue(rows.write(mapOf("forward8404|m$i|LANE|$i" to "payload$i")))
            assertTrue(rows.write(mapOf("terminal8395|m$i|LANE|$i" to "{\"why\":\"x$i\"}")))
        }
        assertEquals(600, rows.keys().size)
        // process death: reconnect, restore from rows
        connection.close(); connect()
        val restored = ShadowColdStore8397(db, "labeler_rows_8409").entries().toMap()
        assertEquals(600, restored.size); assertEquals("payload7", restored["forward8404|m7|LANE|7"])
        // a batch acknowledgement is one transaction; the other namespace is untouched
        val ack = (0 until 64).map { "terminal8395|m$it|LANE|$it" }
        assertTrue(ShadowColdStore8397(db, "labeler_rows_8409").write(ack.associateWith { null }))
        assertEquals(536, ShadowColdStore8397(db, "labeler_rows_8409").keys().size)
        assertEquals("v", ShadowColdStore8397(db, "explorer").read("k"))
    }

    @Test fun a_repeated_migration_batch_neither_loses_nor_duplicates() {
        val rows = ShadowColdStore8397(db, "labeler_rows_8409")
        val legacy = (0 until 50).associate { "forward8404|m$it|L|$it" to "p$it" }
        assertTrue(rows.write(legacy)); assertTrue(rows.write(legacy))   // interrupted before the preferences removal, then repeated
        assertEquals(50, rows.keys().size)
        assertEquals("p3", rows.read("forward8404|m3|L|3"))
    }

    @Test fun the_labeler_writes_rows_through_the_store_and_no_longer_commits_rows_to_the_preferences_file() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(src.contains("putRow8409(FORWARD_PREFIX_8404 + id, raw)"))
        assertTrue(src.contains("removeRows8409(listOf(FORWARD_PREFIX_8404 + id))"))
        assertTrue(src.contains("val committed = putRow8409(id, value)"))
        assertTrue(src.contains("if (p.edit().putString(PENDING_KEY_7946, retained).commit() && removeRows8409(acknowledged))"))
        assertTrue(src.contains("openRowStore8409(context, p)"))
        assertTrue(src.indexOf("openRowStore8409(context, p)") < src.indexOf("val rows8409 = loadRows8409(p)"))
        // the only remaining row-level preferences commits are the fallback inside the 8409 helpers
        assertFalse(src.contains("prefs?.edit()?.putString(FORWARD_PREFIX_8404"))
        assertFalse(src.contains("prefs?.edit()?.remove(FORWARD_PREFIX_8404"))
        assertFalse(src.contains("prefs?.edit()?.putString(id,value)"))
        assertTrue(src.contains("ROWS_NAMESPACE_8409 = \"labeler_rows_8409\""))
    }
}
