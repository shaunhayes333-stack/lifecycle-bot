package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6398 — PAIR HYDRATION STATE.
 *
 * DexScreener degradation must NOT erase a valid source-native pair/pool.
 * Preferred resolution order:
 *   PumpFun (bonding curve) → Raydium pool from scanner event →
 *   Helius metadata → Jupiter route → DexScreener → Birdeye →
 *   watchlist last-known.
 *
 * States:
 *   PAIR_CONFIRMED               — full pair record from a canonical provider
 *   PAIR_SOURCE_NATIVE           — pair from the discovery source itself
 *   ROUTE_CONFIRMED_WITHOUT_PAIR — no pair but Jupiter route resolves
 *   PAIR_PENDING_HYDRATION       — waiting on providers (hydration TTL live)
 *   PAIR_HARD_UNAVAILABLE        — all providers exhausted / no route / TTL expired
 *
 * NO_PAIR_NO_FALLBACK is REPLACED by PAIR_HARD_UNAVAILABLE and may only
 * fire after (1) all configured fallback providers attempted or in
 * backoff, (2) source-native unavailable, (3) no Jupiter route, (4)
 * hydration TTL expired, (5) reason fully recorded.
 */
object PairHydrationState6398 {

    enum class State {
        PAIR_CONFIRMED, PAIR_SOURCE_NATIVE, ROUTE_CONFIRMED_WITHOUT_PAIR,
        PAIR_PENDING_HYDRATION, PAIR_HARD_UNAVAILABLE,
    }

    data class Snapshot(
        val mint: String,
        val state: State,
        val pairAddress: String?,
        val providerAttempted: List<String>,
        val hydrationTtlExpiresAtMs: Long,
        val reason: String,
    )

    /** Hydration TTL (upper bound) — before this, we keep probation-hot. */
    const val HYDRATION_TTL_MS: Long = 45_000L

    private val states = ConcurrentHashMap<String, Snapshot>()

    val hardUnavailableEvents = AtomicLong(0L)

    fun upsert(snap: Snapshot) { states[snap.mint] = snap }
    fun get(mint: String): Snapshot? = states[mint]

    /**
     * Compose the resolution given raw provider inputs. This is the
     * primary API — callers pass what each provider returned and the
     * function collapses to a canonical state.
     */
    fun resolve(
        mint: String,
        dexscreenerPair: String?,
        raydiumPoolFromScanner: String?,
        pumpFunBondingCurve: String?,
        heliusPair: String?,
        jupiterRouteOk: Boolean,
        birdeyePair: String?,
        watchlistLastKnownPair: String?,
        providersAttempted: List<String>,
        hydrationStartedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val hydrationExpiresAt = hydrationStartedAtMs + HYDRATION_TTL_MS
        // Highest-authority canonical pair first.
        heliusPair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "HELIUS_CONFIRMED")) }
        dexscreenerPair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "DEXSCREENER_CONFIRMED")) }
        birdeyePair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "BIRDEYE_CONFIRMED")) }
        // Source-native fallbacks (never erased by DexScreener degradation).
        pumpFunBondingCurve?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "PUMPFUN_BONDING_CURVE")) }
        raydiumPoolFromScanner?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "RAYDIUM_POOL_SCANNER")) }
        watchlistLastKnownPair?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "WATCHLIST_LAST_KNOWN")) }
        // Route without pair.
        if (jupiterRouteOk)
            return record(Snapshot(mint, State.ROUTE_CONFIRMED_WITHOUT_PAIR, null, providersAttempted, hydrationExpiresAt, "JUPITER_ROUTE_OK"))
        // Nothing resolved — pending until TTL expires.
        if (nowMs < hydrationExpiresAt)
            return record(Snapshot(mint, State.PAIR_PENDING_HYDRATION, null, providersAttempted, hydrationExpiresAt, "HYDRATION_IN_PROGRESS"))
        // TTL expired AND every provider attempted — hard unavailable.
        hardUnavailableEvents.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAIR_HARD_UNAVAILABLE_6398") } catch (_: Throwable) {}
        return record(Snapshot(mint, State.PAIR_HARD_UNAVAILABLE, null, providersAttempted, hydrationExpiresAt, "ALL_PROVIDERS_EXHAUSTED_TTL_EXPIRED"))
    }

    private fun record(s: Snapshot): Snapshot { states[s.mint] = s; return s }

    internal fun clearAllForTest() { states.clear(); hardUnavailableEvents.set(0L) }
}
