package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVsStack8539
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8539 — owner: the main stack trades on its own; the Cortex influences live trading only once it proves it finds
 * winners better than that stack. And every floor is fluid — learnt and unlearnt from the stack's own record.
 */
class Aate8539CortexEarnsAndFluidFloorsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun the_cortex_must_beat_the_main_stack_outright() {
        // 8538 run: Cortex STRONG TREASURY n25 -12.0 %, CASHGEN n28 -4.7 %; stack admits -12.8 %
        assertEquals("CORTEX_THIN", CortexVsStack8539.code(25, -12.0, 2.0, 300, -8.6, 1.0))
        assertEquals("STACK_THIN", CortexVsStack8539.code(60, 6.0, 1.0, CortexVsStack8539.MIN_N - 1, -5.0, 1.0))
        assertEquals("picks that lose never earn a say", "CORTEX_PICKS_LOSE", CortexVsStack8539.code(60, -1.0, 0.5, 300, -12.8, 1.0))
        assertEquals("inside the combined error", "NOT_BETTER_THAN_STACK", CortexVsStack8539.code(60, 2.0, 2.0, 300, 0.0, 1.0))
        assertEquals("UNREAD", CortexVsStack8539.code(60, Double.NaN, 1.0, 300, 0.0, 1.0))
        assertTrue(CortexVsStack8539.beats(60, 6.0, 1.5, 300, -2.0, 1.0))
        assertFalse(CortexVsStack8539.beats(60, 6.0, Double.POSITIVE_INFINITY, 300, -2.0, 1.0))
    }

    @Test fun every_live_cortex_lever_reads_the_stack_test() {
        val cx = src("engine/cortex/Cortex7885.kt")
        val auth = cx.substringAfter("private fun authorityFraction8366(lane: String): Double {").substringBefore("/** Caller holds the lock. What keeps")
        assertTrue("authority (sizing, overrule, STRONG evidence, ordering, native grants) is zero until proven",
            auth.contains("if (!beatsStack8539(lane)) { entryGrants8506.remove(lane); return 0.0 }"))
        assertTrue("live refusals other than hard safety", cx.contains("if (nativeOwnsSelection8506(laneRaw) || (!paper && !provenOverStack8539(laneRaw))) {"))
        assertTrue("the operator inverse opportunity in live", cx.contains("(mode != \"LIVE\" || provenOverStack8539(lane)))"))
        assertTrue("the yardstick is the stack's own admits", cx.contains("ForwardReturnLabeler7731.laneAdmittedExecutable8539(lane)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("Cortex7885.stackLine8539()"))
    }

    @Test fun liquidity_floors_are_learnt_and_unlearnt() {
        assertEquals("L0", LearnedLiquidityFloor8539.band(0.0))
        assertEquals("L0", LearnedLiquidityFloor8539.band(Double.NaN))
        assertEquals("L1", LearnedLiquidityFloor8539.band(135.0))
        assertEquals("L2", LearnedLiquidityFloor8539.band(500.0))
        assertEquals("L3", LearnedLiquidityFloor8539.band(2_000.0))
        assertEquals("L5", LearnedLiquidityFloor8539.band(80_000.0))
        assertEquals(LearnedLiquidityFloor8539.Call.OPEN, LearnedLiquidityFloor8539.call(40, 8.0, 3.0))
        assertEquals(LearnedLiquidityFloor8539.Call.CLOSE, LearnedLiquidityFloor8539.call(40, -9.0, 3.0))
        assertEquals("inside one SE", LearnedLiquidityFloor8539.Call.NONE, LearnedLiquidityFloor8539.call(40, 2.0, 3.0))
        assertEquals("thin", LearnedLiquidityFloor8539.Call.NONE, LearnedLiquidityFloor8539.call(LearnedLiquidityFloor8539.MIN_N - 1, 50.0, 1.0))
        val truth = src("engine/LaneAgeLiquidityTruth8140.kt")
        assertTrue(truth.contains("ForwardReturnLabeler7731.liqBandStat8539(l, liquidityUsd)"))
        assertTrue(truth.contains("LANE_LIQ_FLOOR_LEARNED_OPEN_8539_") && truth.contains("LANE_LIQ_BAND_PROVEN_LOSING_8539_"))
        assertTrue("unread depth and the live exit floor retain their verdicts", truth.contains("if (v.outcome != LaneAgeLiquidityScope8140.Outcome8140.DEFER && !belowLiveExitFloor) {"))
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("val withLiq8539 = if (pseudo) withStage else withStage + liqBandKey8539(o.lane, o.liqUsd8539)"))
        assertTrue(frl.contains("it.liqUsd8539 = liq  // V5.0.8539"))
    }

    @Test fun a_specialist_qualified_coin_is_not_parked_on_flow_the_bot_cannot_observe() {
        val c = CandidateAdmission8136.Candidate8136("flow-8539", "SHITCOIN", null, live = true, entryWait8506 = "FLOW_UNOBSERVED", liveEdge8114 = "CHART_BUY",
            priceAgeMs = 10L, expectedGrossPct = 20.0, costPct = 4.5, genericScore = 40.0, genericFloor = 15.0,
            nativeQualified8213 = true, nativeSetup8213 = "PRE_IGNITION_BASE")
        assertTrue(CandidateAdmission8136.specialistQualified8539(c))
        val d = CandidateAdmission8136.decide8136(c)
        assertEquals("the specialist's own call stands", CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
        assertTrue(d.trace.any { it.startsWith("specialistQualified8539 optionalFlow=") })
        assertFalse("below the lane's floor it is no specialist pick", CandidateAdmission8136.specialistQualified8539(c.copy(genericScore = 10.0)))
        assertFalse("no native qualification, no specialist pick", CandidateAdmission8136.specialistQualified8539(c.copy(nativeQualified8213 = false)))
        assertEquals("no specialist: it still waits for the data", CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(nativeQualified8213 = false)).terminal)
        assertEquals("critical facts still defer", CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf("quote:UNKNOWN"))).terminal)
        assertEquals("SAFETY_REFUSED_8136", CandidateAdmission8136.decide8136(c.copy(safetyRefusal = "MINT_AUTH")).reason)
    }

    @Test fun a_bare_native_score_does_not_turn_no_fired_setup_into_a_live_buy() {
        // 8650: CASHGEN native 53/100 without a fired rule is not a named, executable trade.
        val c = CandidateAdmission8136.Candidate8136("phase-8539", "CASHGEN", null, live = true, stage = "NO_SETUP",
            entryWait8506 = "NO_PHASE_TRIGGER", priceAgeMs = 10L, expectedGrossPct = 20.0, costPct = 4.5,
            genericScore = 53.0, genericFloor = 15.0, nativeQualified8213 = true, liveEdge8114 = "CHART_BUY")
        val d = CandidateAdmission8136.decide8136(c)
        assertFalse(CandidateAdmission8136.specialistQualified8539(c))
        assertEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
        assertEquals("no lane brain qualified it: the shared wait still binds", "ENTRY_NO_PHASE_TRIGGER_8506",
            CandidateAdmission8136.decide8136(c.copy(nativeQualified8213 = false)).reason)
        assertEquals("a dump still needs its reclaim", "ENTRY_NO_RECLAIM_8506",
            CandidateAdmission8136.decide8136(c.copy(stage = "DUMPING", entryWait8506 = "NO_RECLAIM")).reason)
    }

    @Test fun a_tape_blind_coin_is_not_a_flatline_while_its_venue_trades() {
        assertFalse("unread venue counts are not dead", FlatChart8027.venueTradesDead8539(null, null))
        assertTrue(FlatChart8027.venueTradesDead8539(FlatChart8027.DEAD_TX_H1_8539 - 1, 0))
        assertFalse(FlatChart8027.venueTradesDead8539(10, 5))
        assertFalse("unfilled 0 / 0 counts are unread, not dead", FlatChart8027.venueTradesDead8539(0, 0, 0.0))
        assertFalse("volume traded is alive", FlatChart8027.venueTradesDead8539(2, 3, FlatChart8027.DEAD_VOLUME_H1_USD_8539))
        assertTrue(FlatChart8027.venueTradesDead8539(2, 3, 50.0))
        val src = src("engine/FlatChart8027.kt")
        assertTrue(src.contains("tapeBlindVerdict8539(mint, nowMs) ?: deadTapeOnPrints8476(mint, nowMs)"))
        assertTrue("a failed read is unknown, not dead", src.contains("} catch (_: Throwable) { false }  // V5.0.8539"))
    }

    @Test fun the_feature_resolver_fetches_the_pair_it_is_missing() {
        // 8539 run: 11,085 evaluations read bp / chg / liq dexPair:NOT_FETCHED; meme lanes had features on 66-100 of ~1,600
        val r = com.lifecyclebot.engine.truth.SetupFeatureResolver8169
        assertTrue("never fetched", r.pairWanted8539(0L, 100_000L, 60_000L))
        assertTrue("older than the evidence window", r.pairWanted8539(10_000L, 100_000L, 60_000L))
        assertFalse("fresh", r.pairWanted8539(90_000L, 100_000L, 60_000L))
        val src = src("engine/truth/SetupFeatureResolver8169.kt")
        assertTrue(src.contains("if (pairWanted8539(pair?.atMs ?: 0L, nowMs, maxAge)) requestPair8539(mint, nowMs)"))
        assertTrue("Solana mints only, once per 20 s", src.contains("isSolanaMint7819(mint)) return") && src.contains("PAIR_ASK_GAP_MS_8539 = 20_000L"))
        assertTrue(src.contains("CandidateMarkFresh8094.requestLane8359(listOf(mint), nowMs)"))
        assertTrue(src.contains("pairAsked8539=${'$'}{pairRequested8539()}"))
    }

    @Test fun paper_explores_what_its_own_lane_qualified() {
        // 8539 paper run: 0 paper meme trades — paper held to every live money door
        val c = CandidateAdmission8136.Candidate8136("paper-8539", "MOONSHOT", null, live = false, priceAgeMs = 10L,
            expectedGrossPct = 3.0, costPct = 4.5, genericScore = 40.0, genericFloor = 15.0,
            nativeQualified8213 = true, nativeSetup8213 = "NATIVE_RULE_8535:MOONSHOT_READY")
        assertTrue(CandidateAdmission8136.paperExplores8539(c))
        assertFalse("live never explores", CandidateAdmission8136.paperExplores8539(c.copy(live = true)))
        val econ = CandidateAdmission8136.decide8136(c)
        assertEquals("economics is advisory to a paper exploration", CandidateAdmission8136.Terminal8136.ADMIT, econ.terminal)
        assertTrue(econ.trace.any { it.startsWith("paperExplore8539 economicsAdvisory") })
        assertEquals("unqualified paper still refuses", "EXECUTION_ECONOMICS_8136",
            CandidateAdmission8136.decide8136(c.copy(nativeQualified8213 = false)).reason)
        val unread = CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = Double.NaN))
        assertEquals("an unread move is explored on known cost", CandidateAdmission8136.Terminal8136.ADMIT, unread.terminal)
        assertEquals("a stale price still defers", CandidateAdmission8136.Terminal8136.DEFER_DATA,
            CandidateAdmission8136.decide8136(c.copy(priceAgeMs = Long.MAX_VALUE)).terminal)
        assertEquals("SAFETY_REFUSED_8136", CandidateAdmission8136.decide8136(c.copy(safetyRefusal = "MINT_AUTH")).reason)
        assertTrue(src("engine/truth/Rehearsal8339.kt").contains("val rehearsal = paper || (EvidenceHold8336.enabled8336 && EvidenceHold8336.rehearsal8336)"))
    }

    @Test fun a_paper_fill_needs_a_price_another_source_agrees_with() {
        val t = com.lifecyclebot.engine.truth.PaperFillTruth8539
        assertTrue("3x off the only reference (Jimothy $4.62M vs $1.53M)", t.disagrees(3.0, listOf(1.0)))
        assertFalse(t.disagrees(1.5, listOf(1.0)))
        assertFalse("one agreeing reference suffices", t.disagrees(3.0, listOf(1.0, 2.9)))
        assertFalse("no reference: proceeds (counted)", t.disagrees(3.0, emptyList()))
        assertEquals(2.0, t.MAX_RATIO, 0.0)
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("PaperFillTruth8539.refusal(ts.mint, paperEntryMark7465.mark?.priceUsd?.value?.toDouble() ?: Double.NaN, now6616) != null"))
        assertTrue("the partial path guards a phantom gain too", ex.contains("if (paperPartialGainUncorroborated8539(ts, pos.entryPrice, actualPrice)) return false"))
    }

    @Test fun the_8536_live_doors_are_learnt_and_unlearnt_too() {
        val c = CandidateAdmission8136.Candidate8136(
            mint = "door-8539", lane = "MOONSHOT", selectedSetup = null, priceAgeMs = 500L,
            missingInputs = listOf("shape:INVALID"), expectedGrossPct = 40.0, costPct = 6.0,
            costSizeSol = 0.031, costBreakdown = "size=0.0310SOL known_liq_quote", genericScore = 20.0, genericFloor = 15.0,
            nativeQualified8213 = true, nativeSetup8213 = CandidateAdmission8136.RUNNER_FORMING_8260, live = true)
        assertEquals(CandidateAdmission8136.LIVE_NATIVE_OPINION_UNPROVEN_8536, CandidateAdmission8136.decide8136(c).reason)
        val demoted = CandidateAdmission8136.decide8136(c) { it != CandidateAdmission8136.LIVE_NATIVE_OPINION_UNPROVEN_8536 }
        assertEquals("a door its own record demotes stops binding", "NATIVE_QUALIFIED_ADMIT_8259", demoted.reason)
        assertTrue(demoted.trace.contains("liveNativeOpinionDoorDemoted8539"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("?.takeIf { Rehearsal8339.doorBindsNow8342(it, l) }  // V5.0.8539"))
    }
}
