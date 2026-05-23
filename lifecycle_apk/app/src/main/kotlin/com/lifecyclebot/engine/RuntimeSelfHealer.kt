package com.lifecyclebot.engine

object RuntimeSelfHealer {
    enum class Action { PAUSE_TRADING, SWITCH_LIVE_TO_PAPER, DISABLE_LANE, DISABLE_SCANNER_SOURCE, QUARANTINE_TOKEN, RESTART_SELL_RECONCILER, CLEAR_STALE_LOCKS, REDUCE_SCANNER_CONCURRENCY, FORCE_UI_RUNTIME_REBIND, NEW_RUNTIME_GENERATION_AFTER_CLEAN_RESTART }
    data class Request(val action: Action, val target: String = "", val reason: String)
    data class Result(val applied: Boolean, val action: Action, val detail: String)

    private val forbiddenText = listOf("edit kotlin", "push main", "deploy apk", "private key", "wallet secret", "live transaction", "enable live")

    fun apply(req: Request): Result {
        if (forbiddenText.any { req.reason.contains(it, true) }) return Result(false, req.action, "forbidden unsafe request")
        return when (req.action) {
            Action.PAUSE_TRADING -> { RuntimeRepairState.pauseTrading(req.reason); Result(true, req.action, "trading paused") }
            Action.SWITCH_LIVE_TO_PAPER -> { RuntimeRepairState.requestPaperMode(req.reason); Result(true, req.action, "paper requested; live not re-enabled automatically") }
            Action.DISABLE_LANE -> { RuntimeRepairState.disableLane(req.target, req.reason); Result(true, req.action, "lane disabled ${req.target}") }
            Action.DISABLE_SCANNER_SOURCE -> { RuntimeRepairState.disableScannerSource(req.target, req.reason); Result(true, req.action, "scanner source disabled ${req.target}") }
            Action.QUARANTINE_TOKEN -> { QuarantineStore.quarantine(req.target, reason = req.reason); Result(true, req.action, "token quarantined ${req.target.take(10)}") }
            Action.RESTART_SELL_RECONCILER -> { BotRuntimeController.markSellReconcilerStarted(BotRuntimeController.currentGeneration(), true); Result(true, req.action, "sell reconciler restart requested") }
            Action.CLEAR_STALE_LOCKS -> { RuntimeRepairState.noteStaleLocksCleared(0L, req.reason); Result(true, req.action, "stale lock clear requested") }
            Action.REDUCE_SCANNER_CONCURRENCY -> { RuntimeRepairState.setScannerConcurrencyCap(req.target.toIntOrNull() ?: 2, req.reason); Result(true, req.action, "scanner cap set ${RuntimeRepairState.scannerCap()}") }
            Action.FORCE_UI_RUNTIME_REBIND -> { RuntimeRepairState.forceUiRuntimeRebind(req.reason); Result(true, req.action, "ui/runtime rebind requested") }
            Action.NEW_RUNTIME_GENERATION_AFTER_CLEAN_RESTART -> Result(false, req.action, "requires explicit operator restart approval; not automatic")
        }
    }
}
