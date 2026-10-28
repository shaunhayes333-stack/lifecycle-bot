package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §DAISY_CHAIN — the council's shared answer cache, keyed by a hash of the exact prompt, with a TTL.
 *
 * The same prompt asked twice inside the TTL is answered from here without spending any provider's budget, and the
 * council members that finish AFTER the first answer was taken land here instead of being cancelled — their work
 * (and their health sample) is kept. A miss is a miss: nothing here invents an answer.
 */
object LlmResponseCache8418 {
    const val TTL_MS_8418 = 90_000L
    private const val MAX_ENTRIES_8418 = 256

    private class Entry8418(val text: String, val atMs: Long, val provider: String)

    private val entries = ConcurrentHashMap<String, Entry8418>()
    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)
    private val lateFills = AtomicLong(0L)

    /** Pure: SHA-256 of the full prompt (system, user, token cap), hex. */
    fun key8418(system: String, user: String, maxTokens: Int): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$maxTokens\u0000$system\u0000$user".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    /** A live answer for [key], or null when absent or older than the TTL. */
    fun get8418(key: String, nowMs: Long): String? {
        val e = entries[key]
        if (e == null || nowMs - e.atMs >= TTL_MS_8418 || nowMs < e.atMs) {
            if (e != null) entries.remove(key, e)
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        return e.text
    }

    /** Stores [text] unless a live answer is already there. [late] marks a member that finished after the winner. */
    fun put8418(key: String, text: String, provider: String, nowMs: Long, late: Boolean = false) {
        if (text.isBlank()) return
        val prior = entries[key]
        if (prior != null && nowMs - prior.atMs < TTL_MS_8418) return
        entries[key] = Entry8418(text, nowMs, provider)
        if (late) lateFills.incrementAndGet()
        if (entries.size > MAX_ENTRIES_8418) evict8418(nowMs)
    }

    private fun evict8418(nowMs: Long) {
        entries.entries.removeIf { nowMs - it.value.atMs >= TTL_MS_8418 }
        while (entries.size > MAX_ENTRIES_8418) {
            val oldest = entries.entries.minByOrNull { it.value.atMs } ?: break
            entries.remove(oldest.key, oldest.value)
        }
    }

    fun statusLine8418(): String =
        "llmCache8418: entries=${entries.size} hits=${hits.get()} misses=${misses.get()} lateFills=${lateFills.get()} ttl=${TTL_MS_8418 / 1000}s"

    fun resetForTests8418() { entries.clear(); hits.set(0L); misses.set(0L); lateFills.set(0L) }
}
