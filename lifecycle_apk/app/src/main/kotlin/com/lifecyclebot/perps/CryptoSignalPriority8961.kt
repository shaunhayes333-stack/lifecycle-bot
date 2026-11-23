package com.lifecyclebot.perps

/** Both crypto scans spend their bounded evaluation slots on their own strategy records first. */
internal object CryptoSignalPriority8961 {
    private data class Ranked<T>(val signal: T, val record: Int, val score: Int, val confidence: Int)

    fun <T> order(signals: List<T>, limit: Int, recordRank: (T) -> Int,
                  score: (T) -> Int, confidence: (T) -> Int): List<T> =
        signals.map { Ranked(it, recordRank(it), score(it), confidence(it)) }
            .sortedWith(compareByDescending<Ranked<T>> { it.record }
                .thenByDescending { it.score }.thenByDescending { it.confidence })
            .take(limit.coerceAtLeast(0)).map { it.signal }
}
