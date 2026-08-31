package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * V5.0.6618 — MARKETS_TOGGLE_AUTHORITY + RESET_PAPER_WALLET_CANONICAL
 *
 * Operator directive (verbatim, Feb 2026):
 *   "Markets currently runs even if it's switched off in settings.
 *    Fix that. Also it's drained all the funds and won't reset the
 *    wallet balance."
 *
 * Two orthogonal bugs, one fix patch:
 *
 *   [1] isMarketsLaneEnabled in BotService.kt short-circuited to
 *       `return true` in paper mode regardless of cfg.marketsTraderEnabled
 *       (V5.0.6069 "PAPER = LEARN EVERYTHING"). User's explicit toggle
 *       was invisible to the engine.
 *       Fix: master toggle has authority. Paper mode still enables sub-
 *       lanes universally BUT the master toggle must be on first.
 *
 *   [2] BehaviorActivity Reset Paper Wallet button wrote to legacy
 *       BotService.status.paperWalletSol + FluidLearning + a
 *       SharedPreferences key — none of which is the canonical
 *       PaperAccountLedger6430 since V5.0.6577. The three heroes
 *       (bound to JournalEconomicAuthority6616 since 6616) kept the
 *       drained state. Fix: route through
 *       PaperAccountLedger6430.resetToFreshBalance6618 which atomically
 *       purges durable state + resets all pico atomics + notifies the
 *       journal authority so all three heroes observe the reset.
 */
class Aate6618MarketsToggleAndResetWalletCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.JournalEconomicAuthority6616.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6618_markets_master_toggle_off_shuts_lane_even_in_paper_mode() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        // The 6618 correction MUST short-circuit on `!cfg.marketsTraderEnabled`
        // BEFORE the paper-mode branch. Otherwise the master toggle stays
        // invisible in paper mode.
        assertTrue(
            "V5.0.6618: isMarketsLaneEnabled must return false when master toggle is off",
            src.contains("if (!cfg.marketsTraderEnabled) return false")
        )
        // The pre-6618 shape `if (cfg.paperMode && !MARKET_TRADER_KILL_SWITCH) return true`
        // MUST be gone — that was the exact bug.
        val pre6618Bug = src.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .any { it.contains("if (cfg.paperMode && !MARKET_TRADER_KILL_SWITCH) return true") }
        assertFalse(
            "V5.0.6618: the pre-6618 short-circuit `if (cfg.paperMode && !KILL) return true` must be removed",
            pre6618Bug
        )
        // 6618 rationale comment must exist so future refactors know
        // the causal chain and don't reintroduce the LEARN EVERYTHING
        // override without gating on the master toggle.
        assertTrue(
            "V5.0.6618: rationale comment must anchor the fix",
            src.contains("V5.0.6618 §MARKETS_TOGGLE_AUTHORITY")
        )
    }

    @Test
    fun aate6618_reset_paper_wallet_button_routes_through_canonical_ledger() {
        val ui = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/BehaviorActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6618: Reset button must call PaperAccountLedger6430.resetToFreshBalance6618 (single canonical entry)",
            ui.contains("PaperAccountLedger6430") &&
                ui.contains(".resetToFreshBalance6618(freshSol, \"USER_BEHAVIOR_UI_RESET_6618\")")
        )
    }

    @Test
    fun aate6618_reset_to_fresh_balance_zeroes_all_atomics_and_notifies_journal() {
        val ledgerSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt"
        ).readText()
        assertTrue(
            "V5.0.6618: resetToFreshBalance6618 must be @Synchronized (writes shared atomics)",
            ledgerSrc.contains("@Synchronized\n    fun resetToFreshBalance6618(")
        )
        assertTrue(
            "V5.0.6618: resetToFreshBalance6618 must purge STATE_6487 so restart cannot resurrect drained state",
            ledgerSrc.contains("prefs6487?.edit()?.remove(STATE_6487)?.apply()")
        )
        assertTrue(
            "V5.0.6618: resetToFreshBalance6618 must notify journal authority so all heroes re-render",
            ledgerSrc.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"RESET_6618\")")
        )
        assertTrue(
            "V5.0.6618: resetToFreshBalance6618 must emit PAPER_WALLET_RESET_6618",
            ledgerSrc.contains("PAPER_WALLET_RESET_6618")
        )
    }

    @Test
    fun aate6618_reset_produces_invariant_satisfied_ledger_at_fresh_value() {
        val ledger = com.lifecyclebot.engine.truth.PaperAccountLedger6430
        ledger.resetForTest()
        // Simulate a drained state with realized losses + fees + open cost.
        ledger.initialize(11.7647)
        ledger.onBuy(costSol = 5.0, feeSol = 0.05)
        ledger.onSell(grossProceedsSol = 2.0, costBasisSoldSol = 5.0, feeSol = 0.02, mint = "MintA")
        // Now user hits Reset.
        ledger.resetToFreshBalance6618(11.7647, "USER_TEST_RESET")
        assertEquals("V5.0.6618: reset must restore fresh cash", 11.7647, ledger.cashSol(), 1e-6)
        assertEquals("V5.0.6618: reset must zero openCostBasis", 0.0, ledger.openCostBasisSol(), 1e-9)
        assertEquals("V5.0.6618: reset must zero realizedPnl", 0.0, ledger.realizedPnlSol(), 1e-9)
        assertEquals("V5.0.6618: reset must zero fees", 0.0, ledger.feesSol(), 1e-9)
        // The capital-conservation invariant must pass at the reset point.
        val violation = ledger.assertInvariant()
        assertTrue(
            "V5.0.6618: capital-conservation invariant must hold immediately after reset (got=$violation)",
            violation == null
        )
    }

    @Test
    fun aate6618_reset_publishes_a_fresh_journal_snapshot_to_heroes() {
        val ledger = com.lifecyclebot.engine.truth.PaperAccountLedger6430
        val journal = com.lifecyclebot.engine.truth.JournalEconomicAuthority6616
        ledger.resetForTest()
        journal.resetForTest()
        ledger.initialize(5.0)
        ledger.onBuy(costSol = 3.0, feeSol = 0.03)
        val preSnap = journal.currentSnapshot()!!
        val preRev = preSnap.revision
        // User hits Reset.
        ledger.resetToFreshBalance6618(11.7647, "USER_TEST_RESET_PUBLISH")
        val postSnap = journal.currentSnapshot()!!
        assertTrue(
            "V5.0.6618: reset must advance journalEconomicRevision (pre=$preRev post=${postSnap.revision})",
            postSnap.revision > preRev
        )
        assertEquals(
            "V5.0.6618: post-reset snapshot cashSol must equal fresh startingSol",
            11.7647, postSnap.cashSol, 1e-6
        )
    }
}
