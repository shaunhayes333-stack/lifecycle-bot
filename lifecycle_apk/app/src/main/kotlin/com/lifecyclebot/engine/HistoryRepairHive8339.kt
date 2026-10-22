package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.8339 §REPAIR_ROWS_OFF_THE_INIT_PATH — CanonicalPaperTransaction6486.repairCryptoHistory6659 re-projects every
 * cross-asset paper close that is missing its journal leg, one TradeHistoryStore.recordTrade per row, synchronously
 * inside BotService.onCreate's bootstrap (reconcileJournalAuthority6663 holds the paper transaction lock throughout).
 * With 4 096 seeded rows the smoke saw SERVICE_BOOTSTRAP_STALL_8144 at
 * TradeHistoryStore.uploadCollectiveJournalRow <- recordTrade <- repairCryptoHistory6659 (stalledMs=22432) and the
 * crypto-alt trader init timing out behind it. The journal write itself must stay where it is (replay needs the leg
 * before it can reconcile); the per-row hive upload (learning sanitizer, journal key, a coroutine per row) does not.
 * Repair rows (reason CROSS_ASSET_HISTORY_REPAIR_6660) are queued here and uploaded in one background pass
 * [DRAIN_DELAY_MS_8339] after the first one is seen — after bootstrap. Nothing is dropped below [MAX_QUEUE_8339]; the
 * upload itself is the same function, unchanged, so the hive sees the same rows later rather than never.
 */
object HistoryRepairHive8339 {
    const val REPAIR_REASON_8339 = "CROSS_ASSET_HISTORY_REPAIR_6660"
    const val DRAIN_DELAY_MS_8339 = 120_000L
    const val MAX_QUEUE_8339 = 8_192

    private val queue = ConcurrentLinkedQueue<Trade>()
    private val queued = AtomicInteger(0)
    private val drainScheduled = AtomicBoolean(false)
    @Volatile private var pendingUpload: ((Trade) -> Unit)? = null

    /** Pure: is a journal row with this [reason] a bootstrap history-repair re-projection? */
    fun isRepairRow8339(reason: String?): Boolean =
        reason != null && reason.contains(REPAIR_REASON_8339, ignoreCase = true)

    /** Pure over the queue: take a repair row off the record path. False when [trade] is not a repair row (upload now). */
    fun enqueue8339(trade: Trade): Boolean {
        if (!isRepairRow8339(trade.reason)) return false
        if (queued.get() >= MAX_QUEUE_8339) {
            try { PipelineHealthCollector.labelInc("HISTORY_REPAIR_HIVE_UPLOAD_DROPPED_8339") } catch (_: Throwable) {}
            return true
        }
        queue.add(trade)
        queued.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HISTORY_REPAIR_HIVE_UPLOAD_DEFERRED_8339") } catch (_: Throwable) {}
        return true
    }

    /** Upload every queued row through [upload]; returns how many were handed over. */
    fun drain8339(upload: (Trade) -> Unit): Int {
        var n = 0
        while (true) {
            val t = queue.poll() ?: break
            queued.decrementAndGet()
            try { upload(t) } catch (_: Throwable) {}
            n++
        }
        if (n > 0) try { PipelineHealthCollector.labelInc("HISTORY_REPAIR_HIVE_UPLOAD_DRAINED_8339") } catch (_: Throwable) {}
        return n
    }

    fun queued8339(): Int = queued.get()

    /**
     * TradeHistoryStore.recordTrade: true when [trade] is a repair row, now queued for a background upload through
     * [upload] after [DRAIN_DELAY_MS_8339]; false when the caller uploads it itself, as before.
     */
    fun deferIfRepairRow8339(trade: Trade, upload: (Trade) -> Unit): Boolean {
        if (!enqueue8339(trade)) return false
        pendingUpload = upload
        scheduleDrain8339()
        return true
    }

    private fun scheduleDrain8339() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            kotlinx.coroutines.GlobalScope.launch(com.lifecyclebot.util.AppDispatchers.sideEffect) {
                try { kotlinx.coroutines.delay(DRAIN_DELAY_MS_8339) } catch (_: Throwable) {}
                drainScheduled.set(false)
                val upload = pendingUpload
                if (upload != null) try { drain8339(upload) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            drainScheduled.set(false)
        }
    }
}
