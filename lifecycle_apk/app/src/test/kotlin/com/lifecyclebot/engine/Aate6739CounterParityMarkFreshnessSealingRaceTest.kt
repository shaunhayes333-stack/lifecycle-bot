package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6739 — regression suite for three source-grounded fixes derived
 * from the 5.0.6738 runtime dump:
 *
 *   1. §COUNTER_PARITY_RESET_PAIR (PipelineHealthCollector.reset)
 *      Operator dump: "Counter parity: FAIL paperOk=356 rows=113".
 *      Root cause: reset() cleared labelCounts (wiping TRADEJRNL_REC_PAPER)
 *      but left execPaperBuyOk / execPaperSellOk / execPaperPartialOk at
 *      their lifetime totals, so parity failed forever after a "fresh
 *      capture". Assertions locked at the source level because the
 *      atomics are private.
 *
 *   2. §MARK_FRESHNESS_ALIGN (CanonicalPriceMarkRegistry6522)
 *      Operator dump: BLUECHIP FDG allow=664 → mark=14 (2.1% conversion).
 *      Root cause: registry `resolveBestSourceEvidence6734` and
 *      `getFresh6734` capped freshness at 120_000L, but Executor's
 *      upstream freshness gates used WINDOW_MS_6616 = 300_000L. Evidence
 *      121-300 s old passed upstream and was rejected silently by the
 *      registry.
 *
 *   3. §SEALING_RACE_DEFER (ExecutableOpenGate)
 *      Operator dump: FDG_ALLOW_WITHOUT_EXEC_INTENT = 8.
 *      Root cause: fdgCan=true can come from a provisional state a few
 *      ms before ExecutionDecisionSnapshot6510 seals. The counter fired
 *      inside that race window as if it were a real integrity violation.
 */
class Aate6739CounterParityMarkFreshnessSealingRaceTest {

    @Before
    fun reset() {
        CanonicalPriceMarkRegistry6522.resetForTest()
    }

    // ─── 1. Counter parity reset pair ─────────────────────────────

    @Test
    fun `reset must zero paper OK atomics that pair with TRADEJRNL_REC_PAPER`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        // The reset() body must zero all three paper OK atomics in the
        // same call that clears labelCounts. Otherwise the parity check
        // reads lifetime atomics against a session-since-reset row count.
        val resetBody = src.substringAfter("fun reset() {").substringBefore("\n    }\n")
        assertTrue(
            "reset() must clear labelCounts (wipes TRADEJRNL_REC_PAPER)",
            resetBody.contains("labelCounts.clear()"),
        )
        assertTrue(
            "reset() must also zero execPaperBuyOk",
            resetBody.contains("execPaperBuyOk.set(0L)"),
        )
        assertTrue(
            "reset() must also zero execPaperSellOk",
            resetBody.contains("execPaperSellOk.set(0L)"),
        )
        assertTrue(
            "reset() must also zero execPaperPartialOk",
            resetBody.contains("execPaperPartialOk.set(0L)"),
        )
        assertTrue(
            "6739 marker must be present so a future refactor cannot drop the pair reset silently",
            src.contains("COUNTER_PARITY_RESET_PAIR"),
        )
    }

    @Test
    fun `resetModeCountersForRuntime already zeroes the same atomics (regression fence)`() {
        // If someone deletes those lines from resetModeCountersForRuntime,
        // parity will break on mode-flip. This fence prevents that.
        val src = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val body = src.substringAfter("fun resetModeCountersForRuntime")
            .substringBefore("private val fdgLiveAllow")
        assertTrue(body.contains("execPaperBuyOk.set(0L)"))
        assertTrue(body.contains("execPaperSellOk.set(0L)"))
        assertTrue(body.contains("execPaperPartialOk.set(0L)"))
    }

    // ─── 2. Mark freshness alignment (retracted — see V5.0.6740 note) ─

    @Test
    fun `registry freshness window is exposed as a named constant`() {
        // V5.0.6740 course-correction: widening the registry to 300 s
        // broke Aate6734RecoveryIntegrityTest and violated the operator
        // directive contract "Do not let a real mismatch disappear
        // merely because its TTL expires". Registry keeps 120 s; the
        // upstream Executor freshness or provider poll cadence must be
        // repaired instead. The constant survives for documentation so
        // any caller referring to the read-side freshness reads a single
        // symbol rather than a magic number.
        val regSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertTrue(
            "Registry must publish MARK_FRESHNESS_WINDOW_MS_6739",
            regSrc.contains("MARK_FRESHNESS_WINDOW_MS_6739 = 120_000L") ||
                regSrc.contains("MARK_FRESHNESS_WINDOW_MS_6739=120_000L"),
        )
        assertTrue(
            "getFresh6734 references the named window",
            regSrc.contains("-5_000L..MARK_FRESHNESS_WINDOW_MS_6739"),
        )
    }

    @Test
    fun `121 second old executable mark still stale (registry contract preserved)`() {
        // Locks the Aate6734RecoveryIntegrityTest.stale_strict_mark_cannot_
        // be_reused_for_execution invariant against future widening.
        val mint = "G".repeat(32)
        val now = System.currentTimeMillis()
        val e = CanonicalPriceMarkRegistry6522.SourceEvidence6734(
            baseMint = mint, pair = "MINT_ROUTE:$mint", quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 1.2345,
            liquidityUsd = 25_000.0,
            timestampMs = now - 121_000L,
        )
        val r = CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734(mint, listOf(e), now)
        assertFalse("121 s old evidence must NOT promote (execution freshness contract)", r.promoted)
    }

    // ─── 3. Sealing race defer ────────────────────────────────────

    @Test
    fun `sealing race deferral marker present in ExecutableOpenGate`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "SEALING_RACE_DEFER marker must be present",
            src.contains("SEALING_RACE_DEFER") ||
                src.contains("FDG_ALLOW_SEALING_RACE_DEFERRED_6739"),
        )
        assertTrue(
            "Defer branch must inspect state.updatedAtMs against a bounded race window",
            src.contains("stateAgeMs in 0..500L"),
        )
        assertTrue(
            "Defer must be PAPER-only so LIVE retains the strict invariant",
            src.contains("paperMode && stateAgeMs in 0..500L"),
        )
        assertTrue(
            "Defer must soft-block with the dedicated reason (not the invariant counter)",
            src.contains("EXEC_OPEN_DEFERRED_SEALING_RACE_6739"),
        )
        assertTrue(
            "AUTHORITY_INVARIANT_FAILURE still fires outside the race window",
            src.contains("AUTHORITY_INVARIANT_FAILURE") &&
                src.contains("FDG_ALLOW_WITHOUT_EXECUTION_INTENT_6519"),
        )
    }
}
