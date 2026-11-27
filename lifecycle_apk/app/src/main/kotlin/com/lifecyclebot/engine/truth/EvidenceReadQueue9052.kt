package com.lifecyclebot.engine.truth

/** Bounded priority queue for evidence reads, not trading authority. One queued/running item per key. */
internal class EvidenceReadQueue9052<T>(private val capacity: Int = 128,
                                        private val heldReserve: Int = 16, private val urgentReserve: Int = 32) {
    init { require(capacity > 0 && heldReserve >= 0 && urgentReserve >= 0 && heldReserve + urgentReserve < capacity) }
    enum class Result { QUEUED, PROMOTED, COALESCED, FULL }
    data class Work<T>(val key: String, val priority: Int, val enqueuedNs: Long, val ttlMs: Long, val value: T) {
        fun expired(nowNs: Long): Boolean = nowNs - enqueuedNs >= ttlMs * 1_000_000L
    }
    private val lock = java.lang.Object()
    private val waiting = LinkedHashMap<String, Work<T>>()
    private val running = HashSet<String>()

    fun offer(key: String, priority: Int, nowNs: Long, ttlMs: Long, value: T): Result = synchronized(lock) {
        require(key.isNotBlank() && priority in 0..4 && ttlMs in 1L..60_000L)
        if (key in running) return@synchronized Result.COALESCED
        val old = waiting[key]
        if (old != null) {
            if (priority < old.priority) {
                waiting[key] = Work(key, priority, old.enqueuedNs, minOf(old.ttlMs, ttlMs), value)
                lock.notifyAll()
                return@synchronized Result.PROMOTED
            }
            return@synchronized Result.COALESCED
        }
        val limit = when (priority) { 0 -> capacity; 1 -> capacity - heldReserve; else -> capacity - heldReserve - urgentReserve }
        if (waiting.size >= limit) return@synchronized Result.FULL
        waiting[key] = Work(key, priority, nowNs, ttlMs, value)
        lock.notifyAll()
        Result.QUEUED
    }

    private fun pollLocked(urgentOnly: Boolean): Work<T>? {
        var best: Work<T>? = null
        for (w in waiting.values) {
            if (urgentOnly && w.priority > 1) continue
            if (best == null || w.priority < best.priority) best = w
        }
        val selected = best ?: return null
        waiting.remove(selected.key)
        running.add(selected.key)
        return selected
    }
    fun poll(urgentOnly: Boolean = false): Work<T>? = synchronized(lock) { pollLocked(urgentOnly) }
    fun take(urgentOnly: Boolean): Work<T> = synchronized(lock) {
        var w = pollLocked(urgentOnly)
        while (w == null) { lock.wait(); w = pollLocked(urgentOnly) }
        w
    }
    fun complete(key: String) = synchronized(lock) { running.remove(key); lock.notifyAll(); Unit }
    fun counts(): Pair<Int,Int> = synchronized(lock) { waiting.size to running.size }
}
