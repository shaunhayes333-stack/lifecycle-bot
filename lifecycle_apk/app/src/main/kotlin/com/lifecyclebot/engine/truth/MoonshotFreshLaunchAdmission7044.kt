package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.ModeRouter
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7044 — MOONSHOT was structurally barred from the only archetype that
 * produces a moonshot.
 *
 * THE OPERATOR'S REPORT. "moonshot used to be the best returning and performing
 * lane by far. its barely trading now. its meant to be the lane that captures
 * huge profits." Their own worked example is Saadboi: bought at 14k market cap,
 * should have come out near 4 million. That is a ~285x, and every trade of that
 * shape starts as a token minutes old with a four- or five-figure market cap.
 *
 * WHAT THE SNAPSHOT SAID. 5.0.7040:
 *
 *   MOONSHOT        candidateN=134 qualifiedN=134 ownerSelectedN=3 markN=0
 *                   sizedN=0 ticketN=0 execN=0 status=SIZING_CHOKED
 *   PROJECT_SNIPER  5.6% n=18 (flagged bleeder, 35 open)
 *   LIFECYCLE/CYCLE_PRIMARY_LANE ... type=FRESH_LAUNCH primary=PROJECT_SNIPER
 *
 * and the executions PROJECT_SNIPER was taking were mcap=$4196, $3767, $4698,
 * $4210 — precisely the Saadboi profile, at a 5.6% win rate.
 *
 * THE CAUSE, which is three separate exclusions pointing the same way:
 *
 *   1. BotService.laneAffinityForTradeType mapped
 *        FRESH_LAUNCH -> {SHITCOIN, PROJECT_SNIPER, EXPRESS, MANIPULATED}
 *      MOONSHOT reached only BREAKOUT_CONTINUATION and GRADUATION. A breakout
 *      needs `hist.size >= 10` before ModeRouter will even score it, so by the
 *      time a token can be classified into a lane MOONSHOT is allowed to own,
 *      it has already run. MOONSHOT was given the second half of every move.
 *
 *   2. Every style a fresh launch can elect excludes MOONSHOT's lane set —
 *      DEGEN_MICRO_SNIPE, MICRO_SNIPE, QUICK_FLIP all resolve to
 *      {PROJECT_SNIPER, SHITCOIN, EXPRESS, MANIPULATED}. The single exception
 *      is REGIME_DEFENSIVE_PROBE, which carries MOONSHOT at sizeMult 0.35 and
 *      holdMult 0.55 — a probe, the exact opposite of a runner hold.
 *
 *   3. canonicalCycleLaneFor re-permits a PROJECT_SNIPER desk hypothesis
 *      specifically when the setup is DEGEN_MICRO_SNIPE or
 *      PUMP_GRADUATION_SNIPE, which is the fresh-launch setup. PROJECT_SNIPER
 *      was explicitly let back in at the one gate MOONSHOT could not pass.
 *
 * So `markN=0` is not a mark bug. Nothing ever asks for a MOONSHOT mark because
 * MOONSHOT almost never owns a candidate: 3 of 134. The lane is not choked, it
 * is not being fed.
 *
 * WHAT THIS ADMITS, AND WHAT IT DELIBERATELY DOES NOT. This does not hand
 * MOONSHOT the fresh-launch pool — that would just move PROJECT_SNIPER's 5.6%
 * into a different column. It admits the window in which the operator's stated
 * objective is physically available: a 100x out of $4k mcap is $400k and
 * happens on this chain every day; a 100x out of $2M is $200M and does not.
 * Above MCAP_RUNNER_CEILING_USD the trade is no longer the trade they described,
 * so MOONSHOT does not claim it.
 *
 * It is an OWNERSHIP decision, not a buy decision. Every gate MOONSHOT already
 * runs — scoring, eligibility, FDG, sizing, rug and toxicity guards — still
 * applies unchanged afterward. Nothing here can cause a buy; it can only change
 * which trader is asked, and therefore which hold and take-profit profile the
 * trade is expressed with. PROJECT_SNIPER is not amputated from FRESH_LAUNCH
 * (doctrine #105); it keeps the candidates outside this window and competes for
 * the ones inside it.
 *
 * COUNTERS. Every branch below has its own name. An admission and a refusal and
 * a "the market cap never loaded" are three different facts, and this session
 * has already spent three builds on counters that collapsed a refusal that did
 * not happen into one that did.
 */
object MoonshotFreshLaunchAdmission7044 {

    /**
     * The upper edge of the window. Chosen from the objective, not from a
     * backtest: the lane exists to capture 10x-1000x, and above this the
     * arithmetic of that multiple stops being something the Solana memecoin
     * market actually produces. Saadboi's entry (14k) sits an order of
     * magnitude inside it; the executions PROJECT_SNIPER was taking (~$4k) sit
     * two.
     */
    private const val MCAP_RUNNER_CEILING_USD = 150_000.0

    /**
     * Below this the "market cap" is almost always a bonding-curve artifact
     * from the first block rather than a price anyone traded at, and the
     * multiple computed off it is fiction.
     */
    // V5.0.7266 — public: MoonshotTraderAI reads these for a runner-shaped
    // fresh launch so the admission window and the lane's own floor are the
    // same authority instead of two numbers that disagree ($500 vs $10k).
    const val MCAP_FLOOR_USD = 500.0

    /** Enough of a pool that an exit is a real transaction and not a wish. */
    const val LIQ_FLOOR_USD = 800.0

    /**
     * Liquidity as a fraction of market cap. A shell with $30k "mcap" and $200
     * of pool is not a runner candidate, it is a rug waiting for a buyer. Kept
     * low because this is an ownership filter, not the lane's safety check —
     * MOONSHOT's own rug and toxicity guards run after this and are stricter.
     */
    private const val LIQ_TO_MCAP_MIN = 0.015

    /**
     * AgenticStyleRouter already treats buy pressure in 45..55 as the NO
     * INFORMATION band (`lowInfoFresh`). Requiring more than the top of that
     * band means there is an actual demand signal, not just a listing.
     */
    private const val BUY_PRESSURE_MIN_PCT = 55.0

    /** Age ceiling, in minutes, matching ModeRouter's own FRESH_LAUNCH window. */
    private const val FRESH_AGE_MAX_MIN = 15.0

    data class Verdict(
        val admit: Boolean,
        val reason: String,
        val mcapUsd: Double,
        val liqUsd: Double,
        val buyPressurePct: Double,
    ) {
        val compact: String
            get() = "admit=$admit reason=$reason mcap=${mcapUsd.toInt()} liq=${liqUsd.toInt()} bp=${buyPressurePct.toInt()}"
    }

    private fun ageMinutes(ts: TokenState): Double = try {
        ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0)
    } catch (_: Throwable) {
        Double.MAX_VALUE
    }

    /**
     * The shape test. Ordered so the counter that fires names the first thing
     * that was actually wrong, and so "we never learned the market cap" is
     * never reported as "the candidate failed the market-cap test".
     */
    fun assess(ts: TokenState, tradeType: ModeRouter.TradeType): Verdict {
        val mcap = try { ts.lastMcap } catch (_: Throwable) { 0.0 }
        val liq = try { ts.lastLiquidityUsd } catch (_: Throwable) { 0.0 }
        val bp = try { ts.lastBuyPressurePct } catch (_: Throwable) { 0.0 }

        fun no(reason: String) = Verdict(false, reason, mcap, liq, bp)

        if (tradeType != ModeRouter.TradeType.FRESH_LAUNCH) return no("NOT_FRESH_LAUNCH")
        if (ageMinutes(ts) > FRESH_AGE_MAX_MIN) return no("AGE_PAST_FRESH_WINDOW")
        // Data absence is its own answer. It is not evidence against the token.
        if (!mcap.isFinite() || mcap <= 0.0) return no("MCAP_UNKNOWN")
        if (!liq.isFinite() || liq <= 0.0) return no("LIQ_UNKNOWN")
        if (mcap < MCAP_FLOOR_USD) return no("MCAP_BELOW_FLOOR")
        if (mcap > MCAP_RUNNER_CEILING_USD) return no("MCAP_ABOVE_RUNNER_CEILING")
        if (liq < LIQ_FLOOR_USD) return no("LIQ_BELOW_FLOOR")
        if (liq / mcap < LIQ_TO_MCAP_MIN) return no("LIQ_TO_MCAP_SHELL")
        if (!bp.isFinite() || bp < BUY_PRESSURE_MIN_PCT) return no("NO_DEMAND_SIGNAL")
        return Verdict(true, "RUNNER_SHAPED", mcap, liq, bp)
    }

    /**
     * Predicate form, for the style path. Counts separately from the election
     * path so the snapshot can show how often MOONSHOT owned a runner-shaped
     * launch but was still about to express it as a flip.
     */
    fun isRunnerShaped(ts: TokenState, tradeType: ModeRouter.TradeType): Boolean =
        try { assess(ts, tradeType).admit } catch (_: Throwable) { false }

    /**
     * Ownership election. Returns MOONSHOT only for a runner-shaped fresh
     * launch that some other lane was about to take. An explicit operator
     * forced-primary always wins; so does a primary that is already MOONSHOT.
     */
    fun electPrimary(
        ts: TokenState,
        classification: ModeRouter.Classification,
        currentPrimary: String,
        operatorForced: String?,
    ): String {
        return try {
            if (!operatorForced.isNullOrBlank()) return currentPrimary
            if (currentPrimary.equals("MOONSHOT", ignoreCase = true)) {
                try { PipelineHealthCollector.labelInc("MOONSHOT_FRESH_ADMIT_7044_ALREADY_PRIMARY") } catch (_: Throwable) {}
                return currentPrimary
            }
            val v = assess(ts, classification.tradeType)
            if (!v.admit) {
                // Only fresh launches are interesting to count here; everything
                // else walks past this authority untouched and saying so 134
                // times a cycle would bury the reasons that matter.
                if (classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH) {
                    try { PipelineHealthCollector.labelInc("MOONSHOT_FRESH_DECLINED_7044_${v.reason}") } catch (_: Throwable) {}
                }
                return currentPrimary
            }
            try {
                PipelineHealthCollector.labelInc("MOONSHOT_FRESH_ADMIT_7044_FROM_$currentPrimary")
                ForensicLogger.lifecycle(
                    "MOONSHOT_FRESH_LAUNCH_ADMITTED_7044",
                    "symbol=${ts.symbol} mint=${ts.mint.take(10)} was=$currentPrimary now=MOONSHOT ${v.compact}",
                )
            } catch (_: Throwable) {}
            "MOONSHOT"
        } catch (_: Throwable) {
            currentPrimary
        }
    }
}
