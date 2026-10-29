package com.lifecyclebot.engine

import com.lifecyclebot.network.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8418 — the owner's 5.0.8418 phone run (225 s, zero buys this session):
 *  A. EXPRESS AUTH_FDG_SEAL_FAILED_7835=5 — every seal failure now carries its exact inner reason;
 *  B. EXPRESS LIVE_BUY_NOT_OPENED_7948:UNNAMED_8330 — a liveBuy refusal ended through buyTerminalFail never recorded
 *     its stop, so the ticket terminal had no name (the THROWN catch is one of those refusals);
 *  C. THROWN:Transaction — "Transaction" was the first word of the exception MESSAGE (OnChainFailed8085 /
 *     TxExpiredProven8085), cut by the whitespace-token roll-up; the reason now carries class + message.
 */
class Aate8418LiveBuyCauseTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { SealMiss8418.resetForTests8418() }
    @After fun tearDown() { SealMiss8418.resetForTests8418() }

    private fun intent(
        lane: String = "EXPRESS", verdict: String = "BUY", allowed: Boolean = true, size: Double = 0.031,
        version: Long = 7L, mode: String = "LIVE", hardNo: List<String> = emptyList(),
    ) = ExecutableOpenGate.ExecutionIntent(
        attemptId = "a", candidateId = "MintE:$version", candidateVersion = version, mint = "MintE", mode = mode,
        canonicalLane = lane, fdgVerdict = verdict, fdgAllowed = allowed, authorityVersion = 1L,
        resolvedSize = size, createdAt = 1L, symbol = "E", hardNoReasons = hardNo,
    )

    // ── A ──

    @Test fun aTicketThatMatchesTheDecisionHasNoMismatch() {
        assertNull(SealMiss8418.mismatch8418(intent(), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        // the first seal of a version caps a later, larger FDG size: smaller ticket is within the verdict
        assertNull(SealMiss8418.mismatch8418(intent(size = 0.030), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
    }

    @Test fun eachSealMismatchIsNamed() {
        assertEquals("SEALED_SIZE_ABOVE_FDG", SealMiss8418.mismatch8418(intent(size = 0.032), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_LANE_SHITCOIN", SealMiss8418.mismatch8418(intent(lane = "SHITCOIN"), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_VERDICT_PROBE_ONLY", SealMiss8418.mismatch8418(intent(verdict = "PROBE_ONLY"), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_NOT_ALLOWED", SealMiss8418.mismatch8418(intent(allowed = false), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_VERSION_MISMATCH", SealMiss8418.mismatch8418(intent(version = 6L), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_MODE_MISMATCH", SealMiss8418.mismatch8418(intent(mode = "PAPER"), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
        assertEquals("SEALED_HARD_NO_RC_SCORE_0", SealMiss8418.mismatch8418(intent(hardNo = listOf("RC_SCORE_0")), "MintE", "LIVE", 7L, "EXPRESS", 0.031))
    }

    @Test fun aNotedInnerReasonIsReadBackPerMintVersionLaneAndCleared() {
        SealMiss8418.note8418("MintE", 7L, "express", "ENTRY_AUTHORITY_DENY_DAILY_LOSS_CAP")
        assertEquals("ENTRY_AUTHORITY_DENY_DAILY_LOSS_CAP", SealMiss8418.reasonFor8418("MintE", 7L, "EXPRESS"))
        assertNull(SealMiss8418.reasonFor8418("MintE", 8L, "EXPRESS"))
        assertNull(SealMiss8418.reasonFor8418("MintE", 7L, "MOONSHOT"))
        SealMiss8418.clear8418("MintE", 7L, "EXPRESS")
        assertNull(SealMiss8418.reasonFor8418("MintE", 7L, "EXPRESS"))
    }

    @Test fun reasonsAreCounterSafe() {
        assertEquals("HARD_NO_LIQUIDITY_UNKNOWN_PENDING_TOKEN_", SealMiss8418.clean8418("hard no:liquidity unknown pending token map"))
        assertEquals("UNKNOWN", SealMiss8418.clean8418("  "))
    }

    @Test fun theAuthorizerCarriesTheReasonIntoThePreSizeCounter() {
        val s = src("engine/TradeAuthorizer.kt")
        assertTrue(s.contains("releasePrimaryAfterAuthFailure(\"FDG_SEAL_FAILED_7835_\$sealWhy8418\")"))
        val seal = src("engine/SpecialistPreauthSeal7834.kt")
        assertTrue(seal.contains("SealMiss8418.reasonFor8418(ts.mint, d.candidateVersion7835, lane)?.let { return it }"))
        val gate = src("engine/ExecutableOpenGate.kt")
        assertTrue(gate.contains("\"ENTRY_AUTHORITY_\" + preEntry6487.verdict.name"))
        assertTrue(gate.contains("sealMissAfterRecord8418(mode, mint, candidateVersion, lane)"))
    }

    @Test fun thePreSizeKeyKeepsTheReason() {
        // recordPreSizeRefusal7809 keeps the text before the first ':' up to 64 chars
        val key = "AUTH_FDG_SEAL_FAILED_7835_" + SealMiss8418.clean8418("VERDICT_KEPT_BY_SHITCOIN")
        assertTrue(key.length <= 64)
        assertFalse(key.contains(':'))
    }

    // ── B ──

    @Test fun aRefusalNamedOnlyThroughBuyTerminalFailNowNamesTheTicket() {
        // before: inner stop null and the ticket still live -> UNNAMED_8330
        assertEquals("LIVE_BUY_NOT_OPENED_7948:UNNAMED_8330",
            TicketCause8330.refine8330("LIVE_BUY_NOT_OPENED_7948", null, mintHeld = false, ticketLive = true))
        // after: buyTerminalFail records the stop, so the same drop is named
        val inner = "BUY_TERMINAL_SUBMIT_FAIL:" + LiveBuyThrown8418.reason8418(
            SolanaWallet.OnChainFailed8085("5sig", "{\"InstructionError\":[3,{\"Custom\":6001}]}"),
            "Transaction failed on-chain: {\"InstructionError\":[3,{\"Custom\":6001}]} (sig=5sig)")
        val refined = TicketCause8330.refine8330("LIVE_BUY_NOT_OPENED_7948", inner, mintHeld = false, ticketLive = true)
        assertEquals("refused", TicketCause8330.classOf8330(refined))
        assertTrue(refined.contains("THROWN:OnChainFailed8085"))
        val s = src("engine/Executor.kt")
        val f = s.indexOf("fun buyTerminalFail(reason: String) {")
        assertTrue(f > 0 && s.indexOf("lastLiveBuyStop8252[ts.mint] = reason", f) in f..(f + 200))
    }

    // ── C ──

    @Test fun theThrownReasonKeepsClassAndMessageThroughTheTokenRollUp() {
        val msg = "Transaction not found after 61s — blockhash expired (proven): 5abc"
        val r = LiveBuyThrown8418.reason8418(SolanaWallet.TxExpiredProven8085("5abc", 61L), msg)
        assertEquals("THROWN:TxExpiredProven8085:Transaction_not_found_after_61s_—_blockhash_expired_(proven):_5abc", r)
        assertFalse(r.any { it.isWhitespace() })
        // the old token was "THROWN:Transaction" — the roll-up cut at the first whitespace
        val old = "reason=THROWN:${msg.take(80)} lastStage=X".substringAfter("reason=").takeWhile { !it.isWhitespace() }
        assertEquals("THROWN:Transaction", old)
    }

    @Test fun theMessageIsCappedAt160() {
        val r = LiveBuyThrown8418.reason8418(RuntimeException("x"), "a ".repeat(400))
        assertEquals(LiveBuyThrown8418.MESSAGE_CHARS_8418, r.removePrefix("THROWN:RuntimeException:").length)
        assertEquals("THROWN:RuntimeException:unknown", LiveBuyThrown8418.reason8418(RuntimeException(), null))
    }

    @Test fun aFeeBookingThatThrowsNeverReachesTheBuy() {
        val sink = object : BuyAppFee8418.Sink8418 {
            override fun accrue(dest: String, sol: Double, tag: String): Double = throw StackOverflowError("persist")
            override fun enqueue(dest: String, sol: Double, tag: String) { throw IllegalStateException("queue") }
        }
        // an Error from the accumulator and an exception from the retry queue are both contained
        val fee = BuyAppFee8418.chargeBuy8418("Self1111", 0.05, "t", sink)
        assertEquals(BuyAppFee8418.bookedFeeSol8418(0.05), fee, 1e-12)
        val s = src("engine/BuyAppFee8418.kt")
        assertTrue(s.contains("APP_BUY_FEE_THREW_ISOLATED_8418_"))
    }
}
