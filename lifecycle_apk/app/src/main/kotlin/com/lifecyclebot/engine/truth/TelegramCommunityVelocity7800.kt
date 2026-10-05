package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.TelegramScraper
import com.lifecyclebot.util.AppDispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * V5.0.7800 — KEYLESS PUBLIC TELEGRAM COMMUNITY VELOCITY.
 *
 * Advisory/background only. Reads public t.me/s/{handle} previews through the
 * existing TelegramScraper. Never performs network I/O on the Moonshot scoring
 * thread. Missing/private/unavailable channels remain neutral.
 */
object TelegramCommunityVelocity7800 {
    data class Snapshot(
        val handle: String,
        val capturedAtMs: Long,
        val subscriberCount: Long,
        val subscriberGrowthPerMin: Double,
        val messages30m: Int,
        val messages2h: Int,
        val avgViews: Double,
        val viewGrowthPct: Double,
        val cadenceAcceleration: Double,
        val communityScore: Double,
        val reason: String,
    )

    private data class Cached(val snap: Snapshot, val raw: TelegramScraper.PublicChannelStats)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scraper = TelegramScraper()
    private const val CACHE_MS = 2L * 60_000L
    private const val MAX_REQUESTS_PER_MIN = 10
    private val budgetWindowStart = AtomicLong(0L)
    private val budgetUsed = AtomicInteger(0)

    private fun handle(raw: String): String = raw.trim()
        .removePrefix("https://t.me/").removePrefix("http://t.me/")
        .removePrefix("t.me/").removePrefix("s/").substringBefore('?')
        .substringBefore('/').trimStart('@').trim()

    private fun takeBudget(now: Long): Boolean {
        val start = budgetWindowStart.get()
        if (start == 0L || now - start >= 60_000L) {
            if (budgetWindowStart.compareAndSet(start, now)) budgetUsed.set(0)
        }
        return budgetUsed.incrementAndGet() <= MAX_REQUESTS_PER_MIN
    }

    fun peekAndRefresh(mint: String, telegramUrl: String?, nowMs: Long = System.currentTimeMillis()): Snapshot? {
        val h = handle(telegramUrl.orEmpty())
        if (mint.isBlank() || h.isBlank()) return cache[mint]?.snap
        val prior = cache[mint]
        if (prior != null && nowMs - prior.snap.capturedAtMs in 0L..CACHE_MS) return prior.snap
        if (!inFlight.add(mint)) return prior?.snap
        if (!takeBudget(nowMs)) {
            inFlight.remove(mint)
            try { PipelineHealthCollector.labelInc("TELEGRAM_COMMUNITY_BUDGET_DEFERRED_7800") } catch (_: Throwable) {}
            return prior?.snap
        }
        try {
            GlobalScope.launch(AppDispatchers.sideEffect) {
                try {
                    val raw = scraper.scrapePublicChannelStats(h, System.currentTimeMillis())
                    if (raw != null) {
                        val old = cache[mint]
                        val dtMin = old?.raw?.let { ((raw.capturedAtMs - it.capturedAtMs).coerceAtLeast(1L) / 60_000.0) } ?: 0.0
                        val subGrowth = if (old != null && dtMin > 0.0 && raw.subscriberCount > 0L && old.raw.subscriberCount > 0L)
                            (raw.subscriberCount - old.raw.subscriberCount).toDouble() / dtMin else 0.0
                        val viewGrowth = if (old != null && old.raw.avgViews > 0.0)
                            ((raw.avgViews / old.raw.avgViews) - 1.0) * 100.0 else 0.0
                        val expected30From2h = raw.messages2h / 4.0
                        val cadenceAccel = if (expected30From2h > 0.0) raw.messages30m / expected30From2h else if (raw.messages30m > 0) 2.0 else 0.0
                        var score = 0.0
                        when {
                            subGrowth >= 25.0 -> score += 14.0
                            subGrowth >= 5.0 -> score += 10.0
                            subGrowth > 0.0 -> score += 5.0
                        }
                        when {
                            cadenceAccel >= 2.0 && raw.messages30m >= 3 -> score += 8.0
                            cadenceAccel >= 1.25 && raw.messages30m >= 2 -> score += 5.0
                            raw.messages30m >= 1 -> score += 2.0
                        }
                        when {
                            viewGrowth >= 50.0 -> score += 7.0
                            viewGrowth >= 15.0 -> score += 4.0
                        }
                        if (raw.subscriberCount >= 1_000L) score += 4.0
                        else if (raw.subscriberCount >= 250L) score += 2.0
                        val snap = Snapshot(
                            handle=h,capturedAtMs=raw.capturedAtMs,subscriberCount=raw.subscriberCount,
                            subscriberGrowthPerMin=subGrowth,messages30m=raw.messages30m,messages2h=raw.messages2h,
                            avgViews=raw.avgViews,viewGrowthPct=viewGrowth,cadenceAcceleration=cadenceAccel,
                            communityScore=score.coerceIn(0.0,30.0),
                            reason="subs="+raw.subscriberCount+" subV="+String.format("%.2f",subGrowth)+"/m m30="+raw.messages30m+" m2h="+raw.messages2h+" cadence="+String.format("%.2f",cadenceAccel)+" views="+String.format("%.0f",raw.avgViews)+" viewV="+String.format("%.1f",viewGrowth)+"% score="+String.format("%.1f",score),
                        )
                        cache[mint] = Cached(snap, raw)
                        try { PipelineHealthCollector.labelInc("TELEGRAM_COMMUNITY_REFRESHED_7800") } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("TELEGRAM_COMMUNITY_REFRESH_FAIL_7800") } catch (_: Throwable) {}
                } finally { inFlight.remove(mint) }
            }
        } catch (_: Throwable) { inFlight.remove(mint) }
        return prior?.snap
    }

    fun cached(mint: String): Snapshot? = cache[mint]?.snap
    internal fun resetForTest() { cache.clear(); inFlight.clear(); budgetUsed.set(0); budgetWindowStart.set(0L) }
}
