package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexOutcomeBook8372
import com.lifecyclebot.engine.cortex.CortexOutcomeBook8372.Action
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Execute the production outcome-ownership state machine against real learner books. */
class Aate8372CortexOutcomeBookTest {
    private val shadowId = "MintA|EXPRESS|1000"
    private val labelId = "MintA|EXPRESS|1100"

    @Test fun forwardFirstThenSelectedShadowCorrectsExactlyOneWinOrLossForEveryFrozenVerdict() {
        for (bucket in CortexScoreboard7885.Bucket.values()) for (terminal in listOf(45.0, -19.0)) {
            val original = CortexOutcomeBook8372()
            assertTrue(original.reserve(shadowId, 1_000L))
            assertTrue(original.bind(shadowId, labelId))
            assertEquals(Action.LEARN, original.forwardPlan(labelId).action)
            val ids = listOf("FLOW")
            val edges = listOf(doubleArrayOf(0.5))
            val raw = doubleArrayOf(1.0)
            val ledger0 = CortexLedger7885()
            val board0 = CortexScoreboard7885()
            val calibration0 = CortexCalibration7901()
            val trace = ledger0.grade("EXPRESS", ids, edges, raw, -4.0, -1.0)!!
            val admitted = bucket == CortexScoreboard7885.Bucket.STRONG
            val stamps = board0.record("EXPRESS", bucket, admitted, -4.0, -1.0)!!
            val calStamp = calibration0.learn("EXPRESS", 8.0, 0.0, -4.0, 8.0)
            original.learnedForward(labelId)

            // First label and selected exit may straddle an app restart.
            val ownership = CortexOutcomeBook8372().also { it.decode(JSONObject(original.encode().toString())) }
            val ledger = CortexLedger7885().also { it.decode(JSONObject(ledger0.encode().toString())) }
            val board = CortexScoreboard7885().also { it.decode(JSONObject(board0.encode().toString())) }
            val calibration = CortexCalibration7901().also { it.decode(JSONObject(calibration0.encode().toString())) }
            val plan = ownership.shadowPlan(shadowId)
            assertEquals(Action.REVISE, plan.action)
            assertEquals(labelId, plan.decisionId)
            ledger.revise8006("EXPRESS", ids, edges, raw, trace, -4.0, terminal, -1.0, terminal + 3.0)
            board.revise8006("EXPRESS", bucket, admitted, -4.0, terminal, -1.0, terminal + 3.0, stamps)
            calibration.revise8322("EXPRESS", 8.0, 0.0, -4.0, terminal, calStamp)
            assertTrue(ownership.finishedShadow(shadowId, true, plan.decisionId))

            assertEquals(1.0, ledger.lanes["EXPRESS"]!!.n, 0.0)
            assertEquals(terminal, ledger.lanes["EXPRESS"]!!.mean(), 1e-9)
            assertEquals(1, ledger.seats["FLOW|EXPRESS"]!!.scored)
            assertEquals(1.0, board.books["EXPRESS"]!!.byBucket[bucket.ordinal].n, 0.0)
            assertEquals(terminal, board.books["EXPRESS"]!!.byBucket[bucket.ordinal].mean(), 1e-9)
            assertEquals(1, calibration.netFit8372("EXPRESS")!!.n)
            assertEquals(terminal, calibration.netFit8372("EXPRESS")!!.expectedNetPct8372(8.0), 1e-9)
            repeat(3) {
                assertEquals(Action.OBSERVE_ONLY, ownership.shadowPlan(shadowId).action)
                assertEquals(Action.OBSERVE_ONLY, ownership.forwardPlan(labelId).action)
            }
            assertTrue(ownership.shadowHasLearnedFor(labelId))
            assertTrue("No simulated reward becomes realised live profit", board.realized.isEmpty())
        }
    }

