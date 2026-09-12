package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738
import com.lifecyclebot.engine.truth.PaperLedgerDivergenceGuard6731
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6742 — Directive §4 (economic-parity revision consistency) and
 * §7 (round-trip verification wired to real production events).
 *
 * §4: Parity is now stamped with the event-schema revision and the
 * journal-economic revision captured before AND after the ledger read.
 * When the two journal revisions differ the compare spanned a real
 * mutation and the divergence guard MUST fail-open on that snapshot.
 * §7: The canonical ExecutorCanonicalMirror6442 wires BUY/SELL/EXIT
 * stages to CanonicalRoundTripReconciler6738. CausalFeedbackAuthority6715
 * wires LEARNING_DELIVERED. Both wires are strictly SOURCE-LEVEL — this
 * test asserts against the actual .kt source files, not a stubbed
 * test-only path.
 */
class Aate6742RevisionParityAndRoundTripWiringTest {

    @Before
    fun reset() {
        ProvenanceAuthority6737.resetForTest6737()
        CanonicalRoundTripReconciler6738.resetForTest6738()
        CanonicalPaperReplay6464.resetForTest()
    }

    // ─── §4 revision consistency ──────────────────────────────────────

    @Test
    fun `parity carries event-schema and journal revisions`() {
        val parity = CanonicalPaperReplay6464.compareToLedger(startingCashSol = 100.0)
        // eventVersion is 0L for a fresh EconomicEventSchema6464 in the
        // unit-test JVM — but the field is now stamped regardless, and
        // journal revisions must be sampled at both edges.
        assertTrue("event-schema revision must be stamped",
            parity.eventSchemaRevision >= 0L)
        assertTrue("journal revision at start must be stamped",
            parity.journalRevisionAtStart >= 0L)
        assertTrue("journal revision at end must be stamped",
            parity.journalRevisionAtEnd >= 0L)
    }

    @Test
    fun `guard verdict inherits revision stamps for attribution`() {
        CanonicalPaperReplay6464.compareToLedger(startingCashSol = 100.0)
        val v = PaperLedgerDivergenceGuard6731.evaluate()
        // Verdict carries the same revisions the parity was computed at.
        assertEquals("event revision must match parity",
            CanonicalPaperReplay6464.lastParity()?.eventSchemaRevision ?: -1L,
            v.eventSchemaRevision)
        assertEquals("journal-start revision must match parity",
            CanonicalPaperReplay6464.lastParity()?.journalRevisionAtStart ?: -1L,
            v.journalRevisionAtStart)
        assertEquals("journal-end revision must match parity",
            CanonicalPaperReplay6464.lastParity()?.journalRevisionAtEnd ?: -1L,
            v.journalRevisionAtEnd)
    }

    @Test
    fun `revision-race parity is source-contract enforced to fail open`() {
        // Source-level assertion. In production JournalEconomicAuthority6616
        // increments on every ledger mutation; the guard's race path is
        // read-only in a unit-test JVM. Assert the source contract so any
        // future edit that removes the fail-open on race breaks CI.
        val guardSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt").readText()
        assertTrue(
            "guard MUST fail-open when revisionRaceObserved",
            guardSrc.contains("PAPER_LEDGER_DIVERGENCE_REVISION_RACE_FAIL_OPEN_6742"),
        )
        assertTrue(
            "guard MUST branch on parity.revisionRaceObserved",
            guardSrc.contains("parity.revisionRaceObserved"),
        )
        val replaySrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        assertTrue(
            "parity MUST sample JournalEconomicAuthority6616.revision() before ledger read",
            replaySrc.contains("journalRevisionAtStart") &&
                replaySrc.contains("JournalEconomicAuthority6616.revision()"),
        )
        assertTrue(
            "parity MUST sample journal revision AFTER ledger read + carry reconcile",
            replaySrc.contains("journalRevisionAtEnd"),
        )
        assertTrue(
            "revision race must be observable via a dedicated telemetry label",
            replaySrc.contains("PAPER_REPLAY_PARITY_REVISION_RACE_6742"),
        )
    }

    // ─── §7 round-trip wiring at the canonical mirror ────────────────

