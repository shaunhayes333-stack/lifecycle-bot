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
    private val staleLocksCleared = AtomicLong(0L)

    fun pauseTrading(reason: String) { tradingPaused.set(true); log("PAUSE_TRADING", reason) }
    fun resumeTrading(reason: String) { tradingPaused.set(false); log("RESUME_TRADING", reason) }
    fun requestPaperMode(reason: String) { forcePaper.set(true); log("REQUEST_PAPER_MODE", reason) }
    fun clearPaperModeRequest(reason: String) { forcePaper.set(false); log("CLEAR_PAPER_REQUEST", reason) }
    fun disableLane(lane: String, reason: String) { disabledLanes += lane.uppercase(); log("DISABLE_LANE", "$lane $reason") }
    fun enableLane(lane: String, reason: String) { disabledLanes -= lane.uppercase(); log("ENABLE_LANE", "$lane $reason") }
    fun disableScannerSource(source: String, reason: String) { disabledScannerSources += source.uppercase(); log("DISABLE_SCANNER_SOURCE", "$source $reason") }
    fun enableScannerSource(source: String, reason: String) { disabledScannerSources -= source.uppercase(); log("ENABLE_SCANNER_SOURCE", "$source $reason") }
    fun setScannerConcurrencyCap(cap: Int, reason: String) { scannerConcurrencyCap.set(cap.coerceAtLeast(0)); log("SET_SCANNER_CONCURRENCY_CAP", "$cap $reason") }
    fun forceUiRuntimeRebind(reason: String) { uiRebindGeneration.incrementAndGet(); log("FORCE_UI_RUNTIME_REBIND", reason) }
    fun noteStaleLocksCleared(count: Long, reason: String) { staleLocksCleared.addAndGet(count); log("CLEAR_STALE_LOCKS", "$count $reason") }

    fun isTradingPaused(): Boolean = tradingPaused.get()
    fun shouldForcePaper(): Boolean = forcePaper.get()
    fun isLaneDisabled(lane: String): Boolean = lane.uppercase() in disabledLanes
    fun isScannerSourceDisabled(source: String): Boolean = source.uppercase() in disabledScannerSources
    fun scannerCap(): Int = scannerConcurrencyCap.get()
    fun disabledLaneSnapshot(): Set<String> = disabledLanes.toSet()
    fun disabledScannerSourceSnapshot(): Set<String> = disabledScannerSources.toSet()
    fun staleLocksClearedCount(): Long = staleLocksCleared.get()
    fun uiRebindGeneration(): Long = uiRebindGeneration.get()
    fun resetForTests() { tradingPaused.set(false); forcePaper.set(false); scannerConcurrencyCap.set(0); disabledLanes.clear(); disabledScannerSources.clear(); staleLocksCleared.set(0L); uiRebindGeneration.set(0L) }

    private fun log(event: String, reason: String) {
        try { ForensicLogger.lifecycle("RUNTIME_REPAIR_$event", reason.take(220)) } catch (_: Throwable) {}
    }
}
