package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8400 — RugCheck is a graded edge on the MOONSHOT score, not a door; market cap is captured with the token at discovery. */
class Aate8400RugcheckEdgeAndDiscoveryCaptureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // superseded by V5.0.8418 — confirmed_low_safety_is_a_graded_penalty / moonshot_door_refuses_only_confirmed_rug_zero:
    // the RugCheck danger band (safety 2..14 = provider risk 86..98) is a hard refusal again (rug is hard safety, never
    // loosened); see Aate8418ExitsDataRoutesTest.moonshotRefusesConfirmedRugAndTheDangerBand.
    @Test fun moonshot_door_refuses_the_danger_band_again() {
        val score = src("v3/scoring/MoonshotTraderAI.kt").substringAfter("fun scoreToken(").substringBefore("fun openPosition")
        assertTrue(score.contains("rugcheckRefusal8418(rugcheckScore)?.let { why -> return MoonshotScore(false, 0, 0.0, why) }"))
        assertFalse(score.contains("score -= rcPenalty8400"))
    }

    // superseded by V5.0.8418 — curve_cap_from_liquidity_inverts_the_8048_depth_rule: the CURVE_FROM_LIQUIDITY step (a cap
    // derived from a depth over an assumed 1B supply) is deleted; a cap with no verified supply stays UNKNOWN.
    @Test fun curve_cap_from_liquidity_is_gone() {
        val dc = src("engine/truth/DiscoveryCapture8400.kt")
        assertFalse(dc.contains("fun curveCapUsdFromLiquidity8400("))
        assertFalse(dc.contains("how = CURVE_FROM_LIQUIDITY"))
    }

    @Test fun market_cap_is_captured_at_intake_for_every_source_and_backstopped_at_the_bridge() {
        val bs = src("engine/BotService.kt")
        val intake = bs.substringAfter("private fun admitProtectedMemeIntake(")
        // the ART-pinned intake method delegates: source cap max-take, cap resolver, depth hydrate, in one helper
        assertTrue(intake.contains("DiscoveryCapture8400.captureAtIntake8400(ts, source, trustedMarketCapUsd6492)"))
        assertFalse(intake.contains("ts.lastMcap = trustedMarketCapUsd6492"))
        val helper = src("engine/truth/DiscoveryCapture8400.kt").substringAfter("fun captureAtIntake8400(")
        val capWrite = helper.indexOf("ts.lastMcap = trustedMarketCapUsd")
        val capture = helper.indexOf("captureMcap8400(ts, \"INTAKE_\$source\")")
        val liq = helper.indexOf("LiquidityTruth8037.hydrate8037(ts)")
        assertTrue(capWrite in 0 until capture && capture < liq)   // cap before depth so the 8164 derivation has a cap
        assertTrue(helper.contains("DISCOVERY_LIQ_MISSING_8400_"))
        // superseded by V5.0.8418 — warmup no longer feeds its stored (entry / first-seen) cap in as the current one:
        // was `marketCapUsd = if (h.mcapUsdEstimate > 0.0) h.mcapUsdEstimate else Double.NaN`.
        assertTrue(bs.contains("marketCapUsd = Double.NaN,   // superseded by V5.0.8418 — a stored entry/first-seen cap is history"))
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
        // superseded by V5.0.8418 — the curve-from-liquidity step between them is deleted (was `p < l && l < q`).
        val q = dc.indexOf("OnChainDepth8038.needDepth8047(ts)")
        assertTrue(a in 0 until p && p < q)
    }
}
