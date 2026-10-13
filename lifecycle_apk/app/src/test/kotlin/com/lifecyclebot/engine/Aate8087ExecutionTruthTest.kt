package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Outcome8084
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.sell.TxResult8087
import com.lifecyclebot.engine.sell.TxState8087
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SignedTxLedger8085.State
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AATE 5.0.8087 — typed execution truth, catastrophic dispatch, unclassified cohorts, unique FDG fanout. */
class Aate8087ExecutionTruthTest {
    private val now = 1_800_000_000_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mints = mutableListOf<String>()
    private fun mint(tag: String) = ("M8087$tag" + System.nanoTime()).also { mints += it }

    @Before fun setUp() { SignedTxLedger8085.resetForTest(); CloseLease.emergencyHandoff8087 = null }
    @After fun tearDown() {
        mints.forEach { CloseLease.release(it, "TEST") }
        SignedTxLedger8085.resetForTest(); CloseLease.emergencyHandoff8087 = null; FdgReEvalThrottle.clear()
    }

    // P0.2 — a signature is identity, not success
    @Test fun unknownSignedSellIsNeverConfirmed() {
        val u = TxResult8087.unknown("SIG1", "MINT", "SELL", "RAYDIUM")
        assertEquals(TxState8087.SUBMITTED_UNKNOWN, u.state); assertFalse(u.confirmed); assertTrue(u.pendingFinality)
        assertEquals("SIG1", u.txId)
    }

    @Test fun afterSendReadsChainStateFromTheLedger() {
        SignedTxLedger8085.record("S_SIGNED", "B64", "BH", SignedTxLedger8085.Intent("M", "SELL", 5L, route = "PUMPPORTAL"), now)
        val r = TxResult8087.afterSend("S_SIGNED", "M", "SELL", "PUMPPORTAL", 5L)
        assertEquals(TxState8087.SUBMITTED_UNKNOWN, r.state); assertFalse(r.confirmed)
        SignedTxLedger8085.transition("S_SIGNED", State.SUBMIT_ATTEMPTED, nowMs = now + 1)
        SignedTxLedger8085.transition("S_SIGNED", State.ACCEPTED, acceptedBy = "helius_sender", nowMs = now + 2)
        assertEquals(TxState8087.RPC_ACCEPTED, TxResult8087.afterSend("S_SIGNED", "M", "SELL", "PUMPPORTAL").state)
        SignedTxLedger8085.transition("S_SIGNED", State.CONFIRMED, nowMs = now + 3)
        assertTrue(TxResult8087.afterSend("S_SIGNED", "M", "SELL", "PUMPPORTAL").confirmed)
        // an externally executed (RFQ) signature with no local row is acceptance, not chain truth
        assertEquals(TxState8087.RPC_ACCEPTED, TxResult8087.afterSend("NO_ROW", "M", "SELL", "JUPITER_ULTRA").state)
        assertEquals(TxState8087.CHAIN_FAILED, TxResult8087.of(State.FAILED_ON_CHAIN))
        assertEquals(TxState8087.EXPIRED_PROVEN, TxResult8087.of(State.EXPIRED_PROVEN))
        assertEquals(TxState8087.NOT_SUBMITTED, TxResult8087.of(State.SUBMIT_REJECTED))
    }

