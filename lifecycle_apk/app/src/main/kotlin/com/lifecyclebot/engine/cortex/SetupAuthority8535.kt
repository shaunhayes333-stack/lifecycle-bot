package com.lifecyclebot.engine.cortex

/**
 * V5.0.8535 §P1-01 / §P1-03 / §P1-13 — a setup FIRING is not the same as a setup granting ENTRY authority.
 *
 * The 8529 run bought AT_TOP / NEAR_HIGH losers (25 LOSS_BOUGHT_TOP) because several predicates read "price near its
 * high" as positive evidence (BREAKOUT_HOLD pxPeak>=.95, BREAKOUT_RUNNER .90 + a 30-300 % run, NEW_HIGH_PUSH .99 + a
 * 40-400 % run, TX_VELOCITY_BREAK .90), nine 8383 evidence shapes are token-QUALITY conditions with no entry event
 * (SWEET_SPOT, YOUNG_LIQUID, DEEP_RATIO, STRONG_TREND, BAND_MOMENTUM, TREND_DIP ...), and the default-favour helper
 * `geOrUnknown(bp, 55)` let HOLDER_SURGE / LIVESTREAM_LAUNCH / COPYCAT_BETA fire with NO buy-pressure measurement.
 *
 * Every family stays in the library and keeps firing for grading and proof. What changes is the AUTHORITY a fire
 * carries into the admission:
 *   TOKEN_QUALITY      a ranking / suitability condition — never an entry event on its own
 *   SETUP_FORMING      fired on partial evidence (a required input unobserved) or a near-high read with no fresh
 *                      breakout witness — watched and graded, not executable
 *   SETUP_TRIGGERED    an actionable entry event on observed inputs
 * Only SETUP_TRIGGERED may be the admission's selected strategy (CandidateAdmission8136 adapter) or a fired option
 * (StrategyRank8153); a candidate whose only fires are quality / forming takes the generic path (entry-wait doors,
 * generic prior, economics) exactly as a coin with no fired setup. No gate is loosened.
 */
object SetupAuthority8535 {

    enum class Authority { TOKEN_QUALITY, SETUP_FORMING, SETUP_TRIGGERED }

    /** Token-quality / ranking conditions (no actionable entry event in the predicate). */
    val QUALITY_ONLY_8535: Set<String> = setOf(
        "SWEET_SPOT_8383", "YOUNG_LIQUID_8383", "DEEP_RATIO_8383", "STRONG_TREND_8383", "BAND_MOMENTUM_8383", "TREND_DIP_8383",
        "RS_LEADER", "DEEP_LIQ_TREND", "LP_LOCKED_BASE",
    )

    /** Predicates that fire on the price sitting at / near its high: they need a fresh breakout witness to be an entry. */
    val NEAR_HIGH_8535: Set<String> = setOf(
        "BREAKOUT_HOLD", "BREAKOUT_RUNNER", "TX_VELOCITY_BREAK", "NEW_HIGH_PUSH", "BUYERS_DOMINATING", "LOW_RUNUP_BASE", "LOW_VOL_COIL",
    )

    /** Predicates that pass an UNKNOWN buy pressure (`geOrUnknown(bp, ..)`): a fire without the measurement is partial. */
    val PARTIAL_ON_UNKNOWN_BP_8535: Set<String> = setOf(
        "HOLDER_SURGE", "LIVESTREAM_LAUNCH", "COPYCAT_BETA", "TURNOVER_SPIKE", "GRADUATION_APPROACH", "MICRO_CAP_IGNITION",
        "FRESH_LAUNCH_MOMENTUM",
    )

    const val WITNESS_VOL_ACCEL_8535 = 55.0
    const val WITNESS_TX_VELOCITY_8535 = 2.0
    const val WITNESS_CHG5M_PCT_8535 = 2.0
    const val WITNESS_MAX_RUNUP_PCT_8535 = 150.0

    /**
     * Pure: a fresh breakout witness for a near-high read — buyers observed and an OBSERVED acceleration (volume, tx
     * velocity or the 5-minute leg) with upside still available (the run from the low not already past
     * [WITNESS_MAX_RUNUP_PCT_8535]). An unknown acceleration is not a witness.
     */
    fun breakoutWitness8535(bp: Double, volAccel: Double, txVelocity: Double, chg5m: Double, runup: Double): Boolean {
        if (!bp.isFinite()) return false
        if (runup.isFinite() && runup > WITNESS_MAX_RUNUP_PCT_8535) return false
        return (volAccel.isFinite() && volAccel >= WITNESS_VOL_ACCEL_8535) ||
            (txVelocity.isFinite() && txVelocity >= WITNESS_TX_VELOCITY_8535) ||
            (chg5m.isFinite() && chg5m >= WITNESS_CHG5M_PCT_8535)
    }

    /** Pure: the authority a fire of [setup] carries on the observed inputs. */
    fun authorityOf8535(setup: String, bp: Double, volAccel: Double, txVelocity: Double, chg5m: Double, runup: Double): Authority = when {
        setup in QUALITY_ONLY_8535 -> Authority.TOKEN_QUALITY
        setup in PARTIAL_ON_UNKNOWN_BP_8535 && !bp.isFinite() -> Authority.SETUP_FORMING
        setup in NEAR_HIGH_8535 && !breakoutWitness8535(bp, volAccel, txVelocity, chg5m, runup) -> Authority.SETUP_FORMING
        else -> Authority.SETUP_TRIGGERED
    }

    fun authorityOf8535(setup: String, f: LanePlaybook7907.F?): Authority =
        authorityOf8535(setup, f?.bp ?: Double.NaN, f?.volAccel ?: Double.NaN, f?.txVelocity ?: Double.NaN,
            f?.chg5m ?: Double.NaN, f?.runup ?: Double.NaN)

    /** Pure: may this fire be the admission's executable strategy? */
    fun executable8535(setup: String, f: LanePlaybook7907.F?): Boolean = authorityOf8535(setup, f) == Authority.SETUP_TRIGGERED

    /** V5.0.8535 §P1-09 — desks whose READY must name one of their own fired strategies (a score band is not an entry). */
    val NAMED_SETUP_LANES_8535: Set<String> = setOf("CORE", "CYCLIC")

    fun requiresNamedSetup8535(lane: String): Boolean = lane.trim().uppercase() in NAMED_SETUP_LANES_8535
}
