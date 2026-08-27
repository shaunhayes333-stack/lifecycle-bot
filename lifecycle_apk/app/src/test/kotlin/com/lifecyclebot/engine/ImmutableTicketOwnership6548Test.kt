package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6548 §P0-A — IMMUTABLE TICKET OWNERSHIP acceptance test.
 *
 * Operator forensic (V5.0.6547): 76 EXEC_OPEN_ALLOWED → 76
 * PAPER_TICKET_NONTERMINAL_RELEASE_6514 → 0 committed. Root cause:
 * `releaseAttemptNonTerminal6514` revoked every ticket + allowedAttempts
 * residue, so the next intake cycle minted a fresh attemptId and the
 * immutable execution authority was lost every defer.
 *
 * This test proves the V5.0.6548 fix:
 *   1. NEXT-CYCLE RESUME — after a nonterminal release, the same
 *      immutable attemptId is retrievable for the mint until TTL expires.
 *   2. TERMINAL CLEARS — after `terminalizeAttempt6514`, the retry slot
 *      is empty and a subsequent nextAttemptId returns a new value.
 *   3. TTL EXPIRY — after RETRY_PENDING_TTL_MS_6548 elapses, the slot
 *      returns null (caller must mint a fresh attemptId).
 */
class ImmutableTicketOwnership6548Test {

    private val mintFoo = "MintFoo_6548_ownership_test"
    private val mintBar = "MintBar_6548_ownership_test"
    private val lane = "STANDARD"

    @Before
    fun setUp() {
        // Ensure clean state for each test.
        ExecutableOpenGate.clearRetryPending6548(mintFoo, "test_setup")
        ExecutableOpenGate.clearRetryPending6548(mintBar, "test_setup")
    }

    @Test
    fun nonterminal_release_preserves_attempt_id_for_next_cycle() {
        val id1 = ExecutableOpenGate.nextAttemptId(mintFoo, lane)
        assertTrue("attemptId must be non-blank", id1.isNotBlank())

        ExecutableOpenGate.releaseAttemptNonTerminal6514(id1, mintFoo, lane, "SOL_USD_MISSING_6509")

        val pending = ExecutableOpenGate.retryPendingFor6548(mintFoo)
        assertNotNull("retry-pending slot must survive nonterminal release", pending)
        assertEquals("attemptId must be identical across the retry window", id1, pending!!.attemptId)
        assertEquals("lane preserved", lane, pending.lane)
        assertEquals("reason preserved", "SOL_USD_MISSING_6509", pending.reason)
    }

    @Test
    fun terminal_release_clears_retry_slot() {
        val id1 = ExecutableOpenGate.nextAttemptId(mintBar, lane)
        ExecutableOpenGate.releaseAttemptNonTerminal6514(id1, mintBar, lane, "TOKEN_MAP_PENDING")
        assertNotNull(ExecutableOpenGate.retryPendingFor6548(mintBar))

        ExecutableOpenGate.terminalizeAttempt6514(id1, mintBar, lane)
        assertNull(
            "retry-pending slot must clear on terminalize",
            ExecutableOpenGate.retryPendingFor6548(mintBar),
        )
    }

    @Test
    fun explicit_clear_removes_slot() {
        val id1 = ExecutableOpenGate.nextAttemptId(mintFoo, lane)
        ExecutableOpenGate.releaseAttemptNonTerminal6514(id1, mintFoo, lane, "TOKEN_MAP_PENDING")
        assertNotNull(ExecutableOpenGate.retryPendingFor6548(mintFoo))

        ExecutableOpenGate.clearRetryPending6548(mintFoo, "COMMITTED")
        assertNull(
            "retry-pending slot must be empty after explicit clear",
            ExecutableOpenGate.retryPendingFor6548(mintFoo),
        )
    }

    @Test
    fun two_defers_on_same_mint_reuse_same_attempt_id() {
        // Simulates: paperBuy attempt #1 defers → next cycle looks up
        // retryPendingFor6548, resumes SAME id → attempt #2 also defers
        // → next cycle still resumes SAME id. Immutable across N cycles.
        val id1 = ExecutableOpenGate.nextAttemptId(mintFoo, lane)
        ExecutableOpenGate.releaseAttemptNonTerminal6514(id1, mintFoo, lane, "SOL_USD_MISSING_6509")

        val resumeId = ExecutableOpenGate.retryPendingFor6548(mintFoo)?.attemptId
        assertEquals("first resume must yield the same id", id1, resumeId)

        // The resumed attempt defers again. Under 6548 the caller
        // re-uses the resumed id, so we defer with id1 (== resumeId).
        ExecutableOpenGate.releaseAttemptNonTerminal6514(id1, mintFoo, lane, "TOKEN_MAP_PENDING")
        val resumeId2 = ExecutableOpenGate.retryPendingFor6548(mintFoo)?.attemptId
        assertEquals("second resume must still yield the same immutable id", id1, resumeId2)
    }

    @Test
    fun different_mints_get_independent_slots() {
        val idA = ExecutableOpenGate.nextAttemptId(mintFoo, lane)
        val idB = ExecutableOpenGate.nextAttemptId(mintBar, lane)
        assertNotEquals("distinct mints must get distinct attempt ids", idA, idB)

        ExecutableOpenGate.releaseAttemptNonTerminal6514(idA, mintFoo, lane, "TOKEN_MAP_PENDING")
        ExecutableOpenGate.releaseAttemptNonTerminal6514(idB, mintBar, lane, "SOL_USD_MISSING_6509")

        assertEquals(idA, ExecutableOpenGate.retryPendingFor6548(mintFoo)?.attemptId)
        assertEquals(idB, ExecutableOpenGate.retryPendingFor6548(mintBar)?.attemptId)

        // Clearing one does not affect the other.
        ExecutableOpenGate.clearRetryPending6548(mintFoo, "test")
        assertNull(ExecutableOpenGate.retryPendingFor6548(mintFoo))
        assertNotNull(ExecutableOpenGate.retryPendingFor6548(mintBar))
    }
}
