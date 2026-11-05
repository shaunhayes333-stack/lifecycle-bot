package com.lifecyclebot.v3.scoring

/**
 * V5.0.8535 §P1-04 / §P1-05 — demonstrated saturation is penalised in every lane and every recovery mode.
 *
 * MOONSHOT paid +18 for buy pressure above 75 % with no local penalty when the pump was already one-sided (SHITCOIN
 * docks >= 85 %, MANIPULATED docks momentum x pressure); CASHGEN paid +30 for >= 70 % and +20 for momentum >= 10 while its
 * own source calls those bands anti-predictive, and refused the overheated entry only inside the AGGRESSIVE
 * WR-recovery band — a mode-dependent discontinuity. TREASURY docked exhaustion but not a one-sided book.
 *
 * Rising, persistent demand stays rewarded by each lane's own bands; this object subtracts only for the saturation
 * signature (a near-unanimous one-sided book, a parabolic leg, or both), the same in every mode. Pure; graded like
 * every score term by the lane's labels.
 */
object SaturationPenalty8535 {

    const val BP_SATURATED_8535 = 90.0
    const val BP_ONE_SIDED_8535 = 85.0
    const val SCALP_MOMENTUM_PARABOLIC_8535 = 20.0
    const val SCALP_MOMENTUM_LATE_8535 = 12.0

    /** MOONSHOT: a one-sided book while the launch is no longer pre-ignition is the blow-off signature. */
    fun moonshot8535(buyPressurePct: Double, phase: String): Int {
        if (!buyPressurePct.isFinite()) return 0
        val late = phase.trim().uppercase().let { it == "EXPANDING" || it == "POST_PUMP_FADE" }
        return when {
            buyPressurePct >= BP_SATURATED_8535 && late -> 22
            buyPressurePct >= BP_SATURATED_8535 -> 12
            buyPressurePct >= BP_ONE_SIDED_8535 && late -> 10
            else -> 0
        }
    }

    /** CASHGEN (0-100 treasury scale): the overheated scalp entry, whatever the recovery mode. */
    fun scalp8535(buyPressurePct: Double, momentumPct: Double): Int {
        val bp = buyPressurePct.takeIf { it.isFinite() } ?: 0.0
        val mom = momentumPct.takeIf { it.isFinite() } ?: 0.0
        return when {
            bp >= BP_ONE_SIDED_8535 && mom >= SCALP_MOMENTUM_LATE_8535 -> 30
            bp >= BP_SATURATED_8535 || mom >= SCALP_MOMENTUM_PARABOLIC_8535 -> 20
            bp >= BP_ONE_SIDED_8535 || mom >= SCALP_MOMENTUM_LATE_8535 -> 10
            else -> 0
        }
    }

    /** TREASURY brain (0-100 composite): a saturated book or a maxed momentum composite is not a scalable scalp. */
    fun treasury8535(pressScore: Double, momScore: Double): Int {
        val p = pressScore.takeIf { it.isFinite() } ?: 0.0
        val m = momScore.takeIf { it.isFinite() } ?: 0.0
        return when {
            p >= BP_ONE_SIDED_8535 && m >= 90.0 -> 18
            p >= BP_SATURATED_8535 || m >= 95.0 -> 10
            else -> 0
        }
    }
}
