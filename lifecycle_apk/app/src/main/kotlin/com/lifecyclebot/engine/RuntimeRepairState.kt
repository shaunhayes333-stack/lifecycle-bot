package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Safe bounded live repair state. No code editing, no deploys, no keys, no tx generation. */
object RuntimeRepairState {
    private val tradingPaused = AtomicBoolean(false)
    private val forcePaper = AtomicBoolean(false)
    private val scannerConcurrencyCap = AtomicInteger(0)
    private val uiRebindGeneration = AtomicLong(0L)
    private val disabledLanes = ConcurrentHashMap.newKeySet<String>()
    private val disabledScannerSources = ConcurrentHashMap.newKeySet<String>()
    // V5.9.1518 — PATCH ITEM 3: explicit operator scanner kill-switch. Defaults
    // OFF so the heartbeat watchdog auto-restarts a stale/dead scanner during
    // normal RUNNING state. Only set true when the user deliberately stops the
    // scanner; the watchdog then leaves it alone.
    private val scannerUserDisabled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val staleLocksCleared = AtomicLong(0L)

    fun pauseTrading(reason: String) { tradingPaused.set(true); log("PAUSE_TRADING", reason) }
    fun resumeTrading(reason: String) { tradingPaused.set(false); log("RESUME_TRADING", reason) }
    fun requestPaperMode(reason: String) { log("REQUEST_PAPER_MODE_IGNORED", "mode authority is operator-controlled: $reason") }
    fun clearPaperModeRequest(reason: String) { forcePaper.set(false); log("CLEAR_PAPER_REQUEST", reason) }
    fun disableLane(lane: String, reason: String) { disabledLanes += lane.uppercase(); log("DISABLE_LANE", "$lane $reason") }
    fun enableLane(lane: String, reason: String) { disabledLanes -= lane.uppercase(); log("ENABLE_LANE", "$lane $reason") }
    fun disableScannerSource(source: String, reason: String) { disabledScannerSources += source.uppercase(); log("DISABLE_SCANNER_SOURCE", "$source $reason") }
    fun enableScannerSource(source: String, reason: String) { disabledScannerSources -= source.uppercase(); log("ENABLE_SCANNER_SOURCE", "$source $reason") }
    fun setScannerUserDisabled(disabled: Boolean, reason: String) { scannerUserDisabled.set(disabled); log(if (disabled) "SCANNER_USER_DISABLED" else "SCANNER_USER_ENABLED", reason) }
    fun isScannerUserDisabled(): Boolean = scannerUserDisabled.get()
    fun setScannerConcurrencyCap(cap: Int, reason: String) { scannerConcurrencyCap.set(cap.coerceAtLeast(0)); log("SET_SCANNER_CONCURRENCY_CAP", "$cap $reason") }
    fun forceUiRuntimeRebind(reason: String) { uiRebindGeneration.incrementAndGet(); log("FORCE_UI_RUNTIME_REBIND", reason) }
    fun noteStaleLocksCleared(count: Long, reason: String) { staleLocksCleared.addAndGet(count); log("CLEAR_STALE_LOCKS", "$count $reason") }

    fun isTradingPaused(): Boolean = tradingPaused.get()
    fun shouldForcePaper(): Boolean = false
    fun isLaneDisabled(lane: String): Boolean = lane.uppercase() in disabledLanes
    fun isScannerSourceDisabled(source: String): Boolean = source.uppercase() in disabledScannerSources
    fun scannerCap(): Int = scannerConcurrencyCap.get()
    fun disabledLaneSnapshot(): Set<String> = disabledLanes.toSet()
    fun disabledScannerSourceSnapshot(): Set<String> = disabledScannerSources.toSet()
    fun staleLocksClearedCount(): Long = staleLocksCleared.get()
    fun uiRebindGeneration(): Long = uiRebindGeneration.get()
    fun resetForTests() { tradingPaused.set(false); forcePaper.set(false); scannerConcurrencyCap.set(0); disabledLanes.clear(); disabledScannerSources.clear(); staleLocksCleared.set(0L); uiRebindGeneration.set(0L); scannerUserDisabled.set(false) }

    private fun log(event: String, reason: String) {
        try { ForensicLogger.lifecycle("RUNTIME_REPAIR_$event", reason.take(220)) } catch (_: Throwable) {}
    }
}
