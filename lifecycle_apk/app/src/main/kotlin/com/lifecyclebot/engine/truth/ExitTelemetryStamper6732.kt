package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6732 — §EXIT_TELEMETRY_STAMPER.
 *
 * Operator diagnostic from 6731: the bot completed 33 real paper sells
 * including hard-floor / catastrophic / profit-lock exits, yet the
 * canonical exit-gate allow/block counters stayed at 0/0 and every
 * `StopLatencyClasses6464` bucket stayed at n=0. The classes and
 * counters existed but were never called from the exit terminal path.
 *
 * This authority owns the single stamping surface for exit lifecycle
 * telemetry:
 *   - `noteExitIntent(positionId, class)` — call when the exit decision
 *     is emitted (protective / catastrophic / profit-lock).
 *   - `noteExitCompleted(positionId, reason)` — call from the mirror
 *     when the terminal sell confirms. Latency is emitted into
 *     `StopLatencyClasses6464` if intent was previously stamped;
 *     otherwise a `EXIT_TERMINAL_NO_INTENT_STAMP_6732` counter fires
 *     so we can see how many exits arrived without an intent phase.
 *
 * Never mutates state elsewhere; purely observability. Failures fall
 * silent (try/catch) so a telemetry hiccup can never block real exits.
 */
object ExitTelemetryStamper6732 {

    private data class Intent(val cls: StopLatencyClasses6464.Class, val atMs: Long)

    private val intents = ConcurrentHashMap<String, Intent>()

    /** Called when the exit decision is emitted (before ticket / execution). */
    fun noteExitIntent(positionId: String, cls: StopLatencyClasses6464.Class) {
        if (positionId.isBlank()) return
        try {
            intents.putIfAbsent(positionId, Intent(cls, System.currentTimeMillis()))
            PipelineHealthCollector.labelInc("EXIT_INTENT_STAMPED_6732_${cls.name}")
            PipelineHealthCollector.labelInc("EXIT_GATE_ALLOWED_${cls.name}_6732")
        } catch (_: Throwable) {}
    }

