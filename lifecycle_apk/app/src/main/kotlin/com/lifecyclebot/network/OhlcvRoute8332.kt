package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8332 §OHLCV_ROUTING — a dead OHLCV source is detected from its own answers and routed around.
 *
 * 5.0.8326: GeckoTerminal OHLCV delivered 0 bars from 1,615 attempts while the local Helius candle fallback delivered 889.
 * The feed kept asking the dead source on every read. Now a source that answers [DEAD_AFTER_ZERO_ANSWERS_8332] consecutive
 * times with no bar is DEAD from that moment: the timing / sniper / PriceShapeBars reads are served from the coin's own
 * local candles (the Helius swap backfill and the venue prints on the tape, PumpPortal frames included) without another
 * provider call, and the dead source is probed once per back-off interval (5 min doubling to 1 h). One served answer revives it.
 *
 * Every served series is stamped with its source and its newest bar time ([stamp8332]); [fresh8332] says whether that bar
 * is current for its timeframe, so a stale bar never reads as the present price.
 */
object OhlcvRoute8332 {
    const val GECKO_8332 = "gecko"
    const val PAPRIKA_8332 = "dexpaprika"
    const val LOCAL_8332 = "local"
    const val DEAD_AFTER_ZERO_ANSWERS_8332 = 12
    const val PROBE_BASE_MS_8332 = 5L * 60_000L
    const val PROBE_MAX_MS_8332 = 60L * 60_000L

    private class SourceState8332 {
        @Volatile var zeroStreak: Int = 0
        @Volatile var deadSinceMs: Long = 0L
        @Volatile var nextProbeMs: Long = 0L
        @Volatile var probes: Int = 0
        val answers = AtomicLong(0L)
        val bars = AtomicLong(0L)
    }

    data class Stamp8332(val source: String, val newestBarMs: Long, val servedAtMs: Long)

    private val sources = ConcurrentHashMap<String, SourceState8332>()
    private val routedBars = ConcurrentHashMap<String, AtomicLong>()
    private val routedAroundDead = AtomicLong(0L)
    private val stamps = ConcurrentHashMap<String, Stamp8332>()
    @Volatile private var lastSource = "-"

    private fun state(source: String): SourceState8332 = sources.computeIfAbsent(source) { SourceState8332() }

    /** Pure: this many consecutive zero-bar answers declare a source dead. */
    fun deadAfter8332(zeroStreak: Int): Boolean = zeroStreak >= DEAD_AFTER_ZERO_ANSWERS_8332

    /** Pure: the probe interval after [probes] failed probes of a dead source — 5 min doubling, capped at 1 h. */
    fun probeIntervalMs8332(probes: Int): Long = (PROBE_BASE_MS_8332 shl probes.coerceIn(0, 4)).coerceAtMost(PROBE_MAX_MS_8332)

    /** Pure: a bar is current for its timeframe only inside two bars plus 30 s of the clock; nothing from the future. */
    fun fresh8332(newestBarMs: Long, nowMs: Long, tfMinutes: Int): Boolean =
        newestBarMs > 0L && nowMs - newestBarMs in 0L..(2L * tfMinutes.coerceAtLeast(1) * 60_000L + 30_000L)

    /** A source ANSWERED (not throttled, not errored) with [bars] bars. Zero bars lengthen its streak; any bar revives it. */
    fun noteAnswer8332(source: String, bars: Int, nowMs: Long = System.currentTimeMillis()) {
        val s = state(source)
        s.answers.incrementAndGet()
        synchronized(s) {
            if (bars > 0) {
                if (s.deadSinceMs > 0L) try { PipelineHealthCollector.labelInc("OHLCV_SOURCE_REVIVED_8332_${source.uppercase()}") } catch (_: Throwable) {}
                s.zeroStreak = 0; s.deadSinceMs = 0L; s.nextProbeMs = 0L; s.probes = 0
                s.bars.addAndGet(bars.toLong())
                return
            }
            s.zeroStreak++
            if (s.deadSinceMs == 0L && deadAfter8332(s.zeroStreak)) {
                s.deadSinceMs = nowMs; s.probes = 0
                s.nextProbeMs = nowMs + probeIntervalMs8332(0)
                try { PipelineHealthCollector.labelInc("OHLCV_SOURCE_DEAD_8332_${source.uppercase()}") } catch (_: Throwable) {}
            }
        }
    }

