package com.lifecyclebot.engine.truth

/** Bounded per-key coalescing with ordered flushes. New epochs supersede old deletes; failed commits retain work. */
internal class ShadowWrites8386(private val capacity: Int = 8192, private val byteCapacity: Long = 2L * 1024 * 1024,
                                private val batchCapacity: Int = 128) {
    data class Write(val epoch: Long, val serial: Long, val value: String?)
    private data class Version(val epoch: Long, val serial: Long, val deleted: Boolean)
    private val state = Any()
    private val writer = Any()
    private val pending = LinkedHashMap<String, Write>()
    // Only ordering metadata survives a flush; retaining Write here retained every serialized ticket twice.
    private val latest = LinkedHashMap<String, Version>()
    private var sequence = 0L
    private var pendingBytes = 0L
    private fun sizeOf(key: String, value: String?): Long = 64L + 2L * (key.length + (value?.length ?: 0))
    fun stage(key: String, epoch: Long, value: String?): Boolean = synchronized(state) {
        val prior = latest[key]
        if (prior != null && (epoch < prior.epoch || (epoch == prior.epoch && prior.deleted && value != null))) return@synchronized false
        if (key !in pending && pending.size >= capacity) return@synchronized false
        val oldBytes = pending[key]?.let { sizeOf(key, it.value) } ?: 0L
        val nextBytes = pendingBytes - oldBytes + sizeOf(key, value)
        if (nextBytes > byteCapacity) return@synchronized false
        val w = Write(epoch, ++sequence, value)
        pending[key] = w; latest[key] = Version(w.epoch, w.serial, value == null); pendingBytes = nextBytes
        if (latest.size > capacity * 2) {
            val it = latest.entries.iterator()
            while (latest.size > capacity && it.hasNext()) { val e = it.next(); if (e.key !in pending) it.remove() }
        }
        true
    }
    fun flush(commit: (Map<String, Write>) -> Boolean): Boolean = synchronized(writer) {
        // A bounded batch per transaction, no full pending-map copy. Serial comparison keeps concurrent newer writes.
        val through = synchronized(state) { sequence }
        repeat((capacity + batchCapacity - 1) / batchCapacity) {
            val batch = synchronized(state) { pending.entries.asSequence().filter { it.value.serial <= through }
                .take(batchCapacity).associate { it.key to it.value } }
            if (batch.isEmpty()) return@synchronized true
            if (!commit(batch)) return@synchronized false
            synchronized(state) { batch.forEach { (k, w) -> if (pending[k] === w) {
                pending.remove(k); pendingBytes -= sizeOf(k, w.value)
            } } }
        }
        synchronized(state) { pending.values.none { it.serial <= through } }
    }
    fun size(): Int = synchronized(state) { pending.size }
    fun bytes(): Long = synchronized(state) { pendingBytes + latest.entries.sumOf { 64L + it.key.length * 2L } }
}
