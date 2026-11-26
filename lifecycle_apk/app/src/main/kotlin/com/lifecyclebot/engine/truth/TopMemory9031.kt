package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9031 §THE_TOP_IS_REMEMBERED — the 26 Nov 21:03 report: the two live buys of the session were coins that had already
 * run that morning. GFmaQA ran from a $23k cap at 07:36 to $301k at 08:02 (the bot's own dataset rows) and was bought at
 * 20:54 at $6.5k, 46 times under its top, and stopped out 61 s later; Grodia topped near $1.7M at 10:52 and was bought at
 * $424k. The owner's rule for this already exists — FlatChart8027 DEAD_RUNNER: "never buy a dead chart", a coin down to a
 * fifth of its own top is not bought — but it took the top from the candles held in memory, and a restart (or a candle
 * window of a few hours) forgets a run from the morning. On the same dataset the coins furthest under a known top are the
 * worst cell there is (60 %+ under: n484, -8.9 % +-2.6, 5 % winners), and the top was unknown for 57 % of the rows.
 *
 * This keeps each coin's top across restarts: the highest price and the highest market cap the bot has itself accepted for
 * the coin, each as the SECOND-highest minute it has seen (two different minutes must have printed at or above it, so one
 * rogue print cannot call a coin dead, as 8027's second-highest bar). The dead-runner rule reads it beside the candles; it
 * calls a coin dead from memory only when BOTH the price and the cap are [FlatChart8027.DEAD_RATIO] or more under their
 * remembered tops, so a wrong-unit price or a wrong supply on its own proves nothing. Nothing else reads it: no size, no
 * score, no exit. [MAX_9031] coins, the least recently seen dropped first; saved at most every [SAVE_GAP_MS_9031].
 */
object TopMemory9031 {
    internal const val MAX_9031 = 6_000
    internal const val SAVE_GAP_MS_9031 = 5L * 60_000L
    private const val KEY_9031 = "TOP_MEMORY_9031"

    /** One coin: the minute of its highest print, the highest and second-highest minute of price and of cap. */
    class Top9031(var minute: Long, var p1: Double, var p2: Double, var c1: Double, var c2: Double)

    private val tops = object : LinkedHashMap<String, Top9031>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Top9031>?): Boolean = size > MAX_9031
    }
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var lastSaveMs = 0L
    private val saving = AtomicBoolean(false)
    private val deadByMemory = AtomicLong(0L)
    private val notes = AtomicLong(0L)

    private fun ok(v: Double) = v.isFinite() && v > 0.0

    /** Pure: [t] after a print of [price] / [cap] in [minute]. A value that is not a positive number leaves its side unchanged. */
    fun step9031(t: Top9031?, price: Double, cap: Double, minute: Long): Top9031 {
        val x = t ?: Top9031(minute, 0.0, 0.0, 0.0, 0.0)
        val sameMinute = x.minute == minute
        if (ok(price)) {
            if (sameMinute) { if (price > x.p1) x.p1 = price }
            else if (price > x.p1) { x.p2 = x.p1; x.p1 = price }
            else if (price > x.p2) x.p2 = price
        }
        if (ok(cap)) {
            if (sameMinute) { if (cap > x.c1) x.c1 = cap }
            else if (cap > x.c1) { x.c2 = x.c1; x.c1 = cap }
            else if (cap > x.c2) x.c2 = cap
        }
        // the minute follows the highest print of either side: a later, lower minute confirms it (fills the second place)
        if (!sameMinute && ((ok(price) && price >= x.p1) || (ok(cap) && cap >= x.c1))) x.minute = minute
        return x
    }

    /** Pure: is a coin at [nowPrice] / [nowCap] dead against its remembered tops [t]? Both sides must say so. */
    fun deadByMemory9031(t: Top9031?, nowPrice: Double, nowCap: Double, ratio: Double): Boolean {
        if (t == null || !ok(nowPrice) || !ok(nowCap) || !ok(t.p2) || !ok(t.c2)) return false
        return t.p2 / nowPrice >= ratio && t.c2 / nowCap >= ratio
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (!(try { LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(tops) {
            if (loaded) return
            try { decode9031(LearningPersistence.load(KEY_9031)).forEach { (k, v) -> if (!tops.containsKey(k)) tops[k] = v } } catch (_: Throwable) {}
            loaded = true
        }
    }

    /** A price / cap the bot has accepted for [mint] (LearnerColumns8798: every decision's own facts). */
    fun note9031(mint: String, price: Double, cap: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || (!ok(price) && !ok(cap))) return
        ensureLoaded()
        synchronized(tops) {
            val before = tops[mint]
            val p1 = before?.p1 ?: 0.0; val p2 = before?.p2 ?: 0.0; val c1 = before?.c1 ?: 0.0; val c2 = before?.c2 ?: 0.0
            val after = step9031(before, price, cap, nowMs / 60_000L)
            if (before == null) tops[mint] = after
            if (before == null || after.p1 != p1 || after.p2 != p2 || after.c1 != c1 || after.c2 != c2) dirty = true
        }
        notes.incrementAndGet()
        maybeSave(nowMs)
    }

    /** FlatChart8027: is [mint] a dead runner by its remembered tops? Counted when it is. */
    fun dead9031(mint: String, nowPrice: Double, nowCap: Double, ratio: Double, count: Boolean = true): Boolean {
        ensureLoaded()
        val dead = synchronized(tops) { deadByMemory9031(tops[mint], nowPrice, nowCap, ratio) }
        if (dead && count) deadByMemory.incrementAndGet()
        return dead
    }

    /** "mint,minute,p1,p2,c1,c2;…" */
    internal fun encode9031(rows: Map<String, Top9031>): String {
        val sb = StringBuilder(rows.size * 96)
        for ((k, v) in rows) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(k).append(',').append(v.minute).append(',').append(v.p1).append(',').append(v.p2).append(',').append(v.c1).append(',').append(v.c2)
        }
        return sb.toString()
    }

    internal fun decode9031(raw: String?): Map<String, Top9031> {
        val out = LinkedHashMap<String, Top9031>()
        if (raw.isNullOrBlank()) return out
        for (row in raw.split(';')) {
            val f = row.split(',')
            if (f.size != 6 || f[0].isBlank()) continue
            val minute = f[1].toLongOrNull() ?: continue
            val p1 = f[2].toDoubleOrNull() ?: continue; val p2 = f[3].toDoubleOrNull() ?: continue
            val c1 = f[4].toDoubleOrNull() ?: continue; val c2 = f[5].toDoubleOrNull() ?: continue
            if (!(p1 >= 0.0 && p2 >= 0.0 && c1 >= 0.0 && c2 >= 0.0) || !p1.isFinite() || !c1.isFinite()) continue
            out[f[0]] = Top9031(minute, p1, p2, c1, c2)
        }
        return out
    }

    private fun maybeSave(nowMs: Long) {
        if (!dirty || !loaded || nowMs - lastSaveMs < SAVE_GAP_MS_9031) return
        if (!saving.compareAndSet(false, true)) return
        lastSaveMs = nowMs
        try {
            Thread({
                try {
                    val text = synchronized(tops) { dirty = false; encode9031(tops) }
                    LearningPersistence.save(KEY_9031, text)
                } catch (_: Throwable) { dirty = true } finally { saving.set(false) }
            }, "top-memory-9031").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { saving.set(false) }
    }

    fun line9031(): String = "topMemory9031[coins=${synchronized(tops) { tops.size }} notes=${notes.get()} deadByMemory=${deadByMemory.get()} loaded=$loaded " +
        "rule=a coin's top is the second-highest minute of the price and of the cap the bot has accepted for it, kept across restarts; " +
        "DEAD_RUNNER_8027 calls a coin dead from it only when both are ${com.lifecyclebot.engine.FlatChart8027.DEAD_RATIO.toInt()}x or more under their tops]"

    internal fun resetForTest9031() {
        synchronized(tops) { tops.clear() }
        loaded = false; dirty = false; lastSaveMs = 0L; deadByMemory.set(0L); notes.set(0L)
    }
}
