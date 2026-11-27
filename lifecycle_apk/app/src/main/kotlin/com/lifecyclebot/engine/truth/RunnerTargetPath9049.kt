package com.lifecyclebot.engine.truth

/**
 * Research only. First ACCEPTED observation at each target and at the frozen
 * gross stop. No simulated fill, no interpolation, no actual live authority.
 * A missing observation is not a negative label. Coverage gaps remain explicit.
 */
internal class RunnerTargetPath9049 private constructor(
    val decisionMs: Long, val entryMs: Long, val stopPct: Double, val costPct: Double
) {
    private var lastMs = maxOf(decisionMs, entryMs)
    private var observations = 0
    private var maxGapMs = 0L
    private var stopMs = 0L
    private val firstMs = LongArray(TARGETS.size)

    @Synchronized fun observe(atMs: Long, grossPct: Double) {
        if (atMs <= lastMs || atMs > decisionMs + HORIZON_MS ||
            !grossPct.isFinite() || grossPct < -100.0) return
        maxGapMs = maxOf(maxGapMs, atMs - lastMs)
        lastMs = atMs
        observations++
        if (stopMs == 0L && grossPct <= -stopPct) stopMs = atMs
        val net = grossPct - costPct
        for (i in TARGETS.indices) if (firstMs[i] == 0L && net >= TARGETS[i]) firstMs[i] = atMs
    }

    /** Compact restart checkpoint. Schema and the original entry/stop/cost are immutable. */
    @Synchronized fun pack(): String = listOf("1", decisionMs, entryMs, stopPct, costPct,
        lastMs, observations, maxGapMs, stopMs).joinToString("|") + "|" + firstMs.joinToString("|")

    /** Marks only; consumers must evaluate temporal coverage and independent evidence. */
    @Synchronized fun json(): String {
        fun stamp(t: Long) = if (t > 0L) t.toString() else "null"
        return "{\"v\":1,\"kind\":\"OBSERVED_MARK_ORDER_ONLY\",\"decisionMs\":" + decisionMs +
            ",\"entryMs\":" + entryMs + ",\"horizonMs\":" + HORIZON_MS + ",\"stopPct\":" + stopPct +
            ",\"costPct\":" + costPct + ",\"lastMs\":" + lastMs + ",\"n\":" + observations +
            ",\"maxGapMs\":" + maxGapMs + ",\"endGapMs\":" + (decisionMs + HORIZON_MS - lastMs) +
            ",\"firstStopMs\":" + stamp(stopMs) + ",\"targetsNetPct\":[" + TARGETS.joinToString(",") +
            "],\"firstTargetMs\":[" + firstMs.joinToString(",") { stamp(it) } + "],\"liveAuthority\":false}"
    }

    companion object {
        private const val HORIZON_MS = 3_600_000L
        private val TARGETS = doubleArrayOf(30.0, 50.0, 100.0, 200.0, 500.0, 1000.0)

        fun start(decisionMs: Long, entryMs: Long, stopPct: Double, costPct: Double): RunnerTargetPath9049? =
            if (decisionMs > 0L && decisionMs <= Long.MAX_VALUE - HORIZON_MS &&
                entryMs in (decisionMs - 5_000L).coerceAtLeast(1L)..(decisionMs + HORIZON_MS) &&
                stopPct.isFinite() && stopPct > 0.0 && stopPct <= 100.0 &&
                costPct.isFinite() && costPct in 0.0..100.0)
                RunnerTargetPath9049(decisionMs, entryMs, stopPct, costPct) else null

        fun unpack(raw: String, decisionMs: Long, entryMs: Long): RunnerTargetPath9049? {
            if (raw.length > 512) return null
            return try {
                val p = raw.split('|')
                if (p.size != 9 + TARGETS.size || p[0] != "1" ||
                    p[1].toLong() != decisionMs || p[2].toLong() != entryMs) return null
                val r = start(decisionMs, entryMs, p[3].toDouble(), p[4].toDouble()) ?: return null
                val start = maxOf(decisionMs, entryMs)
                r.lastMs = p[5].toLong()
                r.observations = p[6].toInt()
                r.maxGapMs = p[7].toLong()
                r.stopMs = p[8].toLong()
                for (i in TARGETS.indices) r.firstMs[i] = p[9+i].toLong()
                if (r.lastMs !in start..(decisionMs + HORIZON_MS) ||
                    r.observations < 0 || r.maxGapMs !in 0L..(r.lastMs-start) ||
                    (r.observations == 0 && r.lastMs != start) ||
                    (r.observations > 0 && r.lastMs == start)) return null
                fun valid(t: Long) = t == 0L || t in (start+1)..r.lastMs
                if (!valid(r.stopMs) || r.firstMs.any { !valid(it) }) return null
                for (i in 1 until TARGETS.size)
                    if (r.firstMs[i] != 0L && (r.firstMs[i-1] == 0L || r.firstMs[i] < r.firstMs[i-1])) return null
                r
            } catch (_: Exception) { null }
        }
    }
}
