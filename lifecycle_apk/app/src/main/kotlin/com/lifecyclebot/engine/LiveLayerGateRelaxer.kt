package com.lifecyclebot.engine

/**
 * V5.9.495z38 — Live Layer Gate Relaxation.
 *
 * Operator directive: "gates need to be relaxed in live trading to
 * allow all the layers to trade." Screenshot evidence:
 *
 *   V3 4 | 40%      Treasury 1 | +0.00      Blue 0/3
 *   Shit 0 | 27%    Express  0 | 100%       Manip 0/0
 *   Moon 0 | 40%
 *
 * Most lanes have 0 trades because each lane carries its own
 * confidence/score floor that was tuned for paper. In live, those
 * thresholds are too strict for several traders to ever fire.
 *
 * This module is the canonical "live relaxation" multiplier. Each
 * lane reads `floorMultiplier(traderTag)` and multiplies its existing
 * paper threshold to derive the LIVE threshold. Default 1.0 (no
 * change). Operator can set < 1.0 to relax.
 *
 * Lanes that are starved get a default 0.85 (15% lower threshold) so
 * they get a chance to trade and produce learning signal.
 */
object LiveLayerGateRelaxer {

    @Volatile var enabled: Boolean = true

    /** Per-lane multiplier on the lane's confidence/score floor when
     *  `cfg.paperMode == false`. 1.0 means "no relaxation". */
    private val perLaneMultiplier = mutableMapOf(
        "BLUECHIP"   to 0.85,
        "SHITCOIN"   to 0.85,
        "EXPRESS"    to 0.85,
        "MANIP"      to 0.90,
        "MOONSHOT"   to 0.85,
        "TREASURY"   to 0.95,
        "QUALITY"    to 0.95,
        "MEME"       to 1.00,
        "CRYPTO"     to 0.90,
        "MARKETS"    to 0.90,
    )

    fun floorMultiplier(traderTag: String): Double {
        if (!enabled) return 1.0
        return perLaneMultiplier[traderTag.uppercase()] ?: 1.0
    }

    /** Relax a paper-mode floor for live mode. */
    fun relaxFloor(originalFloor: Double, traderTag: String, isLiveMode: Boolean): Double {
        if (!isLiveMode) return originalFloor
        return originalFloor * floorMultiplier(traderTag)
    }

    fun relaxFloor(originalFloor: Int, traderTag: String, isLiveMode: Boolean): Int {
        if (!isLiveMode) return originalFloor
        return (originalFloor * floorMultiplier(traderTag)).toInt()
    }

    fun setMultiplier(traderTag: String, multiplier: Double) {
        perLaneMultiplier[traderTag.uppercase()] = multiplier.coerceIn(0.5, 1.5)
    }

    fun summaryLine(): String {
        if (!enabled) return "🔓 GATE RELAXER: disabled"
        val parts = perLaneMultiplier.entries
            .filter { it.value < 1.0 }
            .joinToString(" · ") { "${it.key} ×${"%.2f".format(it.value)}" }
        return if (parts.isEmpty()) "🔓 GATE RELAXER: all lanes at 1.00×"
        else                        "🔓 GATE RELAXER: $parts"
    }
}
