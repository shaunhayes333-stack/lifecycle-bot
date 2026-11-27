package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DatasetSeedCheckpoint9043
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class Aate9043SeedCheckpointStorageTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("invalid checkpoint must be rejected") } catch (_: IllegalArgumentException) {}
    }

    @Test fun restoreOnlyTheExactDatasetPrefixThenApplyTheTailOnce() {
        val dir = Files.createTempDirectory("aate9043-seed").toFile()
        try {
            val data = File(dir, "labels.jsonl").apply { writeText("one\ntwo\n") }
            val cache = File(dir, "seed.bin")
            val cursor = DatasetSeedCheckpoint9043.Cursor(data.length(), 2, 0, 2, 2)
            DatasetSeedCheckpoint9043.save(cache, data, "epoch-A", cursor) { it.writeLong(7) }
            data.appendText("three\n")
            var state = 0L
            val restored = DatasetSeedCheckpoint9043.restore(cache, data, "epoch-A") { state = it.readLong() }!!
            assertEquals(7L, state); assertEquals(cursor, restored)
            val tail = RandomAccessFile(data, "r").use { it.seek(restored.offset); it.readLine() }
            assertEquals("three", tail)
            var called = false
            rejects { DatasetSeedCheckpoint9043.restore(cache, data, "epoch-B") { called = true } }
            assertFalse("incompatible epoch must not mutate ledgers", called)
            data.writeText("ONE\ntwo\nthree\n")
            rejects { DatasetSeedCheckpoint9043.restore(cache, data, "epoch-A") { called = true } }
            assertFalse("same-length prefix replacement must not reuse evidence", called)
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedSavePreservesLastCompleteGenerationAndCorruptionDoesNotApply() {
        val dir = Files.createTempDirectory("aate9043-atomic").toFile()
        try {
            val data = File(dir, "data").apply { writeText("row\n") }; val cache = File(dir, "seed")
            val cursor = DatasetSeedCheckpoint9043.Cursor(data.length(), 1, 0, 1, 1)
            DatasetSeedCheckpoint9043.save(cache, data, "policy", cursor) { it.writeInt(42) }
            try {
                DatasetSeedCheckpoint9043.save(cache, data, "policy", cursor) { it.writeInt(99); throw IOException("crash") }
                fail("injected crash")
            } catch (_: IOException) {}
            var value = 0
            DatasetSeedCheckpoint9043.restore(cache, data, "policy") { value = it.readInt() }
            assertEquals(42, value)
            RandomAccessFile(cache, "rw").use { it.seek(25); it.write(123) }
            var called = false
            rejects { DatasetSeedCheckpoint9043.restore(cache, data, "policy") { called = true } }
            assertFalse(called)
        } finally { dir.deleteRecursively() }
    }

    @Test fun partialLastRowDoesNotAdvanceReplayCursor() {
        val file = Files.createTempFile("aate9043-lines", ".jsonl").toFile()
        try {
            file.writeText("complete\npartial")
            assertEquals(9L, DatasetSeedCheckpoint9043.completePrefix(file))
            file.appendText("\n")
            assertEquals(file.length(), DatasetSeedCheckpoint9043.completePrefix(file))
            file.writeText("partial")
            assertEquals(0L, DatasetSeedCheckpoint9043.completePrefix(file))
        } finally { file.delete() }
    }

}
