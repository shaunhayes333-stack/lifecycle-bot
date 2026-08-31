package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6625 — MEME EXECUTION FUNNEL RECEIVERS (P2/P3/P4/P5/P6 of
 * operator's V5.0.6622 forensic). One file, five receiver objects,
 * each addressing a specific defect from the operator's priority
 * list. Consumers wire callsites in the follow-up mechanical rollout.
 */

/**
 * V5.0.6625 §P2 EXPRESS_HANDOFF_TELEMETRY.
 *
 * Operator: "EXPRESS: qualified=634 → ownerSelected=33 → buyIntent=33
 *   → sized=0 → mark=0 → ticket=0 → exec=0. The break is after
 *   specialist intent creation and before canonical executable mark
 *   /sizing. Do not weaken EXPRESS qualification."
 *
 * Every hop after intent creation calls the corresponding record fn.
 * The counters emitted let the operator grep the EXACT 33→0 hop.
 */
object ExpressHandoffFunnel6625 {
    private val intentSeen = AtomicLong(0L)
    private val markAcquired = AtomicLong(0L)
    private val markMissing = AtomicLong(0L)
    private val sizingEntered = AtomicLong(0L)
    private val sizingReturnedZero = AtomicLong(0L)
    private val sizingReturnedPositive = AtomicLong(0L)
    private val ticketSealed = AtomicLong(0L)
    private val executed = AtomicLong(0L)

    fun onIntentSeen6625(mint: String) {
        intentSeen.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_INTENT_SEEN_6625") } catch (_: Throwable) {}
    }
    fun onMarkAcquisition6625(mint: String, ok: Boolean, reason: String = "") {
        if (ok) {
            markAcquired.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_MARK_OK_6625") } catch (_: Throwable) {}
        } else {
            markMissing.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_MARK_MISSING_6625")
                ForensicLogger.lifecycle("EXPRESS_FUNNEL_MARK_MISSING_6625",
                    "mint=${mint.take(10)} reason=${reason.take(60)}")
            } catch (_: Throwable) {}
        }
    }
    fun onSizingBridgeEntry6625(mint: String) {
        sizingEntered.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_SIZING_ENTERED_6625") } catch (_: Throwable) {}
    }
    fun onSizingResult6625(mint: String, sizedSol: Double, reason: String = "") {
        if (sizedSol > 0.0) {
            sizingReturnedPositive.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_SIZED_POSITIVE_6625") } catch (_: Throwable) {}
        } else {
            sizingReturnedZero.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_SIZED_ZERO_6625")
                ForensicLogger.lifecycle("EXPRESS_FUNNEL_SIZED_ZERO_6625",
                    "mint=${mint.take(10)} reason=${reason.take(60)} action=investigate_sizing_bridge")
            } catch (_: Throwable) {}
        }
    }
    fun onTicketSealed6625(mint: String) {
        ticketSealed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_TICKET_SEALED_6625") } catch (_: Throwable) {}
    }
    fun onExecuted6625(mint: String) {
        executed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EXPRESS_FUNNEL_EXECUTED_6625") } catch (_: Throwable) {}
    }
    fun statusLine(): String =
        "intent=${intentSeen.get()} markOK=${markAcquired.get()} markMissing=${markMissing.get()} " +
            "sizedPos=${sizingReturnedPositive.get()} sizedZero=${sizingReturnedZero.get()} " +
            "ticketSealed=${ticketSealed.get()} executed=${executed.get()}"
    internal fun resetForTest() {
        intentSeen.set(0L); markAcquired.set(0L); markMissing.set(0L)
        sizingEntered.set(0L); sizingReturnedZero.set(0L); sizingReturnedPositive.set(0L)
        ticketSealed.set(0L); executed.set(0L)
    }
}

/**
 * V5.0.6625 §P3 PENDING_INTENT_BACKLOG_DRAINAGE.
 *
 * Operator: "CORE 168, BLUECHIP 51, EXPRESS 33 pending intents. The
 *   168 + 51 backlog should be consumed, rejected with an explicit
 *   economic reason, or superseded. Never remain indefinitely pending."
 *
 * Every specialist intent records its birth here. The reap function
 * evicts intents older than the age threshold (default 30s) and emits
 * PENDING_INTENT_AGED_OUT_<LANE>_6625.
 */
