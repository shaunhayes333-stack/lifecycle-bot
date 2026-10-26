package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8395 (R9) — the EXACT entry prediction sealed with the immutable execution intent.
 *
 * Cortex7885.entryReads keeps only the latest read per mint|MODE|LANE. Assessment A approves; the token is
 * reassessed (B) before the fill lands; the fill used to bind B. The FDG intent now seals the candidate version's
 * read at publication (first seal wins). The fill binds through the version: SEALED -> exactly that read; PENDING
 * (sealed, the read was still being captured) -> only a read decided no later than the seal can fill it;
 * UNSEALED -> unresolved, never the latest read.
 */
class CortexEntrySeal8395<A>(private val cap: Int = 4_000) {
    enum class State { SEALED, PENDING, UNSEALED }
    data class Resolution<A>(val state: State, val read: A?)
    class Entry<A>(@Volatile var read: A?, val sealedAtMs: Long)

    private val seals = ConcurrentHashMap<String, Entry<A>>()

    fun key(readKey: String, version: Long): String = "$readKey|v$version"

    /** First seal for (read key, version) wins; a later reassessment cannot replace it. */
    fun seal(readKey: String, version: Long, read: A?, sealedAtMs: Long): Boolean {
        if (version <= 0L || readKey.isBlank()) return false
        if (seals.size >= cap) evictOldest()
        return seals.putIfAbsent(key(readKey, version), Entry(read, sealedAtMs)) == null
    }

    /** A read captured asynchronously fills only PENDING seals it was decided for (decision <= seal + [slackMs]). */
    fun fillLate(readKey: String, read: A, decisionAtMs: Long, slackMs: Long): List<Long> {
        val out = ArrayList<Long>(1)
        val prefix = "$readKey|v"
        for ((k, e) in seals) {
            if (!k.startsWith(prefix) || e.read != null || decisionAtMs > e.sealedAtMs + slackMs) continue
            synchronized(e) { if (e.read == null) { e.read = read; k.substring(prefix.length).toLongOrNull()?.let(out::add) } }
        }
        return out
    }

    fun resolve(readKey: String, version: Long): Resolution<A> {
        val e = seals[key(readKey, version)] ?: return Resolution(State.UNSEALED, null)
        val r = e.read
        return if (r != null) Resolution(State.SEALED, r) else Resolution(State.PENDING, null)
    }

    fun release(readKey: String, version: Long) { seals.remove(key(readKey, version)) }

    fun size(): Int = seals.size

    fun snapshot(): List<Triple<String, Entry<A>, Long>> =
        seals.entries.map { Triple(it.key.substringBeforeLast("|v"), it.value, it.key.substringAfterLast("|v").toLongOrNull() ?: 0L) }

    fun restore(readKey: String, version: Long, read: A?, sealedAtMs: Long) { seal(readKey, version, read, sealedAtMs) }

    fun prune(nowMs: Long, maxAgeMs: Long) { seals.entries.removeIf { nowMs - it.value.sealedAtMs > maxAgeMs } }

    private fun evictOldest() {
        seals.entries.sortedBy { it.value.sealedAtMs }.take(cap / 10).forEach { seals.remove(it.key, it.value) }
    }
}
