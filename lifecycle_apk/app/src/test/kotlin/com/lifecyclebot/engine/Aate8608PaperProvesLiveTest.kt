package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8608 — live trades what the paper book proves: a lane paper has PROVEN losing does not spend the live wallet. */
class Aate8608PaperProvesLiveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aLosingPaperPriorPreservesTheCandidateVerdictAndEconomics() {
        for (terminal in Terminal8136.values()) {
            val original = Decision8136(terminal, "candidate_reason", true, "BASE_BREAKOUT_FLOW", 8.0, emptyList(), costPct = 3.0)
            val result = LaneAuthority8577.withPaperPrior(original, true)
            org.junit.Assert.assertEquals(original, result.copy(trace = emptyList()))
            assertTrue(result.trace.any { it.startsWith("paperLanePrior=") })
        }
    }

    @Test fun paperLaneLossesRemainMeasurableButDoNotOwnCandidateAdmission() {
        assertTrue("01:42 paper EXPRESS n44 -3.5+-2.6 PF0.54", MemePaperTrader8544.provenLosing8608(44, -3.5, 2.6, 0.54))
        assertTrue("01:42 paper MOONSHOT n40 -4.1+-2.7 PF0.48", MemePaperTrader8544.provenLosing8608(40, -4.1, 2.7, 0.48))
        assertFalse("paper SHITCOIN n33 +29.7 PF3.20 stays live", MemePaperTrader8544.provenLosing8608(33, 29.7, 29.7, 3.20))
        assertFalse("too few closes to prove anything", MemePaperTrader8544.provenLosing8608(25, -6.1, 1.3, 0.08))
        assertFalse("a loss inside one standard error is not proven", MemePaperTrader8544.provenLosing8608(60, -1.0, 2.0, 0.9))
        assertFalse(MemePaperTrader8544.provenLosing8608(60, -3.0, Double.NaN, 0.5))
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(a.contains("MemePaperTrader8544.laneProvenLosing8608(c.lane)"))
        assertFalse("a paper aggregate is not a candidate veto", a.contains("\"PAPER_LANE_PROVEN_LOSING\","))
        assertFalse(LaneAuthority8577.binding8577(LaneAuthority8577.PAPER_LOSING_8608))
        // the paper book is still fed the candidate, so a demoted lane can earn its way back
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("MemePaperTrader8544.consider8544(ts, candidate, nowMs)"))
    }

    // V5.0.8609 — one measured market edge for every lane: live buys only the paying coin state.
    @Test fun liveBuysOnlyThePayingCoinState() {
        for (lane in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "QUALITY", "BLUECHIP", "TREASURY", "CASHGEN", "CORE", "CYCLIC", "DIP_HUNTER"))
            assertTrue(lane, LaneAuthority8577.coinStatePays8609(lane, "EXPANDING"))
        for (p in listOf("MATURE", "POST_PUMP_FADE", "IGNITION", "UNKNOWN_AGE", "", null))
            assertFalse("$p", LaneAuthority8577.coinStatePays8609("SHITCOIN", p))
        assertTrue("the sniper keeps its proven pre-ignition entry", LaneAuthority8577.coinStatePays8609("PROJECT_SNIPER", "PRE_IGNITION"))
        assertFalse(LaneAuthority8577.coinStatePays8609("MOONSHOT", "PRE_IGNITION"))
        assertTrue(LaneAuthority8577.binding8577(LaneAuthority8577.COIN_STATE_8609))
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertFalse("V5.0.8687 — a paper lane record no longer opens the live coin-state door", a.contains("laneProven8572"))
        assertTrue("only a final ADMIT is turned away; data deferrals stay", a.contains("if (!enabled8577 || !c.live || !r.admitted) return r"))
    }

    // V5.0.8687 — a paper winner no longer leads live (8616 is gone): paper SHITCOIN led at +29.7 % PF3.20 and live
    // followed it into -68.6 % / -75.3 % closes; its exits are not live's.
    @Test fun aPaperWinnerNoLongerLeadsLive() {
        assertFalse(src("engine/truth/LaneAuthority8577.kt").contains("laneLeads8616"))
        assertFalse(src("engine/FinalDecisionGate.kt").contains("MemePaperTrader8544.laneLeads8616"))
        assertFalse(src("engine/truth/MemePaperTrader8544.kt").contains("fun leads8616("))
    }

    // V5.0.8620 — the lane-record (8608) and coin-state / pocket (8609 / 8610) verdicts are report-only: traced, never a refusal.
    @Test fun theLearnedLiveFiltersAreReportOnly() {
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(a.contains("return r.copy(trace = r.trace + \"advisory8620=\$why coinState8609="))
        assertFalse(a.contains("return r.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = why, specific = true,"))
        // V5.0.8622 — the lane-record rule alone is re-armed; the coin-state / pocket rules stay report-only.
        assertFalse(a.contains("return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = PAPER_LOSING_8608"))
    }

    @Test fun aYoungChartCanUseLaneAdmissionButAnObservedFailureNeedsSpecificEvidence() {
        val planner = com.lifecyclebot.engine.truth.TradePlan7739
        assertTrue(planner.optionalChartForAdmit(false, "TOO_FEW_BARS"))
        for (reason in listOf("SELL_DOMINANT_TAPE", "STILL_FALLING", "CHASING_THE_HIGH", "PULLBACK_TOO_DEEP")) {
            assertFalse(reason, planner.optionalChartForAdmit(false, reason))
            assertTrue(reason, planner.optionalChartForAdmit(true, reason))
        }
        val p = src("engine/truth/TradePlan7739.kt")
        assertTrue(p.contains("val evidenceAdmit8418 = specific8171?.admitted == true ||"))
        assertFalse(p.contains("if (specific8171?.admitted == true && specific8171.specific) {"))
    }
}
