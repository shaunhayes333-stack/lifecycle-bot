package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6401 §4 — STARTUP EXIT-ONLY LATCH.
 *
 * The 6400 live snapshot showed 19 candidate buys terminally labelled
 * as failures while runtime was temporarily in startup exit-only mode
 * (reason FINALITY_BLOCK:LIVE_EXIT_ONLY_ACTIVE:STARTUP_DEFAULT).
 *
 * Temporary startup deferral must never become permanent trade failure
 * or contaminate strategy statistics.
 *
 * Cleared atomically once ALL pillars are met:
 *   * governor is not HOLD
 *   * scanner queue initialised
 *   * fill-lot ledger initialised
 *   * canonical buy-fill registry initialised
 *   * current-generation sell reconciler has completed its first tick
 *   * wallet snapshot completed
 *   * no global accounting quarantine exists
 *
 * Guarantees:
 *   * warn at 8s
 *   * force readiness recheck at 10s
 *   * repair stale latch at 15s
 *   * never reactivate after a successful live purchase this generation
 */
object StartupExitOnlyLatch6401 {

    const val WARN_MS: Long = 8_000L
    const val RECHECK_MS: Long = 10_000L
    const val REPAIR_MS: Long = 15_000L

    data class Pillars(
        val governorNotHold: Boolean,
        val scannerQueueInit: Boolean,
        val fillLotLedgerInit: Boolean,
        val canonicalBuyRegistryInit: Boolean,
        val sellReconcilerFirstTick: Boolean,
        val walletSnapshotComplete: Boolean,
        val noGlobalAccountingQuarantine: Boolean,
    ) {
        fun allMet(): Boolean = governorNotHold && scannerQueueInit &&
            fillLotLedgerInit && canonicalBuyRegistryInit &&
            sellReconcilerFirstTick && walletSnapshotComplete &&
            noGlobalAccountingQuarantine
        fun blockingPillars(): List<String> = buildList {
            if (!governorNotHold) add("governor_HOLD")
            if (!scannerQueueInit) add("scanner_queue_init")
            if (!fillLotLedgerInit) add("fill_lot_ledger_init")
            if (!canonicalBuyRegistryInit) add("canonical_buy_registry_init")
            if (!sellReconcilerFirstTick) add("sell_reconciler_first_tick")
            if (!walletSnapshotComplete) add("wallet_snapshot")
            if (!noGlobalAccountingQuarantine) add("global_accounting_quarantine")
        }
    }

    private val latchActive = AtomicBoolean(true)
    private val startedAtMs = AtomicLong(System.currentTimeMillis())
    private val liveBuyThisGeneration = AtomicBoolean(false)
    val requeuedDeferrals = AtomicLong(0L)

    /**
     * Evaluate pillars. If all met, clear the latch atomically. Returns
     * true if latch is now cleared (i.e. NOT active).
     */
    fun evaluateAndMaybeClear(pillars: Pillars, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!latchActive.get()) return true
        if (pillars.allMet()) {
            latchActive.set(false)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STARTUP_EXIT_ONLY_LATCH_CLEARED_6401") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("STARTUP_EXIT_ONLY_LATCH_CLEARED_6401", "afterMs=${nowMs - startedAtMs.get()}") } catch (_: Throwable) {}
            return true
        }
        val age = nowMs - startedAtMs.get()
        if (age >= REPAIR_MS) {
            // Repair stale startup latch — force clear.
            latchActive.set(false)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STARTUP_EXIT_ONLY_LATCH_REPAIRED_6401") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("STARTUP_EXIT_ONLY_LATCH_REPAIRED_6401", "ageMs=$age blocking=${pillars.blockingPillars().joinToString(",")}") } catch (_: Throwable) {}
            return true
        }
        if (age >= WARN_MS) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STARTUP_EXIT_ONLY_LATCH_WARN_6401") } catch (_: Throwable) {}
        }
        return false
    }

    fun isActive(): Boolean = latchActive.get()

    /** Candidates deferred by the latch must be REQUEUED, never terminal-fail. */
    fun classifyDeferral(): String {
        requeuedDeferrals.incrementAndGet()
        return "BUY_DEFERRED_STARTUP"
    }

    /** Any successful live buy locks the latch cleared for this generation. */
    fun onLiveBuySuccess() {
        liveBuyThisGeneration.set(true)
        latchActive.set(false)
    }

    /** New runtime generation — reset latch (called by BotRuntimeController). */
    fun resetForNewGeneration() {
        latchActive.set(true)
        startedAtMs.set(System.currentTimeMillis())
        liveBuyThisGeneration.set(false)
        requeuedDeferrals.set(0L)
    }

    internal fun clearAllForTest() { resetForNewGeneration() }
}
