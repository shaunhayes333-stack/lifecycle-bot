package com.lifecyclebot.engine.market

import kotlin.math.abs

/** Candidate routing only. Signed executed swaps are shared across desks, independently of lane refusals. */
object KineticFlow8506 {
    data class Swap(val mint: String, val venue: String, val signature: String, val buyer: String,
        val isBuy: Boolean, val sol: Double, val qty: Double, val atMs: Long, val slot: Long = 0,
        val sponsor: String = "", val tip: Long = 0, val sourceTime: Boolean = false, val pool: String = "")

    data class Vector(val buyers: Int, val netSol: Double, val priceMove: Double, val accelerating: Boolean,
        val linkedBuyers: Int, val tippingBuyers: Int, val maxTipLamports: Long, val mentionGapMs: Long?,
        val venue: String, val priceSol: Double, val trackedBuyers: Int, val fundedBuyers: Int,
        val recentMovePct: Double? = null, val pool: String = "") {
        // A burst can still be up over ten seconds after it has already turned down.
        val active get() = buyers >= 3 && netSol > 0 && priceMove > 0 && accelerating &&
            (recentMovePct == null || recentMovePct > 0.0)
        private val distributing get() = netSol < 0.0 && (recentMovePct ?: 0.0) < 0.0

        fun scannerRankPoints(): Int = when {
            active && maxOf(linkedBuyers, fundedBuyers) < 5 -> 12
            distributing -> -12
            else -> 0
        }
        fun fadingFor(lane: String): Boolean = distributing && lane in momentumLanes
        fun rankMultiplier(lane: String): Double {
            if (fadingFor(lane)) return 0.88
            if (lane !in readers()) return 1.0
            // Independent participation carries the rank. Transaction tips and a dated mention only corroborate it.
            return 1.12 + (if (tippingBuyers >= 2 && maxTipLamports >= 100_000L) 0.06 else 0.0) +
                (if (mentionGapMs != null) 0.04 else 0.0) + (if (trackedBuyers >= 2) 0.03 else 0.0)
        }
        fun readers(): Set<String> {
            if (!active) return emptySet()
            if (maxOf(linkedBuyers, fundedBuyers) >= 5) return setOf("MANIPULATED")
            val readers = mutableSetOf("EXPRESS", "MOONSHOT", "SHITCOIN", "CORE")
            if (venue == "CURVE") readers.add("PROJECT_SNIPER")
            else if (venue == "RAYDIUM" || venue == "PUMPSWAP") readers.add("CASHGEN")
            return readers
        }
    }

    private val momentumLanes = setOf("MOONSHOT", "EXPRESS", "SHITCOIN")

