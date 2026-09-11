package com.lifecyclebot.engine.truth

/** Lifecycle health is independent of a coalesced request timestamp. */
object ExitCoordinatorHealth6737 {
    fun healthy(jobActive: Boolean, heartbeatMs: Long, nowMs: Long): Boolean =
        jobActive && heartbeatMs > 0L && nowMs - heartbeatMs in 0L until 15_000L
}
