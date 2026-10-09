package com.lifecyclebot.engine

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.7951 — SpecialistOwnership: one common conviction scale for every lane,
 * chart BUY as ownership evidence, capital-only refusals kept as owner + intent
 * demand, and named pre-intent refusals (diag 5.0.7949 INTENT_CHOKED lanes).
 */
class Aate7951SpecialistOwnershipTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { SpecialistOwnership7951.resetForTests7951() }
    @After fun tearDown() { SpecialistOwnership7951.resetForTests7951() }

    private fun opinion(lane: String, eligible: Boolean, score: Int, conf: Int) =
        SpecialistBrainBridge7542.Opinion(lane, eligible, score, conf, 0.0, "t", "NONE", "e", "x")

    @Test fun barRelativePutsTheBarAtFifty() {
        assertEquals(50.0, SpecialistOwnership7951.barRelative7951(15.0, 15.0), 1e-9)
        assertEquals(100.0, SpecialistOwnership7951.barRelative7951(100.0, 60.0), 1e-9)
        assertEquals(25.0, SpecialistOwnership7951.barRelative7951(30.0, 60.0), 1e-9)
        // Unknown bar keeps the raw value.
        assertEquals(42.0, SpecialistOwnership7951.barRelative7951(42.0, 0.0), 1e-9)
    }

    @Test fun lowBarLaneIsNoLongerOutRankedByAHighBarLanesWeakestPass() {
        // MOONSHOT passes at 30..48 raw, CASHGEN at 70..90 raw (5.0.7949: 38/41 vs 76).
        val moon = (0 until 20).map { 30.0 + it }
        val cash = (0 until 20).map { 70.0 + it }
        val moonBest = SpecialistOwnership7951.commonScale7951(49.0, moon)
        val cashWeakest = SpecialistOwnership7951.commonScale7951(70.0, cash)
        assertTrue("moon=$moonBest cash=$cashWeakest", moonBest > cashWeakest)
        // Same relative standing reads the same on the common scale.
        assertEquals(
            SpecialistOwnership7951.commonScale7951(40.0, moon),
            SpecialistOwnership7951.commonScale7951(80.0, cash), 1e-9,
        )
        // Before maturity the weakest value a lane has passed is its revealed bar.
        assertEquals(50.0, SpecialistOwnership7951.commonScale7951(33.0, listOf(33.0)), 1e-9)
    }

    @Test fun chartBuyIsStrongOwnershipEvidence() {
        assertEquals(55.0, SpecialistOwnership7951.chartLifted7951(55.0, chartBuy = false), 1e-9)
        assertEquals(80.0, SpecialistOwnership7951.chartLifted7951(55.0, chartBuy = true), 1e-9)
        assertEquals(85.0, SpecialistOwnership7951.chartLifted7951(70.0, chartBuy = true), 1e-9)
        assertEquals(100.0, SpecialistOwnership7951.chartLifted7951(95.0, chartBuy = true), 1e-9)
    }

    @Test fun everyEligibleLaneIsPlacedOnTheCommonScale() {
        val out = SpecialistOwnership7951.onCommonScale7951(
            "mint7951scale",
            mapOf(
                "MOONSHOT" to opinion("MOONSHOT", true, 38, 41),
                "CASHGEN" to opinion("CASHGEN", true, 76, 76),
                "QUALITY" to opinion("QUALITY", false, 90, 90),
            ),
        )
        // First observation of each lane sits exactly on its own revealed bar.
        assertEquals(50.0, out.getValue("MOONSHOT").ownershipConviction7948!!, 1e-9)
        assertEquals(50.0, out.getValue("CASHGEN").ownershipConviction7948!!, 1e-9)
        assertNull(out.getValue("QUALITY").ownershipConviction7948)
    }

    @Test fun authorizerKeepsTheNativeReadyConviction() {
        assertEquals(64.0, SpecialistOwnership7951.readyConviction7951(64.0, 90.0, emptyList(), chartBuy = false), 1e-9)
        val pop = (0 until 10).map { 40.0 + it }
        val scaled = SpecialistOwnership7951.readyConviction7951(null, 49.5, pop, chartBuy = false)
        assertTrue(scaled in 50.0..100.0)
        assertEquals(80.0, SpecialistOwnership7951.readyConviction7951(50.0, 0.0, emptyList(), chartBuy = true), 1e-9)
    }

    @Test fun capitalOnlyIsSizeNotExecutableWithAFreshCapitalRefusal() {
        assertTrue(SpecialistOwnership7951.isCapitalRefusal7951("CAPITAL_BELOW_MIN_EXECUTABLE_6490"))
        assertFalse(SpecialistOwnership7951.isCapitalRefusal7951("RISK_SIZE_BELOW_EXECUTABLE_MINIMUM_7835"))
        assertEquals(
            "CAPITAL_BELOW_MIN_EXECUTABLE_6490",
            SpecialistOwnership7951.capitalOnlyReason7951("SIZE_NOT_EXECUTABLE_7835", "CAPITAL_BELOW_MIN_EXECUTABLE_6490"),
        )
        assertNull(SpecialistOwnership7951.capitalOnlyReason7951("PLAYBOOK_NO_TRIGGER_7907_SHITCOIN", "CAPITAL_BELOW_MIN_EXECUTABLE_6490"))
        assertNull(SpecialistOwnership7951.capitalOnlyReason7951("SIZE_NOT_EXECUTABLE_7835", null))
    }

    @Test fun capitalDemandCountsDistinctCandidatesPerLaneInTheWindow() {
        val t0 = 1_000_000L
        SpecialistOwnership7951.recordCapitalDemand7951("SHITCOIN", "mA", t0)
        SpecialistOwnership7951.recordCapitalDemand7951("SHITCOIN", "mA", t0 + 1_000L)
        SpecialistOwnership7951.recordCapitalDemand7951("SHITCOIN", "mB", t0)
        SpecialistOwnership7951.recordCapitalDemand7951("MOON_SHOT", "mC", t0)
        // Pre-intent capital refusal is not demand by itself; post-intent is.
        SpecialistOwnership7951.onCapitalRefusal7951("QUALITY", "mD", "CAPITAL_BELOW_MIN_EXECUTABLE_6490", postIntent = false, nowMs = t0)
        SpecialistOwnership7951.onCapitalRefusal7951("CORE", "mE", "CAPITAL_BELOW_MIN_EXECUTABLE_6490", postIntent = true, nowMs = t0)
        val d = SpecialistOwnership7951.capitalDemand7951(t0 + 2_000L)
        assertEquals(2, d["SHITCOIN"])
        assertEquals(1, d["MOONSHOT"])
        assertEquals(1, d["CORE"])
        assertNull(d["QUALITY"])
        assertTrue(SpecialistOwnership7951.capitalDemand7951(t0 + 200_000L).isEmpty())
    }

    @Test fun fdgVerdictNamesThePreIntentRefusalAndKeepsCapitalDemand() {
        val now = System.currentTimeMillis()
        SpecialistOwnership7951.onFdgVerdict7951("mF", "SHITCOIN", 7951L, false, "PLAYBOOK_NO_TRIGGER_7907_SHITCOIN", now)
        assertTrue(SpecialistOwnership7951.preIntentRefusals7951("SHITCOIN").contains("FDG_PLAYBOOK_NO_TRIGGER_7907_SHITCOIN=1"))
        SpecialistOwnership7951.onCapitalRefusal7951("MOONSHOT", "mG", "CAPITAL_BELOW_MIN_EXECUTABLE_6490", postIntent = false, nowMs = now)
        SpecialistOwnership7951.onFdgVerdict7951("mG", "MOONSHOT", 7951L, false, "SIZE_NOT_EXECUTABLE_7835", now)
        assertEquals(1, SpecialistOwnership7951.capitalDemand7951(now)["MOONSHOT"])
        assertTrue(SpecialistOwnership7951.preIntentRefusals7951("MOONSHOT").contains("CAPITAL_ONLY_CAPITAL_BELOW_MIN_EXECUTABLE_6490=1"))
        // Allowed verdicts and non-specialist lanes are not refusals.
        SpecialistOwnership7951.onFdgVerdict7951("mH", "CORE", 7951L, true, null, now)
        SpecialistOwnership7951.onFdgVerdict7951("mH", "STANDARD", 7951L, false, "X", now)
        assertEquals("", SpecialistOwnership7951.preIntentRefusals7951("CORE"))
        SpecialistOwnership7951.notePreIntent7951("EXPRESS", "FDG_EDGE_VETO_ACTIVE:detail")
        assertEquals("FDG_EDGE_VETO_ACTIVE=1", SpecialistOwnership7951.preIntentRefusals7951("EXPRESS"))
    }

    @Test fun shownStatusNamesTheRefusalInsteadOfIntentChoked() {
        assertEquals("BUYER_DISABLED", SpecialistOwnership7951.shownStatus7951("INTENT_CHOKED", false, 0L, 5, ""))
        assertEquals("REFUSED_BEFORE_INTENT_7951", SpecialistOwnership7951.shownStatus7951("INTENT_CHOKED", true, 0L, 49, "FDG_X=1"))
        assertEquals("OBSERVING_NO_READY_CANDIDATE", SpecialistOwnership7951.shownStatus7951("INTENT_CHOKED", true, 0L, 0, ""))
        assertEquals("INTENT_CHOKED", SpecialistOwnership7951.shownStatus7951("INTENT_CHOKED", true, 0L, 3, ""))
        assertEquals("FDG_BLOCKED_ALL", SpecialistOwnership7951.shownStatus7951("FDG_BLOCKED_ALL", true, 4L, 3, "FDG_X=1"))
    }

    @Test fun productionPathsAreWired() {
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("SpecialistOwnership7951.onCommonScale7951(ts.mint,out.toMap())"))
        assertTrue(src("engine/TradeAuthorizer.kt").contains("SpecialistOwnership7951.authorizerReadyConviction7951(requestedBook.name, mint, candidateVersion7624, score, safeConfidence)"))
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.substringAfter("private fun rememberFdgVerdict(").substringBefore("fun invalidateCandidate6734").contains("SpecialistOwnership7951.onFdgVerdict7951("))
        assertTrue(fdg.contains("SpecialistOwnership7951.notePreIntent7951(lane, \"FDG_\$reason\")"))
        assertTrue(src("engine/truth/OrderSizeResolver6441.kt").contains("SpecialistOwnership7951.onCapitalRefusal7951(laneName, mint, reason, postIntent = causalEventId.isNotBlank())"))
        assertTrue(src("engine/BotService.kt").contains("SpecialistOwnership7951.noteReadyNotEvaluated7951(lane, ts.mint, primaryLane)"))
        val sheet = src("engine/ToolkitSignalSheet.kt")
        assertTrue(sheet.contains("capitalDemand7951=\${capitalDemand7951[lane] ?: 0}"))
        assertTrue(sheet.contains("preIntentRefusals7951=\${preIntent7951.ifBlank { \"NONE\" }}"))
        assertTrue(src("engine/Executor.kt").contains("capitalDemand7951=\${SpecialistOwnership7951.capitalDemand7951()}"))
    }
}
