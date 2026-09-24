package com.lifecyclebot.engine.truth

/**
 * V5.0.7280 §A SIX-X SPIKE THIRTY SECONDS OLD IS EXIT LIQUIDITY.
 *
 * 5.0.7279 tape, 26 minutes: QUALITY 1.538 SOL into EQdS6o at a $22.7k cap
 * ~30 s after a ~$3.5k create, sold 43 s later at −84% (−1.318 SOL, 13% of
 * the bankroll); PROJECT_SNIPER 0.832 SOL into 5DCa2Q (−0.222); 0.804 SOL
 * into Hnod49. The create frame carries the curve's price at t=0
 * (PumpCurveKeys7269.createPriceSol), so the multiple a ticket is paying
 * over the launch price is a measurement, not a guess.
 *
 * This does not block a trade and does not touch exits. Inside the fresh
 * window, a first ticket paying [CHASE_MULTIPLE]× or more over the create
 * price is sized at the executable minimum (the fee-aware floor) — a
 * lottery ticket, which is what a spike chase is — and the multiple is
 * bucketed for every curve buy so the next snapshot shows what the chases
 * did. The two numbers live here, in one place, for the operator to move.
 */
object LaunchChase7280 {

    const val FRESH_WINDOW_MS = 180_000L
    const val CHASE_MULTIPLE = 3.0

    fun isChase(entryMultiple: Double, ageMs: Long): Boolean =
        entryMultiple.isFinite() && entryMultiple >= CHASE_MULTIPLE &&
            ageMs in 0L..FRESH_WINDOW_MS

    fun bucket(entryMultiple: Double): String = when {
        !entryMultiple.isFinite() || entryMultiple <= 0.0 -> "UNKNOWN"
        entryMultiple < 1.0 -> "BELOW_CREATE"
        entryMultiple < 2.0 -> "X1_2"
        entryMultiple < 3.0 -> "X2_3"
        entryMultiple < 5.0 -> "X3_5"
        else -> "X5_PLUS"
    }
}
