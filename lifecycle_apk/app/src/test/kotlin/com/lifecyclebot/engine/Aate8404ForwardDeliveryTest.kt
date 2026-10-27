package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import org.junit.Assert.*
import org.junit.Test

class Aate8404ForwardDeliveryTest {
    @Test fun anOlderDeliveryCannotRewindAnAcceptedRevisionAfterRestart() {
        val id = "revision8404|MOONSHOT|1000"
        val book = com.lifecyclebot.engine.cortex.CortexGradeBook8144()
        book.addDecision(com.lifecyclebot.engine.cortex.CortexGradeRecord8144('D', id, "revision8404|MOONSHOT",
            1000, "MOONSHOT", "BASE", "TREND", "EARLY", "LOW", "SMALL", 2, 30.0, true, true, false))
        fun proof(at: Long, net: Double): com.lifecyclebot.engine.cortex.CortexOutcomeProof8395 {
            val entry = com.lifecyclebot.engine.truth.LabelMark8386("revision8404", "curve", 1.0, 1000, 1000,
                "CHAIN", decimals = 6, independentBasis = true)
            return com.lifecyclebot.engine.cortex.CortexOutcomeProof8395(id, at - 1000, at, net, net + 5.0,
                entry, entry.copy(usd = 1 + (net + 5.0) / 100, observedAtMs = at, readAtMs = at))
        }
        fun apply(b: com.lifecyclebot.engine.cortex.CortexGradeBook8144, p: com.lifecyclebot.engine.cortex.CortexOutcomeProof8395) =
            b.labelOutcome8395(id, p.netPct, p.grossPct, p.netPct, true, p.horizonMs, p.observedAtMs, p.observedAtMs, p)
        val first = proof(301000, 5.0)
        assertTrue(apply(book, first).accepted)
        assertTrue(apply(book, proof(901000, 25.0)).accepted)
        val restored = com.lifecyclebot.engine.cortex.CortexGradeBook8144().also { it.decode(book.encode()) }
        assertEquals(com.lifecyclebot.engine.cortex.CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED, apply(restored, first))
        assertEquals(com.lifecyclebot.engine.cortex.CortexOutcomeDisposition8395.STALE_OUTCOME, apply(restored, proof(601000, -8.0)))
        assertEquals(25.0, restored.decision(id)!!.observedNetPct, 0.0)
        assertEquals(25.0, restored.decision(id)!!.executableRevisedPct, 0.0)
    }

    @Test fun predictiveObservationNeverStampsExecutionAndSurvivesLaterAdmission() {
        assertFalse(NativeDecisionEnrollment8372.executionAdmission(true, "CORTEX_SHADOW_STRONG"))
        assertTrue(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, "FDG_ALLOW", "CORTEX_SHADOW_REFUSE"))
        assertTrue(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, "FDG_ALLOW", "NATIVE_READY"))
    }
    @Test fun failedStorageRetainsFirstObservationAndNeverCallsConsumer() {
        val q = ForwardOutcomeOutbox8404(2)
        assertFalse(q.stage("episode", { "first" }) { _, _ -> false })
        var calls = 0
        q.drain(32, { _, _ -> false }, { calls++; true }, { true })
        assertEquals(0, calls)
        val disk = linkedMapOf<String, String>()
        assertTrue(q.stage("episode", { "later-price" }) { k, v -> disk[k] = v; true })
        assertEquals("first", disk["episode"])
    }

    @Test fun restartRetriesUntilDurableAcknowledgementAndDeletionFailureDoesNotRedeliver() {
        val disk = linkedMapOf<String, String>()
        val q = ForwardOutcomeOutbox8404(2)
        assertTrue(q.stage("episode", { "original-and-proof" }) { k, v -> disk[k] = v; true })
        val restored = ForwardOutcomeOutbox8404(2)
        restored.restore(disk)
        var attempts = 0
        restored.drain(1, { _, _ -> error("already durable") }, { attempts++; false }, { error("not acknowledged") })
        assertEquals(1, restored.size())
        restored.drain(1, { _, _ -> false }, { attempts++; true }, { false })
        restored.drain(1, { _, _ -> false }, { error("must not redeliver acknowledged value") }, { disk.remove(it); true })
        assertEquals(2, attempts)
        assertEquals(0, restored.size())
        assertTrue(disk.isEmpty())
    }

    @Test fun failedFirstBatchCannotStarveLaterOutcomesAndCapacityNeverEvictsAnOwner() {
        val q = ForwardOutcomeOutbox8404(130)
        repeat(130) { i -> assertTrue(q.stage("%03d".format(i), { i.toString() }) { _, _ -> true }) }
        assertFalse(q.stage("overflow", { "must-retain-producer" }) { _, _ -> true })
        val seen = mutableSetOf<Int>()
        repeat(5) { q.drain(32, { _, _ -> true }, { seen += it.toInt(); false }, { error("unacknowledged") }) }
        assertEquals(130, seen.size)
        assertEquals(130, q.size())
    }

    @Test fun consumerExceptionKeepsOwnershipAndOtherRecordsProgress() {
        val q = ForwardOutcomeOutbox8404(2)
        q.restore(mapOf("a" to "bad", "b" to "good"))
        val removed = mutableListOf<String>()
        q.drain(2, { _, _ -> true }, { if (it == "bad") error("retry") else true }, { removed += it; true })
        assertEquals(listOf("b"), removed)
        assertTrue(q.contains("a"))
        assertFalse(q.contains("b"))
    }
}
