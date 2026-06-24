package com.lifecyclebot.engine

/**
 * V5.0.4128 — PATTERN GOLDEN GOOSE
 * ════════════════════════════════════════════════════════════════════════════
 * Operator mandate: "find the data golden goose for each lane and traders
 * switching the bot into a money printer."
 *
 * The bot already records per-name/per-symbol win-rate patterns in
 * TokenWinMemory. The data is sharp:
 *   theme_space  82.7% WR  n=75
 *   theme_ai     50.4% WR  n=280
 *   theme_musk    0.0% WR  n=11
 *   theme_inu     0.0% WR  n=13
 *   theme_bot     0.0% WR  n=5
 *
 * Until now this data was only routed through OrthogonalSignals where it
 * could shift the entry score by ±5. That's nowhere near the leverage the
 * sharp asymmetry deserves. The goose surfaces it as a first-class lane
 * score bias and an explicit veto path.
 *
 * Doctrine #86: bounded, fail-open. Toxic dominates gold (asymmetric tilt).
 * Veto only fires on CATASTROPHIC verdict (n≥15, WR ≤ 5%) — every other
 * verdict is a score nudge that can be over-ridden by lane strength.
 */
object PatternGoldenGoose {

    /** Returns the lane score-bias for this token (additive). Bounded ±35. */
    fun scoreBias(name: String, symbol: String): Int = try {
        TokenWinMemory.patternEdgeForToken(name, symbol).scoreBias
    } catch (_: Throwable) { 0 }

    /** Full edge breakdown for logging / rejection reasons. */
    fun edge(name: String, symbol: String): TokenWinMemory.PatternEdge = try {
        TokenWinMemory.patternEdgeForToken(name, symbol)
    } catch (_: Throwable) {
        TokenWinMemory.PatternEdge(
            TokenWinMemory.Verdict.NEUTRAL, 0,
            null, 0.0, 0, null, 0.0, 0,
        )
    }

    /**
     * True when the goose says HARD REJECT — token's worst-matched pattern is
     * catastrophic (n≥15, WR ≤ 5%). Pure decision; caller chooses the
     * rejection reason / telemetry tag.
     */
    fun isCatastrophic(name: String, symbol: String): Boolean =
        edge(name, symbol).verdict == TokenWinMemory.Verdict.CATASTROPHIC
}
