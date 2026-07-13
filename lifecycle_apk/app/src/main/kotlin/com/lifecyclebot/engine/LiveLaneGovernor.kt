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
    private const val MIN_SAMPLES_BLEEDER = 20
    private const val BLEEDER_WR = 0.35            // <35% live WR
    private const val BLEEDER_PF = 1.0             // <1.0 live PF

    // Winner criteria (bypasses LaneAutoPauseGuard dampener).
    private const val MIN_SAMPLES_WINNER = 20
    private const val WINNER_WR = 0.50             // ≥50% live WR
    private const val WINNER_PF = 2.0              // ≥2.0 live PF

    private const val PAUSE_MS: Long = 60 * 60_000L         // 60 min hard-pause window
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
        val laneU = lane.uppercase()
        val now = System.currentTimeMillis()
        val until = pausedUntilMs[laneU] ?: 0L
        if (until > now) {
            val remainMin = (until - now) / 60_000L
            return true to "LIVE_LANE_HARD_PAUSED_6247 lane=$laneU remainMin=$remainMin"
        }
        // Not currently paused — refresh stats and check if we should pause NOW.
        val s = laneStats(laneU) ?: return false to ""
        if (s.isBleeder) {
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
            append("V5.0.6247_LIVE_LANE_GOVERNOR: paused=${active.size}")
            if (active.isNotEmpty()) {
                append(" active=[")
                append(active.entries.joinToString(", ") { (k, v) -> "$k(${(v - now) / 60_000L}m)" })
                append("]")
            }
            append(" avgFeeSol=${"%.5f".format(avgFeeSol)} feeN=$n")
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
