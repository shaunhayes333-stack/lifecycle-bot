package com.lifecyclebot.engine

import kotlin.math.max
import kotlin.math.min

/**
 * LiveSizingProfile — V5.0.4098 (AGGRESSIVE_COMPOUND wallet-percent live sizing)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 mandate: "Increase live entry sizing for wallet compounding while
 * avoiding new choke points, lane shutdowns, or strategy disabling. Current
 * live entries are too small for compounding: median BUY size is ~0.0098 SOL,
 * average ~0.0115 SOL. This is not enough for a 2x+ daily compounding profile."
 *
 * Doctrine:
 *   • SOFT-SHAPE ONLY — never zeroes a trade, never blocks a lane.
 *   • LIVE-ONLY      — paper sizing is unchanged (FluidLearning still drives
 *                      paper, since paper is for training).
 *   • Buy/sell mechanics untouched. We only re-shape the SOL amount that
 *     SmartSizer outputs before it goes to the broadcast layer.
 *   • True hard-safety failures (HARD=LP, confirmed rug, invalid mint/route,
 *     unsafe-sell-with-incomplete-token-map) still hard-block — they bypass
 *     this profile via the existing FinalDecisionGate hard-block paths.
 *   • Realized-PnL daily-ramp only. Never ramps from unrealized PnL or from
 *     RESTORED_LIVE_BASIS_UNKNOWN trade closures (those are managed but never
 *     feed compounding authority — see Wave 3 hardening).
 */
object LiveSizingProfile {

    const val VERSION = "V5.0.4098_LIVE_AGGRESSIVE_COMPOUND"

    // ── Master switches ──────────────────────────────────────────────
    @Volatile var enabled: Boolean = true
    @Volatile var mode: String = "AGGRESSIVE_COMPOUND"
    @Volatile var compoundFromRealizedWalletBalance: Boolean = true

    // ── Absolute SOL floors (never below in live mode unless hard safety) ──
    const val MIN_ENTRY_SOL: Double = 0.025
    const val DEFAULT_ENTRY_SOL: Double = 0.040
    const val STRONG_ENTRY_SOL: Double = 0.070
    const val ALPHA_ENTRY_SOL: Double = 0.120

    // ── Wallet-percent sizing tiers ──
    const val BASE_WALLET_PCT: Double = 0.020   // 2.0% on a normal entry
    const val STRONG_WALLET_PCT: Double = 0.040 // 4.0%
    const val ALPHA_WALLET_PCT: Double = 0.075  // 7.5%
    const val MAX_INITIAL_WALLET_PCT: Double = 0.100 // 10% on strongest entries
    const val MAX_TOTAL_TOKEN_WALLET_PCT: Double = 0.180 // after add-ins
    const val MAX_DEPLOYED_WALLET_PCT: Double = 0.600 // active open exposure target
    const val GAS_RESERVE_SOL: Double = 0.075

    enum class Conviction { BASE, STRONG, ALPHA }

    /** Map a 0..100 entry-score / setup quality to a conviction tier.
     *  STRONG ≈ score ≥ 65, ALPHA ≈ score ≥ 80 with A/A+ quality. */
    fun convictionFromScore(score: Double, quality: String?): Conviction {
        val q = (quality ?: "C").take(1).uppercase()
        return when {
            score >= 80.0 && (q == "A" || q == "+") -> Conviction.ALPHA
            score >= 65.0 -> Conviction.STRONG
            else -> Conviction.BASE
        }
    }

    /**
     * Surgical compound floor applied at the end of SmartSizer.calculate().
     *
     * @param baseSol      raw SmartSizer output (after all multipliers)
     * @param walletSol    real (live) wallet balance in SOL
     * @param conviction   BASE / STRONG / ALPHA tier
     * @param isPaperMode  paper trades skip compounding entirely
     * @return possibly-uplifted SOL size, never below MIN_ENTRY_SOL when
     *         live + walletSol can support it; capped by MAX_INITIAL_WALLET_PCT.
     */
    fun applyCompoundFloor(
        baseSol: Double,
        walletSol: Double,
        conviction: Conviction,
        isPaperMode: Boolean,
    ): Double {
        if (!enabled) return baseSol
        if (isPaperMode) return baseSol
        if (baseSol <= 0.0) return baseSol  // 0 means hard-block upstream — respect it
        if (walletSol <= GAS_RESERVE_SOL) return baseSol  // wallet too small to honor floors

        val (absoluteFloor, walletPct) = when (conviction) {
            Conviction.ALPHA  -> ALPHA_ENTRY_SOL  to ALPHA_WALLET_PCT
            Conviction.STRONG -> STRONG_ENTRY_SOL to STRONG_WALLET_PCT
            Conviction.BASE   -> DEFAULT_ENTRY_SOL to BASE_WALLET_PCT
        }
        val pctFloor = walletSol * walletPct
        val targetFloor = max(MIN_ENTRY_SOL, max(absoluteFloor, pctFloor))

        // ── Daily ramp (realized-PnL only) ─────────────────────────────
        val rampedFloor = targetFloor * dailyRampMultiplier()

        // Cap by MAX_INITIAL_WALLET_PCT regardless of conviction
        val hardCap = walletSol * MAX_INITIAL_WALLET_PCT

        // Lift baseSol up to rampedFloor; never reduce a higher base. Then cap.
        val lifted = max(baseSol, rampedFloor)
        val capped = min(lifted, hardCap)

        // Final wallet-aware safety: leave gas reserve room
        val maxSpendable = (walletSol - GAS_RESERWE_SOL_DEFENSIVE).coerceAtLeast(0.0)
        val safe = min(capped, maxSpendable)

        try {
            ErrorLogger.info(
                "LiveSizingProfile",
                "💎 COMPOUND_LIFT: base=${"%.4f".format(baseSol)} → ${"%.4f".format(safe)} " +
                    "(walletPct=${(walletPct * 100).toInt()}% conv=$conviction wallet=${"%.3f".format(walletSol)} " +
                    "ramp=${"%.2f".format(dailyRampMultiplier())})"
            )
        } catch (_: Throwable) { }
        return safe
    }

