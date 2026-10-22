package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexEntryReadBook8076
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 8198 release-hold contract: the Cortex OutcomeTruth close is a registered, retryable canonical consumer with
 * replay-safe grading and a verified durable outcome per delivery. A green badge with a swallowed direct callback
 * is not merge authorization.
 */
class Aate8198CortexCloseDeliveryTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")
    private fun src(rel: String) = File(root, rel).readText()

    private fun env(tradeId: String, positionId: String = tradeId, mode: String = "live", proof: String = "LIVE_FINALIZED",
                    mint: String = "mint$tradeId", lane: String = "MOONSHOT", eligible: Boolean = true, retPct: Double = 12.5) =
        CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = tradeId, atMs = System.currentTimeMillis(), realizedPnlSol = 0.05, realizedReturnPct = retPct,
            mint = mint, lane = lane, positionId = positionId, mode = mode, proofState = proof,
            terminal = true, learningEligible = eligible,
        )

    // ── 1. The swallow is gone; the delivery is a registered consumer ─────────────────────────────────

    @Test fun publishHasNoSwallowedCortexDirectCallAndRegistersTheConsumer() {
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertFalse(bus.contains("Cortex7885.onCanonicalClose(env)"))
        assertTrue(bus.contains("\"CortexOutcome7885\""))
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bridge.contains("\"CortexOutcome7885\" -> deliverToCortexOutcome7885(env)"))
        // it is a LEARNING consumer: consumer-bridge quarantine / reset-epoch / exact-economics gates apply
        val nonLearning = bridge.substringAfter("NON_LEARNING_CONSUMERS").take(160)
        assertFalse(nonLearning.contains("CortexOutcome7885"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("enum class CloseDelivery8198 { ACKED, RETRY }"))
    }

    // ── 2. The receiver is replay-safe, receipt-gated and durability-acknowledging ────────────────────

    @Test fun theReceiverGatesBeforeItMutates() {
        val c = src("engine/cortex/Cortex7885.kt")
        val close = c.substringAfter("fun onCanonicalClose(").substringBefore("// ── persistence ──")
        fun at(lit: String) = close.indexOf(lit).also { assertTrue("missing: $lit", it >= 0) }
        val receipts = at("tradeId in closeReceipts8198")
        val durable = at("LearningPersistence.load(closeReceipt8198(tradeId))")
        val gate = at("tradeId !in uncommittedCloseGrades8198")
        val gateAdd = at("uncommittedCloseGrades8198.add(tradeId)")
        val peek = at("positionReads8076.peek(env.positionId, closeKey7931, mode)")
        val mutations = at("board.recordRealized(mode, a.lane, a.bucket")
        // the grade-path consume (the ineligible earlier release is a different, legitimate branch)
        val take = close.indexOf("positionReads8076.take(env.positionId, closeKey7931, mode)", peek)
            .also { assertTrue("no grade-path take after the peek", it > peek) }
        val commit = c.indexOf("private fun durableCloseCommit8198(receipt: String): Boolean")
        assertTrue(commit > 0)
        // ordering: committed-receipt gate, durably-committed-grade gate, then the first-delivery gate BEFORE mutations;
        // the read is only consumed inside the mutation block (peek left it intact on any earlier failure).
        assertTrue(receipts < durable && durable < gate && gate < gateAdd && gateAdd < peek && peek < take && take < mutations)
    }

    @Test fun theDurableOutcomeCommitsGradeStateAndReceiptInOneTransaction() {
        val c = src("engine/cortex/Cortex7885.kt")
        val commitFn = c.substringAfter("private fun durableCloseCommit8198(receipt: String): Boolean")
            .substringBefore("\n    }\n")
        assertTrue(commitFn.contains("LearningPersistence.commitOnce8090(receipt, blobs)"))
        // the receipt binds the exact graded state: the same three snapshots the periodic write takes
        assertTrue(commitFn.contains("PERSIST_KEY]"))
        assertTrue(commitFn.contains("PENDING_PERSIST_KEY]"))
        assertTrue(commitFn.contains("GRADES_KEY_8144]"))
        assertTrue(commitFn.contains("CORTEX_CLOSE_DURABLE_RETRY_8198"))
        assertFalse(commitFn.contains("GlobalScope"))
        // the periodic writer never splits graded state from its receipt while a close grade is uncommitted
        val persistFn = c.substringAfter("private fun persistBlocking8094() { synchronized(persistLock8325) {")  // superseded by V5.0.8325 (persist lock, not the Cortex monitor)
        assertTrue(persistFn.contains("if (uncommittedCloseGrades8198.isNotEmpty()) return"))
    }

    @Test fun aMidGradeFailureIsATerminalExclusionNeverAnAckOrADoubleGrade() {
        val close = src("engine/cortex/Cortex7885.kt").substringAfter("fun onCanonicalClose(").substringBefore("// ── persistence ──")
        // before the first mutation: clean retry from the still-bound peeked read
        assertTrue(close.contains("CORTEX_CLOSE_GRADE_FAILED_RETRYABLE_8198"))
        assertTrue(close.contains("uncommittedCloseGrades8198.remove(tradeId)"))
        // after the first (additive) mutation: explicit durable exclusion — never an ACK, never re-graded
        assertTrue(close.contains("CORTEX_CLOSE_GRADE_FAILED_TERMINAL_8198"))
        assertTrue(close.contains("exclude("))
        assertTrue(close.substringAfter("CORTEX_CLOSE_GRADE_FAILED_TERMINAL_8198").contains("CloseDelivery8198.RETRY"))
    }

    // ── 3. Functional: duplicate delivery of an uncommitted close never grades twice ────────────────

    @Test fun duplicateDeliveryWhileTheCheckpointRefusesRegradesNothing() {
        val e = env("close_delivery_8198_a", mint = "uncommittedMint8198", positionId = "pos8198a")
        val first = Cortex7885.onCanonicalClose(e)
        // no store is open in the JVM test environment: the durable commit is honestly refused, never faked
        assertEquals(Cortex7885.CloseDelivery8198.RETRY, first)
        val unmatchedAfterFirst = Cortex7885.counter8198("REALIZED_UNMATCHED")
        // the bus redelivers the same envelope; the in-process grade gate must skip the grade, re-attempt only the commit
        val second = Cortex7885.onCanonicalClose(e)
        assertEquals(Cortex7885.CloseDelivery8198.RETRY, second)
        assertEquals(unmatchedAfterFirst, Cortex7885.counter8198("REALIZED_UNMATCHED"))
    }

    @Test fun anIneligibleTerminalIsAnExplicitReleaseAndAck() {
        val e = env("close_delivery_8198_ineligible", mint = "ineligibleMint8198", positionId = "pos8198in", eligible = false)
        assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(e))
        assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(e))  // re-delivery: harmless re-release
        assertTrue(Cortex7885.counter8198("REALIZED_NOT_ELIGIBLE") >= 2)
    }

    @Test fun anUnverifiedFinalityTerminalNeverGradesOrContradictsThe8082Contract() {
        val e = env("close_delivery_8198_unverified", mint = "unverifiedMint8198", positionId = "pos8198uv", proof = "UNKNOWN")
        assertEquals(Cortex7885.CloseDelivery8198.ACKED, Cortex7885.onCanonicalClose(e))
        assertTrue(Cortex7885.counter8198("REALIZED_UNVERIFIED_FINALITY_8082") >= 1)
    }

    // ── 4. The entry read book: peek keeps late evidence; consumption stays one-shot and isolated ─────

    @Test fun peekObservesWithoutConsumingAndKeepsPositionAndModeIsolated() {
        val book = CortexEntryReadBook8076<String>()
        assertTrue(book.bind("p1", "mintA", "LIVE", "assessment"))
        assertEquals("assessment", book.peek("p1", "mintA", "LIVE"))
        assertEquals("assessment", book.peek("p1", "mintA", "LIVE"))          // peek twice: still there
        assertNull(book.peek("p1", "mintA", "PAPER"))                        // mode isolation
        assertNull(book.peek("p1", "mintB", "LIVE"))                          // mint is verified
        assertNull(book.peek("p2", "mintA", "LIVE"))                          // position isolation
        assertTrue(book.bind("p2", "mintA", "LIVE", "other"))                // same mint, different position
        assertEquals("other", book.take("p2", "mintA", "LIVE"))
        assertNull(book.peek("p2", "mintA", "LIVE"))
        assertEquals("assessment", book.take("p1", "mintA", "LIVE"))          // take consumes exactly once
        assertNull(book.take("p1", "mintA", "LIVE"))
    }
}
