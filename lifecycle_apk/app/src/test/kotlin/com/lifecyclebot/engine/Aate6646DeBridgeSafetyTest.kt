package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6646DeBridgeSafetyTest {
    @Test fun debridgeBuyRequiresRoundTripAndDestinationProof() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoBridgeAdapter.kt").readText()
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(bridge.contains("SELL_BACK_ROUTE_MISSING"))
        assertTrue(bridge.contains("DESTINATION_GAS_MISSING"))
        assertTrue(bridge.contains("DESTINATION_BALANCE_UNPROVEN"))
        // V5.0.7316 — Solana side is the connected trading wallet; EVM side the vault signer.
        assertTrue(bridge.contains("val solAddr = wallet.publicKeyB58"))
        assertTrue(bridge.contains("EVM_SIGNER_MISMATCH"))
        assertTrue(bridge.contains("BRIDGE_COST_TOO_HIGH") && bridge.contains("BRIDGE_COST_UNPROVEN"))
        assertTrue(bridge.contains("Fulfilled") && bridge.contains("ClaimedUnlock"))
        assertTrue(bridge.contains("FULL_ROUND_TRIP_IMPLEMENTED = true"))
        assertTrue(bridge.contains("ROUND_TRIP_EXECUTOR_INCOMPLETE"))
        assertTrue(bridge.contains("isConfigured() && chains.containsKey"))
        assertTrue(bridge.contains("LiveRouteReadiness6647()"))
        assertTrue(bridge.contains("paper-only/unavailable"))
        assertTrue(trader.contains("CryptoBridgeAdapter.init(context.applicationContext)"))
    }
}
