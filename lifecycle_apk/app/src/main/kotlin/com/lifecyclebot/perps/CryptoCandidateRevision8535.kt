package com.lifecyclebot.perps

/**
 * V5.0.8535 §C07 — a crypto candidate's revision is the content of its decisive inputs, not a 30-second wall-clock bucket.
 * superseded: `candidateVersion = System.currentTimeMillis() / 30_000L` — a READY written at :29.999 was unreadable at
 * :30.001 (bestReady matched on the bucket), and two different signals inside one bucket overwrote each other's state.
 * Same inputs -> same revision whatever the clock; any changed input -> a different revision. Never 0 (0 = "no revision").
 */
object CryptoCandidateRevision8535 {
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun of(vararg parts: Any?): Long {
        var h = FNV_OFFSET
        for (p in parts) {
            val s = when (p) {
                null -> "null"
                is Double -> if (p.isNaN()) "NaN" else String.format(java.util.Locale.ROOT, "%.6g", p)
                is Float -> String.format(java.util.Locale.ROOT, "%.6g", p.toDouble())
                is Iterable<*> -> p.joinToString(",") { it.toString() }
                else -> p.toString()
            }
            for (ch in s) { h = h xor ch.code.toLong(); h *= FNV_PRIME }
            h = h xor 0x1fL; h *= FNV_PRIME
        }
        val v = h and Long.MAX_VALUE
        return if (v == 0L) 1L else v
    }
}
