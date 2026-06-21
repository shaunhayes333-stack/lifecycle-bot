package com.lifecyclebot.engine

/**
 * V5.0.3984 — live-only maturity authority.
 *
 * Paper/mixed lifetime progress is not allowed to decide live trading maturity.
 * Once the bot has >=500 LIVE terminal closes, it must leave bootstrap-neutral
 * behaviour and actively adapt from live performance. >=5000 remains the full
 * mature/super-AGI sample band from the performance doctrine.
 */
object LiveMaturityAuthority {
    const val LIVE_ADAPTIVE_MIN_CLOSES = 1
    const val LIVE_MATURE_MIN_CLOSES = 5_000

    data class Snapshot(
        val liveTerminalCloses: Int,
        val lifetimeCloses: Int,
        val phase: String,
        val doctrineFloorLabel: String,
        val wrFloorPct: Double,
        val adaptive: Boolean,
        val mature: Boolean,
    )

    fun snapshot(): Snapshot {
        val liveCloses = liveTerminalCloseCount()
        val lifetime = try { TradeHistoryStore.getLifetimeStats().totalSells } catch (_: Throwable) { liveCloses }
        val mature = liveCloses >= LIVE_MATURE_MIN_CLOSES
        // V5.0.4021 — real capital has no bootstrap grace period. From the
        // first clean live terminal close, live sizing/routing must consume
        // live-only feedback. There is no live bootstrap behavior.
        val adaptive = liveCloses >= LIVE_ADAPTIVE_MIN_CLOSES
        val phase = when {
            mature -> "LIVE_MATURE"
            adaptive -> "LIVE_ADAPTIVE"
            else -> "LIVE_ADAPTIVE_FROM_TRADE_1"
        }
        val floor = when {
            mature -> 50.0
            adaptive -> 45.0
            else -> 20.0
        }
        val floorLabel = when {
            mature -> "50-89% live mature"
            adaptive -> "45%+ live adaptive"
            else -> "20-35% live bootstrap"
        }
        return Snapshot(liveCloses, lifetime, phase, floorLabel, floor, adaptive, mature)
    }

    fun liveTerminalCloseCount(limit: Int = 10_000): Int = try {
        TradeHistoryStore.getRecentValidClosedTrades(limit = limit, includePartials = false)
            .count { it.side.equals("SELL", true) && it.mode.equals("live", true) }
    } catch (_: Throwable) { 0 }
}
