package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8538 — owner: "apk 8069-8114 is the profitable range". Its live door admitted on ANY measured positive edge (proven
 * setup / cell / kind / lane trigger, a runner cell beating its lane, a promoted specialist, an expert copy, a confirmed run,
 * a runner ticket, the chart reader's BUY, a Cortex STRONG read) and its live runners rode the runner ladder from entry.
 * 8536 narrowed the live proof to four of those and 8337 sold live runners on the scalp ladder; both are restored.
 */
class Aate8538Edge8114RestoredTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun forming(lane: String, edge: String?) = CandidateAdmission8136.Candidate8136(
        mint = "forming-8538-$lane", lane = lane, selectedSetup = null, priceAgeMs = 500L,
        missingInputs = listOf("shape:INVALID"), expectedGrossPct = 40.0, costPct = 6.0,
        costSizeSol = 0.031, costBreakdown = "size=0.0310SOL known_liq_quote", genericScore = 20.0, genericFloor = 15.0,
        nativeQualified8213 = true, nativeSetup8213 = CandidateAdmission8136.RUNNER_FORMING_8260, live = true, liveEdge8114 = edge,
    )

    @Test fun a_live_lane_opinion_carrying_an_8114_edge_admits_and_alone_it_still_refuses() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS")) {
            val alone = CandidateAdmission8136.decide8136(forming(lane, null))
            assertEquals(lane, CandidateAdmission8136.LIVE_NATIVE_OPINION_UNPROVEN_8536, alone.reason)
            val carried = CandidateAdmission8136.decide8136(forming(lane, "RUNNER_FORMING"))
            assertEquals(lane, CandidateAdmission8136.Terminal8136.ADMIT, carried.terminal)
            assertEquals(lane, "NATIVE_QUALIFIED_ADMIT_8259", carried.reason)
            assertTrue(lane, carried.trace.any { it == "liveNativeCarriedBy8538=RUNNER_FORMING" })
        }
    }

    @Test fun a_live_generic_prior_carrying_an_8114_edge_is_not_refused_as_unsupported() {
        val base = CandidateAdmission8136.Candidate8136("g8538", "SHITCOIN", null, expectedGrossPct = 20.0, costPct = 4.5,
            genericScore = 80.0, genericFloor = 15.0, live = true)
        assertEquals("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", CandidateAdmission8136.decide8136(base).reason)
        assertEquals("GENERIC_PRIOR_CLEARS_8136", CandidateAdmission8136.decide8136(base.copy(liveEdge8114 = "CHART_BUY")).reason)
        assertEquals("paper is unchanged", "GENERIC_PRIOR_CLEARS_8136", CandidateAdmission8136.decide8136(base.copy(live = false)).reason)
    }

    @Test fun the_witness_and_the_live_backstop_read_every_8114_edge() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("liveEdge8114 = if (live8290) LiveEdgeGate7877.edge8114Live8538(ts, lane, nowMs) else null,"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val w = gate.substringAfter("fun edge8114Live8538(").substringBefore("/** Pure (V5.0.8536)")
        for (edge in listOf("provenSetup8056(", "measuredEdge8060(", "cellOrKindProven8094(", "runnerForming8113(",
                "RunnerTicket8073.wouldAdmit8538(", "shadowAuditAdmits8113(", "chartAdmits7950(", "Cortex7885.overrulesEdgeRefusal("))
            assertTrue(edge, w.contains(edge))
        val backstop = gate.substringAfter("private fun unprovenLive8536(").substringBefore("fun edge8114Live8538(")
        assertTrue(backstop.contains("edge8114Live8538(ts, l, nowMs, ticket = false)?.let { return null }"))
        assertTrue("the ticket is issued last, only when no other edge carried the coin",
            backstop.indexOf("RunnerTicket8073.admits8073(ts, nowMs)") > backstop.indexOf("edge8114Live8538(ts, l, nowMs, ticket = false)"))
        val ticket = src("engine/RunnerTicket8073.kt")
        assertTrue(ticket.contains("fun wouldAdmit8538(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean = admitsOrIssues8538(ts, nowMs, issue = false)"))
        assertTrue("a check never issues", ticket.contains("if (!issue) return true\n        tickets[ts.mint] = nowMs"))
    }

    @Test fun live_runners_in_born_runner_lanes_ride_the_runner_ladder_from_entry() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "PROJECT_SNIPER")) assertTrue(lane, com.lifecyclebot.engine.RunnerGrab7967.bornRunnerLadder8538(lane))
        for (lane in listOf("CASHGEN", "TREASURY", "QUALITY")) assertFalse(lane, com.lifecyclebot.engine.RunnerGrab7967.bornRunnerLadder8538(lane))
        val ts = com.lifecyclebot.data.TokenState(mint = "ladder8538", position = com.lifecyclebot.data.Position(qtyToken = 1_000_000.0,
            entryPrice = 1.0, entryTime = System.currentTimeMillis() - 60_000L, tradingMode = "MOONSHOT", isPaperPosition = false,
            peakGainPct = 0.0, entryPolicySnapshot = "style=BREAKOUT_RUNNER;entryTactic=BREAKOUT"))
        assertEquals(com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967, com.lifecyclebot.engine.RunnerGrab7967.tiersFor7967(ts))
        assertEquals("first rung +100 %", 100.0, com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967.first().first, 0.0)
        assertTrue(src("engine/RunnerGrab7967.kt").contains("!bornRunnerLadder8538(ts.position.tradingMode) && scalpTiersUntilConfirmed8337("))
    }
}
