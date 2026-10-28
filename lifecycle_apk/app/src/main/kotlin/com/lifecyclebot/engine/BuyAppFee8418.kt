package com.lifecyclebot.engine

import com.lifecyclebot.network.SolanaWallet

/**
 * V5.0.8418 — THE APP'S 0.5 % BUY FEE, RESTORED (owner: "re add the 0.5% fee on the buy side. leave the performance
 * based fee on the sell side.").
 *
 * 5.0.8391 (17836cb9d) removed the per-side volume fee everywhere and deleted Executor.sendFeeSplit. This object brings
 * back the BUY half only:
 *  - fee = the CONFIRMED cost of the buy (finalized tx SOL spent less the network fee; a top-up's wallet-verified
 *    cost) x [CostTrim8151.BUY_APP_FEE_PCT_8418] %, split 50/50 between the two fee wallets;
 *  - each share goes to [FeeAccumulator.accrue] (the batched pool FeeAccumulator.tryFlush drains), a failed accrual is
 *    handed to [FeeRetryQueue], shares under [FEE_SEND_MIN_SOL_8418] are dust-skipped, and a share addressed to the
 *    trading wallet itself is redirected to the other fee wallet (both self: skipped) — the restored sendFeeSplit;
 *  - the fee is booked into the canonical position's feesSol ([CanonicalPositionAuthority6441.bookAppFee8418]) so the
 *    live terminal P&L (WholePositionEconomics7835) and the performance-fee base are net of it.
 * Sells, partial sells and profit locks pay NO volume fee ([CostTrim8151.SELL_APP_FEE_PCT_8418] = 0); winning sells pay
 * PerformanceFees8391 only.
 */
object BuyAppFee8418 {
    const val WALLET_1_8418 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
    const val WALLET_2_8418 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
    private const val SPLIT_RATIO_8418 = 0.5
    /** Executor.FEE_SEND_MIN_SOL: a share below this is dust and is not accrued. */
    private const val FEE_SEND_MIN_SOL_8418 = 0.000005

    /** Where a fee share goes. [LiveSink8418] is the real pool; tests pass a recorder. */
    interface Sink8418 {
        /** Accrue [sol] to [dest]; the new bucket balance (0.0 = not accrued). May throw on a persistence failure. */
        fun accrue(dest: String, sol: Double, tag: String): Double
        /** Hand [sol] for [dest] to the retry queue. */
        fun enqueue(dest: String, sol: Double, tag: String)
    }

    private object LiveSink8418 : Sink8418 {
        override fun accrue(dest: String, sol: Double, tag: String): Double = FeeAccumulator.accrue(dest, sol, tag)
        override fun enqueue(dest: String, sol: Double, tag: String) = FeeRetryQueue.enqueue(dest, sol, tag)
    }

    /** Pure: the app fee on a buy that cost [confirmedCostSol] (0 for an unknown / non-positive cost). */
    fun buyFeeSol8418(confirmedCostSol: Double): Double =
        if (!confirmedCostSol.isFinite() || confirmedCostSol <= 0.0) 0.0
        else confirmedCostSol * CostTrim8151.BUY_APP_FEE_PCT_8418 / 100.0

    /** Pure: the app volume fee on a sell of [basisSol] — none (V5.0.8418; winners pay PerformanceFees8391). */
    fun sellFeeSol8418(basisSol: Double): Double =
        if (!basisSol.isFinite() || basisSol <= 0.0) 0.0
        else basisSol * CostTrim8151.SELL_APP_FEE_PCT_8418 / 100.0

    /** Pure: the fee that is actually charged and booked — [buyFeeSol8418] when it clears the dust rule, else 0. */
    fun bookedFeeSol8418(confirmedCostSol: Double): Double {
        val fee = buyFeeSol8418(confirmedCostSol)
        return if (fee >= FEE_SEND_MIN_SOL_8418) fee else 0.0
    }

    /** Pure: the destination for a share meant for [primary] when the trading wallet is [self]. */
    private fun dest8418(self: String, primary: String, fallback: String): String? = when {
        !primary.equals(self, false) -> primary
        !fallback.equals(self, false) -> fallback   // redirect self -> other
        else -> null                                 // both self — skip
    }

