package com.lifecyclebot.engine.truth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * V5.0.7801 — LANE-NATIVE OBJECTIVES.
 *
 * Canonical finality still owns economic truth (net PnL / return / fees).
 * This layer answers a different question: "Did this outcome satisfy the
 * specialist mandate?"  It is used for specialist hunting/learning only;
 * it never rewrites canonical PnL, finality, safety, execution or accounting.
 */
object SpecialistObjective7801 {
    data class Utility(
        val lane: String,
        val utility: Double,          // approximately -1..+3, lane-relative
        val mandateSuccess: Boolean,
        val magnitudeClass: String,
        val reason: String,
    )

    data class ExitQuality(
        val lane: String,
        val optimal: Boolean,
        val captureRatio: Double,
        val givebackPct: Double,
        val reason: String,
    )

    fun isTailLane(raw: String?): Boolean {
        val lane = canon(raw.orEmpty())
        return lane in setOf("MOONSHOT", "SHITCOIN", "PROJECT_SNIPER")
    }

    private fun canon(raw: String): String = raw.trim().uppercase()
        .replace("BLUE_CHIP", "BLUECHIP")
        .replace("SHITCOIN_EXPRESS", "EXPRESS")
        .replace("CASH_GENERATION", "CASHGEN")

    private fun perHour(retPct: Double, holdMs: Long): Double {
        val h = max(holdMs / 3_600_000.0, 1.0 / 60.0)
        return retPct / h
    }

    fun evaluate(laneRaw: String, returnPct: Double, holdMs: Long, exitReason: String = ""): Utility {
        val lane = canon(laneRaw)
        val r = if (returnPct.isFinite()) returnPct else 0.0
        val mins = max(holdMs / 60_000.0, 0.0)
        val ph = perHour(r, holdMs)
        val u: Double
        val success: Boolean
        val cls: String
        val why: String
        when (lane) {
            "MOONSHOT" -> {
                // +35% is economically green but a failed Moonshot payoff profile.
                u = when { r >= 1000 -> 3.0; r >= 500 -> 2.5; r >= 150 -> 1.6; r >= 75 -> 0.8; r >= 50 -> 0.35; r > 0 -> -0.15; r <= -20 -> -1.0; else -> -0.45 }
                success = r >= 150.0
                cls = when { r >= 1000 -> "MEGA_10X"; r >= 500 -> "RUNNER_5X"; r >= 150 -> "RUNNER_2_5X"; r > 0 -> "MICRO_WIN_FAILED_TAIL"; else -> "LOSS" }
                why = "fat_tail_return"
            }
            "PROJECT_SNIPER" -> {
                u = when { r >= 150 -> 2.0; r >= 50 -> 1.3; r >= 20 -> 0.8; r >= 8 -> 0.45; r > 0 -> 0.15; r <= -15 -> -1.0; else -> -0.45 }
                success = r >= 20.0
                cls = if (success) "EARLY_REPRICE_CAPTURED" else if (r > 0) "WEAK_REPRICE" else "FAILED_LAUNCH"
                why = "early_entry_reprice"
            }
            "EXPRESS" -> {
                val speed = (ph / 30.0).coerceIn(-1.5, 1.5)
                u = (speed + when { r >= 30 -> 0.8; r >= 12 -> 0.4; r > 0 -> 0.1; else -> -0.3 }).coerceIn(-1.5, 2.2)
                success = r >= 8.0 && mins <= 45.0
                cls = if (success) "FAST_VELOCITY_CAPTURE" else if (r > 0) "SLOW_OR_SMALL_CAPTURE" else "MOMENTUM_FAIL"
                why = "return_per_time"
            }
            "SHITCOIN" -> {
                u = when { r >= 500 -> 2.6; r >= 150 -> 1.7; r >= 50 -> 1.0; r >= 15 -> 0.5; r > 0 -> 0.15; r <= -20 -> -1.0; else -> -0.4 }
                success = r >= 50.0
                cls = if (r >= 150) "DEGEN_RUNNER" else if (success) "ASYMMETRIC_WIN" else if (r > 0) "SMALL_WIN" else "LOSS"
                why = "asymmetric_degen_return"
            }
            "MANIPULATED" -> {
                val speed = (ph / 40.0).coerceIn(-1.0, 1.0)
                u = (speed + when { r >= 25 -> 0.8; r >= 10 -> 0.45; r > 0 -> 0.15; else -> -0.45 }).coerceIn(-1.5, 2.0)
                success = r >= 10.0 && mins <= 30.0
                cls = if (success) "ENGINEERED_LEG_CAPTURED" else if (r > 0) "LATE_MANIP_CAPTURE" else "DISTRIBUTION_CAUGHT"
                why = "manip_phase_capture"
            }
            "DIP_HUNTER" -> {
                u = when { r >= 25 -> 1.5; r >= 12 -> 1.0; r >= 6 -> 0.55; r > 0 -> 0.2; r <= -12 -> -1.0; else -> -0.4 }
                success = r >= 8.0
                cls = if (success) "RECOVERY_CONFIRMED" else if (r > 0) "WEAK_BOUNCE" else "STRUCTURAL_BREAK"
                why = "mean_reversion_recovery"
            }
            "CYCLIC" -> {
                u = when { r >= 40 -> 1.6; r >= 18 -> 1.0; r >= 8 -> 0.55; r > 0 -> 0.2; r <= -12 -> -1.0; else -> -0.35 }
                success = r >= 10.0
                cls = if (success) "CYCLE_LEG_CAPTURED" else if (r > 0) "PARTIAL_CYCLE" else "CYCLE_INVALID"
                why = "repeatable_cycle_leg"
            }
            "QUALITY" -> {
                u = when { r >= 50 -> 1.7; r >= 20 -> 1.1; r >= 8 -> 0.6; r > 0 -> 0.25; r <= -12 -> -1.0; else -> -0.35 }
                success = r >= 8.0
                cls = if (success) "QUALITY_EXPECTANCY_WIN" else if (r > 0) "SCRATCH_WIN" else "QUALITY_FAIL"
                why = "survivable_expectancy"
            }
            "BLUECHIP" -> {
                u = when { r >= 30 -> 1.5; r >= 12 -> 1.0; r >= 5 -> 0.55; r > 0 -> 0.25; r <= -8 -> -1.0; else -> -0.3 }
                success = r >= 5.0
                cls = if (success) "BLUECHIP_SWING_WIN" else if (r > 0) "SMALL_SWING" else "SWING_FAIL"
                why = "mature_risk_adjusted_return"
            }
            "TREASURY" -> {
                // Capital preservation: modest green returns are good; deep losses are disproportionately bad.
                u = when { r >= 12 -> 1.4; r >= 5 -> 1.0; r >= 2 -> 0.6; r > 0 -> 0.25; r <= -8 -> -1.5; r <= -4 -> -1.0; else -> -0.35 }
                success = r >= 2.0
                cls = if (success) "CAPITAL_COMPOUND_WIN" else if (r > 0) "TOO_SMALL" else "CAPITAL_DAMAGE"
                why = "capital_preservation_return"
            }
            "CASHGEN" -> {
                val efficiency = (ph / 12.0).coerceIn(-1.5, 1.5)
                u = (efficiency + when { r >= 8 -> 0.7; r >= 3 -> 0.45; r > 0 -> 0.2; else -> -0.35 }).coerceIn(-1.5, 2.0)
                success = r >= 2.0 && mins <= 180.0
                cls = if (success) "REALIZED_CASHFLOW_WIN" else if (r > 0) "CAPITAL_TIME_INEFFICIENT" else "CASHFLOW_LOSS"
                why = "ev_per_capital_time"
            }
            else -> {
                // CORE: general expert trader; ordinary economic expectancy is appropriate.
                u = (r / 20.0).coerceIn(-1.5, 2.0)
                success = r > 0.0
                cls = if (success) "GENERALIST_WIN" else "GENERALIST_LOSS"
                why = "general_expectancy"
            }
        }
        return Utility(lane, u, success, cls, "$why ret=${String.format("%.1f",r)}% hold=${String.format("%.1f",mins)}m exit=${exitReason.take(40)}")
    }

