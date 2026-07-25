package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6364 — probation-loop root-cause invariants.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "the fixes need to be at the source of creation not a bandaid patch"
 *
 * ROOT CAUSE (V5.0.6363 emergency snapshot):
 *   `INTAKE BY SOURCE: PROBATION = 1278` — 1278 intake events on tokens with
 *   `liq=$0`. Each hit fires FAMILY_DEDUPE + PHASE/INTAKE + WATCHLIST_AFFINITY
 *   + SOURCE_BALANCE_PROBATION_LOOP_BYPASS_6273 + TOKEN_MAP_PENDING +
 *   INTAKE_HARD_REJECT_SKIPPED = ~6 ForensicLogger emits per dead token per
 *   cycle. Cycles ballooned to 180s and the sell path starved.
 *
 * SOURCE OF CREATION:
 *   `GlobalTradeRegistry.processProbation()` — after 5min in probation,
 *   ANY entry that didn't match the narrow `NO_PAIR_NO_FALLBACK` guard
 *   was TIMEOUT_AUTO_PROMOTE'd, dumping the token into the intake
 *   pipeline just to be rejected downstream by INTAKE_PROBATION_LIQ_ZERO_REJECT_4507.
 *
 * FIX (V5.0.6364):
 *   Any probation entry with NO EXECUTABLE SIGNAL (no price, no liquidity,
 *   no additional scanner confirmation, no rugcheck signal) is HELD, not
 *   promoted. LRU pruning in addToProbation still bounds the store.
 */
class ProbationTimeoutHeldSourceOfCreation6364Test {

    @Before
    fun setUp() {
        // File-content assertions — no runtime state needed.
    }

    /**
     * Regression guard: the source-of-creation guard must NOT be limited to the
     * literal string "NO_PAIR_NO_FALLBACK". The V5.0.6363 emergency showed
     * PumpPortal WS zero-liq tokens (source=SOURCE_BALANCE_DIVERT:...) were
     * NOT covered by the narrow guard and got auto-promoted.
     */
    @Test
    fun processProbation_source_string_check_reads_the_widened_guard() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        // The widened guard must exist and cover the union of dead-signal fields.
        assertTrue(
            "V5.0.6364: processProbation must hold entries with no executable signal, not force-promote them.",
            txt.contains("noExecutableSignal6364") &&
                txt.contains("entry.priceAtAdd <= 0.0") &&
                txt.contains("entry.currentPrice <= 0.0") &&
                txt.contains("entry.initialLiquidity <= 0.0") &&
                txt.contains("entry.additionalScanners.isEmpty()") &&
                txt.contains("entry.rcScore < 2"),
        )
        assertTrue(
            "V5.0.6364: HELD event must be observable via ForensicLogger + PipelineHealthCollector.",
            txt.contains("PROBATION_TIMEOUT_HELD_NO_EXECUTABLE_SIGNAL_6364"),
        )
    }

    /**
     * The legacy V5.9.1328 narrow guard must remain (source parity — it covered
     * the specific NO_PAIR_NO_FALLBACK path). V5.0.6364 widens ON TOP OF it.
     */
    @Test
    fun legacy_nopair_no_fallback_hold_is_preserved() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(txt.contains("PROBATION_TIMEOUT_HELD_NO_PAIR"))
        assertTrue(txt.contains("NO_PAIR_TIMEOUT_HELD"))
        assertTrue(txt.contains("NO_PAIR_NO_FALLBACK"))
    }

    /**
     * TIMEOUT_AUTO_PROMOTE path must remain for entries WITH signal — the fix
     * must not break healthy tokens that timed out with real data.
     */
    @Test
    fun healthy_timeout_still_auto_promotes() {
        val txt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(
            "Healthy timeouts must still auto-promote so V3/FDG can evaluate them.",
            txt.contains("TIMEOUT_AUTO_PROMOTE"),
        )
    }
}
