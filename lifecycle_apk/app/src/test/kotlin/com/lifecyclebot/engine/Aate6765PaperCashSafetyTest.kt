package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6765 §STARTING_CASH_FLOOR + §ADD_PAPER_FUNDS regression fence.
 *
 * Operator P0 report Feb 2026:
 *   "fix why updates force the wallet cash balance to zero"
 *   "fix why you cant add more funds in paper mode via the toggle in tuning"
 *
 * These fences lock the source-level guarantees so a future refactor
 * cannot silently reintroduce either defect:
 *
 *   1. `initPersistent6487` floors any non-positive `startingCashSol`
 *      to the canonical baseline (~11.76 SOL). A cold-boot after
 *      update can never zero the wallet again.
 *   2. `addPaperFundsSafe6765` exists and preserves ledger conservation
 *      by folding the addition into both `startingCashPico` and
 *      `cashPico`.
 *   3. `BotConfig.load()` never returns `paperSimulatedBalance <= 0`.
 *   4. `BehaviorActivity` reset button exposes an "Add Funds" path
 *      alongside the reset action.
 */
class Aate6765PaperCashSafetyTest {

    @Test fun init_persistent_floors_zero_starting_cash_to_baseline() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        assertTrue(
            "initPersistent6487 must guard non-positive startingCashSol",
            src.contains("safeStartingCashSol6765 = if (startingCashSol.isFinite() && startingCashSol > 0.0)"),
        )
        assertTrue(
            "guard must default to 11.7647 SOL baseline",
            src.contains("11.7647"),
        )
        assertTrue(
            "guard must emit PAPER_LEDGER_ZERO_STARTING_CASH_FLOOR_6765 telemetry",
            src.contains("PAPER_LEDGER_ZERO_STARTING_CASH_FLOOR_6765"),
        )
    }

    @Test fun add_paper_funds_authority_exists_and_is_conservation_safe() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        assertTrue(
            "addPaperFundsSafe6765 must exist as a synchronized function",
            src.contains("@Synchronized") && src.contains("fun addPaperFundsSafe6765(sol: Double, reason: String)"),
        )
        assertTrue(
            "add must fold into BOTH startingCashPico AND cashPico (conservation invariant: start + realized - fees == cash + reserved + open)",
            src.contains("startingCashPico.addAndGet(addPico)") &&
                src.contains("cashPico.addAndGet(addPico)"),
        )
        assertTrue(
            "add must persist immediately so restart cannot lose it",
            src.contains("addPaperFundsSafe6765(sol: Double, reason: String)") &&
                src.contains("persistCurrent6487()"),
        )
        assertTrue(
            "add must reject non-positive input",
            src.contains("if (!sol.isFinite() || sol <= 0.0)") &&
                src.contains("PAPER_ADD_FUNDS_REJECTED_INVALID_INPUT_6765"),
        )
        assertTrue(
            "add must notify the journal authority so UI heroes render the new balance",
            src.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"ADD_FUNDS_6765\")"),
        )
    }

    @Test fun bot_config_paper_simulated_balance_never_returns_non_positive() {
        val src = File("src/main/kotlin/com/lifecyclebot/data/BotConfig.kt").readText()
        assertTrue(
            "load() must coerce paperSimulatedBalance to positive-only",
            src.contains("if (v.isFinite() && v > 0.0) v else 11.76") ||
                src.contains(".let { v -> if (v.isFinite() && v > 0.0) v else 11.76"),
        )
    }

    @Test fun behavior_activity_exposes_add_funds_alongside_reset() {
        val src = File("src/main/kotlin/com/lifecyclebot/ui/BehaviorActivity.kt").readText()
        assertTrue(
            "reset dialog must expose the Add Funds action",
            src.contains(".setNeutralButton(\"Add Funds\")"),
        )
        assertTrue(
            "add funds path must call addPaperFundsSafe6765",
            src.contains("addPaperFundsSafe6765(addSol,"),
        )
        assertTrue(
            "add funds path must display the new balance on success",
            src.contains("Added %.4f SOL"),
        )
    }
}
