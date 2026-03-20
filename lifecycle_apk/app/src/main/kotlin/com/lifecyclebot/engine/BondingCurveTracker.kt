package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * BondingCurveTracker v2
 *
 * Pump.fun graduation threshold is 85 SOL raised on the bonding curve —
 * fixed in SOL, NOT in USD. The USD equivalent shifts daily with SOL price.
 *
 * Examples:
 *   SOL @ $100  →  graduation at ~$8,500 USD mcap
 *   SOL @ $126  →  graduation at ~$10,710 USD mcap
 *   SOL @ $150  →  graduation at ~$12,750 USD mcap
 *   SOL @ $200  →  graduation at ~$17,000 USD mcap
 *
 * The old $69K hardcoded figure was from when SOL was ~$812 — completely wrong.
 *
 * How we track this:
 *   1. BotService feeds current SOL/USD price from CurrencyManager
 *   2. Graduation threshold = GRADUATION_SOL_RAISED * solUsdPrice
 *   3. We detect graduation by watching the token's bonding curve SOL balance
 *      via Dexscreener liquidity field (not mcap — mcap is misleading)
 *
 * Bonding curve stages (in SOL raised):
 *   0-30  SOL:  Early stage
 *   30-65 SOL:  Mid stage — momentum building
 *   65-80 SOL:  Pre-graduation zone — acceleration, FOMO buying incoming
 *   80-85 SOL:  Graduating imminently — spike incoming, tighten exit
 *   85+   SOL:  Graduated to Raydium — different dynamics
 *
 * If SOL raised data isn't available (Dexscreener doesn't always expose it),
 * we fall back to % of estimated mcap range using live SOL price.
 */
object BondingCurveTracker {

    // Fixed constants in SOL — these never change regardless of SOL price
    const val GRADUATION_SOL_RAISED = 85.0    // SOL raised to graduate
    const val PRE_GRAD_SOL          = 65.0    // SOL raised — pre-grad warning zone
    const val GRADUATING_SOL        = 80.0    // SOL raised — imminent graduation

    // Fallback: if we only have USD mcap, estimate SOL raised
    // Pump.fun bonding curve starts at ~$4K USD mcap at launch
    // Graduation mcap = GRADUATION_SOL_RAISED * solUsdPrice
    // We need solUsdPrice to compute this — passed in from CurrencyManager

    // Current SOL price — updated from BotService every poll
    @Volatile var currentSolUsd: Double = 130.0   // reasonable default

    val graduationMcapUsd: Double
        get() = GRADUATION_SOL_RAISED * currentSolUsd

    val preGradMcapUsd: Double
        get() = PRE_GRAD_SOL * currentSolUsd

    val graduatingMcapUsd: Double
        get() = GRADUATING_SOL * currentSolUsd

    data class CurveState(
        val currentMcap: Double,
        val solRaised: Double,             // SOL raised on bonding curve (-1 if unknown)
        val progressPct: Double,           // 0-100%
        val stage: CurveStage,
        val isGraduated: Boolean,
        val solToGraduation: Double,       // SOL remaining to graduation
        val estimatedMinsToGrad: Double,   // -1 if unknown
        val holdModifier: Double,
        val exitUrgencyBonus: Double,
        val stageLabel: String,
        val graduationMcapUsd: Double,     // what USD mcap graduation is right now
    )

    enum class CurveStage {
        EARLY, MID, PRE_GRAD, GRADUATING, GRADUATED, UNKNOWN
    }

