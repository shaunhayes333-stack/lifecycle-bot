package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.3718 — hot-path safe catastrophic paper bleed signal.
 *
 * 3716 correctly stopped score<=10 specialist-primary leakage during a 5.4% WR
 * paper collapse, but it read RegimeDetector.current() from AgenticStyleRouter
 * and shouldRunBuyLaneForCycle. RegimeDetector can synchronously scan/copy trade
 * history on cache expiry, and those two callers sit in the hottest scanner/lane
 * loops. Result: global slowdown.
 *
 * This object keeps the same protection semantics (paper WR below doctrine floor
 * after enough closes) but exposes an O(1) stale-while-revalidate flag. The hot
 * path never touches TradeHistoryStore or RegimeDetector; a single daemon refresh
 * computes the flag off-thread.
 */
object CatastrophicPaperBleedGuard {
    data class Status(
        val active: Boolean,
        val wrPct: Double,
        val sample: Int,
        val refreshedAtMs: Long,
    )

    private const val WINDOW_SIZE = 200
    private const val MIN_SAMPLE = 100
    private const val ENGAGE_WR_PCT = 20.0
    private const val RELEASE_WR_PCT = 25.0
    private const val REFRESH_MS = 15_000L

    @Volatile private var cached = Status(false, 0.0, 0, 0L)
    private val refreshing = AtomicBoolean(false)

    private val memeModes = setOf(
        "Shit", "ShitCoin", "Shitcoin",
        "Moon", "Moonshot",
        "Quality",
        "Blue", "BlueChip", "Bluechip",
        "Express", "ShitCoinExpress",
        "Manip", "Manipulated",
        "Treasury",
        "V3",
        "DIP_HUNTER", "PROJECT_SNIPER", "LAB",
    )

    private fun isMemeMode(mode: String?): Boolean {
        if (mode.isNullOrBlank()) return false
        val m = mode.trim()
        return memeModes.any { it.equals(m, ignoreCase = true) }
    }

    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (now - cached.refreshedAtMs < REFRESH_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            Thread {
                try {
                    val prev = cached
                    val (sample, wr) = try {
                        TradeHistoryStore.memeWrSnapshot(WINDOW_SIZE, MIN_SAMPLE) { isMemeMode(it) }
                    } catch (_: Throwable) { Pair(prev.sample, prev.wrPct) }
                    val active = if (prev.active) wr < RELEASE_WR_PCT else wr < ENGAGE_WR_PCT
                    cached = Status(active && sample >= MIN_SAMPLE, wr, sample, System.currentTimeMillis())
                } catch (_: Throwable) {
                    // Keep stale status. Hot path must never fail closed or block.
                } finally {
                    refreshing.set(false)
                }
            }.apply { isDaemon = true; name = "catastrophic-paper-bleed-refresh" }.start()
        } catch (_: Throwable) {
            refreshing.set(false)
        }
    }

    fun isActive(): Boolean {
        try { if (!RuntimeModeAuthority.isPaper()) return false } catch (_: Throwable) { return false }
        maybeRefresh()
        return cached.active
    }

    fun snapshot(): Status {
        maybeRefresh()
        return cached
    }
}
