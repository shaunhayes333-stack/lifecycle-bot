package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8869 — owner (19 Nov, after the lane assessment): "together now". A lane's adaptable no (momentum, continuation,
 * buy pressure, score) stands down for a coin the learned forecast has a proven call on; live takes a waiting entry the
 * shadow proved better than buying now, and that wait binds; candidates lost before a decision are named and the crypto
 * desk's stamps stay off the meme chains.
 */
class Aate8869ForecastAnswersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun op(lane: String, eligible: Boolean, reason: String, gradeable: Boolean = true, authoritative: Boolean = true) =
        SpecialistBrainBridge7542.Opinion(lane, eligible, 40, 40, 0.01, reason, "S", "e", "x",
            gradeable = gradeable, authoritative = authoritative)

    private val proven = EdgeModel8537.Verdict(24.0, false, "MIND8842", 900L, 260, 1.6, 2.8, -8.9,
        conviction8868 = 10.0, convictionLower8868 = 13.2)
    private val unproven = EdgeModel8537.Verdict(24.0, false, "MIND8842", 900L, 260, 1.6, 2.8, -8.9)

    @Test fun aProvenForecastAnswersOnlyAnAdaptableNo() {
        val no = op("EXPRESS", false, "NO_CONTINUATION")
        val answered = SpecialistBrainBridge7542.edgeStandDown8869(no, proven)!!
        assertTrue(answered.eligible)
        assertTrue(answered.reason, answered.reason.startsWith(SpecialistBrainBridge7542.EDGE_RETIRED_PREFIX_8869 + "+24.0%/tier+10:NO_CONTINUATION"))
        assertNull("an unproven forecast answers nothing", SpecialistBrainBridge7542.edgeStandDown8869(no, unproven))
        assertNull(SpecialistBrainBridge7542.edgeStandDown8869(no, null))
        assertNull("a forecast loss is never a yes", SpecialistBrainBridge7542.edgeStandDown8869(no, proven.copy(predPct = -4.0)))
        for (hard in listOf("LANE_LIQ_BELOW_FLOOR", "MCAP_TOO_LOW", "RUG_SCORE_HIGH", "TOKEN_TOO_OLD", "NATIVE_AGE_UNOBSERVED_DEFER",
                "PHASE_HANDOFF_8136_BIRTH", "SELLABILITY_REJECT", "TOP_HOLDER_CONCENTRATION"))
            assertNull(hard, SpecialistBrainBridge7542.edgeStandDown8869(op("EXPRESS", false, hard), proven))
        assertNull("already a yes", SpecialistBrainBridge7542.edgeStandDown8869(op("EXPRESS", true, ""), proven))
        assertNull("not a judgement of the coin", SpecialistBrainBridge7542.edgeStandDown8869(op("EXPRESS", false, "WEAK_BUYS", gradeable = false), proven))
        assertNull(SpecialistBrainBridge7542.edgeStandDown8869(op("EXPRESS", false, "WEAK_BUYS", authoritative = false), proven))
        assertTrue(SpecialistBrainBridge7542.edgeMayAnswer8869(op("SHITCOIN", false, "THRESHOLD_FAIL_SCORE")))
        assertTrue(SpecialistBrainBridge7542.edgeMayAnswer8869(op("TREASURY", false, "WEAK_MOMENTUM")))
    }

    @Test fun theStandDownIsReadAtTheDeskAndHonouredAtEveryDoor() {
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val asked8869 = out.filterValues(::edgeMayAnswer8869).keys"))
        assertTrue(b.contains("EdgeModel8537.quietVerdicts8869(ts, asked8869, now)"))
        assertTrue("the crypto desk's keys are not meme coins", b.contains("if (!OwnershipChain8364.cryptoAssetKey8869(ts.mint)) {"))
        assertTrue("the FDG score floor honours it", b.contains("r.startsWith(EDGE_RETIRED_PREFIX_8869)))   // V5.0.8869"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val retired = runner8113 || grab8343 || edge8869 || SpecialistBrainBridge7542.doorRetires8105(lane, false, reason)"))
        assertTrue(bot.contains("if (door.shouldEnter || choice?.action == NativeOpportunityPolicy8522.Action.REFUSE) return door"))
        assertTrue(bot.contains("return NativeOpportunityDoor8522.cashflowAnswered8869(door, ownerLane)"))
        val m = src("engine/truth/EdgeModel8537.kt")
        assertTrue("the quiet read asks no analyst", m.contains("if (!quiet8869 && tree != null && tree >= 0.0) try { LlmAnalyst8746.request8746(raw, mint, now) }"))
        assertTrue(m.contains("if (!quiet8869) try { Mind8746.note8746(mint, raw.lane, f, mind, now) }"))
        assertTrue(m.contains("verdictOfRaw8869(ts, base.copy(lane = lane(l), costPct = cost), stage, quiet = true)"))
    }

    private fun proof(n: Int, mean: Double, se: Double) = EvidenceHold8336.Proof8336(n, mean, se, 1.0, false)

    @Test fun aWaitIsTakenOnlyWhenProvenBetterThanBuyingNow() {
        // MOONSHOT 15:21: NOW/RUNNER n300 -10.0 %, PULLBACK/RUNNER n135 -1.2 % filled 26 % (spreads of 25 points)
        val now = proof(300, -10.0, 25.0 / Math.sqrt(300.0))
        val pull = proof(135, -1.2, 25.0 / Math.sqrt(135.0))
        assertTrue(ExploreShadow8347.waitBeatsNow8869(now, pull, 0.26))
        assertTrue(ExploreShadow8347.waitMargin8869(now, pull, 0.26) > 0.0)
        // EXPRESS: NOW/SCALP n300 -7.1 %, PULLBACK/SCALP n168 -8.2 % filled 42 %: not better
        assertFalse(ExploreShadow8347.waitBeatsNow8869(proof(300, -7.1, 1.4), proof(168, -8.2, 1.9), 0.42))
        // too few closes on either side
        assertFalse(ExploreShadow8347.waitBeatsNow8869(proof(39, -20.0, 1.0), proof(200, 5.0, 1.0), 1.0))
        assertFalse(ExploreShadow8347.waitBeatsNow8869(proof(300, -20.0, 1.0), proof(39, 5.0, 1.0), 1.0))
        assertFalse(ExploreShadow8347.waitBeatsNow8869(proof(300, Double.NaN, 1.0), proof(100, 5.0, 1.0), 1.0))
        // inside two standard errors of each other: not proven
        assertFalse(ExploreShadow8347.waitBeatsNow8869(proof(100, -5.0, 2.0), proof(100, -1.0, 2.0), 1.0))
    }

    @Test fun theWaitBindsAndAnOwnProofIsNotHeldForIt() {
        assertTrue(LaneAuthority8577.binding8577("ENTRY_TIMING_WAIT_RELATIVE_8869_PULLBACK"))
        assertTrue(LaneAuthority8577.binding8577("ENTRY_TIMING_EXPIRED_RELATIVE_8869_PULLBACK"))
        assertTrue(LaneAuthority8577.binding8577("ENTRY_TIMING_WAIT_8347_CONFIRM"))
        val s = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(s.contains("val relative = if (best == null) relative8869[canon(lane)]?.takeIf { it != Entry8347.NOW } else null"))
        assertTrue(s.contains("relative8869[l] = computeRelative8869(l, sinceMs) ?: Entry8347.NOW"))
        assertTrue(s.contains("relative8869{\${relativeLine8869()}}"))
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("val wait = if (paper || laneWide || niche != null || ownProof8869) null else"))
        assertTrue(g.contains("(a.reason == \"SPECIFIC_EDGE_PROVEN_8136\" || a.reason == CandidateAdmission8136.EDGE_MODEL_PROVEN_8537)"))
    }

    @Test fun aCandidateLostBeforeADecisionIsNamed() {
        assertTrue(OwnershipChain8364.cryptoAssetKey8869("solana|CYPHuMmCL1GxJWa2tsPhLKykC7GrHJTCHwbXD4g5uawK"))
        assertFalse(OwnershipChain8364.cryptoAssetKey8869("9HbciSZ7mvhJXs8Q7RK1hEBYo11zjUXNb58uud6Dpump"))
        assertFalse(OwnershipChain8364.cryptoAssetKey8869("|x"))
        val offered = listOf(OwnershipChain8364.Step8364.NATIVE_FIRED, OwnershipChain8364.Step8364.OWNER_OFFERED)
        assertEquals("NOT_EVALUATED_CYCLE_V3_FATAL", OwnershipChain8364.cycleExitTerminal8869("", offered, "", "V3_FATAL"))
        assertEquals("a later exit replaces an earlier one", "NOT_EVALUATED_CYCLE_SYMBOL_FAMILY_DEDUP",
            OwnershipChain8364.cycleExitTerminal8869("", offered, "NOT_EVALUATED_CYCLE_V3_FATAL", "SYMBOL_FAMILY_DEDUP"))
        assertNull("the FDG's deferral names more", OwnershipChain8364.cycleExitTerminal8869("", offered, "FDG_DEFER_DATA_X", "V3_FATAL"))
        assertNull(OwnershipChain8364.cycleExitTerminal8869("TICKET", offered, "", "V3_FATAL"))
        assertNull(OwnershipChain8364.cycleExitTerminal8869("", offered + OwnershipChain8364.Step8364.FDG_BLOCK, "", "V3_FATAL"))
        assertEquals("LEFT_WATCHLIST", OwnershipChain8364.idleCause8869(false))
        assertEquals("IDLE_TTL", OwnershipChain8364.idleCause8869(true))
        assertEquals("IDLE_TTL", OwnershipChain8364.idleCause8869(null))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("processTokenCycleTraced8869(mint, cycleCfg8779(cfg), wallet, t0)"))
        assertTrue(bot.contains("try { processTokenCycle(mint, cfg, wallet, lastSuccessfulPollMs) } finally {"))
        for (g in listOf("V3_FATAL", "V3_BLOCKED", "V3_REJECTED_TERMINAL", "SYMBOL_FAMILY_DEDUP", "CROSS_LANE_MINT_DEDUP", "PROPOSE_DEDUPE", "NATIVE_CORE_PATH"))
            assertTrue(g, bot.contains("return cycleExit8869(\"$g\")   // V5.0.8869"))
        assertTrue(bot.contains("cycleTrace8869.get()?.lanesReached = true   // V5.0.8869"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 60. The forecast answers a lane's preference, and the timing wait binds (8869)"))
    }
}