    fun exitQuality(
        laneRaw: String,
        realizedReturnPct: Double,
        mfePct: Double,
        holdingTimeMs: Long,
        exitReason: String,
    ): ExitQuality {
        val lane = canon(laneRaw)
        val realized = realizedReturnPct.takeIf { it.isFinite() } ?: 0.0
        val peak = max(mfePct.takeIf { it.isFinite() } ?: realized, realized)
        val capture = if (peak > 0.0) (realized.coerceAtLeast(0.0) / peak).coerceIn(0.0, 1.0) else 0.0
        val giveback = (peak - realized).coerceAtLeast(0.0)
        val r = exitReason.uppercase()
        val hardSafety = listOf("RUG", "LIQUIDITY", "DEV_SELL", "HARD_SAFETY", "CATASTROPH").any { r.contains(it) }
        val stop = r.contains("STOP_LOSS") || r.contains("STRICT_SL") || r.contains("STOPLOSS")
        val mandate = evaluate(lane, realized, holdingTimeMs, exitReason)
        val requiredCapture = when (lane) {
            "MOONSHOT" -> 0.55
            "PROJECT_SNIPER", "SHITCOIN" -> 0.50
            "EXPRESS", "MANIPULATED", "CASHGEN" -> 0.70
            "TREASURY" -> 0.65
            "DIP_HUNTER", "CYCLIC", "QUALITY", "BLUECHIP" -> 0.60
            else -> 0.55
        }
        val optimal = when {
            hardSafety -> true
            stop -> false
            peak <= 0.0 -> realized >= 0.0
            else -> mandate.mandateSuccess && capture >= requiredCapture
        }
        return ExitQuality(lane, optimal, capture, giveback,
            "mandate=${mandate.magnitudeClass} capture=${String.format("%.2f",capture)} giveback=${String.format("%.1f",giveback)}%")
    }
}
