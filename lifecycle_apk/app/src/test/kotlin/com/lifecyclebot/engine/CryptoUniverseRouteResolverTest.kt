package com.lifecyclebot.engine

import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.crypto.CryptoExecutionRoute
import com.lifecyclebot.perps.crypto.CryptoUniverseConfig
import com.lifecyclebot.perps.crypto.CryptoUniverseConfigStore
import com.lifecyclebot.perps.crypto.CryptoUniverseDiagCodes
import com.lifecyclebot.perps.crypto.CryptoUniverseRouteResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.495z31 — Acceptance tests for the CryptoUniverse route
 * resolver. Mirrors operator-brief items A–J.
 */
class CryptoUniverseRouteResolverTest {

    private fun cfg(
        bridge: Boolean = false,
        cex: Boolean = false,
        paperFallback: Boolean = true,
    ) = CryptoUniverseConfig(
        cryptoUniverseAllowBridgeAdapters = bridge,
        cryptoUniverseAllowCexAdapters = cex,
        cryptoUniversePaperOnlyWhenNoExecutor = paperFallback,
    )

    @Test
    fun btc_resolves_to_jupiter_or_bridged_when_live_enabled() {
        // V5.9.495 operator commit (Allow Crypto Universe USDC-collateral
        // symbol exposure without target mint): missing-SPL is no longer a
        // live-trade blocker; symbol routes via USDC collateral. With
        // cryptoUniverseLiveEnabled = true (default), BTC must resolve to
        // either JUPITER_ROUTABLE (if a wrapped mint exists) or
        // BRIDGED_WRAPPED_ASSET (symbol-only) and be executable.
        CryptoUniverseConfigStore.set(cfg(bridge = false, cex = false, paperFallback = false))
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 5.0, sizeSol = 0.05)
        assertTrue(
            "BTC must route via Jupiter or USDC-bridge, got ${r.route}",
            r.route == CryptoExecutionRoute.JUPITER_ROUTABLE ||
            r.route == CryptoExecutionRoute.BRIDGED_WRAPPED_ASSET
        )
        assertTrue("BTC must be executable when live is enabled", r.executable)
    }

    @Test
    fun btc_not_executable_when_live_disabled() {
        // Same path as above but with cryptoUniverseLiveEnabled=false → the
        // resolver still selects the route (so the operator can audit the
        // resolution decision) but executable must be false.
        CryptoUniverseConfigStore.set(CryptoUniverseConfig(
            cryptoUniverseAllowBridgeAdapters = false,
            cryptoUniverseAllowCexAdapters = false,
            cryptoUniversePaperOnlyWhenNoExecutor = true,
            cryptoUniverseLiveEnabled = false,
        ))
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 5.0, sizeSol = 0.05)
        assertTrue(
            "BTC must NOT be executable with live disabled, got route=${r.route} executable=${r.executable}",
            !r.executable
        )
    }

    @Test
    fun insufficient_sol_returns_insufficient_sol_route() {
        CryptoUniverseConfigStore.set(cfg())
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 0.0001, sizeSol = 0.0001)
        assertEquals(CryptoExecutionRoute.INSUFFICIENT_SOL, r.route)
        assertEquals(CryptoUniverseDiagCodes.ROUTE_INSUFFICIENT_SOL, r.diagCode)
        assertTrue(!r.executable)
    }
}
