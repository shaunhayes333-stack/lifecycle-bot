package com.lifecyclebot.engine

import org.json.JSONObject

/**
 * V5.0.7017 §THE_UNIT_BUG_THAT_FROZE_A_HUNDRED_POSITIONS.
 *
 * WHAT THE OPERATOR'S 5.0.7012 SNAPSHOT SHOWS
 * ===========================================
 * Three open positions, three cross-basis refusals, all against the same tick
 * source:
 *
 *   POPINU  entry=4.4507569e-06 (DEXSCREENER)  tick=2.956118906040716e-12  refusals=101
 *   LOVE$   entry=3.1312285e-06 (DEXSCREENER)  tick=2.956441331177225e-12  refusals=641
 *   ?       entry=6.4185908e-04 (DEXSCREENER)  tick=6.389582887844357e-12
 *
 * The first tell is that POPINU and LOVE$ — different tokens at different
 * prices — report almost the same tick, 2.9561e-12 and 2.9564e-12. Prices do
 * not coincide to four significant figures by accident.
 *
 * The second tell is arithmetic. The same snapshot prints each position's
 * market cap, and the pump.fun derivation is mcap / supply:
 *
 *   POPINU  mcap $4,239    → 4239   / 1e9 = 4.239e-06   ≈ its entry ✓
 *                          → 4239   / 1e15 = 4.239e-12  ≈ its tick ✓
 *   ?       mcap $638,666  → 638666 / 1e9 = 6.387e-04   = its entry to 3 s.f. ✓
 *                          → 638666 / 1e15 = 6.387e-12  = its tick to 3 s.f. ✓
 *
 * Every position is off by exactly 1e6, which is 10^6, which is the decimals of
 * a standard pump.fun mint. `total_supply` from frontend-api-v3.pump.fun is in
 * RAW BASE UNITS — 1e15 for the standard 1,000,000,000-token supply — and both
 * call sites divided market cap by it as though it were whole tokens.
 *
 * WHY THIS MATTERED SO MUCH MORE THAN A WRONG NUMBER
 * ==================================================
 * The bad price never reached P&L, because V5.0.6895's cross-basis guard caught
 * it — a DexScreener entry against a pump.fun tick a millionfold apart is far
 * outside the 10x band, so the mark was refused. That guard did its job.
 *
 * But look at what refusal serves (Executor.kt ~line 858): the last on-route
 * price if fresh, otherwise **entryPrice**. So the position is marked at
 * exactly what it was bought at, and the snapshot shows the consequence:
 *
 *   OPEN_PNL_BASIS_REJECTED ... entry=4.4507569e-06 current=4.4507569e-06 ratio=1.0
 *   Exit scheduler: eval=195694 SL=0 CATA=4 TP=0 TRAIL=0
 *
 * A position pinned at 0.00% P&L can never hit a stop, a take-profit or a
 * trail. 195,694 exit evaluations produced four exits, all catastrophe latches.
 * The positions became immortal, canonical open reached the 100 hard cap, and:
 *
 *   EXEC_GATE/reason=POSITION_HARD_CAP_EXIT_THROUGHPUT: 191
 *   ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758: 1229
 *   Order size resolver last=[req=0.14179 ... final=0.00000 exec=false]
 *
 * — which is the operator's "meme trader is barely buying or selling", in one
 * causal chain, from one misread field. The bot was not being cautious. It was
 * holding a hundred positions it had made itself unable to price.
 *
 * THE FIX
 * =======
 * One derivation, used by both call sites, that converts raw supply to whole
 * tokens before dividing. It does not simply assume 6 decimals:
 *
 *   1. use the payload's own `decimals` when it carries one;
 *   2. otherwise pick the scaling whose result is a plausible whole-token
 *      supply, which self-corrects if pump.fun ever changes the field's units
 *      in either direction;
 *   3. otherwise fall back to the pump.fun standard 1e9.
 *
 * Guessing a unit is exactly what caused this. Preferring the payload's own
 * answer, then a plausibility test, then a documented constant, is the order
 * that cannot be wrong silently — and step 2 emits telemetry naming which
 * interpretation it chose, so the next person does not have to do the
 * arithmetic above from a log.
 */
object PumpFunPriceUnits7017 {

    /** pump.fun's standard mint: 1,000,000,000 whole tokens. */
    const val STANDARD_WHOLE_SUPPLY = 1_000_000_000.0

    /**
     * A whole-token supply this far outside the standard is not a supply we
     * understand. Wide on purpose — real mints do vary — but narrow enough to
     * reject a raw-base-unit count, which is at least 1e6 times too large.
     */
    private const val MIN_PLAUSIBLE_WHOLE = 1_000.0
    private const val MAX_PLAUSIBLE_WHOLE = 1e12

