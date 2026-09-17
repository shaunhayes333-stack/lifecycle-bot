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
    /**
     * V5.0.6874 §AN_AUTOMATED_LANE_KILL_WITH_NO_WAY_BACK — this was a bare
     * `newKeySet<String>()`. disableLane() is called by RuntimeSelfHealer
     * (Action.DISABLE_LANE) and HotfixRules (RuleType.DISABLE_LANE), and
     * isLaneDisabled() is read at 13 sites, so those calls genuinely stop a lane
     * trading. enableLane() exists and had ZERO callers.
     *
     * So an automated healer or hotfix rule could kill a lane and nothing in the
     * process could ever bring it back — the only escape was a restart, since the
     * set is in-memory. That is a one-way ratchet applied automatically, and it is
     * out of character for everything around it: BleederLaneProbation exits
     * probation on WR recovery, LaneAutoPauseGuard auto-unpauses at WR >= 25%,
     * AdaptiveVetoConsensusAuthority decays its signals in 5 minutes, and the
     * standing mandate (V5.9.1358) is "never disable a lane" — "don't disable,
     * re-educate".
     *
     * Suppression is now time-boxed, so the healer keeps its ability to react while
     * the lane always gets to come back and prove itself. An explicit enableLane()
     * still clears it immediately.
     */
    private const val LANE_DISABLE_TTL_MS_6874: Long = 30L * 60_000L
    private val disabledLanes = ConcurrentHashMap<String, Long>()  // lane -> disabledUntilMs
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
    fun disableLane(lane: String, reason: String) {
        val until = System.currentTimeMillis() + LANE_DISABLE_TTL_MS_6874
        disabledLanes[lane.uppercase()] = until
        log("DISABLE_LANE", "$lane $reason ttlMs=$LANE_DISABLE_TTL_MS_6874 autoReEnable=true")
    }
    fun enableLane(lane: String, reason: String) { disabledLanes.remove(lane.uppercase()); log("ENABLE_LANE", "$lane $reason") }
    fun disableScannerSource(source: String, reason: String) { disabledScannerSources += source.uppercase(); log("DISABLE_SCANNER_SOURCE", "$source $reason") }
    fun enableScannerSource(source: String, reason: String) { disabledScannerSources -= source.uppercase(); log("ENABLE_SCANNER_SOURCE", "$source $reason") }
    fun setScannerUserDisabled(disabled: Boolean, reason: String) { scannerUserDisabled.set(disabled); log(if (disabled) "SCANNER_USER_DISABLED" else "SCANNER_USER_ENABLED", reason) }
    fun isScannerUserDisabled(): Boolean = scannerUserDisabled.get()
    fun setScannerConcurrencyCap(cap: Int, reason: String) { scannerConcurrencyCap.set(cap.coerceAtLeast(0)); log("SET_SCANNER_CONCURRENCY_CAP", "$cap $reason") }
    fun forceUiRuntimeRebind(reason: String) { uiRebindGeneration.incrementAndGet(); log("FORCE_UI_RUNTIME_REBIND", reason) }
    fun noteStaleLocksCleared(count: Long, reason: String) { staleLocksCleared.addAndGet(count); log("CLEAR_STALE_LOCKS", "$count $reason") }

    fun isTradingPaused(): Boolean = tradingPaused.get()
    fun shouldForcePaper(): Boolean = false
    // V5.0.6874 — expire on read, and say so once when a lane comes back, so an
    // operator reading the dumps can see the suppression ended rather than
    // wondering why a lane silently resumed.
    fun isLaneDisabled(lane: String): Boolean {
        val key = lane.uppercase()
        val until = disabledLanes[key] ?: return false
        if (System.currentTimeMillis() < until) return true
        if (disabledLanes.remove(key, until)) {
            log("LANE_DISABLE_EXPIRED_6874", "$key suppression_window_elapsed action=lane_re_enabled")
            try { PipelineHealthCollector.labelInc("RUNTIME_REPAIR_LANE_RE_ENABLED_6874") } catch (_: Throwable) {}
        }
        return false
    }
    fun isScannerSourceDisabled(source: String): Boolean = source.uppercase() in disabledScannerSources
    fun scannerCap(): Int = scannerConcurrencyCap.get()
    // V5.0.6874 — only lanes whose suppression window is still open.
    fun disabledLaneSnapshot(): Set<String> {
        val now = System.currentTimeMillis()
        return disabledLanes.entries.filter { it.value > now }.map { it.key }.toSet()
    }
    fun disabledScannerSourceSnapshot(): Set<String> = disabledScannerSources.toSet()
    fun staleLocksClearedCount(): Long = staleLocksCleared.get()
    fun uiRebindGeneration(): Long = uiRebindGeneration.get()
    fun resetForTests() { tradingPaused.set(false); forcePaper.set(false); scannerConcurrencyCap.set(0); disabledLanes.clear(); disabledScannerSources.clear(); staleLocksCleared.set(0L); uiRebindGeneration.set(0L); scannerUserDisabled.set(false) }

    private fun log(event: String, reason: String) {
        try { ForensicLogger.lifecycle("RUNTIME_REPAIR_$event", reason.take(220)) } catch (_: Throwable) {}
    }
}
