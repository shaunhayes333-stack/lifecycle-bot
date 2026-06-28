package com.lifecyclebot.engine

/** V5.0.4439 — report-only source contract for pending-sell retry safety. */
object OperatorPendingSellRetryDigest {
    fun status(): String {
        val pendingSellSize = try { PendingSellQueue.size() } catch (_: Throwable) { -1 }
        return "OPERATOR_PENDING_SELL_RETRY_DIGEST_4439 pendingSellSize=$pendingSellSize invariants=[bad_payload_not_requeued closed_or_closing_mint_purged retry_queue_not_authority live_close_authority_canonical] report_only=true no_queue_mutation=true no_sell_authority=true"
    }
}
