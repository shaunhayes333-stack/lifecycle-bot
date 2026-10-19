package com.lifecyclebot.engine.truth

/** Atomically reserve a started-job slot only after the independent provider-credit authority admits the work. */
internal class WindowJobBudget8239(private val minuteLimit: Int, private val hourLimit: Int) {
    private val starts = ArrayList<Long>()

    @Synchronized fun claim(nowMs: Long, providerAdmits: () -> Boolean): Boolean {
        starts.removeAll { nowMs - it >= 3_600_000L }
        if (starts.count { nowMs - it in 0L until 60_000L } >= minuteLimit || starts.size >= hourLimit) return false
        if (!providerAdmits()) return false
        starts.add(nowMs)
        return true
    }

    @Synchronized fun cancel(nowMs: Long) { starts.remove(nowMs) }
    @Synchronized fun clear() { starts.clear() }
    @Synchronized internal fun used(): Int = starts.size
}
