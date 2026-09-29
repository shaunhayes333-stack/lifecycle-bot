package com.lifecyclebot.perps

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7444CryptoProvenQuoteExecutionTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun crypto_universe_executes_the_quote_it_already_proved() {
        val cu = src("perps/crypto/CryptoUniverseExecutor.kt")
        assertTrue(cu.contains("prevalidatedQuote7444 = routeQuote"))
        assertTrue(cu.indexOf("val routeQuote =") < cu.indexOf("prevalidatedQuote7444 = routeQuote"))
    }

    @Test fun universal_bridge_accepts_and_reuses_binding_quote() {
        val b = src("engine/UniversalBridgeEngine.kt")
        assertTrue(b.contains("prevalidatedQuote7444: com.lifecyclebot.network.SwapQuote? = null"))
        assertTrue(b.contains("CRYPTO_PROVEN_QUOTE_REUSED_7444"))
        assertTrue(b.contains("proven7444?.inAmount"))
    }

    @Test fun stale_prevalidated_transaction_gets_one_fresh_requote() {
        val b = src("engine/UniversalBridgeEngine.kt")
        assertTrue(b.contains("attempt7444 in 0..1"))
        assertTrue(b.contains("CRYPTO_PROVEN_QUOTE_STALE_REQUOTE_7444"))
        assertTrue(b.contains("supplied7444 = null"))
    }
}
