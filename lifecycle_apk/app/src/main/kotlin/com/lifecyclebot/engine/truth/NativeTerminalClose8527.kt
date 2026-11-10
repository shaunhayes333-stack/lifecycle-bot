package com.lifecyclebot.engine.truth

import com.lifecyclebot.v3.scoring.ShitCoinTraderAI as Shit
import com.lifecyclebot.v3.scoring.MoonshotTraderAI as Moon
import com.lifecyclebot.v3.scoring.QualityTraderAI as Quality
import com.lifecyclebot.v3.scoring.BlueChipTraderAI as Blue
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI as Manip
import com.lifecyclebot.v3.scoring.ShitCoinExpress as Express
import com.lifecyclebot.v3.scoring.DipHunterAI as Dip
import com.lifecyclebot.v3.scoring.CashGenerationAI as CashGen
import com.lifecyclebot.v3.scoring.ProjectSniperAI as Sniper

/** A quantity-terminal partial must use the existing native terminal owners before its receipt callback retires. */
object NativeTerminalClose8527 {
    fun close8527(receipt: CanonicalPartialReceipt8527?, exitPriceUsd: Double): Boolean {
        if (receipt == null || !receipt.terminal || !exitPriceUsd.isFinite() || exitPriceUsd <= 0.0) return false
        val row = CanonicalPositionAuthority6441.getPosition(receipt.positionId) ?: return false
        if (!receipt.belongsToHistory8527(row)) return false
        val paper = receipt.mode == "paper"
        if (!com.lifecyclebot.engine.SpecialistCloseProjection7858.mayClose(receipt.mint, paper, receipt.openedAtMs)) return false
        val mint = receipt.mint
        val win = receipt.realizedPnlPct >= 1.0
        // These methods retain their existing once-only bookkeeping, release and secondary-learning ownership.
        try { CashGen.closePosition(mint, exitPriceUsd, if (win) CashGen.ExitSignal.TAKE_PROFIT else CashGen.ExitSignal.STOP_LOSS, isPaper = paper) } catch (_: Exception) {}
        try { Shit.closePosition(mint, exitPriceUsd, if (win) Shit.ExitSignal.TAKE_PROFIT else Shit.ExitSignal.STOP_LOSS, paper) } catch (_: Exception) {}
        try { Moon.closePosition(mint, exitPriceUsd, if (win) Moon.ExitSignal.TAKE_PROFIT else Moon.ExitSignal.STOP_LOSS, paper) } catch (_: Exception) {}
        try { Quality.closePosition(mint, exitPriceUsd, if (win) Quality.ExitSignal.TAKE_PROFIT else Quality.ExitSignal.STOP_LOSS, paper) } catch (_: Exception) {}
        try { Blue.closePosition(mint, exitPriceUsd, if (win) Blue.ExitSignal.TAKE_PROFIT else Blue.ExitSignal.STOP_LOSS, paper) } catch (_: Exception) {}
        try { Manip.closePosition(mint, exitPriceUsd, if (win) Manip.ManipExitSignal.TAKE_PROFIT else Manip.ManipExitSignal.STOP_LOSS, paper) } catch (_: Exception) {}
        closeReceiptOwner(receipt, exitPriceUsd)
        try { com.lifecyclebot.v3.scoring.SellOptimizationAI.closePosition(mint, receipt.realizedPnlPct,
            canonicalSlice8527 = receipt) } catch (_: Exception) {}
        try { com.lifecyclebot.engine.TradeAuthorizer.releasePosition(mint = mint,
            reason = "CANONICAL_TERMINAL_PARTIAL_8527", isPaperMode = paper) } catch (_: Exception) {}
        return true
    }
    fun closeReceiptOwner(receipt: CanonicalPartialReceipt8527, exitPriceUsd: Double) {
        val row = CanonicalPositionAuthority6441.getPosition(receipt.positionId) ?: return
        if (!receipt.terminal || !receipt.belongsToHistory8527(row) || !exitPriceUsd.isFinite() || exitPriceUsd <= 0.0) return
        val mint = receipt.mint
        val paper = receipt.mode == "paper"
        val win = WholePositionEconomics7835.from(row).returnPct > 0.0
        try {
            val expectedRide8527 = Express.getActiveRides().firstOrNull { it.mint == mint && it.isPaper == paper }
            if (CanonicalLaneIdentity6506.canonical(row.lane).equals("EXPRESS", true) && expectedRide8527 != null)
                Express.exitRide(mint, exitPriceUsd, if (win) Express.ExitSignal.TAKE_PROFIT_30 else Express.ExitSignal.STOP_LOSS,
                    canonicalSlice8527 = receipt, expectedRide8527 = expectedRide8527)
        } catch (_: Exception) {}
        try {
            val expectedMission8527 = Sniper.getMission(mint)
            if (CanonicalLaneIdentity6506.canonical(row.lane).equals("PROJECT_SNIPER", true) && expectedMission8527 != null)
                Sniper.completeMission(mint, exitPriceUsd, Sniper.ExitSignal(
                    shouldExit = true, exitPct = 100,
                    reason = if (win) "TAKE_PROFIT" else "STOP_LOSS", rank = Sniper.SniperRank.PENDING),
                    canonicalSlice8527 = receipt, expectedMission8527 = expectedMission8527)
        } catch (_: Exception) {}
        try {
            if (CanonicalLaneIdentity6506.canonical(row.lane).equals("DIP_HUNTER", true) && Dip.getActiveDips().any { it.mint == mint && it.isPaper == paper })
                Dip.closeDip(mint, exitPriceUsd, if (win) Dip.DipExitSignal.RECOVERY_TARGET else Dip.DipExitSignal.STOP_LOSS,
                    canonicalSlice8527 = receipt)
        } catch (_: Exception) {}
    }

}
