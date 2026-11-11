package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState

/** Observed, closed candles for the existing technical stack; never pad gaps or substitute one timeframe for another. */
object MultiTimeframeCharts8521 {
    enum class Frame8521(val label: String, val intervalMs: Long, val capacity: Int) {
        M1("1m", 60_000L, 300), M5("5m", 300_000L, 100), M15("15m", 900_000L, 60),
        H1("1h", 3_600_000L, 96), H4("4h", 14_400_000L, 72)
    }

    val higherFrames8521 = listOf(Frame8521.M5, Frame8521.M15, Frame8521.H1, Frame8521.H4)

    private fun history8521(ts: TokenState, frame: Frame8521): ArrayDeque<Candle> = when (frame) {
        Frame8521.M1 -> ts.history
        Frame8521.M5 -> ts.history5m
        Frame8521.M15 -> ts.history15m
        Frame8521.H1 -> ts.history1h
        Frame8521.H4 -> ts.history4h
    }

    internal fun valid8521(c: Candle, frame: Frame8521, nowMs: Long): Boolean =
        !c.synthetic && c.ts > 0L && c.ts % frame.intervalMs == 0L &&
            c.intervalMs8521 == frame.intervalMs && c.ts <= nowMs - frame.intervalMs &&
            c.priceUsd.isFinite() && c.openUsd.isFinite() && c.highUsd.isFinite() && c.lowUsd.isFinite() &&
            c.priceUsd > 0.0 && c.openUsd > 0.0 && c.lowUsd > 0.0 && c.highUsd >= c.lowUsd &&
            c.lowUsd <= minOf(c.openUsd, c.priceUsd) && c.highUsd >= maxOf(c.openUsd, c.priceUsd)

    /** The fetch caller knows the requested interval. Only real provider/trade bars acquire that identity. */
    fun stamp8521(rows: List<Candle>, frame: Frame8521, source: String, pool: String = "",
                  provider8521: Boolean = false): List<Candle> = rows.mapNotNull { c ->
        if (c.intervalMs8521 != 0L && c.intervalMs8521 != frame.intervalMs) return@mapNotNull null
        val tag = CandleCoverage8284.tagOf8284(c)
        if (c.intervalMs8521 == frame.intervalMs && tag != null && !c.synthetic &&
            tag.quality.rank >= CandleCoverage8284.Quality8284.TRADE_AGGREGATED.rank) return@mapNotNull c
        val quality = tag?.quality ?: if ((provider8521 || c.intervalMs8521 > 0L) && !c.synthetic)
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV else CandleCoverage8284.qualityOf8284(c)
        // A polled point or rolling snapshot is not a kline, even when its price is current.
        if (quality.rank < CandleCoverage8284.Quality8284.TRADE_AGGREGATED.rank ||
            (!provider8521 && tag == null && c.intervalMs8521 == 0L)) return@mapNotNull null
        val volume = c.periodVolumeUsd8521 ?: if (c.intervalMs8521 == 0L && (provider8521 || tag != null))
            c.vol.takeIf { it.isFinite() && it >= 0.0 } else null
        val stamped = if (c.intervalMs8521 == frame.intervalMs && c.periodVolumeUsd8521 == volume) c
            else c.copy(intervalMs8521 = frame.intervalMs, periodVolumeUsd8521 = volume)
        CandleCoverage8284.tag8284(stamped, quality, tag?.source ?: source, tag?.pool?.ifBlank { pool } ?: pool)
    }

    /** Chronological distinct bars, with neither the current unfinished candle nor stale data treated as current evidence. */
    fun snapshot8521(ts: TokenState, frame: Frame8521, nowMs: Long): List<Candle> {
        if (frame == Frame8521.M1) {
            // Consume the already received executed tape even when no history
            // fetch was requested. This is bounded local arithmetic, not I/O.
            HeliusSwapCandles7819.localTape8284(ts, nowMs, "chart_read")
        }
        val q = history8521(ts, frame)
        val rows = if (frame == Frame8521.M1 && ts.candleTimeframeMinutes > 1) emptyList()
            else try { synchronized(q) { q.toList() } } catch (_: ConcurrentModificationException) { emptyList() }
        val compatible = if (frame == Frame8521.M1) CandleCoverage8284.oneMinuteBars8284(ts.mint) else emptyList()
        val bars = stamp8521(rows + compatible, frame, "observed8521")
            .filter { valid8521(it, frame, nowMs) }.sortedByDescending { CandleCoverage8284.qualityOf8284(it).rank }
            .distinctBy { it.ts }.sortedBy { it.ts }.takeLast(frame.capacity)
        val close = bars.lastOrNull()?.let { it.ts + frame.intervalMs } ?: return emptyList()
        return if (nowMs - close in 0L..(2L * frame.intervalMs)) bars else emptyList()
    }

    /** Merge completed bars by interval, retaining one venue and the better observation. No append-duplicate weighting. */
    fun install8521(ts: TokenState, frame: Frame8521, rows: List<Candle>, nowMs: Long,
                    source: String, pool: String = "", provider8521: Boolean = false): Int {
        val incoming = stamp8521(rows, frame, source, pool, provider8521).filter { valid8521(it, frame, nowMs) }
        if (incoming.isEmpty()) return 0
        if (frame == Frame8521.M1) {
            // Held positions retain their tick tape; the established compatible store supplies their one-minute chart.
            return incoming.groupBy { CandleCoverage8284.tagOf8284(it)!! }.entries.sumOf { (tag, bars) ->
                CandleCoverage8284.merge8284(ts, bars, tag.quality, tag.source, tag.pool, nowMs).changed
            }
        }
        val q = history8521(ts, frame)
        return synchronized(q) { merge8521(q, incoming, frame, nowMs, pool) }
    }

