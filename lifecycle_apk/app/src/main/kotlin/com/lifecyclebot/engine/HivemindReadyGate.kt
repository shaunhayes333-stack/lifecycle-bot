package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6121 — HivemindReadyGate
 *
 * FIX P2 ISSUE: "Hivemind download async race condition — on very fast
 * startups, the bot might trade blind before hive patterns land."
 *
 * WHAT THIS DOES
 * ══════════════
 *
 * At startup, `CollectiveLearning.downloadAll()` fires as a background
 * coroutine that populates in-memory hive caches (patterns, swarm rugs,
 * cofire counts, price samples). Before that finishes, ANY entry the bot
 * makes is uninformed by the swarm. This gate holds the first N live
 * entries until the download is confirmed complete, OR a hard timeout
 * (60s) expires — whichever comes first.
 *
 * Timeout is generous because being wrong here means bleeding capital,
 * not missing a trade.
 *
 * ONE-SHOT: after the gate opens (either via ready() or timeout), it
 * stays open for the rest of the process lifetime.
 *
 * FDG calls `isReady()` on every live entry. Returns true = allow;
 * false = temporary veto. During the veto window, PAPER trades still
 * fire so learning continues.
 *
 * DOCTRINE #86: fail-open — if we can't tell, we allow.
 */
object HivemindReadyGate {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val HARD_TIMEOUT_MS = 60_000L        // 60s max wait
    private const val LOG_THROTTLE_MS = 10_000L        // log every 10s of waiting

    // ── State ───────────────────────────────────────────────────────────
    private val gateOpened = AtomicBoolean(false)
    private val gateOpenedAtMs = AtomicLong(0L)
    private val gateArmedAtMs = AtomicLong(0L)
    private val lastLoggedMs = AtomicLong(0L)

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Called by BotService.onCreate as the earliest possible arming
     * point. Records the time we start waiting.
     */
    fun arm() {
        if (gateArmedAtMs.compareAndSet(0L, System.currentTimeMillis())) {
            try {
                ForensicLogger.lifecycle(
                    "HIVEMIND_GATE_ARMED_6121",
                    "waiting up to ${HARD_TIMEOUT_MS}ms for CollectiveLearning.downloadAll()",
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called by CollectiveLearning at the completion of downloadAll()
     * (success OR failure — both open the gate; failure just means we
     * trade without a hive today).
     */
    fun ready(source: String) {
        if (gateOpened.compareAndSet(false, true)) {
            val nowMs = System.currentTimeMillis()
            gateOpenedAtMs.set(nowMs)
            val armed = gateArmedAtMs.get()
            val waitedMs = if (armed > 0L) nowMs - armed else 0L
            try {
                ForensicLogger.lifecycle(
                    "HIVEMIND_GATE_OPENED_6121",
                    "source=$source waitedMs=$waitedMs",
                )
                PipelineHealthCollector.labelInc("HIVEMIND_GATE_OPENED_6121")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called by FDG before any live entry. Returns true = allow; false
     * = block. Auto-opens on timeout so bootup wedges heal themselves.
     */
    fun isReady(): Boolean {
        if (gateOpened.get()) return true
        val armed = gateArmedAtMs.get()
        if (armed == 0L) {
            // Never armed → never waited → we don't know if hive is ready.
            // Fail-open per Doctrine #86.
            return true
        }
        val nowMs = System.currentTimeMillis()
        val waited = nowMs - armed
        if (waited >= HARD_TIMEOUT_MS) {
            ready("timeout")
            return true
        }
        val lastLog = lastLoggedMs.get()
        if (nowMs - lastLog > LOG_THROTTLE_MS && lastLoggedMs.compareAndSet(lastLog, nowMs)) {
            try {
                ForensicLogger.lifecycle(
                    "HIVEMIND_GATE_WAITING_6121",
                    "waitedMs=$waited / ${HARD_TIMEOUT_MS}ms — live entries held",
                )
                PipelineHealthCollector.labelInc("HIVEMIND_GATE_WAITING_6121")
            } catch (_: Throwable) {}
        }
        return false
    }
}
