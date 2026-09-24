package com.lifecyclebot.engine.truth

/**
 * V5.0.7277 §A POSITION SMALLER THAN ITS FIXED COST IS A DONATION.
 *
 * Operator's 5.0.7274 ledger: realized +2.10 SOL, fees 2.03 SOL, 812 ledger
 * operations, positions of 0.05–0.25 SOL. The fixed leg of a Solana round
 * trip (two priority fees, two base fees) does not shrink with the order, so
 * every multiplier that shrank the notional raised cost as a percentage of
 * the position. 7162 already refuses a trade whose forecast edge cannot clear
 * its cost; this sets the smallest notional at which the FIXED leg alone is
 * an acceptable share, so the sizing stack cannot land on a size that pays
 * more in fixed fees than any edge could return. Percentage legs (spread,
 * slippage) depend on liquidity, not size, and remain the cost gate's job.
 *
 *   fixed round trip ≈ 2 × 0.0008 SOL priority + 2 × 0.000005 SOL base
 *   floor = fixed / MAX_FIXED_COST_FRACTION
 */
object FeeAwareSizeFloor7277 {
    /** Two priority fees at LiveBreakEvenGuard's 0.0008 SOL plus two base fees. */
    private const val FIXED_ROUND_TRIP_SOL = 0.00161

    /** The fixed leg may be at most this share of the notional. */
    const val MAX_FIXED_COST_FRACTION = 0.015

    private const val LAMPORTS_PER_SOL = 1_000_000_000.0

    /**
     * Smallest notional at which the fixed leg is within the share above
     * (≈0.107 SOL), rounded UP to a whole lamport.
     *
     * V5.0.7281 §THREE TRILLIONTHS OF A SOL CLOSED THE DOOR. The raw quotient
     * is 0.10733333333333335, a repeating decimal. OrderSizeResolver6441
     * quantises every size to lamports, so a floor-promoted ticket came back
     * as 0.107333333 SOL, and Executor.paperBuy's atomic commit compared that
     * against this raw double with `<` — true by 3e-12 — and rolled the fill
     * back as NON_EXECUTABLE_SIZE_OR_QTY. Every ticket promoted to the floor
     * since 7277 died there: 35 on 5.0.7278, 117 on 7279, and 416 of 430 on
     * 7280 once the ticket was bounded to the sized figure. A floor that is
     * exact in lamports round-trips through the resolver unchanged.
     */
    fun minimumSol(): Double =
        kotlin.math.ceil(FIXED_ROUND_TRIP_SOL / MAX_FIXED_COST_FRACTION * LAMPORTS_PER_SOL) / LAMPORTS_PER_SOL
}
