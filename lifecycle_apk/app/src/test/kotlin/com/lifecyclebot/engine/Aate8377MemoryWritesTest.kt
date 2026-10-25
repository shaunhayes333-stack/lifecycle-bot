package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8377 — 8376 OOM: whole-file preference rewrites per ticket / per book key and a full Cortex snapshot per shadow tick. */
class Aate8377MemoryWritesTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun cortexShadowCheckpointCoalesces() {
        assertFalse("nothing ready, nothing due", Cortex7885.shadowFlushDue8377(0, 60_000L))
        assertFalse("a trickle waits", Cortex7885.shadowFlushDue8377(3, 2_000L))
        assertTrue("a full batch flushes now", Cortex7885.shadowFlushDue8377(Cortex7885.SHADOW_FLUSH_MIN_BATCH_8377, 0L))
        assertTrue("a trickle flushes after the wait", Cortex7885.shadowFlushDue8377(1, Cortex7885.SHADOW_FLUSH_MAX_WAIT_MS_8377))
    }

    @Test fun exploreBooksAndTicketsWriteOncePerSlice() {
        val s = src("engine/truth/ExploreShadow8347.kt")
        val book = s.substringAfter("private fun bookKey8351(").substringBefore("const val COHORT_SE_MULT_8351")
        assertFalse("a book close never writes the file itself", book.contains("prefs?.edit()"))
        assertTrue(book.contains("dirtyBooks8377.add(key)"))
        val tick = s.substringAfter("private fun tickSingle8372(").substringBefore("private fun deliverCortex8372(")
        assertEquals("one editor per slice", 1, Regex("openPrefs8355\\?\\.edit\\(\\)").findAll(tick).count())
        assertTrue(tick.contains("flushBooks8377(nowMs)"))
        assertTrue(s.contains("\${persistStatus8377()}"))
    }
}
