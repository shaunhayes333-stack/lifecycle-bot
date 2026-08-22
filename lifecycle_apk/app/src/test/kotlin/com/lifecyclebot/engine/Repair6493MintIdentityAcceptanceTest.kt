package com.lifecyclebot.engine

import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.WatchlistEngine
import com.lifecyclebot.perps.crypto.CryptoExecutionRoute
import com.lifecyclebot.perps.crypto.CryptoUniverseRouteResolver
import org.junit.Assert.*
import org.junit.Test

class Repair6493MintIdentityAcceptanceTest {

    @Test fun coingecko_identity_never_falls_back_to_same_ticker_mint() {
        val r = CryptoUniverseRouteResolver.resolve(
            market = PerpsMarket.DYN,
            walletSolBalance = 5.0,
            sizeSol = 0.05,
            assetSymbol6493 = "SYRUP",
            targetMint6493 = "cg:maple-finance",
        )
        assertNull(r.mint)
        assertFalse(r.executable)
        assertEquals(CryptoExecutionRoute.PAPER_ONLY, r.route)
        assertTrue(r.humanMessage.contains("no symbol fallback allowed"))
    }

    @Test fun explicit_mint_is_identity_even_when_display_symbol_is_unrelated() {
        val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val r = CryptoUniverseRouteResolver.resolve(
            market = PerpsMarket.DYN,
            walletSolBalance = 5.0,
            sizeSol = 0.05,
            assetSymbol6493 = "DISPLAY_ONLY",
            targetMint6493 = mint,
        )
        assertEquals(mint, r.mint)
        assertEquals("DISPLAY_ONLY", r.symbol)
        assertEquals(CryptoExecutionRoute.JUPITER_ROUTABLE, r.route)
    }

    @Test fun crypto_watchlist_item_carries_canonical_asset_id() {
        val mint = "So11111111111111111111111111111111111111112"
        val row = WatchlistEngine.WatchlistItem(symbol = "SAME", assetId = mint)
        assertEquals(mint, row.assetId)
        assertEquals("SAME", row.symbol)
    }
}
