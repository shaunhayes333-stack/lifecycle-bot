package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8608 — live trades what the paper book proves: a lane paper has PROVEN losing does not spend the live wallet. */
class Aate8608PaperProvesLiveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

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
        assertTrue("a lane proven in paper trades what paper trades", a.contains("MemePaperTrader8544.laneProven8572(l)) } catch (_: Throwable) { false }) return null"))
        assertTrue("only a final ADMIT is turned away; data deferrals stay", a.contains("if (!enabled8577 || !c.live || !r.admitted) return r"))
    }

    @Test fun anyAdmitPlansInsteadOfWaitingForAChartSetup() {
        val p = src("engine/truth/TradePlan7739.kt")
        assertTrue(p.contains("val evidenceAdmit8418 = specific8171?.admitted == true ||"))
        assertFalse(p.contains("if (specific8171?.admitted == true && specific8171.specific) {"))
    }
}
