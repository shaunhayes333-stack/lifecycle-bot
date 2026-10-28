package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A replay's identity and value are one publication, never independently writable cache fields. */
internal class RevisionedReplayMemo8372<T>(private val revisionOf: (T) -> Long) {
    private data class Entry<T>(val key: String, val revision: Long, val value: T)
    private val entry = AtomicReference<Entry<T>?>(null)

    fun get(key: String, revision: Long): T? {
        val saved = entry.get() ?: return null
        return saved.value.takeIf {
            revision >= 0L && saved.key == key && saved.revision == revision && revisionOf(it) == revision
        }
    }

    fun publish(key: String, revision: Long, value: T): Boolean {
        if (revision < 0L || key.substringBefore('|').toLongOrNull() != revision || revisionOf(value) != revision) return false
        val next = Entry(key, revision, value)
        while (true) {
            val previous = entry.get()
            if (previous != null && previous.revision > revision) return false
            if (entry.compareAndSet(previous, next)) return true
        }
    }

    fun latest(): T? = entry.get()?.value
    fun clear() { entry.set(null) }
}

/** Non-blocking ownership of a reconciliation pass; only a completed, unchanged snapshot is memoized. */
internal class ReconciliationPublication8372(
    private val completed: AtomicReference<String?> = AtomicReference(null),
) {
    class Lease internal constructor(val key: String?) {
        internal val finished = AtomicBoolean(false)
    }

    private val active = AtomicReference<Lease?>(null)

    fun claim(key: String?): Lease? {
        if (key != null && completed.get() == key) return null
        val lease = Lease(key)
        if (!active.compareAndSet(null, lease)) return null
        // A previous owner may have completed between the first read and our CAS.
        if (key != null && completed.get() == key) {
            active.compareAndSet(lease, null)
            return null
        }
        return lease
    }

    fun finish(lease: Lease, currentKey: String?, coherent: Boolean, publish: () -> Unit = {}): Boolean {
        if (active.get() !== lease || !lease.finished.compareAndSet(false, true)) return false
        var published = false
        try {
            if (coherent && lease.key != null && lease.key == currentKey) {
                // The callback installs the immutable result before its completed key becomes reusable.
                publish()
                completed.set(lease.key)
                published = true
            }
            return published
        } finally {
            if (!published) completed.set(null)
            active.compareAndSet(lease, null)
        }
    }

    // V5.0.8418 — a pass that collided with this one; the owner (or the collider, once the owner is gone) runs one follow-up.
    private val pending8418 = AtomicBoolean(false)
    fun notePending8418() { pending8418.set(true) }
    fun takePending8418(): Boolean = pending8418.getAndSet(false)

    fun completedKey(): String? = completed.get()
    fun isBusy(): Boolean = active.get() != null
    fun clear() { completed.set(null) }
}
