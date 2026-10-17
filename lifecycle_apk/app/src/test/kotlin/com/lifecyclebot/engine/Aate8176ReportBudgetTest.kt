package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8176 — the copied Pipeline Health report holds a hard 180k budget without losing the essentials. */
class Aate8176ReportBudgetTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun section(title: String, lines: List<String>): String =
        (listOf("===== $title =====") + lines).joinToString("\n")

    private fun bodyOf(result: String): String = result.substringBefore("\n===== COPY BUDGET 8176 =====")

    private fun report(recentLines: Int = 200): String = listOf(
        section("AATE Pipeline Health Snapshot", listOf("  build=5.0.8176 uptime=3600s", "  Canonical bootstrap timeline (§7557): ok")),
        section("AATE Cortex scoreboard (§7919)", listOf("  seat MOONSHOT a=0.62 skill=+3.1%")),
        section("Throughput choke audit (V5.9.951)", listOf("  choke=none")),
        section("Labelled counters (lane / error / lock / etc.)", (0 until 50).map { "  ${"LIFECYCLE/K$it:".padEnd(30)}${it + 1}" } + "  LIVE_BUY_FAIL_8176:           7"),
        section("Recent events (last 80 of 150)", (0 until recentLines).map { "  12:00:0$it  INTAKE  SYM  " + "x".repeat(90) }),
        section("Interpretation cheat-sheet", listOf("  BOT_LOOP_TICK=5 ok")),
    ).joinToString("\n")

    @Test fun underBudgetIsUnchanged() {
        val text = report(recentLines = 3)
        assertEquals(text, ReportBudget8176.budget8176(text, 1_000_000))
    }

    @Test fun overBudgetTrimsLowPrioritySectionsFirst() {
        val text = report()
        assertTrue(text.length > 15_000)
        val out = ReportBudget8176.budget8176(text, 8_000)
        assertTrue(out.length <= 8_000)
        assertFalse(out.contains("===== Recent events"))
        assertTrue(out.contains("===== AATE Pipeline Health Snapshot ====="))
        assertTrue(out.contains("===== AATE Cortex scoreboard"))
        assertTrue(out.contains("===== Interpretation cheat-sheet ====="))
        // dropping the lowest-priority section was enough: medium and other low sections survive
        assertTrue(out.contains("===== Throughput choke audit"))
        assertTrue(out.contains("===== Labelled counters"))
        assertEquals("Recent events", ReportBudget8176.LOW_PRIORITY_8176.first())
        assertTrue(ReportBudget8176.PROTECTED_8176.contains("Interpretation cheat-sheet"))
    }

    @Test fun footerListsTrimmedSectionsInPriorityOrderAndSalvagesAlarms() {
        val text = report()
        val out = ReportBudget8176.budget8176(text, 1_500)
        assertTrue(out.length <= 1_500)
        val footer = out.substringAfter("===== COPY BUDGET 8176 =====")
        assertTrue(out.contains("===== COPY BUDGET 8176 ====="))
        val recent = footer.indexOf("- Recent events (last 80 of 150)")
        val labelled = footer.indexOf("- Labelled counters")
        assertTrue(recent >= 0)
        assertTrue(labelled > recent)
        assertTrue(footer.contains("LIVE_BUY_FAIL_8176"))
        assertTrue(out.contains("===== Interpretation cheat-sheet ====="))
    }

    @Test fun neverCutsMidLine() {
        val inputLines = report().split('\n').toSet()
        for (max in listOf(1_500, 4_000, 8_000)) {
            val out = ReportBudget8176.budget8176(report(), max)
            assertTrue(out.length <= max)
            bodyOf(out).split('\n').forEach { assertTrue("cut line: $it", it in inputLines) }
        }
        // protected-only overflow: the tail of the protected section goes, in whole lines
        val big = section("AATE Pipeline Health Snapshot", (0 until 500).map { "  status line $it " + "y".repeat(40) })
        val bigLines = big.split('\n').toSet()
        val out = ReportBudget8176.budget8176(big, 3_000)
        assertTrue(out.length <= 3_000)
        bodyOf(out).split('\n').forEach { assertTrue("cut line: $it", it in bigLines) }
        assertTrue(out.contains("[tail "))
        val cut = ReportBudget8176.lineCut8176("aaaa\nbbbb\ncccc", 11)
        assertEquals("aaaa\nbbbb", cut)
    }

    @Test fun zeroCountersAndNoneRowsAreCompacted() {
        val text = section("ENTRY FUNNEL STAGE ACCOUNTING (V5.0.7214)", listOf(
            "  ${"A_ZERO_7214".padEnd(52)}0",
            "  ${"B_LIVE_7214".padEnd(52)}5",
            "  LIVE_FLOOR_* : none",
            "  ${"D_ZERO_7214".padEnd(52)}0",
        ))
        val out = ReportBudget8176.compact8176(text)
        assertFalse(out.contains("A_ZERO_7214"))
        assertFalse(out.contains("D_ZERO_7214"))
        assertFalse(out.contains("LIVE_FLOOR_* : none"))
        assertTrue(out.contains("B_LIVE_7214"))
        assertTrue(out.contains("(2 zero counters omitted)"))
        assertTrue(out.contains("(1 empty '* : none' row(s) omitted)"))
    }

    @Test fun labelledCountersKeepTopSixtyByValue() {
        val rows = (0 until 100).map { "  ${"LIFECYCLE/K$it:".padEnd(30)}${it + 1}" } +
            (0 until 20).map { "  ${"LIFECYCLE/Z$it:".padEnd(30)}0" }
        val out = ReportBudget8176.compact8176(section("Labelled counters (lane / error / lock / etc.)", rows))
        assertTrue(out.contains("LIFECYCLE/K99:"))
        assertTrue(out.contains("LIFECYCLE/K40:"))
        assertFalse(out.contains("LIFECYCLE/K39:"))
        assertFalse(out.contains("LIFECYCLE/Z0:"))
        assertTrue(out.contains("top 60 of 120 counters by value; 40 lower nonzero + 20 zero counters omitted"))
    }

    @Test fun duplicateSectionsAndLinesAreRemoved() {
        val repeated = "  Resident hunters (§7807): runs=5 sweeps=10 hunted=3 intake=2 candidates only ok"
        val text = listOf(
            section("Hunters one", listOf(repeated)),
            section("Scout two", listOf(repeated, "  other")),
            section("Hunters one", listOf(repeated)),
        ).joinToString("\n")
        val out = ReportBudget8176.compact8176(text)
        assertEquals(1, Regex("Resident hunters").findAll(out).count())
        assertTrue(out.contains("duplicate line(s) omitted"))
        assertTrue(out.contains("duplicate of an earlier identical section omitted 8176"))
        assertTrue(out.contains("  other"))
    }

    @Test fun listSectionsAreCapped() {
        val events = (0 until 80).map { "  12:00:00  TAG$it  SYM  " + "z".repeat(400) }
        val out = ReportBudget8176.compact8176(section("Recent events (last 80 of 150)", events))
        val kept = out.split('\n').filter { it.contains("  TAG") }
        assertEquals(25, kept.size)
        assertTrue(kept.all { it.length <= 240 })
        assertTrue(kept.first().contains("TAG0 "))
        val samples = (0 until 30).map { i -> "  12:00:00  gap=${if (i == 4) "  900" else "   40"}ms  android.os.MessageQueue.nativePollOnce" } +
            "  12:00:01  gap=   50ms  com.lifecyclebot.ui.MainActivity.updateUi"
        val s = ReportBudget8176.compact8176(section("Pre-freeze rolling main-thread sample (last 31, newest first)", samples))
        assertEquals(2, s.split('\n').count { it.contains("gap=") })
        val line = "      MOONSHOT tagged{A=1} alsoFired{-} record: S1[prior +1.0] S2[n12 +3.0% exp +2.5] " +
            "S3[n3 +9.0% exp +9.0] S4[n40 -1.0% exp -4.0] S5[prior +0.5]"
        val pb = ReportBudget8176.compactPlaybookRecord8176(line)
        assertTrue(pb.contains("record: S4[n40 -1.0% exp -4.0] S2[n12 +3.0% exp +2.5]"))
        assertFalse(pb.contains("S1["))
        assertFalse(pb.contains("S3["))
        assertTrue(pb.contains("(+3 setups omitted"))
    }

    @Test fun fullCopyRetainsAllRowsAndPartsHoldTheBudget() {
        assertEquals(180_000, ReportBudget8176.COPY_BUDGET_CHARS_8176)
        val huge = listOf(
            section("AATE Pipeline Health Snapshot", listOf("  build=5.0.8176")),
            section("Labelled counters (lane / error / lock / etc.)", (0 until 3_000).map { "  ${"LIFECYCLE/C$it:".padEnd(30)}${it + 1}" }),
            section("Recent events (last 80 of 150)", (0 until 80).map { "  e$it " + "q".repeat(500) }),
            section("Some medium section", (0 until 5_000).map { "  medium row $it " + "m".repeat(60) }),
            section("Interpretation cheat-sheet", listOf("  BOT_LOOP_TICK=5 ok")),
        ).joinToString("\n")
        assertTrue(huge.length > 250_000)
        val out = ReportBudget8176.forCopy8176(huge)
        assertEquals(huge, ReportBudget8176.expand8198(out))
        val parts = ReportBudget8176.chatParts8198(out)
        assertTrue(parts.all { it.length <= 12_000 })
        assertEquals(out, parts.joinToString("") { it.substringAfter('\n') })
    }

    @Test fun copyButtonUsesTheBudgetedText() {
        val ui = src("ui/PipelineHealthActivity.kt")
        assertTrue(ui.contains("ReportBudget8176.forCopy8176(rawText)"))
        assertTrue(ui.contains("ReportBudget8176.chatParts8198(compactText)"))
        assertFalse(ui.contains("rawText.take(60_000)"))
        // the share file still receives the full, uncapped report
        assertTrue(ui.contains(".writeReportSync(activityRef, rawText, prefix = \"aate_pipeline\")"))
        assertTrue(ui.contains("PipelineHealthCollector.pasteSafeSnapshot()"))
    }
}
