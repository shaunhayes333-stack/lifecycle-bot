package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchSniper8748
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate9043LaunchLineageTest {
    private val s = LaunchSniper8748
    private val wall = 1_800_000_000_000L
    private val mono = 50_000L
    private val mint = "two-fires-same-mint-9043"

    @Before fun before() { s.resetClocksForTest9025() }
    @After fun after() { s.resetClocksForTest9025() }

    private fun open(id: String, offset: Long = 0L) = s.openOpportunityForTest9043(
        id, mint, wall + offset, wall + offset + 2_000L, mono + offset, 20_000L)
    private fun admit(id: String, version: Long, offset: Long) = s.noteAdmission9025(
        mint, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", wall + offset, "EXPRESS", version, id, mono + offset)
    private fun gate(version: Long, offset: Long, allow: Boolean = true, reason: String? = null) = s.noteGate9025(
        mint, allow, reason, wall + offset, "EXPRESS", version, mono + offset)

    @Test fun twoFiresKeepTheirOwnDecisionTicketAndSignatureAndIgnoreMintOnlyConfirmation() {
        open("fire-a"); admit("fire-a", 1L, 1_000L); gate(1L, 2_000L)
        assertTrue(s.bindTicket9043(mint, "EXPRESS", 1L, "attempt-a"))
        open("fire-b", 5_000L); admit("fire-b", 2L, 6_000L); gate(2L, 7_000L)
        assertTrue(s.bindTicket9043(mint, "EXPRESS", 2L, "attempt-b"))
        s.noteBuyConfirmed8749(mint, wall + 8_000L, "attempt-a", "signature-a", mono + 8_000L)
        s.noteBuyConfirmed8749(mint, wall + 8_000L, "attempt-a", "signature-a", mono + 8_000L)
        s.noteBuyConfirmed8749(mint, wall + 8_000L)
        var line = s.clockLine9025(wall + 30_000L, mono + 30_000L)
        assertTrue(line, line.contains("fires=2 admittedInWindow=2 gateAllowInWindow=2 landed=1 landedInWindow=1"))
        assertTrue(line, line.contains("landing=1"))
        assertTrue(line, line.contains("LANDED_IN_WINDOW=1") && line.contains("GATE_ALLOWED_NOT_LANDED=1"))
        s.noteBuyConfirmed8749(mint, wall + 40_000L, "attempt-b", "signature-b", mono + 40_000L)
        line = s.clockLine9025(wall + 40_000L, mono + 40_000L)
        assertTrue(line, line.contains("landed=2 landedInWindow=1") && line.contains("LANDED_LATE=1"))
    }

    @Test fun firstAndLastGateReasonKeepTheirOwnTimestampsAndClockRollbackCannotImproveLatency() {
        open("fire-a"); admit("fire-a", 1L, 1_000L)
        gate(1L, 3_000L, false, "DATA_INCOMPLETE_DEFER_8136")
        s.noteGate9025(mint, false, "MEME_BUYS_THE_HIGH_8582", wall - 50_000L, "EXPRESS", 1L, mono + 7_000L)
        val line = s.clockLine9025(wall - 30_000L, mono + 30_000L)
        assertTrue(line, line.contains("gate+5s:DATA_INCOMPLETE_DEFER_8136"))
        assertTrue(line, line.contains("lastGate+9s:MEME_BUYS_THE_HIGH_8582"))
        assertFalse("a blocked gate has no executable lineage", s.bindTicket9043(mint, "EXPRESS", 1L, "blocked"))
    }

    @Test fun ambiguousVersionAndDifferentLaneCannotBorrowAnotherFiresAllow() {
        open("fire-a"); admit("fire-a", 1L, 1_000L); gate(1L, 2_000L)
        assertFalse(s.bindTicket9043(mint, "CORE", 1L, "wrong-lane"))
        assertFalse(s.bindTicket9043(mint, "EXPRESS", 2L, "wrong-version"))
        open("fire-b", 5_000L); admit("fire-b", 1L, 6_000L); gate(1L, 7_000L)
        assertFalse("same version spans two fires: require an unambiguous revision", s.bindTicket9043(mint, "EXPRESS", 1L, "ambiguous"))
        s.noteBuyConfirmed8749(mint, wall + 9_000L, "ambiguous", "signature-x", mono + 9_000L)
        assertTrue(s.clockLine9025(wall + 30_000L, mono + 30_000L).contains("landed=0 landedInWindow=0"))
    }

    @Test fun unjudgedAndRetiredFiresRemainInTheDenominator() {
        repeat(301) { open("fire-$it", it.toLong() * 1_000L) }
        val line = s.clockLine9025(wall + 400_000L, mono + 400_000L)
        assertTrue(line, line.contains("fires=301 admittedInWindow=0"))
        assertTrue(line, line.contains("retained=300 retired=1"))
        assertTrue(line, line.contains("RETIRED_UNRESOLVED:NEVER_JUDGED=1") && line.contains("NEVER_JUDGED=300"))
    }

    @Test fun sourceNetworkLagSpendsFireWindowAndElapsedClockCannotBeRestamped() {
        val fire = LaunchSniper8748.Fire(mint, wall + 8_000L, 1_000L, 5_000.0, "rule", 500, 7.0, 1.0, 0.1,
            true, wall, 20_000L, "source-signature", mono)
        assertEquals(19_000L, s.fireElapsed9043(fire, wall - 60_000L, mono + 11_000L))
        assertEquals(mono + 12_000L, s.deadlineElapsed9043(fire))
        assertEquals(21_000L, s.fireElapsed9043(fire, wall - 60_000L, mono + 13_000L))
    }
}
