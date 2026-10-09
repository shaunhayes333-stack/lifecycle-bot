package com.lifecyclebot.engine.truth

/**
 * V5.0.7948 — ONE live drawdown number, ONE truthful capacity sentence, and
 * capital rotation when the wallet cannot fund a single routable order.
 *
 * 5.0.7947 live diag: KillSwitch printed "SIZE_DOWN_NOT_HALT_7864: DD 86%/25%"
 * while the strategy analytics printed a 0.6% max drawdown. Neither was the
 * account drawdown:
 *   - KillSwitch read a peak that only ever rose (persisted across restarts)
 *     against cash plus cost basis, so one spiky read became a permanent "86%"
 *     and, through KillSwitch.pivotReasons7876, a permanent PIVOT that refused
 *     every live lane without proof;
 *   - the analytics curve is closed-trade P&L seeded at the PAPER starting cash.
 * The basis here is the one the owner set: liquid live SOL plus the MARKED value
 * of open live positions, against the rolling-24h peak of that same equity
 * (LiveRiskPolicy7807's sizing window). Cash moved into a position is not a
 * loss; paper never enters.
 */
object CapitalDrawdown7948 {

    private const val BASIS_7948 = "live equity = liquid SOL + marked open live positions vs rolling-24h peak (paper excluded)"
    private const val PEAK_WINDOW_MS_7948 = 24L * 60L * 60_000L

    // ── pure: equity and drawdown ───────────────────────────────────────────

    /** Liquid SOL + (cost basis + unrealized) of open live positions; the open leg never goes below zero. */
    fun markedEquitySol7948(walletSol: Double, openCostBasisSol: Double, openUnrealizedSol: Double): Double {
        val w = if (walletSol.isFinite()) walletSol.coerceAtLeast(0.0) else 0.0
        val cost = if (openCostBasisSol.isFinite()) openCostBasisSol.coerceAtLeast(0.0) else 0.0
        val upnl = if (openUnrealizedSol.isFinite()) openUnrealizedSol else 0.0
        return w + (cost + upnl).coerceAtLeast(0.0)
    }

    data class Peak7948(val peakSol: Double, val atMs: Long)

    /** Rolling high-water: a new high, an expired window or a clock step back re-anchors at [equitySol]. */
    fun rollPeak7948(prior: Peak7948, equitySol: Double, nowMs: Long, windowMs: Long = PEAK_WINDOW_MS_7948): Peak7948 {
        if (!equitySol.isFinite() || equitySol <= 0.0) return prior
        val expired = !prior.peakSol.isFinite() || prior.peakSol <= 0.0 ||
            nowMs < prior.atMs || nowMs - prior.atMs > windowMs
        return if (expired || equitySol >= prior.peakSol) Peak7948(equitySol, nowMs) else prior
    }

    /** Drawdown percent of [equitySol] from the (rolled) peak, 0..100. */
    fun drawdownPct7948(prior: Peak7948, equitySol: Double, nowMs: Long, windowMs: Long = PEAK_WINDOW_MS_7948): Double {
        if (!equitySol.isFinite() || equitySol <= 0.0) return 0.0
        val p = rollPeak7948(prior, equitySol, nowMs, windowMs)
        if (p.peakSol <= 0.0) return 0.0
        return ((p.peakSol - equitySol) / p.peakSol * 100.0).coerceIn(0.0, 100.0)
    }

    // ── runtime authority (live only) ───────────────────────────────────────

    @Volatile private var peak7948 = Peak7948(0.0, 0L)

    /** KillSwitch seeds the persisted peak at init and on reset. */
    fun seed7948(peakSol: Double, atMs: Long) {
        synchronized(this) { peak7948 = Peak7948(peakSol, atMs) }
    }

    fun currentPeak7948(): Peak7948 = peak7948

    /** Observes [equitySol] (moves the peak) and returns the drawdown percent. */
    fun observePct7948(equitySol: Double, nowMs: Long = System.currentTimeMillis()): Double = synchronized(this) {
        peak7948 = rollPeak7948(peak7948, equitySol, nowMs)
        drawdownPct7948(peak7948, equitySol, nowMs)
    }

    /** Reads the drawdown percent without moving the peak (reports, preflight). */
    fun peekPct7948(equitySol: Double, nowMs: Long = System.currentTimeMillis()): Double =
        drawdownPct7948(peak7948, equitySol, nowMs)

    /** Live marked equity: liquid SOL + canonical live cost basis + the hero's live-only unrealized. */
    fun liveMarkedEquitySol7948(walletSol: Double): Double {
        val deployed = try { LiveRiskPolicy7807.liveEquitySol(0.0) } catch (_: Throwable) { 0.0 }
        val upnl = try { HeroSnapshotAuthority6503.current()?.liveTotalUnrealizedSol ?: 0.0 } catch (_: Throwable) { 0.0 }
        return markedEquitySol7948(walletSol, deployed, upnl)
    }

    /** The one drawdown line every display prints, with its basis. */
    fun line7948(equitySol: Double, nowMs: Long = System.currentTimeMillis()): String {
        val p = rollPeak7948(peak7948, equitySol, nowMs)
        return "liveDD=${"%.1f".format(peekPct7948(equitySol, nowMs))}% equity=${"%.4f".format(equitySol)} " +
            "peak24h=${"%.4f".format(p.peakSol)} basis=[$BASIS_7948]"
    }

    // ── pure: routable capacity sentence ────────────────────────────────────

