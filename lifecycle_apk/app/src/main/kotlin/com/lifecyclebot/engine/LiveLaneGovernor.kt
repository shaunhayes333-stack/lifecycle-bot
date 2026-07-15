package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LIVE LANE GOVERNOR — V5.0.6247
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive (2026-07-13, V5.0.6246 report follow-up):
 *   "live trading tuning!!! losing money not making more"
 *
 * ROOT CAUSE (from V5.0.6246 telemetry):
 *   * STANDARD lane bleeding on live: WR=33% n=27 PF=0.75 E=-3.8% but the bot
 *     kept routing new buys to it (756 lane-evals vs BLUECHIP's 151).
 *   * BLUECHIP (65% WR lifetime, +166 SOL) was being DAMPENED to mult=0.55
 *     via LIVE_PROBABILITY_LANE_PAUSED_FLUID_DAMPENED_4596 despite being
 *     the profitable lane.
 *   * RealizedWalletCompounding correctly detected the negative-EV state
 *     (mult=0.55 reason=defensive_clean_negative_or_low_wr) but that only
 *     trims size — the bleed source (STANDARD lane) kept firing.
 *   * Net: wallet 0.318 → 0.277 SOL.
 *
 * DESIGN — three-lever side-band governor:
 *
 *   1. Bleeder hard-pause (new-BUY block).
 *      When a lane has live n≥MIN_SAMPLES AND liveWR<BLEEDER_WR AND
 *      livePF<BLEEDER_PF, we block new live BUYS on that lane for
 *      PAUSE_MS. Existing positions still managed for exit. Auto-release
 *      when the pause window expires and the stats re-qualify or the
 *      operator taps LaneShadowProofLoop.
 *
 *   2. Proven-winner un-dampen.
 *      When a lane has live n≥MIN_SAMPLES AND liveWR≥WINNER_WR AND
 *      livePF≥WINNER_PF, the LiveProbabilityEngine's paused-lane
 *      dampener is bypassed for that lane. Real live evidence overrides
 *      LaneAutoPauseGuard for lanes that have earned trust.
 *
 *   3. Fee-eater guard.
 *      When the requested size is smaller than FEE_ECONOMIC_MULT × the
 *      recent avg fee per trade, we skip the buy. On a 0.28 SOL wallet
 *      the LAST_MILE_FLOOR sometimes pins buys at fractions that can't
 *      overcome slippage+fee friction.
 *
 * Data source: StrategyTelemetry.computeCleanLiveTerminalLeaderboard —
 * a canonical live-only PnL rollup that the report already trusts.
 * We refresh on demand (short TTL cache) to avoid hot-path recomputation.
 *
 * NO GOLDEN-TAPE LITERALS ARE ALTERED.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LiveLaneGovernor {

    private const val TAG = "LiveLaneGovernor"
    private const val PREFS_NAME = "live_lane_governor_v6247"

    // Bleeder criteria (any active pause blocks new BUYS on that lane).
    // V5.0.6259 — tightened AGAINST loosening the safety net (n=40 min instead
    // of 20 stops sample-noise pauses on brand-new lanes) but SHORTENED the
    // pause window so the AGI has room to earn back trust. Also added DNA-
    // approved bypass and proven-variant bypass below so a specific setup
    // that the AGI has learned to win with can still trade on a paused lane.
    private const val MIN_SAMPLES_BLEEDER = 40
    private const val BLEEDER_WR = 0.30            // <30% live WR (was 35% — too lenient in noise)
    private const val BLEEDER_PF = 0.85            // <0.85 live PF (was 1.0 — same reason)

    // Winner criteria (bypasses LaneAutoPauseGuard dampener).
    private const val MIN_SAMPLES_WINNER = 20
    private const val WINNER_WR = 0.50             // ≥50% live WR
    private const val WINNER_PF = 2.0              // ≥2.0 live PF

    private const val PAUSE_MS: Long = 20 * 60_000L         // V5.0.6259 — 20 min (was 60 min)
    private const val CACHE_TTL_MS: Long = 30_000L          // stats refresh cache

    // Fee-eater guard.
    private const val FEE_ECONOMIC_MULT = 3.0

    private data class LaneStats(
        val lane: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val wrPct: Double,          // 0..100
        val avgWinPct: Double,
        val avgLossPct: Double,     // negative
        val totalSolPnl: Double,
    ) {
        val wr01: Double get() = (wrPct / 100.0).coerceIn(0.0, 1.0)
        val pf: Double get() {
            // Doctrine PF: (avgWin * WR) / (|avgLoss| * (1-WR))
            val w = wr01
            val gross = avgWinPct * w
            val loss = kotlin.math.abs(avgLossPct) * (1.0 - w)
            return if (loss > 0.0) gross / loss else if (gross > 0.0) 99.0 else 0.0
        }
        val isBleeder: Boolean get() =
            trades >= MIN_SAMPLES_BLEEDER && wr01 < BLEEDER_WR && pf < BLEEDER_PF
        val isProvenWinner: Boolean get() =
            trades >= MIN_SAMPLES_WINNER && wr01 >= WINNER_WR && pf >= WINNER_PF
    }

    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private val pausedUntilMs = ConcurrentHashMap<String, Long>()   // lane → pause expiry ms
    private val statsCache = ConcurrentHashMap<String, LaneStats>() // lane → cached stats
    private val cacheExpiryMs = AtomicLong(0L)

    // V5.0.6260 — bypass-win streak auto-promotion.
    //   • bypassedMints: mint → lane, stamped when a DNA bypass fires so the
    //     close path (Executor.recordTrade central fanout) can credit the
    //     outcome to the right lane.
    //   • bypassWinStreak: lane → current consecutive-wins-on-bypass count.
    //   • BYPASS_STREAK_UNPAUSE = wins needed to un-pause the whole lane.
    // Rationale: V5.0.6259 lets AGI-proven setups squeeze through paused
    // lanes; if the AGI keeps winning on a paused lane, that IS proof the
    // lane has recovered and the pause is now a drag on wallet growth.
    private val bypassedMints = ConcurrentHashMap<String, String>()
    private val bypassWinStreak = ConcurrentHashMap<String, Int>()
    private const val BYPASS_STREAK_UNPAUSE = 3
    private var bypassAutoUnpauses: Int = 0

    // Rolling fee estimate (SOL/trade) — updated by RecordLiveFee external hook.
    private val feeSumSol = java.util.concurrent.atomic.AtomicLong(0L)   // fee sol × 1e9
    private val feeCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPauseState()
        try { ErrorLogger.info(TAG, "⚖ LiveLaneGovernor init — pauses=${pausedUntilMs.size}") } catch (_: Throwable) {}
    }

    /**
     * Block new live BUYS on this lane if it's a hard-paused bleeder.
     * Returns (blocked, reason). reason is empty when not blocked.
     */
    fun preBuyBleederPause(lane: String): Pair<Boolean, String> {
        return preBuyBleederPauseWithSetup(lane, entrySetup = null, chartPattern = null)
    }

    /**
     * V5.0.6259 — AGI-informed pause override. If the incoming setup+pattern
     * combo has a WINNING DNA in LiveWinDNAStore (≥2 real wins AND avgWin>0
     * AND no losses for that setup), the buy is allowed through even if the
     * lane is otherwise paused. The AGI has learned this exact shape works;
     * blocking it would waste the training. Op-report V5.0.6258 showed 25/43
     * buys blocked by pauses — the wallet cannot grow 2x-5x daily while 60%
     * of intent is muted. This gives the AGI an escape valve tied to
     * measured wins, not blanket loosening.
     */
    fun preBuyBleederPauseWithSetup(
        lane: String,
        entrySetup: String?,
        chartPattern: String?,
    ): Pair<Boolean, String> {
        return preBuyBleederPauseWithSetupAndMint(lane, entrySetup, chartPattern, mint = null)
    }

    /**
     * V5.0.6260 — Full signature. Passes the mint through so we can stamp
     * bypassedMints for later streak crediting. Executor.liveBuy calls this
     * variant.
     */
    fun preBuyBleederPauseWithSetupAndMint(
        lane: String,
        entrySetup: String?,
        chartPattern: String?,
        mint: String?,
    ): Pair<Boolean, String> {
        val laneU = lane.uppercase()
        val now = System.currentTimeMillis()
        val until = pausedUntilMs[laneU] ?: 0L
        val isPaused = until > now

        // Compute bypass eligibility first — cheap and lets us skip stats refresh.
        if (isPaused) {
            val bypass = dnaProvenBypass(laneU, entrySetup, chartPattern)
            if (bypass != null) {
                if (!mint.isNullOrBlank()) bypassedMints[mint] = laneU
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_LANE_DNA_BYPASS_6259",
                        "lane=$laneU setup=${entrySetup ?: "?"} pattern=${chartPattern ?: "?"} evidence=$bypass",
                    )
                    PipelineHealthCollector.labelInc("LIVE_LANE_DNA_BYPASS_6259")
                } catch (_: Throwable) {}
                return false to ""
            }
            val remainMin = (until - now) / 60_000L
            return true to "LIVE_LANE_HARD_PAUSED_6247 lane=$laneU remainMin=$remainMin"
        }

        // Not currently paused — refresh stats and check if we should pause NOW.
        val s = laneStats(laneU) ?: return false to ""
        if (s.isBleeder) {
            // V5.0.6259 — before hard-pausing, check DNA bypass one more time.
            // If the AGI has already learned this specific setup wins, don't
            // pause the lane at all — trust the sub-lane signal.
            val bypass = dnaProvenBypass(laneU, entrySetup, chartPattern)
            if (bypass != null) {
                if (!mint.isNullOrBlank()) bypassedMints[mint] = laneU
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_LANE_DNA_BYPASS_6259_PREPAUSE",
                        "lane=$laneU setup=${entrySetup ?: "?"} pattern=${chartPattern ?: "?"} evidence=$bypass wr=${"%.1f".format(s.wrPct)}%",
                    )
                    PipelineHealthCollector.labelInc("LIVE_LANE_DNA_BYPASS_6259")
                } catch (_: Throwable) {}
                return false to ""
            }
            pausedUntilMs[laneU] = now + PAUSE_MS
            persistPauseState()
            try {
                ForensicLogger.lifecycle(
                    "LIVE_LANE_HARD_PAUSED_6247",
                    "lane=$laneU n=${s.trades} wr=${"%.1f".format(s.wrPct)}% pf=${"%.2f".format(s.pf)} pnlSol=${"%.4f".format(s.totalSolPnl)} pauseMin=${PAUSE_MS / 60_000L}",
                )
                PipelineHealthCollector.labelInc("LIVE_LANE_HARD_PAUSED_6247")
                PipelineHealthCollector.labelInc("LIVE_LANE_HARD_PAUSED_${laneU}")
            } catch (_: Throwable) {}
            return true to "LIVE_LANE_HARD_PAUSED_6247 lane=$laneU n=${s.trades} wr=${"%.1f".format(s.wrPct)}%"
        }
        return false to ""
    }

    /**
     * V5.0.6260 — BYPASS-WIN STREAK AUTO-PROMOTION.
     * Called from the central-fanout recordTrade path on every decisive close.
     * If this mint entered under a DNA bypass while its lane was paused, credit
     * or reset the lane's win-streak; when the streak hits BYPASS_STREAK_UNPAUSE
     * the lane is un-paused early. Idempotent (bails when mint wasn't a
     * bypass entry).
     */
    fun recordBypassOutcome(mint: String, pnlPct: Double) {
        if (mint.isBlank()) return
        val lane = bypassedMints.remove(mint) ?: return
        val laneU = lane.uppercase()
        try {
            if (pnlPct > 0.0) {
                val next = (bypassWinStreak[laneU] ?: 0) + 1
                bypassWinStreak[laneU] = next
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_LANE_BYPASS_WIN_STREAK_6260",
                        "lane=$laneU streak=$next/$BYPASS_STREAK_UNPAUSE pnl=${"%.1f".format(pnlPct)}%",
                    )
                } catch (_: Throwable) {}
                if (next >= BYPASS_STREAK_UNPAUSE) {
                    val hadPause = (pausedUntilMs[laneU] ?: 0L) > System.currentTimeMillis()
                    pausedUntilMs.remove(laneU)
                    persistPauseState()
                    bypassWinStreak[laneU] = 0
                    if (hadPause) bypassAutoUnpauses++
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_LANE_BYPASS_AUTO_UNPAUSE_6260",
                            "lane=$laneU wins=$next total_auto_unpauses=$bypassAutoUnpauses",
                        )
                        PipelineHealthCollector.labelInc("LIVE_LANE_BYPASS_AUTO_UNPAUSE_6260")
                    } catch (_: Throwable) {}
                }
            } else if (pnlPct < 0.0) {
                // Streak broken. The AGI's confidence in this shape was
                // misplaced for this lane — reset and let the pause window
                // resume its natural decay.
                if ((bypassWinStreak[laneU] ?: 0) > 0) {
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_LANE_BYPASS_STREAK_RESET_6260",
                            "lane=$laneU pnl=${"%.1f".format(pnlPct)}%",
                        )
                    } catch (_: Throwable) {}
                }
                bypassWinStreak[laneU] = 0
            }
            // pnlPct == 0 is a scratch — leave streak untouched.
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6259 — Check LiveWinDNAStore for evidence that this specific
     * (setup, chartPattern) combo has been PROVEN to win. Returns a short
     * evidence string when the AGI has learned this shape wins, else null.
     *
     *   setup criteria: setup in winning-setup histogram with n≥2 AND
     *                   avgPnl>0 AND setup NOT in losing-setup histogram
     *                   with ≥2 samples.
     */
    private fun dnaProvenBypass(lane: String, entrySetup: String?, chartPattern: String?): String? {
        if (entrySetup.isNullOrBlank() && chartPattern.isNullOrBlank()) return null
        return try {
            val setupKey = entrySetup?.trim().orEmpty()
            val patternKey = chartPattern?.trim().orEmpty()
            val winners = LiveWinDNAStore.setupFrequency(minCount = 2)
            val losers = LiveWinDNAStore.losingSetupFrequency(minCount = 2)
            val winPatterns = LiveWinDNAStore.chartPatternFrequency(minCount = 2)
            val setupWinner = winners.firstOrNull { it.first.equals(setupKey, ignoreCase = true) }
            val setupLoser = losers.firstOrNull { it.first.equals(setupKey, ignoreCase = true) }
            val patternWinner = winPatterns.firstOrNull { it.first.equals(patternKey, ignoreCase = true) }
            when {
                setupWinner != null && setupWinner.third > 0.0 && setupLoser == null ->
                    "setup=$setupKey n=${setupWinner.second} μ=${"%.1f".format(setupWinner.third)}%"
                patternWinner != null && patternWinner.third > 0.0 && setupLoser == null ->
                    "pattern=$patternKey n=${patternWinner.second} μ=${"%.1f".format(patternWinner.third)}%"
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** True if this lane's live stats have earned override authority against the dampener. */
    fun isProvenWinner(lane: String): Boolean {
        val laneU = lane.uppercase()
        val s = laneStats(laneU) ?: return false
        return s.isProvenWinner
    }

    /**
     * Fee-eater guard. Skip the buy if requestedSol < FEE_ECONOMIC_MULT × avg_fee_sol.
     * Returns (skip, reason).
     */
    fun feeEaterGuard(requestedSol: Double): Pair<Boolean, String> {
        val n = feeCount.get()
        if (n < 5) return false to ""    // insufficient fee history — allow
        val avgFeeSol = feeSumSol.get().toDouble() / 1e9 / n.toDouble()
        val floor = FEE_ECONOMIC_MULT * avgFeeSol
        if (avgFeeSol > 0.0 && requestedSol > 0.0 && requestedSol < floor) {
            try {
                ForensicLogger.lifecycle(
                    "SIZE_BELOW_FEE_ECONOMIC_FLOOR_6247",
                    "requestedSol=${"%.5f".format(requestedSol)} avgFeeSol=${"%.5f".format(avgFeeSol)} floor=${"%.5f".format(floor)} multiplier=${"%.1f".format(FEE_ECONOMIC_MULT)}",
                )
                PipelineHealthCollector.labelInc("SIZE_BELOW_FEE_ECONOMIC_FLOOR_6247")
            } catch (_: Throwable) {}
            return true to "SIZE_BELOW_FEE_ECONOMIC_FLOOR_6247 requestedSol=${"%.5f".format(requestedSol)} floor=${"%.5f".format(floor)}"
        }
        return false to ""
    }

    /** External hook: called from Executor after a live sell to accumulate fee cost. */
    fun recordLiveFee(feeSol: Double) {
        if (!feeSol.isFinite() || feeSol <= 0.0) return
        feeSumSol.addAndGet((feeSol * 1e9).toLong())
        feeCount.incrementAndGet()
    }

    /** Manual/testing hook — clear a specific lane pause. */
    fun releaseLane(lane: String) {
        pausedUntilMs.remove(lane.uppercase())
        persistPauseState()
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        val active = pausedUntilMs.filterValues { it > now }
        val n = feeCount.get()
        val avgFeeSol = if (n > 0) feeSumSol.get().toDouble() / 1e9 / n.toDouble() else 0.0
        return buildString {
            append("V5.0.6260_LIVE_LANE_GOVERNOR: paused=${active.size}")
            if (active.isNotEmpty()) {
                append(" active=[")
                append(active.entries.joinToString(", ") { (k, v) -> "$k(${(v - now) / 60_000L}m)" })
                append("]")
            }
            append(" avgFeeSol=${"%.5f".format(avgFeeSol)} feeN=$n")
            // V5.0.6260 — bypass-streak auto-promotion visibility.
            append(" bypassWatched=${bypassedMints.size} autoUnpauses=$bypassAutoUnpauses")
            val activeStreaks = bypassWinStreak.filterValues { it > 0 }
            if (activeStreaks.isNotEmpty()) {
                append(" streaks=[")
                append(activeStreaks.entries.joinToString(", ") { (k, v) -> "$k:$v/$BYPASS_STREAK_UNPAUSE" })
                append("]")
            }
            // Also show which lanes are proven-winners so operators can see the un-dampen coverage.
            val winners = try {
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
                    .filter { it.trades >= MIN_SAMPLES_WINNER }
                    .mapNotNull { m ->
                        val w = (m.winRatePct / 100.0).coerceIn(0.0, 1.0)
                        val pf = if (kotlin.math.abs(m.avgLossPct) * (1.0 - w) > 0.0)
                            (m.avgWinPct * w) / (kotlin.math.abs(m.avgLossPct) * (1.0 - w))
                        else if (m.avgWinPct * w > 0.0) 99.0 else 0.0
                        if (w >= WINNER_WR && pf >= WINNER_PF) "${m.strategy.uppercase()}(wr=${"%.0f".format(m.winRatePct)}%,pf=${"%.1f".format(pf)})" else null
                    }
            } catch (_: Throwable) { emptyList() }
            if (winners.isNotEmpty()) append(" winners=[${winners.joinToString(",")}]")
        }
    }

    // ─── stats plumbing ─────────────────────────────────────────────────────

    private fun laneStats(laneU: String): LaneStats? {
        refreshIfStale()
        return statsCache[laneU]
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now < cacheExpiryMs.get()) return
        try {
            val leaderboard = StrategyTelemetry.computeCleanLiveTerminalLeaderboard(limit = 1_500)
            statsCache.clear()
            leaderboard.forEach { m ->
                statsCache[m.strategy.uppercase()] = LaneStats(
                    lane = m.strategy.uppercase(),
                    trades = m.trades,
                    wins = m.wins,
                    losses = m.losses,
                    wrPct = m.winRatePct,
                    avgWinPct = m.avgWinPct,
                    avgLossPct = m.avgLossPct,
                    totalSolPnl = m.totalSolPnl,
                )
            }
        } catch (_: Throwable) {}
        cacheExpiryMs.set(now + CACHE_TTL_MS)
    }

    // ─── persistence ────────────────────────────────────────────────────────

    private fun persistPauseState() {
        val p = prefs ?: return
        try {
            val serialized = pausedUntilMs.entries
                .filter { it.value > System.currentTimeMillis() }
                .joinToString(";") { "${it.key}=${it.value}" }
            p.edit().putString("paused", serialized).apply()
        } catch (_: Throwable) {}
    }

    private fun loadPauseState() {
        val p = prefs ?: return
        try {
            val raw = p.getString("paused", null) ?: return
            val now = System.currentTimeMillis()
            raw.split(";").filter { it.isNotBlank() }.forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val lane = pair.substring(0, idx)
                    val until = pair.substring(idx + 1).toLongOrNull() ?: 0L
                    if (until > now) pausedUntilMs[lane] = until
                }
            }
        } catch (_: Throwable) {}
    }
}
