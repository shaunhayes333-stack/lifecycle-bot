package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Production rung book, exact signed identities, controllable time and restart codecs; no source-string assertions. */
class Aate9043RunnerLifecycleTest {
    private val ladder = RunnerRungBook8153.LADDER_RUNNER_8153
    private val key = RunnerIdentity9043.positionKey("mint9043", 10L, false, "LIVE:mint9043:lot-1")
    private fun raw(n: Long) = BigInteger.valueOf(n)
    private fun request(book: RunnerRungBook8153, at: Long = 100L): RunnerIdentity9043.Request {
        assertTrue(book.request8153(key, ladder, 1, "SPIKE_CAPTURE_7943_T1", at))
        assertTrue(book.acceptDispatch8397(key, "SPIKE_CAPTURE_7943_T1", at, at + 1L, ladder))
        return RunnerIdentity9043.Request(key, ladder, 1, at, "SPIKE_CAPTURE_7943_T1")
    }

    @Test fun ticketAndMintLockAreNotBroadcastEvidence() {
        val b = RunnerRungBook8153(); request(b)
        assertFalse(b.settle8153(key, true, 105L))
        assertEquals(RunnerRungBook8153.Stage8153.TICKET_ACCEPTED, b.pending8153(key)!!.stage)
        assertTrue(b.statusLine8153().contains("bcast=0"))
        assertEquals(0, b.confirmedTier8153(key, ladder))
    }

    @Test fun rejectedUnsentRungRetriesWithoutConsumingItsTier() {
        val b = RunnerRungBook8153(); val r = request(b)
        assertTrue(b.revertMatching8397(key, r.reason, r.requestedAtMs, 110L, expectedLadder8397 = ladder))
        assertFalse(b.request8153(key, ladder, 1, r.reason, 500L))
        val next = request(b, 1_111L)
        assertTrue(b.signed9043(next, "sig-retry", raw(100), 1_112L))
        assertTrue(b.submitted9043(next, "sig-retry", 1_113L))
        assertTrue(b.confirm8153(key, ladder, 1, "sig-retry", .2, .1, raw(100)))
        assertEquals(1, b.confirmedTier8153(key, ladder))
        assertEquals(.1, b.bankedNetSol8153(key), 1e-12)
    }

    @Test fun lostAcknowledgementCancellationAndTimeoutCannotReleaseSignedQuantity() {
        val b = RunnerRungBook8153(); val r = request(b)
        assertTrue(b.signed9043(r, "sig-pending", raw(100), 102L))
        assertTrue(b.submitted9043(r, "sig-pending", 103L))
        assertFalse(b.revertMatching8397(key, r.reason, r.requestedAtMs, 104L, expectedLadder8397 = ladder))
        assertFalse(b.settle8153(key, false, 999_999L))
        assertFalse(b.request8153(key, ladder, 2, "SPIKE_CAPTURE_7943_T2", 1_000_000L))
        assertEquals(0, b.confirmedTier8153(key, ladder))
        assertEquals(0.0, b.bankedNetSol8153(key), 0.0)
    }

    @Test fun restartBeforeAndAfterSendKeepsExactReservationAndDuplicateFillBooksOnce() {
        for (sent in listOf(false, true)) {
            val b = RunnerRungBook8153(); val r = request(b)
            assertTrue(b.signed9043(r, "sig-restart-$sent", raw(100), 102L))
            if (sent) assertTrue(b.submitted9043(r, "sig-restart-$sent", 103L))
            val restored = RunnerRungBook8153(); restored.restore8153(b.encode8153())
            assertEquals(b.pending8153(key), restored.pending8153(key))
            assertFalse(restored.settle8153(key, false, 1_000_000L))
            assertTrue(restored.confirm8153(key, ladder, 1, "sig-restart-$sent", .3, .1, raw(100)))
            val again = RunnerRungBook8153(); again.restore8153(restored.encode8153())
            assertFalse(again.confirm8153(key, ladder, 1, "sig-restart-$sent", .3, .1, raw(100)))
            assertEquals(.2, again.bankedNetSol8153(key), 1e-12)
        }
    }

    @Test fun signedLedgerCanRecoverReservationIfRungCheckpointWasLost() {
        val b = RunnerRungBook8153(); val r = request(b)
        val cold = RunnerRungBook8153()
        assertTrue(cold.signed9043(RunnerIdentity9043.parse(r.attemptId)!!, "sig-cold", raw(100), 102L, recovering = true))
        assertFalse(cold.settle8153(key, false, 1_000_000L))
        assertFalse(cold.request8153(key, ladder, 1, r.reason, 1_000_000L))
    }

