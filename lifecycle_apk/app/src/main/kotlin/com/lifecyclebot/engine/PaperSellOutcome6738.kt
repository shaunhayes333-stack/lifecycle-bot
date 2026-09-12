package com.lifecyclebot.engine

/** A wait, lock collision or failed quote is not evidence that a position closed. */
object PaperSellOutcome6738 {
    fun blocked(guard: PaperPositionCloseAuthority.Guard): Executor.SellResult =
        if (guard.state == PaperPositionCloseAuthority.State.CLOSED) Executor.SellResult.ALREADY_CLOSED
        else Executor.SellResult.FAILED_RETRYABLE

    fun isTerminal(result: Executor.SellResult): Boolean = when (result) {
        Executor.SellResult.CONFIRMED, Executor.SellResult.PAPER_CONFIRMED,
        Executor.SellResult.ALREADY_CLOSED -> true
        else -> false
    }
}
