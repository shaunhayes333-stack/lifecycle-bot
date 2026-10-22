package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.network.PumpCurveKeys7269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8320 (owner's exact T1, labels 8319) — an ACTIVE pump.fun curve observed inside the curve TTL with >= 0.5 real SOL
 * is exit-safe for the launch lanes; unknown / stale / graduated curves, non-curve pools and established lanes keep their
 * floors, and EXIT_SAFE_HARD_FLOOR_USD_8140 stays 500.0.
 */
class Aate8320CurveExitFloorTest {
    private val t = LaneAgeLiquidityTruth8140
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun activeCurveWithHalfSolRealIsExitSafe() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val mint = "Curve8320ActiveAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteAccount7951(mint, 31.2, 1_031_000_000.0, 1.2, now)
        assertTrue(t.activeCurveExitSafe8319(mint, now))
        // Launch lanes pass a $200 real-SOL pool that would refuse below their floor.
        for (lane in listOf("SHITCOIN", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "MOONSHOT")) {
            assertNull(lane, t.laneLiquidityRefusal8140(lane, 200.0, 1_000.0, mint))
        }
        CurveReserves7951.resetForTest7951()
    }

    @Test fun thinStaleUnknownOrGraduatedCurveIsNotExitSafe() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val thin = "Curve8320ThinBBBBBBBBBBBBBBBBBBBBBBBBBBBpump"
        CurveReserves7951.noteAccount7951(thin, 30.3, 1_062_000_000.0, 0.3, now)
        assertFalse(t.activeCurveExitSafe8319(thin, now))
        assertNotNull(t.laneLiquidityRefusal8140("SHITCOIN", 60.0, 1_000.0, thin))

        val stale = "Curve8320StaleCCCCCCCCCCCCCCCCCCCCCCCCCpump"
        CurveReserves7951.noteAccount7951(stale, 35.0, 920_000_000.0, 5.0, now - 2L * 60L * 60_000L)
        assertFalse(t.activeCurveExitSafe8319(stale, now))

        assertFalse(t.activeCurveExitSafe8319("Curve8320UnknownDDDDDDDDDDDDDDDDDDDDDDDpump", now))
        assertFalse(t.activeCurveExitSafe8319("", now))

        val grad = "Curve8320GraduatedEEEEEEEEEEEEEEEEEEEEEEpump"
        CurveReserves7951.noteAccount7951(grad, 115.0, 280_000_000.0, 85.0, now)
        PumpCurveKeys7269.markGraduated7392(grad)
        assertFalse(t.activeCurveExitSafe8319(grad, now))
        assertNotNull(t.laneLiquidityRefusal8140("EXPRESS", 400.0, 1_000.0, grad))
        CurveReserves7951.resetForTest7951()
    }

    @Test fun establishedLanesAndNonCurvePoolsKeepTheirFloors() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val mint = "Curve8320EstablishedFFFFFFFFFFFFFFFFFFFFpump"
        CurveReserves7951.noteAccount7951(mint, 40.0, 805_000_000.0, 10.0, now)
        for (lane in listOf("QUALITY", "BLUECHIP", "DIP_HUNTER")) {
            assertNotNull(lane, t.laneLiquidityRefusal8140(lane, 200.0, 3_000.0, mint))
        }
        // A non-curve pool (no curve reading) still refuses below the lane floor.
        assertNotNull(t.laneLiquidityRefusal8140("SHITCOIN", 200.0, 1_000.0, "Pool8320NonCurveGGGGGGGGGGGGGGGGGGGGGGGGGGG"))
        assertEquals(500.0, LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140, 0.0)
        assertEquals(0.5, LaneAgeLiquidityTruth8140.CURVE_EXIT_MIN_REAL_SOL_8319, 0.0)
        CurveReserves7951.resetForTest7951()
    }

    @Test fun launchScorersPassTheMintAndSkipTheLegacyRecheck() {
        val sites = mapOf(
            "ShitCoinTraderAI" to "laneLiquidityRefusal8140(\"SHITCOIN\", liquidityUsd, minLiq, mint)",
            "ShitCoinExpress" to "laneLiquidityRefusal8140(\"EXPRESS\", liquidityUsd, 1_000.0, mint)",
            "MoonshotTraderAI" to "laneLiquidityRefusal8140(\"MOONSHOT\", liquidityObserved7389, minLiq, mint)",
            "ProjectSniperAI" to "laneLiquidityRefusal8140(\"PROJECT_SNIPER\", ts.lastLiquidityUsd, MIN_LIQUIDITY_USD, ts.mint)",
            "ManipulatedTraderAI" to "laneLiquidityRefusal8140(\"MANIPULATED\", liquidityUsd, minLiq, mint)",
        )
        for ((file, call) in sites) {
            val s = src("v3/scoring/$file.kt")
            assertTrue(file, s.contains(call))
            assertTrue(file, s.contains("!com.lifecyclebot.engine.LaneAgeLiquidityTruth8140.activeCurveExitSafe8319("))
        }
        // Established lanes keep the 3-arg floor.
        assertTrue(src("v3/scoring/QualityTraderAI.kt").contains("laneLiquidityRefusal8140(\"QUALITY\", liquidityUsd, minLiqUsd)"))
        assertTrue(src("v3/scoring/BlueChipTraderAI.kt").contains("laneLiquidityRefusal8140(\"BLUECHIP\", liquidityUsd, minLiq)"))
    }

    @Test fun preTradeHardGateWiring() {
        val g = src("engine/PreTradeHardGate.kt")
        assertTrue(g.contains("MIN_LIVE_LIQ_HARD_FLOOR_USD = 500.0"))
        assertTrue(g.contains("val curveExitSafe8319 = try { LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(ts.mint) } catch (_: Throwable) { false }"))
        assertTrue(g.contains("ts.lastLiquidityUsd < MIN_LIVE_LIQ_HARD_FLOOR_USD && !curveExitSafe8319) {"))
        // The thin-pool arm of the holder-distribution defer is curve-aware; a blind rugcheck still defers.
        assertTrue(g.contains("(ts.lastLiquidityUsd in 0.000001..MIN_LIVE_LIQ_USD && !curveExitSafe8319)"))
        assertTrue(g.contains("rcStatus in setOf(\"UNKNOWN\", \"TIMEOUT\", \"PENDING\", \"PENDING_REVIEW\", \"ERROR\", \"\")"))
        // Every other wall is still there.
        for (wall in listOf("TRUE_ZERO_LIQUIDITY", "TOP_HOLDER_CONCENTRATION", "FREEZE_AUTHORITY_UNVERIFIED_7238", "SAFETY_HARD_BLOCK")) {
            assertTrue(wall, g.contains(wall))
        }
        // Paper parity mirrors the live wall.
        val p = src("engine/truth/PaperUniverseParity8223.kt")
        assertTrue(p.contains("w.liquidityUsd < LIVE_LIQ_HARD_FLOOR_USD_8223 && !w.curveExitSafe8319 ->"))
    }
}
