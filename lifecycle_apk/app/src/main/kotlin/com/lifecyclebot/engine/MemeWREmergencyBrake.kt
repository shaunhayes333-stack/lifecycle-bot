package com.lifecyclebot.engine

/**
 * V5.9.446 — Meme Win-Rate Emergency Brake
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Self-healing circuit breaker for the meme lane.
 *
 * CONTEXT (user 02-2026, build 2316):
 *   Meme trader stuck at 22% WR after 1,297 trades (286W / 992L / 192S).
 *   Shit lane alone is 17% WR, Express lane 100% loss. The bot keeps buying
 *   low-quality pump.fun dust, losing on the overwhelming majority, and
 *   training bad buckets that reinforce the pattern.
 *
 * STRATEGY:
 *   - Read the last 200 closed SELL trades from TradeHistoryStore.
 *   - Filter to meme-lane closes only (tradingMode = Shit / Moon / Quality /
 *     BlueChip / Express / Manip / Treasury / V3) so perps/stocks noise
 *     doesn't mask the meme picture.
 *   - If >= 500 lifetime meme closes AND window WR < 30%, engage the brake:
 *       • +15 score threshold (stricter entry)
 *       • 0.5× position sizing (half capital at risk)
 *   - Brake releases automatically when WR climbs back above 35% — a small
 *     hysteresis band prevents flapping right at 30%.
 *
 * READ-ONLY from downstream callers. Never mutates state; trade recording
 * is already handled by TradeHistoryStore / FluidLearningAI.
 */
object MemeWREmergencyBrake {

    private const val TAG = "MemeWRBrake"

    // V5.9.986 — Performance Doctrine alignment: bootstrap phase is <5000 lifetime
    // meme closes (WR 20-35% is THE EXPECTED RANGE per Doctrine #4). The brake
    // was engaging at 500 trades with <30% WR, which is the literal middle of
    // the bootstrap doctrine band — choking the bot during its learning phase.
    // Raised to 5000 so the brake only activates once the bot enters the
    // mature-phase WR target (50-89%) and a sub-30% reading is actually anomalous.
    private const val MIN_LIFETIME_TRADES = 5000
    private const val WINDOW_SIZE         = 200
    private const val ENGAGE_WR_PCT       = 30.0
    private const val RELEASE_WR_PCT      = 35.0   // hysteresis
    // V5.9.495z9 — operator: 'completely choked, no layers or traders are
    // working' (live, 06 May 2026). Brake stack was killing all live entries.
    // SCORE_BOOST 8→2 (just a nudge), SIZING_MULT 0.85→0.95.
    // V5.9.618 — bounded restoration of bite. The walk-back to 2/0.95 was an
    // over-correction to "no layers working" symptom — the disease was the
    // dead learning loop, not the brake. With pattern-learning + closed-loop
    // narratives now active (V5.9.618), the brake can have meaningful effect
    // again WITHOUT reverting to the historical 8/0.85 stomp.
    //   SCORE_BOOST: 2 → 4   (+2 score bar at <30% WR, still half of original 8)
    //   SIZING_MULT: 0.95 → 0.85   (15% size reduction, still half of original 0.5 cap)
    // Engagement gate (500 lifetime trades + <30% WR) is unchanged so this
    // never fires during bootstrap — only after the bot has had real reps.
    private const val SCORE_BOOST         = 4
    private const val SIZING_MULT         = 0.85

    private val MEME_MODES = setOf(
        "Shit", "ShitCoin", "Shitcoin",
        "Moon", "Moonshot",
        "Quality",
        "Blue", "BlueChip", "Bluechip",
        "Express", "ShitCoinExpress",
        "Manip", "Manipulated",
        "Treasury",
        "V3",
    )

    @Volatile private var cachedStatus: Status = Status(false, 0.0, 0, 0L)
    // V5.9.1342 — async-refresh guard. The hot path NEVER blocks; a single
    // background thread recomputes the snapshot when the cache goes stale.
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var primed = false

    private const val REFRESH_MS = 30_000L

    data class Status(
        val engaged: Boolean,
        val windowWrPct: Double,
        val windowClosedCount: Int,
        val refreshedAtMs: Long,
    )

    private fun isMemeMode(mode: String?): Boolean {
        if (mode.isNullOrBlank()) return false
        val m = mode.trim()
        return MEME_MODES.any { it.equals(m, ignoreCase = true) }
    }

