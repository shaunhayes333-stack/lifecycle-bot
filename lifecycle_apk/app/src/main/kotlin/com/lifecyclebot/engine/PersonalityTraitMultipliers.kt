package com.lifecyclebot.engine

/**
 * V5.9.1333 — PERSONALITY TUNE → trading-behavior multipliers.
 *
 * The PersonalityMemoryStore tracks 6 traits that drift as the bot trades:
 *   discipline / patience / aggression / paranoia / euphoria / loyalty
 *
 * Until now these only influenced LLM quote selection. This module wires them
 * to BOUNDED entry/exit multipliers so the personality actually steers
 * behavior (Train-First doctrine: bounded ±10%, fail-open, no veto).
 *
 *   paranoia   ↑ → +3 score floor bias, 0.95× sizing      (cuts early, smaller)
 *   euphoria   ↑ → 0.85× sizing                          (fade FOMO peaks)
 *   discipline ↑ → 1.05× sizing                          (reward rule-following)
 *   aggression ↑ → 1.05× sizing, +3% TP target           (size up after wins)
 *   patience   ↑ → +2 score floor bias                   (waits for A+)
 *   conviction (loyalty) ↑ → +5% trail slack on winners  (let winners run)
 *
 * All bounded, all fail-open. Call sites multiply size / add to score floor.
 */
object PersonalityTraitMultipliers {

    /**
     * V5.0.7192 — the same arithmetic as [sizingMultiplier], on trait values
     * supplied by the caller instead of read from the store.
     *
     * WHY THIS EXISTS. V5.0.7192 lets the LLM nudge its own traits, and a
     * learner must never be able to talk itself into more risk while it is
     * under water. Deciding whether a proposed nudge expands risk means
     * hand-classifying six traits by sign — and the signs here are genuinely
     * counterintuitive: `discipline ↑` INCREASES size (rewarding rule-
     * following) and `euphoria ↑` DECREASES it (deliberately fading FOMO
     * peaks). A hand-written table of "which direction is riskier" would be
     * wrong the first time either of those lines changed, and wrong silently.
     *
     * So the caller does not classify. It evaluates this function twice —
     * once on current traits, once on current+delta — and compares. The risk
     * direction is DERIVED from the real sizing arithmetic, so it cannot drift
     * away from it.
     */
    fun sizingMultiplierFor7192(
        paranoia: Double,
        euphoria: Double,
        discipline: Double,
        aggression: Double,
    ): Double {
        // Each trait contributes a small bounded factor; multiply them.
        val p = 1.0 - (paranoia.coerceIn(-1.0, 1.0) * 0.05)
        val e = 1.0 - (euphoria.coerceIn(-1.0, 1.0) * 0.07)
        val d = 1.0 + (discipline.coerceIn(-1.0, 1.0) * 0.05)
        val a = 1.0 + (aggression.coerceIn(-1.0, 1.0) * 0.05)
        return (p * e * d * a).coerceIn(0.80, 1.15)
    }

    /** V5.0.7192 — [scoreFloorBias] on supplied traits. See [sizingMultiplierFor7192]. */
    fun scoreFloorBiasFor7192(paranoia: Double, patience: Double): Int {
        val p = (paranoia.coerceIn(-1.0, 1.0) * 3.0).toInt()       // -3..+3
        val pat = (patience.coerceIn(-1.0, 1.0) * 2.0).toInt()      // -2..+2
        return (p + pat).coerceIn(-2, 6)
    }

    /** Sizing multiplier for an entry, clamped to [0.80, 1.15]. */
    fun sizingMultiplier(): Double {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            sizingMultiplierFor7192(t.paranoia, t.euphoria, t.discipline, t.aggression)
        } catch (_: Throwable) { 1.0 }
    }

    /** Additive score-floor bias (int, range [-2, +6]). */
    fun scoreFloorBias(): Int {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            scoreFloorBiasFor7192(t.paranoia, t.patience)
        } catch (_: Throwable) { 0 }
    }

    /** Trailing-stop slack multiplier on winners, clamped to [0.95, 1.10]. */
    fun trailSlackMultiplier(): Double {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            val loyalty = 1.0 + (t.loyalty.coerceIn(-1.0, 1.0) * 0.05)
            loyalty.coerceIn(0.95, 1.10)
        } catch (_: Throwable) { 1.0 }
    }

    /** Additive take-profit bias %, range [-2, +5]. */
    fun takeProfitBiasPct(): Double {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            (t.aggression.coerceIn(-1.0, 1.0) * 3.0).coerceIn(-2.0, 5.0)
        } catch (_: Throwable) { 0.0 }
    }

    /** Short human-readable summary for the snapshot dump. */
    fun summaryLine(): String {
        return try {
            val sz = sizingMultiplier()
            val sf = scoreFloorBias()
            val tp = takeProfitBiasPct()
            val ts = trailSlackMultiplier()
            "🎭 Personality tune: size×${"%.2f".format(sz)} · floor+${sf} · TP+${"%+.1f".format(tp)}% · trail×${"%.2f".format(ts)}"
        } catch (_: Throwable) { "🎭 Personality tune: unavailable" }
    }
}
