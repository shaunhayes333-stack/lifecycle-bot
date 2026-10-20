package com.lifecyclebot.engine

/**
 * V5.0.8253 §EXIT_REASON_INVARIANT — the 6312 invariant ("a reason that claims profit with realised PnL <= 0 is
 * rewritten and excluded from learning") matched the word RUNNER alone, so a runner lane's own LOSS exits
 * (RUNNER_HARD_STOP_7967, RUNNER_STRUCTURE_BREAK_7967, RUNNER_FROM_ENTRY_GIVEBACK) were rewritten to
 * FALLBACK_AFTER_FAILED_PROFIT_EXIT_6312 and dropped from learning as EXIT_REASON_INVARIANT_MARKER: the one lane the
 * operator wants taught never learned its stops. A profit claim is a take, a bank, a milestone or a multiple; a
 * reason that names a stop, a break, a giveback, a floor or a failure claims nothing.
 */
object ExitReasonInvariant8253 {
    private val LOSS_WORDS_8253 = listOf("STOP", "BREAK", "CATASTROPHE", "GIVEBACK", "FLOOR", "DEAD", "FAIL", "RELEASED",
        "UNTRUSTED", "EARLY_EXIT", "SHADOW", "DISTRIBUTION", "COLLAPSE", "RUG", "EMERGENCY", "RECOVERY")
    private val PROFIT_WORDS_8253 = listOf("QUICK_RUNNER", "TAKE_PROFIT", "BANK", "PROFIT_TARGET", "MILESTONE", "10X", "6X", "RUNNER")

    /** Pure: does [reason] claim a profit exit (so realised PnL <= 0 contradicts it)? */
    fun claimsProfit8253(reason: String): Boolean {
        val r = reason.uppercase()
        if (LOSS_WORDS_8253.any { it in r }) return false
        return PROFIT_WORDS_8253.any { it in r }
    }
}