    @Test fun provenFailureReleasesOnlyItsOwnSignatureAndRetainsBackoffAcrossRestart() {
        val b = RunnerRungBook8153(); val r = request(b)
        assertTrue(b.signed9043(r, "sig-failed", raw(100), 102L))
        assertFalse(b.failed9043(r, "unrelated", 110L))
        assertTrue(b.failed9043(r, "sig-failed", 110L))
        val restored = RunnerRungBook8153(); restored.restore8153(b.encode8153())
        assertFalse(restored.request8153(key, ladder, 1, r.reason, 500L))
        val next = request(restored, 1_111L)
        assertTrue(restored.signed9043(next, "new-signature", raw(100), 1_112L))
        assertFalse(restored.failed9043(r, "sig-failed", 1_113L))
        assertEquals("new-signature", restored.pending8153(key)!!.signature)
    }

    @Test fun partialFillBooksActualQuantityAndRetriesOnlyRemainingRawUnits() {
        val b = RunnerRungBook8153(); val r = request(b)
        assertTrue(b.signed9043(r, "sig-underfill", raw(100), 102L))
        assertTrue(b.confirm8153(key, ladder, 1, "sig-underfill", .12, .06, raw(60)))
        assertEquals(0, b.confirmedTier8153(key, ladder))
        assertEquals(raw(40), b.remainder9043(key, ladder)!!.raw)
        val cold = RunnerRungBook8153(); cold.restore8153(b.encode8153())
        val retry = request(cold, 200L)
        assertEquals(raw(40), cold.pending8153(key)!!.requestedRaw)
        assertFalse("never resell the original 100", cold.signed9043(retry, "sig-over", raw(100), 202L))
        assertTrue(cold.signed9043(retry, "sig-remainder", raw(40), 202L))
        assertTrue(cold.confirm8153(key, ladder, 1, "sig-remainder", .08, .04, raw(40)))
        assertEquals(1, cold.confirmedTier8153(key, ladder))
        assertNull(cold.remainder9043(key, ladder))
        assertEquals(.1, cold.bankedNetSol8153(key), 1e-12)
    }

    @Test fun modesAndSameClockPositionsCannotBorrowRungsOrIntentIdentity() {
        val paper = RunnerIdentity9043.positionKey("mint9043", 10L, true, "PAPER:lot")
        val replacement = RunnerIdentity9043.positionKey("mint9043", 10L, false, "LIVE:lot-2")
        assertNotEquals(key, paper); assertNotEquals(key, replacement)
        val b = RunnerRungBook8153(); val r = request(b)
        b.confirm8153(key, ladder, 1, "sig-live", .2, .1)
        assertEquals(0, b.confirmedTier8153(paper, ladder))
        assertEquals(0, b.confirmedTier8153(replacement, ladder))
        assertEquals(r, RunnerIdentity9043.parse(r.attemptId))
        assertFalse(r.attemptId.contains('|'))
        assertTrue(b.statusLine8153().contains("LIVE:requested=1"))
    }

    @Test fun legacyStateNeedsUniqueCanonicalOwnerBeforeMigration() {
        val b = RunnerRungBook8153(); val legacy = "mint9043|10"
        b.confirm8153(legacy, ladder, 1, "legacy-fill", .2, .1)
        assertFalse(b.migrateLegacy9043(legacy, key, false))
        assertEquals(0, b.confirmedTier8153(key, ladder))
        assertTrue(b.migrateLegacy9043(legacy, key, true))
        assertEquals(1, b.confirmedTier8153(key, ladder))
        assertFalse(b.migrateLegacy9043(legacy, key, true))
    }

    @Test fun capacityPressureNeverEvictsAnUnknownSignedReservation() {
        val b = RunnerRungBook8153(cap = 1); val r = request(b)
        assertTrue(b.signed9043(r, "sig-kept", raw(100), 102L))
        val other = RunnerIdentity9043.positionKey("another-mint", 10L, false, "another-position")
        assertFalse(b.request8153(other, ladder, 1, r.reason, 103L))
        assertEquals("sig-kept", b.pending8153(key)!!.signature)
        assertFalse(b.settle8153(key, false, 1_000_000L))
    }
}
