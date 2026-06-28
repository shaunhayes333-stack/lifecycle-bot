package com.lifecyclebot.engine

/** V5.0.4438 — report-only runtime digest for retry/background queues. */
object OperatorQueueRuntimeDigest {
    fun status(): String {
        val pendingSell = try { "PendingSellQueue.size=${PendingSellQueue.size()} hasPending=${PendingSellQueue.hasPending()}" } catch (_: Throwable) { "PendingSellQueue unavailable" }
        val downstream = "DownstreamWorkQueue lanes=[VERIFY RECONCILE RETRY TELEMETRY] report_only=true"
        val reconcile = "PendingReconcileQueue background_recheck=[2m 5m 10m] in_memory_only=true"
        val feeRetry = "FeeRetryQueue transfer_retry_persistence=SharedPreferences self_transfer_drop=true"
        return "OPERATOR_QUEUE_RUNTIME_DIGEST_4438 pendingSell=[$pendingSell] downstream=[$downstream] reconcile=[$reconcile] feeRetry=[$feeRetry] no_execution_authority=true no_queue_mutation=true report_only=true"
    }
}
