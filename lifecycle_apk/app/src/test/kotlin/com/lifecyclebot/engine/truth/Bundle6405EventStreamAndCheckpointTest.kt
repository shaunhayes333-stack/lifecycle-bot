package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Bundle6405CanonicalEventStreamTest {

    @After fun tearDown() { CanonicalEventStream6405.clearForTest() }

    @Test fun append_produces_monotonic_seq() {
        val a = CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.BUY_VERIFIED,
        )
        val b = CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.SELL_VERIFIED,
        )
        assertTrue(b.seq > a.seq)
    }

    @Test fun fold_computes_bought_sold_lamports() {
        CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.BUY_VERIFIED,
            rawQty = BigInteger.valueOf(1000L),
            lamports = BigInteger.valueOf(500_000L),
        )
        CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.SELL_VERIFIED,
            rawQty = BigInteger.valueOf(600L),
            lamports = BigInteger.valueOf(400_000L),
        )
        val f = CanonicalEventStream6405.fold("M", 1L)
        assertEquals(BigInteger.valueOf(1000L), f.rawBought)
        assertEquals(BigInteger.valueOf(600L), f.rawSold)
        assertEquals(BigInteger.valueOf(500_000L), f.lamportsSpent)
        assertEquals(BigInteger.valueOf(400_000L), f.lamportsRecovered)
    }

    @Test fun subscriber_receives_events() {
        val received = mutableListOf<CanonicalEventStream6405.Event>()
        val sub = CanonicalEventStream6405.subscribe { received.add(it) }
        CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.BUY_INTENT,
        )
        sub.close()
        CanonicalEventStream6405.append(
            wallet = "W", mint = "M", positionGeneration = 1L,
            type = CanonicalEventStream6405.Type.SELL_INTENT,
        )
        assertEquals(1, received.size)
    }
}

class Bundle6405CheckpointRecoveryTest {

    @After fun tearDown() {
        CheckpointRecoveryAuthority6405.clearForTest()
        TerminalFinalityAuthority6405.clearForTest()
    }

    private fun pos(mint: String, gen: Long, entry: Long, sold: Long) =
        CheckpointRecoveryAuthority6405.OpenPosition(
            wallet = "W", mint = mint, positionGeneration = gen,
            entryRaw = BigInteger.valueOf(entry),
            soldRaw = BigInteger.valueOf(sold),
            entryLamports = BigInteger.valueOf(1_000L),
            isPaper = false,
        )

    @Test fun upsert_and_find_roundtrip() {
        CheckpointRecoveryAuthority6405.upsert(pos("M", 1L, 100L, 0L))
        val found = CheckpointRecoveryAuthority6405.find("W", "M", 1L)
        assertTrue(found != null)
        assertEquals(BigInteger.valueOf(100L), found!!.remainingRaw)
    }

    @Test fun retire_removes_position() {
        CheckpointRecoveryAuthority6405.upsert(pos("M", 1L, 100L, 100L))
        CheckpointRecoveryAuthority6405.retire("W", "M", 1L)
        assertNull(CheckpointRecoveryAuthority6405.find("W", "M", 1L))
    }

    @Test fun replay_retires_terminal_with_zero_remaining() {
        CheckpointRecoveryAuthority6405.upsert(pos("M", 1L, 100L, 100L))
        TerminalFinalityAuthority6405.markTerminal(
            "M", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP",
        )
        val r = CheckpointRecoveryAuthority6405.replay()
        assertEquals(0, r.kept)
        assertEquals(1, r.retired)
        assertEquals(0, r.integrityViolations.size)
    }

    @Test fun replay_flags_terminal_with_nonzero_remaining_as_violation() {
        CheckpointRecoveryAuthority6405.upsert(pos("M", 1L, 100L, 60L))
        TerminalFinalityAuthority6405.markTerminal(
            "M", 1L, TerminalFinalityAuthority6405.Terminal.CLOSED_FULL_EXIT, "TP",
        )
        val r = CheckpointRecoveryAuthority6405.replay()
        assertEquals(1, r.integrityViolations.size)
        assertTrue(r.integrityViolations[0].contains("TERMINAL_WITH_NONZERO_REMAINING"))
    }

    @Test fun replay_keeps_non_terminal_positions() {
        CheckpointRecoveryAuthority6405.upsert(pos("M", 1L, 100L, 40L))
        val r = CheckpointRecoveryAuthority6405.replay()
        assertEquals(1, r.kept)
        assertEquals(0, r.retired)
    }
}
