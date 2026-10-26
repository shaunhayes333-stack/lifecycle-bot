package com.lifecyclebot.engine.cortex

/**
 * An asynchronous capture may publish only while it owns the exact observation.
 * Queue coalescing alone cannot order jobs that are already running on different workers.
 * The caller supplies its model lock so ownership and pending/context publication are atomic.
 * Only small identities are retained; unfinished owners are never evicted to make room.
 */
internal class CortexCaptureOwnership8397(
    private val lock: Any = Any(),
    private val capacity: Int = 8_512,
) {
    data class Ticket internal constructor(val key: String, val atMs: Long, val generation: Long)
    private data class Owner(val ticket: Ticket, var published: Boolean = false, var terminal: Boolean = false)
    private val owners = LinkedHashMap<String, Owner>()
    private var generation = 0L

    fun claim(key: String, atMs: Long, restoredAtMs: Long = 0L): Ticket? = synchronized(lock) {
        if (key.isBlank() || atMs <= 0L || atMs <= restoredAtMs) return@synchronized null
        val prior = owners[key]
        if (prior != null && atMs <= prior.ticket.atMs) return@synchronized null
        if (prior == null && !makeRoom()) return@synchronized null
        Ticket(key, atMs, ++generation).also { owners[key] = Owner(it) }
    }

    fun current(ticket: Ticket): Boolean = synchronized(lock) {
        owners[ticket.key]?.let { it.ticket == ticket && !it.terminal } == true
    }

    fun awaiting(key: String, atMs: Long): Boolean = synchronized(lock) {
        owners[key]?.let { it.ticket.atMs == atMs && !it.terminal && !it.published } == true
    }

    /** Exactly one publication, including its context and entry-read state, under the shared model lock. */
    fun publish(ticket: Ticket, block: () -> Unit): Boolean = synchronized(lock) {
        val owner = owners[ticket.key]
        if (owner == null || owner.ticket != ticket || owner.terminal || owner.published) return@synchronized false
        block()
        owner.published = true
        true
    }

    /** Release a failed preparation/worker only if it still owns this unpublished generation. */
    fun abandon(ticket: Ticket): Boolean = synchronized(lock) {
        val owner = owners[ticket.key]
        if (owner == null || owner.ticket != ticket || owner.terminal || owner.published) return@synchronized false
        owner.terminal = true
        true
    }

    /** A late worker cannot resurrect an unavailable/consumed observation, or close a newer one. */
    fun terminal(key: String, atMs: Long) = synchronized(lock) {
        if (key.isBlank() || atMs <= 0L) return@synchronized
        val prior = owners[key]
        if (prior != null && prior.ticket.atMs > atMs) return@synchronized
        if (prior?.ticket?.atMs == atMs) prior.terminal = true
        else if (prior != null || makeRoom()) owners[key] = Owner(Ticket(key, atMs, ++generation), terminal = true)
    }

    private fun makeRoom(): Boolean {
        if (owners.size < capacity) return true
        val retired = owners.entries.firstOrNull { it.value.terminal }?.key ?: return false
        owners.remove(retired)
        return true
    }

    internal fun size(): Int = synchronized(lock) { owners.size }
}