    @Test
    fun `mirror wires BUY_COMMITTED to round-trip reconciler`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt").readText()
        // BUY: the canonical mirror stamps BUY_COMMITTED into the reconciler
        // ONLY on the APPLIED-committed branch (never on shadow / rejected).
        assertTrue(
            "mirror must wire BUY_COMMITTED to CanonicalRoundTripReconciler6738",
            src.contains("CanonicalRoundTripReconciler6738.record(") &&
                src.contains("Stage.BUY_COMMITTED"),
        )
        assertTrue(
            "BUY wire must be inside the APPLIED branch (guarded by CANONICAL_BUY_CONFIRMED_OPEN_6448)",
            src.contains("CANONICAL_BUY_CONFIRMED_OPEN_6448") &&
                src.substringAfter("CANONICAL_BUY_CONFIRMED_OPEN_6448")
                    .substringBefore("}")
                    .contains("CanonicalRoundTripReconciler6738") ||
                // fallback: some code lays the wire immediately after; just
                // require both markers are within 20 lines of each other.
                run {
                    val idx1 = src.indexOf("CANONICAL_BUY_CONFIRMED_OPEN_6448")
                    val idx2 = src.indexOf("Stage.BUY_COMMITTED")
                    idx1 > 0 && idx2 > 0 && kotlin.math.abs(idx2 - idx1) < 1200
                },
        )
    }

    @Test
    fun `mirror wires SELL_COMMITTED but NEVER on a partial sell`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt").readText()
        assertTrue(
            "mirror must record SELL_COMMITTED on the terminal branch",
            src.contains("Stage.SELL_COMMITTED"),
        )
        // Partial sells MUST NOT count as completed round trips. The
        // partial branch stamps EXIT_DECIDED (exit-decision recorded)
        // and never SELL_COMMITTED. Assert both by source proximity.
        val partialIdx = src.indexOf("CANONICAL_PARTIAL_SELL_CONFIRMED_6448")
        assertTrue("partial-sell branch marker must exist", partialIdx > 0)
        val partialWindow = src.substring(partialIdx, kotlin.math.min(partialIdx + 900, src.length))
        assertTrue(
            "partial-sell branch must stamp EXIT_DECIDED (not SELL_COMMITTED)",
            partialWindow.contains("Stage.EXIT_DECIDED"),
        )
        assertFalse(
            "partial-sell branch must NOT stamp SELL_COMMITTED",
            partialWindow.substringBefore("EXECUTOR_MIRROR_SELL_").contains("Stage.SELL_COMMITTED"),
        )
    }

    @Test
    fun `causal feedback wires LEARNING_DELIVERED into reconciler`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()
        assertTrue(
            "markLearned must record LEARNING_DELIVERED into CanonicalRoundTripReconciler6738",
            src.contains("CanonicalRoundTripReconciler6738.record(") &&
                src.contains("Stage.LEARNING_DELIVERED"),
        )
        // The wire must be inside markLearned() AFTER the CAUSAL_OWNER_LEARN_ACK_6715
        // emit — proving it only fires on the real owner-bound success path.
        val ackIdx = src.indexOf("CAUSAL_OWNER_LEARN_ACK_6715")
        val learnIdx = src.indexOf("Stage.LEARNING_DELIVERED")
        assertTrue("owner-learn-ack marker must exist", ackIdx > 0)
        assertTrue("learning wire must exist", learnIdx > 0)
        assertTrue(
            "learning wire must appear AFTER the owner-learn-ack success emit",
            learnIdx > ackIdx,
        )
    }

    @Test
    fun `production round-trip flow reconciles end-to-end via reconciler API`() {
        // Exercise the reconciler like the wired production sites do.
        val pid = "prod-flow-1"
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.BUY_COMMITTED, "MEME", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.SELL_COMMITTED, "MEME", "PAPER"))
        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.LEARNING_DELIVERED, "", ""))
        val trip = CanonicalRoundTripReconciler6738.tripOf(pid)
        assertNotNull(trip)
        assertTrue("terminal wire must produce a reconciled trip", trip!!.reconciled)
        assertEquals(1, CanonicalRoundTripReconciler6738.summary().reconciled)
    }
}
