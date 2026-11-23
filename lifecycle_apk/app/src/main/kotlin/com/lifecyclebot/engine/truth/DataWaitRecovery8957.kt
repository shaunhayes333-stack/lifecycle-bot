package com.lifecyclebot.engine.truth

/** Data-provider failures are retryable observations, never a losing market thesis. */
object DataWaitRecovery8957 {
    private val dataReasons = setOf(
        "DATA_INCOMPLETE_DEFER_8136", "DATA_NEVER_ARRIVED_8418", "DATA_WAIT_SPENT_8418",
        "DATA_DEFER_FETCH_NEVER_COMPLETED_8204", "DATA_DEFER_BUDGET_EXHAUSTED_8136",
    )

    fun isDataWait(reason: String): Boolean = reason in dataReasons

    fun retryableCanonical(reason: String): Boolean {
        val raw = when {
            reason.startsWith("CANONICAL_DEFERRED:") -> reason.removePrefix("CANONICAL_DEFERRED:")
            reason.startsWith("CANONICAL_BLOCKED:") -> reason.removePrefix("CANONICAL_BLOCKED:")
            else -> return false
        }
        return isDataWait(raw)
    }
}
