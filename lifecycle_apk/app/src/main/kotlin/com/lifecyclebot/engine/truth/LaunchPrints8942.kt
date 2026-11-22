package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8942 §THE_LAUNCH_IS_READ_AT_ITS_TWENTIETH_PRINT — owner, 22 Nov 15:07 / 15:28: "capturing these big winners as early
 * as possible", then "keep going". The launch-hour paths (9,688 pump.fun launches, 14-20 Nov): the moment a launch shows its
 * 20th print (20-40 s after birth for most) is where an entry pays best; each 10 s later costs 2-4 points (stop -30 %, held:
 * +15.7 % filled at the next bar, +12.6 % at 20 s, +8.8 % at 30 s). The lanes' admitted launches entered at a median 46-78 s,
 * 17-23 % into the run, and 29-32 % of the eventual 4x coins after half the run.
 *
 * Why so late: a PumpPortal create runs the full pipeline at once (BotService.fastLaneEvaluate7277, twice 1.5 s apart) —
 * when the coin has a print or two and the lanes can only defer — and that fast lane is then closed to the mint for 60 s, so
 * nothing re-reads the coin at its 20th print; it waits for the main loop (a 5.8 s cycle on the 13:59 capture) and its turn
 * in it (decisionLatency8882 PUMP_PORTAL_WS decide50/90 = 14/46 s).
 *
 * Now each witnessed, live-timed print of a pump.fun launch (TradeTape8100.launchEvidence8750, the same prints the launch-hour
 * export counts) is counted from the launch's first print; at its 20th, 40th and 80th within 15 minutes of that first print
 * the listener runs the same full pipeline on it (no gate bypassed, its own fast-lane key per milestone). Nothing here admits,
 * sizes or trades.
 */
object LaunchPrints8942 {
    private val MILESTONES_8942 = intArrayOf(20, 40, 80)
    private const val WINDOW_MS_8942 = 15L * 60_000L
    private const val MAX_TRACKED_8942 = 4_000

    private class Count8942(val firstMs: Long) { var n = 0 }
    private val counts = ConcurrentHashMap<String, Count8942>()
    private val fired = ConcurrentHashMap<Int, AtomicLong>()
    private val late = AtomicLong(0L)
    private val notWatched = AtomicLong(0L)

    /** BotService.wireExternalStreams: run the full pipeline on (mint, milestone). */
    @Volatile var listener8942: ((String, Int) -> Unit)? = null

    /** Pure: is [n] a print count the pipeline re-reads a launch at? */
    internal fun milestone8942(n: Int): Boolean = MILESTONES_8942.contains(n)

    /** Pure: is a milestone at [eventMs] for a launch first printed at [firstMs] inside the window? */
    internal fun inWindow8942(firstMs: Long, eventMs: Long): Boolean = eventMs - firstMs in 0L..WINDOW_MS_8942

    /** TradeTape8100.launchEvidence8750: one witnessed, live-timed print of [mint] at [eventMs]. */
    fun onPrint8942(mint: String, eventMs: Long) {
        if (!mint.trim().endsWith("pump", ignoreCase = true) || eventMs <= 0L) return
        if (counts.size > MAX_TRACKED_8942) counts.entries.removeIf { eventMs - it.value.firstMs > WINDOW_MS_8942 }
        val c = counts.computeIfAbsent(mint) { Count8942(eventMs) }
        val n = synchronized(c) { c.n += 1; c.n }
        if (!milestone8942(n)) return
        if (!inWindow8942(c.firstMs, eventMs)) { late.incrementAndGet(); return }
        fired.computeIfAbsent(n) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LAUNCH_PRINTS_8942_AT_$n") } catch (_: Throwable) {}
        try { listener8942?.invoke(mint, n) } catch (_: Throwable) {}
    }

    /** BotService: the milestone's coin was not on the watchlist; from 8945 it goes through the protected intake. */
    fun noteNotWatched8942() { notWatched.incrementAndGet() }

    private val admitted8945 = AtomicLong(0L)
    /** V5.0.8945 — BotService: an unwatched milestone launch the protected intake admitted. */
    fun noteAdmitted8945() { admitted8945.incrementAndGet() }

    fun statusLine8942(): String =
        "launchPrints8942[tracked=${counts.size} fired{${MILESTONES_8942.joinToString(",") { "$it=${fired[it]?.get() ?: 0}" }}} " +
            "late=${late.get()} notWatched=${notWatched.get()} intakeAdmitted8945=${admitted8945.get()} rule=a pump.fun launch is run through the full pipeline at its 20th, 40th " +
            "and 80th witnessed print within 15 min of its first (the 20th-print entry paid best on the launch paths); no gate bypassed]"

    internal fun resetForTest8942() { counts.clear(); fired.clear(); late.set(0L); notWatched.set(0L); admitted8945.set(0L); listener8942 = null }
}