object PendingIntentBacklog6625 {
    private data class PendingEntry(val bornAtMs: Long, val lane: String, val mint: String)
    private val pending = ConcurrentHashMap<String, PendingEntry>()
    private val agedOut = AtomicLong(0L)
    private val consumed = AtomicLong(0L)

    fun record6625(attemptId: String, lane: String, mint: String) {
        pending[attemptId] = PendingEntry(System.currentTimeMillis(), lane, mint)
        try { PipelineHealthCollector.labelInc("PENDING_INTENT_RECORDED_${lane}_6625") } catch (_: Throwable) {}
    }
    fun consume6625(attemptId: String, outcome: String) {
        val e = pending.remove(attemptId) ?: return
        consumed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PENDING_INTENT_CONSUMED_${e.lane}_6625") } catch (_: Throwable) {}
    }
    fun reap6625(maxAgeMs: Long = 30_000L): Int {
        val now = System.currentTimeMillis()
        var reaped = 0
        val expired = pending.entries.filter { now - it.value.bornAtMs > maxAgeMs }
        for (e in expired) {
            pending.remove(e.key)
            agedOut.incrementAndGet()
            reaped++
            try {
                PipelineHealthCollector.labelInc("PENDING_INTENT_AGED_OUT_6625")
                PipelineHealthCollector.labelInc("PENDING_INTENT_AGED_OUT_${e.value.lane}_6625")
                ForensicLogger.lifecycle("PENDING_INTENT_AGED_OUT_6625",
                    "attemptId=${e.key} lane=${e.value.lane} " +
                        "mint=${e.value.mint.take(10)} ageMs=${now - e.value.bornAtMs} " +
                        "action=drain_stale_intent_from_backlog")
            } catch (_: Throwable) {}
        }
        return reaped
    }
    fun currentBacklogByLane6625(): Map<String, Int> =
        pending.values.groupingBy { it.lane }.eachCount()
    fun statusLine(): String =
        "pending=${pending.size} agedOut=${agedOut.get()} consumed=${consumed.get()} " +
            "byLane=${currentBacklogByLane6625()}"
    internal fun resetForTest() { pending.clear(); agedOut.set(0L); consumed.set(0L) }
}

/**
 * V5.0.6625 §P4 MOONSHOT_EXIT_FINALITY_WIRING.
 *
 * Operator: "MOONSHOT: 23 sell attempts, sellConfirmedN=0, status=
 *   EXIT_CHOKED. PAPER_CLOSE_STUCK_TTL_RETRY_6071=87. Retries must
 *   resume the same transaction ID, not create competing states."
 *
 * Companion counter to V5.0.6620's MemeSellFinality6620 for the
 * SPECIFIC MOONSHOT surface. Records the transaction ID at first
 * requestSell so retries with the same positionId can be deduplicated
 * and the successful terminal releases occupancy/lock/slot exactly once.
 */
object MoonshotExitTransaction6625 {
    private val liveTransactions = ConcurrentHashMap<String, String>()
    private val firstAttempts = AtomicLong(0L)
    private val duplicateRetries = AtomicLong(0L)
    private val terminated = AtomicLong(0L)

    fun beginOrResumeTransaction6625(positionId: String, txIdIfNew: String): String {
        val existing = liveTransactions.putIfAbsent(positionId, txIdIfNew)
        return if (existing == null) {
            firstAttempts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MOONSHOT_EXIT_TX_BEGIN_6625") } catch (_: Throwable) {}
            txIdIfNew
        } else {
            duplicateRetries.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("MOONSHOT_EXIT_TX_RETRY_RESUMED_6625")
                ForensicLogger.lifecycle("MOONSHOT_EXIT_TX_RETRY_RESUMED_6625",
                    "positionId=${positionId.take(18)} txId=$existing " +
                        "action=resume_same_transaction_id_do_not_create_competing_state")
            } catch (_: Throwable) {}
            existing
        }
    }
    fun terminate6625(positionId: String) {
        val removed = liveTransactions.remove(positionId) ?: return
        terminated.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MOONSHOT_EXIT_TX_TERMINATED_6625") } catch (_: Throwable) {}
    }
    fun statusLine(): String =
        "live=${liveTransactions.size} firstAttempts=${firstAttempts.get()} " +
            "retries=${duplicateRetries.get()} terminated=${terminated.get()}"
    internal fun resetForTest() {
        liveTransactions.clear(); firstAttempts.set(0L)
        duplicateRetries.set(0L); terminated.set(0L)
    }
}

