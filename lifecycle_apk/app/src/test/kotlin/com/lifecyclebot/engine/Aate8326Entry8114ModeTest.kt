package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.truth.Entry8114Mode8326
import com.lifecyclebot.engine.truth.Entry8114Shadow8326
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveAdmissionProof8290
import com.lifecyclebot.engine.truth.MinPromotion8188
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8326 — Entry8114Mode8326 (owner: "Make 8114 live now"). With the mode ON a live meme entry is decided by the 8114
 * decision (Entry8114Gate8326): a gate added after 8114 does not refuse, hard safety / economics / route minimum still
 * do, the current full verdict is kept as the shadow and both are recorded. With the mode OFF nothing changes.
 */
class Aate8326Entry8114ModeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun restoreDefault() { Entry8114Mode8326.noteConfig8326(Entry8114Mode8326.DEFAULT_ON_8326) }

    @Test fun modeOnACandidateRefusedOnlyByAPostEntry8114GateIsAdmitted() {
        Entry8114Mode8326.noteConfig8326(true)
        val post8114 = listOf(
            "CANDIDATE_ADMISSION_8136_CELL_PROVEN_LOSING_8136",
            "CANDIDATE_ADMISSION_8136_STAGE_PROVEN_LOSING_8136",
            "CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136",
            "CANDIDATE_ADMISSION_8136_SETUP_RECORD_LOSING_8136",
            "CANDIDATE_ADMISSION_8136_SHEET_AVOID_8323",
            "CANDIDATE_ADMISSION_8136_LIVE_GENERIC_PRIOR_UNSUPPORTED_8238",
            "DATA_INCOMPLETE_DEFER_8136",
            "DATA_DEFER_FETCH_NEVER_COMPLETED_8204",
            "SETUP_LIVE_RECORD_NEGATIVE_8310",
            "TAIL_CELL_UNCONFIRMED_LOSING_LANE_8310",
        )
        for (r in post8114) {
            assertFalse(r, Entry8114Mode8326.hardSafety8326(r))
            // the 8114 decision admitted (null): the current refusal does not refuse
            assertNull(r, Entry8114Mode8326.finalVerdict8326(null, r))
        }
        assertEquals("ADMISSION8136_CELL_PROVEN_LOSING_8136", Entry8114Mode8326.gateKey8326("CANDIDATE_ADMISSION_8136_CELL_PROVEN_LOSING_8136"))
        assertEquals("SETUP_LIVE_RECORD_NEGATIVE_8310", Entry8114Mode8326.gateKey8326("SETUP_LIVE_RECORD_NEGATIVE_8310"))
        assertEquals("EDGE_7877", Entry8114Mode8326.gateKey8326("EDGE_7877_CELL_NEGATIVE_SHITCOIN"))
        // the bypass is counted per gate
        val before = Entry8114Mode8326.bypassCount8326("ADMISSION8136_TIMING_ADVERSE_8136")
        Entry8114Mode8326.noteBypass8326(Entry8114Mode8326.gateKey8326("CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136"), "SHITCOIN")
        assertEquals(before + 1, Entry8114Mode8326.bypassCount8326("ADMISSION8136_TIMING_ADVERSE_8136"))
        // the 8114 decision's own refusal still refuses, whatever the current verdict says
        assertEquals("LIVE_PROVEN_SETUP_ONLY_8059", Entry8114Mode8326.finalVerdict8326("LIVE_PROVEN_SETUP_ONLY_8059", null))
        assertEquals("CHASING_SPIKE_8065", Entry8114Mode8326.finalVerdict8326("CHASING_SPIKE_8065", "CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136"))
    }

    @Test fun hardSafetyAndEconomicRefusalsStillRefuse() {
        Entry8114Mode8326.noteConfig8326(true)
        val hard = listOf(
            "CANDIDATE_ADMISSION_8136_SAFETY_REFUSED_8136",
            "CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136",
            "CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136",
            "NET_EDGE_BELOW_2X_COST_8030",
            "MAYHEM_MODE_7943",
            "WALLET_BLIND_8074",
            "AT_LAUNCH_CAP_8047",
            "STAGE_RUG_PRONE_7928_SHITCOIN",
            "FREEZE_AUTHORITY_UNVERIFIED",
            "ADMISSION_COST_REQUOTE_FAILED_8209",
        )
        for (r in hard) {
            assertTrue(r, Entry8114Mode8326.hardSafety8326(r))
            assertEquals(r, Entry8114Mode8326.finalVerdict8326(null, r))
        }
        // an 8114 verdict that could not be computed fails closed
        assertTrue(Entry8114Mode8326.hardSafety8326(Entry8114Mode8326.EVAL_FAILED_8326))
        // the 8114 gate keeps the economics, Mayhem, dead-launch, blind-wallet and HARD_BLOCK doors
        val gate = src("engine/truth/Entry8114Gate8326.kt")
        for (s in listOf("NetEdge8030.entryRefusal8030(ts, lane)", "MayhemMode7943.liveRefusal(ts, nowMs)", "OnChainDepth8038.earlyLaunchRefusal8048(ts, nowMs)",
            "LiveBootRecovery8074.blind8074(nowMs)", "SafetyTier.HARD_BLOCK", "RebuyLockout8019.refusal8019(ts.mint, nowMs)")) assertTrue(s, gate.contains(s))
    }

    @Test fun routeMinimumAndNegativeEdgeAtTheMinimumStillBind() {
        Entry8114Mode8326.noteConfig8326(true)
        // an 8114-admitted candidate is promoted past the post-8114 promotion refusals...
        assertNull(Entry8114Mode8326.promotionInputFilter8326(true, "SHITCOIN", MinPromotion8188.REFUSED_LOSS_BUDGET_8188))
        assertNull(Entry8114Mode8326.promotionInputFilter8326(true, "SHITCOIN", MinPromotion8188.REFUSED_DRAWDOWN_8188))
        assertNull(Entry8114Mode8326.promotionInputFilter8326(true, "SHITCOIN", MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188))
        // ...but a measured negative edge at the promoted size still refuses, and a non-admitted candidate is unchanged
        assertEquals(MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188,
            Entry8114Mode8326.promotionInputFilter8326(true, "SHITCOIN", MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188))
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188,
            Entry8114Mode8326.promotionInputFilter8326(false, "SHITCOIN", MinPromotion8188.REFUSED_LOSS_BUDGET_8188))
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188,
            Entry8114Mode8326.promotionFilter8326("mint-never-admitted", "SHITCOIN", MinPromotion8188.REFUSED_LOSS_BUDGET_8188))
        assertTrue(Entry8114Mode8326.hardSafety8326("ROUTE_MIN_NOT_FUNDABLE"))
        // the route minimum itself is never lowered: promotion only lifts a sub-minimum order TO the minimum
        val sizer = src("v3/sizing/SmartSizerV3.kt")
        assertTrue(sizer.contains("SMART_SIZER_V3_SUB_ROUTABLE_REFUSED_7831"))
        assertTrue(sizer.contains("Entry8114Mode8326.admittedNow8326(candidate.mint, candidate.extraString(\"lane\"))"))
        val risk = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(risk.contains("?.let { Entry8114Mode8326.promotionInputFilter8326(i.entry8114Admitted8326, i.lane, it) }"))
        assertTrue(risk.contains("SizingNetEdge8153.promotionRefusal8153("))
        assertTrue(src("engine/truth/MinPromotion8188.kt").contains("val r = Entry8114Mode8326.promotionFilter8326(mint, lane, try {"))
    }

    @Test fun modeOffKeepsCurrentBehaviour() {
        Entry8114Mode8326.noteConfig8326(false)
        assertFalse(Entry8114Mode8326.enabled8326())
        assertFalse(Entry8114Mode8326.liveActive8326("SHITCOIN", false))
        assertFalse(Entry8114Mode8326.routes8326("MOONSHOT", false))
        assertFalse(Entry8114Mode8326.admittedNow8326("any-mint", "SHITCOIN"))
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188,
            Entry8114Mode8326.promotionFilter8326("any-mint", "SHITCOIN", MinPromotion8188.REFUSED_DRAWDOWN_8188))
        Entry8114Mode8326.noteConfig8326(true)
        // ON: live meme lanes only; paper and non-meme lanes never route
        assertTrue(Entry8114Mode8326.liveActive8326("SHITCOIN", false))
        for (l in listOf("MOONSHOT", "EXPRESS", "MANIPULATED", "PROJECT_SNIPER", "QUALITY", "BLUECHIP", "DIP_HUNTER", "TREASURY", "CASHGEN", "CYCLIC", "CORE"))
            assertTrue(l, Entry8114Mode8326.liveActive8326(l, false))
        assertFalse(Entry8114Mode8326.liveActive8326("SHITCOIN", true))
        assertFalse(Entry8114Mode8326.liveActive8326("PERPS", false))
        assertEquals(13, Entry8114Mode8326.LANES_8326.size)
        // not while the shadow (current) verdict or the 8114 verdict is being computed (no recursion)
        assertFalse(Entry8114Mode8326.inCurrent8326 { Entry8114Mode8326.routes8326("SHITCOIN", false) })
        assertFalse(Entry8114Mode8326.inLegacy8326 { Entry8114Mode8326.routes8326("SHITCOIN", false) })
        assertTrue(Entry8114Mode8326.routes8326("SHITCOIN", false))
        // the call site is one line, and the default is ON and persisted
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains(
            "if (Entry8114Mode8326.routes8326(lane, paper)) return Entry8114Mode8326.decide8326(ts, lane, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(BotConfig().entry8114ModeEnabled)
        val cfg = src("data/BotConfig.kt")
        assertTrue(cfg.contains("putBoolean(com.lifecyclebot.engine.truth.Entry8114Mode8326.SETTING_KEY_8326, cfg.entry8114ModeEnabled)"))
        assertTrue(cfg.contains("com.lifecyclebot.engine.truth.Entry8114Mode8326.noteConfig8326(it.entry8114ModeEnabled)"))
        assertTrue(src("ui/SettingsBottomSheet.kt").contains("entry8114ModeEnabled = switchEntry8114Mode.isChecked"))
        assertTrue(File("src/main/res/layout/dialog_settings.xml").readText().contains("@+id/switchEntry8114Mode"))
    }

    @Test fun legacyScopeReadsEvidenceThe8114Way() {
        assertFalse(Entry8114Mode8326.legacyScope8326())
        // current: a suspect record or a realised shortfall refuses the proof; 8114: mean - SE > 0 on the record
        assertFalse(LiveAdmissionProof8290.survives8290(40.0, 10.0, 3.0, true, 0.0))
        assertFalse(LiveAdmissionProof8290.survives8290(40.0, 10.0, 3.0, false, 8.0))
        assertTrue(Entry8114Mode8326.inLegacy8326 { LiveAdmissionProof8290.survives8290(40.0, 10.0, 3.0, true, 8.0) })
        assertEquals(0.0, Entry8114Mode8326.inLegacy8326 { LiveAdmissionProof8290.laneExtra8290("SHITCOIN") }, 1e-12)
        assertFalse(Entry8114Mode8326.legacyScope8326())
        // the 8114 raw cell read: mean = sum / n, population SE, never suspect
        val c = ForwardReturnLabeler7731.legacyCell8326("k", 4, 40.0, 500.0, 3, 1, 2, 0, 0.0)
        assertEquals(10.0, c.meanNet60Pct, 1e-9)
        assertEquals(Math.sqrt((500.0 / 4 - 100.0) / 4), c.stderr60Pct, 1e-9)
        assertEquals(0.75, c.winRate60, 1e-9)
        assertFalse(c.outlierSuspect8185)
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(labeler.contains("else if (Entry8114Mode8326.legacyScope8326()) legacyCell8326(key, n, sum, sumSq, wins, runners, t.lost, t.n240, t.sum240)"))
        val playbook = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(playbook.contains("(try { provenSetup8056(ts, laneRaw, nowMs)?.first } catch (_: Throwable) { null }) ?: classify(ts, canon(laneRaw), nowMs)"))
        assertTrue(playbook.contains("if (com.lifecyclebot.engine.truth.Entry8114Mode8326.legacyScope8326()) return raw"))
        val cortex = src("engine/cortex/Cortex7885.kt")
        val old = cortex.substringAfter("fun entryRefusal8114(").substringBefore("fun overrulesEdgeRefusal(")
        assertTrue(old.contains("board.refusalAuthority(a.lane, a.runnerLane, false) && consistent(a.lane)"))
        assertFalse(old.contains("refusalAuthority8269"))
        assertFalse(old.contains("CortexInversion8310"))
    }

    @Test fun fdgHelpersReadNoAdmissionInModeAndEvaluateIsUntouched() {
        val fdg = src("engine/FinalDecisionGate.kt")
        val evaluate = fdg.substringAfter("    fun evaluate(").substringBefore("\n    private fun resolveExecutableSize7835(")
        assertFalse(evaluate.contains("Entry8114Mode8326"))
        assertTrue(fdg.split("Entry8114Mode8326.admission8326(").size - 1 >= 4)
        assertTrue(fdg.contains("if (!com.lifecyclebot.engine.truth.Entry8114Mode8326.liveActiveNow8326(lane)) floorFromAdmission8136("))
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("if (Entry8114Mode8326.liveActive8326(lane, paper)) return null"))
        assertTrue(plan.contains("val specific8171 = if (Entry8114Mode8326.liveActive8326(lane, paper)) null"))
        // in mode no admission reaches the helpers; off, the same decision object passes through
        Entry8114Mode8326.noteConfig8326(false)
        assertNull(Entry8114Mode8326.admission8326("SHITCOIN", false, null))
    }

    @Test fun shadowRecordCapturesBothVerdicts() {
        val label = Entry8114Shadow8326.labelOf8326(null, "CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136")
        assertTrue(label.startsWith("8114:ADMIT"))
        assertTrue(label.contains("now:REFUSE(CANDIDATE_ADMISSION_8136_TIMING_ADVERSE_8136)"))
        assertEquals("8114:REFUSE(WATCH_FIRST_7994_THIN_CELL) now:ADMIT", Entry8114Shadow8326.labelOf8326("WATCH_FIRST_7994_THIN_CELL", null))
        assertNull(Entry8114Shadow8326.labelFor8326("never-seen-mint", "SHITCOIN"))
        assertEquals(listOf("buys8114", "onlyOld"), Entry8114Shadow8326.setsOf8326(true, false))
        assertEquals(listOf("buysNow", "onlyNew"), Entry8114Shadow8326.setsOf8326(false, true))
        assertEquals(listOf("buys8114", "buysNow", "both"), Entry8114Shadow8326.setsOf8326(true, true))
        assertTrue(Entry8114Shadow8326.setsOf8326(false, false).isEmpty())
        // forward net: observed marks only, net of the round trip quoted at first sight; an unknown mark is not a number
        assertEquals(44.0, Entry8114Shadow8326.forwardNetPct8326(1.0, 1.5, 6.0), 1e-9)
        assertTrue(Entry8114Shadow8326.forwardNetPct8326(1.0, Double.NaN, 6.0).isNaN())
        assertTrue(Entry8114Shadow8326.forwardNetPct8326(0.0, 1.0, 6.0).isNaN())
        assertEquals(60L * 60_000L, Entry8114Shadow8326.WINDOW_MS_8326)
        assertEquals(1.5, Entry8114Shadow8326.RUNNER_MULT_8326, 1e-12)
        val mode = src("engine/truth/Entry8114Mode8326.kt")
        assertTrue(mode.contains("Entry8114Shadow8326.record8326(ts, l, old.reason, now, final, nowMs)"))
        // the current verdict is computed first (it files its own assessment), the 8114 verdict second, the final filed last
        assertTrue(mode.indexOf("inCurrent8326 { LiveEdgeGate7877.liveRefusal(") < mode.indexOf("inLegacy8326 { Entry8114Gate8326.liveVerdict8114("))
        assertTrue(mode.indexOf("inLegacy8326 { Entry8114Gate8326.liveVerdict8114(") < mode.indexOf("fileFinal8326(ts, l, old, final, nowMs)"))
        assertTrue(src("engine/truth/TradePlan7739.kt").contains("Entry8114Mode8326.statusLine8326()"))
    }

    @Test fun anAdmissionObjectPassesThroughWhenTheModeIsOff() {
        Entry8114Mode8326.noteConfig8326(false)
        val d = com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136(
            com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "X", 5.0, emptyList(), "basis")
        assertSame(d, Entry8114Mode8326.admission8326("SHITCOIN", false, d))
        Entry8114Mode8326.noteConfig8326(true)
        assertNull(Entry8114Mode8326.admission8326("SHITCOIN", false, d))
        assertSame(d, Entry8114Mode8326.admission8326("SHITCOIN", true, d))
    }
}
