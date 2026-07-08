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
    // V5.0.4114 — bumped 50-65% to convert big winners into real dollars.
    // Operator screenshot: CHUNGUS hit +24,570% but only netted +$0.33
    // because the entry was 0.0000 SOL of dust. On a $80 wallet (~0.57 SOL)
    // the previous BASE=0.025 floor was 4.4% of wallet — fine — but post-
    // SmartSizer multipliers (cold-streak 0.35×, prob-engine 0.48×, etc.)
    // were collapsing entries to 0.003–0.006 SOL, which is what the user
    // observed in the journal. We now (a) raise the absolute floors and
    // (b) clamp every live buy at the broadcast site so multipliers cannot
    // drop below the floor.
    // V5.0.6018 — compound floor upgrade. Operator report: most live trades were
    // still dollar-sized under a sub-1 SOL wallet, which makes 2x-5x daily
    // compounding mathematically impossible even with great winners. Raise the
    // live absolute floors and wallet-percent caps; hard safety, liquidity caps,
    // wallet reserve and sell finality remain authoritative.
    // V5.0.6142 — economic live compounding floor.
    // Runtime screenshot after two live hours showed 0.025–0.040 SOL buys on a
    // ~0.3 SOL wallet: enough to generate activity, not enough to hit the
    // operator's 2x–5x daily wallet-growth mandate. Live entries now target
    // meaningful wallet-percent exposure while true wallet/route/safety caps
    // remain authoritative.
    const val MIN_ENTRY_SOL: Double = 0.035
    const val DEFAULT_ENTRY_SOL: Double = 0.100
    const val STRONG_ENTRY_SOL: Double = 0.150
    const val ALPHA_ENTRY_SOL: Double = 0.220

    // ── Wallet-percent sizing tiers ──
    const val BASE_WALLET_PCT: Double = 0.120
    const val STRONG_WALLET_PCT: Double = 0.180
    const val ALPHA_WALLET_PCT: Double = 0.260
    const val MAX_INITIAL_WALLET_PCT: Double = 0.320
    const val MAX_TOTAL_TOKEN_WALLET_PCT: Double = 0.440
    const val MAX_DEPLOYED_WALLET_PCT: Double = 0.860
    const val GAS_RESERVE_SOL: Double = 0.025      // was 0.030 (small-wallet compounding without draining to zero)

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
            r.contains("TOXIC_PATTERN_SOFT_6207")          -> 0.35
            r.contains("BLEEDER_LANE_RECOVERY_PROBE_6210") -> 0.30
            else                                            -> 1.0
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-mint pending gate-soft-shape multiplier (Wave 2). FDG writes
    // here when it converts a hard-block to a soft-shape; SmartSizer
    // reads & clears at the end of calculate(). 5s TTL fail-safe so a
    // stale mult never leaks across cycles.
    // ─────────────────────────────────────────────────────────────────

    private data class PendingShape(val mult: Double, val whenMs: Long)

    private val pendingShapes = java.util.concurrent.ConcurrentHashMap<String, PendingShape>()
    private const val PENDING_TTL_MS = 5_000L

    fun markGateSoftShape(mint: String, blockReason: String) {
        if (mint.isBlank()) return
        val mult = gateSizeMult(blockReason)
        if (mult >= 0.999) return
        pendingShapes[mint] = PendingShape(mult, System.currentTimeMillis())
        threadLocalMult.set(mult)
        try {
            ErrorLogger.info(
                "LiveSizingProfile",
                "🪄 GATE→SIZE: ${mint.take(6)}… reason=$blockReason mult=${"%.2f".format(mult)}"
            )
            PipelineHealthCollector.labelInc("GATE_TO_SIZE_SHAPE")
        } catch (_: Throwable) { }
    }

    fun consumeGateSoftShape(mint: String): Double {
        if (mint.isBlank()) return consumeThreadLocal()
        val p = pendingShapes.remove(mint) ?: return consumeThreadLocal()
        val age = System.currentTimeMillis() - p.whenMs
        threadLocalMult.remove()
        return if (age < PENDING_TTL_MS) p.mult else 1.0
    }

    private val threadLocalMult = ThreadLocal<Double?>()
    private fun consumeThreadLocal(): Double {
        val v = threadLocalMult.get() ?: return 1.0
        threadLocalMult.remove()
        return v
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-lane sizing overrides (Wave 2 / 4099)
    //   MOONSHOT          : initial 5.0% / strong 8.0% / alpha 12.0% / max-initial 18%
    //   STANDARD          : initial 4.5% / strong 7.0% / alpha 11.0% / max-initial 16%
    //   WALLET_RECOVERED  : minEntry 0.080, alpha 0.280, proof-state-aware
    //   BLUECHIP/QUALITY  : compound-sized, no dollar trades
    // Exposed via laneCompoundFloor(); applyCompoundFloor() falls back to
    // the global tiers when the lane is unknown so siblings stay safe.
    // ─────────────────────────────────────────────────────────────────

    fun laneCompoundFloor(
        lane: String,
        baseSol: Double,
        walletSol: Double,
        conviction: Conviction,
        isPaperMode: Boolean,
    ): Double {
        if (!enabled || isPaperMode || baseSol <= 0.0) return baseSol
        if (walletSol <= GAS_RESERVE_SOL) return baseSol
        val L = lane.uppercase()

        // V5.0.4114 — lane tiers bumped in lockstep with global tiers so
        // multipliers cannot collapse entries to dust on the user's small
        // wallets. (initialPct, strongPct, alphaPct, minSol, defaultSol,
        // strongSol, alphaSol, maxInitialWalletPct)
        val (lP, dS) = when (L) {
            "MOONSHOT", "SHITCOIN" -> Triple(
                listOf(0.120, 0.180, 0.260), listOf(0.060, 0.100, 0.150, 0.220), 0.320
            ) to "moonshot"
            "STANDARD" -> Triple(
                listOf(0.110, 0.160, 0.240), listOf(0.060, 0.095, 0.140, 0.200), 0.300
            ) to "standard"
            "WALLET_RECOVERED" -> Triple(
                listOf(0.140, 0.220, 0.300), listOf(0.070, 0.120, 0.180, 0.260), 0.340
            ) to "wallet_recovered"
            "BLUECHIP", "DIP_HUNTER", "QUALITY" -> Triple(
                listOf(0.130, 0.200, 0.280), listOf(0.060, 0.110, 0.160, 0.240), 0.320
            ) to "established"
            else -> Triple(
                listOf(BASE_WALLET_PCT, STRONG_WALLET_PCT, ALPHA_WALLET_PCT),
                listOf(MIN_ENTRY_SOL, DEFAULT_ENTRY_SOL, STRONG_ENTRY_SOL, ALPHA_ENTRY_SOL),
                MAX_INITIAL_WALLET_PCT
            ) to "default"
        }
        val pcts = lP.first
        val sols = lP.second
        val maxInitialPct = lP.third

        val (absoluteFloor, walletPct) = when (conviction) {
            Conviction.ALPHA  -> sols[3] to pcts[2]
            Conviction.STRONG -> sols[2] to pcts[1]
            Conviction.BASE   -> sols[1] to pcts[0]
        }
        val minSol = sols[0]
        val pctFloor = walletSol * walletPct
        val targetFloor = max(minSol, max(absoluteFloor, pctFloor))
        val rampedFloor = targetFloor * dailyRampMultiplier()
        val hardCap = walletSol * maxInitialPct
        val lifted = max(baseSol, rampedFloor)
        val capped = min(lifted, hardCap)
        val maxSpendable = (walletSol - GAS_RESERVE_SOL).coerceAtLeast(0.0)
        return min(capped, maxSpendable)
    }

    /**
     * V5.0.4114 — LAST-MILE BROADCAST FLOOR.
     * Called from doBuy() at the very last step before broadcast. Cleans up
     * the edge cases where the size came from a non-SmartSizer path
     * (network-signal auto-buy, manual buy, wallet_recovered add-in,
     * social-velocity bridge, etc.) or where post-SmartSizer multipliers
     * (Executor's score-band cuts, anti-choke scalers, expectancy-gate)
     * collapsed the result below the floor.
     *
     * Behaviour:
     *  - Paper / disabled / non-finite → pass-through.
     *  - Wallet ≤ GAS_RESERVE_SOL → pass-through (defensive: don't lift on
     *    a near-empty wallet, the original size is already coerced upstream).
     *  - Hard-block (baseSol ≤ 0) → pass-through (must remain 0).
     *  - Otherwise: lift to max(MIN_ENTRY_SOL, walletSol × BASE_WALLET_PCT)
     *    capped by walletSol × MAX_INITIAL_WALLET_PCT and (wallet - reserve).
     *
     * This guarantees that on a wallet ≥ 0.10 SOL the bot CAN NEVER enter
     * with less than the live compound floor when spendable balance supports it.
     * Operator mandate:
     * "if it catching huge wins it needs to make big wins".
     */
    fun lastMileEntryFloor(baseSol: Double, walletSol: Double, isPaperMode: Boolean, riskMult: Double = 1.0): Double {
        if (!enabled || isPaperMode) return baseSol
        if (!baseSol.isFinite() || baseSol <= 0.0) return baseSol
        if (!walletSol.isFinite() || walletSol <= GAS_RESERVE_SOL) return baseSol
        // V5.0.6205 — DAMPER-RESPECTING FLOOR (root-cause audit: THE live/paper killer).
        // `max(baseSol, 12% wallet)` was ERASING every risk damper the AI stack applied
        // (bleeder-lane 0.35x, DUMP 0.35x, CorrelationGuard 0.20x, discipline probes,
        // BehaviorAI tilt). Live re-inflated proven-toxic probes to 12-32% of wallet
        // while paper kept the damped size — paper +19.9 SOL, live -9.0 SOL. The floor
        // now SCALES with the composed risk multiplier: undamped winners keep the full
        // compounding floor; damped probes stay probes (dust-guarded at 0.015 SOL so
        // fees don't eat them, but NEVER re-inflated), and damped entries are hard-
        // capped at 8% of wallet.
        val rm = if (riskMult.isFinite()) riskMult.coerceIn(0.0, 1.0) else 1.0
        val damped = rm < 0.90
        val dustFloor = if (damped) 0.015 else MIN_ENTRY_SOL
        val pctFloor = walletSol * BASE_WALLET_PCT * rm
        val targetFloor = max(dustFloor, pctFloor)
        val hardCap = walletSol * (if (damped) 0.08 else MAX_INITIAL_WALLET_PCT)
        val maxSpendable = (walletSol - GAS_RESERVE_SOL).coerceAtLeast(0.0)
        val lifted = max(baseSol, targetFloor)
        val result = min(min(lifted, hardCap), maxSpendable)
        if (result > baseSol * 1.01) {
            try {
                ErrorLogger.info(
                    "LiveSizingProfile",
                    "🛡️ LAST_MILE_FLOOR: base=${"%.4f".format(baseSol)} → ${"%.4f".format(result)} (wallet=${"%.3f".format(walletSol)})"
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LAST_MILE_ENTRY_FLOOR_LIFTED")
            } catch (_: Throwable) {}
        }
        return result
    }
}
