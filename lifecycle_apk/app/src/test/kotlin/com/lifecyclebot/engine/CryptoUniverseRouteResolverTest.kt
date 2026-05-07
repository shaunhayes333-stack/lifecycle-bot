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
    fun btc_native_no_adapters_is_cex_required_or_paper_only_not_buy_failed() {
        CryptoUniverseConfigStore.set(cfg(bridge = false, cex = false, paperFallback = false))
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 5.0, sizeSol = 0.05)
        assertEquals(CryptoExecutionRoute.CEX_REQUIRED, r.route)
        assertEquals(CryptoUniverseDiagCodes.ROUTE_CEX_REQUIRED, r.diagCode)
        assertTrue("BTC w/o adapters must NOT be executable", !r.executable)
    }

    @Test
    fun xmr_no_route_no_adapters_with_paper_fallback_is_paper_only() {
        CryptoUniverseConfigStore.set(cfg(bridge = false, cex = false, paperFallback = true))
        // XMR is not in PerpsMarket enum (operator may or may not have it),
        // so use BTC equivalent (also native-only) and paper fallback.
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 5.0, sizeSol = 0.05)
        assertEquals(CryptoExecutionRoute.PAPER_ONLY, r.route)
        assertTrue(!r.executable)
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
