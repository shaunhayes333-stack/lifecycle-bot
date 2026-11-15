package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8789 — owner: the crypto trader trades whatever it wants, including what the meme side holds or watches. */
class Aate8789CryptoTradesAnythingTest {
    @Test fun pump_coins_enter_the_crypto_universe() {
        assertTrue(DynamicAltTokenRegistry.openToMemeCoins8789)
        assertTrue(DynamicAltTokenRegistry.cryptoMayTrade8789("mGzw6zwvig3GitrCM6sGJXdRWeWz81X2vyzVH3Upump"))
        assertNull("no read pool, no offer", DynamicAltTokenRegistry.ingestMemeWatch8789("mGzw6zwvig3GitrCM6sGJXdRWeWz81X2vyzVH3Upump", "X", 0.0))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
            .contains("DynamicAltTokenRegistry.ingestMemeWatch8789(mint, symbol, initialLiquidityUsd)"))
    }

    @Test fun live_never_doubles_a_coin_another_book_holds() {
        assertFalse("paper: a meme-held coin is tradeable", CryptoAltTrader.heldSkip8789(paper = true, anyBookHolds = true, cryptoHolds = false))
        assertTrue("live: one wallet balance per coin", CryptoAltTrader.heldSkip8789(paper = false, anyBookHolds = true, cryptoHolds = false))
        assertTrue("the desk's own holding is always skipped", CryptoAltTrader.heldSkip8789(paper = true, anyBookHolds = true, cryptoHolds = true))
        assertFalse(CryptoAltTrader.heldSkip8789(paper = false, anyBookHolds = false, cryptoHolds = false))
    }
}
