package com.lifecyclebot.engine

object InvariantGuardian {
    enum class FaultCode {
        RUNTIME_UI_SPLIT_BRAIN, SELL_RECONCILER_DEAD, HOST_TRACKER_DESYNC,
        LANE_FANOUT_EXPLOSION, EXEC_REQUEST_INFLATION, LEARNING_LEDGER_DUPLICATION,
        PAPER_LIVE_CONTAMINATION, SCANNER_RESTORE_POISONING, MAIN_THREAD_STALL, API_LAYER_DEGRADED
    }
    data class Fault(val code: FaultCode, val severity: String, val detail: String, val evidence: Map<String, String> = emptyMap(), val tsMs: Long = System.currentTimeMillis())

    fun check(s: RuntimeStateSnapshot, uiRunning: Boolean = s.uiState == "RUNNING"): List<Fault> {
        val out = mutableListOf<Fault>()
        if (s.botLoopActive && !uiRunning) out += Fault(FaultCode.RUNTIME_UI_SPLIT_BRAIN, "HIGH", "runtime active but UI stopped")
        if (s.botLoopActive && !s.sellReconcilerStarted && s.hostTrackerOpenCount > 0) out += Fault(FaultCode.SELL_RECONCILER_DEAD, "CRITICAL", "running with open host positions but sell reconciler stopped")
        if (s.hostTrackerOpenCount != s.positionStoreOpenCount) out += Fault(FaultCode.HOST_TRACKER_DESYNC, "HIGH", "host=${s.hostTrackerOpenCount} positionStore=${s.positionStoreOpenCount}")
        val laneRatio = if (s.intake > 0) s.laneEval.toDouble() / s.intake else 0.0
        if (s.intake > 0 && laneRatio > 12.0) out += Fault(FaultCode.LANE_FANOUT_EXPLOSION, "HIGH", "laneEval/intake=${"%.2f".format(laneRatio)}")
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
        val birdeyeLocked = try { BirdeyeBudgetGate.snapshot().lockedDown || BirdeyeBudgetGate.snapshot().pctUsed >= 85.0 } catch (_: Throwable) { false }
        if (badApis.isNotEmpty() || birdeyeLocked) out += Fault(FaultCode.API_LAYER_DEGRADED, "MEDIUM", "badApis=${badApis.keys.joinToString(",")} birdeyeLocked=$birdeyeLocked")
        return out
    }
}
