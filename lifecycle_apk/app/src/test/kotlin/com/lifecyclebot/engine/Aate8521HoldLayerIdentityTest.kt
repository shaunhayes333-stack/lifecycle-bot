package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Aate8521HoldLayerIdentityTest {
    @Test fun everyCanonicalNativeLaneUsesItsExistingFluidFamily() {
        val expected = mapOf("BLUECHIP" to "BLUECHIP", "CASHGEN" to "TREASURY", "QUALITY" to "V3",
            "CYCLIC" to "V3", "SHITCOIN" to "SHITCOIN", "MOONSHOT" to "MOONSHOT",
            "TREASURY" to "TREASURY", "CORE" to "V3", "EXPRESS" to "SHITCOIN",
            "DIP_HUNTER" to "V3", "PROJECT_SNIPER" to "SHITCOIN", "MANIPULATED" to "SHITCOIN")
        assertEquals(12, expected.size)
        expected.forEach { (lane, family) -> assertEquals(lane, family, HoldingLogicLayer.getLayerFromMode(lane)) }
    }

    @Test fun historicalAliasesAndCanonicalEntryOwnersResolveToTheSameFluidPolicy() {
        val aliases = mapOf("BLUE_CHIP" to "BLUECHIP", "blue chip" to "BLUECHIP", "CASH_GENERATION" to "CASHGEN",
            "SHIT_COIN" to "SHITCOIN", "MANIP" to "MANIPULATED", "DIP" to "DIP_HUNTER",
            "SNIPER" to "PROJECT_SNIPER", "PRESALE_SNIPE" to "PROJECT_SNIPER", "SHITCOIN_EXPRESS" to "EXPRESS",
            "MICRO_CAP" to "SHITCOIN", "PUMP_SNIPER" to "EXPRESS", "PUMP_DUMP" to "MANIPULATED")
        aliases.forEach { (alias, lane) -> assertEquals(alias, HoldingLogicLayer.getLayerFromMode(lane), HoldingLogicLayer.getLayerFromMode(alias)) }
        assertEquals("V3", HoldingLogicLayer.getLayerFromMode("UNKNOWN_DESK"))
    }
}
