package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import org.junit.Assert.*
import org.junit.Test

/** Executes the actual ranking adapters used by native election, rescue, and receiver transfer. */
class Aate8527SelectionPriorityContinuityTest {
    private fun opinion(lane: String, raw: Int, nativePriority: Double, selectedPriority: Double? = null) =
        SpecialistBrainBridge7542.Opinion(lane, true, raw, raw, 0.01, "READY", "BREAKOUT", "buy", "sell",
            ownershipConviction7948 = nativePriority, selectionPriority8522 = selectedPriority)

    @Test fun inversePriorityDoesNotRevertToRawRankAtRescueOrReceiverTransfer() {
        val inverse = opinion("MOONSHOT", 31, 31.0, 92.0)
        val ordinary = opinion("QUALITY", 89, 89.0)
        val opinions = listOf(inverse, ordinary)
        assertEquals(92.0, NativeOpportunityPolicy8522.selectionPriority8522(inverse), 0.0)
        val contenders = opinions.map { SpecialistOwnership7951.contenderOf8136(it) }
        assertEquals("MOONSHOT", SpecialistElection8136.electRescue8136(contenders, "TREASURY"))
        val reads = opinions.map { SpecialistBrainBridge7542.handoffRead8527(it) }
        assertEquals("MOONSHOT", AdmissionHandoff8144.receiver8144(null, "TREASURY", reads))
        val transferred = HandoffTaskRuntime8153.eligiblePriorities8527(opinions) { true }
        assertEquals(92.0, transferred.getValue("MOONSHOT"), 0.0)
        assertEquals("MOONSHOT", transferred.maxByOrNull { it.value }!!.key)
        assertEquals(31, inverse.score)
        assertEquals(31, inverse.confidence)
        assertEquals(31.0, inverse.ownershipConviction7948!!, 0.0)
    }

    @Test fun nativePriorityAndRawFallbackStillAgreeWhenTheOperatorAbstains() {
        val native = opinion("MOONSHOT", 31, 86.0)
        val raw = native.copy(lane = "QUALITY", score = 73, confidence = 77, ownershipConviction7948 = null)
        val nonfiniteOverride = native.copy(selectionPriority8522 = Double.NaN)
        assertEquals(86.0, SpecialistOwnership7951.contenderOf8136(native).normalised, 0.0)
        assertEquals(77.0, SpecialistOwnership7951.contenderOf8136(raw).normalised, 0.0)
        assertEquals(86.0, SpecialistOwnership7951.contenderOf8136(nonfiniteOverride).normalised, 0.0)
    }

    @Test fun receiverRanksDoNotReenableRefusedDisabledOrNonAuthoritativeLanes() {
        val ready = opinion("MOONSHOT", 31, 31.0, 92.0)
        val refused = opinion("QUALITY", 99, 99.0, 100.0).copy(eligible = false)
        val advisory = opinion("CASHGEN", 99, 99.0).copy(authoritative = false)
        val disabled = opinion("BLUECHIP", 99, 99.0)
        val ranks = HandoffTaskRuntime8153.eligiblePriorities8527(listOf(ready, refused, advisory, disabled)) {
            it != "BLUECHIP"
        }
        assertEquals(setOf("MOONSHOT"), ranks.keys)
    }
}
