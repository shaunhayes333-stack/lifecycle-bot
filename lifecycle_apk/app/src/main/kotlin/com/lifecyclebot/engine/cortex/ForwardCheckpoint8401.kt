package com.lifecyclebot.engine.cortex

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** SharedPreferences retains strings in RAM: keep the companion sparse-prediction checkpoint compressed. */
internal object ForwardCheckpoint8401 {
    private const val PREFIX = "gzip8401:"
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
        if (!raw.startsWith(PREFIX)) return raw
        return GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw.removePrefix(PREFIX))))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
