package com.lifecyclebot.engine.truth

/** Bounded per-key coalescing with ordered flushes. New epochs supersede old deletes; failed commits retain work. */
internal class ShadowWrites8386(private val capacity: Int = 8192) {
    data class Write(val epoch: Long, val serial: Long, val value: String?)
    private val state = Any()
    private val writer = Any()
    private val pending = LinkedHashMap<String, Write>()
    private val latest = LinkedHashMap<String, Write>()
    private var sequence = 0L
    fun stage(key: String, epoch: Long, value: String?): Boolean = synchronized(state) {
        val prior = latest[key]
        if (prior != null && (epoch < prior.epoch || (epoch == prior.epoch && prior.value == null && value != null))) return@synchronized false
        if (key !in pending && pending.size >= capacity) return@synchronized false
        val w = Write(epoch, ++sequence, value)
        pending[key] = w; latest[key] = w
        if (latest.size > capacity * 2) {
            val it = latest.entries.iterator()
            while (latest.size > capacity && it.hasNext()) { val e = it.next(); if (e.key !in pending) it.remove() }
        }
        true
    }
    fun flush(commit: (Map<String, Write>) -> Boolean): Boolean = synchronized(writer) {
        val batch = synchronized(state) { pending.toMap() }
        if (batch.isEmpty()) return@synchronized true
        if (!commit(batch)) return@synchronized false
        synchronized(state) { batch.forEach { (k, w) -> if (pending[k] === w) pending.remove(k) } }
        true
    }
    fun size(): Int = synchronized(state) { pending.size }
}
