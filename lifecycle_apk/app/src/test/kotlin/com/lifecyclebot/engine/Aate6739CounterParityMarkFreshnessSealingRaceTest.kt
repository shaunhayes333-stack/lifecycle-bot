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

    // ─── 2. Mark freshness alignment ──────────────────────────────

    @Test
    fun `registry freshness window equals upstream Executor freshness window`() {
        // Executor uses 300_000L. Registry must match, otherwise evidence
        // passed by Executor is dropped by the registry.
        val regSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertTrue(
            "Registry must publish MARK_FRESHNESS_WINDOW_MS_6739 = 300_000L",
            regSrc.contains("MARK_FRESHNESS_WINDOW_MS_6739 = 300_000L") ||
                regSrc.contains("MARK_FRESHNESS_WINDOW_MS_6739=300_000L"),
        )
        // Both call sites (resolveBestSourceEvidence6734 and getFresh6734)
        // must reference the constant, not a hard-coded 120_000L.
        assertFalse(
            "Registry must NOT still use the 120_000L hard-code that dropped 121-300 s old evidence",
            regSrc.contains("-5_000L..120_000L"),
        )
        assertTrue(
            "getFresh6734 must reference the aligned window",
            regSrc.contains("-5_000L..MARK_FRESHNESS_WINDOW_MS_6739"),
        )
    }

    @Test
    fun `130 second old evidence with valid liquidity and price now promotes at registry`() {
        val mint = "F".repeat(32)
        val now = System.currentTimeMillis()
        val e = CanonicalPriceMarkRegistry6522.SourceEvidence6734(
            baseMint = mint, pair = "MINT_ROUTE:$mint", quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 1.2345,
            liquidityUsd = 25_000.0,
            timestampMs = now - 130_000L,        // 130 s old — used to be rejected
        )
        val r = CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734(mint, listOf(e), now)
        assertTrue(
            "130 s old fresh-per-Executor evidence must promote now, got reason=${r.reason}",
            r.promoted,
        )
    }

    @Test
    fun `evidence older than 300 seconds still rejected (freshness contract preserved)`() {
        val mint = "G".repeat(32)
        val now = System.currentTimeMillis()
        val e = CanonicalPriceMarkRegistry6522.SourceEvidence6734(
            baseMint = mint, pair = "MINT_ROUTE:$mint", quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 1.2345,
            liquidityUsd = 25_000.0,
            timestampMs = now - 301_000L,
        )
        val r = CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734(mint, listOf(e), now)
        assertFalse("evidence older than freshness window must NOT promote", r.promoted)
    }

    // ─── 3. Sealing race defer ────────────────────────────────────

    @Test
    fun `sealing race deferral marker present in ExecutableOpenGate`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        // The race-defer branch must fire BEFORE the invariant counter so
        // routine sealing races do not bump AUTHORITY_INVARIANT_FAILURE.
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
            "Defer must soft-block with the dedicated reason (not the invariant counter)",
            src.contains("EXEC_OPEN_DEFERRED_SEALING_RACE_6739"),
        )
        // Existing invariant counter still fires OUTSIDE the race window
        // so real integrity violations remain visible.
        assertTrue(
            "AUTHORITY_INVARIANT_FAILURE still fires outside the race window",
            src.contains("AUTHORITY_INVARIANT_FAILURE") &&
                src.contains("FDG_ALLOW_WITHOUT_EXECUTION_INTENT_6519"),
        )
    }
}