    /**
     * Called from the terminal sell path (ExecutorCanonicalMirror6442.mirrorSell)
     * when a sell confirms. The reason may identify a class, but only a real
     * intent timestamp can supply latency. Missing timestamps remain visible
     * as unknown rather than manufactured zero-millisecond measurements.
     */
    fun noteExitCompleted(positionId: String, reason: String, mint: String = "") {
        if (positionId.isBlank()) return
        try {
            val intent = intents.remove(positionId)
            val cls = intent?.cls ?: classify(reason)
            val now7809 = System.currentTimeMillis()
            if (intent == null) {
                PipelineHealthCollector.labelInc("EXIT_TERMINAL_NO_INTENT_STAMP_6732_${cls.name}")
            } else if (now7809 - intent.atMs > TRIGGER_STAMP_MAX_AGE_MS_7807) {
                // V5.0.7809 — an intent stamped by a request that was deferred long
                // ago is not this exit's latency; count it, never average it in.
                PipelineHealthCollector.labelInc("EXIT_INTENT_STAMP_STALE_7809_${cls.name}")
            } else {
                val elapsedMs = (now7809 - intent.atMs).coerceAtLeast(0L)
                StopLatencyClasses6464.record(cls, elapsedMs)
            }
            // V5.0.7809 — broadcast -> finality for the regression gate.
            val b7809 = if (mint.isNotBlank()) lastBroadcastAtMs7809[mint] else null
            if (b7809 != null && b7809 >= SESSION_START_MS_7809 && now7809 - b7809 <= TRIGGER_STAMP_MAX_AGE_MS_7807) {
                StopLatencyClasses6464.recordGateBroadcastToFinality7809((now7809 - b7809).coerceAtLeast(0L))
            }
            PipelineHealthCollector.labelInc("EXIT_TERMINAL_STAMPED_6732_${cls.name}")
            // V5.0.7948 — the position is closed: a trigger re-stamped after its
            // broadcast (sweeps re-asking during confirmation) is not a pending exit.
            if (mint.isNotBlank()) clearTrigger7807(mint)
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6964 §EVERY_EMERGENCY_WAS_FILED_AS_A_NORMAL_STOP.
     *
     * This buckets an exit for STOP-LATENCY measurement — CATASTROPHIC_EXIT
     * carries a 1000ms target in StopLatencyClasses6464, the other classes do
     * not. Measured against the vocabulary riskCheck actually emits, the old
     * matcher sent ALL of these to NORMAL_STOP:
     *
     *     dev_dump, whale_dump, velocity_dump, crosstalk_coordinated_dump,
     *     liquidity_collapse, liquidity_drain, reflex_abort, reflex_liq_drain,
     *     accelerating_loss, gemini_immediate_exit, learned_rug_pattern,
     *     stop_loss, STALE_QUOTE_EMERGENCY_*_BACKSTOP, PROTECTIVE_EXIT_*_6450
     *
     * Every rug and dump signature the bot has was filed in the same latency
     * bucket as a routine stop. So the one question this instrument exists to
     * answer — "are our EMERGENCY exits fast enough" — could not be answered
     * from its own output, and the operator's avgStopMs=689425 was an average
     * over a bucket containing both a 700-second housekeeping close and a rug
     * that needed to fire in under a second.
     *
     * Note "CATASTROPHIC" is correct here where "CATASTROPHE" was wrong in 6951
     * and 6963 — the same concept spelled three ways across three files, which
     * is how two of them ended up matching nothing.
     *
     * Bucket only. This changes which histogram a latency sample lands in and
     * nothing else: no exit is admitted, refused, delayed or re-routed.
     */
    internal fun classify(reason: String): StopLatencyClasses6464.Class {
        val r = reason.uppercase()
        return when {
            // Get-out-now class: the 1000ms target applies to these.
            r.contains("CATASTROPH") || r.contains("GAP_GUARD") ||
                r.contains("RUG") || r.contains("DUMP") || r.contains("COLLAPSE") ||
                r.contains("DRAIN") || r.contains("REFLEX") || r.contains("HONEYPOT") ||
                r.contains("IMMEDIATE_EXIT") || r.contains("EMERGENCY") ||
                r.contains("BACKSTOP") || r.contains("ACCELERATING_LOSS") ->
                StopLatencyClasses6464.Class.CATASTROPHIC_EXIT
            r.contains("HARD_FLOOR") || r.contains("HARD_STOP") || r.contains("PANIC") ||
                r.contains("STOP_LOSS") || r.contains("STRICT_SL") ||
                r.contains("PROTECTIVE_EXIT") ->
                StopLatencyClasses6464.Class.HARD_STOP
            r.contains("TRAIL") || r.contains("PROFIT_LOCK") || r.contains("BREAKEVEN") ->
                StopLatencyClasses6464.Class.TRAILING_STOP
            else -> StopLatencyClasses6464.Class.NORMAL_STOP
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7807 — B1 TRIGGER -> BROADCAST.
    //
    // The operator's SLA is trigger -> broadcast <= 3s for protective
    // emergencies. Intent -> confirm (above) includes on-chain confirmation and
    // wallet verification, so it cannot answer that question. The earliest
    // trigger time per mint is stamped here (the risk clock passes its latch
    // timestamp; requestSell stamps the moment it is asked) and the first
    // SELL_BROADCAST phase on any route (Jupiter, PumpPortal, Raydium/Helius
    // Sender) closes the sample into StopLatencyClasses6464's broadcast
    // buckets. A stamp older than [TRIGGER_STAMP_MAX_AGE_MS_7807] is dropped
    // as unknown rather than reported as a multi-minute latency it may not be.
    // Field Manual L248.
    private data class Trigger7807(val cls: StopLatencyClasses6464.Class, val atMs: Long, val emergency: Boolean)
    private val triggers7807 = ConcurrentHashMap<String, Trigger7807>()
    private const val TRIGGER_STAMP_MAX_AGE_MS_7807 = 10L * 60_000L

    /** Stamp the trigger time for [mint]; the earliest stamp wins. */
    fun noteTrigger7807(mint: String, reason: String, atMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || atMs <= 0L) return
        try {
            val now = System.currentTimeMillis()
            // V5.0.7809 — a retry re-stamping a trigger that a broadcast already
            // measured (the risk clock re-passes its original latch time on every
            // redispatch) would count the same condition twice, the second time
            // with the first attempt's whole retry history inside it.
            val lastB7809 = lastBroadcastAtMs7809[mint]
            if (lastB7809 != null && atMs <= lastB7809) {
                PipelineHealthCollector.labelInc("EXIT_TRIGGER_RESTAMP_AFTER_BROADCAST_IGNORED_7809")
                return
            }
            val stamp = Trigger7807(
                classify(reason), atMs.coerceAtMost(now),
                com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason),
            )
            // V5.0.7948 — a re-request while this mint's sell is on the wire awaiting
            // its outcome is not a new trigger (no queue / trigger->broadcast sample).
            if (!ExitStageTiming7876.onTrigger(mint, stamp.cls, stamp.atMs, now)) {
                PipelineHealthCollector.labelInc("EXIT_TRIGGER_RESTAMP_AWAITING_OUTCOME_IGNORED_7948")
                return
            }
            triggers7807.merge(mint, stamp) { old, new ->
                when {
                    now - old.atMs > TRIGGER_STAMP_MAX_AGE_MS_7807 -> new
                    new.atMs < old.atMs -> new.copy(emergency = new.emergency || old.emergency)
                    else -> old.copy(emergency = new.emergency || old.emergency)
                }
            }
        } catch (_: Throwable) {}
    }

    /** First broadcast for [mint] since its trigger: record trigger -> broadcast ms. */
    fun noteBroadcast7807(mint: String) {
        if (mint.isBlank()) return
        try {
            val now7809 = System.currentTimeMillis()
            // V5.0.7809 — every broadcast is remembered (finality gate, re-stamp guard).
            if (lastBroadcastAtMs7809.size > 2_000) {
                lastBroadcastAtMs7809.entries.removeIf { now7809 - it.value > TRIGGER_STAMP_MAX_AGE_MS_7807 }
            }
            lastBroadcastAtMs7809[mint] = now7809
            val t = triggers7807.remove(mint) ?: return
            val elapsed = (now7809 - t.atMs).coerceAtLeast(0L)
            if (elapsed > TRIGGER_STAMP_MAX_AGE_MS_7807) {
                PipelineHealthCollector.labelInc("EXIT_TRIGGER_TO_BROADCAST_STAMP_STALE_7807")
                return
            }
            StopLatencyClasses6464.recordTriggerToBroadcast7807(t.cls, elapsed, t.emergency)
            // V5.0.7809 — regression gate: only conditions first actionable in this
            // session (a stamp carried from before process start is excluded).
            if (t.atMs >= SESSION_START_MS_7809) StopLatencyClasses6464.recordGateTriggerToBroadcast7809(t.cls, elapsed)
        } catch (_: Throwable) {}
    }

    /** The position closed or the mint was answered without a broadcast. */
    fun clearTrigger7807(mint: String) {
        if (mint.isBlank()) return
        try { triggers7807.remove(mint) } catch (_: Throwable) {}
        // V5.0.7948 — the per-stage track goes with it, or its stale trigger time
        // becomes the next position's "queue" on this mint.
        try { ExitStageTiming7876.closeUnbroadcast7948(mint) } catch (_: Throwable) {}
    }

    // ── V5.0.7809 — latency samples measure condition-first-ACTIONABLE ───────
    // requestSell stamps its trigger before the hold gates. A non-emergency exit
    // a hold gate then defers (style min-hold, profit dust, healthy reconciler
    // hold) is not actionable yet; leaving its stamp put the deliberate hold time
    // into trigger -> broadcast, and the never-expiring intent into intent ->
    // confirm. An emergency stamp is never withdrawn. Field Manual L248.
    // Session floor: this object loads on the first exit request of the process;
    // the risk clock's latch (made moments before that first dispatch) is still
    // this session, so a 60 s grace keeps it. Anything older came from restored
    // state and never enters the gate.
    private val SESSION_START_MS_7809: Long = System.currentTimeMillis() - 60_000L
    private val lastBroadcastAtMs7809 = ConcurrentHashMap<String, Long>()

    fun withdrawDeferred7809(mint: String, positionId: String) {
        try {
            var emergencyKept7809 = false
            if (mint.isNotBlank() && triggers7807.containsKey(mint)) {
                val kept = triggers7807.computeIfPresent(mint) { _, t -> if (t.emergency) t else null }
                if (kept == null) {
                    PipelineHealthCollector.labelInc("EXIT_TRIGGER_WITHDRAWN_DEFERRED_7809")
                } else {
                    emergencyKept7809 = true
                }
            }
            if (positionId.isNotBlank() && !emergencyKept7809) intents.remove(positionId)
            // V5.0.7948 — ExitStageTiming7876 kept the deferred trigger, so the queue
            // stage (trigger -> first SELL_START) timed the whole hold. Withdraw it too.
            if (mint.isNotBlank() && !emergencyKept7809) ExitStageTiming7876.withdrawUndispatched7948(mint)
        } catch (_: Throwable) {}
    }

    internal fun resetForTest() {
        intents.clear()
        triggers7807.clear()
        lastBroadcastAtMs7809.clear()
    }

    fun statusLine(): String = "ExitTelemetryStamper6732 pendingIntents=${intents.size}"
}
