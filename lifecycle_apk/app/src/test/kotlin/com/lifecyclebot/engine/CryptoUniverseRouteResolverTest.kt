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
 * V5.9.665 — restored route-resolver contract (post-revert of 758ecca26).
 *
 * The resolver behavior:
 *   1. Symbol has a registered Solana SPL mint → JUPITER_ROUTABLE,
 *      executable iff cryptoUniverseLiveEnabled.
 *   2. Symbol has no SPL mint:
 *      - If bridge adapter enabled AND configured → BRIDGE_REQUIRED, executable.
 *      - Else if CEX adapter enabled AND configured → CEX_REQUIRED, executable.
 *      - Else → PAPER_ONLY, NOT executable.
 *   3. Insufficient SOL on wallet → INSUFFICIENT_SOL, NOT executable.
 *
 * The previous "USDC-collateral symbol exposure without target mint"
 * regression (commit 758ecca26) was reverted because it caused live
 * trades to silently end at USDC instead of bridging through to the
 * intended target asset.
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
    fun btc_resolves_to_jupiter_when_wrapped_mint_registered_else_paper_only() {
        // BTC may or may not have a wrapped SPL mint registered in
        // CryptoWrappedAssetMapper depending on registry state. Either
        // outcome is acceptable; what matters is we never silently
        // route a non-Solana asset to USDC-collateral exposure.
        CryptoUniverseConfigStore.set(cfg(bridge = false, cex = false, paperFallback = true))
        val r = CryptoUniverseRouteResolver.resolve(PerpsMarket.BTC, walletSolBalance = 5.0, sizeSol = 0.05)
        assertTrue(
            "BTC must route to JUPITER_ROUTABLE (wrapped) or PAPER_ONLY (no SPL) — got ${r.route}",
            r.route == CryptoExecutionRoute.JUPITER_ROUTABLE ||
            r.route == CryptoExecutionRoute.PAPER_ONLY
        )
        if (r.route == CryptoExecutionRoute.PAPER_ONLY) {
            assertTrue("BTC PAPER_ONLY must NOT be executable", !r.executable)
        }
    }

    @Test
    fun btc_not_executable_when_live_disabled() {
        // With cryptoUniverseLiveEnabled=false, even a Jupiter-routable
        // BTC must report executable=false. Resolver still selects the
        // route so audit logs show the resolution decision.
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
