package com.lifecyclebot.engine

/**
 * V5.0.8865 §HOW_MANY_OF_EACH_SCREEN_ARE_ALIVE — owner's phone on 5.0.8862, 127 minutes in: heap 432 of 512 MB with 327 MB
 * the store census cannot name, 2,175 blocking collections, and the main thread's stalls at MainActivity.onCreate (x4) and
 * VMDebug.countInstancesOfClass (x3) — the count Android's StrictMode runs on the main thread when it finds more instances
 * of an Activity class alive than it expects. 5.0.8863 found one way a second dashboard was built (the splash started a new
 * one over the one already open) and removed it. Whether a screen is still alive more than once, and so holding its whole
 * view tree, adapters and everything they reference a second time, has not been measured anywhere: this counts it.
 *
 * Per Activity class: created, destroyed, alive now (created less destroyed) and the most alive at once this process.
 * Fed by the application's one ActivityLifecycleCallbacks (AATEApp); printed in the heap log. Counting only: no reference
 * to an Activity is kept.
 */
object ActivityCensus8865 {
    private class Count { var created = 0L; var destroyed = 0L; var maxAlive = 0L }
    private val byClass = java.util.TreeMap<String, Count>()   // guarded by itself

    fun noteCreated8865(name: String) {
        synchronized(byClass) {
            val c = byClass.getOrPut(name) { Count() }
            c.created++
            val alive = c.created - c.destroyed
            if (alive > c.maxAlive) c.maxAlive = alive
        }
    }

    fun noteDestroyed8865(name: String) {
        synchronized(byClass) { byClass.getOrPut(name) { Count() }.destroyed++ }
    }

    fun statusLine8865(): String = synchronized(byClass) {
        val alive = byClass.values.sumOf { (it.created - it.destroyed).coerceAtLeast(0L) }
        val rows = byClass.entries.sortedByDescending { it.value.maxAlive * 1_000_000L + it.value.created }.take(10)
            .joinToString(" ") { (k, c) -> "$k=${(c.created - c.destroyed).coerceAtLeast(0L)}/${c.maxAlive}/${c.created}" }.ifBlank { "-" }
        val stackedNow = byClass.entries.filter { it.value.created - it.value.destroyed > 1L }.joinToString(",") { "${it.key} x${it.value.created - it.value.destroyed}" }.ifBlank { "none" }
        val stackedEver = byClass.entries.filter { it.value.maxAlive > 1L }.joinToString(",") { "${it.key} x${it.value.maxAlive}" }.ifBlank { "none" }
        "activities8865[alive=$alive stackedNow=$stackedNow stackedEver=$stackedEver {$rows} " +
            "rule=per screen class alive now / most alive at once / created; a class alive more than once holds its view tree more than once]"
    }

    internal fun resetForTest8865() { synchronized(byClass) { byClass.clear() } }
}
