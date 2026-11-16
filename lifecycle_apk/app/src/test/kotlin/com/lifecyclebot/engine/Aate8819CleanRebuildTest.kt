package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CleanRebuild8819
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LearningEpoch8164
import com.lifecyclebot.engine.truth.LearningResetSweep7781
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** V5.0.8819 — the lot is remade from data recorded after the Helius plan was restored; the old tables are archived. */
class Aate8819CleanRebuildTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @After fun tearDown() { LearningEpoch8164.setForTest8164(0L) }

    @Test fun theCleanLineIsAfterTheHeliusRestore() {
        assertEquals(java.time.Instant.parse("2026-11-16T08:30:00Z").toEpochMilli(), CleanRebuild8819.CLEAN_FROM_MS_8819)
    }

    @Test fun theDatasetReplayReadsOnlyRowsFromTheLearningLine() {
        fun row(t: Long?) = JSONObject().put("mint", "Abcdefghijkmnpqrstuvwxyz123456789pump").put("lane", "MOONSHOT")
            .put("fill", true).put("horizonMs", 900_000).put("mcap", 200_000.0).put("net", -30.0).also { if (t != null) it.put("t", t) }
        val line = CleanRebuild8819.CLEAN_FROM_MS_8819
        LearningEpoch8164.setForTest8164(0L)
        assertTrue("no line: every row as before", EdgeModel8537.replayAccept8642(row(line - 86_400_000L)))
        LearningEpoch8164.setForTest8164(line)
        assertFalse("recorded before the line", EdgeModel8537.replayAccept8642(row(line - 1)))
        assertTrue(EdgeModel8537.replayAccept8642(row(line)))
        assertTrue(EdgeModel8537.replayAccept8642(row(line + 60_000L)))
        assertTrue("an undated row is not judged by time", EdgeModel8537.replayAccept8642(row(null)))
    }

    @Test fun everyLearningStoreTheResetClearsIsFoundForTheArchive() {
        val root = Files.createTempDirectory("aate8819").toFile()
        try {
            val prefs = File(root, "shared_prefs").apply { mkdirs() }
            val files = File(root, "files").apply { mkdirs() }
            val dbs = File(root, "databases").apply { mkdirs() }
            File(prefs, "token_win_memory.xml").writeText("<map/>")
            File(prefs, "wallet_keys.xml").writeText("<map/>")              // not learning: never archived or wiped
            File(dbs, "learning_kv.db").writeText("db"); File(dbs, "learning_kv.db-wal").writeText("wal")
            File(files, "edge_model_8722.bin").writeText("m"); File(files, "colour_shape_8816.json").writeText("{}")
            val found = CleanRebuild8819.storeFiles8819(root, files) { File(dbs, it) }.map { it.name }.toSet()
            assertEquals(setOf("token_win_memory.xml", "learning_kv.db", "learning_kv.db-wal", "edge_model_8722.bin", "colour_shape_8816.json"), found)
        } finally { root.deleteRecursively() }
        assertTrue(LearningResetSweep7781.LEARNING_FILES_7975.containsAll(listOf("edge_model_8722.bin", "colour_shape_8816.json")))
    }

    @Test fun theRebuildArmsBeforeAnyLearnerLoadsAndNeverTouchesEvidence() {
        val boot = src("engine/truth/LearningResetSweep7781.kt").substringAfter("fun applyPendingAtBoot(ctx: Context) {")
        val arm = boot.indexOf("CleanRebuild8819.armOnce8819(app)")
        assertTrue(arm in 0 until boot.indexOf("LearningEpoch8164.load8164(app)"))
        val cr = src("engine/truth/CleanRebuild8819.kt")
        assertTrue(cr.contains("src.copyTo(target, overwrite = true)") && !cr.contains(".delete()"))
        assertTrue(cr.indexOf("archive8819(app)") in 0 until cr.indexOf("LearningResetSweep7781.armAtBoot8819(app)"))
        assertTrue(src("engine/truth/EdgeModel8537.kt").contains("if (LearningEpoch8164.preEpoch8164(row.optLong(\"t\", 0L))) return false"))
        assertTrue(src("engine/truth/LearningResetSweep7781.kt").contains("CleanRebuild8819.statusLine8819()"))
        assertEquals(emptyList<String>(), com.lifecyclebot.engine.truth.ReconcileEvidenceStores8144.violations8144(
            LearningResetSweep7781.LEARNING_PREFS_7781, LearningResetSweep7781.LEARNING_DBS_7975, LearningResetSweep7781.LEARNING_FILES_7975))
    }
}
