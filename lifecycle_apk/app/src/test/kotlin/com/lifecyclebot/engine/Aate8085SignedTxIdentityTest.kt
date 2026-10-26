package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.sell.SellRouteRace8083
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SignedTxLedger8085.State
import com.lifecyclebot.network.SignedTxLedger8085.Submit
import com.lifecyclebot.network.SolanaWallet
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8085 — signed-transaction identity: the id comes from the signed bytes and is persisted before any send;
 * provider acknowledgement is not chain truth; an unknown outcome is adopted, never re-traded.
 */
class Aate8085SignedTxIdentityTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val sig = "5LocalSigAAAA"

    @Before fun reset() { SignedTxLedger8085.resetForTest() }

    // 1. RPC acceptance is a JSON-RPC result equal to the local id — not HTTP 200.
    @Test fun rpcAcceptanceNeedsMatchingResult() {
        assertEquals(Submit.ACCEPTED, SignedTxLedger8085.classifyRpcReply(true, sig, null, sig))
        assertEquals(Submit.UNKNOWN, SignedTxLedger8085.classifyRpcReply(true, "OtherSig", null, sig))
        assertEquals(Submit.UNKNOWN, SignedTxLedger8085.classifyRpcReply(true, "", null, sig))      // 200 + blank
        assertEquals(Submit.UNKNOWN, SignedTxLedger8085.classifyRpcReply(false, null, null, sig))   // timeout / 5xx
        assertEquals(Submit.REJECTED, SignedTxLedger8085.classifyRpcReply(true, "", "Blockhash not found", sig))
    }

    // 2. "already processed" is acceptance of these exact bytes.
    @Test fun alreadyProcessedIsAcceptance() {
        assertEquals(Submit.ACCEPTED, SignedTxLedger8085.classifyRpcReply(true, "", "Transaction already processed", sig))
        assertTrue(SignedTxLedger8085.alreadyProcessed("AlreadyProcessed"))
    }

    // 3. A later bank simulation cannot disprove an earlier copy in flight.
    @Test fun rejectionDefinitenessDependsOnOtherCopies() {
        assertTrue(SignedTxLedger8085.rejectionIsDefinite("Blockhash not found", otherCopyMaybeDelivered = false, jitoCopySent = false))
        assertFalse(SignedTxLedger8085.rejectionIsDefinite("Blockhash not found", otherCopyMaybeDelivered = true, jitoCopySent = false))
        assertFalse(SignedTxLedger8085.rejectionIsDefinite("node is behind", otherCopyMaybeDelivered = false, jitoCopySent = true))
        assertFalse(SignedTxLedger8085.rejectionIsDefinite("custom program error: 0x1788", otherCopyMaybeDelivered = true, jitoCopySent = true))
    }

    // 4. Expiry is proven only by an unseen signature AND an invalid blockhash; unknown validity proves nothing.
    @Test fun expiryNeedsProof() {
        assertTrue(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = false))
        assertFalse(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = null))
        assertFalse(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = true))
        assertFalse(SignedTxLedger8085.expiredProven(statusSeen = true, blockhashValid = false))
    }

    // 5. An unresolved row for (mint, side) blocks a second order; a terminal row does not.
    @Test fun unresolvedRowBlocksSecondOrderTerminalDoesNot() {
        SignedTxLedger8085.record(sig, "b64", "bh", SignedTxLedger8085.Intent("MINT1", "SELL", 100L), nowMs = 1_000L)
        SignedTxLedger8085.transition(sig, State.SUBMIT_ATTEMPTED, nowMs = 1_100L)
        SignedTxLedger8085.transition(sig, State.OUTCOME_UNKNOWN, "rpc_threw", nowMs = 1_200L)
        assertNotNull(SignedTxLedger8085.unresolvedFor("MINT1", "SELL", nowMs = 2_000L))
        assertNull(SignedTxLedger8085.unresolvedFor("MINT1", "BUY", nowMs = 2_000L))
        assertNull(SignedTxLedger8085.unresolvedFor("MINT2", "SELL", nowMs = 2_000L))
        SignedTxLedger8085.transition(sig, State.EXPIRED_PROVEN, nowMs = 3_000L)
        assertNull(SignedTxLedger8085.unresolvedFor("MINT1", "SELL", nowMs = 4_000L))
    }

    // 6. Chain truth never moves back to a provider-side state; a failing endpoint never erases an acceptance.
    @Test fun terminalAndAcceptedEvidenceAreNotErased() {
        SignedTxLedger8085.record(sig, "b64", "bh", null, nowMs = 1L)
        SignedTxLedger8085.transition(sig, State.ACCEPTED, acceptedBy = "alchemy", nowMs = 2L)
        SignedTxLedger8085.transition(sig, State.OUTCOME_UNKNOWN, "helius timeout", nowMs = 3L)
        assertEquals(State.ACCEPTED, SignedTxLedger8085.get(sig)!!.state)
        SignedTxLedger8085.transition(sig, State.CONFIRMED, nowMs = 4L)
        SignedTxLedger8085.transition(sig, State.OUTCOME_UNKNOWN, nowMs = 5L)
        SignedTxLedger8085.transition(sig, State.ACCEPTED, acceptedBy = "rpc", nowMs = 6L)
        assertEquals(State.CONFIRMED, SignedTxLedger8085.get(sig)!!.state)
    }

    // 7. Restart: rows survive encode/decode and unresolved ones are handed back for a finality read.
    @Test fun restartRestoresUnresolvedRows() {
        SignedTxLedger8085.record(sig, "c2lnbmVk", "BH1", SignedTxLedger8085.Intent("MINTR", "BUY", 7L, "pos1", "int1", "JUPITER"), nowMs = 10L)
        SignedTxLedger8085.transition(sig, State.OUTCOME_UNKNOWN, "x|y", nowMs = 11L)
        val line = SignedTxLedger8085.encode(SignedTxLedger8085.get(sig)!!)
        SignedTxLedger8085.resetForTest()
        val recovered = ArrayList<String>()
        assertEquals(1, SignedTxLedger8085.restore(listOf(line, "garbage")) { recovered.add(it.signature) })
        assertEquals(listOf(sig), recovered)
        val e = SignedTxLedger8085.get(sig)!!
        assertEquals("MINTR", e.mint); assertEquals("BUY", e.side); assertEquals("BH1", e.blockhash); assertEquals("c2lnbmVk", e.signedB64)
        assertEquals(7L, e.rawQty); assertEquals("pos1", e.positionId); assertEquals(State.OUTCOME_UNKNOWN, e.state)
    }

    // 8. The caller intent binds only the next signature on the thread.
    @Test fun intentIsConsumedOnce() {
        SignedTxLedger8085.intend(SignedTxLedger8085.Intent("M", "SELL"))
        assertNotNull(SignedTxLedger8085.consumeIntent())
        assertNull(SignedTxLedger8085.consumeIntent())
    }

    // 9. Race outcome is typed; only a provably failed/unsent transaction frees the quantity.
    @Test fun raceOutcomeIsTyped() {
        assertEquals(SellRouteRace8083.TxOutcome8085.OUTCOME_UNKNOWN,
            SellRouteRace8083.classify8085(SolanaWallet.SentButUnconfirmed7733("s", RuntimeException("t"))))
        assertEquals(SellRouteRace8083.TxOutcome8085.ONCHAIN_FAILED, SellRouteRace8083.classify8085(SolanaWallet.OnChainFailed8085("s", "0x1788")))
        assertEquals(SellRouteRace8083.TxOutcome8085.EXPIRED_PROVEN, SellRouteRace8083.classify8085(SolanaWallet.TxExpiredProven8085("s", 61)))
        assertEquals(SellRouteRace8083.TxOutcome8085.SUBMIT_REJECTED, SellRouteRace8083.classify8085(SolanaWallet.SubmitRejected8085("no endpoint")))
        assertFalse(SellRouteRace8083.quantityFree8085(SellRouteRace8083.TxOutcome8085.OUTCOME_UNKNOWN))
        assertFalse(SellRouteRace8083.quantityFree8085(SellRouteRace8083.TxOutcome8085.LANDED))
        assertTrue(SellRouteRace8083.quantityFree8085(SellRouteRace8083.TxOutcome8085.ONCHAIN_FAILED))
        assertTrue(SellRouteRace8083.quantityFree8085(null))
    }

    // 10. On-chain failure keeps the slippage-ladder message (a proven no-fill may escalate); expiry keeps "blockhash".
    @Test fun chainTruthExceptionsKeepClassifierText() {
        assertTrue(SolanaWallet.OnChainFailed8085("s", "{\"Custom\":6024} 0x1788").message!!.contains("0x1788"))
        assertTrue(SolanaWallet.TxExpiredProven8085("s", 45).message!!.contains("blockhash expired"))
        assertTrue(SolanaWallet.SubmitRejected8085("x").message!!.startsWith("sendTransaction failed:"))
    }

    // 11. Wallet: id derived + persisted before any network call; sends classified; confirmation proves expiry.
    @Test fun walletSourceContract() {
        val w = src("network/SolanaWallet.kt")
        val sign = w.substringAfter("fun signAndSend(").substringBefore("// ── V5.0.8085 §SIGNED_TX_IDENTITY")
        assertTrue(sign.indexOf("signTracked8085(txBase64") in 0 until sign.indexOf("HeliusSender.send8085"))
        assertTrue(sign.contains("submitRpc8085(signedB64, params, localSig8085"))
        assertFalse(sign.contains("throw RuntimeException(\"sendTransaction failed: \$errorMsg\")"))
        val tracked = w.substringAfter("private fun signTracked8085(").substringBefore("private fun guardDuplicate8085(")
        assertTrue(tracked.indexOf("guardDuplicate8085") < tracked.indexOf("SignedTxLedger8085.record("))
        val await = w.substringAfter("fun awaitConfirmation(").take(6000)
        assertTrue(await.contains("throw TxExpiredProven8085(signature"))
        assertFalse(await.contains("blockhash may have expired"))
        assertTrue(await.contains("throw OnChainFailed8085(signature, err)"))
        val keep = w.substringAfter("private fun awaitConfirmationKeepingSignature7733(").take(1400)
        assertTrue(keep.contains("scheduleResolve8085(sig)"))
        val ultra = w.substringAfter("private fun signAndExecuteUltra(").substringBefore("private fun sendRawTransaction(")
        assertTrue(ultra.contains("executeMaybeDelivered8085"))
        assertTrue(w.contains("val ack = SignedTxLedger8085.classifyRpcReply(r.isSuccessful && body != null"))
    }

    // 12. Executor: every live sell send adopts an unknown outcome; buys propagate it to the 7733 pending path.
    @Test fun executorSourceContract() {
        val x = src("engine/Executor.kt")
        assertFalse(x.contains("wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute, txResult.senderCompatible)"))
        assertTrue(x.split("sellSend8085(").size >= 9)
        assertTrue(x.contains("buySend8085(wallet, ts.mint, tx, useJito, jitoTip, ultraReqId, c, submission8397)"))
        val pump = x.substringAfter("private fun tryPumpPortalBuy(").substringBefore("private fun ", )
        assertTrue(pump.contains("catch (e: SolanaWallet.SentButUnconfirmed7733) {\n            throw e"))
        assertTrue(x.contains("if (deferDuplicateBuy8085(ts, sol, e))"))
        val ray = x.substringAfter("private fun sendRaydiumBuilt8083(").substringBefore("private fun sellSend8085(")
        assertTrue(ray.contains("return adoptSellUnknown8085(mint, e.signature, \"RAYDIUM\""))
    }

    // 13. Setup classification follow-ups: input re-fetch throttle, decision identity, persisted forward stats.
    @Test fun setupFollowUps() {
        assertTrue(LanePlaybook7907.inputRefreshDue8085(null, 1_000L))
        assertFalse(LanePlaybook7907.inputRefreshDue8085(1_000L, 20_000L))
        assertTrue(LanePlaybook7907.inputRefreshDue8085(1_000L, 31_000L))
        assertTrue(LanePlaybook7907.sameDecision8085("BREAKOUT;A,B#O=MATCHED#V=42", "BREAKOUT;A#O=MATCHED#V=42"))
        assertFalse(LanePlaybook7907.sameDecision8085("BREAKOUT#O=MATCHED#V=42", "BREAKOUT#O=MATCHED#V=43"))
        assertFalse(LanePlaybook7907.sameDecision8085("", "BREAKOUT#O=MATCHED#V=42"))
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(p.contains("o.put(\"_outcomeFwd8085\""))
        assertTrue(p.contains("o.optJSONObject(\"_outcomeFwd8085\")"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("if (forcePoll8085.remove(mint))"))
        assertTrue(src("engine/truth/EntryStrategySnapshot6450.kt").contains(".put(\"cortexSetup8085\", snap.cortexSetupTag8085)"))
    }
}
