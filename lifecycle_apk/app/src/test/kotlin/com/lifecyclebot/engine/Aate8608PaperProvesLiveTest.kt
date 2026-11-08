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

    @Test fun aMeasuredCoinSetupBeatsTheLosingLanePriorWithoutExemptingGenericBuys() {
        fun decision(reason: String, specific: Boolean, net: Double, cost: Double = 3.0) =
            Decision8136(Terminal8136.ADMIT, reason, specific, "BASE_BREAKOUT_FLOW", net, emptyList(), costPct = cost)
        assertTrue(LaneAuthority8577.specificBeatsLanePrior8643(decision("SPECIFIC_EDGE_PROVEN_8136", true, 8.0)))
        assertTrue(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("EDGE_MODEL_PROVEN_8537", true, 4.0)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("NATIVE_QUALIFIED_ADMIT_8259", false, 8.0)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("GENERIC_PRIOR_CLEARS_8136", false, 8.0)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("SPECIFIC_EDGE_PROVEN_8136", true, 0.0)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("SPECIFIC_EDGE_PROVEN_8136", true, -2.0)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("SPECIFIC_EDGE_PROVEN_8136", true, 8.0, Double.NaN)))
        assertFalse(LaneAuthority8577.specificBeatsLanePrior8643(
            decision("SPECIFIC_EDGE_PROVEN_8136", true, 8.0).copy(terminal = Terminal8136.REFUSE)))
    }

    @Test fun aProvenLosingPaperLaneIsKeptOutOfLive() {
        assertTrue("01:42 paper EXPRESS n44 -3.5+-2.6 PF0.54", MemePaperTrader8544.provenLosing8608(44, -3.5, 2.6, 0.54))
        assertTrue("01:42 paper MOONSHOT n40 -4.1+-2.7 PF0.48", MemePaperTrader8544.provenLosing8608(40, -4.1, 2.7, 0.48))
        assertFalse("paper SHITCOIN n33 +29.7 PF3.20 stays live", MemePaperTrader8544.provenLosing8608(33, 29.7, 29.7, 3.20))
        assertFalse("too few closes to prove anything", MemePaperTrader8544.provenLosing8608(25, -6.1, 1.3, 0.08))
        assertFalse("a loss inside one standard error is not proven", MemePaperTrader8544.provenLosing8608(60, -1.0, 2.0, 0.9))
        assertFalse(MemePaperTrader8544.provenLosing8608(60, -3.0, Double.NaN, 0.5))
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(a.contains("MemePaperTrader8544.laneProvenLosing8608(c.lane)"))
        assertTrue("the refusal binds at the live gate", a.contains("\"PAPER_LANE_PROVEN_LOSING\","))
        assertTrue(LaneAuthority8577.binding8577(LaneAuthority8577.PAPER_LOSING_8608))
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
        assertTrue("a lane proven in paper trades what paper trades", a.contains("if (try { MemePaperTrader8544.laneProven8572(l) } catch (_: Throwable) { false }) return null"))
        assertTrue("only a final ADMIT is turned away; data deferrals stay", a.contains("if (!enabled8577 || !c.live || !r.admitted) return r"))
    }

    // V5.0.8616 — a runner-shaped paper winner leads live: positive mean and PF >= 1.5 over 30+ closes.
    @Test fun aPaperWinnerLeadsLive() {
        assertTrue("03:50 paper SHITCOIN n39 +20.0 % PF2.21", MemePaperTrader8544.leads8616(39, 20.0, 2.21))
        assertFalse("paper MOONSHOT n82 -2.1 % PF0.71", MemePaperTrader8544.leads8616(82, -2.1, 0.71))
        assertFalse("too few closes", MemePaperTrader8544.leads8616(20, 11.5, 1.83))
        assertFalse("positive but a weak profit factor", MemePaperTrader8544.leads8616(60, 0.5, 1.1))
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(a.contains("if (try { MemePaperTrader8544.laneLeads8616(l) } catch (_: Throwable) { false }) return null"))
    }

    // V5.0.8620 — the lane-record (8608) and coin-state / pocket (8609 / 8610) verdicts are report-only: traced, never a refusal.
    @Test fun theLearnedLiveFiltersAreReportOnly() {
        val a = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(a.contains("return r.copy(trace = r.trace + \"advisory8620=\$why coinState8609="))
        assertFalse(a.contains("return r.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = why, specific = true,"))
        // V5.0.8622 — the lane-record rule alone is re-armed; the coin-state / pocket rules stay report-only.
        assertTrue(a.contains("return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = PAPER_LOSING_8608"))
    }

    @Test fun anyAdmitPlansInsteadOfWaitingForAChartSetup() {
        val p = src("engine/truth/TradePlan7739.kt")
        assertTrue(p.contains("val evidenceAdmit8418 = specific8171?.admitted == true ||"))
        assertFalse(p.contains("if (specific8171?.admitted == true && specific8171.specific) {"))
    }
}
