package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexOutcomeDisposition8395
import com.lifecyclebot.engine.cortex.CortexOutcomeProof8395
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.DriverManager

/**
 * V5.0.8418 item 18 — Cortex restart and long horizon.
 *
 * (1) Books decisions in a runner lane (6 h executable hold) and a 5-minute lane (1 h hold), restarts the grade book,
 *     voter-seat ledger and calibration through their persisted encodings after EVERY checkpoint, lets the 1 h / 4 h / 24 h
 *     checkpoints land, and proves: frozen predictions/bucket survive, each checkpoint revises at most once (a redelivery
 *     after restart is DUPLICATE / STALE and never re-enters the models), a revision past the lane's hold is observed only,
 *     and the ledger/calibration revision replaces the booked value (n unchanged) exactly once.
 * (2) The real Cortex7885 snapshot path: persistBlocking8094 -> LearningPersistence (SQLite) -> fresh in-memory state ->
 *     ensureLoaded, asserting the seats, calibrated read and grade records come back from disk.
 */
class Aate8418CortexRestartHorizonTest {

    private val at = 1_000_000L
    private val runnerLanes = setOf("MOONSHOT")
    private fun newBook8418(): CortexGradeBook8144 = CortexGradeBook8144().also { b -> b.runnerLane8322 = { l -> l in runnerLanes } }
    private fun restartBook8418(b: CortexGradeBook8144): CortexGradeBook8144 = newBook8418().also { it.decode(b.encode()) }
    private fun restartLedger8418(l: CortexLedger7885): CortexLedger7885 =
        CortexLedger7885().also { it.decode(org.json.JSONObject(l.encode().toString())) }
    private fun restartCalibration8418(c: CortexCalibration7901): CortexCalibration7901 =
        CortexCalibration7901().also { it.decode(org.json.JSONObject(c.encode().toString())) }

    private fun id8418(lane: String) = "proof8418|$lane|$at"

    private fun deliver8418(b: CortexGradeBook8144, lane: String, minutes: Int, net: Double): CortexOutcomeDisposition8395 {
        val p = CortexOutcomeProof8395(id8418(lane), minutes * 60_000L, at + minutes * 60_000L, net, net + 5.0)
        // the Cortex's own guard (Cortex7885.reviseLabel8006): a revision counts only inside the lane's executable hold
        val exec = CortexLabelHygiene8144.revisionCounts8144(lane in runnerLanes, p.observedAtMs - at)
        return b.labelOutcome8395(p.decisionId, net, p.grossPct, net, exec, p.horizonMs, p.observedAtMs, p.observedAtMs, p)
    }

    @Test fun predictionsSeatsAndGradesSurviveRestartsAndEachCheckpointRevisesOnce8418() {
        val voter = "PROOF8418_RESTART"; val edges = doubleArrayOf(0.0)
        var ledger = CortexLedger7885()
        var calibration = CortexCalibration7901()
        for (i in 0 until 120) {
            val raw = if (i % 2 == 0) 1.0 else -1.0
            ledger.grade("MOONSHOT", listOf(voter), listOf(edges), doubleArrayOf(raw), 10.0 * raw, 10.0 * raw)
            calibration.learn("MOONSHOT", 5.0 * raw, 0.0, 8.0 * raw)
        }
        // the decision under test: booked at its five-minute label
        val trace = ledger.grade("MOONSHOT", listOf(voter), listOf(edges), doubleArrayOf(1.0), 4.0, 9.0)!!
        val calStamp = calibration.learn("MOONSHOT", 5.0, 0.0, 4.0)
        val predictedBefore = ledger.predict(voter, "MOONSHOT", edges, 1.0).netPct
        val authorityBefore = ledger.predict(voter, "MOONSHOT", edges, 1.0).authority
        val calibratedBefore = calibration.calibrate("MOONSHOT", 6.0, 0.0)
        val nBefore = ledger.lanes["MOONSHOT"]!!.n
        val sumBefore = ledger.lanes["MOONSHOT"]!!.sum

        var book = newBook8418()
        for (lane in listOf("MOONSHOT", "TREASURY")) {
            assertTrue(book.addDecision(CortexGradeRecord8144('D', id8418(lane), "proof8418|$lane", at, lane, "BASE", "TREND",
                "EARLY", "LOW", "SMALL", 2, 7.5, true, true, false)))
            assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, deliver8418(book, lane, 5, 4.0))
        }

