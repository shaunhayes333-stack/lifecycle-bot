package com.lifecyclebot.engine

/**
 * V5.0.4016 — immutable per-attempt execution mode authority.
 * Runtime mode can change or stale config caches can lie; a trade attempt must
 * carry its own mode from admission through execution/journal/reconcile.
 */
enum class ExecMode { PAPER, LIVE, SHADOW }

data class ExecutionContext(
    val execMode: ExecMode,
    val attemptId: String,
    val source: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val isLive: Boolean get() = execMode == ExecMode.LIVE
    val isPaper: Boolean get() = execMode == ExecMode.PAPER
    val isShadow: Boolean get() = execMode == ExecMode.SHADOW
}
