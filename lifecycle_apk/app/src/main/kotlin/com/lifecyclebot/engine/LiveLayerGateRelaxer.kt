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
    // V5.0.4300 — live must mimic paper's successful learning shape:
    // start soft across layers, collect terminal samples, then harden/fade
    // once the trading brains have enough live evidence. A 0% WR over 3-5
    // closes is bootstrap noise, not a valid global relaxer kill-switch.
    private const val GLOBAL_SOFT_START_LIVE_CLOSES = 40

    /** Per-lane STARVED (cold-start) multiplier when `cfg.paperMode == false`.
     *  1.0 = no relaxation. Fades to 1.00 as the lane matures. */
    private val perLaneMultiplier = mutableMapOf(
        "BLUECHIP"   to 1.00, // V5.0.6031: report 6028 shows BLUECHIP WR20 EV-14.46 PnL-0.6858; no live relax until recovery
        "SHITCOIN"   to 0.85,
        "EXPRESS"    to 0.85,
        "MANIP"      to 0.90,
        "MOONSHOT"   to 0.85, // V5.0.6031: positive-EV MOONSHOT may bypass global WR lock via lanePositiveCache
        "TREASURY"   to 0.95,
        "QUALITY"    to 1.00, // V5.0.6031: report 6028 shows QUALITY WR40 but EV-23.97; fix exits before relaxing entries
        "MEME"       to 1.00,
        "CRYPTO"     to 0.90,
        "MARKETS"    to 0.90,
    )

    // cheap cached per-lane LIVE trade counts. V5.0.4056: refresh is async/stale-only
    // because StrategyTelemetry reads TradeHistoryStore; report/UI calls must never block
    // the main thread and steal trading loop cycles.
    @Volatile private var laneLiveCountCache: Map<String, Int> = emptyMap()
    @Volatile private var laneToxicCache: Map<String, Boolean> = emptyMap()
    @Volatile private var lanePositiveCache: Map<String, Boolean> = emptyMap()
    @Volatile private var laneCacheStampMs: Long = 0L
    private const val LANE_CACHE_TTL_MS = 30_000L
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun refreshLaneCacheIfStale() {
        val now = System.currentTimeMillis()
        if (now - laneCacheStampMs <= LANE_CACHE_TTL_MS) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                val busCounts = CanonicalOutcomeBus.recentSnapshot()
                    .asSequence()
                    .filter { it.environment == TradeEnvironment.LIVE }
                    .filter { it.realizedPnlPct != null }
                    .groupingBy { canonicalLaneKey(it.mode.name) }
                    .eachCount()
                    .toMutableMap()
                val toxic = mutableMapOf<String, Boolean>()
                val positive = mutableMapOf<String, Boolean>()
                // V5.0.4051 mux fix retained, but offloaded: reports/journal proved
                // MOONSHOT/SHITCOIN had hundreds of LIVE closes while the relaxer printed
                // n=0/1. StrategyTelemetry is journal-backed, so only this background
                // refresher may touch it.
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500).forEach { m ->
                    val k = canonicalLaneKey(m.strategy)
                    busCounts[k] = maxOf(busCounts[k] ?: 0, m.trades)
                    toxic[k] = m.trades >= 5 && (m.winRatePct < 35.0 || m.totalSolPnl < 0.0 || m.meanPnlPct < -3.0)
                    positive[k] = m.trades >= 8 && m.winRatePct >= 45.0 && m.totalSolPnl >= 0.0 && m.meanPnlPct > 0.0
                }
                laneLiveCountCache = busCounts
                laneToxicCache = toxic
                lanePositiveCache = positive
                laneCacheStampMs = System.currentTimeMillis()
            } catch (_: Throwable) {
                laneCacheStampMs = System.currentTimeMillis()
            } finally {
                refreshInFlight.set(false)
            }
        }, "AATE-live-layer-relaxer-refresh").apply { isDaemon = true; start() }
    }

    private fun liveCountForLane(traderTag: String): Int {
        refreshLaneCacheIfStale()
        return laneLiveCountCache[canonicalLaneKey(traderTag)] ?: 0
    }

    private fun canonicalLaneKey(raw: String?): String {
        val r = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return "STANDARD"
        return when (r) {
            "BLUE_CHIP" -> "BLUECHIP"
            "MANIP" -> "MANIPULATED"
            "COPYTRADE", "COPY_TRADE", "WALLET_COPY", "WALLET_RECOVERED" -> "WALLET_RECOVERED"
            else -> r
        }
    }

    private fun dumpRegimeNoRelax(traderTag: String): Boolean {
        val lane = canonicalLaneKey(traderTag)
        if (lane !in setOf("MOONSHOT", "SHITCOIN", "EXPRESS", "MANIPULATED", "PRESALE_SNIPE")) return false
        val dump = try { RegimeDetector.currentRegime() == RegimeDetector.Regime.DUMP } catch (_: Throwable) { false }
        if (!dump) return false
        refreshLaneCacheIfStale()
        // If the cache has not warmed yet, be conservative in DUMP: no cold-start relax
        // for toxic-prone meme lanes. This is a floor multiplier reset, not a veto.
        return laneToxicCache[lane] ?: true
    }

    private fun lanePositiveOverride(traderTag: String): Boolean {
        refreshLaneCacheIfStale()
        val lane = canonicalLaneKey(traderTag)
        return lanePositiveCache[lane] == true && laneToxicCache[lane] != true
    }

    private fun relaxedBaseForLane(traderTag: String): Double {
        return perLaneMultiplier[canonicalLaneKey(traderTag)] ?: perLaneMultiplier[traderTag.uppercase()] ?: 1.0
    }

    /** EFFECTIVE multiplier after the maturity fade and WR-gate. */
    private fun effectiveMultiplier(traderTag: String): Double {
        if (!enabled) return 1.0
        // V5.0.4081 — NO BOOTSTRAP IN LIVE (operator P0). Live trading uses
        // real money: sample-count handholding is a paper-mode concept that
        // leaks bleed-protection into a real-money pipeline. The V5.0.4078
        // bootstrap branch incorrectly tied the WR floor to lifetime sample
        // count, producing a 45% MATURE cliff at n=20 that locked the relaxer
        // off for the rebuild window. Now the floors are FLAT and quality-
        // based: relax fires whenever live WR clears 30%, emergency only
        // below 20%. Quality drives sizing, not newness.
        val liveWr = refreshLiveWrCache()
        val liveTerminalN = cachedLiveTerminalCount
        // V5.0.4300 — soft-start before global WR kill-switch. Paper learning
        // works because it samples broadly first; live must do the same with
        // reduced size instead of disabling the relaxer after 3-5 bootstrap
        // losses. Once enough live terminal closes exist, WR floors harden.
        val applyGlobalWrFloor = liveTerminalN >= GLOBAL_SOFT_START_LIVE_CLOSES
        val lanePositive6031 = lanePositiveOverride(traderTag)
        if (applyGlobalWrFloor && liveWr < EMERGENCY_FLOOR_PCT && !lanePositive6031) return 1.0
        if (applyGlobalWrFloor && liveWr < DOCTRINE_FLOOR_PCT && !lanePositive6031)  return 1.0
        if (dumpRegimeNoRelax(traderTag) && !lanePositive6031) return 1.0
        val base = relaxedBaseForLane(traderTag)
        if (base >= 1.0) return 1.0  // never relaxed → nothing to fade
        val liveN = liveCountForLane(traderTag)
        if (lanePositive6031) return base // earned positive-EV lane keeps throughput; bad global WR must not choke it
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

    /**
     * V5.0.4129 — Per-token golden-goose override.
     * When live WR is below the doctrine floor (death-spiral state), the relaxer
     * globally disables. That locks lanes out of the very tokens that COULD
     * recover the WR — including PROVEN winning patterns (theme_space 82% WR,
     * theme_ai 50% WR, etc.). This override lets the relaxer still relax FOR
     * THE SPECIFIC TOKEN when PatternGoldenGoose says GOLD or WINNER.
     *
     * Non-veto, fail-open: if the goose has no opinion, falls through to the
     * standard global floorMultiplier(). Only GOLD/WINNER verdicts grant the
     * override; TOXIC/CATASTROPHIC verdicts return 1.0 (no relax) regardless.
     */
    fun floorMultiplierForToken(traderTag: String, name: String, symbol: String): Double {
        if (!enabled) return 1.0
        val verdict = try {
            com.lifecyclebot.engine.PatternGoldenGoose.edge(name, symbol).verdict
        } catch (_: Throwable) { com.lifecyclebot.engine.TokenWinMemory.Verdict.NEUTRAL }
        // Toxic/catastrophic patterns NEVER get a relax — extra protection layer.
        if (verdict == com.lifecyclebot.engine.TokenWinMemory.Verdict.TOXIC ||
            verdict == com.lifecyclebot.engine.TokenWinMemory.Verdict.CATASTROPHIC) {
            return 1.0
        }
        // Gold/winner pattern: bypass the global WR-floor and DUMP-regime locks.
        // Apply the configured per-lane multiplier with the maturity fade only.
        if (verdict == com.lifecyclebot.engine.TokenWinMemory.Verdict.GOLD ||
            verdict == com.lifecyclebot.engine.TokenWinMemory.Verdict.WINNER) {
            val base = relaxedBaseForLane(traderTag)
            if (base >= 1.0) return 1.0
            val liveN = liveCountForLane(traderTag)
            return when {
                liveN < WARM_MIN   -> base
                liveN >= WARM_FULL -> 1.0
                else -> {
                    val t = (liveN - WARM_MIN).toDouble() / (WARM_FULL - WARM_MIN).toDouble()
                    base + (1.0 - base) * t
                }
            }
        }
        return effectiveMultiplier(traderTag)
    }

    fun relaxFloor(originalFloor: Double, traderTag: String, isLiveMode: Boolean): Double {
        if (!isLiveMode) return originalFloor
        return originalFloor * floorMultiplier(traderTag)
    }

    fun relaxFloor(originalFloor: Int, traderTag: String, isLiveMode: Boolean): Int {
        if (!isLiveMode) return originalFloor
        return (originalFloor * floorMultiplier(traderTag)).toInt()
    }

    fun setMultiplier(traderTag: String, multiplier: Double) {
        perLaneMultiplier[canonicalLaneKey(traderTag)] = multiplier.coerceIn(0.5, 1.5)
    }

    fun summaryLine(): String {
        if (!enabled) return "🔓 GATE RELAXER: disabled"
        val liveWr = refreshLiveWrCache()
        val liveTerminalN = cachedLiveTerminalCount
        if (liveTerminalN >= GLOBAL_SOFT_START_LIVE_CLOSES && liveWr < DOCTRINE_FLOOR_PCT) {
            val positiveParts = perLaneMultiplier.keys
                .filter { lanePositiveOverride(it) && relaxedBaseForLane(it) < 1.0 }
                .joinToString(" · ") { "$it ×${"%.2f".format(relaxedBaseForLane(it))}(positive_EV n=${liveCountForLane(it)})" }
            if (positiveParts.isBlank()) return "🔒 GATE RELAXER: DISABLED (live WR=${"%.1f".format(liveWr)}% < ${DOCTRINE_FLOOR_PCT.toInt()}% floor, n=$liveTerminalN)"
            return "🔓 GATE RELAXER: LANE-POSITIVE OVERRIDE $positiveParts globalWR=${"%.1f".format(liveWr)}% n=$liveTerminalN"
        }
        val parts = perLaneMultiplier.keys
            .map { it to effectiveMultiplier(it) }
            .filter { it.second < 1.0 }
            .joinToString(" · ") { (tag, m) ->
                "$tag ×${"%.2f".format(m)}(n=${liveCountForLane(tag)})"
            }
        return if (parts.isEmpty()) "🔓 GATE RELAXER: all lanes matured → 1.00× (earned floors) liveWR=${"%.1f".format(liveWr)}% n=$liveTerminalN"
        else                        "🔓 GATE RELAXER (soft-start/fade): $parts liveWR=${"%.1f".format(liveWr)}% n=$liveTerminalN"
    }

    // V5.0.4081 — FLAT QUALITY-BASED FLOORS (no bootstrap in live). The
    // relaxer fires whenever live WR clears 30%; emergency lockdown only
    // below 20%. These thresholds match the operator doctrine "we trade
    // for profit, not for samples".
    // V5.0.4178 — operator directive: V5.0.4177's lowering of the floor
    // was the WRONG philosophy. Operator wants TIGHTER quality when WR
    // is bad, not looser entries. Restored to 30/20.
    private const val EMERGENCY_FLOOR_PCT = 20.0
    private const val DOCTRINE_FLOOR_PCT  = 30.0

    // V5.0.4067 — LIVE WR GATE. Operator directive: relaxer must not loosen
    // lanes while live WR is below the 45% doctrine floor. Below 35% = emergency
    // quality-only (all relaxation disabled). In DUMP regime = zero relaxation.
    // V5.0.4071 — moved here (after summaryLine) so it's outside the
    // dumpRegimeNoRelax extraction window tested by Golden Tape.
    @Volatile private var cachedLiveWrPct: Double = 100.0
    @Volatile private var cachedLiveTerminalCount: Int = 0
    @Volatile private var liveWrCacheStampMs: Long = 0L
    private const val LIVE_WR_CACHE_TTL_MS = 30_000L

    private fun refreshLiveWrCache(): Double {
        val now = System.currentTimeMillis()
        if (now - liveWrCacheStampMs <= LIVE_WR_CACHE_TTL_MS) return cachedLiveWrPct
        liveWrCacheStampMs = now
        val wr = try {
            val leaderboard = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 2_500)
            val allLive = leaderboard.filter { it.isStatisticallyMeaningful }
            val totalTrades = allLive.sumOf { it.trades }
            cachedLiveTerminalCount = totalTrades
            if (allLive.isEmpty()) 100.0  // no data → don't choke cold start
            else {
                val totalWins = allLive.sumOf { it.wins }
                if (totalTrades > 0) (totalWins.toDouble() / totalTrades) * 100.0 else 100.0
            }
        } catch (_: Throwable) { 100.0 }
        cachedLiveWrPct = wr
        return wr
    }

    /**
     * V5.0.4178 — public live WR accessor.
     * Exposes the cached live WR % so other call sites (e.g.
     * BotService.shouldRunBuyLaneForCycle for L7 lane suppression) can
     * gate behaviour on live performance without duplicating the
     * StrategyTelemetry leaderboard computation. TTL is 30s, identical
     * to the cache used by the relaxer itself.
     */
    fun currentLiveWrPct(): Double = refreshLiveWrCache()

    /** V5.0.4300 — expose sample count so sibling gates can avoid disabling
     * live soft-start before the brains have enough terminal evidence. */
    fun currentLiveTerminalCount(): Int {
        refreshLiveWrCache()
        return cachedLiveTerminalCount
    }
}
