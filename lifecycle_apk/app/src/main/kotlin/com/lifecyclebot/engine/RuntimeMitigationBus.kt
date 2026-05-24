package com.lifecyclebot.engine

object RuntimeMitigationBus {
    sealed class Command(open val reason: String, open val ttlMs: Long) {
        data class DisableLane(val lane: String, override val reason: String, override val ttlMs: Long = 30_000L): Command(reason, ttlMs)
        data class DisableScannerSource(val source: String, override val reason: String, override val ttlMs: Long = 60_000L): Command(reason, ttlMs)
        data class ReduceScannerConcurrency(val value: Int, override val reason: String, override val ttlMs: Long = 60_000L): Command(reason, ttlMs)
        data class PauseTrading(override val reason: String, override val ttlMs: Long = 60_000L): Command(reason, ttlMs)
        data class DisablePreAuth(override val reason: String, override val ttlMs: Long = 30_000L): Command(reason, ttlMs)
        data class ForceQualityOnly(override val reason: String, override val ttlMs: Long = 30_000L): Command(reason, ttlMs)
        data class RestartSellReconciler(override val reason: String): Command(reason, 1_000L)
        data class QuarantineSource(val source: String, override val reason: String, override val ttlMs: Long = 60_000L): Command(reason, ttlMs)
    }
    data class Result(val applied: Boolean, val command: String, val detail: String)
    fun publish(cmd: Command): Result = when (cmd) {
        is Command.DisableLane -> { RuntimeConfigOverlay.disableLane(cmd.lane, cmd.reason, cmd.ttlMs); Result(true, "DisableLane", cmd.lane) }
        is Command.DisableScannerSource -> { RuntimeConfigOverlay.disableScannerSource(cmd.source, cmd.reason, cmd.ttlMs); Result(true, "DisableScannerSource", cmd.source) }
        is Command.ReduceScannerConcurrency -> { RuntimeConfigOverlay.reduceScannerConcurrency(cmd.value, cmd.reason, cmd.ttlMs); Result(true, "ReduceScannerConcurrency", cmd.value.toString()) }
        is Command.PauseTrading -> { RuntimeConfigOverlay.pauseTrading(cmd.reason, cmd.ttlMs); RuntimeRepairState.pauseTrading(cmd.reason); Result(true, "PauseTrading", cmd.reason) }
        is Command.DisablePreAuth -> { RuntimeConfigOverlay.disablePreAuth(cmd.reason, cmd.ttlMs); Result(true, "DisablePreAuth", cmd.reason) }
        is Command.ForceQualityOnly -> {
            RuntimeConfigOverlay.forcePrimaryLane("QUALITY", cmd.reason, cmd.ttlMs)
            listOf("TREASURY", "SHITCOIN", "EXPRESS", "MANIPULATED", "MOONSHOT", "DIP_HUNTER", "PROJECT_SNIPER", "BLUECHIP", "LAB", "SHADOW", "CORE")
                .forEach { lane -> RuntimeConfigOverlay.disableLane(lane, "force_quality_only:${cmd.reason}", cmd.ttlMs) }
            Result(true, "ForceQualityOnly", cmd.reason)
        }
        is Command.RestartSellReconciler -> { BotRuntimeController.markSellReconcilerStarted(BotRuntimeController.currentGeneration(), true); Result(true, "RestartSellReconciler", cmd.reason) }
        is Command.QuarantineSource -> { RuntimeConfigOverlay.quarantineSource(cmd.source, cmd.reason, cmd.ttlMs); Result(true, "QuarantineSource", cmd.source) }
    }
}
