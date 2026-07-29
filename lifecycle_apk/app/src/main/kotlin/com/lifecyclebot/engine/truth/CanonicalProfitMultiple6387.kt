package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * V5.0.6387 — DIRECTIVE B P0 — CANONICAL PROFIT MULTIPLE + 10X VALIDATION +
 * EXIT REASON SEMANTICS + EXECUTOR BOUNDARY DEFENSE.
 */
object CanonicalProfitMultiple6387 {
    /**
     * canonicalNetMultiple =
     *   (cumulativeRealisedProceedsLamports + grossPositionValueLamports)
     *   / canonicalOriginalCostBasisLamports
     *
     * Where grossPositionValueLamports = remainingRawQty × currentLamportsPerRawToken.
     */
    fun compute(
        originalCostBasisLamports: Lamports,
        cumulativeRealisedProceedsLamports: Lamports,
        remainingRawQty: RawTokenAmount,
        currentLamportsPerRawToken: BigDecimal,
    ): BigDecimal {
        if (originalCostBasisLamports.value.signum() <= 0) return BigDecimal.ZERO
        val gross = BigDecimal(remainingRawQty.value).multiply(currentLamportsPerRawToken)
        val numerator = BigDecimal(cumulativeRealisedProceedsLamports.value).add(gross)
        return numerator.divide(BigDecimal(originalCostBasisLamports.value), MathContext(20, RoundingMode.HALF_UP))
    }
}

/**
 * SEMANTIC EXIT-REASON INVARIANT.
 * Profit-labelled reasons include: QUICK_RUNNER_6X, QUICK_RUNNER_10X, PROFIT_LOCK,
 * TAKE_PROFIT, MOONSHOT_MULTIPLE_EXIT, RUNNER_BANK, MFE_PROFIT_EXIT, or any reason
 * containing PROFIT / RUNNER / MULTIPLE / 6X / 10X / TAKE_PROFIT.
 */
object ExitReasonSemantics6387 {
    private val PROFIT_KEYWORDS = arrayOf("PROFIT", "RUNNER", "MULTIPLE", "6X", "10X", "TAKE_PROFIT")
    fun isProfitExitReason(reason: String): Boolean {
        val up = reason.uppercase()
        return PROFIT_KEYWORDS.any { up.contains(it) }
    }
    /** Threshold-specific: QUICK_RUNNER_10X_FULL_EXIT implies canonicalCurrentPnlPct >= 900. */
    fun requiredMinPct(reason: String): Double = when {
        reason.contains("10X", true) -> 900.0
        reason.contains("6X", true) -> 500.0
        else -> 0.0
    }
    data class ContradictionCheck(val ok: Boolean, val reason: String)
    fun checkContradiction(exitReason: String, canonicalNetPnlLamports: Long, canonicalCurrentPnlPct: Double): ContradictionCheck {
        if (!isProfitExitReason(exitReason)) return ContradictionCheck(true, "not_profit_reason")
        if (canonicalNetPnlLamports <= 0)
            return ContradictionCheck(false, "EXIT_REASON_PNL_CONTRADICTION_6387 reason=$exitReason pnl=$canonicalNetPnlLamports")
        val minPct = requiredMinPct(exitReason)
        if (minPct > 0.0 && canonicalCurrentPnlPct < minPct)
            return ContradictionCheck(false, "EXIT_REASON_PCT_CONTRADICTION_6387 reason=$exitReason pct=$canonicalCurrentPnlPct minRequired=$minPct")
        return ContradictionCheck(true, "OK")
    }
    fun emitContradiction(check: ContradictionCheck) {
        if (check.ok) return
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_REASON_PNL_CONTRADICTION_6387")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("EXIT_REASON_PNL_CONTRADICTION_6387", check.reason)
        } catch (_: Throwable) {}
    }
}

/**
 * QUICK_RUNNER_10X_FULL_EXIT — 13-point validation.
 * Every check must pass; any failure emits PROFIT_EXIT_INVARIANT_REJECTED_6387.
 * The failed trigger must NOT fall through to another provider.
 */
object QuickRunner10xValidator6387 {
    data class Snapshot(
        val canonicalPositionId: String?,
        val buyFillFinalised: Boolean,
        val originalCostBasisLamports: Lamports,
        val remainingRawQtyWalletProven: Boolean,
        val entryPrice: CanonicalTokenPrice6387?,
        val currentPrice: CanonicalTokenPrice6387?,
        val peakPrice: CanonicalTokenPrice6387?,
        val canonicalNetMultiple: BigDecimal,
        val canonicalNetPnlLamports: Long,
        val canonicalCurrentPnlPct: Double,
        val canonicalPeakPnlPct: Double,
        val qtyConservationOk: Boolean,
        val basisConservationOk: Boolean,
        val walletAssetClass: WalletAssetClass6387,
        val revalidatedImmediatelyBeforeLease: Boolean,
    )

    data class Result(val allowed: Boolean, val failedCheck: String)

