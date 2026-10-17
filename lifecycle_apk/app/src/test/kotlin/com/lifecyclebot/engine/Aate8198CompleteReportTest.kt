package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8198CompleteReportTest {
    private fun restore(text: String): String = ReportBudget8176.expand8198(text)
    private fun join(parts: List<String>): String = if (parts.size == 1) parts.first() else parts.joinToString("") { it.substringAfter('\n') }

    @Test fun repeated_telemetry_compacts_without_losing_modes_numbers_or_any_rows() {
        val raw = buildString {
            appendLine("===== MONEY PATH =====")
            repeat(1200) { i -> appendLine("ts=$i mode=${if (i % 2 == 0) "LIVE" else "PAPER"} lane=MOONSHOT mint=CanonicalMintABCDEFG123456789 cost=0.000000123 pnl=-12.3456789 SOURCE_ROUTE_FINALITY_PROOF_RECEIVED_8198=0 source=SOURCE_ROUTE_FINALITY_PROOF_RECEIVED_8198") }
        }
        val compact = ReportBudget8176.forCopy8176(raw)
        assertEquals(raw, restore(compact))
        assertTrue(compact.length < raw.length * 0.80)
        println("REPORT_FIXTURE rawChars=${raw.length} compactChars=${compact.length}")
    }

    @Test fun duplicate_rows_retain_their_order_and_multiplicity() {
        val repeated = "  mode=LIVE lane=MOONSHOT reason=" + "receipt_pending_".repeat(15)
        val raw = repeated + "\nunique a=0\n" + repeated + "\nunique a=1\n" + repeated + "\n"
        assertEquals(raw, restore(ReportBudget8176.forCopy8176(raw)))
    }

    @Test fun all_zero_counters_and_low_sample_setups_survive_huge_reports() {
        val raw = buildString { repeat(9000) { appendLine("counter_$it=0 setup=UNSEEN_$it n=0 mean=-0.000001 version=8198") } }
        val compact = ReportBudget8176.forCopy8176(raw)
        val parts = ReportBudget8176.chatParts8198(compact)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 12_000 })
        assertEquals(raw, restore(join(parts)))
    }

    @Test fun enormous_single_line_is_continued_not_dropped() {
        val raw = "reason=" + (0..14000).joinToString("_") + " tail=FINAL_EVIDENCE"
        val parts = ReportBudget8176.chatParts8198(raw)
        assertTrue(parts.all { it.length <= 12_000 })
        assertEquals(raw, join(parts))
    }

    @Test fun unicode_surrogates_and_crlf_survive_part_boundaries() {
        val raw = ("x".repeat(95) + "🚀\r\nLIVE=0 PAPER=1\t").repeat(100)
        val parts = ReportBudget8176.chatParts8198(raw, 256)
        assertTrue(parts.all { it.length <= 256 && !it.last().isHighSurrogate() })
        assertEquals(raw, join(parts))
    }

    @Test fun marker_like_source_text_cannot_be_confused_with_encoded_rows() {
        val raw = "~\n~~\n~AATE:\n~K0;\n~R0=not-a-reference\n" + "FULL_REAL_TOKEN_IDENTITY_AND_FINALITY=0\n".repeat(400)
        assertEquals(raw, restore(ReportBudget8176.forCopy8176(raw)))
        assertEquals("", ReportBudget8176.forCopy8176(""))
        assertEquals("small n=0", ReportBudget8176.forCopy8176("small n=0"))
    }

    @Test fun varied_unicode_and_literal_content_round_trips_exactly() {
        val random = java.util.Random(8198)
        repeat(70) {
            val raw = buildString { repeat(150) { append(listOf("零", "\n", "\r\n", "~K0;", "  ", "FULL_CANONICAL_POSITION_FINALITY_8198", "-0.00001", "🚀", "\t")[random.nextInt(9)]) } }
            assertEquals(raw, restore(ReportBudget8176.forCopy8176(raw)))
        }
    }

    @Test fun both_copy_paths_keep_files_parts_and_true_cache_timestamps() {
        val root = File("src/main/kotlin/com/lifecyclebot")
        val pipeline = File(root, "ui/PipelineHealthActivity.kt").readText()
        val errors = File(root, "ui/ErrorLogActivity.kt").readText()
        val hub = File(root, "engine/ReportingHub.kt").readText()
        for (ui in listOf(pipeline, errors)) {
            assertTrue(ui.contains("chatParts8198(compactText)"))
            assertTrue(ui.contains("ReportCopyDialog8198.show("))
            assertFalse(ui.contains("clipboardSafeText("))
            assertFalse(ui.contains("Intent.EXTRA_TEXT"))
        }
        assertFalse(pipeline.contains("rawText.take(60_000)"))
        assertFalse(hub.contains("REPORT_TRUNCATED_UNEXPECTED"))
        assertTrue(hub.contains("CacheEntry(sourceAtMs, report)"))
        assertTrue(hub.contains("STALE_REPORT_CACHE"))
    }
}
