package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.Trade

/** V5.0.3800 — paper simulator row sanity shared by journal + canonical learning. */
object PaperLearningSanity {
    data class Verdict(val ok: Boolean, val reason: String = "")

    fun configuredMinTradeSol(): Double {
        val cfg = loadCfg()
        val legacyMin = cfg.smallBuySol.takeIf { it.isFinite() && it > 0.0 } ?: BotConfig().smallBuySol
        // V5.0.3873 — same live-transfer floor as Executor.paperBuy().
        return maxOf(legacyMin, (cfg.paperSimulatedBalance * 0.01).coerceIn(0.05, 0.15))
    }

    fun configuredMaxTradeSol(): Double {
        val cfg = loadCfg()
        val min = configuredMinTradeSol()
        val legacyMax = maxOf(min, cfg.maxPositionSol.takeIf { it.isFinite() && it > 0.0 } ?: BotConfig().maxPositionSol)
        // V5.0.3873 — same live-transfer cap as Executor.paperBuy(). Do not
        // quarantine valid larger paper rows just because legacy maxPositionSol is 0.15.
        return maxOf(legacyMax, (cfg.paperSimulatedBalance * 0.10).coerceIn(legacyMax, 2.0))
    }

    fun inspect(t: Trade): Verdict {
        if (!isPaper(t.mode)) return Verdict(true)
        val maxSol = configuredMaxTradeSol()
        if (!t.sol.isFinite()) return Verdict(false, "PAPER_SOL_NOT_FINITE")
        if (t.side.equals("BUY", true) && t.sol <= 0.0) return Verdict(false, "PAPER_BUY_SOL_NON_POSITIVE")
        if (t.sol > maxSol + 0.0000001) return Verdict(false, "PAPER_SOL_ABOVE_CONFIG_MAX")
        if ((t.side.equals("SELL", true) || t.side.equals("PARTIAL_SELL", true)) && t.sol <= 0.0) return Verdict(false, "PAPER_SELL_COST_NON_POSITIVE")
        if ((t.side.equals("SELL", true) || t.side.equals("PARTIAL_SELL", true)) && t.sol > 0.0) {
            val pnlAbs = kotlin.math.abs(t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol)
            // Very loose: allows 1000x runners on valid configured entry sizes, but catches
            // decimal/fantasy wallet rows before they tune WR/PF/drawdown/lane memory.
            val maxReasonablePnlSol = maxOf(5.0, t.sol * 1000.0)
            if (!t.pnlPct.isFinite() || !t.pnlSol.isFinite() || !t.netPnlSol.isFinite()) return Verdict(false, "PAPER_PNL_NOT_FINITE")
            if (pnlAbs > maxReasonablePnlSol) return Verdict(false, "PAPER_PNL_IMPOSSIBLE_RELATIVE_TO_SIZE")
        }
        return Verdict(true)
    }

    fun emitQuarantine(t: Trade, reason: String) {
        try { PipelineHealthCollector.labelInc("PAPER_LEARNING_ROW_QUARANTINED") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("PAPER_LEARNING_ROW_QUARANTINED_$reason") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("PAPER_LEARNING_ROW_QUARANTINED", "mint=${t.mint.take(10)} side=${t.side} sol=${t.sol} pnlPct=${t.pnlPct} reason=$reason tradeReason=${t.reason.take(60)}") } catch (_: Throwable) {}
    }

    private fun isPaper(mode: String): Boolean {
        val m = mode.trim().lowercase()
        return m == "paper" || m == "shadow"
    }

    private fun loadCfg(): BotConfig {
        return try {
            val ctx = BotService.instance?.applicationContext ?: com.lifecyclebot.AATEApp.appContextOrNull()
            if (ctx != null) ConfigStore.load(ctx) else BotConfig()
        } catch (_: Throwable) { BotConfig() }
    }
}