    /**
     * V5.9.1342 — STALE-WHILE-REVALIDATE. The hot path ALWAYS returns the last
     * cached value instantly (O(1)) and NEVER touches the trade store on the
     * calling thread. When the cache is older than REFRESH_MS, a single
     * background thread recomputes it. This is the structural fix for the
     * recurring ~2s main-thread ANR: memeWrSnapshot() (a locked linear scan of
     * the whole trade list) was running synchronously inside compute() on the
     * UI thread via MainActivity.updateUi → ShitCoinTraderAI.getStats →
     * scoreBoost() every time the 30s cache expired, producing 9s frame gaps
     * and Android ANR kills that looked like "trading stopped".
     * Operator doctrine: fix ANR at the structural source; heavy DB work off
     * the main thread; never throttle the render loop.
     */
    private fun status(): Status {
        val now = System.currentTimeMillis()
        val cur = cachedStatus
        if (now - cur.refreshedAtMs >= REFRESH_MS) {
            // Cache is stale — kick off ONE background recompute. Callers keep
            // using the slightly-stale value until it lands (next tick).
            if (refreshing.compareAndSet(false, true)) {
                try {
                    Thread {
                        try {
                            val fresh = compute(System.currentTimeMillis())
                            cachedStatus = fresh
                            primed = true
                        } catch (_: Throwable) {
                        } finally {
                            refreshing.set(false)
                        }
                    }.apply { isDaemon = true; name = "meme-wr-brake-refresh" }.start()
                } catch (_: Throwable) {
                    // If we somehow can't spawn a thread, never strand the guard.
                    refreshing.set(false)
                }
            }
        }
        return cur
    }

    private fun compute(now: Long): Status {
        // V5.9.1249 — single bounded, lock-internal pass. Was: getAllTrades()
        // copied up to 10k Trade objects then sequence-filtered ON THE MAIN
        // THREAD (via MainActivity.updateUi → ShitCoinTraderAI.getStats →
        // scoreBoost), producing a 2.1s ArrayList.copyOf ANR that stalled the
        // bot-loop to 18.5s/cycle and looked like a freeze. memeWrSnapshot does
        // the lifetime count + last-WINDOW_SIZE WR inside the store's lock with
        // no full-list materialisation. Brake semantics unchanged.
        val (lifetime, wrPct) = try {
            TradeHistoryStore.memeWrSnapshot(WINDOW_SIZE, MIN_LIFETIME_TRADES) { isMemeMode(it) }
        } catch (_: Exception) { Pair(0, 0.0) }

        if (lifetime < MIN_LIFETIME_TRADES) {
            return Status(false, 0.0, lifetime, now)
        }

        val windowCount = if (lifetime < WINDOW_SIZE) lifetime else WINDOW_SIZE

        // Hysteresis: once engaged, require RELEASE_WR_PCT to release.
        val prev = cachedStatus
        val engaged = if (prev.engaged) wrPct < RELEASE_WR_PCT else wrPct < ENGAGE_WR_PCT

        if (engaged != prev.engaged) {
            if (engaged) {
                ErrorLogger.warn(TAG,
                    "🚨 ENGAGED | meme WR=${"%.1f".format(wrPct)}% (last $windowCount) lifetime=$lifetime " +
                    "— raising score bar +$SCORE_BOOST, halving sizing until WR ≥ ${RELEASE_WR_PCT.toInt()}%")
            } else {
                ErrorLogger.info(TAG,
                    "✅ RELEASED | meme WR recovered to ${"%.1f".format(wrPct)}% — full entry gate restored")
            }
        }
        return Status(engaged, wrPct, windowCount, now)
    }

    /** Is the brake currently active? Hot-path safe.
     *  V5.9.986 — paper bypass (doctrine #1: paper is the learning lab).
     *  Live mode keeps the brake intact for capital protection. */
    fun isEngaged(): Boolean {
        try { if (com.lifecyclebot.engine.KillSwitch.isPaperMode) return false } catch (_: Throwable) {}
        return status().engaged
    }

    /** Add to score threshold when engaged, else 0. */
    fun scoreBoost(): Int = if (status().engaged) SCORE_BOOST else 0

    /** Multiplier to apply to meme entry sizing (0.5 when engaged, 1.0 otherwise). */
    fun sizingMultiplier(): Double = if (status().engaged) SIZING_MULT else 1.0

    /** Exposed for UI/debug readout. */
    fun snapshot(): Status = status()
}
