package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.StrategyLab8755
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V5.0.9033 — the 26 Nov 23:38 report (5.0.9031, 76 min): the collector blocking for 80 % of the run while five batch jobs
 * that need 10 to 31 s of CPU each ran for 4 to 16 minutes over one another; the strategy lab failing with a JSONException
 * on every cycle; a heap map excerpt that named classes and not owners.
 */
class Aate9033BatchTurnsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun whichJobsWaitAndForHowLong() {
        assertEquals(JobCost8813.TURN_WAIT_MAX_MS_9033, JobCost8813.turnWaitMs9033("strategyLab", waitsForHeadroom = true))
        assertEquals(JobCost8813.YIELDING_TURN_WAIT_MS_9033, JobCost8813.turnWaitMs9033("treeTrain", waitsForHeadroom = false))
        assertEquals("the Cortex snapshot is durability, not a batch job", 0L, JobCost8813.turnWaitMs9033("cortexSnapshot", true))
        assertEquals(0L, JobCost8813.turnWaitMs9033("coingeckoCoinList", true))
        // superseded by V5.0.9035 — the launch replay, the heaviest batch thread of the 23:38 CPU sample, joins the five
        assertEquals(setOf("datasetSeed", "treeParse", "treeTrain", "strategyLab", "authorityTable8887", "launchReplay"), JobCost8813.GATED_9033)
        // the edge model's replay runs inside the model's load lock: a reader of the model would wait behind the turn
        assertFalse("edgeReplay" in JobCost8813.GATED_9033)
        assertFalse("edgeConvictionSeed" in JobCost8813.GATED_9033)
        assertTrue(JobCost8813.headroomWaits9033("CRITICAL", 0L, waitsForHeadroom = true))
        assertTrue(JobCost8813.headroomWaits9033("HARD", 60_000L, true))
        assertFalse(JobCost8813.headroomWaits9033("SOFT", 0L, true))
        assertFalse(JobCost8813.headroomWaits9033("NORMAL", 0L, true))
        assertFalse("the tree's refit is what the decision waits for", JobCost8813.headroomWaits9033("CRITICAL", 0L, waitsForHeadroom = false))
        assertFalse("no wait is unbounded", JobCost8813.headroomWaits9033("CRITICAL", JobCost8813.HEADROOM_WAIT_MAX_MS_9033, true))
        assertTrue(JobCost8813.HEADROOM_STEP_MS_9033 in 1_000L..10_000L)
        assertTrue(JobCost8813.turnsLine9033().contains("no wait is unbounded"))
        assertTrue(JobCost8813.statusLine8813().startsWith("jobCost8813["))
        val jc = src("engine/JobCost8813.kt")
        assertTrue(jc.contains("val turn9033 = heavyEnter9033(name, waitsForHeadroom = true)"))
        assertTrue(jc.contains("val turn9033 = heavyEnter9033(name, waitsForHeadroom = false)"))
        assertEquals(2, Regex("finally \\{ heavyExit9033\\(turn9033\\) \\}").findAll(jc).count())
        assertTrue("headroom is waited for before the turn is taken, not while holding it",
            jc.indexOf("while (headroomWaits9033(pressureName9033(), waited, headroom9042))") < jc.indexOf("if (heavyTurn9033.tryAcquire()) true"))
    }

    @Test fun twoBatchJobsDoNotRunAtOnceAndANestedJobIsPartOfItsOwnersTurn() {
        JobCost8813.pressureForTest9033 = "NORMAL"
        try {
            val order = Collections.synchronizedList(ArrayList<String>())
            val aInside = CountDownLatch(1); val release = CountDownLatch(1); val bDone = CountDownLatch(1)
            val a = Thread {
                JobCost8813.measureBackground8817("datasetSeed") {
                    order.add("A-start")
                    // a job started inside this one on the same thread does not wait for the turn its owner holds
                    order.add("A-nested-" + JobCost8813.measureBackground8817("authorityTable8887") { 5 })
                    aInside.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    order.add("A-end")
                }
            }
            val b = Thread {
                JobCost8813.measureYielding8866("treeParse") { order.add("B-start") }
                bDone.countDown()
            }
            a.start()
            assertTrue(aInside.await(30, TimeUnit.SECONDS))
            b.start()
            Thread.sleep(300L)
            assertEquals("B waits while A holds the turn", listOf("A-start", "A-nested-5"), order.toList())
            release.countDown()
            assertTrue(bDone.await(30, TimeUnit.SECONDS))
            a.join(30_000L)
            assertEquals(listOf("A-start", "A-nested-5", "A-end", "B-start"), order.toList())
            // the turn was given back: the next batch job runs at once; a job that is not a batch job never waits
            assertEquals(9, JobCost8813.measureBackground8817("strategyLab") { 9 })
            assertEquals(7, JobCost8813.measureBackground8817("coingeckoCoinList") { 7 })
        } finally { JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813() }
    }

    @Test fun aJobWaitingForHeadroomHoldsNothingAnotherJobWaitsFor() {
        JobCost8813.pressureForTest9033 = "CRITICAL"
        try {
            val ran = CountDownLatch(1)
            // superseded by V5.0.9042 — the dataset seed is a decision input and no longer waits for headroom; the lab does
            val a = Thread { JobCost8813.measureBackground8817("strategyLab") { ran.countDown() } }
            a.start()
            Thread.sleep(300L)
            assertEquals("a background batch job does not start into a full heap", 1L, ran.count)
            // the tree's refit does not wait for headroom, and the sleeper is not holding the turn
            assertEquals(3, JobCost8813.measureYielding8866("treeTrain") { 3 })
            assertEquals(1L, ran.count)
            JobCost8813.pressureForTest9033 = "NORMAL"
            assertTrue("it runs on the first step with room", ran.await(JobCost8813.HEADROOM_STEP_MS_9033 * 4, TimeUnit.MILLISECONDS))
            a.join(30_000L)
        } finally { JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813() }
    }

    @Test fun aOneSidedCellIsStoredAndReadBack() {
        // the miner's one-sided boxes: "liq <= 5000" is lo = -infinity. put(String, double) throws on an infinity.
        val below = StrategyLab8755.Spec(StrategyLab8755.Family.CELL, "ANY", "ANY", conds = listOf(StrategyLab8755.Cond("liq", Double.NEGATIVE_INFINITY, 5_000.0)))
        val above = StrategyLab8755.Spec(StrategyLab8755.Family.CELL, "MOONSHOT", "ANY",
            conds = listOf(StrategyLab8755.Cond("mcap", 20_000.0, Double.POSITIVE_INFINITY), StrategyLab8755.Cond("age", 1.0, 30.0)))
        for (spec in listOf(below, above)) {
            val js = StrategyLab8755.specJson8755(spec)
            assertFalse(js, js.contains("Infinity"))
            assertEquals(spec, StrategyLab8755.specOf8755(JSONObject(js)))
        }
        assertFalse(StrategyLab8755.specJson8755(below).contains("\"lo\""))
        assertTrue(StrategyLab8755.specJson8755(above).contains("\"lo\":20000"))
    }

    @Test fun theStrategyLabWaitsForRoomButNotForEver() {
        assertTrue(StrategyLab8755.deferredForMemory9033("CRITICAL", 0L))
        assertTrue(StrategyLab8755.deferredForMemory9033("hard", 59L * 60_000L))
        assertFalse(StrategyLab8755.deferredForMemory9033("SOFT", 0L))
        assertFalse(StrategyLab8755.deferredForMemory9033("NORMAL", 0L))
        assertFalse("past the limit it runs", StrategyLab8755.deferredForMemory9033("CRITICAL", StrategyLab8755.DEFER_MAX_MS_9033))
        val lab = src("engine/lab/StrategyLab8755.kt")
        val defer = lab.indexOf("if (deferredForMemory9033(pressure, if (since == 0L) 0L else nowMs - since))")
        assertTrue("deferred before the hour is spent", defer >= 0 && defer < lab.indexOf("lastRunMs = nowMs"))
    }

    @Test fun theAutomaticMapIsOncePerBuildAndTheReportShowsOwners() {
        assertTrue(HeapMap8866.autoTakenForBuild9033("5.0.9033", "5.0.9033"))
        assertFalse(HeapMap8866.autoTakenForBuild9033("5.0.9032", "5.0.9033"))
        assertFalse(HeapMap8866.autoTakenForBuild9033(null, "5.0.9033"))
        assertFalse("an unknown build name never blocks the map", HeapMap8866.autoTakenForBuild9033("", ""))
        val map = src("engine/HeapMap8866.kt")
        assertTrue(map.indexOf("if (autoTakenForBuild9033(prefs9033?.getString(\"build\", null), build9033))") < map.indexOf("start8866(app, \"auto-pressure\""))

        val lines = ArrayList<String>()
        lines += listOf("AATE heap map", "format", "objects", "heaps", "", "-- classes holding the most bytes (shallow) --")
        for (i in 1..70) lines += "    %5.1fMB  %4.1f%%  %8d  Class$i".format(100.0 - i, 10.0, i)
        lines += ""
        lines += "-- what holds the bytes (12140 objects sampled by size) --"
        lines += "   read a chain right to left"
        for (o in 1..40) {
            lines += "     %5.1fMB  %4.1f%% n=%d    static Owner$o".format(50.0 - o, 5.0, o)
            for (c in 1..9) lines += "         1.0MB n=1     chain$o.$c"
        }
        val shown = HeapMap8866.excerpt9033(lines, 90)
        assertEquals(90, shown.size)
        assertTrue(shown.contains(lines[6 + HeapMap8866.REPORT_CLASS_ROWS_9033 - 1]))
        assertFalse("the 19th class row is folded", shown.contains(lines[6 + HeapMap8866.REPORT_CLASS_ROWS_9033]))
        assertTrue(shown.any { it.contains("and 52 more classes in the full map") })
        // the chains of the first owners, then owner rows only: far more owners than 90 lines of the raw text reached
        assertTrue(shown.any { it.endsWith("chain${HeapMap8866.OWNER_CHAINS_9033}.9") })
        assertFalse(shown.any { it.contains("chain${HeapMap8866.OWNER_CHAINS_9033 + 1}.") })
        assertTrue(shown.any { it.endsWith("static Owner30") })
        // a short map is shown whole; a text without the headings is cut as before
        assertEquals(lines.take(20), HeapMap8866.excerpt9033(lines.take(20), 90))
        val plain = (1..200).map { "line $it" }
        assertEquals(plain.take(5), HeapMap8866.excerpt9033(plain, 5))
        val block = HeapMap8866.reportBlock8866(lines.joinToString("\n"), 0L, 600_000L, "", maxLines = 90)
        assertTrue(block, block.contains("90 of ${lines.size} lines: the largest ${HeapMap8866.REPORT_CLASS_ROWS_9033} classes, then every owner row"))
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §218 — V5.0.9033: the batch jobs take turns"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9033*'"))
    }
}