    @Test fun everySellRouteGatesConfirmedLabelsOnChainTruth() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("landed?.confirmed == true -> \"SELL_RACE_RAYDIUM_LANDED_8083\""))
        assertTrue(ex.contains("landed != null -> \"SELL_RACE_RAYDIUM_OUTCOME_UNKNOWN_8087\""))
        assertTrue(ex.contains("if (res8087.confirmed) {"))   // PumpPortal
        assertTrue(ex.contains("if (sent8087.confirmed) LiveTradeLogStore.Phase.SELL_CONFIRMED else LiveTradeLogStore.Phase.TX_SUBMIT_OUTCOME_UNKNOWN"))
        assertFalse(ex.contains("if (landed != null) \"SELL_RACE_RAYDIUM_LANDED_8083\""))
        assertTrue(ex.contains("return TxResult8087.unknown(sig, mint, \"SELL\", route)") || ex.contains("return com.lifecyclebot.engine.sell.TxResult8087.unknown(sig, mint, \"SELL\", route)"))
    }

    // P0.3 — a signed sell is never reported "no signature"; expiries are attributed
    @Test fun signedSellNeverFallsIntoNoSignature() {
        val ex = src("engine/Executor.kt")
        val adopt = ex.indexOf("SELL_NO_SIGNATURE_AVOIDED_SIGNED_EXISTS_8087")
        val noSig = ex.indexOf("\"NO_SIGNATURE: \${ts.symbol}: all providers exhausted")
        assertTrue(adopt in 1 until noSig)
    }

    @Test fun provenExpiryIsAttributedToRouteAndDelay() {
        SignedTxLedger8085.record("S_EXP", "B64", "BH", SignedTxLedger8085.Intent("M", "SELL", 1L, route = "JUPITER"), now)
        SignedTxLedger8085.transition("S_EXP", State.SUBMIT_ATTEMPTED, nowMs = now + 20_000L)
        SignedTxLedger8085.transition("S_EXP", State.EXPIRED_PROVEN, "blockhash_invalid", nowMs = now + 90_000L)
        assertTrue(SignedTxLedger8085.expiryLine8087().contains("JUPITER|STALE_AT_SUBMIT=1"))
        assertTrue(SignedTxLedger8085.statusLine(now + 90_000L).contains("expiries=JUPITER|STALE_AT_SUBMIT=1"))
    }

    // P0.1 — catastrophic exit coalesced onto a running attempt, handed off the moment it ends
    @Test fun catastropheBehindRunningAttemptIsHandedOffWithoutBackoff() {
        val m = mint("H")
        val catastrophe = "PROTECTIVE_EXIT_CATASTROPHE_6450_RISKCLOCK"
        assertTrue(ProtectiveExitClass7807.isEmergency(catastrophe))
        val handed = mutableListOf<Pair<String, String>>()
        CloseLease.emergencyHandoff8087 = { mm, r -> handed += mm to r }
        assertNotNull(CloseLease.acquire(m, "T", "TAKE_PROFIT_LADDER"))
        assertNull("in-flight attempt is never doubled", CloseLease.acquire(m, "T", catastrophe))
        assertTrue("ordinary attempt stands down before signing", CloseLease.preemptForEmergency8087(m, "TAKE_PROFIT_LADDER"))
        assertFalse("an emergency attempt is not preempted", CloseLease.preemptForEmergency8087(m, catastrophe))
        CloseLease.recordRetry(m, "PREEMPTED_BY_EMERGENCY_8087")
        assertEquals(1, handed.size); assertEquals(m, handed[0].first)
        assertEquals(0L, CloseLease.msUntilEligible(m))
        assertNotNull("the emergency gets the lease at once", CloseLease.acquire(m, "T", catastrophe))
    }

    @Test fun unresolvedSignedSellIsNotPreempted() {
        val m = mint("U")
        assertNotNull(CloseLease.acquire(m, "T", "TAKE_PROFIT_LADDER"))
        assertNull(CloseLease.acquire(m, "T", "PROTECTIVE_EXIT_CATASTROPHE_6450_RISKCLOCK"))
        SignedTxLedger8085.record("S_OPEN", "B64", "BH", SignedTxLedger8085.Intent(m, "SELL", 1L, route = "JUPITER"), System.currentTimeMillis())
        assertFalse(CloseLease.preemptForEmergency8087(m, "TAKE_PROFIT_LADDER"))
    }

    @Test fun broadcastSampleClosesAtRealSubmissionNotThePreSendLog() {
        assertFalse(src("engine/LiveTradeLogStore.kt").contains("ExitTelemetryStamper6732.noteBroadcast7807(mint)"))
        val led = src("network/SignedTxLedger8085.kt")
        assertTrue(led.contains("State.SUBMIT_ATTEMPTED -> {") && led.contains("ExitTelemetryStamper6732.noteBroadcast7807(mint)"))
        val st = src("engine/truth/ExitStageTiming7876.kt")
        assertTrue(st.contains("if (phase == \"TX_SUBMIT_ATTEMPTED\" && t.broadcastAtMs == 0L) {"))
        assertTrue(st.contains("queueCause["))
        assertTrue(src("engine/BotService.kt").contains("CloseLease.emergencyHandoff8087 = { mint8087, reason8087 ->"))
        assertTrue(src("engine/Executor.kt").contains("CloseLease.preemptForEmergency8087(ts.mint, reason)"))
    }

    // P1.2 — only a genuine no-match is NO_TRIGGER
    @Test fun incompleteDataIsNeverANoTriggerGrade() {
        val inc = LanePlaybook7907.decide8084("SHITCOIN", emptyList(), emptyList(), null, listOf("shape"), listOf("shape"), 1, now)
        assertEquals(Outcome8084.DATA_INCOMPLETE, inc.outcome)
        assertEquals(LanePlaybook7907.DATA_INCOMPLETE_8087, inc.tag); assertNull(inc.consumerTag8087); assertFalse(inc.noMatchCohort)
        val err = LanePlaybook7907.decide8084("SHITCOIN", emptyList(), listOf("X"), null, emptyList(), emptyList(), 1, now)
        assertEquals(LanePlaybook7907.CLASSIFIER_ERROR_8087, err.tag); assertNull(err.consumerTag8087)
        val none = LanePlaybook7907.decide8084("SHITCOIN", emptyList(), emptyList(), null, emptyList(), emptyList(), 1, now)
        assertEquals(LanePlaybook7907.NO_TRIGGER, none.tag); assertEquals(LanePlaybook7907.NO_TRIGGER, none.consumerTag8087); assertTrue(none.noMatchCohort)
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("classify8084(ts, laneRaw, nowMs)?.consumerTag8087"))
    }

    @Test fun missingInputsRefreshFromTheirOwnSource() {
        val d = src("engine/DataOrchestrator.kt")
        assertTrue(d.contains("refreshMissingInputs8087(mint, symbol, missing)"))
        assertTrue(d.contains("if (\"shape\" in keys) scope.launch {"))
        assertTrue(d.contains("if (keys.any { it in TAPE_INPUTS_8087 }) scope.launch {"))
    }

    // P1.4 — repeated evaluation of one candidate is not fanout
    @Test fun repeatedEvaluationIsNotCountedAsUniqueDecisions() {
        FdgReEvalThrottle.clear()
        repeat(5) { FdgReEvalThrottle.noteDecision8087("MINT_A", 7L, "SHITCOIN", "ev1") }
        FdgReEvalThrottle.noteDecision8087("MINT_A", 7L, "MOONSHOT", "ev1")
        FdgReEvalThrottle.noteDecision8087("MINT_A", 7L, "SHITCOIN", "ev2")
        assertEquals(3L, FdgReEvalThrottle.uniqueDecisions8087())
        assertTrue(FdgReEvalThrottle.fanoutLine8087().contains("repeatSameEvidence=4"))
        assertTrue(src("engine/InvariantGuardian.kt").contains("val fdgDecisions = if (unique8087 > 0L) unique8087 else fdgGateEffective8087"))
    }

    // P0.4 — quantity skew per position lineage
    private fun buy(m: String, pid: String, q: Double, ts: Long = 1_000L) =
        Trade(side = "BUY", mode = "live", sol = 0.1, price = 1.0, ts = ts, mint = m, entryQtyToken = q, entryCostSol = 0.1, positionId = pid)
    private fun sell(m: String, pid: String, q: Double, ts: Long = 2_000L, reason: String = "TP") =
        Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = ts, mint = m, soldQtyToken = q, reason = reason, positionId = pid)

    @Test fun twoPositionsOnOneMintAreNotOneSkew() {
        val r = ForensicReconciler6377.qtySkew8087(
            listOf(buy("M", "P1", 60_000.0), buy("M", "P2", 25_000.0)),
            listOf(sell("M", "P1", 60_000.0), sell("M", "P2", 25_000.0))) { null }
        assertTrue(r.violators.isEmpty()); assertEquals(2, r.lineages)
    }

    @Test fun evictedBuyIsCheckedAgainstCanonicalOrReportedUnverifiable() {
        // P1's BUY left the bounded ring: canonical original qty answers it
        val ok = ForensicReconciler6377.qtySkew8087(listOf(buy("M", "P2", 25_000.0)),
            listOf(sell("M", "P1", 60_000.0), sell("M", "P2", 25_000.0))) { pid -> if (pid == "P1") 60_000.0 else null }
        assertTrue(ok.violators.isEmpty())
        val unknown = ForensicReconciler6377.qtySkew8087(emptyList(), listOf(sell("M", "P1", 60_000.0))) { null }
        assertTrue(unknown.violators.isEmpty()); assertEquals(1, unknown.unverifiable); assertTrue(unknown.violatorMints.isEmpty())
    }

    @Test fun realOverSellDuplicateCloseAndRecoveredInventoryAreDistinct() {
        val over = ForensicReconciler6377.qtySkew8087(listOf(buy("M", "P1", 1_000.0)), listOf(sell("M", "P1", 5_000.0))) { null }
        assertEquals(listOf("M"), over.violatorMints)
        val dup = ForensicReconciler6377.qtySkew8087(listOf(buy("M", "P1", 1_000.0)),
            listOf(sell("M", "P1", 500.0), sell("M", "P1", 500.0, ts = 3_000L))) { null }
        assertEquals(1, dup.duplicateCloses.size); assertEquals(listOf("M"), dup.violatorMints)
        val rec = ForensicReconciler6377.qtySkew8087(emptyList(), listOf(sell("M", "", 9_000.0, reason = "RECOVERED_WALLET_ORPHAN"))) { null }
        assertEquals(1, rec.recovered); assertTrue(rec.violators.isEmpty())
    }

    // ── P1.3 — runner-lane inversion is tail-aware ──
    private fun stat8087(n: Int, mean: Double, sd: Double, runnerEvery: Int = 0): com.lifecyclebot.engine.cortex.CortexLedger7885.Stat {
        val s = com.lifecyclebot.engine.cortex.CortexLedger7885.Stat()
        repeat(n) { i -> s.add(mean + if (i % 2 == 0) sd else -sd, runnerEvery > 0 && i % runnerEvery == 0) }
        return s
    }

    @org.junit.Test fun runner_lane_strong_that_catches_more_runners_is_not_inverted() {
        val strong = stat8087(40, -8.0, 2.0, runnerEvery = 5)   // 20% runners
        val neutral = stat8087(20, 6.0, 2.0, runnerEvery = 20)  // 5% runners
        val sb = com.lifecyclebot.engine.cortex.CortexScoreboard7885
        org.junit.Assert.assertTrue(sb.invertedProven7948(strong, neutral))                    // generic lane: inverted
        org.junit.Assert.assertFalse(sb.invertedProven7948(strong, neutral, runnerLane = true)) // runner lane: tail wins
        val noTail = stat8087(40, -8.0, 2.0)
        org.junit.Assert.assertTrue(sb.invertedProven7948(noTail, neutral, runnerLane = true))  // no tail: still inverted
    }
}
