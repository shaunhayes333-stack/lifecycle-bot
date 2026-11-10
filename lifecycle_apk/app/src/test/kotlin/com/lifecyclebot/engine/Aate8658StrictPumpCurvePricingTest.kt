package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.KeylessMarks8359
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.network.PumpCurveKeys7269
import com.lifecyclebot.network.PumpFunWS
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Non-pump launchpad prices and liquidity must never use the pump.fun virtual-reserve invariant. */
class Aate8658StrictPumpCurvePricingTest {
    private val pump = "11111111111111111111111111111111pump"
    private val bonk = "11111111111111111111111111111111bonk"
    private val key = "11111111111111111111111111111111"
    private val at = System.currentTimeMillis()

    @After fun cleanup() = CurveReserves7951.resetForTest7951()

    @Test fun only_pump_suffix_can_register_curve_identity_or_birth_price() {
        assertTrue(PumpCurveKeys7269.isPumpMint8658(pump))
        assertTrue(PumpCurveKeys7269.isPumpMint8658("  ${pump.uppercase()}  "))
        assertFalse(PumpCurveKeys7269.isPumpMint8658(bonk))
        PumpCurveKeys7269.remember(bonk, key)
        PumpCurveKeys7269.rememberCreate7280(bonk, 1e-7, at)
        assertNull(PumpCurveKeys7269.keyFor(bonk))
        assertNull(PumpCurveKeys7269.canonicalCurveKey7392(bonk))
        assertNull(PumpCurveKeys7269.createPriceSol7280(bonk))
        assertNull(PumpCurveKeys7269.createdAtMs7280(bonk))
        PumpCurveKeys7269.remember(pump, key)
        assertEquals(key, PumpCurveKeys7269.keyFor(pump))
        assertTrue(PumpFunWS.pumpCurveFrame8657("pump", pump))
        assertFalse(PumpFunWS.pumpCurveFrame8657("", bonk))
        assertFalse(PumpFunWS.pumpCurveFrame8657("bonk", pump))
        assertFalse(KeylessMarks8359.curveLike8359(bonk, hasKey = true, graduated = false))
        assertTrue(KeylessMarks8359.curveLike8359(pump, hasKey = false, graduated = false))
    }

    @Test fun non_pump_reserve_writes_and_reads_are_all_closed() {
        CurveReserves7951.noteFrame7951(bonk, 40.0, 700_000_000.0, at)
        CurveReserves7951.noteAccount7951(bonk, 40.0, 700_000_000.0, 10.0, at + 1)
        assertFalse(CurveReserves7951.known7951(bonk))
        assertNull(CurveReserves7951.acceptedObservation8385(bonk))
        assertNull(CurveReserves7951.readingAtMs8173(bonk))
        assertNull(CurveReserves7951.realSolIfActive8046(bonk, at + 5))
        assertNull(CurveReserves7951.accountObservation8522(bonk, at + 5))
        assertNull(CurveReserves7951.activeExitObservation(bonk, at + 5, 30_000))
        assertEquals(0.0, CurveReserves7951.priceSolFor7951(bonk, at + 5, 30_000), 0.0)
        assertEquals(0.0, CurveReserves7951.liquiditySol7951(bonk, at + 5), 0.0)
        CurveReserves7951.noteAccount7951(pump, 40.0, 700_000_000.0, 10.0, at)
        assertTrue(CurveReserves7951.known7951(pump))
        assertEquals(40.0 / 700_000_000.0, CurveReserves7951.priceSolFor7951(pump, at + 1, 30_000), 1e-15)
        assertEquals(10.0, CurveReserves7951.liquiditySol7951(pump, at + 1), 0.0)
    }

    @Test fun source_paths_do_not_reenable_the_explicit_or_price_implied_bypass() {
        val net = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val routes = File("src/main/kotlin/com/lifecyclebot/engine/RouteTruthHydrator.kt").readText()
        val map = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        val features = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertFalse(net.contains("explicitCurve7392"))
        assertTrue(net.contains("PumpCurveKeys7269.isPumpMint8658(m)"))
        assertTrue(bot.contains("if (com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint))"))
        assertTrue(bot.contains("if (!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint)) return"))
        assertTrue(routes.contains("pumpCurveMint8658 && hay.contains(\"PUMP\")"))
        assertTrue(map.contains("if (pumpCurveMint8658 && !tm.migratedOrGraduated"))
        assertTrue(features.contains("val isBondingCurve = pumpCurveMint8658 &&"))
        assertTrue(TokenFacts.pumpCurveMetricSource8658("PUMP_CURVE_RPC_KEYLESS_8359"))
        assertTrue(TokenFacts.pumpCurveMetricSource8658("HELIUS_CURVE_8354"))
        assertTrue(TokenFacts.pumpCurveMetricSource8658("PUMP_FUN_BC_SYNTHETIC"))
        assertFalse("actual pool prices must remain available to other launchpads",
            TokenFacts.pumpCurveMetricSource8658("POOL_VAULT_8418"))
        assertFalse(TokenFacts.pumpCurveMetricSource8658("DEXSCREENER_PAIR"))
    }

    @Test fun non_pump_launchpad_is_never_classed_as_pump_fun_curve_or_graduating() {
        val ts = TokenState(mint = bonk, symbol = "BONK").apply {
            lastPriceSource = "PUMP_PORTAL_TRADE_WS_7278"
            lastPriceDex = "PUMP_FUN"
            lastMcap = 50_000.0
            lastLiquidityUsd = 12_000.0
        }
        assertFalse(LiquidityClassifier.isBondingCurveQuote(ts))
        assertFalse(LiquidityClassifier.isBcSimOnly(ts))
        assertEquals(BondingCurveTracker.CurveStage.UNKNOWN, BondingCurveTracker.evaluate(ts).stage)
    }
}
