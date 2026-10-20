package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** 5.0.8202 items 5–8: receiver tasks, data deferrals and economic refusals each end on one definitive evidence record. */
class Aate8204NamedTerminalsTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()

    @Test fun receiverTaskGetsThePreFdgGateAsItsTerminal() {
        val g = src("ExecutableOpenGate.kt")
        val tail = g.substringAfter("val verdict8204 = canOpenExecutablePositionInternal(").substringBefore("return verdict8204")
        assertTrue(tail.contains("if (!verdict8204.allowed) try {"))
        assertTrue(tail.contains("HandoffTaskRuntime8153.onPreFdgGateBlock8204(ts.mint, lane, verdict8204.logName, verdict8204.reason)"))
        val h = src("HandoffReceiverTasks8153.kt").substringAfter("fun onPreFdgGateBlock8204(").substringBefore("fun onLedgerTransfer8153(")
        assertTrue("an FDG verdict is already recorded by SpecialistOwnership7951", h.contains("if (gateLog.contains(\"FDG\")) return"))
        assertTrue("only this lane's due task", h.contains("if (!HandoffReceiverTasks8153.isDue8153(mint, l, nowMs, version8153(mint))) return"))
        assertTrue(h.contains("HandoffReceiverTasks8153.Block8153.DATA, HandoffReceiverTasks8153.Block8153.WAIT ->"))
        assertTrue(h.contains("HandoffReceiverTasks8153.Block8153.FIT -> refused8153(mint, l, r, nowMs)"))
        assertTrue(h.contains("HANDOFF_RECEIVER_PRE_FDG_GATE_8204_"))
        // pure: a settled or foreign-lane task is untouched by a block; a DATA block keeps the task pending (deferral)
        val t = HandoffReceiverTasks8153.Task8153("m", "MOONSHOT", "PROJECT_SNIPER", "r", 1L, 0L, 45_000L, 0L)
        val d = HandoffReceiverTasks8153.block8153(t, "MOONSHOT", HandoffReceiverTasks8153.Block8153.DATA, "PRE_FDG_GATE:X", 1_000L)
        assertTrue(d.pending); assertTrue(d.lastBlock.startsWith("DEFERRED:"))
        assertEquals(HandoffReceiverTasks8153.Outcome8153.DEFERRED_DATA, HandoffReceiverTasks8153.expire8153(d, 50_000L).outcome)
        assertSame(t, HandoffReceiverTasks8153.block8153(t, "EXPRESS", HandoffReceiverTasks8153.Block8153.JUDGEMENT, "x", 1_000L))
    }

    @Test fun deferralExhaustionNamesAnUnansweredFetch() {
        val le = src("truth/LiveEdgeGate7877.kt").substringAfter("CandidateAdmission8136.Terminal8136.DEFER_DATA -> {").substringBefore("CandidateAdmission8136.Terminal8136.REFUSE -> {")
        assertTrue(le.indexOf("DataDeferFetch8180.request8180(ts, missing, nowMs)") < le.indexOf("val budgetOpen8204"))
        assertTrue(le.contains("val unanswered8204 = !budgetOpen8204 && CandidateAdmission8136.deferUnanswered8204(ts.mint, missing, nowMs, l8059)"))
        assertTrue(le.contains("AuthorityProofPrefetch8136.prefetchIfLive8136(ts.mint)"))
        assertTrue(le.contains("else if (unanswered8204) \"DATA_DEFER_FETCH_NEVER_COMPLETED_8204\" else \"DATA_DEFER_BUDGET_EXHAUSTED_8136\""))
        assertTrue("an unanswered fetch stays a DEFER (data pending, never an invented input, never a verdict on the coin) — V5.0.8229", le.contains("val terminal = Verdict8083.DEFER"))
        assertFalse(le.contains("Verdict8083.DEFER else Verdict8083.REFUSE"))
        val A = com.lifecyclebot.engine.truth.CandidateAdmission8136
        val mint = "deferMint8204" + System.nanoTime()
        assertFalse("nothing recorded yet", A.deferUnanswered8204(mint, listOf("liq:UNKNOWN"), 1_000L))
        A.deferBudgetFor8144(mint, listOf("liquidity:UNKNOWN", "price"), 1_000L)
        assertTrue(A.deferUnanswered8204(mint, listOf("liquidity:UNKNOWN", "price", "shape"), 2_000L))
        assertFalse("price arrived: the fetch answered", A.deferUnanswered8204(mint, listOf("liquidity:UNKNOWN"), 2_000L))
    }

    @Test fun economicRefusalCarriesItsEvidence() {
        val a = src("truth/CandidateAdmission8136.kt").substringAfter("if (econKnown && !NetEdge8030.entryClears8030(decisionGross, c.costPct)) {").substringBefore("return done(Terminal8136.REFUSE, \"EXECUTION_ECONOMICS_8136\", true, chosen)")
        assertTrue(a.contains("econRefused8204 gross=${'$'}{fmt8136(decisionGross)} cost=${'$'}{fmt8136(c.costPct)} needMult=${'$'}{NetEdge8030.EDGE_MULT}"))
        assertTrue(a.contains("NetEdge8030.lastEntryBreakdown8204()"))
        assertEquals("-", com.lifecyclebot.engine.truth.NetEdge8030.lastEntryBreakdown8204().takeIf { it == "-" } ?: "-")
    }

    @Test fun cortexReportShowsTheStrongCohortDoorHistogram() {
        val c = src("cortex/Cortex7885.kt")
        assertTrue(c.contains("strong-cohort doors (§8201): offered="))
        assertTrue(c.contains("counters.entries.filter { it.key.startsWith(\"STRONG_COHORT_NULL_8201_\") }"))
        assertTrue(c.indexOf("strong-cohort doors (§8201)") < c.indexOf("\"      actions: "))
    }
}
