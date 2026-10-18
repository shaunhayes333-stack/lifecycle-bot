package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 8198 — the report export contract. The COPY is the readable budgeted report (plain text, one clipboard payload,
 * section-structured). The COMPLETE uncapped report is always the share file, and an export can never dead-end.
 */
class Aate8198CompleteReportTest {
    private fun section(title: String, rows: List<String>) = "===== $title =====\n" + rows.joinToString("\n") + "\n"
    private val root = File("src/main/kotlin/com/lifecyclebot")

    @Test fun copyIsReadablePlainTextWithNoEncodingMarkers() {
        val raw = buildString {
            append(section("AATE Pipeline Health Snapshot", listOf("  build=5.0.8198")))
            append(section("Money path", (0 until 300).map { "  mode=${if (it % 2 == 0) "LIVE" else "PAPER"} lane=MOONSHOT pnl=-1.$it mint=CanonicalMintABCDEFG123456789" }))
            append(section("Interpretation cheat-sheet", listOf("  BOT_LOOP_TICK=5 ok")))
        }
        val copy = ReportBudget8176.forCopy8176(raw)
        assertFalse(copy.contains("AATE COMPACT DIAGNOSTIC"))
        assertFalse(copy.contains("sourceSha256"))
        assertFalse(Regex("~K\\d+;|~R\\d+[=;]").containsMatchIn(copy))
        assertTrue(copy.contains("===== AATE Pipeline Health Snapshot ====="))
        assertTrue(copy.contains("mode=LIVE lane=MOONSHOT"))
        assertTrue(copy.contains("mode=PAPER lane=MOONSHOT"))
    }

    @Test fun hugeReportFitsOneClipboardPayloadAndProtectedSectionsSurvive() {
        val raw = listOf(
            section("AATE Pipeline Health Snapshot", listOf("  build=5.0.8198")),
            section("Labelled counters (lane / error / lock / etc.)", (0 until 4_000).map { "  ${"LIFECYCLE/C$it:".padEnd(30)}${it + 1}" }),
            section("Some medium section", (0 until 6_000).map { "  medium row $it " + "m".repeat(60) }),
            section("Interpretation cheat-sheet", listOf("  BOT_LOOP_TICK=5 ok")),
        ).joinToString("\n")
        assertTrue(raw.length > ReportBudget8176.COPY_BUDGET_CHARS_8176)
        val copy = ReportBudget8176.forCopy8176(raw)
        assertTrue(copy.length <= ReportBudget8176.COPY_BUDGET_CHARS_8176)
        assertTrue(copy.length <= ReportingHub.CLIPBOARD_SAFE_MAX_CHARS)
        assertTrue(copy.contains("===== AATE Pipeline Health Snapshot ====="))
        assertTrue(copy.contains("===== Interpretation cheat-sheet ====="))
    }

    @Test fun smallReportIsCopiedWholeAndNoteSaysSo() {
        val raw = section("AATE Pipeline Health Snapshot", listOf("  build=5.0.8198", "  live=0"))
        assertEquals(raw.trimEnd(), ReportBudget8176.forCopy8176(raw).trimEnd())
        assertEquals("Copied 10 chars", ReportBudget8176.fullShareNote8198(10, 10))
        assertTrue(ReportBudget8176.fullShareNote8198(10, 500).contains("Tap Share for the complete file"))
    }

    @Test fun bothExportScreensCopyReadableTextAndOfferTheCompleteFile() {
        val pipeline = File(root, "ui/PipelineHealthActivity.kt").readText()
        val errors = File(root, "ui/ErrorLogActivity.kt").readText()
        for (ui in listOf(pipeline, errors)) {
            assertTrue(ui.contains("ReportBudget8176.forCopy8176("))
            assertTrue(ui.contains("clipboardSafeText("))
            assertTrue(ui.contains("ReportCopyDialog8198.show("))
            assertTrue(ui.contains("fullShareNote8198("))
            assertFalse(ui.contains("chatParts8198"))
            assertFalse(ui.contains("compactReportFile"))
        }
        // the complete uncapped report is always persisted for sharing
        assertTrue(pipeline.contains(".writeReportSync(activityRef, rawText, prefix = \"aate_pipeline\")"))
        assertTrue(errors.contains(".writeReportSync(this, fullText, prefix = \"aate_unified\")"))
    }

    @Test fun shareCanNeverDeadEnd() {
        val dialog = File(root, "ui/ReportCopyDialog8198.kt").readText()
        assertTrue(dialog.contains("PipelineReportFileExporter6401.shareIntent("))
        assertTrue(dialog.contains("Intent.EXTRA_TEXT"))          // plain-text fallback when the file/intent is unavailable
        assertTrue(dialog.contains("fileIntent ?: textIntent"))
        assertTrue(dialog.contains("Share unavailable on this device"))
    }

    @Test fun trueCacheTimestampsAndFullErrorSectionsStayInTheCompleteReport() {
        val hub = File(root, "engine/ReportingHub.kt").readText()
        assertTrue(hub.contains("CacheEntry(sourceAtMs, report)"))
        assertTrue(hub.contains("STALE_REPORT_CACHE"))
        assertFalse(hub.contains("REPORT_TRUNCATED_UNEXPECTED"))
    }
}
