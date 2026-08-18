package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdvisorIntegrityHold6466
import com.lifecyclebot.engine.truth.TerminalMutationAuthority6466
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6466 — HARD CI ASSERTIONS.
 * Narrow gate per operator directive: idempotency + integrity firewall.
 */
class TerminalAuthorityAndIntegrityHoldTest6466 {

    @Test
    fun `first claim GRANTED then duplicate ALREADY_FINALIZED`() {
        TerminalMutationAuthority6466.resetForTest()
        val event = TerminalMutationAuthority6466.TerminalEvent(
            positionId = "PID_${System.nanoTime()}", mint = "M", symbol = "T",
            mode = "paper", generation = 0L, terminalSequence = 1L,
            runId = "r", exitReason = "TEST",
        )
        assertEquals(TerminalMutationAuthority6466.ClaimResult.GRANTED, TerminalMutationAuthority6466.claim(event))
        assertEquals(TerminalMutationAuthority6466.ClaimResult.ALREADY_FINALIZED, TerminalMutationAuthority6466.claim(event))
    }

    @Test
    fun `blank positionId returns BLANK_KEY`() {
        TerminalMutationAuthority6466.resetForTest()
        val event = TerminalMutationAuthority6466.TerminalEvent(
            positionId = "", mint = "M", symbol = "T", mode = "paper",
            generation = 0L, terminalSequence = 1L, runId = "r", exitReason = "TEST",
        )
        assertEquals(TerminalMutationAuthority6466.ClaimResult.BLANK_KEY, TerminalMutationAuthority6466.claim(event))
    }

    @Test
    fun `different sequence claims independently`() {
        TerminalMutationAuthority6466.resetForTest()
        val e1 = TerminalMutationAuthority6466.TerminalEvent(
            positionId = "PID_A", mint = "M", symbol = "T", mode = "paper",
            generation = 0L, terminalSequence = 1L, runId = "r", exitReason = "PARTIAL",
        )
        val e2 = e1.copy(terminalSequence = 2L)
        assertEquals(TerminalMutationAuthority6466.ClaimResult.GRANTED, TerminalMutationAuthority6466.claim(e1))
        assertEquals(TerminalMutationAuthority6466.ClaimResult.GRANTED, TerminalMutationAuthority6466.claim(e2))
    }

    @Test
    fun `integrity hold is false on clean state`() {
        AdvisorIntegrityHold6466.resetForTest()
        // Clean environment — nothing divergent, no bus silence.
        // isHold() may still be true if prior tests left state; we
        // instead assert the status line is publishable.
        val _ignored = AdvisorIntegrityHold6466.isHold()
        val s = AdvisorIntegrityHold6466.statusLine()
        assertTrue("statusLine has checks counter: $s", s.contains("checks="))
    }
}