    /**
     * The REFUSE sentence for LivePreflight7222's ROUTABLE_CAPACITY. Capacity 0
     * means liquid tradeable SOL is below one routable order: no share guard is
     * involved (the single-position rule applies at capacity 0 and 1), so the
     * only honest remedy is the shortfall to one order.
     */
    fun routableRefusalReason7948(capacity: Int, tradeableSol: Double, routableMinSol: Double): String =
        if (capacity <= 0) "tradeable ${"%.4f".format(tradeableSol)} SOL is below one routable order " +
            "${"%.5f".format(routableMinSol)} SOL (single-position rule; no share cap applies)"
        else "one routable position exceeds the share guard"

    /** SOL the wallet needs to add before one routable order can be funded (>= 0). */
    fun shortfallSol7948(walletSol: Double, reserveSol: Double, routableMinSol: Double): Double =
        (routableMinSol + reserveSol - walletSol).coerceAtLeast(0.0)

    // ── pure: capital rotation ──────────────────────────────────────────────

    data class RotationInput7948(
        val walletSol: Double,
        val reserveSol: Double,
        val routableMinSol: Double,
        val positionValueSol: Double,
        val pnlPct: Double,
        val peakGainPct: Double,
        val ageMs: Long,
        val laneMaxHoldMinutes: Int,
        /** -1 when this position's last new high is unknown. */
        val msSinceNewHigh: Long,
        val markAgeMs: Long,
        /** Partial sold, capital recovered, profit locked or house money. */
        val banked: Boolean,
        val msSinceLastRotation: Long,
    )

    /** Above this the position is green: never rotated. */
    private const val ROTATE_MAX_PNL_PCT_7948 = 1.0
    /** PeakDrawdownLock arms here; its trail owns the exit. */
    private const val ROTATE_PEAK_EXEMPT_PCT_7948 = 20.0
    private const val ROTATE_NO_NEW_HIGH_MS_7948 = 15L * 60_000L
    private const val ROTATE_MARK_MAX_AGE_MS_7948 = 120_000L
    /** One rotation at a time; the freed SOL gets a chance to buy first. */
    private const val ROTATE_COOLDOWN_MS_7948 = 5L * 60_000L
    /** Exit cost allowance when testing whether selling frees one order. */
    private const val ROTATE_EXIT_HAIRCUT_7948 = 0.95

    /**
     * A position's useful hold: its lane's reference hold, bounded to 45..90
     * minutes. Below 45 the 30-minute culls and the 60-minute labels have not
     * spoken; above 90 nothing in the 5m..60m label evidence says holding pays.
     */
    fun usefulHoldMs7948(laneMaxHoldMinutes: Int): Long =
        laneMaxHoldMinutes.coerceIn(45, 90).toLong() * 60_000L

    /**
     * Null = rotate (sell this position so its SOL funds a proven setup).
     * V5.0.7951 — the Executor passes "a candidate is waiting on capital"
     * (CapitalThroughput7951.demandWaiting7951) as [provenSetupWaiting].
     * Otherwise the first reason it is kept. [provenSetupWaiting] is evaluated
     * last, only when every cheaper condition already holds.
     */
    fun rotationBlocker7948(i: RotationInput7948, provenSetupWaiting: () -> Boolean): String? {
        val tradeable = (i.walletSol - i.reserveSol).coerceAtLeast(0.0)
        return when {
            !i.routableMinSol.isFinite() || i.routableMinSol <= 0.0 -> "NO_ROUTABLE_MIN"
            tradeable >= i.routableMinSol -> "NOT_CAPITAL_STARVED"
            !i.positionValueSol.isFinite() || i.positionValueSol <= 0.0 -> "NO_VALUE"
            tradeable + i.positionValueSol * ROTATE_EXIT_HAIRCUT_7948 < i.routableMinSol -> "WOULD_NOT_FUND_ONE_ORDER"
            i.banked -> "BANKED"
            !i.pnlPct.isFinite() || i.pnlPct > ROTATE_MAX_PNL_PCT_7948 -> "GREEN"
            i.peakGainPct >= ROTATE_PEAK_EXEMPT_PCT_7948 -> "RUNNER_LOCK_ARMED"
            i.ageMs < usefulHoldMs7948(i.laneMaxHoldMinutes) -> "WITHIN_USEFUL_HOLD"
            i.msSinceNewHigh < 0L || i.msSinceNewHigh < ROTATE_NO_NEW_HIGH_MS_7948 -> "STILL_MAKING_HIGHS"
            i.markAgeMs < 0L || i.markAgeMs > ROTATE_MARK_MAX_AGE_MS_7948 -> "STALE_MARK"
            i.msSinceLastRotation in 0L until ROTATE_COOLDOWN_MS_7948 -> "ROTATION_COOLDOWN"
            !provenSetupWaiting() -> "NO_PROVEN_SETUP"
            else -> null
        }
    }

    @Volatile private var lastRotationAtMs7948 = 0L

    fun msSinceLastRotation7948(nowMs: Long = System.currentTimeMillis()): Long =
        if (lastRotationAtMs7948 <= 0L) Long.MAX_VALUE else nowMs - lastRotationAtMs7948

    fun noteRotation7948(nowMs: Long = System.currentTimeMillis()) { lastRotationAtMs7948 = nowMs }

    /** True when at least one live lane's own evidence is PROVEN (LivePivotAuthority7876's test). */
    fun anyLiveLaneProven7948(): Boolean = try {
        LiveRiskPolicy7807.LaneRiskBudget7807.keys.any {
            LivePivotAuthority7876.laneVerdict(it) == LivePivotAuthority7876.Evidence.PROVEN
        }
    } catch (_: Throwable) { false }
}
