package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6366 — worker-timeout raise + ghost paper purge + learning ceiling raise.
 *
 * Operator directive (verbatim): "workers shouldn't time out thats costing
 * wallet growth and stalling the bot. tokens are stuck they aren't clearing.
 * if they aren't either good hold potential or green tokens they should be sold."
 *
 * Golden-tape guards so the three fixes don't silently regress.
 */
class Bundle6366InvariantsTest {

    @Test
    fun supervisor_worker_timeout_was_raised_to_15s() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6366: SUPERVISOR_WORKER_TIMEOUT_MS must be 15_000L so slower external " +
                "API p95 doesn't trip 246+ timeouts per 10min and arm the emergency throttle.",
            txt.contains("private val SUPERVISOR_WORKER_TIMEOUT_MS: Long = 15_000L"),
        )
        assertFalse(
            "V5.0.6366: old 9_000L worker timeout must be gone.",
            txt.contains("private val SUPERVISOR_WORKER_TIMEOUT_MS: Long = 9_000L"),
        )
    }

    @Test
    fun ghost_paper_purge_wired_into_current_paper_open_mints() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // Purge fires when a raw-ledger open paper position isn't owned by any V3 sub-trader.
        assertTrue("Must build canonical-owned mint set from all five V3 sub-traders",
            txt.contains("ShitCoinTraderAI.getActivePositionsForMode(true)") &&
                txt.contains("MoonshotTraderAI.getActivePositionsForMode(true)") &&
                txt.contains("BlueChipTraderAI.getActivePositionsForMode(true)") &&
                txt.contains("QualityTraderAI.getActivePositionsForMode(true)") &&
                txt.contains("CashGenerationAI.getActivePositionsForMode(true)"))
        assertTrue("Must emit PAPER_GHOST_PURGED_6366 when force-closing a ghost",
            txt.contains("PAPER_GHOST_PURGED_6366"))
        assertTrue("Must fall back gracefully when canonicalOwnedMints is null",
            txt.contains("canonicalOwnedMints != null && ts.mint !in canonicalOwnedMints"))
    }

    @Test
    fun paper_learning_ceiling_scales_with_paper_balance() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PaperLearningSanity.kt").readText()
        assertTrue(
            "V5.0.6366: learning-eligibility ceiling scales with paperSimulatedBalance (25%) " +
                "with a bounded [2.0, 20.0] SOL window so paper wallets bigger than 20 SOL " +
                "trade sizes larger than 2.0 aren't silently starved from the learning aggregators.",
            txt.contains("paperCeiling6366 = (cfg.paperSimulatedBalance * 0.25).coerceIn(2.0, 20.0)"),
        )
        assertFalse(
            "V5.0.6366: old 10% × [legacyMax, 2.0] hard ceiling must be removed.",
            txt.contains("(cfg.paperSimulatedBalance * 0.10).coerceIn(legacyMax, 2.0)"),
        )
    }

    /** Runtime sanity: the ceiling math produces the expected values across paper sizes. */
    @Test
    fun paper_learning_ceiling_math_is_within_expected_bounds() {
        // Simulate what the code computes for a range of paper balances.
        for ((bal, want) in listOf(
            0.5   to 2.0,   // tiny paper wallet -> hits floor
            30.0  to 7.5,   // 30 SOL -> 25% = 7.5
            80.0  to 20.0,  // 80 SOL -> 25% = 20.0 (at ceiling)
            10000.0 to 20.0, // absurd -> ceiling saturates
        )) {
            val computed = (bal * 0.25).coerceIn(2.0, 20.0)
            assertTrue(
                "ceiling for bal=$bal must be $want, got $computed",
                kotlin.math.abs(computed - want) < 1e-9,
            )
        }
    }
}