        // restart #1: the frozen prediction, calibrated read and seat are what was saved
        book = restartBook8418(book); ledger = restartLedger8418(ledger); calibration = restartCalibration8418(calibration)
        assertEquals(predictedBefore, ledger.predict(voter, "MOONSHOT", edges, 1.0).netPct, 1e-9)
        assertEquals(authorityBefore, ledger.predict(voter, "MOONSHOT", edges, 1.0).authority, 1e-9)
        assertTrue("the seat must stay seated across the restart", authorityBefore > 0.0)
        assertEquals(calibratedBefore, calibration.calibrate("MOONSHOT", 6.0, 0.0), 1e-9)
        assertEquals(7.5, book.decision(id8418("MOONSHOT"))!!.predictedEdgePct, 0.0)
        assertEquals(2, book.decision(id8418("MOONSHOT"))!!.bucket)

        // 1 h / 4 h / 24 h checkpoints, a restart after each; the models revise only on ACCEPTED_REVISION
        val expected = mapOf(
            "MOONSHOT" to listOf(CortexOutcomeDisposition8395.ACCEPTED_REVISION, CortexOutcomeDisposition8395.ACCEPTED_REVISION,
                CortexOutcomeDisposition8395.OBSERVED_ONLY),
            "TREASURY" to listOf(CortexOutcomeDisposition8395.ACCEPTED_REVISION, CortexOutcomeDisposition8395.OBSERVED_ONLY,
                CortexOutcomeDisposition8395.OBSERVED_ONLY),
        )
        val checkpoints = listOf(60 to 18.0, 240 to 40.0, 1440 to 90.0)
        var bookedNet = 4.0
        var modelRevisions = 0
        for ((k, cp) in checkpoints.withIndex()) {
            for (lane in listOf("MOONSHOT", "TREASURY")) {
                val d = deliver8418(book, lane, cp.first, cp.second)
                assertEquals("$lane @${cp.first}m", expected[lane]!![k], d)
                if (lane == "MOONSHOT" && d == CortexOutcomeDisposition8395.ACCEPTED_REVISION) {
                    ledger.revise8006("MOONSHOT", listOf(voter), listOf(edges), doubleArrayOf(1.0), trace, bookedNet, cp.second, 9.0, cp.second + 5.0)
                    assertTrue(calibration.revise8322("MOONSHOT", 5.0, 0.0, bookedNet, cp.second, calStamp))
                    bookedNet = cp.second; modelRevisions++
                }
            }
            book = restartBook8418(book); ledger = restartLedger8418(ledger); calibration = restartCalibration8418(calibration)
        }
        assertEquals(2, modelRevisions)

