package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.sql.DriverManager

class Aate8417CortexHourDeliveryRuntimeTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    @Test fun hourPredictionSurvivesIntermediateReceiptsAndRestartUntilItsOneModelGrade() {
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
            val at = System.currentTimeMillis() - 65 * 60_000L
            val key = "hour-delivery8417|QUALITY"; val id = "$key|$at"
            val receipt = "CORTEX_FORWARD_RECEIPT_8404|$id"
            val a = Cortex7885.Assessment("QUALITY", false, doubleArrayOf(4.0), listOf("TEST8404"), listOf(doubleArrayOf(0.0)), "TEST",
                CortexLedger7885.Fused(4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()), 4.0,
                CortexScoreboard7885.Bucket.STRONG, 0, false, at)
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
                val frozen = requireNotNull(cortex.freezeForwardOutcome8404("hour-delivery8417", "QUALITY", at))
                pending.remove(key) // restart has lost the ordinary pending cache; producer owns the original
                val paired = field(cortex, "pairedPending8401").get(cortex) as MutableMap<String, Any>
                val deferred = field(cortex, "deferredHour8415").get(cortex) as MutableSet<String>
                val book = field(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144
                fun proof(minutes: Int): CortexOutcomeProof8395 {
                    fun mark(time: Long) = com.lifecyclebot.engine.truth.LabelMark8386("hour-delivery8417", "TEST",
                        if (time == at) 1.0 else 1.08, time, time, "TEST", decimals = 6, independentBasis = true)
                    val end = at + minutes * 60_000L
                    return CortexOutcomeProof8395(id, minutes * 60_000L, end, 5.0, 8.0, mark(at), mark(end))
                }
                val flush = cortex.javaClass.getDeclaredMethod("flushShadowOutcomesBlocking8403").apply { isAccessible = true }
                fun deliver(minutes: Int, frozenOriginal: String) {
                    val d = ForwardOutcomeDelivery8404("hour-delivery8417", "QUALITY", at, 5.0, 8.0, null,
                        false, true, proof(minutes), frozenOriginal, revision = minutes != 5)
                    assertFalse(cortex.deliverForwardOutcome8404(d))
                    assertEquals(true, flush.invoke(cortex))
                    assertTrue("receipt exists only after database checkpoint", cortex.deliverForwardOutcome8404(d))
                }
                deliver(5, frozen)
                assertEquals("five-minute result does not train an hour lane", "", book.decision(id)!!.modelContribution8395)
                assertTrue("original prediction must survive its first receipt", paired.containsKey(id))
                for (minutes in listOf(15)) {
                    val next = requireNotNull(cortex.freezeForwardOutcome8404("hour-delivery8417", "QUALITY", at))
                    assertEquals(0, org.json.JSONObject(ForwardCheckpoint8401.unpack(next)).getJSONArray("unpaired8401").length())
                    deliver(minutes, next)
                    assertEquals("intermediate observation is not an hour model grade", "", book.decision(id)!!.modelContribution8395)
                }
                val pairedSnapshot = cortex.checkpointForward8401(setOf(id))
                assertEquals(0, org.json.JSONObject(ForwardCheckpoint8401.unpack(pairedSnapshot)).getJSONArray("unpaired8401").length())
                val saved = requireNotNull(LearningPersistence.load("CORTEX_PENDING_7920"))
                assertTrue("the receipt transaction also owns the deferred prediction", saved.contains("\"paired8417\":true"))
                pending.remove(key); paired.remove(id); deferred.remove(id)
                book.decode(requireNotNull(LearningPersistence.load("CORTEX_GRADES_8144")))
                cortex.javaClass.declaredMethods.single { it.name == "restorePending" && it.parameterCount == 2 }
                    .apply { isAccessible = true }.invoke(cortex, saved, System.currentTimeMillis())
                val restored = requireNotNull(cortex.freezeForwardOutcome8404("hour-delivery8417", "QUALITY", at))
                deliver(30, restored)
                assertTrue("restart must retain the original through another intermediate receipt", paired.containsKey(id))
                deliver(60, requireNotNull(cortex.freezeForwardOutcome8404("hour-delivery8417", "QUALITY", at))) // identical return, different due horizon: still needs its first model grade
                assertEquals(id, book.decision(id)!!.modelContribution8395)
                assertEquals(4.0, book.decision(id)!!.predictedEdgePct, 0.0)
                assertFalse(paired.containsKey(id))
                assertFalse(deferred.contains(id))
            } finally {
                pool.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
                pending.remove(key)
                (field(cortex, "pairedPending8401").get(cortex) as MutableMap<String, Any>).remove(id)
                (field(cortex, "deferredHour8415").get(cortex) as MutableSet<String>).remove(id)
                ready.keys.removeAll { it.startsWith(receipt) }
                flushedAt.set(cortex, savedFlushedAt)
                loaded.set(cortex, savedLoaded); dbField.set(LearningPersistence, savedDb)
            }
        }
    }
}
