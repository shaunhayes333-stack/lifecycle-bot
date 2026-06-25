package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.4151 — ROUTE TRUTH HYDRATOR.
 *
 * Route truth is execution metadata, not Birdeye-only token metadata. This
 * hydrates the canonical TokenMap from already-present scanner/source payloads
 * before FDG spends a decision on TOKEN_MAP_INCOMPLETE.
 *
 * No network calls here: hot-path safe. Provider order:
 * Pump/PumpPortal payload → pool/pair scanner payload → Dex pair cache-ish
 * fields → Jupiter quote flags already in TokenMap → Helius/account/proof-like
 * fields already on TokenState → Birdeye bypass telemetry.
 */
object RouteTruthHydrator {
    data class Result(
        val hasRoute: Boolean,
        val source: String,
        val reason: String,
    )

    fun hydrate(ts: TokenState): Result {
        val now = System.currentTimeMillis()
        val tm = ts.tokenMap
        if (tm.canonicalTargetMint.isBlank()) tm.canonicalTargetMint = ts.mint
        if (tm.symbol.isBlank()) tm.symbol = ts.symbol
        if (tm.name.isBlank()) tm.name = ts.name
        if (tm.sourceScanner.isBlank()) tm.sourceScanner = ts.source
        if (tm.updatedAtMs <= 0L) tm.updatedAtMs = now

        fun inc(label: String) { try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }
        fun hit(src: String, reason: String): Result {
            tm.routeStatus = "ROUTE_TRUTH_$src"
            tm.hydrationComplete = true
            tm.hydrationConfidence = maxOf(tm.hydrationConfidence, 0.75)
            tm.updatedAtMs = now
            tm.providerTimestamps[src] = now
            inc("ROUTE_TRUTH_HIT")
            inc("ROUTE_TRUTH_FROM_$src")
            return Result(true, src, reason)
        }

        // 1. PumpPortal / Pump.fun bonding route from source/TokenMap.
        val pumpSource = ts.source.contains("PUMP", ignoreCase = true) || ts.lastPriceSource.contains("PUMP", ignoreCase = true)
        if (tm.pumpFunExecutable && (tm.pumpFunBondingCurveAddress.isNotBlank() || pumpSource) && !tm.migratedOrGraduated) {
            if (tm.pumpFunBondingCurveAddress.isBlank()) tm.pumpFunBondingCurveAddress = ts.pairAddress.ifBlank { ts.lastPricePoolAddr }
            return hit("PUMP", "pumpFunExecutable=${tm.pumpFunExecutable} bonding=${tm.pumpFunBondingCurveAddress.take(12)}")
        }

        // 2. Pool registry/direct scanner payload.
        val pool = tm.poolAddress.ifBlank { ts.lastPricePoolAddr.ifBlank { if (ts.lastPriceDex.isNotBlank()) ts.pairAddress else "" } }
        if (pool.isNotBlank()) {
            tm.poolAddress = pool
            if (tm.pairAddress.isBlank() && ts.pairAddress.isNotBlank()) tm.pairAddress = ts.pairAddress
            tm.dexRouteOk = true
            if (tm.dexId == "UNKNOWN" && ts.lastPriceDex.isNotBlank()) tm.dexId = ts.lastPriceDex
            return hit("POOL", "pool=${pool.take(12)} dex=${tm.dexId}")
        }

        // 3. DexScreener pair cache / TokenState pair.
        val pair = tm.pairAddress.ifBlank { ts.pairAddress }
        if (pair.isNotBlank()) {
            tm.pairAddress = pair
            tm.dexRouteOk = true
            if (tm.poolAddress.isBlank()) tm.poolAddress = ts.lastPricePoolAddr
            return hit("DEX", "pair=${pair.take(12)}")
        }

        // 4. Jupiter route availability already known from prior checks.
        if (tm.jupiterQuoteOk || tm.expectedOutAmount > 0.0 || tm.dexRouteOk) {
            return hit("JUPITER", "jupiter=${tm.jupiterQuoteOk} dex=${tm.dexRouteOk} out=${tm.expectedOutAmount}")
        }

        // 5. Helius/account proof equivalent already present: real price+liq+provider.
        if (ts.lastPrice > 0.0 && ts.lastLiquidityUsd > 0.0 && (ts.lastPriceSource.isNotBlank() || ts.source.isNotBlank())) {
            tm.priceUsd = ts.lastPrice
            tm.liquidityUsd = ts.lastLiquidityUsd
            tm.marketCap = ts.lastMcap.takeIf { it > 0.0 }
            tm.routeStatus = "ROUTE_TRUTH_PROVIDER_PRICE_LIQ_PENDING_EXEC_ROUTE"
            tm.hydrationConfidence = maxOf(tm.hydrationConfidence, 0.50)
            tm.updatedAtMs = now
            inc("ROUTE_TRUTH_HIT")
            inc("ROUTE_TRUTH_FROM_HELIUS")
            return Result(true, "HELIUS", "providerPriceLiq source=${ts.lastPriceSource.ifBlank { ts.source }}")
        }

        // 6. Birdeye is explicitly not route authority. Surface bypass during CU exhaustion.
        inc("ROUTE_TRUTH_BIRDEYE_BYPASSED_CU")
        inc("ROUTE_TRUTH_MISS")
        tm.hydrationComplete = false
        tm.hydrationFailureReasons.add("ROUTE_TRUTH_MISS_NO_PAIR_POOL_PUMP_JUPITER_PROVIDER")
        tm.routeStatus = "WATCH_PROBATION_ROUTE_UNKNOWN"
        tm.updatedAtMs = now
        return Result(false, "MISS", "no pair/pool/pump/jupiter/provider route truth")
    }
}
