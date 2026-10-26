package com.lifecyclebot.engine

/** A full-exit request is handled even when rejected; only its completed result can acknowledge a rung. */
internal object ProfitEscalationReceipt8397 {
    fun request8397(sell: () -> Executor.SellResult): Executor.PartialSellReceipt6566 {
        val result = sell()
        // Match the full-size partial's canonical completion contract. ALREADY_CLOSED is an existing
        // terminal position, not a newly booked fill; no proceeds or new trade are created here.
        val completed = result == Executor.SellResult.CONFIRMED || result == Executor.SellResult.PAPER_CONFIRMED ||
            result == Executor.SellResult.ALREADY_CLOSED
        return Executor.PartialSellReceipt6566(
            applied = completed,
            terminal = completed,
            authorityResult = "ESCALATED_FULL_8189_${result.name}",
        )
    }
}
