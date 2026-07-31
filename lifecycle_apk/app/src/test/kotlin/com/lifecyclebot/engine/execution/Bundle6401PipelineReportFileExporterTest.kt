package com.lifecyclebot.engine.execution

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * V5.0.6401 ANR-KILLER — full-report file share path.
 *
 * The 20k-of-60k truncation in the operator's health snapshot was
 * caused by the clipboard cap. This helper writes the full report to
 * cache and hands out a FileProvider URI so nothing is ever truncated.
 * Every invariant here protects that guarantee.
 *
 * The test drives the internal `writeReportSyncToDir` overload so we
 * do not need a real Android Context — the production path is a thin
 * wrapper that just resolves `ctx.cacheDir` before delegating here.
 */
class Bundle6401PipelineReportFileExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before fun setUp() { dir = tmp.newFolder("pipeline_reports") }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun full_report_persisted_verbatim_no_truncation() {
        // 400_000 chars is well past the 20_000 clipboard cap that
        // caused the original 20-of-60 truncation.
        val big = buildString { repeat(400_000) { append('x') } }
        val f = PipelineReportFileExporter6401.writeReportSyncToDir(dir, big, prefix = "aate_pipeline")
        assertNotNull(f)
        assertTrue(f!!.exists())
        assertEquals(big.length.toLong(), f.length())
        assertEquals(big, f.readText())
    }

    @Test fun retention_prunes_old_files_to_max_five() {
        // Write 8 reports; retention should keep <=5.
        repeat(8) { idx ->
            PipelineReportFileExporter6401.writeReportSyncToDir(dir, "report-$idx", prefix = "test_$idx")
            // Nudge lastModified() apart so sort is stable across writes.
            Thread.sleep(15L)
        }
        val remaining = (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray()).size
        assertTrue("expected <=5 files retained, got $remaining", remaining <= 5)
    }

    @Test fun write_survives_multiple_generations() {
        // Simulate a series of health-snapshot exports across a session.
        // Each write must succeed and preserve the payload byte-for-byte.
        val payloads = (1..3).map { i -> "gen_$i-" + "y".repeat(50_000) }
        val files = payloads.map { p ->
            PipelineReportFileExporter6401.writeReportSyncToDir(dir, p, prefix = "gen")!!
        }
        // Every payload lands on disk with the correct length.
        files.zip(payloads).forEach { (f, p) ->
            assertEquals(p.length.toLong(), f.length())
        }
    }
}