    /**
     * USD price per WHOLE token from a frontend-api-v3.pump.fun `/coins/<mint>`
     * payload. Returns 0.0 when the payload cannot support a price, which every
     * caller already treats as "no quote" — never a guess.
     */
    fun priceUsd(json: JSONObject): Double {
        val mcap = json.optDouble("usd_market_cap", 0.0)
        if (!mcap.isFinite() || mcap <= 0.0) return 0.0
        val whole = wholeSupply(json)
        if (!whole.isFinite() || whole <= 0.0) return 0.0
        val price = mcap / whole
        return if (price.isFinite() && price > 0.0) price else 0.0
    }

    /**
     * Whole-token supply, resolving the raw/whole ambiguity explicitly.
     * Visible for the two call sites and for reasoning about a payload by hand.
     */
    fun wholeSupply(json: JSONObject): Double {
        val raw = json.optDouble("total_supply", 0.0)
        if (!raw.isFinite() || raw <= 0.0) {
            note("SUPPLY_ABSENT_STANDARD_ASSUMED")
            return STANDARD_WHOLE_SUPPLY
        }

        // 1. The payload's own decimals, when it carries them. This is the only
        //    branch that is not an inference.
        val decimals = json.optInt("decimals", -1)
        if (decimals in 0..18) {
            val scaled = raw / Math.pow(10.0, decimals.toDouble())
            if (plausible(scaled)) {
                note("SUPPLY_FROM_PAYLOAD_DECIMALS_$decimals")
                return scaled
            }
        }

        // 2. No usable decimals field.
        //
        // V5.0.7110 §MOST_SCALED_FIRST_WAS_STILL_A_GUESS, AND IT WAS WRONG BY 1e3.
        //
        // This loop ran intArrayOf(9, 8, 6) and returned the FIRST plausible
        // reading. For the standard pump.fun mint — 1e9 whole tokens at 6
        // decimals, so total_supply = 1e15 raw — that is:
        //
        //     d=9 → 1e15 / 1e9 = 1e6   plausible (band is 1e3..1e12) → RETURNED
        //     d=6 → 1e15 / 1e6 = 1e9   correct, never reached
        //
        // Supply resolved to 1e6 instead of 1e9, so every pump.fun price came
        // out exactly 1000x too high. The operator's 5.0.7106 device says it in
        // one line, with the chain supply next to the derived one:
        //
        //   METRICS_IDENTITY_BROKEN_7069 mint=DZ84Quh3Fw src=PUMP_FUN_FRONTEND_API
        //     reportedPrice=0.020919707  impliedPrice=0.000020919707
        //     mcap=20919  supply=1000000000  ratio=1000.00
        //
        // and every quarantined mint in that log divides out the same:
        // gainMultiple 5296.1 -> 5.3, 1005.5 -> 1.005, 1000.8 -> 1.0008,
        // 4635.0 -> 4.6. Those are ordinary holdings, not absurd ones.
        //
        // 7017 fixed a 1e6 error by preferring the most-scaled reading and, in
        // doing so, replaced one guess with another. First-match ordering is not
        // a decision procedure — it is whichever candidate happens to be tried
        // first, and with a band three orders wide on each side, several are.
        //
        // Choose by DISTANCE FROM THE DOCUMENTED STANDARD instead. pump.fun's
        // mint is 1e9 whole tokens and that constant is already declared above,
        // so "the reading closest to the supply this venue actually issues" is
        // evidence rather than ordering. It still self-corrects: a genuinely
        // different supply wins whenever it is the only plausible candidate, and
        // the chosen decimals are named in telemetry either way.
        data class Candidate7110(val label: String, val value: Double)
        val candidates7110 = ArrayList<Candidate7110>(4)
        for (d in intArrayOf(9, 8, 6)) {
            val scaled = raw / Math.pow(10.0, d.toDouble())
            if (plausible(scaled)) candidates7110 += Candidate7110("INFERRED_DECIMALS_$d", scaled)
        }
        if (plausible(raw)) candidates7110 += Candidate7110("ALREADY_WHOLE", raw)
        val best7110 = candidates7110.minByOrNull {
            kotlin.math.abs(kotlin.math.log10(it.value / STANDARD_WHOLE_SUPPLY))
        }
        if (best7110 != null) {
            note("SUPPLY_${best7110.label}")
            if (candidates7110.size > 1) {
                // More than one reading was in band, which is exactly the
                // condition first-match ordering silently resolved the wrong way.
                note("SUPPLY_AMBIGUOUS_RESOLVED_BY_STANDARD_7110")
            }
            return best7110.value
        }

        // 3. Nothing was plausible. Say so rather than dividing by a number we
        //    do not understand.
        note("SUPPLY_IMPLAUSIBLE_STANDARD_ASSUMED")
        return STANDARD_WHOLE_SUPPLY
    }

    private fun plausible(v: Double): Boolean =
        v.isFinite() && v >= MIN_PLAUSIBLE_WHOLE && v <= MAX_PLAUSIBLE_WHOLE

    private fun note(label: String) {
        try { PipelineHealthCollector.labelInc("PUMPFUN_SUPPLY_UNITS_7017_$label") } catch (_: Throwable) {}
    }
}
