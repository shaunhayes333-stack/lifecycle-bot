package com.lifecyclebot.engine.cortex

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** SharedPreferences retains strings in RAM: keep the companion sparse-prediction checkpoint compressed. */
internal object ForwardCheckpoint8401 {
    private const val PREFIX = "gzip8401:"
    // A prior 26 MB pending graph and older frozen forward rows reached the label clock on a
    // 512 MB phone. Unbounded readText() doubled its backing array during inflation and OOM'd.
    // The current single-episode checkpoint is much smaller; oversized legacy blobs must be
    // reported unavailable, never inflated while the live price/exit loop is running.
    private const val MAX_PACKED_CHARS = 1_500_000
    private const val MAX_UNPACKED_CHARS = 6_000_000
    fun pack(raw: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(raw) }
        return PREFIX + Base64.getEncoder().encodeToString(output.toByteArray())
    }
    /** Compress bounded pending rows directly; keeping all admitted rows must not require a second multi-MiB joined string. */
    fun packRows8521(rows: Sequence<String>, separator: Char): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            var first = true
            for (row in rows) {
                if (!first) writer.write(separator.code)
                writer.write(row)
                first = false
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(output.toByteArray())
    }
    fun unpack(raw: String): String {
        if (!raw.startsWith(PREFIX)) {
            require(raw.length <= MAX_UNPACKED_CHARS) { "FORWARD_CHECKPOINT_UNPACK_TOO_LARGE_8659" }
            return raw
        }
        require(raw.length <= MAX_PACKED_CHARS) { "FORWARD_CHECKPOINT_PACKED_TOO_LARGE_8659" }
        return GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw.substring(PREFIX.length))))
            .bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    require(n <= MAX_UNPACKED_CHARS - out.length) { "FORWARD_CHECKPOINT_UNPACK_TOO_LARGE_8659" }
                    out.append(chunk, 0, n)
                }
                out.toString()
            }
    }
}
