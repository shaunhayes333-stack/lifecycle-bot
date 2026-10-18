package com.lifecyclebot.engine

import com.lifecyclebot.ui.ReportCopyDialog8198
import org.junit.Assert.*
import org.junit.Test

/** 8213 — the share chooser must fit one binder transaction (1 MB), or Android drops it silently. */
class Aate8213ShareBinderSafeTest {
    @Test fun shareTextIsBinderSafeAndNamesTruncation() {
        val big = "x".repeat(2_000_000)
        val t = ReportCopyDialog8198.binderSafeText8213(big)
        assertTrue(t.length < 61_000); assertTrue(t.contains("2000000 chars"))
        assertEquals("short", ReportCopyDialog8198.binderSafeText8213("short"))
        assertTrue("UTF-16 parcel of the file-share body stays well under 1 MB",
            com.lifecyclebot.engine.execution.PipelineReportFileExporter6401.BINDER_SAFE_MAX_CHARS * 2 * 2 < 1_000_000)
    }
}
