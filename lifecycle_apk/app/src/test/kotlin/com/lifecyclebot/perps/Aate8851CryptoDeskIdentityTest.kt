package com.lifecyclebot.perps

import com.lifecyclebot.engine.ToolkitSignalSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each crypto overlay must keep its actual owner and setup through qualification. */
class Aate8851CryptoDeskIdentityTest {
    private fun hypothesis(lane: String, conviction: Double) = ToolkitSignalSheet.DeskHypothesis(
        lane = lane, setup = ToolkitSignalSheet.Setup.CASHFLOW_SCALP,
        conviction = conviction, entryStyle = "test-entry", exitStyle = "test-exit",
        holdMult = 1.0, sizeMult = 1.0, tpMult = 1.0, reason = "test",
    )

    @Test fun cashgenAndTreasuryAreDistinctCryptoOwners() {
        val ranked = CryptoLaneDesk7391.rankedHypotheses8851(listOf(
            hypothesis("CASHGEN", 81.0), hypothesis("TREASURY", 74.0),
            hypothesis("cashgen", 61.0), hypothesis("CORE", 99.0),
            hypothesis("UNSUPPORTED", 96.0),
        )) { true }
        assertEquals(listOf("CASHGEN", "TREASURY"), ranked.map { it.lane })
        assertEquals(81.0, ranked.first().conviction, 0.0)
        assertEquals(ToolkitSignalSheet.Setup.CASHFLOW_SCALP, ranked.first().setup)
        assertEquals("the shared cashflow brain cannot supply two independent CORE votes", 1,
            CryptoLaneDesk7391.independentVoters8851(ranked).size)
        val treasuryFirst = CryptoLaneDesk7391.rankedHypotheses8851(listOf(
            hypothesis("TREASURY", 92.0), hypothesis("CASHGEN", 52.0),
        )) { true }
        assertEquals(listOf("TREASURY", "CASHGEN"), treasuryFirst.map { it.lane })
        assertEquals(1, CryptoLaneDesk7391.independentVoters8851(treasuryFirst).size)
        val distinctFamilies = CryptoLaneDesk7391.rankedHypotheses8851(listOf(
            hypothesis("CASHGEN", 81.0), hypothesis("TREASURY", 74.0), hypothesis("MOONSHOT", 65.0),
        )) { true }
        assertEquals(2, CryptoLaneDesk7391.independentVoters8851(distinctFamilies).size)
    }

    @Test fun everyOtherCryptoDeskSurvivesQualificationButEligibilityStillVetoes() {
        val supported = listOf("EXPRESS", "DIP_HUNTER", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT")
        val ranked = CryptoLaneDesk7391.rankedHypotheses8851(
            supported.mapIndexed { i, lane -> hypothesis(lane, 90.0 - i) }
        ) { it != "DIP_HUNTER" }
        assertEquals(supported.filterNot { it == "DIP_HUNTER" }, ranked.map { it.lane })
        assertFalse(ranked.any { it.lane == "DIP_HUNTER" })
        assertTrue(ranked.all { it.conviction >= 85.0 })
    }

    @Test fun executableLaneNeverInheritsAnUnrelatedWinningDeskSetup() {
        val top = CryptoLaneDesk7391.Election("CASHGEN", 88.0, "CASHFLOW_SCALP", "UP", 1)
        val matched = CryptoLaneDesk7391.Election("SHITCOIN", 62.0, "DEGEN_MICRO_SNIPE", "BREAKOUT", 1)
        val ownReason = CryptoLaneDesk7391.evidencedSignalReason8851("SHITCOIN", top, listOf(top, matched))
        assertEquals("SHITCOIN", CryptoLaneDesk7391.laneFromReasons(listOf(ownReason)))
        assertEquals("DEGEN_MICRO_SNIPE", CryptoLaneDesk7391.observedSetup8849(listOf(ownReason)))
        assertTrue(ownReason.contains("desk=CASHGEN"))

        val unprovenReason = CryptoLaneDesk7391.evidencedSignalReason8851("EXPRESS", top, listOf(top))
        assertEquals("EXPRESS", CryptoLaneDesk7391.laneFromReasons(listOf(unprovenReason)))
        assertNull(CryptoLaneDesk7391.observedSetup8849(listOf(unprovenReason)))
        assertFalse(unprovenReason.contains("setup="))
        val cryptoBrainOwnReason = CryptoLaneDesk7391.evidencedSignalReason8851("CASHGEN", top, listOf(top))
        assertEquals("CASHFLOW_SCALP", CryptoLaneDesk7391.observedSetup8849(listOf(cryptoBrainOwnReason)))
    }
}
