package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 5.0.8202 item 9 — ONE qualified BUY candidate walks the real authorities, in order, as behaviour (no source strings):
 * hand-off receiver task -> FDG verdict settles the task -> one execution lease (duplicate owner refused) -> one sealed
 * ticket (consumed once) -> single-sell/buy state machine -> simulated provider finality checked by the settlement
 * invariants -> canonical lot -> strategy-truth terminal row counted once. Negative cases: duplicate owner, stale candidate
 * version, finality failure (debit mismatch / wrong mint), PnL (received-raw) divergence quarantined, closed lot not
 * re-openable without a new verified buy signature, one terminal row never double-counted.
 */
class Aate8206ExecutionChainFixtureTest {
    private val mint = "ChainMint8206" + System.nanoTime()
    private val lane = "MOONSHOT"
    private val version = 7L
    private val t0 = 1_000_000L

    @Before fun clean() {
        CanonicalReceiptStore6394.clearForTest(); PositionLotLedger6394.clearForTest()
        SingleSellStateMachine6394.clearForTest(); ExecutionTicketAuthority6394.clearForTest()
        ExecutionAttemptLease.forceReleaseForMint(mint, "test_cleanup")
    }

    private fun receipt(sig: String, m: String = mint, postOwner: Long = 9_800_000L, received: Long = 1_000L) = CanonicalExecutionReceipt6394(
        executionId = "E_$sig", decisionId = "D_$sig", positionId = "P_$m", lotId = "L_$sig",
        runtimeGeneration = 1L, mint = m, symbol = "SYM", lane = lane, side = "BUY", provider = "JUPITER", route = "r",
        requestedRawAmount = BigInteger.valueOf(1_000L), actualConsumedRawAmount = BigInteger.valueOf(1_000L),
        actualReceivedRawAmount = BigInteger.valueOf(received), tokenDecimals = 6,
        preTokenRaw = BigInteger.ZERO, postTokenRaw = BigInteger.valueOf(received),
        preOwnerLamports = BigInteger.valueOf(10_000_000L), postOwnerLamports = BigInteger.valueOf(postOwner),
        principalLamports = BigInteger.valueOf(180_000L), grossProceedsLamports = BigInteger.ZERO,
        networkFeeLamports = BigInteger.valueOf(5_000L), priorityFeeLamports = BigInteger.valueOf(10_000L),
        jitoTipLamports = BigInteger.valueOf(5_000L), rentCreatedLamports = BigInteger.ZERO, rentRefundedLamports = BigInteger.ZERO,
        appFeeAccruedLamports = BigInteger.ZERO, appFeePaidLamports = BigInteger.ZERO, netUserProceedsLamports = BigInteger.ZERO,
        signature = sig, slot = 100L, blockTime = 200L, confirmationStatus = "FINALIZED", settlementStatus = "SETTLED",
        proofSource = "TX_META", createdAt = t0, finalizedAt = t0 + 5_000L,
    )

