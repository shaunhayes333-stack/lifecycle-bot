package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** Serializes one mint's local native/sheet publication, including its resident READY/LOST writes.
 *
 * The map holds only active or waiting callers, not every mint ever observed. Registry updates
 * finish before the mint monitor is acquired; the monitor is released before the registry is
 * updated again. Nested Toolkit -> native reads use the same reentrant monitor. Cached readers
 * do not acquire it, and provider requests retain their existing asynchronous queues.
 */
internal object SpecialistBuild8522 {
    private class Gate(var users: Int = 0)
    private val active = ConcurrentHashMap<String, Gate>()

    fun <T> local8522(mint: String, build: () -> T): T {
        val gate = active.compute(mint) { _, prior ->
            (prior ?: Gate()).also { it.users++ }
        }!!
        try {
            return synchronized(gate) { build() }
        } finally {
            active.compute(mint) { _, current ->
                check(current === gate) { "Specialist build guard changed while in use" }
                gate.users--
                if (gate.users == 0) null else gate
            }
        }
    }

    internal fun activeMints8522(): Int = active.size
}
