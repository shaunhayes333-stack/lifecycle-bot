package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8433 — the report names its own defects: in-app frames, grouped exceptions, failure counters, a bounded LLM read. */
class Aate8433DefectSweepTest {
    private val stack = """
        java.lang.IllegalStateException: boom 12345
            at java.util.ArrayList.get(ArrayList.java:437)
            at com.lifecyclebot.engine.ErrorLogger.log(ErrorLogger.kt:104)
            at com.lifecyclebot.engine.truth.CandidateAdmission8136${'$'}decide8136${'$'}1.invoke(CandidateAdmission8136.kt:515)
            at com.lifecyclebot.engine.BotService.loop(BotService.kt:1428)
    """.trimIndent()

    @Test fun theFirstInAppFrameNamesTheCodeLocation() {
        assertEquals("CandidateAdmission8136.invoke:515", DefectSweep8433.frame8433(stack))
        assertEquals("-", DefectSweep8433.frame8433(null))
        assertEquals("-", DefectSweep8433.frame8433("at java.lang.Thread.run(Thread.java:1)"))
        assertEquals("Executor.requestSell:23425", DefectSweep8433.frame8433("at com.lifecyclebot.engine.Executor.requestSell(Executor.kt:23425)"))
    }

    @Test fun repeatsOfOneDefectGroupTogetherAndSortLargestFirst() {
        val mint = "B6hVwvn5toMkHwXYxB9QqgdAkdhcp3NMUaYDob12pump"
        val entries = listOf(
            Triple("Executor", "sell failed for $mint after 3 attempts", stack),
            Triple("Executor", "sell failed for ${mint.reversed()} after 7 attempts", stack),
            Triple("Labeler", "mark lost for $mint at 12:00", null),
            Triple("Labeler", "mark lost for ${mint.reversed()} at 12:05", null),
            Triple("Labeler", "mark lost for ${mint.reversed()} at 12:09", null),
        )
        val d = DefectSweep8433.fromErrors8433(entries)
        assertEquals(2, d.size)
        assertEquals("Labeler:mark lost for <mint> at #:#", d[0].where); assertEquals(3L, d[0].count)
        assertEquals("CandidateAdmission8136.invoke:515", d[1].where); assertEquals(2L, d[1].count)
        assertEquals("sell failed for <mint> after # attempts", DefectSweep8433.signature8433("sell failed for $mint after 3 attempts"))
    }

    @Test fun onlyFailureShapedCountersAreDefectsAndTheTableIsBounded() {
        val c = DefectSweep8433.fromCounters8433(mapOf(
            "FORWARD_TERMINAL_DURABILITY_WAIT_8395" to 53_714L, "SHADOW_CHECKPOINT_WORKER_RETRY_8403" to 3L,
            "CANDIDATE_ADMISSION_8136_LIVE_ADMIT" to 61L, "EVIDENCE_SHADOW_TELEMETRY_FAILED_8392" to 9L,
            "MARK_EXECUTABLE_QUOTE_NO_ROUTE_7301" to 108L, "ZERO_COUNTER_FAILED" to 0L))
        assertEquals(listOf("EVIDENCE_SHADOW_TELEMETRY_FAILED_8392", "SHADOW_CHECKPOINT_WORKER_RETRY_8403"), c.map { it.where })
        val table = DefectSweep8433.render8433(DefectSweep8433.fromErrors8433(List(30) { Triple("C$it", "m$it", null) }), c)
        assertEquals(12, table.lines().size)
        // 8435: a stackless error row is an ERROR_LOG (EXCEPTION needs an in-app frame), and the counters share the rows
        assertTrue(table.startsWith("#1 ERROR_LOG where="))
        assertTrue(table.contains("COUNTER where=EVIDENCE_SHADOW_TELEMETRY_FAILED_8392"))
        assertEquals("EXCEPTION", DefectSweep8433.kind8435(true, "boom"))
        assertEquals("SAFETY_DECISION", DefectSweep8433.kind8435(false, "LP hard block: top holder 62%"))
        assertEquals("ERROR_LOG", DefectSweep8433.kind8435(false, "mark lost for <mint>"))
        // a safety decision is left out of the table entirely
        assertEquals("none", DefectSweep8433.render8433(DefectSweep8433.fromErrors8433(listOf(Triple("Guard", "HARD_BLOCK freeze authority", null))), emptyList()))
        assertEquals("none", DefectSweep8433.render8433(emptyList(), emptyList()))
        val prompt = DefectSweep8433.prompt8433("x".repeat(10_000), "funnel")
        assertTrue(prompt.length <= 6_000)
        assertTrue(DefectSweep8433.SYSTEM_8433.contains("never invent"))
    }

    @Test fun wiring() {
        DefectSweep8433.resetForTest8433()
        // with no error store and no counters on the JVM the section is the empty table, no LLM scheduled
        val section = DefectSweep8433.section8433(1_800_000_000_000L)
        assertTrue(section, section.startsWith("exceptions=0 groups=0 errorLogs=0 safetyDecisionsOmitted=0 failureCounters="))  // 8435
        val phc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("sb.append(\"  \").append(DefectSweep8433.SECTION_8433).append(\": \").append("))
        val ds = File("src/main/kotlin/com/lifecyclebot/engine/DefectSweep8433.kt").readText()
        assertTrue("the LLM read is scheduled on its own daemon thread, never on the report thread", ds.contains("Thread({") && ds.contains("\"defect-sweep-8433\""))
        assertTrue(ds.contains("KeylessLlmClient.runChat(SYSTEM_8433, prompt, 320)"))
        assertFalse("advisory only: nothing in the sweep touches a decision", ds.contains("FinalDecisionGate") || ds.contains("Executor.") || ds.contains("requestSell"))
    }
}
