package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4168 — PUMP PORTAL WS ADAPTIVE THROTTLE.
 *
 * Operator dump (June 2026) showed AATE consumed 73.66 GB of mobile data
 * across the month — driven hard by two PumpPortal WebSocket clients
 * (`PumpFunWS` + `PumpFunWebSocket`) both connected to
 * `wss://pumpportal.fun/api/data` simultaneously, streaming every
 * pump.fun `create` and `migrate` event in real-time. During hot meme
 * cycles that's ~5–20 MB/min PER socket = up to 60 GB/day combined.
 *
 * THIS IS NOT A KILL SWITCH. We keep the WS alive because the meme
 * trader's edge comes from being first on pump.fun launches. What this
 * gate does is filter messages BEFORE they enter our scoring pipeline:
 *
 *   - `migrate` events: ALWAYS pass through (those are bonding-curve
 *     graduations, low volume + high signal).
 *   - `create` events: drop the bottom-tail of low-mcap-SOL launches
 *     that are statistically dust / scam coins. Threshold rises with
 *     watchlist size — when watchlist is already full of candidates we
 *     don't need every $1K-mcap meme.
 *
 * Per-message bytes saved: keeping ALL events on the wire still costs
 * bandwidth, but the WS framing is small (~200–500 bytes per event).
 * The REAL saving is that filtered-out events do NOT trigger the
 * downstream cascade (provider proofs, rugcheck calls, Birdeye/DexScreener
 * enrichment) — each filtered event saves ~30–100 KB of follow-up
 * HTTP/RPC traffic. That's the dominant cost.
 *
 * SELF-CLEARING. Thresholds rise/fall with the watchlist size live;
 * no persistence; no permanent throttle. Once you're back on Wi-Fi
 * and the watchlist shrinks, full firehose resumes automatically.
 */
object PumpPortalThrottle {

    /** Hard minimum mcap (SOL) for a `create` event to ever pass. */
    private const val MIN_MCAP_SOL_FLOOR = 10.0

    /** Elevated threshold when watchlist is saturated. */
    private const val MIN_MCAP_SOL_SATURATED = 25.0

    /** Watchlist size above which the saturated threshold kicks in. */
    private const val WATCHLIST_SATURATION_SIZE = 200

    private val droppedCreates = AtomicLong(0L)
    private val droppedMigrates = AtomicLong(0L)
    private val passedCreates = AtomicLong(0L)
    private val passedMigrates = AtomicLong(0L)

    /**
     * Returns true if a `create` event with the given mcap (SOL) should
     * be processed. Returns false to drop it (saving ~30–100 KB of
     * downstream provider/rugcheck calls per drop).
     */
    fun allowCreate(marketCapSol: Double): Boolean {
        val watchlistSize = try {
            GlobalTradeRegistry.getWatchlistEntries().size
        } catch (_: Throwable) { 0 }
        val threshold = if (watchlistSize >= WATCHLIST_SATURATION_SIZE)
            MIN_MCAP_SOL_SATURATED
        else MIN_MCAP_SOL_FLOOR
        val allow = marketCapSol >= threshold
        if (allow) passedCreates.incrementAndGet() else droppedCreates.incrementAndGet()
        return allow
    }

    /** Migrations always pass — they're rare and high-signal. */
    fun allowMigrate(): Boolean {
        passedMigrates.incrementAndGet()
        return true
    }

    private val droppedTrades = AtomicLong(0L)
    private val passedTrades = AtomicLong(0L)

    /**
     * Buy/sell events fire on EVERY trade across EVERY pump.fun token —
     * this is the largest single bandwidth driver. Only process trades
     * for mints we already track (watchlist) or have an open position
     * in. Everything else drops at the WS edge so it never enters the
     * scoring/provider cascade.
     */
    fun allowTrade(mint: String): Boolean {
        if (mint.isBlank()) {
            droppedTrades.incrementAndGet()
            return false
        }
        // V5.0.4173 — TRADE SCOPING (pick 3): the meme trader only needs
        // trade events for tokens it ACTUALLY HOLDS (to react to dumps
        // and bank winners). Watchlist candidates have score-driven entry
        // logic that doesn't depend on the trade firehose. This cuts the
        // largest single bandwidth source (every pump.fun trade on every
        // subscribed mint) down to just trades on our open bags.
        //
        // SAFETY: fail-open on any registry error so we never lose a
        // trade event we genuinely need.
        val tracked = try {
            val hasOpenPosition = GlobalTradeRegistry.getOpenPositions().any { it.mint == mint }
            if (hasOpenPosition) {
                true
            } else {
                // Still allow for the first 60s of a watchlist entry's
                // life — that's the meme trader's "decide whether to
                // buy" window where trade flow IS informative.
                val entry = GlobalTradeRegistry.getWatchlistEntries().firstOrNull { it.mint == mint }
                val freshWindow = entry != null &&
                    (System.currentTimeMillis() - entry.addedAt) < 60_000L
                freshWindow
            }
        } catch (_: Throwable) { true /* fail-open */ }
        if (tracked) passedTrades.incrementAndGet() else droppedTrades.incrementAndGet()
        return tracked
    }

    /** Current snapshot for the operator dump telemetry strip. */
    data class Snapshot(
        val droppedCreates: Long,
        val passedCreates: Long,
        val droppedMigrates: Long,
        val passedMigrates: Long,
    ) {
        val totalSeen: Long get() = droppedCreates + passedCreates + droppedMigrates + passedMigrates
        val dropRatePct: Double
            get() = if (totalSeen == 0L) 0.0 else
                (droppedCreates + droppedMigrates).toDouble() / totalSeen * 100.0
    }

    fun snapshot(): Snapshot = Snapshot(
        droppedCreates = droppedCreates.get(),
        passedCreates = passedCreates.get(),
        droppedMigrates = droppedMigrates.get(),
        passedMigrates = passedMigrates.get(),
    )
}