    /** The restored Executor.sendFeeSplit (5.0.8390): true when at least one share was accrued. */
    private fun sendFeeSplit8418(self: String, amount1: Double, amount2: Double, tag: String, sink: Sink8418): Boolean {
        val a1 = share8418(self, amount1, WALLET_1_8418, WALLET_2_8418, "${tag}_w1", sink)
        val a2 = share8418(self, amount2, WALLET_2_8418, WALLET_1_8418, "${tag}_w2", sink)
        return a1 || a2
    }

    private fun share8418(self: String, amount: Double, primary: String, fallback: String, tag: String, sink: Sink8418): Boolean {
        if (amount < FEE_SEND_MIN_SOL_8418) {
            if (amount > 0.0) try { ErrorLogger.debug("BuyAppFee8418", "fee share ($tag) dust-skipped: $amount SOL < $FEE_SEND_MIN_SOL_8418") } catch (_: Throwable) {}
            return false
        }
        val d = dest8418(self, primary, fallback)
        if (d == null) {
            try { ErrorLogger.warn("BuyAppFee8418", "fee share ($tag) skipped: both fee wallets == self") } catch (_: Throwable) {}
            return false
        }
        return try {
            val bucket = sink.accrue(d, amount, tag)
            if (bucket > 0.0) {
                try { com.lifecyclebot.engine.truth.FeeAccrualObservability6439.noteAccrue(d, amount, tag, false) } catch (_: Throwable) {}
                true
            } else {
                try { PipelineHealthCollector.labelInc("FEE_ACCRUE_RETURNED_ZERO_7124") } catch (_: Throwable) {}
                false
            }
        } catch (e: Exception) {
            // Accumulator persistence failed — fall back to the retry queue.
            sink.enqueue(d, amount, "${tag}_acc_fail")
            false
        }
    }

    /**
     * Charge the buy fee on [confirmedCostSol] for the trading wallet [self]: split 50/50 through [sendFeeSplit8418];
     * if the split itself throws, both shares go to the retry queue. Returns the fee charged (0 = below dust).
     */
    fun chargeBuy8418(self: String, confirmedCostSol: Double, tag: String, sink: Sink8418 = LiveSink8418): Double {
        val fee = bookedFeeSol8418(confirmedCostSol)
        if (fee <= 0.0) return 0.0
        val w1 = fee * SPLIT_RATIO_8418
        val w2 = fee * (1.0 - SPLIT_RATIO_8418)
        try {
            sendFeeSplit8418(self, w1, w2, tag, sink)
        } catch (e: Exception) {
            try { ErrorLogger.error("BuyAppFee8418", "BUY FEE SEND FAILED ($tag) — queued for retry: ${e.message}") } catch (_: Throwable) {}
            try { sink.enqueue(WALLET_1_8418, w1, "${tag}_w1") } catch (_: Throwable) {}
            try { sink.enqueue(WALLET_2_8418, w2, "${tag}_w2") } catch (_: Throwable) {}
        }
        return fee
    }

    /**
     * Executor (finalized live BUY, live top-up): book the fee on the canonical position [positionId] (idempotent per
     * [signature]) and charge it from [wallet]. Returns the fee charged.
     */
    fun chargeLiveBuy8418(
        wallet: SolanaWallet,
        confirmedCostSol: Double,
        positionId: String,
        signature: String,
        mint: String,
        tag: String,
        onLog: (String, String) -> Unit,
    ): Double {
        val fee = bookedFeeSol8418(confirmedCostSol)
        if (fee <= 0.0) return 0.0
        val booked = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.bookAppFee8418("APP_BUY_FEE_8418:$tag:$signature", positionId, fee)
        } catch (_: Throwable) { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }
        try { PipelineHealthCollector.labelInc("APP_BUY_FEE_BOOKED_${booked.name}_8418") } catch (_: Throwable) {}
        val self = try { wallet.publicKeyB58 } catch (_: Throwable) { "" }
        val charged = chargeBuy8418(self, confirmedCostSol, tag)
        try { onLog("💸 APP FEE: ${"%.6f".format(charged)} SOL (${CostTrim8151.BUY_APP_FEE_PCT_8418}% of confirmed BUY) split 50/50", mint) } catch (_: Throwable) {}
        return charged
    }
}
