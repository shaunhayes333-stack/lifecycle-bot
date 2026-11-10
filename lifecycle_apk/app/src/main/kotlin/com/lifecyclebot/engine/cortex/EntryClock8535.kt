package com.lifecyclebot.engine.cortex

/**
 * V5.0.8535 §P0-03 / §P1-07 — the entry CLOCK each playbook setup trades on.
 *
 * The admission read one generic chart clock for every strategy: `entryWait8506 = NO_RECLAIM` (stage DUMPING /
 * PEAK_EXHAUSTION with no lane reclaim) refused a candidate even when the setup that fired is BY DEFINITION an entry
 * before the broad candle turns (MOMENTUM_PULLBACK_5M fires on a −6..−1 % five-minute inside a rising hour;
 * FIRST_DIP_BOUGHT on a 15-second buyer reversal; TREND_DIP_8383 / FIRST_PULLBACK_AFTER_RUN / PEAK_RECLAIM_8383 on a
 * drawdown with buyers back). Likewise FALLING_HOUR_UNPROVEN treated a negative hour as adverse for every momentum-lane
 * strategy, including RECLAIM_AFTER_WEAKNESS, whose whole thesis is a positive five-minute inside a negative hour.
 *
 * Each setup is classed by the clock its predicate encodes; the gates consult the class of the setup that FIRED, so
 * a fired strategy is never overruled by an unrelated strategy's chart clock. The classes are the audit's own:
 * (a) EARLY_BASE — before the first impulse; (b) IMPULSE_CONTINUATION — only with remaining post-fill impulse;
 * (c) REBOUND_RECLAIM — rebound / reclaim timing; (d) QUALITY_ONLY — token quality, no entry event of its own;
 * STRUCTURE — an external plan / structure read with its own witness.
 */
object EntryClock8535 {

    enum class Clock { EARLY_BASE, IMPULSE_CONTINUATION, REBOUND_RECLAIM, QUALITY_ONLY, STRUCTURE }

    private val EARLY = setOf(
        "EARLY_SUSTAINED_DEMAND", "PRE_IGNITION_BASE", "VERIFIED_LAUNCH", "LOW_RUNUP_BASE", "EARLY_BUYER_EXPANSION_8170", "FAST_CROWD",
        "DEV_HOLDS_CROWD_BUYS", "CLEAN_DEV_LAUNCH", "QUIET_ACCUMULATION", "LOW_VOL_COIL", "CROWD_FORMING", "LAUNCH_LADDER_PROVEN",
    )
    private val REBOUND = setOf(
        "MARKET_SECOND_WAVE_RECLAIM", "HIGHER_LOW_PULLBACK", "TREND_PULLBACK", "PLAN_PULLBACK_RECLAIM", "PLAN_SWEEP_RECLAIM", "RECLAIM_AFTER_WEAKNESS",
        "FIRST_PULLBACK", "HIGHER_LOW_CONTINUATION", "POST_EVENT_RECLAIM", "SWEEP_RECLAIM_FLOW", "CAPITULATION_HIGHER_LOW",
        "SUPPORT_FLIP", "DISTRIBUTION_RECLAIM", "RANGE_LOW_BOUNCE", "HL_RECLAIM", "FIRST_DIP_BOUGHT", "OPP_DIP_RECOVERY",
        "SECOND_LEG", "MEAN_REVERSION_OVERSOLD", "MOMENTUM_PULLBACK_5M", "RANGE_SUPPORT_SWING", "SECOND_WAVE",
        "MICRO_PULLBACK_TREND", "FIRST_PULLBACK_AFTER_RUN", "DIP_IN_UPTREND", "TREND_PULLBACK_8383", "RUNUP_HOLD_8383",
        "PEAK_RECLAIM_8383", "TREND_DIP_8383",
    )
    private val QUALITY = setOf(
        "SWEET_SPOT_8383", "YOUNG_LIQUID_8383", "DEEP_RATIO_8383", "HOLDER_SURGE", "SPECIALIST_EDGE", "LIVESTREAM_LAUNCH",
        "COPYCAT_BETA", "SOCIAL_LAUNCH", "BOOSTED_LAUNCH", "NARRATIVE_WAVE", "INSIDER_ACCUMULATION", "NO_BUNDLE_CLEAN",
        "LP_LOCKED_BASE", "DEEP_LIQ_TREND", "BROAD_DISTRIBUTION",
    )
    private val STRUCT = setOf("PLAN_BASE_BREAKOUT", "COLOUR_SEQUENCE", "EXPERT_ENTRY", "POST_MIGRATION_HOLD", "CTO_REVIVAL")

    fun of(setup: String?): Clock {
        val s = setup?.trim()?.uppercase().orEmpty()
        return when {
            s in REBOUND -> Clock.REBOUND_RECLAIM
            s in EARLY -> Clock.EARLY_BASE
            s in QUALITY -> Clock.QUALITY_ONLY
            s in STRUCT -> Clock.STRUCTURE
            else -> Clock.IMPULSE_CONTINUATION   // breakouts, continuations, ignitions, RS leaders, opportunity ranks
        }
    }

    /** A setup whose own clock is the pullback / dip / reclaim: the generic NO_RECLAIM wait is not its witness. */
    fun ownsReclaim(setup: String?): Boolean = of(setup) == Clock.REBOUND_RECLAIM

    /** A setup that by design acts before the hour turns: a falling hour is its context, not an adverse sign (P1-07). */
    fun actsBeforeHourTurns(setup: String?): Boolean = of(setup) == Clock.REBOUND_RECLAIM
}