        // every checkpoint redelivered after the last restart: nothing re-enters the grade book or the models
        val redelivered = listOf(5 to 4.0) + checkpoints
        for (cp in redelivered) {
            val d = deliver8418(book, "MOONSHOT", cp.first, cp.second)
            assertTrue("MOONSHOT @${cp.first}m redelivery must not revise: $d", d != CortexOutcomeDisposition8395.ACCEPTED_REVISION &&
                d != CortexOutcomeDisposition8395.ACCEPTED_FIRST)
        }
        assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED, deliver8418(book, "MOONSHOT", 240, 40.0))
        assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED, deliver8418(book, "MOONSHOT", 5, 4.0))
        assertEquals(CortexOutcomeDisposition8395.STALE_OUTCOME, deliver8418(book, "MOONSHOT", 60, 18.0))
        assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED, deliver8418(book, "TREASURY", 60, 18.0))

        val m = book.decision(id8418("MOONSHOT"))!!
        assertEquals("the first (five-minute) executable label is immutable", 4.0, m.executablePct, 0.0)
        assertEquals("the 4 h revision inside the 6 h runner hold owns the grade", 40.0, m.executableRevisedPct, 0.0)
        assertEquals("the 24 h read is observed only", 90.0, m.observedNetPct, 0.0)
        assertEquals(40.0, book.objectivePct8322(m), 0.0)
        assertEquals(7.5, m.predictedEdgePct, 0.0)
        val t = book.decision(id8418("TREASURY"))!!
        assertEquals(18.0, t.executableRevisedPct, 0.0)
        assertEquals("a five-minute lane is graded on its first label", 4.0, book.objectivePct8322(t), 0.0)

        // the voter books replaced 4 -> 40 once: n unchanged, sum moved by exactly +36
        assertEquals(nBefore, ledger.lanes["MOONSHOT"]!!.n, 1e-9)
        assertEquals(sumBefore + 36.0, ledger.lanes["MOONSHOT"]!!.sum, 1e-6)
    }

    // ── the real Cortex7885 snapshot path through LearningPersistence ────────────────────────────────────────────

    private fun field8418(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun mockDb8418(connection: Connection): SQLiteDatabase {
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
        return db
    }

    @Test fun cortexSnapshotRestoresSeatsCalibratedReadAndGradesFromDisk8418() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val db = mockDb8418(connection)
            val cortex = Cortex7885
            val loaded = field8418(cortex, "loaded"); val savedLoaded = loaded.get(cortex)
            val retry = field8418(cortex, "loadRetryAtMs8325"); val savedRetry = retry.get(cortex)
            val dbField = field8418(LearningPersistence, "db"); val savedDb = dbField.get(LearningPersistence)
            val ledger = field8418(cortex, "ledger").get(cortex) as CortexLedger7885
            val book = field8418(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144
            val kept = ledger.detached8386()   // deep copy: grade() mutates the shared lane / all-lane books in place
            val voter = "PROOF8418_DISK"; val lane = "QUALITY"; val edges = doubleArrayOf(0.0)
            val decisionId = "disk8418mint|$lane|$at"
            try {
                loaded.set(cortex, true); dbField.set(LearningPersistence, db)
                for (i in 0 until 240) {
                    val raw = if (i % 2 == 0) 1.0 else -1.0
                    ledger.grade(lane, listOf(voter), listOf(edges), doubleArrayOf(raw), 12.0 * raw, 12.0 * raw)
                }
                book.addDecision(CortexGradeRecord8144('D', decisionId, "disk8418mint|$lane", at, lane, "BASE", "TREND",
                    "EARLY", "LOW", "SMALL", 2, 6.5, true, true, false))
                book.labelOutcome8395(decisionId, 4.0, 9.0, 4.0, true, 300_000L, at + 300_000L, at + 300_000L)
                val seatBefore = ledger.seats["$voter|$lane"]!!
                val predicted = ledger.predict(voter, lane, edges, 1.0)
                val calibrationLine = cortex.calibrationLine8169()
                val recordBefore = book.decision(decisionId)!!

                cortex.javaClass.getDeclaredMethod("persistBlocking8094").apply { isAccessible = true }.invoke(cortex)
                val saved = LearningPersistence.load("CORTEX_7885_V8076")
                assertNotNull("the snapshot must reach the store (an Android-only call on this path would drop it)", saved)
                assertTrue(saved!!.contains("$voter|$lane"))
                assertNotNull(LearningPersistence.load("CORTEX_GRADES_8144"))

                // a fresh process: the in-memory books are gone, the store is not
                ledger.lanes.clear(); ledger.seats.clear(); ledger.pairs.clear()
                loaded.set(cortex, false); retry.set(cortex, 0L)
                cortex.javaClass.getDeclaredMethod("ensureLoaded").apply { isAccessible = true }.invoke(cortex)
                assertEquals(true, loaded.get(cortex))

                val seatAfter = ledger.seats["$voter|$lane"]
                assertNotNull("seat restored from disk", seatAfter)
                assertNotSame(seatBefore, seatAfter)
                assertEquals(seatBefore.scored, seatAfter!!.scored)
                val restored = ledger.predict(voter, lane, edges, 1.0)
                assertEquals(predicted.netPct, restored.netPct, 1e-9)
                assertEquals(predicted.authority, restored.authority, 1e-9)
                assertEquals(calibrationLine, cortex.calibrationLine8169())
                val recordAfter = book.decision(decisionId)
                assertNotNull("grade record restored from disk", recordAfter)
                assertNotSame(recordBefore, recordAfter)
                assertEquals(6.5, recordAfter!!.predictedEdgePct, 0.0)
                assertEquals(4.0, recordAfter.executablePct, 0.0)
                // the restored five-minute grade is not re-applied by a redelivery after the restart
                assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED,
                    book.labelOutcome8395(decisionId, 4.0, 9.0, 4.0, true, 300_000L, at + 300_000L, at + 300_000L))
            } finally {
                ledger.lanes.clear(); ledger.lanes.putAll(kept.lanes)
                ledger.seats.clear(); ledger.seats.putAll(kept.seats)
                ledger.pairs.clear(); ledger.pairs.putAll(kept.pairs)
                loaded.set(cortex, savedLoaded); retry.set(cortex, savedRetry); dbField.set(LearningPersistence, savedDb)
            }
        }
    }
}
