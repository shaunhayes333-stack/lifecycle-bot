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

    /**
     * V5.0.8654: fill ONLY the approving candidate's seal. The former fillLate
     * walked every pending version for a mint/lane, and one async assessment
     * was silently credited to unrelated approved trades. The callback must
     * carry candidateVersion from the FDG winner; timestamps alone cannot
     * establish which of several overlapping intents owned the prediction.
     */
    fun fillExact(readKey: String, version: Long, read: A, completedAtMs: Long, slackMs: Long): Boolean {
        if (version <= 0L || completedAtMs <= 0L || slackMs < 0L) return false
        val e = seals[key(readKey, version)] ?: return false
        if (completedAtMs > e.sealedAtMs + slackMs) return false
        synchronized(e) {
            if (e.read != null) return false
            e.read = read
            return true
        }
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
