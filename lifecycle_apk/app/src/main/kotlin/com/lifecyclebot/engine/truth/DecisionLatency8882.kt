package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8882 §WHERE_THE_FIRST_SECONDS_GO (plan W9, speed) — owner: "there are 60,000% profit opportunities out there
 * literally every day"; "it's just aate doesn't trade past 5 minutes".
 *
 * The launch-hour replay of every export (20 Nov) under the live doctrine: bought at a launch's first print +13.7 % a
 * trade, ten seconds later +1.7 %, thirty seconds later -7.5 % (paths with six bars or more, so optimistic). The first
 * lane decision on a launch comes a median 41 s after its birth and only a quarter within 10 s (37,410 launches). That
 * delay has two parts no record could separate: how long after birth the bot HEARD the coin (its watchlist entry,
 * TokenState.addedToWatchlistAt) and how long after hearing it the first lane DECIDED (LaunchHour8628's first decision
 * row). This splits them per discovery source (the first entry of TokenState.source), so the next report says whether the
 * seconds go to the feeds or to the decision loop. Measurement only: nothing here changes a decision.
 */
object DecisionLatency8882 {
    private const val HOUR_MS = 3_600_000L
    private const val KEEP = 600
    private const val MAX_FAMILIES = 40

    private class Ring { val heard = LongArray(KEEP); val decide = LongArray(KEEP); var n = 0L }
    private val firstAt = ConcurrentHashMap<String, Long>()
    private val bySource = ConcurrentHashMap<String, Ring>()

    /** Pure: the discovery family of a TokenState.source ("PUMP_PORTAL_WS,SCANNER_DIRECT" -> PUMP_PORTAL_WS). */
    fun family8882(source: String?): String =
        source.orEmpty().split(',').firstOrNull()?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.take(32) ?: "UNKNOWN"

    /**
     * Pure: (heard, decided) seconds for a first decision at [atMs] on a coin born at [bornMs] and put on the watchlist at
     * [heardMs]; heard is -1 when the watchlist time does not lie between birth and the decision (a restored coin).
     */
    fun split8882(bornMs: Long, heardMs: Long, atMs: Long): Pair<Long, Long> {
        val heardOk = heardMs in bornMs..atMs
        val heard = if (heardOk) (heardMs - bornMs) / 1000L else -1L
        val decide = (atMs - (if (heardOk) heardMs else bornMs)) / 1000L
        return heard to decide
    }

    /** LaunchHour8628.observeDecision: the first lane decision on a launch inside its first hour (later ones are ignored). */
    fun note8882(mint: String, source: String?, bornMs: Long, heardMs: Long, atMs: Long) {
        if (mint.isBlank() || atMs - bornMs !in 0L..HOUR_MS) return
        if (firstAt.putIfAbsent(mint, atMs) != null) return
        if (firstAt.size > 20_000) firstAt.entries.removeIf { atMs - it.value > HOUR_MS }
        val fam = family8882(source)
        val ring = bySource[fam] ?: if (bySource.size >= MAX_FAMILIES) bySource.computeIfAbsent("OTHER") { Ring() }
            else bySource.computeIfAbsent(fam) { Ring() }
        val (heard, decide) = split8882(bornMs, heardMs, atMs)
        synchronized(ring) {
            val i = (ring.n % KEEP).toInt()
            ring.heard[i] = heard; ring.decide[i] = decide; ring.n++
        }
    }

    private fun pct(v: List<Long>, p: Double): Long = if (v.isEmpty()) -1L else v.sorted()[((v.size - 1) * p).toInt()]

    /** Brief: per source (most first decisions first), heard and decided seconds at the median and p90, and the 10 s share. */
    fun statusLine8882(): String {
        val rows = bySource.entries.map { (k, r) ->
            synchronized(r) {
                val m = minOf(r.n, KEEP.toLong()).toInt()
                Triple(k, r.n, (0 until m).map { r.heard[it] } to (0 until m).map { r.decide[it] })
            }
        }.sortedByDescending { it.second }.take(10)
        val body = rows.joinToString(" ") { (k, n, v) ->
            val heard = v.first.filter { it >= 0L }
            val total = v.first.indices.map { i -> (if (v.first[i] >= 0L) v.first[i] else 0L) + v.second[i] }
            val fast = if (total.isEmpty()) 0 else 100 * total.count { it <= 10L } / total.size
            "$k{n=$n heard50/90=${pct(heard, 0.5)}/${pct(heard, 0.9)}s decide50/90=${pct(v.second, 0.5)}/${pct(v.second, 0.9)}s within10s=$fast%}"
        }.ifBlank { "-" }
        return "decisionLatency8882[$body rule=first lane decision on a launch, split into birth->watchlist (heard) and watchlist->decision (decide), last $KEEP per source]"
    }

    internal fun resetForTest8882() { firstAt.clear(); bySource.clear() }
}
