package com.lifecyclebot.engine.cortex

/**
 * V5.0.8419 §A_RECEIPT_WITH_NO_STATE_TO_PROTECT_IS_WRITTEN_ON_ITS_OWN.
 *
 * 5.0.8418 (uptime 225 s): terminal8401[queued=5482 ack=87 oldestMs=40238625 capacity=53714], FORWARD_TERMINAL_DURABILITY_WAIT_8395
 * = 53,714; 8418b (1849 s): queued=5015 ack=0 capacity=68669. The labeler's terminal outbox was restored full of
 * observation terminals up to eleven hours old, and the Cortex acknowledged none of them: Cortex7885.observationUnavailable8395
 * answers true only once the terminal's receipt is durable, and every receipt rode the next shadow checkpoint — a full
 * multi-MB Cortex snapshot (SHADOW_CHECKPOINT_MS_8372 = 433,905 ms under SOFT memory pressure in 8418c). While the outbox sat
 * at its cap, every observation reaching its deadline was refused capacity on every tick (the 53,714), stayed pending, and
 * the labeler took no new observations (pending full): learning starved behind a receipt backlog.
 *
 * The snapshot is needed only when acknowledging the terminal CHANGES Cortex state (onLabelLost8187 removes the pending /
 * paired observation and marks the grade-book decision lost): that mutation and its receipt must be durable together. An
 * episode the Cortex no longer holds (no pending or paired observation at that decision time) has no such mutation —
 * onLabelLost8187 returns without touching a book — so its receipt is one small verified row of its own
 * ([needsSnapshot8419] false), written at the drain instead of behind the checkpoint queue. The durability order is kept:
 * the receipt is committed before the answer is true, and an episode the Cortex still holds takes the snapshot path as before.
 */
object TerminalReceipt8419 {
    /**
     * Pure: does acknowledging the terminal of the decision at [atMs] change Cortex state that only a full snapshot makes
     * durable? [pendingAtMs] is the lane's pending observation time (null = none); [paired] whether the paired restore
     * holds the exact episode.
     */
    fun needsSnapshot8419(pendingAtMs: Long?, atMs: Long, paired: Boolean): Boolean =
        paired || (pendingAtMs != null && pendingAtMs == atMs)

    /**
     * Pure: the receipt row written on its own. V5.0.8445 — also for an episode the Cortex held ([held]): its in-memory
     * removal is already applied and rides the next regular Cortex save (see Cortex7885.observationUnavailable8395).
     */
    fun directReceipt8419(decisionId: String, reason: String, atMs: Long, held: Boolean = false): String =
        org.json.JSONObject().put("decisionId", decisionId).put("reason", reason)
            .put("disposition", if (held) "EPISODE_RELEASED_8445" else "EPISODE_NOT_HELD_8419").put("at", atMs).toString()
}
