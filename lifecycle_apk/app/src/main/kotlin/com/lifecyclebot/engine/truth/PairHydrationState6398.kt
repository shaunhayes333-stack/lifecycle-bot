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

    /**
     * 8196: a timeout proves age, not an exhausted route. The former BotService
     * no-pair path passed DEXSCREENER/BIRDEYE/ORACLE as "attempted" literals
     * before the asynchronous hydrators had actually returned anything.
     * Require distinct real metadata and execution-route attempts before
     * terminalising a candidate as HARD_UNAVAILABLE. A temporary provider
     * outage or unattempted route remains PENDING, never an invented no-route.
     */
    fun fallbackExhausted8196(attempted: List<String>): Boolean {
        val sources = attempted.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        val metadata = sources.any { it == "DEXSCREENER" || it == "HELIUS" || it == "BIRDEYE" }
        val route = sources.any { it == "JUPITER" || it == "ORACLE" }
        return sources.size >= 3 && metadata && route
    }

    /** Build a real no-pair witness from a token's own source and dated canonical map. */
    fun resolveFromToken8196(
        ts: com.lifecyclebot.data.TokenState,
        sourceNativePump: Boolean,
        hydrationStartedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val map = ts.tokenMap
        val recent = try {
            map.providerTimestamps.entries.filter { nowMs - it.value in 0L..600_000L }
                .map { it.key.uppercase() }
        } catch (_: Throwable) { emptyList() }
        // The caller genuinely asked DexScreener and got no pair; every other
        // producer is counted only when its canonical map says it answered.
        val attempted = (listOf("DEXSCREENER") + recent).distinct()
        val candidatePool = map.poolAddress.ifBlank { map.pairAddress }
        // A real, recently hydrated pool remains available when the scanner's own
        // DexScreener call returns no pair. Do not discard the canonical witness.
        val trustedPool = candidatePool.takeIf { it.isNotBlank() && it != ts.mint && map.hydrationComplete &&
            nowMs - map.updatedAtMs in 0L..300_000L }
        val nativeVenues = listOf("RAYDIUM", "PUMPSWAP", "PUMP_SWAP", "METEORA", "ORCA", "BONKSWAP")
        val priceVenueNative = nativeVenues.any { ts.lastPriceDex.contains(it, true) } ||
            ts.source.contains("RAYDIUM_NEW_POOL", true) || ts.source.contains("METEORA_NEW_POOL", true)
        val sourcePool = ts.lastPricePoolAddr.takeIf { it.isNotBlank() && it != ts.mint &&
            nowMs - ts.lastPriceUpdate in 0L..300_000L && priceVenueNative }
        val mapVenueNative = nativeVenues.any { map.venue.contains(it, true) || map.dexId.contains(it, true) }
        val nativePool = sourcePool ?: trustedPool?.takeIf { mapVenueNative }
        val isRaydium = ts.lastPriceDex.contains("RAYDIUM", true) ||
            ts.source.contains("RAYDIUM_NEW_POOL", true) ||
            (sourcePool == null && (map.venue.contains("RAYDIUM", true) || map.dexId.contains("RAYDIUM", true)))
        val raydiumPool = nativePool?.takeIf { isRaydium }
        // An actual bonding-curve PDA, not the mint address, is needed to claim
        // source-native PumpFun route proof. Creation events alone prove birth.
        val curve = map.pumpFunBondingCurveAddress.takeIf { it.isNotBlank() && it != ts.mint &&
            map.pumpFunExecutable && nowMs - map.updatedAtMs in 0L..300_000L &&
            (sourceNativePump || map.venue.contains("PUMP", true) || map.dexId.contains("PUMP", true)) }
        val heliusPool = trustedPool?.takeIf { recent.any { it.contains("HELIUS") } }
        return resolve(
            mint = ts.mint,
            dexscreenerPair = trustedPool?.takeIf { recent.any { it.contains("DEXSCREENER") } },
            raydiumPoolFromScanner = raydiumPool,
            pumpFunBondingCurve = curve,
            heliusPair = heliusPool,
            jupiterRouteOk = map.jupiterQuoteOk && map.expectedOutAmount > 0.0 &&
                nowMs - map.updatedAtMs in 0L..60_000L,
            birdeyePair = trustedPool?.takeIf { recent.any { it.contains("BIRDEYE") } },
            watchlistLastKnownPair = null,
            providersAttempted = attempted,
            hydrationStartedAtMs = hydrationStartedAtMs,
            nowMs = nowMs,
            sourceNativePool8197 = nativePool?.takeIf { !isRaydium },
        )
    }

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
        sourceNativePool8197: String? = null,
    ): Snapshot {
        val hydrationExpiresAt = hydrationStartedAtMs + HYDRATION_TTL_MS
        // Highest-authority canonical pair first.
        heliusPair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "HELIUS_CONFIRMED")) }
        dexscreenerPair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "DEXSCREENER_CONFIRMED")) }
        birdeyePair?.let { return record(Snapshot(mint, State.PAIR_CONFIRMED, it, providersAttempted, hydrationExpiresAt, "BIRDEYE_CONFIRMED")) }
        // Source-native fallbacks (never erased by DexScreener degradation).
        pumpFunBondingCurve?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "PUMPFUN_BONDING_CURVE")) }
        raydiumPoolFromScanner?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "RAYDIUM_POOL_SCANNER")) }
        sourceNativePool8197?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "SOURCE_NATIVE_POOL_8197")) }
        watchlistLastKnownPair?.let { return record(Snapshot(mint, State.PAIR_SOURCE_NATIVE, it, providersAttempted, hydrationExpiresAt, "WATCHLIST_LAST_KNOWN")) }
        // Route without pair.
        if (jupiterRouteOk)
            return record(Snapshot(mint, State.ROUTE_CONFIRMED_WITHOUT_PAIR, null, providersAttempted, hydrationExpiresAt, "JUPITER_ROUTE_OK"))
        // A 45-second timeout without independent fallback attempts is NOT
        // proof that the token has no route. Do not turn source latency into
        // a permanent hard rejection; cold/probation scheduling owns retries.
        if (nowMs < hydrationExpiresAt || !fallbackExhausted8196(providersAttempted))
            return record(Snapshot(mint, State.PAIR_PENDING_HYDRATION, null, providersAttempted, hydrationExpiresAt,
                if (nowMs < hydrationExpiresAt) "HYDRATION_IN_PROGRESS" else "PROVIDER_ATTEMPTS_UNPROVEN_8196"))
        // TTL expired AND real fallback attempts are independently recorded — hard unavailable.
        hardUnavailableEvents.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAIR_HARD_UNAVAILABLE_6398") } catch (_: Throwable) {}
        return record(Snapshot(mint, State.PAIR_HARD_UNAVAILABLE, null, providersAttempted, hydrationExpiresAt, "ALL_PROVIDERS_EXHAUSTED_TTL_EXPIRED"))
    }

    private fun record(s: Snapshot): Snapshot { states[s.mint] = s; return s }

    internal fun clearAllForTest() { states.clear(); hardUnavailableEvents.set(0L) }
}
