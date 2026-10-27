package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.sql.DriverManager

class Aate8404CortexDeliveryRuntimeTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    @Test fun frozenStalePredictionRestoresExactlyAndProducerWaitsForActualDatabaseReceipt() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE kv (tracker TEXT, bucket TEXT, payload TEXT, updated INTEGER, PRIMARY KEY(tracker,bucket))") }
            val db = mock(SQLiteDatabase::class.java)
            doAnswer { a ->
                connection.prepareStatement(a.getArgument<String>(0)).use { statement ->
                    a.getArgument<Array<Any?>>(1).forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.execute()
                }; null
            }.`when`(db).execSQL(anyString(), any())
            doAnswer { a ->
                val rows = connection.prepareStatement(a.getArgument<String>(0)).use { statement ->
                    a.getArgument<Array<String>?>(1)?.forEachIndexed { i, value -> statement.setString(i + 1, value) }
                    statement.executeQuery().use { result -> buildList {
                        while (result.next()) add((1..result.metaData.columnCount).map { result.getObject(it) })
                    } }
                }
                var row = -1
                mock(Cursor::class.java).also { cursor ->
                    `when`(cursor.moveToFirst()).thenAnswer { row = 0; rows.isNotEmpty() }
                    `when`(cursor.moveToNext()).thenAnswer { row++; row < rows.size }
                    `when`(cursor.getString(anyInt())).thenAnswer { rows[row][it.getArgument<Int>(0)]?.toString() }
                    `when`(cursor.getLong(anyInt())).thenAnswer { (rows[row][it.getArgument<Int>(0)] as Number).toLong() }
                }
            }.`when`(db).rawQuery(anyString(), any())
            var committed = false
            doAnswer { connection.autoCommit = false; committed = false; null }.`when`(db).beginTransaction()
            doAnswer { committed = true; null }.`when`(db).setTransactionSuccessful()
            doAnswer { if (committed) connection.commit() else connection.rollback(); connection.autoCommit = true; null }.`when`(db).endTransaction()

            val cortex = Cortex7885
            val loaded = field(cortex, "loaded"); val savedLoaded = loaded.get(cortex)
            val flushedAt = field(cortex, "shadowFlushedAtMs8377"); val savedFlushedAt = flushedAt.get(cortex)
            val pool = field(cortex, "persistPool8094").get(cortex) as java.util.concurrent.ExecutorService
            pool.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val dbField = field(LearningPersistence, "db"); val savedDb = dbField.get(LearningPersistence)
            val pending = field(cortex, "pending").get(cortex) as MutableMap<String, Any>
            val ready = field(cortex, "forwardReady8404").get(cortex) as MutableMap<String, String>
            val at = System.currentTimeMillis() - 300_000
            val key = "stale-delivery8404|QUALITY"; val id = "$key|$at"
            val receipt = "CORTEX_FORWARD_RECEIPT_8404|$id"
            val a = Cortex7885.Assessment("QUALITY", false, doubleArrayOf(4.0), listOf("TEST8404"), listOf(doubleArrayOf(0.0)), "TEST",
                CortexLedger7885.Fused(4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()), 4.0,
                CortexScoreboard7885.Bucket.STRONG, 0, true, at)
            val ctor = Class.forName("${cortex.javaClass.name}\$Pending").declaredConstructors.single { it.parameterCount == 7 }.apply { isAccessible = true }
            try {
                loaded.set(cortex, true); dbField.set(LearningPersistence, db)
                val original = ctor.newInstance(a, false, at, null, "", "", emptyList<String>())
                field(original, "decisionReason8196").set(original, "CORTEX_SHADOW_STRONG")
                val context = Class.forName("${cortex.javaClass.name}\$DecisionCtx8144").declaredConstructors
                    .single { it.parameterCount == 5 }.apply { isAccessible = true }
                    .newInstance("EARLY", "LOW", "SMALL", true, true)
                field(original, "context8372").set(original, context)
                pending[key] = original
                val frozen = requireNotNull(cortex.freezeForwardOutcome8404("stale-delivery8404", "QUALITY", at))
                pending.remove(key) // restart has lost the ordinary pending cache; producer owns the original
                val d = ForwardOutcomeDelivery8404("stale-delivery8404", "QUALITY", at, 5.0, 8.0, null, false, false, null, frozen)
                assertFalse(cortex.deliverForwardOutcome8404(d))
                assertTrue(ready.containsKey(receipt))
                assertNull(LearningPersistence.load(receipt))
                val record = (field(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144).decision(id)!!
                assertEquals("stale original cannot earn training after restart", "", record.modelContribution8395)
                assertTrue(record.executablePct.isNaN())
                assertFalse(cortex.deliverForwardOutcome8404(d))
                val flush = cortex.javaClass.getDeclaredMethod("flushShadowOutcomesBlocking8403").apply { isAccessible = true }
                `when`(db.inTransaction()).thenReturn(true)
                assertEquals(false, flush.invoke(cortex))
                assertTrue(ready.containsKey(receipt))
                assertNull(LearningPersistence.load(receipt))
                `when`(db.inTransaction()).thenReturn(false)
                assertEquals(true, flush.invoke(cortex))
                assertNotNull(LearningPersistence.load(receipt))
                assertTrue(cortex.deliverForwardOutcome8404(d))
                assertFalse(ready.containsKey(receipt))
                assertEquals("", record.modelContribution8395)
            } finally {
                pool.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
                pending.remove(key); ready.remove(receipt)
                flushedAt.set(cortex, savedFlushedAt)
                loaded.set(cortex, savedLoaded); dbField.set(LearningPersistence, savedDb)
            }
        }
    }
}
