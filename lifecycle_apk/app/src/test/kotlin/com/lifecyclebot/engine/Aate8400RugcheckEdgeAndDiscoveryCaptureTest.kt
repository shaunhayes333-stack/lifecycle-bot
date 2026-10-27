package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8400 — RugCheck is a graded edge on the MOONSHOT score, not a door; market cap is captured with the token at discovery. */
class Aate8400RugcheckEdgeAndDiscoveryCaptureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun confirmed_low_safety_is_a_graded_penalty_pending_and_safe_are_free() {
        assertEquals(0, MoonshotTraderAI.rugcheckSoftPenalty8400(-1))   // unfetched
        assertEquals(0, MoonshotTraderAI.rugcheckSoftPenalty8400(1))    // pending
        assertEquals(13, MoonshotTraderAI.rugcheckSoftPenalty8400(2))   // provider risk 98
        assertEquals(7, MoonshotTraderAI.rugcheckSoftPenalty8400(8))
        assertEquals(1, MoonshotTraderAI.rugcheckSoftPenalty8400(14))
        assertEquals(0, MoonshotTraderAI.rugcheckSoftPenalty8400(15))
        assertEquals(0, MoonshotTraderAI.rugcheckSoftPenalty8400(100))
        var prev = Int.MAX_VALUE
        for (s in 2..100) { val p = MoonshotTraderAI.rugcheckSoftPenalty8400(s); assertTrue(p <= prev); prev = p }
    }

    @Test fun moonshot_door_refuses_only_confirmed_rug_zero() {
        val s = src("v3/scoring/MoonshotTraderAI.kt")
        val score = s.substringAfter("fun scoreToken(").substringBefore("fun openPosition")
        assertTrue(score.contains("if (rugcheckScore == 0) {"))
        assertTrue(score.contains("\"rugcheck_0_confirmed_rug\""))
        // no refusal is built from a "below minimum" reason any more (history of the old door stays in comments)
        assertFalse(score.contains("\"rugcheck_\${rugcheckScore}_below_min_"))
        assertFalse(score.contains("val minRcScore"))
        val scoring = score.substringAfter("var score = 0")
        assertTrue(scoring.substringBefore("val mcapScore").contains("score -= rcPenalty8400"))
    }

    @Test fun curve_cap_from_liquidity_inverts_the_8048_depth_rule() {
        for (capSol in listOf(28.0, 40.0, 75.0, 150.0, 400.0)) {
            val real = LiquidityTruth8037.curveRealSolFromCap8048(capSol)
            val back = DiscoveryCapture8400.curveCapSolFromRealSol8400(real)
            assertEquals(capSol, back, capSol * 1e-9)
        }
        assertTrue(DiscoveryCapture8400.curveCapUsdFromLiquidity8400(0.0, 100.0).isNaN())
        assertTrue(DiscoveryCapture8400.curveCapUsdFromLiquidity8400(1000.0, 0.0).isNaN())
        // $1,000 of real SOL at $100/SOL = 10 SOL real -> (40^2 / 32.19) SOL cap ~= 49.7 SOL ~= $4,970
        val usd = DiscoveryCapture8400.curveCapUsdFromLiquidity8400(1000.0, 100.0)
        assertEquals(40.0 * 40.0 / 32.19 * 100.0, usd, 1.0)
    }

    @Test fun market_cap_is_captured_at_intake_for_every_source_and_backstopped_at_the_bridge() {
        val bs = src("engine/BotService.kt")
        val intake = bs.substringAfter("private fun admitProtectedMemeIntake(")
        val capWrite = intake.indexOf("ts.lastMcap = trustedMarketCapUsd6492")
        val capture = intake.indexOf("DiscoveryCapture8400.captureMcap8400(ts, \"INTAKE_\$source\")")
        assertTrue(capWrite >= 0 && capture > capWrite)   // after the source's own cap, before the row is handed on
        // liquidity is captured right after the cap (so the 8164 cap -> depth derivation has a cap to read)
        val liq = intake.indexOf("LiquidityTruth8037.hydrate8037(ts)", capture)
        assertTrue(liq > capture)
        assertTrue(intake.contains("DISCOVERY_LIQ_MISSING_8400_"))
        // warmup carries the cap it captured instead of NaN
        assertTrue(bs.contains("marketCapUsd = if (h.mcapUsdEstimate > 0.0) h.mcapUsdEstimate else Double.NaN"))
        val warm = src("engine/HotConvictionWarmup.kt")
        assertTrue(warm.contains("val mcapUsdEstimate: Double = 0.0"))
        assertTrue(warm.contains("put(\"mcapUsdEstimate\", h.mcapUsdEstimate)"))
        assertTrue(warm.contains("mcapUsdEstimate = o.optDouble(\"mcapUsdEstimate\", 0.0)"))
        // bridge backstop runs before the fingerprint/cache read; the fingerprint carries lastMcap so a capture re-evaluates
        val b = src("engine/SpecialistBrainBridge7542.kt")
        val eval = b.substringAfter("fun evaluate(ts:TokenState)")
        val bridgeCapture = eval.indexOf("DiscoveryCapture8400.captureMcap8400(ts, \"BRIDGE\")")
        val fp = eval.indexOf("val f=fp(ts,paper,residentScope7828)")
        assertTrue(bridgeCapture in 0 until fp)
        assertTrue(b.substringAfter("private fun fp(").contains("ts.lastMcap.toLong()"))
        // the resolver order is evidence first, chain read last
        val dc = src("engine/truth/DiscoveryCapture8400.kt").substringAfter("fun captureMcap8400(")
        val a = dc.indexOf("CurveReserves7951.hydrateMcap8399"); val p = dc.indexOf("TrustedMcap8019.mcap8019(ts)")
        val l = dc.indexOf("curveCapUsdFromLiquidity8400(ts.lastLiquidityUsd"); val q = dc.indexOf("OnChainDepth8038.needDepth8047(ts)")
        assertTrue(a in 0 until p && p < l && l < q)
    }
}