    @Test fun qualifiedBuyReachesCanonicalLotAndOneLearnerRow() {
        // 1. hand-off: PROJECT_SNIPER passes the coin to MOONSHOT; the receiver task is pending and due.
        val task = HandoffReceiverTasks8153.open8153(null, mint, lane, "PROJECT_SNIPER", "STAGE_LATE", version, t0, t0)!!
        assertTrue(task.pending)
        assertTrue(HandoffReceiverTasks8153.due8153(task, lane, t0 + 1_000L, version))
        // 2. FDG allows: the task settles EVALUATED/FDG_ALLOW (one verdict, named).
        val settled = HandoffReceiverTasks8153.onFdg8153(task, lane, true, null, t0 + 2_000L, version)
        assertEquals(HandoffReceiverTasks8153.Outcome8153.EVALUATED, settled.outcome)
        assertEquals("FDG_ALLOW", settled.verdict)
        assertSame("a second verdict on a settled task changes nothing", settled, HandoffReceiverTasks8153.onFdg8153(settled, lane, false, "late", t0 + 3_000L, version))
        // 3. one execution owner: the lease is held by MOONSHOT; a second lane on the same mint/side/mode is refused.
        val lease = ExecutionAttemptLease.acquire("BUY", mint, "SYM", "LIVE_BUY_$lane", "LIVE", version, 20_000L)
        assertTrue(lease.allowed); assertTrue(ExecutionAttemptLease.isActiveKey6514(lease.key))
        val dup = ExecutionAttemptLease.acquire("BUY", mint, "SYM", "LIVE_BUY_EXPRESS", "LIVE", version, 20_000L)
        assertFalse("duplicate owner must not hold a second active buy lease on the same mint", dup.allowed)
        assertTrue(dup.reason.isNotBlank())
        // 4. one sealed ticket, consumed exactly once.
        val ticket = ExecutionTicket6394("T_$mint", "D_$mint", mint, lane, 0.05, 72.0, 60.0, "ALLOWED", "OPEN", "BUY", t0 + 2_500L)
        assertFalse("a WAIT verdict never seals", ExecutionTicketAuthority6394.issue(ticket.copy(ticketId = "T_wait_$mint", verdict = "WAIT")))
        assertFalse("a failed safety result never seals", ExecutionTicketAuthority6394.issue(ticket.copy(ticketId = "T_unsafe_$mint", safetyResult = "HARD_BLOCK")))
        assertTrue(ExecutionTicketAuthority6394.issue(ticket))
        assertFalse("the same decision cannot seal twice", ExecutionTicketAuthority6394.issue(ticket))
        assertNotNull(ExecutionTicketAuthority6394.consume(ticket.ticketId))
        assertNull("consumed once", ExecutionTicketAuthority6394.consume(ticket.ticketId))
        // 5. state machine walks forward and never downgrades under an inconclusive callback.
        val sm = SingleSellStateMachine6394
        assertTrue(sm.tryTransition(mint, SingleSellStateMachine6394.State.INTENT_CREATED, "E_sig1"))
        assertTrue(sm.tryTransition(mint, SingleSellStateMachine6394.State.BROADCAST, "E_sig1"))
        assertTrue(sm.tryTransition(mint, SingleSellStateMachine6394.State.CONFIRMED, "E_sig1"))
        assertFalse("CONFIRMED must not fall back to QUOTING", sm.tryTransition(mint, SingleSellStateMachine6394.State.QUOTING, "E_sig1"))
        assertEquals(SingleSellStateMachine6394.State.CONFIRMED, sm.currentState(mint))
        // 6. simulated provider finality: receipt reconciles debit, mint and received raw within tolerance.
        val r = receipt("sig1")
        val v = BuySettlementInvariants6394.check(r, BigInteger.valueOf(250_000L), BigInteger.valueOf(1_000L), mint)
        assertTrue(v.failures.joinToString(), v.passed); assertFalse(v.quoteMismatch); assertFalse(v.quarantineDivergent)
        assertTrue(CanonicalReceiptStore6394.persist(r))
        assertFalse("same signature persists once", CanonicalReceiptStore6394.persist(r))
        assertTrue(sm.tryTransition(mint, SingleSellStateMachine6394.State.FULLY_SETTLED, "E_sig1"))
        // 7. canonical lot opened from the verified receipt.
        val lot = PositionLot6394("L_sig1", mint, 6, BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), r.principalLamports, BigInteger.valueOf(20_000L), "sig1", "OPEN", 1L)
        assertTrue(PositionLotLedger6394.upsert(lot))
        assertEquals(BigInteger.valueOf(1_000L), PositionLotLedger6394.get(mint)!!.remainingRaw)
        // 8. learner attribution: the terminal sell row is counted once however many times it is cleaned.
        val row = com.lifecyclebot.data.Trade(side = "SELL", mode = "live", sol = 0.06, price = 1.2, ts = t0 + 60_000L, reason = "TP_LADDER",
            pnlSol = 0.01, sig = "sellSig8206_$mint", mint = mint, positionId = "P_$mint", entryPriceSnapshot = 1.0, entryCostSol = 0.05,
            entryQtyToken = 1000.0, soldQtyToken = 1000.0, remainingQtyToken = 0.0)
        assertEquals("a LIVE terminal row without wallet/tx proof is not strategy truth (MISSING_LIVE_PROOF)", 0, StrategyTruthLedger.clean(listOf(row), limit = 10).rows.size)
        // (clean() memoises by journal fingerprint (size, newest ts, limit, revision): each variant row below is a distinct ts.)
        val proven = row.copy(proofState = "WALLET_FINAL:OWNER_DELTA:${r.signature}", tradingMode = lane, pnlPct = 20.0, ts = row.ts + 1)
        assertEquals("a PnL percent that does not reconcile with SOL/basis is excluded (PNL_SOL_PERCENT_MISMATCH)", 0,
            StrategyTruthLedger.clean(listOf(proven.copy(pnlPct = 400.0, ts = row.ts + 2)), limit = 10).rows.size)
        val before = PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS")
        repeat(3) { assertEquals(1, StrategyTruthLedger.clean(listOf(proven), limit = 10).rows.size) }
        assertEquals(lane, StrategyTruthLedger.strategyLaneFor(proven))
        assertTrue(PipelineHealthCollector.labelCountSnapshot("STRATEGY_CLEAN_TERMINAL_ROWS") - before <= 1L)
        ExecutionAttemptLease.forceReleaseForMint(mint, "test_done")
    }

    @Test fun staleCandidateVersionIsNotDueAndCannotSettleOnTheOldVerdict() {
        val task = HandoffReceiverTasks8153.open8153(null, mint, lane, "PROJECT_SNIPER", "STAGE_LATE", version, t0, t0)!!
        assertFalse("a newer candidate version invalidates the scheduled read", HandoffReceiverTasks8153.due8153(task, lane, t0 + 1_000L, version + 1))
        val same = HandoffReceiverTasks8153.onFdg8153(task, lane, true, null, t0 + 2_000L, version + 1)
        assertTrue("an FDG verdict for a stale version does not settle the task", same.pending)
    }

    @Test fun finalityFailuresAreNamedAndNeverOpenALot() {
        val wrongMint = BuySettlementInvariants6394.check(receipt("sig2", m = "OtherMint"), BigInteger.valueOf(250_000L), BigInteger.valueOf(1_000L), mint)
        assertFalse(wrongMint.passed); assertTrue(wrongMint.failures.contains("MINT_MISMATCH"))
        val badDebit = BuySettlementInvariants6394.check(receipt("sig3", postOwner = 9_000_000L), BigInteger.valueOf(250_000L), BigInteger.valueOf(1_000L), mint)
        assertFalse(badDebit.passed); assertTrue(badDebit.failures.any { it.startsWith("DEBIT_RECONCILIATION_FAILURE") })
        assertTrue(badDebit.failures.contains("EXECUTION_SPEND_INVARIANT_FAILED"))
        val pnlSkew = BuySettlementInvariants6394.check(receipt("sig4", received = 600L), BigInteger.valueOf(250_000L), BigInteger.valueOf(1_000L), mint)
        assertTrue("40% fewer tokens than quoted is a quote mismatch", pnlSkew.quoteMismatch)
        assertTrue("and an accounting quarantine, not a position", pnlSkew.quarantineDivergent)
        assertNull("no lot was opened by any failed finality", PositionLotLedger6394.get(mint))
        assertTrue(SingleSellStateMachine6394.tryTransition(mint, SingleSellStateMachine6394.State.BROADCAST, "E_sig3"))
        assertTrue(SingleSellStateMachine6394.tryTransition(mint, SingleSellStateMachine6394.State.FAILED_FINAL, "E_sig3"))
        assertEquals(SingleSellStateMachine6394.State.FAILED_FINAL, SingleSellStateMachine6394.currentState(mint))
    }

    @Test fun closedLotNeedsANewVerifiedBuySignatureToReopen() {
        val open = PositionLot6394("L1", mint, 6, BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), BigInteger.valueOf(180_000L), BigInteger.ZERO, "sig1", "OPEN", 1L)
        assertTrue(PositionLotLedger6394.upsert(open))
        assertTrue(PositionLotLedger6394.upsert(open.withSell(BigInteger.valueOf(1_000L), "sellSig").copy(status = "CLOSED")))
        assertFalse("a closed lot cannot be re-opened without a fresh verified buy signature", PositionLotLedger6394.upsert(open.copy(status = "OPEN", entrySignature = "")))
        assertTrue(PositionLotLedger6394.upsert(open.copy(status = "OPEN", entrySignature = "sig9", version = 3L)))
    }
}
