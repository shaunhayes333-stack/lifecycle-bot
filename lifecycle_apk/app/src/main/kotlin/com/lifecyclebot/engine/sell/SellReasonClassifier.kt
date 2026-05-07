package com.lifecyclebot.engine.sell

/**
 * V5.9.495z39 — operator spec item 3.
 *
 * Maps free-form sell reason strings used across the executor + sub-traders
 * into the canonical [ExitReason] enum. This is the single place where
 * the legacy string taxonomy is interpreted; once a SellIntent has been
 * built, downstream code should consume the enum and never the raw string.
 *
 * The classifier is deterministic and case-insensitive. Unknown reasons
 * default to [ExitReason.UNKNOWN] — they may then be promoted to a
 * specific reason by the caller if context is available.
 */
object SellReasonClassifier {

    fun fromString(reason: String?): ExitReason {
        if (reason.isNullOrBlank()) return ExitReason.UNKNOWN
        val r = reason.uppercase()
        return when {
            r.contains("MANUAL")            -> ExitReason.MANUAL_FULL_EXIT
            r.contains("RUG") ||
            r.contains("CATASTROPHE") ||
            r.contains("LIQUIDITY_COLLAPSE") ||
            r.contains("DRAIN_EXIT") ||
            r.contains("EMERGENCY")          -> ExitReason.RUG_DRAIN
            r.contains("HARD_FLOOR") ||
            r.contains("HARD_STOP")          -> ExitReason.HARD_STOP
            r.contains("CAPITAL_RECOVERY") ||
            r.contains("RECOVER")            -> ExitReason.CAPITAL_RECOVERY
            r.contains("STOP_LOSS") ||
            r.contains("STOPLOSS") ||
            r.contains("HARD_SL") ||
            r.contains("UNIVERSAL_HARD_FLOOR_SL") -> ExitReason.STOP_LOSS
            r.contains("PARTIAL_TAKE_PROFIT") ||
            r.contains("PARTIAL_PROFIT_LOCK") ||
            r.contains("PARTIAL")            -> ExitReason.PARTIAL_TAKE_PROFIT
            r.contains("PROFIT_LOCK") ||
            r.contains("TAKE_PROFIT") ||
            r.contains("DRAWDOWN_FROM_PEAK") ||
            r.contains("MICROWIN")           -> ExitReason.PROFIT_LOCK
            else -> ExitReason.UNKNOWN
        }
    }

    /**
     * Variant for the full-balance liveSell path. Partial-class reasons
     * (PROFIT_LOCK / PARTIAL_TAKE_PROFIT / CAPITAL_RECOVERY) are promoted
     * to HARD_STOP because liveSell drains 100% of the wallet balance.
     */
    fun fullExitFromString(reason: String?): ExitReason {
        return when (val classified = fromString(reason)) {
            ExitReason.PROFIT_LOCK,
            ExitReason.PARTIAL_TAKE_PROFIT,
            ExitReason.CAPITAL_RECOVERY,
            ExitReason.UNKNOWN     -> ExitReason.HARD_STOP
            ExitReason.STOP_LOSS   -> ExitReason.HARD_STOP
            else -> classified
        }
    }
}
