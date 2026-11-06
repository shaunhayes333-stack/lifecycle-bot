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

    @Test fun hourPredictionSurvivesIntermediateReceiptsAndRestartUntilItsOneModelGrade() {
        runHourCase8521(missingFiveMinute = false)
    }

    @Test fun firstHourResultCanGradeItsOriginalPredictionWhenTheFiveMinuteSourceWasMissing() {
        runHourCase8521(missingFiveMinute = true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runHourCase8521(missingFiveMinute: Boolean) {
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
            val mint = if (missingFiveMinute) "hour-first8521" else "hour-delivery8417"
            val key = "$mint|QUALITY"; val id = "$key|$at"
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
                    .single { it.parameterCount == 6 }.apply { isAccessible = true }
                    .newInstance("EARLY", "LOW", "SMALL", true, true, null)
                field(original, "context8372").set(original, context)
                pending[key] = original
                val frozen = requireNotNull(cortex.freezeForwardOutcome8404(mint, "QUALITY", at))
                pending.remove(key) // restart has lost the ordinary pending cache; producer owns the original
                val paired = field(cortex, "pairedPending8401").get(cortex) as MutableMap<String, Any>
                val deferred = field(cortex, "deferredHour8415").get(cortex) as MutableSet<String>
                val book = field(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144
                fun proof(minutes: Int): CortexOutcomeProof8395 {
                    val entryAt = at + 5_000L
                    fun mark(time: Long) = com.lifecyclebot.engine.truth.LabelMark8386(mint, "TEST",
                        if (time == entryAt) 1.0 else 1.08, time, time, "TEST", decimals = 6, independentBasis = true)
                    val end = at + minutes * 60_000L
                    return CortexOutcomeProof8395(id, minutes * 60_000L, end, 5.0, 8.0, mark(entryAt), mark(end))
                }
                val flush = cortex.javaClass.getDeclaredMethod("flushShadowOutcomesBlocking8403").apply { isAccessible = true }
                fun deliver(minutes: Int, frozenOriginal: String) {
                    val d = ForwardOutcomeDelivery8404(mint, "QUALITY", at, 5.0, 8.0, null,
                        false, true, proof(minutes), frozenOriginal, revision = minutes != 5)
                    assertFalse(cortex.deliverForwardOutcome8404(d))
                    assertEquals(true, flush.invoke(cortex))
                    assertTrue("receipt exists only after database checkpoint", cortex.deliverForwardOutcome8404(d))
                }
                var checkpoint = frozen
                if (!missingFiveMinute) {
                    deliver(5, frozen)
                    assertEquals("the five-minute result is provisional for this hour mandate", "", book.decision(id)!!.modelContribution8395)
                    assertTrue(book.decision(id)!!.executablePct.isNaN())
                    assertEquals(4.0, book.decision(id)!!.predictedEdgePct, 0.0)
                    assertTrue(deferred.contains(id))
                    assertTrue("the original voter snapshot remains paired", paired.containsKey(id))
                    checkpoint = requireNotNull(cortex.freezeForwardOutcome8404(mint, "QUALITY", at))
                    // Recreate the grade book and discard session-only deferral state as a restart would.
                    book.decode(book.encode()); paired.remove(id); deferred.remove(id)
                } else {
                    assertNull("no synthetic five-minute row was needed", book.decision(id))
                }
                // V5.0.8592 — the hour target IS the 15-minute checkpoint now (was 60m, which memecoins never reached: the
                // grader starved). The exact original prediction learns once here; the 5-minute read above stayed provisional.
                deliver(15, checkpoint)
                assertEquals("the exact original prediction learns once at its frozen 15-minute horizon", id, book.decision(id)!!.modelContribution8395)
                assertEquals(5.0, book.decision(id)!!.executablePct, 0.0)
                assertEquals(at + CortexForwardEpoch8521.HOUR_MS, book.decision(id)!!.outcomeDueAtMs8394)
                assertFalse(deferred.contains(id))
                assertFalse(paired.containsKey(id))
                // A later 60-minute path may be displayed, but cannot retrain the frozen 15-minute target.
                deliver(60, checkpoint)
                assertEquals("a later checkpoint cannot retrain the frozen target", id, book.decision(id)!!.modelContribution8395)
                assertEquals(5.0, book.decision(id)!!.executablePct, 0.0)
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