    fun dead8332(source: String): Boolean = (sources[source]?.deadSinceMs ?: 0L) > 0L

    /** Milliseconds until a dead source may be probed (0 = not dead, or a probe is due now). Does not claim the probe. */
    fun probeWaitMs8332(source: String, nowMs: Long = System.currentTimeMillis()): Long {
        val s = sources[source] ?: return 0L
        if (s.deadSinceMs == 0L) return 0L
        return (s.nextProbeMs - nowMs).coerceAtLeast(0L)
    }

    /** True when the dead source may be asked once now; the claim schedules the next probe on the back-off. A live source is always askable. */
    fun probeDue8332(source: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = sources[source] ?: return true
        if (s.deadSinceMs == 0L) return true
        synchronized(s) {
            if (nowMs < s.nextProbeMs) return false
            s.probes++
            s.nextProbeMs = nowMs + probeIntervalMs8332(s.probes)
            try { PipelineHealthCollector.labelInc("OHLCV_DEAD_SOURCE_PROBED_8332_${source.uppercase()}") } catch (_: Throwable) {}
            return true
        }
    }

    /** A read routed to [LOCAL_8332] because the provider is dead. */
    fun noteRoutedAroundDead8332(deadSource: String) {
        routedAroundDead.incrementAndGet()
        try { PipelineHealthCollector.labelInc("OHLCV_ROUTED_AROUND_DEAD_8332_${deadSource.uppercase()}") } catch (_: Throwable) {}
    }

    /** Bars served to a caller from [source]: counted per source and stamped per mint with the newest bar's time. */
    fun served8332(source: String, mint: String, bars: List<Candle>, nowMs: Long = System.currentTimeMillis()) {
        if (bars.isEmpty()) return
        routedBars.computeIfAbsent(source) { AtomicLong(0L) }.addAndGet(bars.size.toLong())
        lastSource = source
        val newest = bars.maxOf { it.ts }
        stamps[mint] = Stamp8332(source, newest, nowMs)
        if (stamps.size > 3_000) stamps.entries.removeIf { nowMs - it.value.servedAtMs > 30L * 60_000L }
    }

    fun stamp8332(mint: String): Stamp8332? = stamps[mint]

    /** The newest served bar for [mint] is current for [tfMinutes] (false when nothing was served or the bar is stale). */
    fun current8332(mint: String, tfMinutes: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val st = stamps[mint] ?: return false
        return fresh8332(st.newestBarMs, nowMs, tfMinutes)
    }

    fun statusLine8332(nowMs: Long = System.currentTimeMillis()): String {
        val bars = routedBars.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        val dead = sources.entries.filter { it.value.deadSinceMs > 0L }.sortedBy { it.key }.joinToString(",") {
            "${it.key}=${(nowMs - it.value.deadSinceMs) / 1000L}s(probes=${it.value.probes},nextProbeS=${((it.value.nextProbeMs - nowMs) / 1000L).coerceAtLeast(0L)})"
        }.ifBlank { "none" }
        val streaks = sources.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.zeroStreak}/${it.value.answers.get()}" }.ifBlank { "none" }
        return "ohlcvRoute8332[source=$lastSource, bars{$bars}, deadSince{$dead}, zeroStreak{$streaks}, routedAroundDead=${routedAroundDead.get()}]"
    }

    internal fun resetForTest8332() {
        sources.clear(); routedBars.clear(); stamps.clear(); routedAroundDead.set(0L); lastSource = "-"
    }
}
