package com.lifecyclebot.engine.truth

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Derived evidence cache, never a replacement for the original labelled dataset or economic journal. */
internal object DatasetSeedCheckpoint9043 {
    private const val MAGIC = 0x41415445
    private const val VERSION = 9043
    private const val MAX_ITEMS = 1_000_000
    data class Cursor(val offset: Long, val lines: Long, val refused: Long, val parsed: Long, val accepted: Long)

    fun count(input: DataInputStream, max: Int = MAX_ITEMS): Int = input.readInt().also {
        require(it in 0..max) { "invalid checkpoint collection" }
    }

    fun finite(input: DataInputStream): Double = input.readDouble().also {
        require(it.isFinite()) { "invalid checkpoint statistic" }
    }

    private fun digest(file: File, length: Long): ByteArray {
        require(length in 0L..file.length())
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0L) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("checkpoint cancelled")
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(n > 0) { "dataset changed during checkpoint validation" }
                md.update(buffer, 0, n); remaining -= n
            }
        }
        return md.digest()
    }

    /** Validate identity, entire dataset prefix and entire payload before a reader mutates any derived state. */
    fun restore(file: File, dataset: File, policy: String, readState: (DataInputStream) -> Unit): Cursor? {
        if (!file.isFile || file.length() < 100L) return null
        val payloadLength = file.length() - 32L
        val expected = FileInputStream(file).use { raw ->
            raw.channel.position(payloadLength)
            ByteArray(32).also { DataInputStream(raw).readFully(it) }
        }
        require(MessageDigest.isEqual(expected, digest(file, payloadLength))) { "checkpoint checksum" }
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "checkpoint version" }
            require(input.readUTF() == policy) { "checkpoint policy" }
            val cursor = Cursor(input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong())
            require(cursor.offset >= 0L && cursor.lines >= 0L && cursor.refused >= 0L && cursor.parsed >= 0L && cursor.accepted >= 0L)
            require(cursor.accepted <= cursor.parsed && cursor.parsed <= cursor.lines && cursor.refused <= cursor.lines)
            val prefix = ByteArray(32).also { input.readFully(it) }
            require(MessageDigest.isEqual(prefix, digest(dataset, cursor.offset))) { "dataset prefix changed" }
            readState(input)
            require(input.readInt() == MAGIC) { "incomplete ledger generation" }
            val footer = ByteArray(32).also { input.readFully(it) }
            require(MessageDigest.isEqual(expected, footer) && input.read() == -1) { "checkpoint trailing state" }
            cursor
        }
    }

    /** One fsynced generation: an interrupted write cannot replace the last complete checkpoint. */
    fun save(file: File, dataset: File, policy: String, cursor: Cursor, writeState: (DataOutputStream) -> Unit) {
        val prefix = digest(dataset, cursor.offset)
        val temp = File(file.parentFile, file.name + ".new")
        try {
            FileOutputStream(temp).use { raw ->
                val output = DataOutputStream(BufferedOutputStream(raw))
                output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(policy)
                output.writeLong(cursor.offset); output.writeLong(cursor.lines); output.writeLong(cursor.refused)
                output.writeLong(cursor.parsed); output.writeLong(cursor.accepted); output.write(prefix)
                writeState(output); output.writeInt(MAGIC); output.flush(); raw.fd.sync()
            }
            FileOutputStream(temp, true).use { raw -> raw.write(digest(temp, temp.length())); raw.fd.sync() }
            // All producers retain the source dataset. If it was rotated during this write, do not publish a mixed generation.
            require(MessageDigest.isEqual(prefix, digest(dataset, cursor.offset))) { "dataset rotated during checkpoint" }
            require(temp.renameTo(file)) { "checkpoint atomic rename failed" }
        } finally { if (temp.exists()) temp.delete() }
    }

    /** Snapshot only complete appended rows. An in-progress final line is replayed on the next generation. */
    fun completePrefix(file: File): Long = RandomAccessFile(file, "r").use { input ->
        var end = input.length()
        if (end == 0L) return@use 0L
        input.seek(end - 1L)
        if (input.read() == '\n'.code) return@use end
        val buffer = ByteArray(8192)
        while (end > 0L) {
            val start = maxOf(0L, end - buffer.size)
            input.seek(start); val n = (end - start).toInt(); input.readFully(buffer, 0, n)
            for (i in n - 1 downTo 0) if (buffer[i] == '\n'.code.toByte()) return@use start + i + 1L
            end = start
        }
        0L
    }

}
