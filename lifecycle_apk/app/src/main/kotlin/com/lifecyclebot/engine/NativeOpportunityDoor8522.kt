package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.*

/** Carries the elected operator choice to the original lane door; never changes raw score or confidence. */
object NativeOpportunityDoor8522 {
    private fun eligible(raw: Boolean, reason: String, choice: NativeOpportunityPolicy8522.Decision?) =
        NativeOpportunityPolicy8522.doorEligible8522(raw, reason, choice)
    private fun reason(raw: String, choice: NativeOpportunityPolicy8522.Decision?, changed: Boolean) =
        if (changed) choice?.reason ?: raw else raw
    // A proposal uses the existing smallest native probe. FDG and OrderSizeResolver still own executable sizing.
    private fun proposal(raw: Double, enter: Boolean): Double = if (enter) raw.coerceAtLeast(0.01) else 0.0
    private fun tp(raw: Double, lane: String): Double = if (raw > 0.0) raw else HoldingLogicLayer.getHoldParams(lane).targetProfitPct
    private fun sl(raw: Double, lane: String): Double = if (raw < 0.0) raw else HoldingLogicLayer.getHoldParams(lane).stopLossPct

    fun quality8522(s: QualityTraderAI.QualitySignal, p: NativeOpportunityPolicy8522.Decision?): QualityTraderAI.QualitySignal {
        val enter = eligible(s.shouldEnter, s.reason, p)
        return if (enter == s.shouldEnter) s else s.copy(shouldEnter = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            reason = reason(s.reason, p, true))
    }

    fun blueChip8522(s: BlueChipTraderAI.BlueChipSignal, p: NativeOpportunityPolicy8522.Decision?): BlueChipTraderAI.BlueChipSignal {
        val enter = eligible(s.shouldEnter, s.reason, p)
        return if (enter == s.shouldEnter) s else s.copy(shouldEnter = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            takeProfitPct = if (enter) tp(s.takeProfitPct, "BLUECHIP") else s.takeProfitPct,
            stopLossPct = if (enter) sl(s.stopLossPct, "BLUECHIP") else s.stopLossPct, reason = reason(s.reason, p, true))
    }

    fun cashflow8522(s: CashGenerationAI.TreasurySignal, p: NativeOpportunityPolicy8522.Decision?): CashGenerationAI.TreasurySignal {
        val enter = eligible(s.shouldEnter, s.reason, p)
        return if (enter == s.shouldEnter) s else s.copy(shouldEnter = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            takeProfitPct = if (enter) tp(s.takeProfitPct, p?.lane ?: "CASHGEN") else s.takeProfitPct,
            stopLossPct = if (enter) sl(s.stopLossPct, p?.lane ?: "CASHGEN") else s.stopLossPct, reason = reason(s.reason, p, true))
    }

    fun moonshot8522(s: MoonshotTraderAI.MoonshotScore, p: NativeOpportunityPolicy8522.Decision?): MoonshotTraderAI.MoonshotScore {
        val enter = eligible(s.eligible, s.rejectReason, p)
        return if (enter == s.eligible) s else s.copy(eligible = enter, suggestedSizeSol = proposal(s.suggestedSizeSol, enter),
            rejectReason = reason(s.rejectReason, p, true))
    }

    fun manipulated8522(s: ManipulatedTraderAI.ManipSignal, p: NativeOpportunityPolicy8522.Decision?): ManipulatedTraderAI.ManipSignal {
        val enter = eligible(s.shouldEnter, s.reason, p)
        return if (enter == s.shouldEnter) s else s.copy(shouldEnter = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            reason = reason(s.reason, p, true))
    }

    fun express8522(s: ShitCoinExpress.ExpressSignal, p: NativeOpportunityPolicy8522.Decision?): ShitCoinExpress.ExpressSignal {
        val enter = eligible(s.shouldRide, s.reason, p)
        return if (enter == s.shouldRide) s else s.copy(shouldRide = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            rideType = if (enter && s.rideType == ShitCoinExpress.RideType.NO_RIDE) ShitCoinExpress.RideType.QUICK_FLIP else s.rideType,
            estimatedGainPct = if (enter && s.estimatedGainPct <= 0.0) ShitCoinExpress.RideType.QUICK_FLIP.targetPct else s.estimatedGainPct,
            reason = reason(s.reason, p, true))
    }

    fun shitcoin8522(s: ShitCoinTraderAI.ShitCoinSignal, p: NativeOpportunityPolicy8522.Decision?): ShitCoinTraderAI.ShitCoinSignal {
        val enter = eligible(s.shouldEnter, s.reason, p)
        return if (enter == s.shouldEnter) s else s.copy(shouldEnter = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            takeProfitPct = if (enter) tp(s.takeProfitPct, "SHITCOIN") else s.takeProfitPct,
            stopLossPct = if (enter) sl(s.stopLossPct, "SHITCOIN") else s.stopLossPct, reason = reason(s.reason, p, true))
    }

    fun sniper8522(s: ProjectSniperAI.TargetAssessment, p: NativeOpportunityPolicy8522.Decision?): ProjectSniperAI.TargetAssessment {
        val enter = eligible(s.shouldEngage, s.reason, p)
        return if (enter == s.shouldEngage) s else s.copy(shouldEngage = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            reason = reason(s.reason, p, true))
    }

    fun dip8522(s: DipHunterAI.DipSignal, p: NativeOpportunityPolicy8522.Decision?, currentPrice: Double, highPrice: Double): DipHunterAI.DipSignal {
        val enter = eligible(s.shouldBuy, s.reason, p)
        if (enter && !s.shouldBuy && (!currentPrice.isFinite() || currentPrice <= 0.0 || !highPrice.isFinite() || highPrice <= currentPrice)) return s
        return if (enter == s.shouldBuy) s else s.copy(shouldBuy = enter, positionSizeSol = proposal(s.positionSizeSol, enter),
            dipQuality = if (enter && s.dipQuality == DipHunterAI.DipQuality.NO_DIP) DipHunterAI.DipQuality.RISKY_DIP else s.dipQuality,
            dipDepthPct = if (enter && s.dipDepthPct <= 0.0) (highPrice - currentPrice) / highPrice * 100.0 else s.dipDepthPct,
            reason = reason(s.reason, p, true))
    }
}
