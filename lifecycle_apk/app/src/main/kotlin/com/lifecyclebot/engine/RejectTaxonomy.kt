package com.lifecyclebot.engine

/**
 * V5.0.4423 — canonical reject taxonomy helper.
 *
 * Strict doctrine mapping:
 * - pending/defer = PENALTY
 * - low-liq/liquidity = LOW_LIQ_SIZE_REDUCTION
 * - unprofitable/cost/slippage/fee = COST_REJECT
 * Hard safety remains hard. This helper is read-only; it does not change gate
 * decisions, execution authority, sizing, or retry behavior.
 */
object RejectTaxonomy {
    enum class Category {
        HARD_SAFETY,
        COST_REJECT,
        PENALTY,
        LOW_LIQ_SIZE_REDUCTION,
        ADVISORY,
        DUPLICATE_POSITION,
        RECOVERY_PROBE,
        UNKNOWN_REVIEW,
    }

    data class Classification(
        val category: Category,
        val trainable: Boolean,
        val hardSafety: Boolean,
        val normalizedReason: String,
    )

    fun classify(reason: String, blockLevel: TradeAuthorizer.BlockLevel? = null): Classification {
        val r = reason.uppercase()
        val category = when {
            r.contains("ZERO_LIQUIDITY") || r.contains("NO_LIQUIDITY") -> Category.HARD_SAFETY
            r.contains("LOW_LIQ") || r.contains("LOW LIQ") || r.contains("LIQUIDITY") || r.startsWith("LIQ=") -> Category.LOW_LIQ_SIZE_REDUCTION
            r.contains("UNPROFITABLE") || r.contains("COST") || r.contains("SLIPPAGE") || r.contains("FEE") -> Category.COST_REJECT
            r.contains("PENDING") || r.contains("DEFER") || r.contains("COOLDOWN") || r.contains("WAIT") -> Category.PENALTY
            r.contains("RECOVERY_PROBE") || r.contains("PROBE") -> Category.RECOVERY_PROBE
            r.contains("ALREADY_OPEN") || r.contains("DUPLICATE") || r.contains("ONE-MINT") -> Category.DUPLICATE_POSITION
            r.contains("RUG") || r.contains("BANNED") || r.contains("FINALITY") || r.contains("SAFETY") || r.contains("RUNTIME_PAUSED") || r.contains("LP_UNLOCK") || blockLevel == TradeAuthorizer.BlockLevel.PERMANENT -> Category.HARD_SAFETY
            r.contains("LANE_TELEMETRY") || r.contains("QUALITY_ONLY") || r.contains("ADVISORY") || blockLevel == TradeAuthorizer.BlockLevel.SOFT -> Category.ADVISORY
            blockLevel == TradeAuthorizer.BlockLevel.HARD -> Category.HARD_SAFETY
            else -> Category.UNKNOWN_REVIEW
        }
        return Classification(
            category = category,
            trainable = category != Category.HARD_SAFETY && category != Category.UNKNOWN_REVIEW,
            hardSafety = category == Category.HARD_SAFETY,
            normalizedReason = r.take(96),
        )
    }

    fun status(): String = "REJECT_TAXONOMY_4423 pending=PENALTY low_liq=LOW_LIQ_SIZE_REDUCTION unprofitable=COST_REJECT hard_safety_preserved=true report_only=true no_execution_authority=true trade_authorizer_consumed_4424=true ledger_consumed_4425=true fdg_consumed_4427=true executor_preattempt_consumed_4428=true scanner_hard_reject_consumed_4429=true learning_label_sentinel_4430=true zero_liquidity_hard_safety=true"
}
