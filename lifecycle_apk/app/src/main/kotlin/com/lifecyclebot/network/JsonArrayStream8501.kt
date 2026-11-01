package com.lifecyclebot.network

import org.json.JSONObject
import java.io.Reader

/**
 * V5.0.8501 §A_LIST_IS_READ_ONE_ROW_AT_A_TIME. 5.0.8499 ran at heap 512/512 MB (peak 100 %, 21 hard trims, 4 of them
 * ineffective) with unattributedMb=276: transient allocations no store owns. The largest bodies the bot reads are the
 * Jupiter verified token list (lite-api.jup.ag/tokens 5,156 KB, read by JupiterStrictTokenList AND
 * DynamicAltTokenRegistry), each read as `body.string()` (~2N) and then a whole `JSONArray` DOM (~5-10N): 35-60 MB
 * allocated at once per read, twice per cycle of refreshes, which is the spike that pins the heap.
 *
 * This reads a top-level JSON array straight off the response stream and hands each element over as its own small
 * [JSONObject]; one element's text is resident at a time (a reused buffer), never the whole body or the whole tree.
 * Strings and escapes are honoured, nested objects / arrays inside an element are kept verbatim. Non-object elements
 * are skipped. [fn] returns false to stop early. Returns the number of objects delivered.
 */
object JsonArrayStream8501 {
    fun forEachObject8501(reader: Reader, maxElementChars: Int = 512 * 1024, fn: (JSONObject) -> Boolean): Int {
        val buf = CharArray(8 * 1024)
        val el = StringBuilder(2048)
        var started = false
        var depth = 0           // depth inside the current element (0 = between elements)
        var inString = false
        var escape = false
        var capturing = false
        var oversize = false
        var delivered = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buf[i]
                if (!started) {
                    if (ch == '[') started = true
                    else if (!ch.isWhitespace() && ch != '\uFEFF') return delivered   // not an array
                    continue
                }
                if (capturing) {
                    if (!oversize) { if (el.length >= maxElementChars) oversize = true else el.append(ch) }
                    if (inString) {
                        if (escape) escape = false
                        else if (ch == '\\') escape = true
                        else if (ch == '"') inString = false
                        continue
                    }
                    when (ch) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) {
                                capturing = false
                                if (!oversize) {
                                    val o = try { JSONObject(el.toString()) } catch (_: Throwable) { null }
                                    if (o != null) { delivered++; if (!fn(o)) return delivered }
                                }
                                el.setLength(0); oversize = false
                            }
                        }
                    }
                } else {
                    when (ch) {
                        '{' -> { capturing = true; depth = 1; el.setLength(0); el.append(ch) }
                        ']' -> return delivered   // end of the top-level array
                        '[' -> { capturing = true; depth = 1; el.setLength(0); el.append(ch); oversize = true } // nested array element: skipped
                        else -> Unit                // commas, whitespace, scalars between elements
                    }
                }
            }
        }
        return delivered
    }
}
