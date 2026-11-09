package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CostBandExit8536
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8536 — owner, after 8535 live (0/5, every buy through the lane-opinion door): (1) no bypass of the setup-authority
 * filter, (2) no live buy on the lane opinion alone, (3) no exit inside the cost band, (4) no live buy from proven-losing
 * sources; and paper meme buys are no longer diverted by a fixed paper-only score gate.
 */
class Aate8536LiveProvenOnlyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun the_native_identity_takes_the_same_setup_authority_test() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue("native choices need the very same fired-predicate test as playbook choices",
            adm.contains("val nativeIdentity8650 = (nativeOpinionId8650 ?: nativeSetupBook8217 ?: runnerForming8260)\n            ?.takeIf { it.isNotBlank() && !it.equals(\"NONE\", true) && triggered8535(it) }"))
        assertTrue(adm.contains("val selected = selectedRaw8535?.takeIf { triggered8535(it) }"))
    }

    @Test fun live_needs_proven_evidence_and_paper_keeps_the_lane_opinion() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val i = adm.indexOf("if (nativeLive8259 && c.live) {")
        assertTrue(i > 0 && i < adm.indexOf("if (nativeLive8259) {\n            trace += \"nativeQualifiedAdmit8259="))
        assertTrue(adm.contains("return done(Terminal8136.REFUSE, LIVE_NATIVE_OPINION_UNPROVEN_8536, true, chosen)"))
        assertEquals("LIVE_NATIVE_OPINION_UNPROVEN_8536", CandidateAdmission8136.LIVE_NATIVE_OPINION_UNPROVEN_8536)
        // the live backstop: whatever door admitted it, an unproven live entry is refused
        assertFalse(LiveEdgeGate7877.provenLive8536(false, false, false, false))
        assertTrue(LiveEdgeGate7877.provenLive8536(true, false, false, false))
        assertTrue(LiveEdgeGate7877.provenLive8536(false, true, false, false))
        assertTrue(LiveEdgeGate7877.provenLive8536(false, false, true, false))
        assertTrue(LiveEdgeGate7877.provenLive8536(false, false, false, true))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        // superseded by V5.0.8577 — "val final = core ?: hold ?: niche ?: wait ?: proof8536": the doors' answer is doors8577; the
        // verdict (final) is that answer unless lane authority makes it advisory for a lane-qualified LIVE candidate.
        assertTrue(gate.contains("val doors8577 = core ?: hold ?: niche ?: wait ?: proof8536"))
        assertTrue(gate.contains("val final = if (advisory8577) null else doors8577"))
        assertTrue(gate.contains("else (sourceRefusal8536(ts) ?: unprovenLive8536(ts, l, nowMs, candidateVersion, candidateAdmission))"))
        assertTrue(gate.contains("admission.reason == \"SPECIFIC_EDGE_PROVEN_8136\""))
        assertEquals("LIVE_UNPROVEN_EDGE_8536", CandidateAdmission8136.LIVE_UNPROVEN_EDGE_8536)
    }

    @Test fun exits_never_sell_inside_the_cost_band() {
        // 4.95 % round trip: band edge -7.95 %
        assertEquals(-(4.95 + CostBandExit8536.NOISE_PCT), CostBandExit8536.bandPct(4.95)!!, 1e-9)
        assertEquals(3.0, CostBandExit8536.NOISE_PCT, 0.0)
        assertNull(CostBandExit8536.bandPct(Double.NaN))
        assertEquals("a -4 % fluid stop is widened to the band edge", -7.95, CostBandExit8536.stopFloorPct(-4.0, 4.95), 1e-9)
        assertEquals("a wider stop is untouched", -12.0, CostBandExit8536.stopFloorPct(-12.0, 4.95), 0.0)
        assertEquals("a trailing (positive) stop is untouched", 6.0, CostBandExit8536.stopFloorPct(6.0, 4.95), 0.0)
        assertEquals("unknown cost changes nothing", -4.0, CostBandExit8536.stopFloorPct(-4.0, Double.NaN), 0.0)
        // AjZ8JF: rotated at -3.4 % after 4 minutes
        assertTrue(CostBandExit8536.rotationHolds(-3.4, 4L * 60_000L, 4.95))
        assertFalse("past the young window", CostBandExit8536.rotationHolds(-3.4, CostBandExit8536.YOUNG_MS, 4.95))
        assertFalse("below the band it may rotate", CostBandExit8536.rotationHolds(-9.0, 60_000L, 4.95))
        assertFalse("above the cost it may rotate", CostBandExit8536.rotationHolds(6.0, 60_000L, 4.95))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("fluidStopOutsideCostBand8536(ts, RUNNER_LANE_MIN_FLUID_STOP_PCT_7696)") && bot.contains("} else fluidStopOutsideCostBand8536(ts, dynamicStopPct)"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("val eligible = !inCostBand8536 && com.lifecyclebot.engine.truth.OpportunityRotation8098.victimEligible8098("))
    }

    @Test fun live_skips_sources_proven_losing() {
        assertTrue(LiveEdgeGate7877.sourceProvenLosing8536(158, -18.7, 2.0))
        assertFalse("thin", LiveEdgeGate7877.sourceProvenLosing8536(LiveEdgeGate7877.SOURCE_MIN_N_8536 - 1, -18.7, 2.0))
        assertFalse("inside one SE of zero", LiveEdgeGate7877.sourceProvenLosing8536(41, -1.0, 2.5))
        assertFalse(LiveEdgeGate7877.sourceProvenLosing8536(41, 1.8, 2.0))
        assertFalse(LiveEdgeGate7877.sourceProvenLosing8536(200, Double.NaN, 1.0))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue("every source must be proven losing", gate.contains("stats.filterNotNull().all { sourceProvenLosing8536("))
        assertTrue("an ungraded source refuses nothing", gate.contains("if (stats.any { it == null }) return null"))
        assertEquals("LIVE_SOURCE_PROVEN_LOSING_8536", LiveEdgeGate7877.LIVE_SOURCE_PROVEN_LOSING_8536)
    }

    @Test fun the_brief_names_why_paper_meme_buys_do_not_open() {
        val line = DiagnosisBrief8438.paperFunnelLine8536(12, 3, "LIVE_WALL_8223_LP=5", 2)
        assertEquals("paper meme8536 buyAttempts=12 opened=3 notOpened{LIVE_WALL_8223_LP=5} nonEconomicApproval=2", line)
        assertTrue(DiagnosisBrief8438.paperFunnelLine8536(0, 0, "", 0).contains("notOpened{-}"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("sb.append(\"               \").append(paperFunnel8536()).append('\\n')"))
    }
}
