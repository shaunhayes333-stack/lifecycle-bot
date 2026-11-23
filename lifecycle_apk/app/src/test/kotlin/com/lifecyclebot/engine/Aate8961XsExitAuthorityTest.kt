package com.lifecyclebot.engine

import com.lifecyclebot.xstocks.XsDesk
import org.junit.Assert.*
import org.junit.Test

class Aate8961XsExitAuthorityTest {
    @Test fun unarmedAssessmentClosesAnsweredZeroTargetsAndKeepsNewEntriesOff() {
        val actions = XsDesk.rebalanceActions8961(null, evaluated = true, armed = false, killed = false,
            targets = mapOf("AAPL" to 0.4, "TSLA" to 0.0),
            held = setOf("AAPL", "TSLA", "NVDA"), unanswered = emptySet())
        assertEquals(setOf("TSLA", "NVDA"), actions.closeSymbols)
        assertFalse(actions.mayOpen)
        assertEquals("BRAIN_NOT_ARMED", actions.entryRefusal)
    }

    @Test fun missingHistoryDoesNotTurnAStoredHoldingIntoAnExit() {
        val pending = XsDesk.rebalanceActions8961(null, true, false, false,
            emptyMap(), setOf("TSLA"), setOf("TSLA"))
        assertTrue(pending.closeSymbols.isEmpty())
        assertFalse(pending.mayOpen)
        val answered = XsDesk.rebalanceActions8961(null, true, false, false,
            emptyMap(), setOf("TSLA"), emptySet())
        assertEquals(setOf("TSLA"), answered.closeSymbols)
        assertFalse(answered.mayOpen)
    }

    @Test fun settingsWalletAndBudgetAuthorityStillApplyToOrdinaryRebalance() {
        for (refusal in listOf("SETTINGS_UNREADABLE", "LIVE_OFF", "NO_WALLET", "NO_BUDGET")) {
            val actions = XsDesk.rebalanceActions8961(refusal, true, false, false,
                emptyMap(), setOf("TSLA"), emptySet())
            assertTrue(actions.closeSymbols.isEmpty())
            assertFalse(actions.mayOpen)
            assertEquals(refusal, actions.entryRefusal)
        }
    }

    @Test fun startupWithoutAnEvaluationCannotInferZeroTargets() {
        val actions = XsDesk.rebalanceActions8961(null, false, false, false,
            emptyMap(), setOf("TSLA"), emptySet())
        assertTrue(actions.closeSymbols.isEmpty())
        assertFalse(actions.mayOpen)
        assertEquals("NO_EVALUATION", actions.entryRefusal)
    }

    @Test fun armedEntriesAndKilledEntrySuppressionRemainDistinctFromAnsweredExits() {
        val armed = XsDesk.rebalanceActions8961(null, true, true, false,
            mapOf("AAPL" to 0.5), setOf("AAPL", "TSLA"), emptySet())
        assertEquals(setOf("TSLA"), armed.closeSymbols)
        assertTrue(armed.mayOpen)
        val killed = XsDesk.rebalanceActions8961(null, true, true, true,
            mapOf("AAPL" to 0.5), setOf("AAPL", "TSLA"), emptySet())
        assertEquals(setOf("TSLA"), killed.closeSymbols)
        assertFalse(killed.mayOpen)
        assertEquals("STOP_HIT_SWITCH_LIVE_OFF_AND_ON", killed.entryRefusal)
    }
}
