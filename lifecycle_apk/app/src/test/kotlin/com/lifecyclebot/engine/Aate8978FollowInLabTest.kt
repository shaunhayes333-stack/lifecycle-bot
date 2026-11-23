package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * V5.0.8978 — the research follow-up runs in the lab process: the waits go over as whole files, the finished rows come back
 * as whole files read once, the trading process follows itself only if the lab never reports, and without an app (these
 * tests) it follows here exactly as before.
 */
class Aate8978FollowInLabTest {
    private val f = ResearchFollow8884

    @Test fun aChannelFileIsWholeOrNotThereAndIsReadOnceInOrder() {
        val dir = Files.createTempDirectory("lab8978").toFile()
        try {
            assertTrue(f.writeAtomic8978(dir, f.OUT_PREFIX_8978, listOf("b1", "b2"), 1_000L, 2L))
            assertTrue(f.writeAtomic8978(dir, f.OUT_PREFIX_8978, listOf("a1"), 1_000L, 1L))
            assertTrue(f.writeAtomic8978(dir, f.OUT_PREFIX_8978, listOf("c1"), 2_000L, 10L))
            assertTrue(f.writeAtomic8978(dir, f.IN_PREFIX_8978, listOf("w1"), 1_000L, 3L))
            File(dir, f.OUT_PREFIX_8978 + "999_000000001" + f.ROWS_SUFFIX_8978 + ".tmp").writeText("half\n")   // a file still being written
            assertEquals(listOf("a1", "b1", "b2", "c1"), f.takeFiles8978(dir, f.OUT_PREFIX_8978))
            assertEquals(emptyList<String>(), f.takeFiles8978(dir, f.OUT_PREFIX_8978))   // filed once
            assertEquals(listOf("w1"), f.takeFiles8978(dir, f.IN_PREFIX_8978))
            assertTrue("the unfinished file is left alone", dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun theTradingProcessTakesTheFollowBackOnlyAfterTheLabIsSilent() {
        val min = 60_000L
        assertFalse(f.fellSilent8978(9 * min, 0L, 0L))
        assertTrue(f.fellSilent8978(11 * min, 0L, 0L))
        assertFalse("a report resets the clock", f.fellSilent8978(11 * min, 0L, 5 * min))
        assertFalse("an old report from a previous run does not count against a fresh ask", f.fellSilent8978(11 * min, 5 * min, 1L))
        assertEquals(10 * min, f.FALLBACK_MS_8978)
    }

    @Test fun withoutTheAppTheFollowStaysHere() {
        f.resetForTest8884()
        f.note8884(1_000L, "So11111111111111111111111111111111111111112", "CORE", true, "S", "SETUP", 1.0, 0.5, 50_000.0, 20_000.0, 3.0, 1.0, "R")
        assertEquals(1, f.pendingForTest8884().size)
        assertTrue(f.statusLine8884().contains("followLab8978[where=here "))
        f.resetForTest8884()
    }

    @Test fun wired() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchFollow8884.kt").readText()
        assertTrue(src.contains("if (labMode8978) { buffer8978(encode8884(w)); return }"))
        assertTrue(src.contains("if (!labRole8978) { ResearchRows8877.note8877(line, nowMs); return }"))
        assertTrue(src.contains("if (labRole8978 && traderOwns8978()) return"))
        assertTrue(src.contains("try { ResearchRows8877.note8877(line, nowMs); rowsBack8978.incrementAndGet() }"))
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/LabProcess8906.kt").readText()
        assertTrue(lab.contains("if (kind == KIND_FOLLOW_8978) {"))
        assertTrue(lab.contains("if (!com.lifecyclebot.engine.truth.ResearchFollow8884.labFollowing8978()) stopSelf(startId)"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:process=\":lab8906\""))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 163. The research follow-up runs in the lab (8978)"))
    }
}