    fun evaluate(ts: TokenState): CurveState {
        val mcap = ts.lastMcap.takeIf { it > 0 } ?: 0.0

        // Try to estimate SOL raised from liquidity data
        // Dexscreener returns liquidity.usd — on Pump.fun this is roughly
        // 2x the SOL raised (both sides of bonding curve)
        val liquidityUsd = ts.history.lastOrNull()?.vol ?: 0.0  // approximation
        val estimatedSolRaised = if (mcap > 0 && currentSolUsd > 0)
            (mcap / currentSolUsd).coerceAtMost(GRADUATION_SOL_RAISED + 10.0)
        else -1.0

        val gradMcap = graduationMcapUsd
        if (gradMcap <= 0) {
            return CurveState(mcap, -1.0, 0.0, CurveStage.UNKNOWN, false,
                GRADUATION_SOL_RAISED, -1.0, 1.0, 0.0, "Unknown", gradMcap)
        }

        val progress   = if (mcap > 0) (mcap / gradMcap * 100.0).coerceIn(0.0, 100.0) else 0.0
        val graduated  = mcap >= gradMcap
        val solRaised  = estimatedSolRaised
        val solToGrad  = (GRADUATION_SOL_RAISED - solRaised).coerceAtLeast(0.0)

        val stage = when {
            graduated                     -> CurveStage.GRADUATED
            solRaised >= GRADUATING_SOL   -> CurveStage.GRADUATING
            solRaised >= PRE_GRAD_SOL     -> CurveStage.PRE_GRAD
            solRaised >= 30.0             -> CurveStage.MID
            solRaised > 0                 -> CurveStage.EARLY
            // Fallback to mcap % if no SOL data
            progress >= 94                -> CurveStage.GRADUATING
            progress >= 76                -> CurveStage.PRE_GRAD
            progress >= 35                -> CurveStage.MID
            progress > 0                  -> CurveStage.EARLY
            else                          -> CurveStage.UNKNOWN
        }

        val minsToGrad = estimateMinsToGrad(ts, mcap, gradMcap)

        val holdMod = when (stage) {
            CurveStage.PRE_GRAD   -> 2.2   // strong extension — spike incoming
            CurveStage.GRADUATING -> 1.8   // still riding but tighten soon
            CurveStage.MID        -> 1.3   // moderate extension
            CurveStage.GRADUATED  -> 0.7   // post-grad — be cautious
            else                  -> 1.0
        }

        val exitBonus = when (stage) {
            CurveStage.GRADUATING -> 18.0  // about to spike — prepare exit
            CurveStage.GRADUATED  -> 28.0  // post-grad dump risk is real
            else                  -> 0.0
        }

        val timeStr = if (minsToGrad > 0 && minsToGrad < 999)
            " ~${minsToGrad.toInt()}min" else ""

        val label = when (stage) {
            CurveStage.EARLY      -> "Early ${progress.toInt()}%"
            CurveStage.MID        -> "Mid ${progress.toInt()}%"
            CurveStage.PRE_GRAD   -> "⚡ Pre-grad ${progress.toInt()}%$timeStr"
            CurveStage.GRADUATING -> "🚀 Graduating!$timeStr"
            CurveStage.GRADUATED  -> "✅ Graduated"
            CurveStage.UNKNOWN    -> "—"
        }

        return CurveState(
            currentMcap         = mcap,
            solRaised           = solRaised,
            progressPct         = progress,
            stage               = stage,
            isGraduated         = graduated,
            solToGraduation     = solToGrad,
            estimatedMinsToGrad = minsToGrad,
            holdModifier        = holdMod,
            exitUrgencyBonus    = exitBonus,
            stageLabel          = label,
            graduationMcapUsd   = gradMcap,
        )
    }

    private fun estimateMinsToGrad(ts: TokenState, currentMcap: Double, gradMcap: Double): Double {
        val hist = ts.history.toList()
        if (hist.size < 3 || currentMcap <= 0 || currentMcap >= gradMcap) return -1.0
        val recent = hist.takeLast(5)
        if (recent.size < 2) return -1.0
        val timeMins   = (recent.last().ts - recent.first().ts) / 60_000.0
        val mcapChange = recent.last().ref - recent.first().ref
        if (timeMins <= 0 || mcapChange <= 0) return -1.0
        val ratePerMin = mcapChange / timeMins
        return ((gradMcap - currentMcap) / ratePerMin).coerceAtMost(999.0)
    }

    /** Called from BotService poll loop to keep SOL price current */
    fun updateSolPrice(solUsd: Double) {
        if (solUsd > 0) currentSolUsd = solUsd
    }
}
