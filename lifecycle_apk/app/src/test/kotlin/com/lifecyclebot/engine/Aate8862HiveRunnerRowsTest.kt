package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HiveRunnerRows8862
import com.lifecyclebot.engine.truth.RunnerModel8861
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * V5.0.8862 — the hive both ways for the runner model: an install census, and the model's rows shared as a union keyed by
 * launch. Another install's row teaches the model; only this install's own rows can prove it.
 */
class Aate8862HiveRunnerRowsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val M = RunnerModel8861
    private val nF = RunnerModel8861.FEATURES_8861.size
    private val now = 1_795_000_000_000L
    private val mintA = "A".repeat(40) + "pump"

    @After fun tearDown() { M.resetForTest8861() }

    private fun dbRow(mint: String = mintA, born: Long = now - 5 * 3_600_000L, x: String = HiveRunnerRows8862.encodeX8862(DoubleArray(nF) { it.toDouble() }),
                      net: Any? = 42.5, doubled: Any? = 1, past: Any? = 0): Map<String, Any?> =
        mapOf("mint" to mint, "born" to born, "x" to x, "doubled" to doubled, "net" to net, "past" to past, "updated_ms" to now)

    @Test fun aRowTravelsAsItWasGraded() {
        val x = DoubleArray(nF) { if (it == 3) Double.NaN else it * 1.25 }
        val back = HiveRunnerRows8862.decodeX8862(HiveRunnerRows8862.encodeX8862(x), nF)!!
        for (i in 0 until nF) assertTrue("reading $i", (x[i].isNaN() && back[i].isNaN()) || x[i] == back[i])
        assertNull("a row of another feature list", HiveRunnerRows8862.decodeX8862("1.0,2.0", nF))
        assertNull(HiveRunnerRows8862.decodeX8862(List(nF) { "x" }.joinToString(","), nF))
        assertNull(HiveRunnerRows8862.decodeX8862(null, nF))
        val r = HiveRunnerRows8862.rowOf8862(dbRow(), now)!!
        assertFalse("graded by another install", r.mine)
        assertEquals(mintA, r.mint); assertEquals(42.5, r.net, 0.0); assertTrue(r.doubled); assertFalse(r.pastHour); assertEquals(5.0, r.x[5], 0.0)
        // the database hands numbers back as text as often as not
        assertEquals(-12.0, HiveRunnerRows8862.rowOf8862(dbRow(net = "-12.0", doubled = "0"), now)!!.net, 0.0)
    }

    @Test fun aRowThatCannotBeAGradedLaunchIsRefused() {
        assertNull("not a mint", HiveRunnerRows8862.rowOf8862(dbRow(mint = "short"), now))
        assertNull("born in the future", HiveRunnerRows8862.rowOf8862(dbRow(born = now + 60_000L), now))
        assertNull("older than any row an install could hold", HiveRunnerRows8862.rowOf8862(dbRow(born = now - HiveRunnerRows8862.MAX_ROW_AGE_MS_8862 - 1L), now))
        assertNull("no return", HiveRunnerRows8862.rowOf8862(dbRow(net = null), now))
        assertNull("a position cannot lose more than itself", HiveRunnerRows8862.rowOf8862(dbRow(net = -350.0), now))
        assertNull("another feature list", HiveRunnerRows8862.rowOf8862(dbRow(x = "1,2,3"), now))
    }

    private fun own(n: Int) = List(n) { i ->
        val runs = i % 5 < 2
        val x = DoubleArray(nF); x[M.FEATURES_8861.indexOf("volAccel")] = if (runs) 3.0 else 1.0
        RunnerModel8861.Row("Mint$i", 1_000_000L + i * 60_000L, x, runs, if (runs) 100.0 else -20.0, false)
    }

    @Test fun anotherInstallsRowTeachesTheModelAndNeverEntersTheForwardRecord() {
        M.resetForTest8861()
        M.seedForTest8861(own(500), emptyList(), emptyList(), 0L)
        val theirs = HiveRunnerRows8862.rowOf8862(dbRow(), now)!!          // a launch far newer than anything the model saw, +42.5 %
        assertEquals(1, M.offerHive8862(listOf(theirs)))
        assertEquals("offered twice, taken once", 0, M.offerHive8862(listOf(theirs)))
        assertEquals("this install's own rows are never taken back from the hive", 0, M.offerHive8862(own(1)))
        M.evaluate8861(3.0, 10_000L, now)
        val line = M.statusLine8861()
        assertTrue(line, line.contains("runnerModel8861[ran rows=501 new=1 "))
        assertTrue(line, line.contains("rowsFromOtherInstalls8862=1 "))
        assertTrue("a newer launch the model had not seen, yet not a forward pick: it is not this phone's", line.contains("forward{picks n0 of every cross n0}"))
        assertEquals("a launch this install already holds is not taken again", 0, M.offerHive8862(listOf(theirs)))
        // what this install sends: its own rows only, oldest first, after the mark
        val send = M.ownRowsAfter8862(1_000_000L + 497 * 60_000L, 10)
        assertEquals(listOf("Mint498", "Mint499"), send.map { it.mint })
    }

    @Test fun theFileKeepsEachRowsOriginAndAnOlderFileIsAllThisInstalls() {
        val theirs = HiveRunnerRows8862.rowOf8862(dbRow(), now)!!
        val rs = own(2) + theirs
        val empty = RunnerModel8861.Record()
        val bytes = ByteArrayOutputStream().also { o -> DataOutputStream(o).use { M.write8861(it, rs, empty, empty) } }.toByteArray()
        val back = M.read8861(DataInputStream(ByteArrayInputStream(bytes)))!!.first
        assertEquals(listOf(true, true, false), back.map { it.mine })
        // a file written by 5.0.8861: no origin per row
        val v1 = ByteArrayOutputStream().also { o -> DataOutputStream(o).use { out ->
            out.writeInt(0x524D3836); out.writeInt(nF)
            repeat(2) { out.writeInt(0); out.writeDouble(0.0); out.writeDouble(0.0); out.writeDouble(0.0); out.writeInt(0); out.writeInt(0) }
            out.writeInt(1)
            out.writeUTF("MintOld"); out.writeLong(123L); repeat(nF) { out.writeDouble(it.toDouble()) }; out.writeBoolean(true); out.writeDouble(7.0); out.writeBoolean(true)
        } }.toByteArray()
        val old = M.read8861(DataInputStream(ByteArrayInputStream(v1)))!!.first.single()
        assertTrue(old.mine); assertEquals("MintOld", old.mint); assertEquals(7.0, old.net, 0.0); assertTrue(old.doubled && old.pastHour)
    }

    @Test fun theHiveMarksTheInstallSendsOnceAndNeverPullsItsOwn() {
        val hive = src("engine/truth/HiveRunnerRows8862.kt")
        assertTrue("the first install to grade a launch keeps its row", hive.contains("\"INSERT OR IGNORE INTO \$ROWS_TABLE_8862 (mint, born, x, doubled, net, past, updated_ms, instance, build) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)\""))
        assertTrue(hive.contains("FROM \$ROWS_TABLE_8862 WHERE updated_ms > ? AND instance != ? ORDER BY updated_ms LIMIT \$PULL_LIMIT"))
        assertTrue(hive.contains("\"SELECT COUNT(*) AS n FROM \$INSTALLS_TABLE_8862 WHERE updated_ms > ? AND instance != ?\""))
        assertEquals("hive_runner_rows_8861", HiveRunnerRows8862.ROWS_TABLE_8862)
        val edge = src("engine/truth/HiveEdge8000.kt").substringAfter("suspend fun sync8000")
        assertTrue(edge.indexOf("HiveRunnerRows8862.sync8862(client, instanceId, now)") > edge.indexOf("inherit()"))
        val model = src("engine/truth/RunnerModel8861.kt")
        val loop = model.substringAfter("for (r in fresh) {")
        assertTrue("origin is checked before a row can be a forward pick", loop.indexOf("if (!r.mine) fromHive++") in 0 until loop.indexOf("picks.add(r.net)"))
        assertTrue(HiveRunnerRows8862.statusLine8862().startsWith("hiveRunnerRows8862[otherInstalls7d=unread rows{sent=0 pulled=0 taken=0 refused=0} syncs=0 failed=0 "))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Hive runner rows (§8862): "))
    }
}
