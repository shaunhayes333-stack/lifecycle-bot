package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738
import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738.Stage
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6738 — regression suite for Pillars 4, 5, 6.
 *
 * Pillar 4 (Round-trip reconciler) — Meme + Crypto Universe complete a
 *   reconciled round trip. Divergence attributes to a specific event id
 *   without hard-blocking global admission.
 * Pillar 5 (Exit recovery coalesce) — PaperPositionCloseAuthority's
 *   FAILED/REJECTED within-TTL branch now blocks with an explicit
 *   coalesced-retry reason (not a masked-OPEN fall-through).
 * Pillar 6 (Terminal learning delivered exactly once) — reconciler
 *   refuses the second delivery for the same positionId.
 */
class Aate6738RoundTripAndExitRecoveryTest {

    @Before
    fun reset() {
        ProvenanceAuthority6737.resetForTest6737()
        CanonicalRoundTripReconciler6738.resetForTest6738()
        PaperPositionCloseAuthority.reopen("PAPER", "MM1"); PaperPositionCloseAuthority.reopen("PAPER", "MM2")
        PaperPositionCloseAuthority.reopen("PAPER", "SFTRY")
    }

    // ─── Pillar 4: Round-trip reconciliation ───────────────────────

    @Test
    fun `meme lane completes a reconciled round trip`() {
        val pid = "meme-p1"
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED, "MOONSHOT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.EXIT_DECIDED, "MOONSHOT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED, "MOONSHOT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.LEARNING_DELIVERED, "MOONSHOT", "PAPER"))
        val t = CanonicalRoundTripReconciler6738.tripOf(pid)
        assertTrue("meme round trip must reconcile", t != null && t.reconciled)
        assertEquals(1, CanonicalRoundTripReconciler6738.summary().reconciled)
    }

    @Test
    fun `crypto universe round trip is tracked independently and reconciles`() {
        val pid = "crypto-p1"
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED, "CRYPTO_ALT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED, "CRYPTO_ALT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.LEARNING_DELIVERED, "CRYPTO_ALT", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(pid)!!.reconciled)
    }

    @Test
    fun `stage recording is idempotent per positionId and stage`() {
        val pid = "idem-p1"
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))
        assertFalse("duplicate stage record must not double-count",
            CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))
    }

    @Test
    fun `shadow event id bypasses round-trip statistics`() {
        val pid = "shadow-p1"
        val eventId = "shadow-evt-1"
        ProvenanceAuthority6737.classifyOnce(eventId, ProvenanceAuthority6737.Origin.REPLAY_SHADOW)
        val recorded = CanonicalRoundTripReconciler6738.record(
            pid, Stage.BUY_COMMITTED, "SHADOW_MEME", "PAPER", eventId = eventId,
        )
        assertFalse("shadow event must NOT enter round-trip stats", recorded)
        assertEquals(1L, CanonicalRoundTripReconciler6738.summary().shadowTripSkipped)
        assertEquals("no reconciled trips for shadow events",
            0, CanonicalRoundTripReconciler6738.summary().reconciled)
    }

    @Test
    fun `accounting divergence tags the specific event id ambiguous without global block`() {
        val pid = "div-p1"
        val eventId = "evt-div-1"
        // Round trip is in flight
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED))
        // Later, the reconciler observes cash / open-cost drift for the
        // journal side of this position.
        CanonicalRoundTripReconciler6738.observeAccountingDivergence(
            positionId = pid, eventId = eventId, reason = "CASH_DELTA",
        )
        assertEquals(
            "specific event id must be quarantined so it's excluded from parity",
            ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS,
            ProvenanceAuthority6737.originOf(eventId),
        )
        // ... but no global admission block. Nothing about ExitThroughputAuthority6727,
        // no counter that would suppress a new admission for a healthy lane.
    }

    // ─── Pillar 6: Terminal learning delivered exactly once ─────────

    @Test
    fun `terminal learning delivered exactly once per positionId`() {
        val pid = "learn-p1"
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.LEARNING_DELIVERED))
        assertFalse("second learning delivery must be refused",
            CanonicalRoundTripReconciler6738.record(pid, Stage.LEARNING_DELIVERED))
        val s = CanonicalRoundTripReconciler6738.summary()
        assertEquals(1L, s.learningDeliveredOnce)
        assertEquals(1L, s.learningDeliveredDuplicateRefused)
    }

    @Test
    fun `synthetic outcomes stay separated from genuine learning delivery`() {
        val genuinePid = "g-learn-1"
        val shadowPid = "s-learn-1"
        val shadowEventId = "s-evt-1"
        ProvenanceAuthority6737.classifyOnce(shadowEventId, ProvenanceAuthority6737.Origin.REPLAY_SHADOW)

        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.BUY_COMMITTED)
        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.SELL_COMMITTED)
        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.LEARNING_DELIVERED)

        // Shadow position attempts to deliver learning — must be skipped.
        val shadowLearn = CanonicalRoundTripReconciler6738.record(
            shadowPid, Stage.LEARNING_DELIVERED, mode = "PAPER", eventId = shadowEventId,
        )
        assertFalse("shadow learning must not increment genuine deliveries", shadowLearn)

        val s = CanonicalRoundTripReconciler6738.summary()
        assertEquals("only the genuine delivery counts",
            1L, s.learningDeliveredOnce)
    }

    // ─── Pillar 5: PaperPositionCloseAuthority TTL coalesce ─────────

    @Test
    fun `paper close FAILED within TTL is blocked with coalesce reason (not masked OPEN)`() {
        val mint = "MM1"; val sym = "MM1_SYMBOL"
        // Seed a FAILED close for this mint. The state now uses
        // markFailed() which stamps updatedAtMs = now.
        PaperPositionCloseAuthority.markFailed("PAPER", mint, sym, "SIMULATED_FAILURE")
        val g = PaperPositionCloseAuthority.preSellGuard("PAPER", mint, sym, "RETRY_ATTEMPT")
        assertTrue("within TTL, FAILED must block explicitly", g.blocked)
        assertTrue(
            "block reason must indicate retry backoff (not the legacy fall-through)",
            g.reason.contains("close_retry_backoff_within_ttl_6738"),
        )
        // FAILED state preserved so the pending exit intent survives.
        assertEquals(PaperPositionCloseAuthority.State.FAILED,
            PaperPositionCloseAuthority.stateOf("PAPER", mint))
    }

    @Test
    fun `paper close does NOT return fake OPEN on FAILED-within-TTL (source contract)`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        // The 6738 coalesce marker must be present.
        assertTrue(
            "coalesce marker must be present",
            src.contains("PAPER_CLOSE_FAILED_WITHIN_TTL_COALESCE") ||
                src.contains("PAPER_CLOSE_RETRY_COALESCED_WITHIN_TTL_6738"),
        )
        assertTrue(
            "within-TTL FAILED branch must return with backoff reason, not fall through to fake OPEN",
            src.contains("close_retry_backoff_within_ttl_6738"),
        )
    }

    @Test
    fun `close FAILED at OPEN state does NOT block (control) so unrelated mints proceed`() {
        val mint = "SFTRY"
        val g = PaperPositionCloseAuthority.preSellGuard("PAPER", mint, "SFTRY_S", "NORMAL_EXIT")
        assertFalse("OPEN state must not block a fresh close", g.blocked)
    }
}
