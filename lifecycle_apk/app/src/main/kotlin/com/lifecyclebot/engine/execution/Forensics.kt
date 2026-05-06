package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.495z19 — Forensic event log for the buy-execution state machine.
 *
 * Operator spec requires these named events to appear in the journal so a
 * partial-bridge / output-mismatch failure is traceable end-to-end. Wire
 * `Forensics.log(event, …)` at every state transition in the buy pipeline.
 *
 * Pure logging surface — no side effects beyond ErrorLogger output and a
 * bounded in-memory ring buffer that the diagnostics screen can read.
 */
object Forensics {

    private const val TAG = "Forensics"
    private const val MAX_EVENTS = 500

    enum class Event {
        ROUTE_INTENT_CREATED,
        ROUTE_SELECTED,
        ROUTE_VALIDATE_PRE_OK,
        ROUTE_VALIDATE_PRE_FAILED,
        JUPITER_V2_ORDER_REQUEST,
        JUPITER_V2_ORDER_OK,
        JUPITER_V2_ORDER_REJECTED_OUTPUT_MISMATCH,
        JUPITER_V2_ROUTE_PLAN_FINAL_MINT,
        JUPITER_V2_SIGNED,
        JUPITER_V2_EXECUTE_REQUEST,
        JUPITER_V2_EXECUTE_OK,
        PUMPPORTAL_BUY_REQUEST,
        PUMPPORTAL_BUY_OK,
        PUMPPORTAL_BUY_FAILED,
        TX_CONFIRMED,
        TX_PARSE_TARGET_DELTA_OK,
        TX_PARSE_INTERMEDIATE_ONLY,
        INTERMEDIATE_ASSET_RECOVERY_CREATED,
        INTERMEDIATE_SECOND_LEG_STARTED,
        INTERMEDIATE_SECOND_LEG_CONFIRMED,
        INTERMEDIATE_UNWIND_STARTED,
        INTERMEDIATE_UNWOUND_TO_SOL,
        FINAL_TOKEN_VERIFIED,
        HOST_TRACKER_OPENED,
        EXIT_MONITOR_ATTACHED,
        OUTPUT_MISMATCH_BLOCKED,
        BUY_FAILED_NO_TARGET_TOKEN,
    }

    data class Entry(
        val ts: Long,
        val event: Event,
        val mintShort: String,
        val msg: String,
    )

    private val ring = ArrayDeque<Entry>(MAX_EVENTS)

    @Synchronized
    fun log(event: Event, mint: String = "", msg: String = "") {
        val short = if (mint.length > 12) mint.take(8) + "…" else mint
        val entry = Entry(System.currentTimeMillis(), event, short, msg)
        if (ring.size >= MAX_EVENTS) ring.removeFirst()
        ring.addLast(entry)
        ErrorLogger.info(TAG, "▸ ${event.name} | $short${if (msg.isNotBlank()) " | $msg" else ""}")
    }

    @Synchronized
    fun recent(limit: Int = 50): List<Entry> = ring.toList().takeLast(limit).reversed()

    @Synchronized
    fun clear() { ring.clear() }
}
