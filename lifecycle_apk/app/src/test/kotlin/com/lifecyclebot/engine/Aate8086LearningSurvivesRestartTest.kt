package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8086 — Cortex and learning reset across restarts.
 *
 * Root cause family: "unreadable or not-yet-restored is treated as empty, and the empty state is then
 * saved over the real one". Oversized rows failed the 2 MB CursorWindow read, saves ran before the boot
 * restore finished, a second init() re-imported over the session, file learners latched before the
 * context existed, file writes truncated in place, and the backup never carried the learning store.
 */
class Aate8086LearningSurvivesRestartTest {

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val lp = src("engine/LearningPersistence.kt")

    @Test
    fun split_rows_are_bounded_lossless_and_never_split_a_surrogate_pair() {
        val emoji = "\uD83D\uDE80"
        val big = buildString { repeat(70_000) { append("ab").append(emoji) } }   // 280k UTF-16 units
        val parts = LearningPersistence.splitRow8086(big, 1001)
        assertEquals(big, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1001 })
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) })
        assertTrue(parts.none { Character.isLowSurrogate(it.first()) })
        assertEquals(listOf("abc"), LearningPersistence.splitRow8086("abc", 1001))
    }

    @Test
    fun oversized_rows_are_written_in_parts_and_read_in_slices() {
        assertTrue(lp.contains("private const val ROW_CHARS_8086 = 256_000"))
        assertTrue(lp.contains("SELECT substr(payload, ?, ?) FROM kv WHERE tracker = ? AND bucket = ?"))
        assertTrue(lp.contains("arrayOf(name, if (parts == 0) json else \"\$PART_PREFIX_8086\$parts\", now)"))
        assertTrue(lp.contains("for ((name, json) in blobs) writeRow8086(d, name, json, now)"))
        assertFalse("delete-then-insert outside a transaction lost the row on a kill",
            lp.contains("d.execSQL(\"DELETE FROM kv WHERE tracker = 'BLOB' AND bucket = ?\", arrayOf(name))"))
    }

    @Test
    fun unreadable_is_not_absent_and_is_never_overwritten() {
        val get = lp.substringAfter("private fun getBlob(name: String): String? {").substringBefore("\n    }\n")
        assertTrue(get.contains("unreadable8086.add(name)"))
        assertTrue(get.contains("LEARNING_READ_FAILED_HELD_8086"))
        val put = lp.substringAfter("private fun putBlob(name: String, json: String) {").substringBefore("\n    }\n")
        assertTrue(put.contains("if (writeRefused8086(name)) return"))
        assertTrue(lp.contains("if (blobs.keys.any { writeRefused8086(it) }) return"))
        assertTrue(lp.contains("LEARNING_DECODE_FAILED_QUARANTINED_8086"))
        for (f in listOf("engine/cortex/Cortex7885.kt", "engine/cortex/CortexExit7897.kt", "engine/cortex/CortexTiming7900.kt", "engine/cortex/LanePlaybook7907.kt")) {
            val body = src(f).substringAfter("private fun ensureLoaded() {").substringBefore("\n    }\n")
            assertTrue("$f decode failure must quarantine, not vanish", body.contains("LearningPersistence.decodeFailed8086("))
        }
    }

    @Test
    fun no_save_before_the_boot_restore_and_init_is_idempotent() {
        val save = lp.substringAfter("private fun saveAllBlockingInternal() {").substringBefore("d.beginTransaction()")
        assertTrue(save.contains("if (!restoreComplete8086 || importAwaitingRestart8086) {"))
        assertTrue(save.contains("LEARNING_SAVE_SKIPPED_BEFORE_RESTORE_8086"))
        val init = lp.substringAfter("fun init(ctx: Context) {").substringBefore("GlobalScope.launch")
        assertTrue(init.contains("if (db != null) return"))
        assertTrue(lp.contains("} finally {\n                // Every restore step is individually guarded"))
        assertTrue(lp.contains("try { ScoreExpectancyTracker.importState(loadTracker(d, \"SCORE\")) } catch"))
    }

    @Test
    fun file_learners_wait_for_a_store_and_replace_atomically() {
        for (f in listOf("engine/cortex/StopLearner8040.kt", "engine/RunnerPlay8018.kt", "engine/TailBag8034.kt", "engine/truth/HiveEdge8000.kt", "engine/truth/TailHunter7996.kt", "engine/LifecycleHold8065.kt")) {
            val s = src(f)
            assertTrue("$f latches before context", s.contains("if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return   // V5.0.8086"))
            assertFalse("$f truncates in place", s.contains("file()?.writeText("))
        }
        assertTrue(lp.contains("fun java.io.File.writeTextAtomic8086(text: String) {"))
    }

    @Test
    fun reinstall_carries_the_learning_store_and_only_the_learning_store() {
        val pl = src("engine/PersistentLearning.kt")
        assertTrue(pl.contains("LearningPersistence.exportRows8086()?.let { rows ->"))
        assertTrue(pl.contains("val n = LearningPersistence.importRows8086(rows)"))
        assertTrue(pl.contains("\"learning_store_8086\","))
        assertTrue(lp.contains("importAwaitingRestart8086 = true"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/learning_data_extraction_8086\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/learning_backup_rules_8086\""))
        for (x in listOf("learning_backup_rules_8086.xml", "learning_data_extraction_8086.xml")) {
            val r = File("src/main/res/xml/$x").readText()
            val includes = Regex("<include [^>]*/>").findAll(r).map { it.value }.toList()
            assertTrue(includes.isNotEmpty())
            assertTrue("$x must back up the learning store only", includes.all { it.contains("domain=\"database\" path=\"learning_kv.db\"") })
            assertFalse(r.contains("sharedpref"))
        }
    }
}
