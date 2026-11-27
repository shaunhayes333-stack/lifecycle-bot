package com.lifecyclebot.engine.truth

/** First accepted mark at each target/stop; never a fill or a live authority. */
internal class RunnerTargetPath9049 private constructor(
    val decisionMs: Long, val entryMs: Long, val stopPct: Double, val costPct: Double
) {
    private var lastMs = maxOf(decisionMs, entryMs)
    private var observations = 0
    private var maxGapMs = 0L
    private var stopMs = 0L
    private val firstMs = LongArray(TARGETS.size)
    // The uninterrupted prefix ends permanently at the first excessive gap.
    private var prefixLast = lastMs
    private var prefixN = 0

    @Synchronized fun observe(atMs: Long, grossPct: Double) {
        if (atMs <= lastMs || atMs > decisionMs + HORIZON_MS ||
            !grossPct.isFinite() || grossPct < -100.0) return
        val gap = atMs - lastMs
        if (prefixLast == lastMs && gap <= RunnerBarrierLearning9051.GAP) {
            prefixLast = atMs; prefixN++
        }
        maxGapMs = maxOf(maxGapMs, gap)
        lastMs = atMs
        observations++
        if (stopMs == 0L && grossPct <= -stopPct) stopMs = atMs
        val net = grossPct - costPct
        for (i in TARGETS.indices) if (firstMs[i] == 0L && net >= TARGETS[i]) firstMs[i] = atMs
    }

    /** Compact restart checkpoint. v1 without a reconstructible prefix stays unknown. */
    @Synchronized fun pack(): String = listOf("2", decisionMs, entryMs, stopPct, costPct,
        lastMs, observations, maxGapMs, stopMs).joinToString("|") + "|" + firstMs.joinToString("|") +
        "|" + prefixLast + "|" + prefixN

    /** Marks only; consumers must evaluate temporal coverage and independent evidence. */
    @Synchronized fun json(): String {
        fun stamp(t: Long) = if (t > 0L) t.toString() else "null"
        return "{\"v\":2,\"kind\":\"OBSERVED_MARK_ORDER_ONLY\",\"decisionMs\":" + decisionMs +
            ",\"entryMs\":" + entryMs + ",\"horizonMs\":" + HORIZON_MS + ",\"stopPct\":" + stopPct +
            ",\"costPct\":" + costPct + ",\"lastMs\":" + lastMs + ",\"n\":" + observations +
            ",\"maxGapMs\":" + maxGapMs + ",\"endGapMs\":" + (decisionMs + HORIZON_MS - lastMs) +
            ",\"firstStopMs\":" + stamp(stopMs) + ",\"targetsNetPct\":[" + TARGETS.joinToString(",") +
            "],\"firstTargetMs\":[" + firstMs.joinToString(",") { stamp(it) } + "],\"prefixLast9057\":" + prefixLast +
            ",\"prefixN9057\":" + prefixN + ",\"liveAuthority\":false}"
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
                if (p.isEmpty() || !((p[0] == "1" && p.size == 9 + TARGETS.size) ||
                    (p[0] == "2" && p.size == 11 + TARGETS.size)) ||
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
                if (p[0] == "2") {
                    r.prefixLast = p[9+TARGETS.size].toLong()
                    r.prefixN = p[10+TARGETS.size].toInt()
                } else if (r.maxGapMs <= RunnerBarrierLearning9051.GAP) {
                    r.prefixLast = r.lastMs; r.prefixN = r.observations
                }
                // A legacy path containing a gap has no recoverable continuous prefix.
                if (!RunnerBarrierLearning9051.validPrefix9057(start,r.lastMs,r.maxGapMs,r.observations,r.prefixLast,r.prefixN)) return null
                r
            } catch (_: Exception) { null }
        }
    }
}
