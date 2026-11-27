package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.network.SignedTxLedger8085
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real signed-ledger boundary runs before the simulated transport, including retries of refused bytes. */
class Aate9043LaunchDispatchTest {
    private val s = LaunchSniper8748
    private val wall = 1_800_000_000_000L
    private val mint = "dispatch-9043"
    private val op = "legacy:$mint:$wall"
    private val savedStates = mutableListOf<SignedTxLedger8085.State>()

    @Before fun before() {
        s.resetClocksForTest9025(); SignedTxLedger8085.resetForTest()
        SignedTxLedger8085.persister = { rows -> rows.lastOrNull()?.let { savedStates += it.state } }
        s.openClockForTest9025(mint, wall, 20_000L)
    }
    @After fun after() {
        SignedTxLedger8085.persister = null; SignedTxLedger8085.resetForTest(); s.resetClocksForTest9025()
        ExecutionAttemptLease.forceReleaseForMint(mint, "TEST_CLEANUP")
    }

    private fun approved(attempt: String, version: Long = 1L, dependent: Boolean = true, at: Long = wall + 1_000L): Boolean {
        s.noteAdmission9025(mint, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", at, "EXPRESS", version, op, fireDependent = dependent)
        s.noteGate9025(mint, true, null, at, "EXPRESS", version)
        return s.bindTicket9043(mint, "EXPRESS", version, attempt)
    }
    private fun signed(attempt: String, signature: String) = SignedTxLedger8085.record(signature, "signed-bytes", "blockhash",
        SignedTxLedger8085.Intent(mint, "BUY", intentId = attempt), nowMs = wall)

    @Test fun lateFirstBroadcastIsPersistedAsUnsentAndRepeatedSubmissionCannotPass() {
        assertTrue(approved("old-ticket"))
        val row = signed("old-ticket", "signature-old")
        var broadcasts = 0
        repeat(2) {
            try {
                SignedTxLedger8085.transition(row.signature, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, nowMs = wall + 20_001L)
                broadcasts++
                fail("expired first broadcast must throw before transport")
            } catch (e: LaunchSniper8748.DispatchRefused9043) {
                assertEquals("LAUNCH_OPPORTUNITY_EXPIRED_9043", e.reason)
            }
        }
        assertEquals(0, broadcasts)
        assertEquals(SignedTxLedger8085.State.SUBMIT_REJECTED, row.state)
        assertEquals(0L, row.submittedAtMs8087)
        assertTrue(savedStates.contains(SignedTxLedger8085.State.SUBMIT_REJECTED))
    }

    @Test fun validFirstSubmitPassesAndLaterSameSignatureTransportRetryKeepsItsExistingLiability() {
        assertTrue(approved("valid-ticket"))
        val row = signed("valid-ticket", "signature-valid")
        SignedTxLedger8085.transition(row.signature, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, nowMs = wall + 19_000L)
        assertEquals(SignedTxLedger8085.State.SUBMIT_ATTEMPTED, row.state)
        assertEquals(wall + 19_000L, row.submittedAtMs8087)
        SignedTxLedger8085.transition(row.signature, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, nowMs = wall + 25_000L)
        assertTrue(row.unresolved)
        assertEquals(wall + 19_000L, row.submittedAtMs8087)
    }

    @Test fun refreshedVersionCannotRenewFrozenFireButIndependentNewDecisionCanQualify() {
        assertTrue(approved("frozen-ticket"))
        assertTrue(approved("frozen-ticket", 2L, true, wall + 30_000L))
        assertEquals("LAUNCH_OPPORTUNITY_EXPIRED_9043", s.dispatchRefusal9043("frozen-ticket", wall + 30_000L))
        assertFalse("old ticket cannot borrow an independent later decision", approved("frozen-ticket", 3L, false, wall + 31_000L))
        assertEquals("LAUNCH_OPPORTUNITY_EXPIRED_9043", s.dispatchRefusal9043("frozen-ticket", wall + 31_000L))
        assertTrue(approved("new-independent-ticket", 3L, false, wall + 31_000L))
        assertNull(s.dispatchRefusal9043("new-independent-ticket", wall + 31_000L))
    }

    @Test fun localRevalidationAddsNoFailureAndPreservesEarlierGenuineBackoff() {
        val first = ExecutionAttemptLease.acquire("BUY", mint, "TEST", "EXPRESS", "LIVE", 9043L)
        assertTrue(first.allowed)
        ExecutionAttemptLease.releaseForRevalidation9043(first.key, "BUY", mint, "TEST", "LAUNCH_OPPORTUNITY_EXPIRED_9043")
        assertEquals(0L, ExecutionAttemptLease.buyBackoffLeftMs8717(mint, "EXPRESS"))
        val retry = ExecutionAttemptLease.acquire("BUY", mint, "TEST", "EXPRESS", "LIVE", 9043L)
        assertTrue(retry.allowed)
        ExecutionAttemptLease.terminalFail(retry.key, "BUY", mint, "TEST", "NETWORK_TIMEOUT", "EXPRESS")
        val at = System.currentTimeMillis()
        val oldBackoff = ExecutionAttemptLease.buyBackoffLeftMs8717(mint, "EXPRESS", at)
        assertTrue(oldBackoff > 0L)
        ExecutionAttemptLease.releaseForRevalidation9043(retry.key, "BUY", mint, "TEST", "LAUNCH_LINEAGE_UNMATCHED_9043")
        assertEquals(oldBackoff, ExecutionAttemptLease.buyBackoffLeftMs8717(mint, "EXPRESS", at))
    }

    @Test fun restartCannotConvertPersistedSignedBytesIntoOrdinaryEntryAuthorization() {
        assertTrue(approved("restart-ticket"))
        val pending = signed("restart-ticket", "signature-restart")
        val saved = SignedTxLedger8085.encode(pending)
        s.resetClocksForTest9025(); SignedTxLedger8085.resetForTest()
        assertEquals(1, SignedTxLedger8085.restore(listOf(saved)))
        val recovered = requireNotNull(SignedTxLedger8085.get("signature-restart"))
        assertTrue("liability remains available for chain resolution", recovered.unresolved)
        assertFalse("recovery cannot perform the first broadcast", SignedTxLedger8085.mayRebroadcast9043(recovered))
        try {
            SignedTxLedger8085.transition(recovered.signature, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, nowMs = wall + 30_000L)
            fail("lost process authorization must require a new decision")
        } catch (e: LaunchSniper8748.DispatchRefused9043) {
            assertEquals("ENTRY_AUTHORITY_LOST_REVALIDATE_9043", e.reason)
        }
        assertEquals(SignedTxLedger8085.State.SIGNED, recovered.state)
        val submitted = saved.replace("|SIGNED|", "|SUBMIT_ATTEMPTED|").replace("signature-restart", "signature-already-submitted")
        assertEquals(1, SignedTxLedger8085.restore(listOf(submitted)))
        assertTrue(SignedTxLedger8085.mayRebroadcast9043(requireNotNull(SignedTxLedger8085.get("signature-already-submitted"))))
    }
}
