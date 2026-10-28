package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.MintSupply8418
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8399 — a pump.fun curve prices its own token: a row with no market cap is hydrated from the curve's fresh reserves. */
class Aate8399CurveMcapHydrationTest {
    @Test fun curveReservesPriceTheFixedPumpSupply() {
        // 34 SOL / 9.4e8 tokens -> 3.617e-8 SOL per token; x 1e9 VERIFIED supply x $150 = ~$5,425
        // superseded by V5.0.8418 — the supply is the mint account's (passed in), not a PUMP_TOTAL_SUPPLY_8399 constant.
        val mcap = CurveReserves7951.mcapUsdFromCurve8399(34.0, 9.4e8, 150.0, 1e9)
        assertEquals(34.0 / 9.4e8 * 1e9 * 150.0, mcap, 1e-6)
        assertTrue(CurveReserves7951.mcapUsdFromCurve8399(34.0, 9.4e8, Double.NaN, 1e9).isNaN())
        assertTrue(CurveReserves7951.mcapUsdFromCurve8399(0.0, 9.4e8, 150.0, 1e9).isNaN())
        // No verified supply: the cap is UNKNOWN, never a 1e9 guess.
        assertTrue(CurveReserves7951.mcapUsdFromCurve8399(34.0, 9.4e8, 150.0, 0.0).isNaN())
        assertTrue(CurveReserves7951.mcapUsdFromCurve8399(34.0, 9.4e8, 150.0, Double.NaN).isNaN())
    }

    @Test fun unhydratedRowTakesTheCurveMcapAndAnObservedRowKeepsItsOwn() {
        CurveReserves7951.resetForTest7951()
        MintSupply8418.resetForTest8418()
        val now = 1_000_000L
        CurveReserves7951.noteFrame7951("mint8399", 34.0, 9.4e8, now - 1_000L)
        val ts = TokenState(mint = "mint8399", symbol = "CURVE")
        assertEquals(0.0, ts.lastMcap, 0.0)
        // V5.0.8418 — before the mint account is read the curve cannot state a cap.
        assertTrue(CurveReserves7951.hydrateMcap8399(ts, 150.0, now).isNaN())
        assertEquals(0.0, ts.lastMcap, 0.0)
        MintSupply8418.accept8418(MintSupply8418.Supply8418("mint8399", java.math.BigInteger("1000000000000000"), 6, 300L, now, MintSupply8418.Source8418.TOKEN_SUPPLY), notifyMetrics = false)
        val written = CurveReserves7951.hydrateMcap8399(ts, 150.0, now)
        assertTrue(written > 0.0)
        assertEquals(written, ts.lastMcap, 0.0)
        // An observed market cap is never overwritten by the derived one.
        val observed = TokenState(mint = "mint8399", symbol = "CURVE").apply { lastMcap = 12_345.0 }
        assertTrue(CurveReserves7951.hydrateMcap8399(observed, 150.0, now).isNaN())
        assertEquals(12_345.0, observed.lastMcap, 0.0)
    }

    @Test fun staleOrAbsentReservesDoNotInventAMarketCap() {
        CurveReserves7951.resetForTest7951()
        val now = 1_000_000L
        CurveReserves7951.noteFrame7951("stale8399", 34.0, 9.4e8, now - 16L * 60_000L)
        assertTrue(CurveReserves7951.hydrateMcap8399(TokenState(mint = "stale8399", symbol = "S"), 150.0, now).isNaN())
        assertTrue(CurveReserves7951.hydrateMcap8399(TokenState(mint = "absent8399", symbol = "A"), 150.0, now).isNaN())
    }

    @Test fun theSpecialistReadAndThePrintMarkBothHydrate() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        // V5.0.8400 — the bridge reads the curve through the one discovery resolver, whose first step is hydrateMcap8399.
        assertTrue(bridge.contains("DiscoveryCapture8400.captureMcap8400(ts, \"BRIDGE\")"))
        val resolver = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/DiscoveryCapture8400.kt").readText()
        assertTrue(resolver.contains("CurveReserves7951.hydrateMcap8399(ts, solUsd)"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // superseded by V5.0.8418 — the print mark prices the VERIFIED supply (was px x PUMP_TOTAL_SUPPLY_8399).
        assertTrue(bot.contains("com.lifecyclebot.engine.truth.MintSupply8418.capUsd8418(mint, px).takeIf { it.isFinite() }?.let { ts.lastMcap = it }"))
    }
}