    private class Window {
        val swaps = LinkedHashMap<String, Swap>()
        var lastWake = Long.MIN_VALUE
        var lastVector: Vector? = null
    }
    private fun <T> bounded(cap: Int) = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > cap
    }
    private val windows = bounded<Window>(512)
    private val mentions = bounded<Long>(1024)
    private data class Funding(val from: String, val atMs: Long, val slot: Long)
    private val funding = bounded<Funding>(2048)
    @Volatile var trackedWallet: (String) -> Boolean = { false }
    @Synchronized fun noteFunding(sender: String, recipient: String, atMs: Long, slot: Long) {
        if (sender.isNotBlank() && recipient.isNotBlank() && sender != recipient && atMs >= (funding[recipient]?.atMs ?: 0L))
            funding[recipient] = Funding(sender, atMs, slot)
    }
    @Synchronized fun reset() {
        windows.clear(); mentions.clear(); funding.clear(); wakeSecond = 0; wakeCount = 0
        onWave = null; ownWallet = ""; trackedWallet = { false }
    }
    @Volatile var ownWallet: String = ""
    private var wakeSecond = 0L
    private var wakeCount = 0
    @Volatile var onWave: ((String) -> Unit)? = null
    @Synchronized fun mention(mint: String, publishedMs: Long, now: Long) {
        if (publishedMs > 0 && now - publishedMs in 0L..30_000L && publishedMs > (mentions[mint] ?: 0L))
            mentions[mint] = publishedMs
    }

    fun observe(s: Swap, now: Long = System.currentTimeMillis()) {
        if (s.buyer == ownWallet || s.signature.isBlank() || s.mint.isBlank() || s.buyer.isBlank() || !s.sol.isFinite() || s.sol <= 0 ||
            !s.qty.isFinite() || s.qty <= 0 || now - s.atMs !in 0L..15_000L) return
        val wake = synchronized(this) {
            val w = windows.getOrPut(s.mint) { Window() }
            val key = s.signature + "|" + s.venue + "|" + s.buyer + "|" + s.isBuy
            val old = w.swaps[key]
            if (old == null) w.swaps[key] = s
            else {
                if (old.sol != s.sol && abs(old.sol - s.sol) > 0.000001 * maxOf(old.sol, s.sol)) return
                w.swaps[key] = old.copy(slot = old.slot.takeIf { it > 0 } ?: s.slot,
                    sponsor = old.sponsor.ifBlank { s.sponsor }, tip = maxOf(old.tip, s.tip),
                    atMs = if (!old.sourceTime && s.sourceTime) s.atMs else old.atMs,
                    sourceTime = old.sourceTime || s.sourceTime, pool = old.pool.ifBlank { s.pool })
                if (w.swaps[key] == old) return
            }
            if (w.swaps.size > 96) w.swaps.entries.iterator().apply { next(); remove() }
            val v = vector(w, mentions[s.mint], now)
            w.lastVector = v
            if (now / 1000L != wakeSecond) { wakeSecond = now / 1000L; wakeCount = 0 }
            if (wakeCount < 4 && v?.active == true && (w.lastWake == Long.MIN_VALUE || now - w.lastWake >= 5_000L)) {
                w.lastWake = now
                wakeCount++
                true
            } else false
        }
        if (wake) onWave?.invoke(s.mint)
    }

    @Synchronized fun current(mint: String, now: Long): Vector? {
        val w = windows[mint] ?: return null
        return if (w.lastVector != null) vector(w, mentions[mint], now) else null
    }

    private fun vector(w: Window, mention: Long?, now: Long): Vector? {
        val last = w.swaps.values.maxByOrNull { it.atMs } ?: return null
        if (now - last.atMs !in 0L..5_000L) return null
        val pool = last.pool.ifBlank { w.swaps.values.lastOrNull { it.venue == last.venue && it.pool.isNotBlank() }?.pool.orEmpty() }
        val sample = w.swaps.values.filter { it.venue == last.venue && (pool.isBlank() || it.pool.isBlank() || it.pool == pool) && now - it.atMs in 0L..10_000L }
        val recent = sample.filter { now - it.atMs < 5_000L }
        val prev = sample.filter { now - it.atMs >= 5_000L }
        val buys = recent.filter { it.isBuy }
        val highSlot = sample.maxOfOrNull { it.slot } ?: 0L
        val sameSlots = buys.filter { it.slot > 0 && highSlot - it.slot in 0L..2L }
        val funded = sameSlots.mapNotNull { e -> funding[e.buyer]?.takeIf { now - it.atMs in 0L..1_800_000L && it.slot > 0L && e.slot > it.slot }?.let { it.from to e.buyer } }
            .groupBy { it.first }.values.maxOfOrNull { it.distinctBy { link -> link.second }.size } ?: 0
        val clustered = sameSlots.filter { it.sponsor.isNotBlank() && it.sponsor != it.buyer }
            .groupBy { it.sponsor }.values.maxOfOrNull { group -> group.distinctBy { it.buyer }.size } ?: 0
        val first = sample.minByOrNull { it.atMs } ?: return null
        val move = 100.0 * ((last.sol / last.qty) / (first.sol / first.qty) - 1)
        val gap = mention?.let { at -> buys.filter { it.sourceTime }.minOfOrNull { abs(it.atMs - at) } }?.takeIf { it <= 3_000L }
        // An unlabelled swap cannot establish direction across two pools for the same mint.
        val prices = if (pool.isBlank()) recent else recent.filter { it.pool == pool }
        val recentFirst = prices.minByOrNull { it.atMs }
        val recentLast = prices.maxByOrNull { it.atMs }
        val recentMove = if (recentFirst != null && recentLast != null && recentLast.atMs == last.atMs &&
            recentLast.atMs - recentFirst.atMs >= 1_000L && prices.distinctBy { it.signature }.size >= 3) {
            (100.0 * ((recentLast.sol / recentLast.qty) / (recentFirst.sol / recentFirst.qty) - 1.0)).takeIf { it.isFinite() }
        } else null
        return Vector(buys.distinctBy { it.buyer }.size, recent.sumOf { if (it.isBuy) it.sol else -it.sol }, move,
            buys.size >= 3 && buys.size > prev.count { it.isBuy } && buys.sumOf { it.sol } > prev.filter { it.isBuy }.sumOf { it.sol },
            clustered, buys.filter { it.tip > 0 }.distinctBy { it.buyer }.size, buys.maxOfOrNull { it.tip } ?: 0, gap, last.venue, last.sol / last.qty,
            sameSlots.map { it.buyer }.distinct().count(trackedWallet), funded, recentMove, pool)
    }
}
