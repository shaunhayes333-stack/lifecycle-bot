package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.ExecutableVenue6411
import com.lifecyclebot.engine.truth.RouteResolver6411
import org.junit.Assert.*
import org.junit.Test

class Aate8237RouteContinuityTest {
    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")

    @Test fun scannerBucketsCannotPermanentlyMigrateAnySpecialistsActiveCurve() {
        for (lane in lanes) {
            val ts = TokenState("route8237${lane}pump", symbol = "ROUTE", source = "PUMP_FUN_GRADUATE,SCANNER_DIRECT_RAYDIUM_NEW_POOL")
            ts.position.tradingMode = lane
            ts.lastPrice = 0.001
            ts.lastPriceUpdate = System.currentTimeMillis()
            ts.lastPriceDex = "PUMP_FUN"
            ts.lastPriceSource = "PUMP_PORTAL_TRADE_WS_7278"
            ts.lastLiquidityUsd = 9_000.0
            assertFalse(lane, RouteTruthHydrator.markGraduationIfDetected6852(ts))
            assertFalse(lane, ts.tokenMap.migratedOrGraduated)
            val map = TokenMapAuthority.ensureDiscoveryTokenMap(ts)
            assertEquals(lane, "PUMPFUN_BONDING_CURVE_EXECUTABLE", map.routeStatus)
            assertTrue(lane, TokenMapAuthority.executableForLiveBuy(ts))
            assertEquals("discovery cannot fabricate a quote for $lane", 0.0, map.expectedOutAmount, 0.0)
            assertFalse(lane, map.jupiterQuoteOk)
        }
    }

    @Test fun realMigrationStaysBindingAcrossAliasesAndMintSuffixRouting() {
        val mint = "event8237ConfirmedMigrationpump"
        val original = TokenState(mint, source = "PUMP_PORTAL_WS")
        assertTrue(RouteTruthHydrator.markGraduatedByEvent7744(original))
        val alias = TokenState(mint, source = "PUMP_FUN_NEW")
        TokenMapAuthority.ensureDiscoveryTokenMap(alias)
        assertTrue(alias.tokenMap.migratedOrGraduated)
        assertFalse(alias.tokenMap.pumpFunExecutable)
        assertFalse(TokenMapAuthority.executableForLiveBuy(alias))
        assertEquals(ExecutableVenue6411.PUMPSWAP, RouteResolver6411.resolveVenue(mint, alias.source))
    }

    @Test fun actualAmmVenueTransitionsButTextContainingMigrationDoesNot() {
        val ts = TokenState("amm8237Migrationpump", source = "PUMP_FUN_NEW")
        ts.lastPriceDex = "PUMPSWAP"
        assertTrue(RouteTruthHydrator.markGraduationIfDetected6852(ts))
        assertTrue(ts.tokenMap.migratedOrGraduated)
        assertFalse(ts.tokenMap.pumpFunExecutable)
        assertFalse(RouteTruthHydrator.observedAmmVenue8237("SCANNER_DIRECT_RAYDIUM_NEW_POOL"))
        assertFalse(RouteTruthHydrator.explicitMigrationSource8237("PUMP_FUN_GRADUATE,NO_MIGRATION"))
        assertTrue(RouteTruthHydrator.explicitMigrationSource8237("SCANNER_DIRECT,PUMP_PORTAL_MIGRATE"))
        assertTrue(RouteTruthHydrator.observedAmmVenue8237("raydium-cpmm"))
    }

    @Test fun observedVenueRebuildsAliasWitnessButAnOldBooleanCannotSupplyIt() {
        val observed = TokenState("route8237ExistingAmmFlagpump", source = "PUMP_FUN_NEW")
        observed.tokenMap.migratedOrGraduated = true
        observed.lastPriceDex = "PUMPSWAP"
        assertFalse("the migration was already counted", RouteTruthHydrator.markGraduationIfDetected6852(observed))
        assertTrue(com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(observed.mint))
        assertEquals(ExecutableVenue6411.PUMPSWAP, RouteResolver6411.resolveVenue(observed.mint, observed.source))
        val unproved = TokenState("route8237BareOldFlagpump", source = "PUMP_FUN_GRADUATE")
        unproved.tokenMap.migratedOrGraduated = true
        assertFalse(RouteTruthHydrator.markGraduationIfDetected6852(unproved))
        assertFalse(com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(unproved.mint))
    }

    @Test fun executorRouteDeferralRequestsRealPairAndAuthorityInputs() {
        val request = DataDeferFetch8180.inputsFor8180(listOf("token_map:UNRESOLVED", "route:UNKNOWN"))
        assertTrue(request.contains("liq:REFRESH"))
        assertTrue(DataDeferFetch8180.missingExecutableProof8236(request))
        assertTrue(request.contains("token_map:UNRESOLVED"))
        assertEquals(1, request.count { it == "liq:REFRESH" })
    }
}
