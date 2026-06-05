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

    /** Sizing multiplier for an entry, clamped to [0.80, 1.15]. */
    fun sizingMultiplier(): Double {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            // Each trait contributes a small bounded factor; multiply them.
            val paranoia   = 1.0 - (t.paranoia.coerceIn(-1.0, 1.0) * 0.05)
            val euphoria   = 1.0 - (t.euphoria.coerceIn(-1.0, 1.0) * 0.07)
            val discipline = 1.0 + (t.discipline.coerceIn(-1.0, 1.0) * 0.05)
            val aggression = 1.0 + (t.aggression.coerceIn(-1.0, 1.0) * 0.05)
            (paranoia * euphoria * discipline * aggression).coerceIn(0.80, 1.15)
        } catch (_: Throwable) { 1.0 }
    }

    /** Additive score-floor bias (int, range [-2, +6]). */
    fun scoreFloorBias(): Int {
        return try {
            val t = PersonalityMemoryStore.getTraits()
            val p = (t.paranoia.coerceIn(-1.0, 1.0) * 3.0).toInt()       // -3..+3
            val pat = (t.patience.coerceIn(-1.0, 1.0) * 2.0).toInt()      // -2..+2
            (p + pat).coerceIn(-2, 6)
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
