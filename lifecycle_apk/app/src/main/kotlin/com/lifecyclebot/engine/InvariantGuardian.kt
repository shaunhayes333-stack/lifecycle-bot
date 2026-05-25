package com.lifecyclebot.engine

object InvariantGuardian {
    enum class FaultCode {
        RUNTIME_UI_SPLIT_BRAIN, SELL_RECONCILER_DEAD, HOST_TRACKER_DESYNC,
        LANE_FANOUT_EXPLOSION, EXEC_REQUEST_INFLATION, LEARNING_LEDGER_DUPLICATION,
        PAPER_LIVE_CONTAMINATION, SCANNER_RESTORE_POISONING, MAIN_THREAD_STALL, API_LAYER_DEGRADED,
        FDG_FANOUT_EXPLOSION, FDG_SIGNAL_BYPASS, EXIT_SWEEP_UNSTABLE
    }
    data class Fault(val code: FaultCode, val severity: String, val detail: String, val evidence: Map<String, String> = emptyMap(), val tsMs: Long = System.currentTimeMillis())

    fun check(s: RuntimeStateSnapshot, uiRunning: Boolean = s.uiState == "RUNNING"): List<Fault> {
        val out = mutableListOf<Fault>()
        // V5.9.1164 — runtime active while Main/UI is backgrounded is NORMAL.
        // Navigating to another app/activity must never become a trading fault.
        // Keep RUNTIME_UI_SPLIT_BRAIN enum for old reports, but do not emit it
        // for foreground absence.
        if (s.botLoopActive && !s.sellReconcilerStarted && s.liveOpenPositions > 0) out += Fault(FaultCode.SELL_RECONCILER_DEAD, "CRITICAL", "running with live open positions but sell reconciler stopped")
        // V5.9.1162 — compare live domain to live wallet truth only. Paper positions
        // are expected to have walletHeldMints=0 and must not be reported as healthy
        // live wallet drift. They are surfaced separately as paperOpenPositions.
        if (s.liveOpenPositions != s.hostTrackerOpenCount && s.mode == "LIVE") out += Fault(FaultCode.HOST_TRACKER_DESYNC, "HIGH", "liveStore=${s.liveOpenPositions} hostLive=${s.hostTrackerOpenCount} paper=${s.paperOpenPositions} walletHeld=${s.walletHeldMints}")
        val laneRatio = if (s.intake > 0) s.laneEval.toDouble() / s.intake else 0.0
        if (s.intake > 0 && laneRatio > 12.0) out += Fault(FaultCode.LANE_FANOUT_EXPLOSION, "HIGH", "laneEval/intake=${"%.2f".format(laneRatio)}")
        val pipe = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val fdg = pipe?.phaseCounts?.get("FDG") ?: s.fdg
        val fdgRatio = if (s.intake > 0) fdg.toDouble() / s.intake else 0.0
        if (s.intake > 0 && fdgRatio > 3.0) out += Fault(FaultCode.FDG_FANOUT_EXPLOSION, "HIGH", "FDG/intake=${"%.2f".format(fdgRatio)} fdg=$fdg intake=${s.intake}")
        val ignoredSignal = pipe?.labelCounts?.get("LIFECYCLE/FDG_BASE_SIGNAL_BLOCK_IGNORED") ?: 0L
        if (ignoredSignal > 0L) out += Fault(FaultCode.FDG_SIGNAL_BYPASS, "CRITICAL", "FDG_BASE_SIGNAL_BLOCK_IGNORED=$ignoredSignal")
        // V5.9.1125 — do NOT treat EXEC=0 + TRADEJRNL_REC>0 as split-brain.
        // The funnel EXEC counter tracks executor invocations, while EXEC_BUY/
        // EXEC_SELL/TRADEJRNL_REC track completed journaled trades. 3092 showed
        // this false-positive guard publishing PAUSE_TRADING and choking all
        // QUALITY entries. Real accounting split-brain belongs in the report,
        // not as an automatic global pause from this semantic mismatch.
        val exitReset = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_RESET") ?: 0L
        val exitTimeout = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_TIMEOUT") ?: 0L
        val workerTimeout = pipe?.labelCounts?.get("LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT") ?: 0L
        if (exitReset > 10L || exitTimeout > 0L || workerTimeout > 100L) out += Fault(FaultCode.EXIT_SWEEP_UNSTABLE, "HIGH", "exitReset=$exitReset exitTimeout=$exitTimeout workerTimeout=$workerTimeout")
        val actualBuys = (s.exec).coerceAtLeast(0L)
        val execRatio = if (actualBuys > 0) (s.exec.toDouble() / actualBuys) else 0.0
        if (actualBuys > 0 && execRatio > 5.0) out += Fault(FaultCode.EXEC_REQUEST_INFLATION, "HIGH", "execOpenRequest/actualBuys=${"%.2f".format(execRatio)}")
        if (s.learningTrades != s.uniqueClosedPositionIds) out += Fault(FaultCode.LEARNING_LEDGER_DUPLICATION, "CRITICAL", "learning=${s.learningTrades} uniqueClosed=${s.uniqueClosedPositionIds}")
        if (s.mode == "LIVE" && ExecutionRouteGuard.paperBlockedInLiveCount() > 0) out += Fault(FaultCode.PAPER_LIVE_CONTAMINATION, "CRITICAL", "paper route attempted in live=${ExecutionRouteGuard.paperBlockedInLiveCount()}")
        val quarantine = QuarantineStore.suppressedCount()
        if (s.intake > 0 && quarantine > s.intake) out += Fault(FaultCode.SCANNER_RESTORE_POISONING, "HIGH", "quarantine=$quarantine intake=${s.intake}")
        val mainStall = s.topBlockReasons.keys.any { it.contains("MainActivity", true) || it.contains("renderOpenPositions", true) || it.contains("onCreate", true) }
        if (s.anrHints > 0 && mainStall) out += Fault(FaultCode.MAIN_THREAD_STALL, "HIGH", "anrHints=${s.anrHints} top=${s.topBlockReasons.keys.take(3)}")
        val badApis = s.apiHealth.filterValues { it.successRatePct < 70 && it.failures >= 5 }
        // Birdeye may be intentionally locked down by conservation mode. That
        // must not generate RuntimeDoctor fanout mitigations every tick.
        if (badApis.isNotEmpty()) out += Fault(FaultCode.API_LAYER_DEGRADED, "MEDIUM", "badApis=${badApis.keys.joinToString(",")}")
        return out
    }
}
