package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4172 — ADAPTIVE TOKEN REFRESH POLICY.
 *
 * Operator architecture mandate: *"once discovered we only need to monitor
 * things that move. not the whole token metrics."* (June 2026, after AATE
 * burned 281 GB / 29 days.)
 *
 * This module is the single source of truth for "should we refetch dynamic
 * data for this mint right now?". Every caller that touches the network to
 * refresh `price / liquidity / volume / holders` consults
 * [shouldRefreshDynamic] before issuing the call. Static metadata (`symbol`,
 * `name`, `decimals`, `pairAddress`, `creator`, `creationTimeMs`,
 * `mintAuthority`) is cached forever via TokenMetaCache and never refetched.
 *
 * Four tiers, set by [classify] from runtime signals:
 *
 *   ACTIVE     — open position OR fresh score ≥ 70 OR fresh<60s
 *                → refresh every cycle (~6 s)
 *   WATCHLIST  — present in registry, not active
 *                → refresh every 30 s
 *   COLD       — in cache but no activity ≥ 5 min
 *                → refresh every 5 min
 *   DORMANT    — no activity ≥ 30 min
 *                → never auto-refresh; let watchlist eviction reap it
 *
 * MEME-TRADER SAFETY:
 *   - ACTIVE tier covers everything the meme trader cares about (open
 *     bags + hot candidates + fresh launches in the first minute).
 *   - The freshness check uses `firstSeenMs < 60s` so newly-discovered
 *     pump.fun launches ALWAYS refresh every cycle even before they
 *     accumulate a score.
 *   - [forceRefresh] lets any caller bypass the policy when a fresh
 *     read is mandatory (e.g. pre-buy quote, post-sell reconciliation).
 *
 * SELF-CONTAINED. No persistence. In-memory map of last-refresh
 * timestamps per (mint, field-class) keyed by canonical mint.
 */
object TokenRefreshPolicy {

    // ─── Tier classification ─────────────────────────────────────────────
    enum class Tier { ACTIVE, WATCHLIST, COLD, DORMANT }

    // ─── Per-tier refresh intervals ──────────────────────────────────────
    private val intervalMs = mapOf(
        Tier.ACTIVE    to 5_000L,        // every cycle (~6s); slight buffer
        Tier.WATCHLIST to 30_000L,       // 30s
        Tier.COLD      to 5 * 60_000L,   // 5 min
        Tier.DORMANT   to Long.MAX_VALUE // never auto-refresh
    )

    // ─── Thresholds for tier classification ──────────────────────────────
    private const val ACTIVE_FRESHNESS_MS = 60_000L         // first 60s = always ACTIVE
    private const val ACTIVE_SCORE_FLOOR = 70.0             // score>=70 = ACTIVE
    private const val COLD_AFTER_MS = 5 * 60_000L           // no activity 5min = COLD
    private const val DORMANT_AFTER_MS = 30 * 60_000L       // no activity 30min = DORMANT

    private val lastRefreshAtMs = ConcurrentHashMap<String, Long>()

    private val totalAllow = AtomicLong(0L)
    private val totalSkip = AtomicLong(0L)
    private val byTierAllow = ConcurrentHashMap<Tier, AtomicLong>()
    private val byTierSkip = ConcurrentHashMap<Tier, AtomicLong>()

    /**
     * Classify a mint into a refresh tier based on its runtime signals.
     *
     * @param firstSeenMs when the mint first entered the registry
     * @param lastActivityMs latest score / price-change / trade-event timestamp
     * @param score most recent V3 score; 0 if unscored
     * @param hasOpenPosition true if the bot holds a bag in this mint
     */
    fun classify(
        firstSeenMs: Long,
        lastActivityMs: Long,
        score: Double,
        hasOpenPosition: Boolean,
    ): Tier {
        val now = System.currentTimeMillis()
        // Open positions are ALWAYS active — meme trader must see exits.
        if (hasOpenPosition) return Tier.ACTIVE
        // First minute since intake → always active. Meme trader edge
        // comes from being first on pump.fun launches, so the freshness
        // window protects every newly-discovered mint.
        if (firstSeenMs > 0L && (now - firstSeenMs) < ACTIVE_FRESHNESS_MS) return Tier.ACTIVE
        if (score >= ACTIVE_SCORE_FLOOR) return Tier.ACTIVE
        val sinceActivity = if (lastActivityMs > 0L) now - lastActivityMs else Long.MAX_VALUE
        return when {
            sinceActivity > DORMANT_AFTER_MS -> Tier.DORMANT
            sinceActivity > COLD_AFTER_MS    -> Tier.COLD
            else                              -> Tier.WATCHLIST
        }
    }