    internal fun merge8521(target: ArrayDeque<Candle>, incoming: List<Candle>, frame: Frame8521,
                           nowMs: Long, pool: String = ""): Int {
        val byTime = java.util.TreeMap<Long, Candle>()
        // Token-wide providers may return tagged bars without a caller pool hint.
        // Keep the newest observed venue instead of joining two pools into a false trend.
        val selectedPool = pool.ifBlank {
            (target.toList() + incoming).filter { valid8521(it, frame, nowMs) &&
                CandleCoverage8284.tagOf8284(it)?.pool?.isNotBlank() == true }
                .maxByOrNull { it.ts }?.let { CandleCoverage8284.tagOf8284(it)?.pool }.orEmpty()
        }
        fun venueMatches(c: Candle): Boolean {
            val venue = CandleCoverage8284.tagOf8284(c)?.pool.orEmpty()
            return selectedPool.isBlank() || venue.isBlank() || selectedPool == venue
        }
        for (c in target) if (valid8521(c, frame, nowMs) && venueMatches(c)) byTime[c.ts] = c
        var changed = 0
        for (c in incoming) {
            if (!valid8521(c, frame, nowMs) || !venueMatches(c)) continue
            val old = byTime[c.ts]
            val oldPool = old?.let { CandleCoverage8284.tagOf8284(it)?.pool }.orEmpty()
            val newPool = CandleCoverage8284.tagOf8284(c)?.pool.orEmpty()
            if (old != null && oldPool.isNotBlank() && newPool.isNotBlank() && oldPool != newPool) continue
            if (old == null || CandleCoverage8284.qualityOf8284(c).rank >= CandleCoverage8284.qualityOf8284(old).rank) {
                if (old != c) changed++
                byTime[c.ts] = c
            }
        }
        target.clear()
        byTime.values.toList().takeLast(frame.capacity).forEach { target.addLast(it) }
        return changed
    }

    /** Aggregate complete contiguous intervals only. A missing minute/hour stays missing; rolling volumes are never summed. */
    fun resample8521(rows: List<Candle>, from: Frame8521, to: Frame8521, nowMs: Long): List<Candle> {
        if (to.intervalMs <= from.intervalMs || to.intervalMs % from.intervalMs != 0L) return emptyList()
        val count = (to.intervalMs / from.intervalMs).toInt()
        return rows.filter { valid8521(it, from, nowMs) }.distinctBy { it.ts }
            .groupBy { it.ts / to.intervalMs * to.intervalMs }.toSortedMap().mapNotNull { (start, group) ->
                val bars = group.sortedBy { it.ts }
                if (bars.size != count || bars.withIndex().any { (i, c) -> c.ts != start + i * from.intervalMs } ||
                    start > nowMs - to.intervalMs) return@mapNotNull null
                val venues = bars.mapNotNull { CandleCoverage8284.tagOf8284(it)?.pool?.takeIf(String::isNotBlank) }.toSet()
                if (venues.size > 1) return@mapNotNull null
                val volumes = bars.map { it.periodVolumeUsd8521 }
                val volume = if (volumes.all { it != null && it.isFinite() && it >= 0.0 }) volumes.sumOf { it!! } else null
                val c = Candle(start, bars.last().priceUsd, bars.last().marketCap, 0.0, volume ?: 0.0,
                    highUsd = bars.maxOf { it.highUsd }, lowUsd = bars.minOf { it.lowUsd }, openUsd = bars.first().openUsd,
                    intervalMs8521 = to.intervalMs, periodVolumeUsd8521 = volume)
                CandleCoverage8284.tag8284(c, CandleCoverage8284.Quality8284.TRADE_AGGREGATED,
                    "resampled_${from.label}_8521", venues.singleOrNull().orEmpty())
            }.takeLast(to.capacity)
    }

    /** Free reuse of already observed data, before asking a provider. */
    fun refreshLocal8521(ts: TokenState, nowMs: Long) {
        val one = snapshot8521(ts, Frame8521.M1, nowMs)
        for (frame in listOf(Frame8521.M5, Frame8521.M15, Frame8521.H1))
            install8521(ts, frame, resample8521(one, Frame8521.M1, frame, nowMs), nowMs, "local_aggregate8521")
        install8521(ts, Frame8521.H4, resample8521(snapshot8521(ts, Frame8521.H1, nowMs),
            Frame8521.H1, Frame8521.H4, nowMs), nowMs, "local_aggregate8521")
    }

    fun fetchPossible8521(frame: Frame8521, ageMs: Long?): Boolean = ageMs == null || ageMs < 0L || ageMs >= frame.intervalMs

    fun refreshGap8521(frame: Frame8521): Long = when (frame) {
        Frame8521.M1 -> 30_000L
        Frame8521.M5 -> 60_000L
        Frame8521.M15 -> 300_000L
        Frame8521.H1 -> 900_000L
        Frame8521.H4 -> 3_600_000L
    }
}
