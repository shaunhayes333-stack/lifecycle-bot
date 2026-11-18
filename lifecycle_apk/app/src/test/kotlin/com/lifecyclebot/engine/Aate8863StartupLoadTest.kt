package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DatasetReplay8863
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LearningEpoch8164
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8863 — the launch freeze and the start-up load: the splash returns to the dashboard that is already there; the
 * labelled dataset is read once for the three ledgers and a row its own text refuses is not parsed; a forensic line is
 * not an error-log row; the error log commits a quarter second of rows at a time and keeps its errors.
 */
class Aate8863StartupLoadTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "Abcdefghijkmnpqrstuvwxyz123456789pump"

    @After fun tearDown() {
        LearningEpoch8164.setForTest8164(0L)
        DatasetReplay8863.resetForTest8863()
        ForensicLogger.resetForTest8863()
    }

    /** A row in the order EdgeModel8537.datasetLine writes it: "t" first, bare literals. */
    private fun row(t: Long, fill: Boolean = true, extra: String = "") =
        "{\"t\":$t,\"horizonMs\":900000,\"mint\":\"$mint\",\"lane\":\"MOONSHOT\",\"src\":\"X\",\"liq\":20000.0,\"mcap\":200000.0," +
            "\"age\":12.0,\"cost\":3.5,\"net\":-30.0,\"fill\":$fill,\"witness\":false$extra}"

    // ── a row its own text refuses is not parsed ──

    @Test fun aRowBeforeTheLearningLineIsRefusedFromItsText() {
        val line = 1_795_024_788_972L
        assertTrue(EdgeModel8537.refusedUnparsed8863(row(line - 1), line))
        assertFalse("recorded at the line", EdgeModel8537.refusedUnparsed8863(row(line), line))
        assertFalse(EdgeModel8537.refusedUnparsed8863(row(line + 60_000L), line))
        assertFalse("no line: no row is refused for its time", EdgeModel8537.refusedUnparsed8863(row(line - 1), 0L))
    }

    @Test fun anUnfilledRowIsRefusedFromItsText() {
        assertTrue(EdgeModel8537.refusedUnparsed8863(row(1_795_024_788_972L, fill = false), 0L))
        assertFalse(EdgeModel8537.refusedUnparsed8863(row(1_795_024_788_972L, fill = true), 0L))
    }

    @Test fun whatTheTextCannotProveIsLeftToTheParser() {
        val line = 2_000L
        // the time: not the first key, written twice, a leading zero (octal to the parser), quoted, too long for a Long, or the last field
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"horizonMs\":900000,\"t\":1000,\"fill\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":1000,\"horizonMs\":900000,\"t\":5000,\"fill\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":0777,\"horizonMs\":900000,\"fill\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":\"1000\",\"horizonMs\":900000,\"fill\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":1000000000000000000000,\"horizonMs\":900000,\"fill\":true}", Long.MAX_VALUE))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":1000}", line))
        // "fill": written twice, inside a nested object, or not the bare literal false
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":5000,\"fill\":false,\"horizonMs\":900000,\"fill\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":5000,\"ext\":{\"fill\":false},\"fill2\":true}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("{\"t\":5000,\"fill\":\"false\",\"horizonMs\":900000}", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("", line))
        assertFalse(EdgeModel8537.refusedUnparsed8863("not a row", line))
    }

    @Test fun theTextNeverRefusesARowTheRuleWouldTake() {
        val line = 1_795_024_788_972L
        val rows = listOf(row(line - 86_400_000L), row(line - 1), row(line), row(line + 1), row(line + 3_600_000L),
            row(line + 1, fill = false), row(line - 1, fill = false), row(line + 5, extra = ",\"volAccel\":1.5"))
        for (epoch in listOf(0L, line)) {
            LearningEpoch8164.setForTest8164(epoch)
            var taken = 0
            for (r in rows) {
                val accepted = EdgeModel8537.replayAccept8642(JSONObject(r))
                if (accepted) taken++
                if (EdgeModel8537.refusedUnparsed8863(r)) assertFalse("epoch=$epoch $r", accepted)
            }
            assertTrue("the rule takes some of these rows, so the test is not empty (epoch=$epoch)", taken >= 3)
        }
        // with the line set, every filled row before it and every unfilled row is refused without a parse
        LearningEpoch8164.setForTest8164(line)
        assertEquals(4, rows.count { EdgeModel8537.refusedUnparsed8863(it) })
    }

    // ── the line count on bytes ──

    private fun counts(bytes: ByteArray): Pair<Int, Int> {
        val f = File.createTempFile("aate-8863-lines-", ".jsonl")
        try {
            f.writeBytes(bytes)
            return EdgeModel8537.countLines8863(f) to f.useLines { it.count() }
        } finally { f.delete() }
    }

    @Test fun theByteCountIsTheLineReadersCount() {
        for (text in listOf("", "a", "a\n", "a\nb", "a\r\nb\r\n", "a\rb", "\n\n", "\r\r\n\n", "x\n\r", "é—漢\n字", "\r", "\r\n")) {
            val (mine, readers) = counts(text.toByteArray(Charsets.UTF_8))
            assertEquals("lines of ${text.toByteArray(Charsets.UTF_8).toList()}", readers, mine)
        }
        // a \r\n split across the 64 KB read buffer, a bare \n, a bare \r and a last line without a terminator
        val big = ByteArray(200_000) { 97 }
        big[65_535] = 13; big[65_536] = 10; big[131_071] = 10; big[150_000] = 13
        val (mine, readers) = counts(big)
        assertEquals(4, mine)
        assertEquals(readers, mine)
        assertEquals(0, EdgeModel8537.countLines8863(File("no-such-file-8863.jsonl")))
    }

    // ── one read for the three ledgers ──

    @Test fun oneReadGivesEveryLedgerEachAcceptedRowOnceInTheOrderWritten() {
        LearningEpoch8164.setForTest8164(0L)
        val lines = listOf(
            "{\"t\":1,\"horizonMs\":900000,\"id\":\"a\",\"ok\":true}",
            "{\"t\":2,\"id\":\"no-horizon\",\"ok\":true}",
            "{\"t\":3,\"horizonMs\":900000,\"id\":\"unfilled\",\"ok\":true,\"fill\":false}",
            "{\"t\":4,\"horizonMs\":900000,\"id\":\"not-taken\",\"ok\":false}",
            "not json, though it says \"horizonMs\"",
            "{\"t\":5,\"horizonMs\":900000,\"id\":\"b\",\"ok\":true}",
        )
        val first = ArrayList<String>(); val second = ArrayList<String>(); val third = ArrayList<String>()
        var asked = 0
        val tally = DatasetReplay8863.pass8863(
            lines.asSequence(),
            accept = { o -> asked++; o.optBoolean("ok") },
            sinks = listOf(
                { o -> first.add(o.getString("id")) },
                { o -> if (o.getString("id") == "a") error("this ledger cannot file a"); second.add(o.getString("id")) },
                { o -> third.add(o.getString("id")) },
            ),
        )
        assertEquals(listOf("a", "b"), first)
        assertEquals("a ledger that throws loses that row for itself only", listOf("b"), second)
        assertEquals(listOf("a", "b"), third)
        assertEquals("the rule is asked once per parsed row, not once per ledger", 3, asked)
        assertEquals(6L, tally.lines)
        assertEquals("the unfilled row is refused by its text", 1L, tally.refusedUnparsed)
        assertEquals(3L, tally.parsed)
        assertEquals(2L, tally.accepted)
    }

    @Test fun aRowRefusedByItsTextNeverReachesTheParserOrALedger() {
        val seen = ArrayList<String>()
        var asked = 0
        val tally = DatasetReplay8863.pass8863(
            sequenceOf("{\"horizonMs\":1,\"id\":\"x\"}", "{\"horizonMs\":1,\"id\":\"y\"}"),
            accept = { asked++; true },
            sinks = listOf({ o -> seen.add(o.getString("id")) }),
            refused = { it.contains("\"x\"") },
        )
        assertEquals(listOf("y"), seen)
        assertEquals(1, asked)
        assertEquals(1L, tally.refusedUnparsed)
    }

    @Test fun aReadThatStoppedResumesAfterTheLinesAlreadyOffered() {
        LearningEpoch8164.setForTest8164(0L)
        val all = (1..7).map { "{\"t\":$it,\"horizonMs\":900000,\"id\":\"r$it\"}" }
        val got = ArrayList<String>()
        val sinks = listOf<(JSONObject) -> Unit>({ o -> got.add(o.getString("id")) })
        val tally = DatasetReplay8863.Counts8863()
        DatasetReplay8863.pass8863(all.take(3).asSequence(), { true }, sinks, tally)          // the read failed after three lines
        val resumeAfter = tally.lines
        var index = 0L
        DatasetReplay8863.pass8863(all.asSequence().filter { index++ >= resumeAfter }, { true }, sinks, tally)
        assertEquals((1..7).map { "r$it" }, got)
        assertEquals(7L, tally.lines)
        assertEquals(7L, tally.accepted)
    }

    @Test fun withNoDatasetTheLedgersStartFromLiveLabels() {
        DatasetReplay8863.resetForTest8863()
        assertTrue(DatasetReplay8863.await8863())
        assertTrue(DatasetReplay8863.await8863())
        val status = DatasetReplay8863.statusLine8863()
        assertTrue(status, status.contains("datasetSeed8863[no dataset") && status.contains("reads=1 failed=0"))
    }

    @Test fun theThreeLedgersWaitForTheOneReadAndTheOtherReadersAskTheTextFirst() {
        val replay = src("engine/truth/DatasetReplay8863.kt")
        assertTrue(replay.contains("JobCost8813.measureBackground8817(\"datasetSeed\")"))
        assertTrue(replay.contains("{ o -> PocketLedger8610.seedRow8863(o) }") && replay.contains("{ o -> PayingCell8723.seedRow8863(o) }") &&
            replay.contains("{ o -> ManagedBands8719.seedRow8863(o) }"))
        for (ledger in listOf("PocketLedger8610", "PayingCell8723", "ManagedBands8719")) {
            val s = src("engine/truth/$ledger.kt")
            assertTrue(ledger, s.contains("ready = DatasetReplay8863.await8863()") && s.contains("internal fun seedRow8863(o: JSONObject)"))
            assertFalse("$ledger reads the dataset itself", s.contains("forEachLine") || s.contains("datasetFile()"))
        }
        assertTrue(src("engine/truth/TreeRanker8737.kt").contains("if (EdgeModel8537.refusedUnparsed8863(line)) continue"))
        assertTrue(src("engine/truth/TreeRanker8737.kt").contains("val total = EdgeModel8537.countLines8863(dataset)"))
        assertTrue(src("engine/lab/StrategyLab8755.kt").contains("if (EdgeModel8537.refusedUnparsed8863(line)) return null"))
        val edge = src("engine/truth/EdgeModel8537.kt")
        assertTrue(edge.contains("if (refusedUnparsed8863(line)) return@row8844"))
        assertTrue(edge.contains("val total = countLines8863(file)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Dataset seed (§8863): "))
    }

    // ── the splash ──

    @Test fun theSplashReturnsToTheDashboardThatIsThere() {
        val splash = src("ui/SplashActivity.kt")
        assertTrue(splash.contains("startActivity(dashboardIntent8863())"))
        assertTrue(splash.contains("Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)"))
        assertFalse("a bare intent builds a second dashboard", splash.contains("startActivity(Intent(this, MainActivity::class.java))"))
    }

    // ── the logs ──

    @Test fun theForensicTailKeepsTheNewestLinesOldestFirst() {
        ForensicLogger.resetForTest8863()
        val max = ForensicLogger.TAIL_MAX_8863
        repeat(max + 5) { ForensicLogger.keepTail8863(1_795_000_000_000L + it, "line $it") }
        val tail = ForensicLogger.tail8863()
        assertEquals(max, tail.size)
        assertTrue(tail.first(), tail.first().endsWith(" line 5"))
        assertTrue(tail.last(), tail.last().endsWith(" line ${max + 4}"))
        assertEquals(listOf(" line ${max + 2}", " line ${max + 3}", " line ${max + 4}"), ForensicLogger.tail8863(3).map { it.substring(it.indexOf(' ')) })
        assertTrue(ForensicLogger.tailText8863(2).startsWith("Forensic tail (§8863): newest 2 of "))
        assertTrue(ForensicLogger.statusLine8863().contains("tail=$max/$max"))
    }

    @Test fun aForensicLineIsNotAnErrorLogRowAndTheErrorLogKeepsItsErrors() {
        val forensic = src("engine/ForensicLogger.kt")
        assertTrue(forensic.contains("write8863(next)"))
        assertTrue(forensic.contains("android.util.Log.i(\"AATE.FORENSIC\", text)"))
        assertFalse("a forensic line is inserted into the error table", forensic.contains("ErrorLogger.info(\"FORENSIC\""))
        assertTrue(src("engine/ReportingHub.kt").contains("ForensicLogger.tailText8863()"))
        val log = src("engine/ErrorLogger.kt")
        assertTrue(log.contains("handler.postDelayed({ flush8818(database) }, FLUSH_WAIT_MS_8863)"))
        assertTrue(log.contains("if (level == Level.ERROR || level == Level.CRASH || queued >= FLUSH_NOW_ROWS_8863) {"))
        assertTrue(log.contains("else if (batch.size >= BATCH_MAX_8818) h.post { flush8818(database) }"))
        assertTrue(log.contains("AND level IN ('DEBUG','INFO')"))
        assertTrue(log.contains("WHERE level NOT IN ('DEBUG','INFO') ORDER BY id DESC LIMIT 1 OFFSET \$MAX_LOGS), -1)"))
        assertFalse("the start-up trim cuts every level to the newest rows", log.contains("ORDER BY timestamp DESC LIMIT \$MAX_LOGS)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Forensic log (§8863): "))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 54. The launch freeze and the start-up load (8863)"))
    }
}
