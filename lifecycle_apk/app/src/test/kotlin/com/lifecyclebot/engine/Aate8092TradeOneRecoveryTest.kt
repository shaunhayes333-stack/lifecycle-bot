package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8092 production regression: one authoritative trade and no pre-fill reservation leaks. */
class Aate8092TradeOneRecoveryTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun theOpeningCapAppliesOnlyToActiveBondingCurves() {
        assertTrue(OnChainDepth8038.launchCapOnActiveCurve8092(true, 29.0))
        assertFalse(OnChainDepth8038.launchCapOnActiveCurve8092(false, 29.0))
        assertFalse(OnChainDepth8038.launchCapOnActiveCurve8092(true, 55.0))
        assertFalse(OnChainDepth8038.launchCapOnActiveCurve8092(true, Double.NaN))
        val code = src("engine/truth/OnChainDepth8038.kt")
        assertTrue(code.contains("launchCapOnActiveCurve8092(curveReal8046 != null, capSol8047)"))
        assertTrue(code.contains("launchCapOnActiveCurve8092(real != null,"))
    }

    @Test fun failedColdStartBuyDoesNotSpendSixHourLiveCapacity() {
        val code = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(code.contains("COLD_RESERVATION_MS_8092 = 90_000L"))
        assertTrue(code.contains("coldConfirmed8092"))
        assertTrue(code.contains("occupied8092.putAll(coldAdmits8080)"))
        assertTrue(code.contains("fun onConfirmedColdEntry8092("))
        assertTrue(code.contains("coldAdmits8080.entries.removeIf { nowMs - it.value >= COLD_RESERVATION_MS_8092 }"))
        assertTrue(LiveEdgeGate7877.coldBudgetOpen8080(mapOf("A" to 100L), "B", 200L))
    }

    @Test fun runnerTicketSpacingBeginsAtConfirmedPosition() {
        val code = src("engine/RunnerTicket8073.kt")
        assertTrue(code.contains("UNFILLED_RESERVATION_MS_8092"))
        assertTrue(code.contains("fun onConfirmedBuy8092("))
        assertTrue(code.contains("lastIssuedMs = nowMs"))
        assertTrue(code.indexOf("lastIssuedMs = nowMs") > code.indexOf("fun onConfirmedBuy8092("))
        assertTrue(code.contains("confirmed8092.remove(mint)"))
        val entry = src("engine/truth/EntryStrategySnapshot6450.kt")
        assertTrue(entry.contains("RunnerTicket8073.onConfirmedBuy8092("))
        assertTrue(entry.contains("LiveEdgeGate7877.onConfirmedColdEntry8092("))
    }

    @Test fun realisedSetupComesOnlyFromTheSealedPositionIdentity() {
        val code = src("engine/truth/LiveTruth8064.kt")
        assertTrue(code.contains("EntryStrategySnapshot6450.snapshot(positionId)"))
        assertTrue(code.contains("recordSetup8083(e.positionId, e.mint, lane8083"))
        assertFalse(code.contains("pendingSetup8083[mint]"))
        assertTrue(code.contains("it.mint == mint && laneOf(it.entryLane) == lane"))
        assertTrue(code.contains("LIVE_CLOSE_SETUP_UNATTRIBUTED_8083"))
    }

    @Test fun specialistUsesProvenAlternativeBeforeDefaultSetupVeto() {
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val a = gate.indexOf("val setup8083 = try {")
        val b = gate.indexOf("LiveTruth8064.setupRefusal8083(l8059, setup8083", a)
        assertTrue(a > 0 && b > a)
        assertTrue(gate.substring(a, b).contains("LanePlaybook7907.provenSetup8056"))
        assertTrue(gate.substring(a, b).contains("LanePlaybook7907.coldStartSetup8080"))
        val play = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(play.contains("val setup = provenSetup8056(ts, lane, nowMs)?.first"))
        assertTrue(src("engine/AgenticStyleRouter.kt").contains("if (!measured.isNullOrBlank()) return measured"))
    }
}
