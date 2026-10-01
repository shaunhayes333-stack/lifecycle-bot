package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7674DynamicCryptoLeadLagCausalityTest {
    @Test fun dynamicCryptoUsesSymbolIdentityAndIntervalPriceSampling() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val block = s.substringAfter("val leadLagSymbol7431 = refreshed.symbol.trim().uppercase()")
            .substringBefore("CrossMarketRegimeAI.updateMarketState(tok.mint")
        assertTrue(block.contains("CrossAssetLeadLagAI.recordPrice7441(leadLagSymbol7431, price)"))
        assertFalse(block.contains("CrossAssetLeadLagAI.recordReturn(leadLagSymbol7431, change)"))
        assertTrue(block.contains("CROSS_ASSET_DYNAMIC_INTERVAL_PRICE_FEED_7674"))
    }

    @Test fun hardcodedAndDynamicCryptoShareSameLeadLagChronology() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(s.contains("CrossAssetLeadLagAI.recordPrice7441(market.symbol, data.price)"))
        assertTrue(s.contains("CrossAssetLeadLagAI.recordPrice7441(leadLagSymbol7431, price)"))
    }
}
