package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527SpecialistInputParityTest {
    private fun read(momentum: Double = -8.0, change: Double? = -2.0, bp: Double? = 90.0) =
        SpecialistMarketInputs8522.Read(
            NativeHistory8521.Read(listOf(100.0, 92.0), listOf(100.0, 92.0), 105.0, 1.7, emptyList()),
            NativeLaneAge8521.Evidence(2.0, 2.0), 92.0, bp, change, -2.0,
            ContinuationRead8348.Continuation8348(momentum, ContinuationRead8348.Source8348.FROM_TAPE), 41, 17)

    @Test fun strongBuyPressureCannotInvertObservedNegativeContinuation() {
        val i = SpecialistInputParity8527.expressInputs8527(read())
        assertEquals(-8.0, i.momentumPct, 0.0)
        assertEquals(-2.0, i.change5mPct, 0.0)
        assertEquals(90.0, i.buyPressurePct, 0.0)
        assertEquals(1.7, i.volumeRatio, 0.0)
        assertEquals(2.0, i.ageMinutes, 0.0)
        val flat = SpecialistInputParity8527.expressInputs8527(read(momentum = 0.0, change = 0.0))
        assertEquals(0.0, flat.momentumPct, 0.0)
        assertEquals(0.0, flat.change5mPct, 0.0)
    }

    @Test fun unknownBirthStaysUnknownAtMandatedDoorsAndCannotReplaceStructuralRefusal() {
        val absent = NativeLaneAge8521.Evidence(null, null)
        val no = SpecialistInputParity8527.ageDecision8527("MANIPULATED", true, "READY", absent)
        assertFalse(no.first)
        assertTrue(no.second.contains("UNOBSERVED_DEFER"))
        val physical = SpecialistInputParity8527.ageDecision8527("MANIPULATED", false, "MCAP_TOO_LOW", absent)
        assertFalse(physical.first)
        assertEquals("MCAP_TOO_LOW", physical.second)
        val i = SpecialistInputParity8527.expressInputs8527(read().copy(age = absent))
        assertTrue(i.ageMinutes.isNaN())
    }

    @Test fun missingPriceChangeDefersRatherThanBecomingAnAdaptableNegativeOpinion() {
        val absent = read(change = null, bp = null).copy(
            history = NativeHistory8521.Read(emptyList(), emptyList(), null, null, emptyList()),
            momentumPct = null,
            continuation = ContinuationRead8348.Continuation8348(0.0, ContinuationRead8348.Source8348.UNOBSERVED))
        val reason = SpecialistInputParity8527.observedReason8527(
            com.lifecyclebot.data.TokenState("unobserved8527"), "EXPRESS", false, "NO_CONTINUATION: 0.0% < 1.0%", absent)
        assertTrue(reason.contains("UNOBSERVED_DEFER"))
        assertFalse(NativeOpportunityPolicy8522.opportunityReason8522("EXPRESS", reason))
    }

    @Test fun previewAndActualDoorsUseSameProducersWithoutSyntheticMomentumOrBundleVotes() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val n = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        for (method in listOf("express8527", "manipulated8527")) {
            assertTrue(b.contains("SpecialistInputParity8527." + method + "("))
            assertTrue(n.contains("SpecialistInputParity8527." + method + "("))
        }
        assertFalse(b.contains("effectiveExpressMom"))
        assertFalse(b.contains("expressMinBuyP"))
        assertFalse(b.contains("val manipBundlePct = if"))
        assertTrue(b.contains("val passesPreFilter = expressInMcapRange || expressUnknownMcapOk"))
        assertTrue(b.contains("laneDoor8105(SpecialistInputParity8527.express8527("))
        assertTrue(b.contains("laneDoor8105(SpecialistInputParity8527.manipulated8527("))
        assertTrue(b.contains("choice?.action == NativeOpportunityPolicy8522.Action.REFUSE) return projected"))
        assertTrue(b.contains("tokenAgeMinutes = inputs.age.inputMinutes, holderCount = ts.peakHolderCount.coerceAtLeast(0)"))
        assertTrue(b.contains("val tokenAgeHours = SpecialistMarketInputs8522.forCycle8522(cycleSheet8522, ts).age.inputMinutes / 60.0"))
    }
}