    fun validate(s: Snapshot): Result {
        if (s.canonicalPositionId.isNullOrBlank()) return Result(false, "1_NO_POSITION_ID")
        if (!s.buyFillFinalised) return Result(false, "2_BUY_NOT_FINALISED")
        if (s.originalCostBasisLamports.value.signum() <= 0) return Result(false, "3_BASIS_ZERO_OR_UNKNOWN")
        if (!s.remainingRawQtyWalletProven) return Result(false, "4_REMAINING_QTY_NOT_WALLET_PROVEN")
        val e = s.entryPrice ?: return Result(false, "5A_ENTRY_PRICE_MISSING")
        val c = s.currentPrice ?: return Result(false, "5B_CURRENT_PRICE_MISSING")
        val id = PriceIdentityInvariant6387.check(e, c)
        if (!id.compatible) {
            PriceIdentityInvariant6387.emitFailure(id.reason, e, c)
            return Result(false, "5_PRICE_IDENTITY:${id.reason}")
        }
        if (s.canonicalNetMultiple < BigDecimal("10.0")) return Result(false, "6_MULTIPLE_LT_10")
        if (s.canonicalNetPnlLamports <= 0) return Result(false, "7_NET_PNL_NOT_POSITIVE")
        if (s.canonicalCurrentPnlPct < 900.0) return Result(false, "8_CURRENT_PCT_LT_900")
        if (s.canonicalPeakPnlPct < 900.0) return Result(false, "9_PEAK_PCT_LT_900")
        if (!s.qtyConservationOk || !s.basisConservationOk) return Result(false, "10_CONSERVATION_FAIL")
        if (s.walletAssetClass == WalletAssetClass6387.BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN)
            return Result(false, "11_RECOVERED_UNKNOWN_BASIS")
        if (s.walletAssetClass.isNonTradable()) return Result(false, "12_NON_TRADABLE")
        if (!s.revalidatedImmediatelyBeforeLease) return Result(false, "13_NOT_REVALIDATED_BEFORE_LEASE")
        return Result(true, "OK")
    }

    fun emitRejection(failedCheck: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PROFIT_EXIT_INVARIANT_REJECTED_6387")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "PROFIT_EXIT_INVARIANT_REJECTED_6387", "failedCheck=$failedCheck",
            )
        } catch (_: Throwable) {}
    }
}

/**
 * EXECUTOR BOUNDARY DEFENSE — independent, mandatory even if the strategy
 * layer is fixed. Rejects a submitted sell intent labelled as profit exit
 * when canonical PnL contradicts it.
 */
object ExecutorBoundaryDefense6387 {
    data class BoundaryCheck(val allowed: Boolean, val reason: String)
    fun check(exitReason: String, canonicalCurrentPnlPct: Double, canonicalNetPnlLamports: Long,
              claimedMultiple: BigDecimal, recomputedMultiple: BigDecimal,
              entry: CanonicalTokenPrice6387?, current: CanonicalTokenPrice6387?, positionVersion: Long): BoundaryCheck {
        // Rule A: QUICK_RUNNER_10X_FULL_EXIT requires >= 900%.
        if (exitReason.equals("QUICK_RUNNER_10X_FULL_EXIT", true) && canonicalCurrentPnlPct < 900.0) {
            emit(exitReason, canonicalCurrentPnlPct, canonicalNetPnlLamports, claimedMultiple, recomputedMultiple, entry, current, positionVersion, "10X_REQUIRES_900PCT")
            return BoundaryCheck(false, "EXECUTOR_BLOCKED_FALSE_PROFIT_EXIT_6387:10X_REQUIRES_900PCT")
        }
        // Rule B: any profit-labelled reason requires positive canonical PnL.
        if (ExitReasonSemantics6387.isProfitExitReason(exitReason) && canonicalNetPnlLamports <= 0) {
            emit(exitReason, canonicalCurrentPnlPct, canonicalNetPnlLamports, claimedMultiple, recomputedMultiple, entry, current, positionVersion, "PROFIT_REASON_NEGATIVE_PNL")
            return BoundaryCheck(false, "EXECUTOR_BLOCKED_FALSE_PROFIT_EXIT_6387:PROFIT_REASON_NEGATIVE_PNL")
        }
        return BoundaryCheck(true, "OK")
    }
    private fun emit(reason: String, pct: Double, pnl: Long, claim: BigDecimal, recomp: BigDecimal,
                     e: CanonicalTokenPrice6387?, c: CanonicalTokenPrice6387?, v: Long, tag: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXECUTOR_BLOCKED_FALSE_PROFIT_EXIT_6387")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "EXECUTOR_BLOCKED_FALSE_PROFIT_EXIT_6387",
                "reason=$reason pct=$pct pnl=$pnl claimed=$claim recomputed=$recomp " +
                    "entryDen=${e?.denomination} currentDen=${c?.denomination} " +
                    "entryHash=${e?.identityHash} currentHash=${c?.identityHash} " +
                    "positionVersion=$v tag=$tag",
            )
        } catch (_: Throwable) {}
    }
}
