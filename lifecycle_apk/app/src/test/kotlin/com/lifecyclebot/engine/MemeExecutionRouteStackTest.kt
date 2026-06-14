package com.lifecyclebot.engine

import com.lifecyclebot.engine.execution.MemeExecutionRouteStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemeExecutionRouteStackTest {

    private fun ctx(side: MemeExecutionRouteStack.Side = MemeExecutionRouteStack.Side.SELL) =
        MemeExecutionRouteStack.ExecutionRouteContext(
            side = side,
            mint = "ExampleMint111111111111111111111111111pump",
            symbol = "EXAMPLE",
            amountIn = 1.0,
            amountInRaw = "1000000",
            slippageBps = 500,
            reason = "STRICT_SL_-10",
            urgency = MemeExecutionRouteStack.Urgency.STOP_LOSS,
            walletSol = 1.0,
            tokenBalanceAuthority = "HOST_TRACKER_TX_PARSE",
            routeIntelligence = MemeExecutionRouteStack.RouteIntelligenceSnapshot(
                birdeyePrice = 0.000001,
                coingeckoContext = "context",
                dexScreenerPair = "raydium_pair",
                pumpPortalSignal = "ws",
                pumpFunBondingSignal = true,
                pumpSwapPoolFound = true,
                raydiumPoolFound = true,
                meteoraPoolFound = true,
                orcaPoolFound = true,
                liquidityDepthUsd = 10_000.0,
                priceConfidence = 80,
                exitDepthUsd = 8_000.0,
                recommendedVenues = listOf("PUMP", "RAYDIUM", "METEORA", "ORCA"),
            ),
            callSite = "test",
        )

    @Test fun provider_order_is_full_stack_not_three_route_ladder_for_buy_and_sell() {
        val expected = listOf(
            "PumpFunDirect", "PumpPortal", "PumpSwapDirect", "RaydiumDirect",
            "MeteoraDirect", "OrcaDirect", "JupiterUltra", "JupiterMetis"
        )
        assertEquals(expected, MemeExecutionRouteStack.providerOrder(ctx(MemeExecutionRouteStack.Side.BUY)).map { it.providerName })
        assertEquals(expected, MemeExecutionRouteStack.providerOrder(ctx(MemeExecutionRouteStack.Side.SELL)).map { it.providerName })
        assertFalse(MemeExecutionRouteStack.coverage(ctx()).oldThreeRouteCollapse())
    }

    @Test fun sender_order_separates_builders_from_landing_paths() {
        assertEquals(
            listOf("standardRpc", "HeliusSender", "Jito"),
            MemeExecutionRouteStack.senderOrder().map { it.senderName },
        )
    }

    @Test fun data_providers_are_route_intelligence_not_execution_providers() {
        val providerNames = MemeExecutionRouteStack.providerOrder(ctx()).map { it.providerName }
        listOf("Birdeye", "CoinGecko", "DexScreener", "PumpPortalWS").forEach {
            assertFalse("$it must not be an execution provider", providerNames.contains(it))
        }
        val intelProviders = ctx().routeIntelligence.providersUsedForIntel()
        assertTrue(intelProviders.contains("Birdeye"))
        assertTrue(intelProviders.contains("CoinGecko"))
        assertTrue(intelProviders.contains("DexScreener"))
        assertTrue(intelProviders.contains("PumpPortalWS"))
    }

    @Test fun direct_providers_become_supported_when_pool_signals_exist() {
        val support = MemeExecutionRouteStack.providerOrder(ctx()).associate { it.providerName to it.supports(ctx()).supported }
        assertTrue(support.getValue("PumpSwapDirect"))
        assertTrue(support.getValue("RaydiumDirect"))
        assertTrue(support.getValue("MeteoraDirect"))
        assertTrue(support.getValue("OrcaDirect"))
    }
}
