package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8982 — the run maps its own heap once when a hard trim frees nothing at 85 %+ after 30 minutes, and every map is
 * read in the lab process; an automatic map the lab cannot read is deleted unread, never read in the trading heap.
 */
class Aate8982AutoHeapMapTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theAutomaticMapIsDueOnceOnAFullHeapLateInARun() {
        val h = HeapMap8866
        assertTrue(h.autoDue8982(31L * 60_000L, 0.91, takenThisRun = false))
        assertFalse("not in the first 30 minutes (start-up restores)", h.autoDue8982(10L * 60_000L, 0.99, false))
        assertFalse("not below 85 %", h.autoDue8982(60L * 60_000L, 0.80, false))
        assertFalse("once a run", h.autoDue8982(60L * 60_000L, 0.95, true))
        assertFalse(h.autoDue8982(60L * 60_000L, Double.NaN, false))
    }

    @Test fun theLabsStatsLineRoundTrips() {
        val r = HeapMapCore8866.Result(true, "map text", 6_918_044L, 320L shl 20, 33_749, 13_120, "")
        val back = LabProcess8906.readStats8982(LabProcess8906.statsLine8982(r), "map text")!!
        assertEquals(r.complete, back.complete); assertEquals(r.objects, back.objects); assertEquals(r.bytes, back.bytes)
        assertEquals(r.classes, back.classes); assertEquals(r.samples, back.samples); assertEquals("map text", back.text)
        val stopped = HeapMapCore8866.Result(false, "t", 1L, 2L, 3, 4, "budget|hit\nnow")
        assertEquals("budget/hit now", LabProcess8906.readStats8982(LabProcess8906.statsLine8982(stopped), "t")!!.stopped)
        assertNull(LabProcess8906.readStats8982("1|2|3", "t"))
    }

    @Test fun wired() {
        assertTrue(src("engine/MemoryGuard7977.kt").contains("try { HeapMap8866.maybeAuto8982(fracAfter) } catch (_: Throwable) {}"))
        val map = src("engine/HeapMap8866.kt")
        assertTrue(map.contains("var result = try { LabProcess8906.heapMap8982(app, file, header) } catch (_: Throwable) { null }"))
        assertTrue("only the owner's map falls back to the trading heap", map.contains("if (result == null && !auto8982) {"))
        assertTrue("the dump is still deleted whatever happened", map.contains("try { raw?.delete() } catch (_: Throwable) {}"))
        assertTrue(map.contains("+ \"  \" + autoLine8982() + \"\\n\""))
        val lab = src("engine/LabProcess8906.kt")
        assertTrue(lab.contains("KIND_HEAP_MAP_8982 -> heapMapJob8982(ctx, inPath, token)"))
        assertTrue("the lab deletes the dump it read", lab.contains("} finally { try { raw.delete() } catch (_: Throwable) {} }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 167. The heap maps itself before it runs out (8982)"))
    }
}