    @Test fun shadowFirstKeepsItsOwnDecisionIdentityWhenTheForwardObservationBindsLater() {
        val book = CortexOutcomeBook8372()
        book.reserve(shadowId, 1_000L)
        assertEquals(CortexOutcomeBook8372.Plan(Action.LEARN, shadowId), book.shadowPlan(shadowId))
        book.finishedShadow(shadowId, true, shadowId)
        assertTrue(book.bind(shadowId, labelId))
        assertEquals(CortexOutcomeBook8372.Plan(Action.OBSERVE_ONLY, shadowId), book.forwardPlan(labelId))
        assertTrue(book.shadowHasLearnedFor(labelId))
        val restored = CortexOutcomeBook8372().also { it.decode(JSONObject(book.encode().toString())) }
        assertEquals(Action.OBSERVE_ONLY, restored.shadowPlan(shadowId).action)
        assertEquals(CortexOutcomeBook8372.Plan(Action.OBSERVE_ONLY, shadowId), restored.forwardPlan(labelId))
    }

    @Test fun unavailableShadowDoesNotSuppressAValidLaterForwardLabel() {
        val book = CortexOutcomeBook8372()
        book.reserve(shadowId, 1_000L)
        book.bind(shadowId, labelId)
        assertTrue(book.finishedShadow(shadowId, learned = false))
        assertEquals(Action.OBSERVE_ONLY, book.shadowPlan(shadowId).action)
        assertEquals(CortexOutcomeBook8372.Plan(Action.LEARN, labelId), book.forwardPlan(labelId))
        book.learnedForward(labelId)
        assertEquals(Action.OBSERVE_ONLY, book.forwardPlan(labelId).action)
        assertFalse(book.shadowHasLearnedFor(labelId))
    }

    @Test fun tokenLaneAndEpisodeAreIndependentAndNoBindingCanRedirectAnotherTokensReward() {
        val book = CortexOutcomeBook8372()
        val ids = listOf("MintA|EXPRESS|1000", "MintB|EXPRESS|1000", "MintA|QUALITY|1000", "MintA|EXPRESS|2000")
        ids.forEachIndexed { i, id ->
            assertTrue(book.reserve(id, 1_000L + i))
            assertTrue(book.bind(id, "$id-label"))
        }
        book.finishedShadow(ids[0], true)
        assertEquals(Action.OBSERVE_ONLY, book.forwardPlan("${ids[0]}-label").action)
        ids.drop(1).forEach { assertEquals(Action.LEARN, book.forwardPlan("$it-label").action) }
        assertFalse(book.bind(ids[1], "${ids[0]}-label"))
        assertEquals(4, book.size())
        assertEquals(Action.OBSERVE_ONLY, book.shadowPlan(ids[0]).action)
    }

    @Test fun alreadyLearnedForwardBindingSelectsRevisionButNeverMergesTwoExistingContributions() {
        val forward = CortexOutcomeBook8372()
        forward.reserve(shadowId, 1_000L)
        assertTrue(forward.bind(shadowId, labelId, forwardAlreadyLearned = true))
        assertEquals(CortexOutcomeBook8372.Plan(Action.REVISE, labelId), forward.shadowPlan(shadowId))
        val shadow = CortexOutcomeBook8372()
        shadow.reserve(shadowId, 1_000L)
        shadow.finishedShadow(shadowId, true, shadowId)
        assertFalse(shadow.bind(shadowId, labelId, forwardAlreadyLearned = true))
    }

    @Test fun unfinishedReservationsAreNotEvictedAndOnlyUnclaimedReservationsCanCancel() {
        val book = CortexOutcomeBook8372(capacity = 2)
        assertTrue(book.reserve("A|EXPRESS|1", 1L))
        assertTrue(book.reserve("B|EXPRESS|2", 2L))
        assertFalse(book.reserve("C|EXPRESS|3", 3L))
        assertEquals(1L, book.full)
        assertEquals(0, book.prune(10_000L))
        assertTrue(book.cancel("B|EXPRESS|2"))
        assertTrue(book.reserve("C|EXPRESS|3", 3L))
        book.bind("A|EXPRESS|1", "A-label")
        assertFalse(book.cancel("A|EXPRESS|1"))
        book.finishedShadow("C|EXPRESS|3", false)
        assertEquals(1, book.prune(10_000L))
        assertTrue(book.contains("A|EXPRESS|1"))
    }

    @Test fun unknownTerminalWaitsForItsPredictionRatherThanInventingAModelInput() {
        val book = CortexOutcomeBook8372()
        assertEquals(CortexOutcomeBook8372.Plan(Action.RETRY, shadowId), book.shadowPlan(shadowId))
        assertFalse(book.finishedShadow(shadowId, true))
        assertFalse(book.bind(shadowId, labelId))
        assertEquals(0, book.size())
    }