    /**
     * The single gate every dynamic-data fetcher should consult before
     * issuing a refresh call. Returns true to proceed (and stamps the
     * mint's last-refresh timestamp), false to skip (no network call).
     *
     * Caller pattern:
     *   if (TokenRefreshPolicy.shouldRefreshDynamic(mint, tier)) {
     *       doNetworkFetch(...)
     *   }
     */
    fun shouldRefreshDynamic(mint: String, tier: Tier): Boolean {
        val interval = intervalMs[tier] ?: 30_000L
        if (interval == Long.MAX_VALUE) {
            byTierSkip.getOrPut(tier) { AtomicLong(0L) }.incrementAndGet()
            totalSkip.incrementAndGet()
            return false
        }
        val now = System.currentTimeMillis()
        val last = lastRefreshAtMs[mint] ?: 0L
        val due = (now - last) >= interval
        if (due) {
            lastRefreshAtMs[mint] = now
            byTierAllow.getOrPut(tier) { AtomicLong(0L) }.incrementAndGet()
            totalAllow.incrementAndGet()
        } else {
            byTierSkip.getOrPut(tier) { AtomicLong(0L) }.incrementAndGet()
            totalSkip.incrementAndGet()
        }
        return due
    }

    /**
     * Bypass for callers that MUST get a fresh read regardless of policy
     * (pre-buy quote validation, exit slippage projection, etc.). Always
     * stamps the refresh timestamp so the policy doesn't immediately
     * green-light another call.
     */
    fun forceRefresh(mint: String) {
        lastRefreshAtMs[mint] = System.currentTimeMillis()
    }

    /** Convenience overload: classify + gate in one call. */
    fun shouldRefreshDynamic(
        mint: String,
        firstSeenMs: Long,
        lastActivityMs: Long,
        score: Double,
        hasOpenPosition: Boolean,
    ): Boolean = shouldRefreshDynamic(
        mint = mint,
        tier = classify(firstSeenMs, lastActivityMs, score, hasOpenPosition),
    )

    /** Drop refresh state for an evicted mint. */
    fun forget(mint: String) {
        lastRefreshAtMs.remove(mint)
    }

    data class Snapshot(
        val totalAllow: Long,
        val totalSkip: Long,
        val byTier: Map<Tier, Pair<Long, Long>>,
    ) {
        val skipRatePct: Double get() {
            val total = totalAllow + totalSkip
            return if (total == 0L) 0.0 else (totalSkip.toDouble() / total) * 100.0
        }
    }

    fun snapshot(): Snapshot {
        val byTier = Tier.values().associateWith { tier ->
            (byTierAllow[tier]?.get() ?: 0L) to (byTierSkip[tier]?.get() ?: 0L)
        }
        return Snapshot(totalAllow.get(), totalSkip.get(), byTier)
    }
}

/**
 * V5.0.4172 — WALLET ACCOUNT SNAPSHOT CACHE.
 *
 * Operator: Helius RPC `getTokenAccountsByOwner` fires every cycle
 * (~6s) and returns the entire wallet's SPL holdings. That's a
 * 5–100 KB payload, every cycle, refetching the same state we just
 * fetched. Across a day that's ~14,400 calls × ~30 KB avg = ~430 MB
 * burned re-reading state that almost never changes mid-cycle.
 *
 * Cache TTL = 10s. Bust on confirmed trade. Meme-trader safe:
 *   - Pre-buy gate uses [snapshot] which returns stale-OK if < 10s.
 *   - Pre-sell gate calls [bustNow] after any sell broadcast, so the
 *     next read forces fresh fetch.
 *   - WS trade-event hooks call [bustNow] so deposits/withdrawals
 *     don't lag.
 */
object WalletAccountCache {
    private const val DEFAULT_TTL_MS = 10_000L

    @Volatile private var cachedAtMs: Long = 0L
    @Volatile private var cachedSnapshotJson: String? = null

    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)
    private val busts = AtomicLong(0L)

    /**
     * Returns cached snapshot if within TTL, null otherwise.
     * Callers fetch from RPC on null and call [put] with the result.
     */
    fun snapshot(ttlMs: Long = DEFAULT_TTL_MS): String? {
        val now = System.currentTimeMillis()
        val ageMs = now - cachedAtMs
        val snap = cachedSnapshotJson
        return if (snap != null && ageMs < ttlMs) {
            hits.incrementAndGet()
            snap
        } else {
            misses.incrementAndGet()
            null
        }
    }

    /** Store a fresh snapshot. */
    fun put(json: String) {
        cachedSnapshotJson = json
        cachedAtMs = System.currentTimeMillis()
    }

    /** Force-invalidate. Called by sell/buy post-broadcast paths. */
    fun bustNow() {
        cachedAtMs = 0L
        cachedSnapshotJson = null
        busts.incrementAndGet()
    }

    data class Stats(val hits: Long, val misses: Long, val busts: Long) {
        val hitRatePct: Double
            get() {
                val total = hits + misses
                return if (total == 0L) 0.0 else (hits.toDouble() / total) * 100.0
            }
    }

    fun stats(): Stats = Stats(hits.get(), misses.get(), busts.get())
}
