package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One pending snapshot request; receipt writes never wait for full-history replay. */
internal class CoalescedJournalWork8077(
    private val schedule: (Runnable) -> Unit,
    private val publish: (String) -> Unit,
) {
    private val pending = AtomicReference<String?>(null)
    private val scheduled = AtomicBoolean(false)

    fun request(kind: String) {
        pending.set(kind)
        enqueue()
    }

    private fun enqueue() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            schedule(Runnable {
                try { pending.getAndSet(null)?.let(publish) }
                finally {
                    scheduled.set(false)
                    if (pending.get() != null) enqueue()
                }
            })
        } catch (e: Exception) {
            scheduled.set(false)
            throw e
        }
    }
}
