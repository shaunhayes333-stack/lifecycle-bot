package com.lifecyclebot.engine

/**
 * V5.9.495z38 — Live Layer Gate Relaxation.
 *
 * Operator directive: "gates need to be relaxed in live trading to
 * allow all the layers to trade." Most lanes had 0 live trades because
 * each lane carries its own confidence/score floor tuned for paper; in
 * live those thresholds were too strict for several traders to fire.
 *
 * This module is the canonical "live relaxation" multiplier. Each lane
 * reads `floorMultiplier(traderTag)` and multiplies its existing paper
 * threshold to derive the LIVE threshold. Default 1.0 (no change).
 *
 * ─────────────────────────────────────────────────────────────────────
 * V5.9.1520 — WR-AWARE AUTO-FADE (root WR-drop fix).
 *
 * OPERATOR REPORT: "live trading really poorly now, previously over 60%".
 *
 * ROOT CAUSE: the static 0.85 (= 15% lower) floor applied to LIVE ONLY and
 * NEVER faded. A bot that LEARNED a clean 60%+ WR at full floors was then
 * permanently forced to take 15%-weaker setups in live — exactly the
 * marginal entries that lose. Because the relaxation was live-only, live WR
 * structurally trailed paper, and as a lane matured the relaxer kept
 * poisoning it with sub-floor trades.
 *
 * The relaxer's ORIGINAL purpose was cold-start only: give a STARVED lane
 * (few live trades) a chance to fire and produce learning signal. Once a
 * lane has enough live samples it must trade at its EARNED floor.
 *
 * Fix: the per-lane multiplier now FADES from its starved value back to
 * 1.00 as the lane accumulates live trades:
 *   liveN <  WARM_MIN      → full configured relaxation (cold start)
 *   WARM_MIN..WARM_FULL    → linearly interpolate relax → 1.00
 *   liveN >= WARM_FULL     → 1.00 (no relaxation; trade the earned floor)
 *
 * Keeps throughput on genuinely dead lanes without ever dragging a matured
 * lane's entry quality. Reads LIVE outcomes from CanonicalOutcomeBus
 * (already persisted), so the fade survives restarts — a matured lane
 * stays matured across sessions instead of re-relaxing.
 */
object LiveLayerGateRelaxer {

    @Volatile var enabled: Boolean = true

    /** Lane is "warming" below WARM_MIN live trades (full configured
     *  relaxation) and "matured" at/above WARM_FULL (no relaxation).
     *  Between the two the relaxation fades linearly. */
    private const val WARM_MIN = 15
    private const val WARM_FULL = 40

    /** Per-lane STARVED (cold-start) multiplier when `cfg.paperMode == false`.
     *  1.0 = no relaxation. Fades to 1.00 as the lane matures. */
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

    // cheap cached per-lane LIVE trade counts (refreshed at most every 30s)
    @Volatile private var laneLiveCountCache: Map<String, Int> = emptyMap()
    @Volatile private var laneCacheStampMs: Long = 0L
    private const val LANE_CACHE_TTL_MS = 30_000L

    private fun liveCountForLane(traderTag: String): Int {
        val now = System.currentTimeMillis()
        if (now - laneCacheStampMs > LANE_CACHE_TTL_MS) {
            laneLiveCountCache = try {
                CanonicalOutcomeBus.recentSnapshot()
                    .asSequence()
                    .filter { it.environment == TradeEnvironment.LIVE }
                    .filter { it.realizedPnlPct != null }
                    .groupingBy { it.mode.name.uppercase() }
                    .eachCount()
            } catch (_: Throwable) { laneLiveCountCache }
            laneCacheStampMs = now
        }
        return laneLiveCountCache[traderTag.uppercase()] ?: 0
    }

    /** EFFECTIVE multiplier after the maturity fade. */
    private fun effectiveMultiplier(traderTag: String): Double {
        if (!enabled) return 1.0
        val base = perLaneMultiplier[traderTag.uppercase()] ?: 1.0
        if (base >= 1.0) return 1.0  // never relaxed → nothing to fade
        val liveN = liveCountForLane(traderTag)
        return when {
            liveN < WARM_MIN   -> base   // cold start → full relax
            liveN >= WARM_FULL -> 1.0    // matured → earned floor
            else -> {
                val t = (liveN - WARM_MIN).toDouble() / (WARM_FULL - WARM_MIN).toDouble()
                base + (1.0 - base) * t
            }
        }
    }

    fun floorMultiplier(traderTag: String): Double = effectiveMultiplier(traderTag)

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
        val parts = perLaneMultiplier.keys
            .map { it to effectiveMultiplier(it) }
            .filter { it.second < 1.0 }
            .joinToString(" · ") { (tag, m) ->
                "$tag ×${"%.2f".format(m)}(n=${liveCountForLane(tag)})"
            }
        return if (parts.isEmpty()) "🔓 GATE RELAXER: all lanes matured → 1.00× (earned floors)"
        else                        "🔓 GATE RELAXER (WR-aware fade): $parts"
    }
}
