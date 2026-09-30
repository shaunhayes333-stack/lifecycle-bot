package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7515CryptoDiscoveryRuntimeWiringTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun startup_wires_discovery_to_same_crypto_runtime_plan() {
        val s = src()
        val region = s.substringAfter("val cryptoUniverseOnAtStart = plan6526.cryptoUniverseOn")
            .substringBefore("// V5.9.1405")
        assertTrue(region.contains("CryptoAltTrader.setEnabled(cryptoUniverseOnAtStart)"))
        assertTrue(region.contains("DynamicAltTokenRegistry.startBackgroundDiscovery()"))
        assertTrue(region.contains("DynamicAltTokenRegistry.stopBackgroundDiscovery()"))
        assertTrue(region.contains("CRYPTO_DISCOVERY_RUNTIME_STARTED_7515"))
    }

    @Test fun reapply_wires_discovery_to_plan_crypto_authority() {
        val s = src()
        val region = s.substringAfter("val cryptoUniverseOn = plan.cryptoUniverseOn")
            .substringBefore("EnabledTraderAuthority.publish")
        assertTrue(region.contains("CryptoAltTrader.setEnabled(cryptoUniverseOn)"))
        assertTrue(region.contains("if (cryptoUniverseOn) com.lifecyclebot.perps.DynamicAltTokenRegistry.startBackgroundDiscovery()"))
        assertTrue(region.contains("else com.lifecyclebot.perps.DynamicAltTokenRegistry.stopBackgroundDiscovery()"))
    }

    @Test fun shutdown_stops_discovery_scheduler() {
        val s = src()
        val region = s.substringAfter("com.lifecyclebot.perps.TokenizedStockTrader.stop()")
            .substringBefore("ErrorLogger.info(\"BotService\", \"All Markets traders stopped")
        assertTrue(region.contains("CryptoAltTrader.stop()"))
        assertTrue(region.contains("DynamicAltTokenRegistry.stopBackgroundDiscovery()"))
    }
}
