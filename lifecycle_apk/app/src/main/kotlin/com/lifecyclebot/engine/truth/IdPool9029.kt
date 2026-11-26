package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9029 §ONE_STRING_PER_ID — owner, 26 Nov 19:16, 5.0.9028 after 23 min: "nope. full heaped out".
 *
 * The 12:31 heap map (26 Nov): CanonicalFinalityPersistence6486.excluded8094 10.5 MB, 5.6 MB of it the key Strings;
 * LearnerAttribution8418.ledgers 7.1 MB, 3.8 MB of it the id Strings. Both are "which closes has this consumer seen", one
 * set per consumer, and each consumer's set is read from its own stored text — so the same trade id is a separate String in
 * every consumer's set (about thirty consumers, a few thousand closes). An id is now one String shared by every set that
 * holds it: equal text, so every lookup, every stored blob and every reader is unchanged.
 *
 * The pool is bounded: past [MAX_9029] ids it is emptied (the sets keep the Strings they hold; later ids start a new
 * shared copy). Pure: no Android, no I/O.
 */
object IdPool9029 {
    internal const val MAX_9029 = 60_000
    private val pool = ConcurrentHashMap<String, String>()
    private val shared = AtomicLong()
    private val first = AtomicLong()

    /** The one shared String equal to [id]. */
    fun canon9029(id: String): String {
        val had = pool.putIfAbsent(id, id)
        if (had != null) { shared.incrementAndGet(); return had }
        first.incrementAndGet()
        if (pool.size > MAX_9029) pool.clear()
        return id
    }

    fun line9029(): String = "idPool9029[ids=${pool.size} shared=${shared.get()} first=${first.get()}]"

    internal fun resetForTest9029() { pool.clear(); shared.set(0L); first.set(0L) }
}
