package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — the per-subsystem memory census and the one trim dispatch that reaches every registered store.
 *
 * 5.0.8142's diag named three caches (miner keys, chart reads, the token archive) while the heap filled with stores
 * it never counted. Every resident store registers here once: an O(1) entry count, an estimated retained size
 * (entries x a per-entry estimate for this store's objects on ART, or a store-supplied estimate), and the trim the
 * memory guard runs under pressure. The guard thread computes the census every tick (never the UI thread); the
 * status line shows the largest stores, the total, queue depths and evictions, and a hard trim records which stores
 * it reached and what each freed, so an ineffective trim names the store that did not shrink.
 *
 * Pure (no Android): stores are lambdas, evaluated only when the census or a trim runs.
 */
class MemoryRegistry8144 {

    /** One registered store. [trim] null = trimmed elsewhere (the guard's own token-row / archive passes). */
    private class Structure8144(
        val name: String,
        val bytesPerEntry: Long,
        val count: () -> Int,
        val trim: ((RetentionPolicy8136.Pressure) -> Unit)?,
        val estBytes: (() -> Long)?,
    )

    /** A census row: entries (-1 = unknown), estimated retained bytes, evictions recorded so far. */
    data class Row8144(val name: String, val count: Int, val estBytes: Long, val evictions: Long)

    /**
     * What one trim did to one store. [reached] false = its trim threw (or it has none).
     * V5.0.8309 — [beforeBytes] / [afterBytes]: the store's estimated retained bytes around the trim (-1 = unknown), so a
     * hard trim that "freed nothing" names, per store, the MB it actually released; [exempt] = a store marked fixed /
     * ledger / queue / transient (by design never shrunk by a pressure trim) is not reported as unfreed.
     */
    data class TrimResult8144(
        val name: String, val before: Int, val after: Int, val reached: Boolean,
        val beforeBytes: Long = -1L, val afterBytes: Long = -1L, val exempt: Boolean = false,
    ) {
        val freed: Int get() = if (before >= 0 && after >= 0) (before - after).coerceAtLeast(0) else 0
        val freedBytes: Long get() = if (beforeBytes >= 0L && afterBytes >= 0L) (beforeBytes - afterBytes).coerceAtLeast(0L) else 0L
    }

    private val structures = CopyOnWriteArrayList<Structure8144>()
    private val evictions = ConcurrentHashMap<String, AtomicLong>()
    private val trimsRun = AtomicLong(0)

    /** Register (or replace, by name) one store. */
    fun register8144(
        name: String,
        bytesPerEntry: Long,
        count: () -> Int,
        trim: ((RetentionPolicy8136.Pressure) -> Unit)? = null,
        estBytes: (() -> Long)? = null,
    ) {
        structures.removeAll { it.name == name }
        structures += Structure8144(name, bytesPerEntry.coerceAtLeast(0L), count, trim, estBytes)
    }

    fun names8144(): List<String> = structures.map { it.name }

    /** Stores with no trim of their own here (V5.0.8309: a store marked with a kind is exempt by design, not untrimmed). */
    fun untrimmed8144(): List<String> = structures.filter { it.trim == null && !kinds8309.containsKey(it.name) }.map { it.name }

    // ── V5.0.8309 — stores a pressure trim must not shrink, by design ──
    // "fixed" (preallocated at capacity), "ledger" (learning evidence / outcome stamps: never reset by memory pressure),
    // "queue" (queued work: dropping it drops a save or a side effect), "transient" (bytes in flight right now).
    private val kinds8309 = ConcurrentHashMap<String, String>()

    /** Mark [name] as exempt from pressure trims with [kind] (fixed / ledger / queue / transient / capped). */
    fun markKind8309(name: String, kind: String) { kinds8309[name] = kind }

    /** "name(kind)" for every exempt store, in registration order. */
    fun exempt8309(): List<String> = structures.mapNotNull { s -> kinds8309[s.name]?.let { "${s.name}($it)" } }

    private fun bytesOf8309(s: Structure8144, n: Int): Long {
        val est = s.estBytes?.let { f -> try { f().coerceAtLeast(0L) } catch (_: Throwable) { -1L } } ?: -1L
        return if (est >= 0L) est else if (n > 0) n.toLong() * s.bytesPerEntry else if (n == 0) 0L else -1L
    }

    /** A store evicted [n] entries on its own (insert-time caps). */
    fun noteEvictions8144(name: String, n: Long) {
        if (n > 0L) evictions.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(n)
    }

    fun evictions8144(name: String): Long = evictions[name]?.get() ?: 0L

    fun trimsRun8144(): Long = trimsRun.get()

    private fun countOf(s: Structure8144): Int = try { s.count() } catch (_: Throwable) { -1 }

    /** The census now: one row per registered store (counts are O(1); a store's own estimate may walk it). */
    fun census8144(): List<Row8144> = structures.map { s ->
        val n = countOf(s)
        val est = s.estBytes?.let { f -> try { f().coerceAtLeast(0L) } catch (_: Throwable) { -1L } } ?: -1L
        val bytes = if (est >= 0L) est else if (n > 0) n.toLong() * s.bytesPerEntry else 0L
        Row8144(s.name, n, bytes, evictions8144(s.name))
    }

    /**
     * Run every registered trim at [p]. A store whose trim throws is reported unreached and the rest still run;
     * entries a trim freed are booked as that store's evictions.
     */
    fun trimAll8144(p: RetentionPolicy8136.Pressure): List<TrimResult8144> {
        trimsRun.incrementAndGet()
        return structures.map { s ->
            val before = countOf(s)
            val beforeBytes = bytesOf8309(s, before)   // V5.0.8309
            val t = s.trim
            val reached = if (t == null) false else try { t(p); true } catch (_: Throwable) { false }
            val after = countOf(s)
            val r = TrimResult8144(s.name, before, after, reached, beforeBytes, bytesOf8309(s, after), kinds8309.containsKey(s.name))
            noteEvictions8144(s.name, r.freed.toLong())
            r
        }
    }

    companion object {
        /**
         * Pure (V5.0.8309): what the last trim released, per store, as "total=xMB [name:beforeKB->afterKB,...]" for the
         * [top] stores that released the most (then the largest that released nothing), so the report says which stores a
         * hard trim actually shrank and by how much.
         */
        fun trimMbLine8309(results: List<TrimResult8144>, top: Int = 8): String {
            if (results.isEmpty()) return "-"
            val total = results.sumOf { it.freedBytes }
            val shown = results.filter { it.beforeBytes > 0L }
                .sortedWith(compareByDescending<TrimResult8144> { it.freedBytes }.thenByDescending { it.beforeBytes }.thenBy { it.name })
                .take(top.coerceAtLeast(0))
                .joinToString(",") { "${it.name}:${it.beforeBytes / 1024}->${(if (it.afterBytes >= 0L) it.afterBytes else it.beforeBytes) / 1024}KB" }
            return "total=${mb(total)} [$shown]"
        }

        /** Pure: total estimated bytes of a census. */
        fun totalBytes8144(rows: List<Row8144>): Long = rows.sumOf { it.estBytes.coerceAtLeast(0L) }

        private fun mb(b: Long): String = if (b >= 10L shl 20) "${b shr 20}MB" else "${"%.1f".format(b / 1048576.0)}MB"

        /** Pure: "est=123MB stores=14 top[name:n/xMB ev=k,...]" for the [top] largest stores. */
        fun line8144(rows: List<Row8144>, top: Int = 8): String {
            if (rows.isEmpty()) return "-"
            val shown = rows.sortedWith(compareByDescending<Row8144> { it.estBytes }.thenBy { it.name }).take(top.coerceAtLeast(0))
                .joinToString(",") { r ->
                    "${r.name}:${if (r.count >= 0) r.count.toString() else "?"}/${mb(r.estBytes)}" + (if (r.evictions > 0) " ev=${r.evictions}" else "")
                }
            return "est=${mb(totalBytes8144(rows))} stores=${rows.size} top[$shown]"
        }

        /**
         * Pure: the stores a trim left large: unreached, or reached but freed nothing, ordered by estimated size
         * (the stores to look at when a hard trim recovered no headroom).
         */
        fun unfreed8144(results: List<TrimResult8144>, rows: List<Row8144>, top: Int = 5): List<String> {
            val bytes = rows.associate { it.name to it.estBytes }
            return results.filter { !it.exempt && (!it.reached || it.freed == 0) }
                .sortedWith(compareByDescending<TrimResult8144> { bytes[it.name] ?: 0L }.thenBy { it.name })
                .take(top.coerceAtLeast(0))
                .map { if (it.reached) it.name else "${it.name}(unreached)" }
        }
    }
}