    // GAS_RESERVE_SOL is used twice with the same value; alias used here so a
    // future tunable can be threaded without breaking const semantics elsewhere.
    private val GAS_RESERWE_SOL_DEFENSIVE: Double get() = GAS_RESERVE_SOL

    // ── Daily realized-PnL ramp ───────────────────────────────────────
    // Only fed by Executor.recordRealizedClose(pnlSol) on confirmed live closes.
    // Resets at UTC day boundary. Ignores unrealized PnL and unknown-basis
    // restorations (per operator hardening doctrine).
    @Volatile private var dailyAnchorWalletSol: Double = 0.0
    @Volatile private var dailyAnchorDayUtc: Long = -1L
    @Volatile private var dailyRealizedPnlSol: Double = 0.0

    private fun currentDayUtc(): Long = System.currentTimeMillis() / 86_400_000L

    private fun rolloverIfDayChanged(walletNowSol: Double) {
        val today = currentDayUtc()
        if (dailyAnchorDayUtc != today) {
            dailyAnchorDayUtc = today
            dailyAnchorWalletSol = walletNowSol
            dailyRealizedPnlSol = 0.0
        }
    }

    /** Called by Executor on each confirmed live close (pnlSol = realized only). */
    fun recordRealizedClose(walletNowSol: Double, pnlSol: Double, restoredUnknownBasis: Boolean) {
        if (!enabled) return
        if (restoredUnknownBasis) return  // doctrine: never feeds compounding ramp
        rolloverIfDayChanged(walletNowSol)
        dailyRealizedPnlSol += pnlSol
    }

    /** Surfaced for telemetry / unified report. */
    fun dailyRealizedPnlPct(): Double {
        if (dailyAnchorWalletSol <= 0.0) return 0.0
        return (dailyRealizedPnlSol / dailyAnchorWalletSol) * 100.0
    }

    /** Multiplier on the floor based on confirmed daily realized gains. */
    fun dailyRampMultiplier(): Double {
        val pct = dailyRealizedPnlPct()
        return when {
            pct >= 50.0 -> 1.50  // mid-tier between strong/alpha (averaged spec values)
            pct >= 25.0 -> 1.30
            pct >= 10.0 -> 1.15
            else        -> 1.00
        }
    }

    fun summary(): String {
        return "$VERSION enabled=$enabled mode=$mode floors=[${MIN_ENTRY_SOL},${DEFAULT_ENTRY_SOL},${STRONG_ENTRY_SOL},${ALPHA_ENTRY_SOL}] " +
            "walletPct=[${(BASE_WALLET_PCT * 100).toInt()}%,${(STRONG_WALLET_PCT * 100).toInt()}%,${(ALPHA_WALLET_PCT * 100).toInt()}%/${(MAX_INITIAL_WALLET_PCT * 100).toInt()}%] " +
            "dailyRealizedPnl=${"%.1f".format(dailyRealizedPnlPct())}% ramp=${"%.2f".format(dailyRampMultiplier())}x"
    }

    // ─────────────────────────────────────────────────────────────────
    // Gate→Size soft-shape table (replaces hard blocks with size shaping
    // for selected gate reasons; consumed by FinalDecisionGate when it
    // would otherwise HARD_KILL on one of these reasons).
    // ─────────────────────────────────────────────────────────────────

    enum class GateAction { HARD_BLOCK, SIZE_ONLY }

    @Volatile var cGradeAction: GateAction = GateAction.SIZE_ONLY
    @Volatile var circuitBreakerAction: GateAction = GateAction.SIZE_ONLY
    @Volatile var liveWrFloorAction: GateAction = GateAction.SIZE_ONLY

    /** Multiplier to apply when a previously-hard-blocking reason is converted. */
    fun gateSizeMult(blockReason: String): Double {
        val r = blockReason.uppercase()
        return when {
            r.contains("C_GRADE_CONFIDENCE_FLOOR")        -> 0.65
            r.contains("CIRCUIT_BREAKER")                  -> 0.55
            r.contains("MOMENTUM_AVOID")                   -> 0.60
            r.contains("BRAIN_PATTERN_SUPPRESSED")         -> 0.65
            r.contains("PROVIDER_PROOF_HOLDER_CASCADE")    -> 0.70
            r.contains("PROVIDER_PROOF_LIQUIDITY_CASCADE") -> 0.60
            r.contains("FLUID_EXECUTE_FLOOR")              -> 0.75
            else                                            -> 1.0
        }
    }
}
