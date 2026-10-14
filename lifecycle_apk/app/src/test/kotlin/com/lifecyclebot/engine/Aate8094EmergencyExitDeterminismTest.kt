package com.lifecyclebot.engine

import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SignedTxLedger8085.State
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8094 forensic P0 — emergency exits under provider outages: no unproven-expiry duplicate sell, no indefinite
 * suppression, no discretionary hold in front of an emergency, bounded self-retry.
 */
class Aate8094EmergencyExitDeterminismTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun reset() { SignedTxLedger8085.resetForTest() }

    // Conflicting signed transactions: an unresolved sell blocks a second sell until chain evidence resolves it.
    @Test fun anUnresolvedSellBlocksASecondSellUntilChainEvidenceArrives() {
        SignedTxLedger8085.record("SIG_A", "b64", "bh", SignedTxLedger8085.Intent("MINT_E", "SELL", 100L), nowMs = 1_000L)
        SignedTxLedger8085.transition("SIG_A", State.SUBMIT_ATTEMPTED, "fanout_8094", nowMs = 1_100L)
        // Delayed / failing RPC: the outcome is UNKNOWN, never proven either way...
        SignedTxLedger8085.transition("SIG_A", State.OUTCOME_UNKNOWN, "rpc_timeout", nowMs = 1_200L)
        // ...and no amount of elapsed time alone releases the guard.
        assertNotNull(SignedTxLedger8085.unresolvedFor("MINT_E", "SELL", nowMs = 1_000L + 10 * 60_000L))
        // Expiry needs an unseen signature AND an invalid blockhash; an unreadable blockhash proves nothing.
        assertFalse(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = null))
        assertTrue(SignedTxLedger8085.expiredProven(statusSeen = false, blockhashValid = false))
        SignedTxLedger8085.transition("SIG_A", State.EXPIRED_PROVEN, "blockhash_invalid_signature_unseen", nowMs = 90_000L)
        assertNull(SignedTxLedger8085.unresolvedFor("MINT_E", "SELL", nowMs = 91_000L))      // the exit may go again
    }

    // A landed sell is chain truth: a later provider timeout can never turn it into an expiry or a resend.
    @Test fun aConfirmedSellIsNeverReopenedByALaterTimeout() {
        SignedTxLedger8085.record("SIG_B", "b64", "bh", SignedTxLedger8085.Intent("MINT_F", "SELL", 100L), nowMs = 1L)
        SignedTxLedger8085.transition("SIG_B", State.CONFIRMED, "status=confirmed", nowMs = 2L)
        SignedTxLedger8085.transition("SIG_B", State.EXPIRED_PROVEN, "late", nowMs = 3L)
        SignedTxLedger8085.transition("SIG_B", State.OUTCOME_UNKNOWN, "late timeout", nowMs = 4L)
        assertEquals(State.CONFIRMED, SignedTxLedger8085.get("SIG_B")!!.state)
        // The sign-time fan-out and the Sender path mark SUBMIT_ATTEMPTED twice: idempotent, one signature.
        SignedTxLedger8085.record("SIG_C", "b64", "bh", SignedTxLedger8085.Intent("MINT_G", "SELL", 100L), nowMs = 10L)
        SignedTxLedger8085.transition("SIG_C", State.SUBMIT_ATTEMPTED, "fanout_8094", nowMs = 11L)
        SignedTxLedger8085.transition("SIG_C", State.SUBMIT_ATTEMPTED, "helius_sender", nowMs = 12L)
        assertEquals(State.SUBMIT_ATTEMPTED, SignedTxLedger8085.get("SIG_C")!!.state)
    }

    // Repeated catastrophic triggers / stale lease: one bounded self-retry per mint, never parked longer than 15 s.
    @Test fun repeatedTriggersScheduleOneBoundedSelfRetry() {
        val l = com.lifecyclebot.engine.sell.CloseLease
        for (gap in listOf(-60_000L, 0L, 2_000L, 10_000L, 600_000L)) {
            val d = l.selfRetryDelayMs8094(eligibleAtMs = 100_000L + gap, nowMs = 100_000L)
            assertTrue("gap=$gap delay=$d", d in 250L..15_000L)
        }
        val lease = src("engine/sell/CloseLease.kt")
        assertTrue(lease.contains("if (pending != null && pending >= now) return            // one self-retry per mint at a time"))
        assertTrue(lease.contains("if (current(mint) != null) {"))     // a released lease is not re-dispatched
    }

    // Unavailable balance proof: an emergency sells from the last complete snapshot; a partial snapshot proves nothing.
    @Test fun unavailableBalanceProofDoesNotBlockAnEmergencyOrFakeAZero() {
        val x = src("engine/Executor.kt")
        assertTrue(x.contains("preSellGuard(ts.mint, ts.symbol ?: \"?\", if (emergency8094) null else wallet)"))
        val sa = src("engine/sell/SellAmountAuthority.kt")
        assertTrue(sa.contains("fun emergencyWalletSnapshotBalance7730(mint: String, reason: String)"))
        assertTrue(sa.contains("WalletSnapshotCompleteness7140.isLastPartial()"))
        // A retire needs a COMPLETE snapshot after the close; no snapshot / partial -> 0 (no proof).
        assertEquals(0L, walletZeroProvenAt8094(false, 5_000L, null, java.math.BigInteger.ONE))
    }

    // No discretionary hold sits in front of an emergency exit.
    @Test fun discretionaryHoldsNeverDeferAnEmergency() {
        val rg = src("engine/RunnerGrab7967.kt").substringAfter("fun deferrable7967(").substringBefore("// ── grab ──")
        assertTrue(rg.contains("if (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(r)) return false"))
        val lh = src("engine/LifecycleHold8065.kt").substringAfter("fun passes8065(").take(400)
        assertTrue(lh.contains("Priority.STRUCTURAL_EMERGENCY.rank"))       // catastrophe + structural always pass
        val pe = src("engine/sell/PartialNetEconomics8094.kt")
        assertTrue(pe.contains("ProtectiveExitClass7807.isEmergency(reason)"))   // the fee check never holds an emergency
    }

    // Memory / main thread: Cortex saves are queued on one background thread; onDestroy waits a bounded time.
    @Test fun cortexPersistenceNeverEncodesOnTheCallerThread() {
        val c = src("engine/cortex/Cortex7885.kt")
        val persist = c.substringAfter("    private fun persist() {").substringBefore("@Synchronized private fun persistBlocking8094()")
        assertTrue(persist.contains("persistQueued8094.compareAndSet(false, true)"))          // coalesced
        assertTrue(persist.contains("persistPool8094.execute { persistQueued8094.set(false); persistBlocking8094() }"))
        val now = c.substringAfter("fun persistNow7930() {").substringBefore("private const val FLUSH_WAIT_MS_8094")
        assertTrue(now.contains("persistPool8094.submit(work).get(FLUSH_WAIT_MS_8094"))      // bounded main-thread wait
        assertFalse(c.contains("@Synchronized private fun persist() {"))
    }

    // Cortex: a strong lane read cannot overrule for a candidate whose own setup record is negative.
    @Test fun aStrongLaneCannotOverruleANegativeSetup() {
        val c = com.lifecyclebot.engine.cortex.Cortex7885
        assertTrue(c.strongSetupNegative8094(n = 12.0, meanPct = -9.0, sePct = 4.0))
        assertFalse(c.strongSetupNegative8094(n = 12.0, meanPct = -2.0, sePct = 4.0))   // not proven negative
        assertFalse(c.strongSetupNegative8094(n = 3.0, meanPct = -40.0, sePct = 1.0))   // too few reads
        val src = src("engine/cortex/Cortex7885.kt")
        val ov = src.substringAfter("fun overrulesEdgeRefusal(").substringBefore("val cell8083 =")
        assertTrue(ov.contains("LanePlaybook7907.provenLosingSetupRefusal8079(ts, a.lane)"))
        assertTrue(ov.contains("CORTEX_OVERRIDE_REFUSED_NEGATIVE_SETUP_8094"))
        assertTrue(src.contains("CORTEX_OVERRIDE_EXPLAINED_8083"))                       // every override attributed
    }
}