/**
 * V5.0.6625 §P5 SPECIALIST_CAUSAL_FUNNEL_INTEGRITY.
 *
 * Operator: "It must become impossible to print things such as
 *   fdgAllow=0 exec=113. Its counters need to be keyed from one
 *   identity: runId + mode + mint + lane + authorityVersion +
 *   intentId. Every stage should be derived from that same canonical
 *   record: DISCOVER → QUALIFY → OWNER → INTENT → FDG → MARK → SIZE
 *   → TICKET → EXEC → OPEN → EXIT → SELL → FINALIZE → LEARN."
 *
 * Every stage recorder stamps the same causal key. Reads always
 * derive stage counters from the same record set — no separate
 * independent counters that can drift apart.
 */
object SpecialistCausalFunnel6625 {
    enum class Stage {
        DISCOVER, QUALIFY, OWNER, INTENT, FDG, MARK, SIZE,
        TICKET, EXEC, OPEN, EXIT, SELL, FINALIZE, LEARN,
    }
    data class CausalKey(
        val runId: String, val mode: String, val mint: String,
        val lane: String, val authorityVersion: Long, val intentId: String,
    )
    private data class Record(val key: CausalKey, val stages: MutableMap<Stage, Long> = mutableMapOf())
    private val records = ConcurrentHashMap<String, Record>()

    private fun keyString(k: CausalKey): String =
        "${k.runId}|${k.mode}|${k.mint}|${k.lane}|${k.authorityVersion}|${k.intentId}"

    fun stamp6625(key: CausalKey, stage: Stage) {
        val ks = keyString(key)
        val rec = records.computeIfAbsent(ks) { Record(key) }
        synchronized(rec) { rec.stages[stage] = System.currentTimeMillis() }
        try {
            PipelineHealthCollector.labelInc("CAUSAL_FUNNEL_STAGE_${stage.name}_${key.lane}_6625")
        } catch (_: Throwable) {}
    }
    fun stageCounts6625(lane: String): Map<Stage, Int> {
        val result = mutableMapOf<Stage, Int>()
        for (r in records.values) {
            if (r.key.lane != lane) continue
            synchronized(r) {
                for (s in r.stages.keys) result[s] = (result[s] ?: 0) + 1
            }
        }
        return result
    }
    fun statusLine(): String = "records=${records.size}"
    internal fun resetForTest() { records.clear() }
}

/**
 * V5.0.6625 §P6 UI_OFF_MAIN_THREAD_AUDIT.
 *
 * Operator: "6.3-second frame gaps. MainActivity.onCreate, token-card
 *   construction, position rendering and MarkAuthorityIntegrityGate
 *   6496.evaluate should not do expensive work on the main thread."
 *
 * Every candidate offender wraps itself with recordMainThreadWork6625
 * (site, durationMs). Long-duration entries emit the counter so the
 * operator can grep which UI operation is holding the Main thread and
 * drive it into a background dispatcher.
 */
object UiOffMainAudit6625 {
    private val longRuns = AtomicLong(0L)
    private val perSite = ConcurrentHashMap<String, AtomicLong>()

    /** Threshold matches an Android frame (~16ms) plus a small slack. */
    private const val LONG_MS = 32L

    fun recordMainThreadWork6625(site: String, durationMs: Long) {
        if (durationMs < LONG_MS) return
        val onMain = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) { false }
        if (!onMain) return
        longRuns.incrementAndGet()
        perSite.computeIfAbsent(site) { AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("UI_MAIN_THREAD_LONG_RUN_6625")
            PipelineHealthCollector.labelInc("UI_MAIN_THREAD_LONG_RUN_${site.uppercase()}_6625")
            ForensicLogger.lifecycle("UI_MAIN_THREAD_LONG_RUN_6625",
                "site=$site durationMs=$durationMs threshold=${LONG_MS} " +
                    "action=move_to_background_dispatcher")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val top = perSite.entries.sortedByDescending { it.value.get() }.take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "longRuns=${longRuns.get()} top=[$top]"
    }
    internal fun resetForTest() { longRuns.set(0L); perSite.clear() }
}