    @Test fun restartDoesNotChangeUnavailableOrPendingReceiptSemantics() {
        val original = CortexOutcomeBook8372()
        original.reserve(shadowId, 1_000L)
        original.bind(shadowId, labelId)
        original.finishedShadow(shadowId, false)
        original.reserve("MintB|QUALITY|2000", 2_000L)
        val restored = CortexOutcomeBook8372().also { it.decode(JSONObject(original.encode().toString())) }
        assertEquals(Action.LEARN, restored.forwardPlan(labelId).action)
        assertEquals(Action.OBSERVE_ONLY, restored.shadowPlan(shadowId).action)
        assertEquals(Action.LEARN, restored.shadowPlan("MintB|QUALITY|2000").action)
        assertEquals(2, restored.size())
    }

    @Test fun duplicateReservationDoesNotOwnOrReplaceTheOriginalEpoch() {
        val book = CortexOutcomeBook8372(capacity = 1)
        assertTrue(book.reserve(shadowId, 1_000L))
        assertTrue(book.bind(shadowId, labelId))
        assertFalse("duplicate must not enter the opener/cancel branch", book.reserve(shadowId, 9_000L))
        assertEquals(0L, book.full)
        assertEquals(CortexOutcomeBook8372.Plan(Action.LEARN, labelId), book.shadowPlan(shadowId))
        assertEquals(1_000L, book.encode().getJSONArray("rows").getJSONArray(0).getLong(1))
        val restored = CortexOutcomeBook8372(capacity = 1).also { it.decode(JSONObject(book.encode().toString())) }
        assertFalse(restored.reserve(shadowId, 1_000L))
        assertEquals(CortexOutcomeBook8372.Plan(Action.LEARN, labelId), restored.shadowPlan(shadowId))
    }

    @Test fun acknowledgedTerminalCapacityIsReclaimedOnlyWithDurableTrainingOwnership() {
        val book = CortexOutcomeBook8372(capacity = 2)
        assertTrue(book.reserve("still-open", 1L))
        assertFalse(book.retireAcknowledged8372("still-open", trainingOwnershipDurable = true))
        repeat(100) { i ->
            val id = "Mint$i|EXPRESS|${i + 2}"
            val alias = "$id-forward"
            assertTrue("completed receipts cannot exhaust active capacity", book.reserve(id, i + 2L))
            assertTrue(book.bind(id, alias))
            book.learnedForward(alias)
            assertTrue(book.finishedShadow(id, learned = true, trainedId = alias))
            assertEquals(CortexOutcomeBook8372.Terminal8372(id, alias, alias, true), book.terminal8372(id))
            assertFalse("a plain ACK is not proof of preserved forward suppression", book.retireAcknowledged8372(id, false))
            assertEquals(Action.OBSERVE_ONLY, book.forwardPlan(alias).action)
            assertTrue(book.retireAcknowledged8372(id, trainingOwnershipDurable = true))
            assertNull(book.terminal8372(id))
            assertEquals(1, book.size())
        }
        assertTrue(book.contains("still-open"))
        assertEquals(0L, book.full)
    }

    @Test fun unavailableRetirementDoesNotClaimALearnedRewardAndExistingForwardOwnershipIsProtected() {
        val book = CortexOutcomeBook8372(capacity = 1)
        book.reserve(shadowId, 1_000L)
        book.bind(shadowId, labelId)
        book.finishedShadow(shadowId, learned = false)
        assertEquals(CortexOutcomeBook8372.Terminal8372(shadowId, labelId, "", false), book.terminal8372(shadowId))
        assertTrue(book.retireAcknowledged8372(shadowId, trainingOwnershipDurable = false))
        assertEquals(CortexOutcomeBook8372.Plan(Action.LEARN, labelId), book.forwardPlan(labelId))
        book.reserve(shadowId, 1_000L)
        book.bind(shadowId, labelId)
        book.learnedForward(labelId)
        book.finishedShadow(shadowId, learned = false)
        assertFalse("already learned forward reward also needs an ownership receipt", book.retireAcknowledged8372(shadowId, false))
        assertTrue(book.retireAcknowledged8372(shadowId, trainingOwnershipDurable = true))
    }
}
