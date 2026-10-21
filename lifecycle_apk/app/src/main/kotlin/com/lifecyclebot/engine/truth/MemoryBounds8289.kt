package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8289 §HARD_CAP_PLUS_TTL_ON_WRITE — the bound every 8283-8286 store applies on write.
 *
 * 5.0.8286: heap 172 MB at 7 min, 511/512 MB at 19 min (pressure CRITICAL, 3 of 9 hard trims ineffective) with
 * census 63 MB / unattributed 448 MB. Several new stores evicted only "entries older than the TTL once above the cap":
 * a burst of fresh keys (every print, every curve tick, every candidate) kept such a map above its cap with nothing to
 * evict, so the cap never held. [capByAge8289] first drops what the TTL already expired, then — if the map is still
 * over [cap] — the oldest entries down to 90% of it, so the size is bounded whatever the arrival rate.
 *
 * [intern8289] shares the few distinct venue / feed strings a per-trade record repeats (a pool address, a feed name)
 * instead of one parsed copy per trade. Nothing here reads or decides anything.
 */
object MemoryBounds8289 {

    /**
     * Bound [map] (a concurrent map) to [cap] entries: entries whose [atOf] is more than [ttlMs] before [nowMs] go
     * first, then the oldest until the map holds at most 90% of [cap]. Returns the entries removed. A map at or under
     * [cap] is not touched (O(1)).
     */
    fun <K : Any, V : Any> capByAge8289(map: ConcurrentHashMap<K, V>, cap: Int, ttlMs: Long, nowMs: Long, atOf: (V) -> Long): Int {
        if (map.size <= cap) return 0
        val before = map.size
        try { map.entries.removeIf { nowMs - atOf(it.value) > ttlMs } } catch (_: Throwable) {}
        if (map.size > cap) {
            val target = (cap * 9 / 10).coerceAtLeast(0)
            val oldest = try { map.entries.map { it.key to atOf(it.value) } } catch (_: Throwable) { emptyList() }
            val drop = (map.size - target).coerceAtLeast(0)
            for ((k, _) in oldest.sortedBy { it.second }.take(drop)) map.remove(k)
        }
        return (before - map.size).coerceAtLeast(0)
    }

    /** Remove the eldest entries of an insertion / access-ordered synchronized map until it holds at most [target]. */
    fun <K, V> trimEldest8289(map: MutableMap<K, V>, target: Int): Int {
        var removed = 0
        synchronized(map) {
            val it = map.entries.iterator()
            while (map.size > target.coerceAtLeast(0) && it.hasNext()) { it.next(); it.remove(); removed++ }
        }
        return removed
    }

    internal const val INTERN_CAP_8289 = 4_096
    private val interned = ConcurrentHashMap<String, String>()

    /** The shared instance of [s] (bounded table; a full table starts over — earlier holders keep their copy). */
    fun intern8289(s: String): String {
        if (s.isEmpty()) return ""
        interned[s]?.let { return it }
        if (interned.size >= INTERN_CAP_8289) interned.clear()
        return interned.putIfAbsent(s, s) ?: s
    }

    internal fun internCount8289(): Int = interned.size
}
