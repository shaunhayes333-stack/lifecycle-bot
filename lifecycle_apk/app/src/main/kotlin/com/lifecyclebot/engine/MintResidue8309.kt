package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8309 — per-mint residue: the caches a token leaves behind after its row is gone.
 *
 * 5.0.8306 ran at 473/512 MB (peak 100%, HARD pressure) with 406 MB the census could not attribute, and hard trims freed
 * nothing. The guard prunes token rows (MemoryGuard7977.pruneTokens8025 / prunePressure8136), but a pruned row was only
 * removed from BotService.status.tokens: every per-mint cache the evaluation path had filled for that mint stayed. Several
 * of those caches are written for every evaluated mint and never removed by anything — their "clear" / "cleanup" helpers
 * have no caller (SmartChartScanner.clearCache, RegimeTransitionAI.clearHistory, DistributionDetector.clearHistory,
 * VolatilityRegimeAI / OrderFlowImbalanceAI / SmartMoneyDivergenceAI / DistributionFadeAvoider / DataPipeline.cleanup).
 * With a few thousand mints an hour passing through the 300-row token map, each leaving ~5-25 KB (a 200-candle chart copy,
 * three 100-sample flow deques, 50 regime snapshots, ...), this grows by tens of MB an hour and no trim reaches it.
 *
 * Each such cache registers its map here. A sweep removes every key whose mint is not live (not a token row, not watched,
 * not open, not wallet-tracked, not an active admission / runner ticket / hand-off: the caller's predicate). These are
 * caches and short histories of the evaluation path, never learning ledgers, positions, cooldowns or safety evidence; a
 * mint that comes back simply rebuilds them. Pure (no Android): stores are lambdas, the predicate is supplied.
 */
object MintResidue8309 {

    /** One per-mint cache: its map, an estimate of bytes per entry (or its own estimate). */
    private class Store8309(
        val name: String,
        val bytesPerEntry: Long,
        val map: () -> ConcurrentHashMap<String, *>?,
        val estBytes: (() -> Long)?,
    )

    private val stores = CopyOnWriteArrayList<Store8309>()
    private val swept = ConcurrentHashMap<String, AtomicLong>()
    private val sweeps = AtomicLong(0L)
    @Volatile private var lastSweepMs = 0L
    @Volatile private var lastSwept = 0

    /** Register (or replace, by name) one per-mint cache. Keys are mints, or "mint|..." (the mint is the part before '|'). */
    fun register8309(name: String, bytesPerEntry: Long, map: () -> ConcurrentHashMap<String, *>?, estBytes: (() -> Long)? = null) {
        stores.removeAll { it.name == name }
        stores += Store8309(name, bytesPerEntry.coerceAtLeast(0L), map, estBytes)
    }

    fun names8309(): List<String> = stores.map { it.name }

    /** Pure: the mint a residue key belongs to ("mint" or "mint|suffix"). */
    fun mintOf8309(key: String): String = key.substringBefore('|')

    /**
     * Remove every key of [map] whose mint is not [isLive]. A predicate that throws keeps the key (never drop on doubt).
     * Returns the keys removed. Safe on a ConcurrentHashMap while writers run (weakly consistent iterator).
     */
    fun sweepMap8309(map: ConcurrentHashMap<String, *>, isLive: (String) -> Boolean): Int {
        var n = 0
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            val live = try { isLive(mintOf8309(k)) } catch (_: Throwable) { true }
            if (!live) { it.remove(); n++ }
        }
        return n
    }

    /**
     * Hard cap on write for a per-mint cache: past [cap] keys, non-live mints go first (any order), then — if still over —
     * arbitrary keys down to 90% of [cap] except live ones. Returns keys removed. O(1) while at or under the cap.
     */
    fun capMap8309(map: ConcurrentHashMap<String, *>, cap: Int, isLive: (String) -> Boolean = { false }): Int {
        if (map.size <= cap) return 0
        val before = map.size
        val target = (cap.coerceAtLeast(0) * 9 / 10)
        val it = map.keys.iterator()
        while (map.size > target && it.hasNext()) {
            val k = it.next()
            val live = try { isLive(mintOf8309(k)) } catch (_: Throwable) { true }
            if (!live) it.remove()
        }
        return (before - map.size).coerceAtLeast(0)
    }

    /** Sweep one registered cache by [name]. Returns the keys removed (0 for an unknown store). */
    fun sweepOne8309(name: String, isLive: (String) -> Boolean): Int {
        val s = stores.firstOrNull { it.name == name } ?: return 0
        val m = try { s.map() } catch (_: Throwable) { null } ?: return 0
        val n = try { sweepMap8309(m, isLive) } catch (_: Throwable) { 0 }
        if (n > 0) { swept.computeIfAbsent(name) { AtomicLong(0L) }.addAndGet(n.toLong()); lastSwept = n }
        return n
    }

    /** Sweep every registered cache. Returns the keys removed in total. */
    fun sweep8309(isLive: (String) -> Boolean, nowMs: Long = System.currentTimeMillis()): Int {
        var total = 0
        for (s in stores) {
            val m = try { s.map() } catch (_: Throwable) { null } ?: continue
            val n = try { sweepMap8309(m, isLive) } catch (_: Throwable) { 0 }
            if (n > 0) { swept.computeIfAbsent(s.name) { AtomicLong(0L) }.addAndGet(n.toLong()); total += n }
        }
        sweeps.incrementAndGet()
        lastSweepMs = nowMs
        lastSwept = total
        return total
    }

    /** Pure: is a periodic (no-pressure) sweep due? */
    fun sweepDue8309(lastMs: Long, nowMs: Long, periodMs: Long = SWEEP_PERIOD_MS_8309): Boolean = lastMs <= 0L || nowMs - lastMs >= periodMs

    const val SWEEP_PERIOD_MS_8309 = 120_000L

    fun lastSweepMs8309(): Long = lastSweepMs

    /** Keys held by the registered caches now. */
    fun count8309(): Int = stores.sumOf { s -> try { s.map()?.size ?: 0 } catch (_: Throwable) { 0 } }

    /** Estimated retained bytes of one registered cache (-1 = unknown store). */
    fun bytesOf8309(name: String): Long {
        val s = stores.firstOrNull { it.name == name } ?: return -1L
        return bytesOfStore(s)
    }

    fun countOf8309(name: String): Int {
        val s = stores.firstOrNull { it.name == name } ?: return -1
        return try { s.map()?.size ?: -1 } catch (_: Throwable) { -1 }
    }

    private fun bytesOfStore(s: Store8309): Long {
        val own = s.estBytes?.let { f -> try { f().coerceAtLeast(0L) } catch (_: Throwable) { -1L } } ?: -1L
        if (own >= 0L) return own
        val n = try { s.map()?.size ?: 0 } catch (_: Throwable) { 0 }
        return n.toLong() * s.bytesPerEntry
    }

    /** Estimated retained bytes of every registered cache. */
    fun bytes8309(): Long = stores.sumOf { bytesOfStore(it) }

    /** "residue8309[keys= estKB= sweeps= lastSwept= swept=[name:n,..]]" for the memory line. */
    fun line8309(): String =
        "residue8309[keys=${count8309()} estKB=${bytes8309() / 1024} sweeps=${sweeps.get()} lastSwept=$lastSwept " +
            "swept=[${swept.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}:${it.value.get()}" }}]]"
}
