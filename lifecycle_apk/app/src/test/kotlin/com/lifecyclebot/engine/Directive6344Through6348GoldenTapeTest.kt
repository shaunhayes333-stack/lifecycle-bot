package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6349 — GOLDEN-TAPE GUARD FOR THE 6344→6348 ARCHITECTURAL DIRECTIVE.
 *
 * Operator directive P0-1 through P1-2 landed as a family of six modules
 * plus the CanonicalPnLAuthority6343 that already shipped. If any future
 * regression deletes / renames / silently guts these files this test
 * fails loudly so the CI build is red BEFORE the operator has to catch
 * it in a live pipeline health dump.
 *
 * These are file-shape assertions only — the runtime invariant tests
 * live in the module-specific *Test.kt files.
 */
class Directive6344Through6348GoldenTapeTest {

    private fun src(name: String) =
        File("src/main/kotlin/com/lifecyclebot/engine/$name").readText()

    @Test
    fun canonical_pnl_authority_6343_still_exposes_key_apis() {
        val txt = src("CanonicalPnLAuthority6343.kt")
        assertTrue("must expose computeRealizedSol",
            txt.contains("fun computeRealizedSol("))
        assertTrue("must expose assertPriceCostQtyParity",
            txt.contains("fun assertPriceCostQtyParity("))
        assertTrue("must forbid LIVE_BROADCAST canonical attribution",
            txt.contains("PROOF_LIVE_BROADCAST_NOT_CANONICAL_6343"))
        assertTrue("must carry Cupsey partial-lot correction docs",
            txt.contains("CUPSEY PARTIAL-LOT CORRECTION"))
    }

    @Test
    fun unit_types_6344_defines_all_five_value_classes() {
        val txt = src("UnitTypes6344.kt")
        assertTrue(txt.contains("value class SolAmount("))
        assertTrue(txt.contains("value class UsdAmount("))
        assertTrue(txt.contains("value class TokenQuantity("))
        assertTrue(txt.contains("value class PriceSolPerToken("))
        assertTrue(txt.contains("value class PriceUsdPerToken("))
    }

    @Test
    fun fill_lot_ledger_6344_is_immutable_and_pk_is_wallet_mint_sig() {
        val txt = src("FillLotLedger6344.kt")
        // Primary key spec.
        assertTrue("PK must be wallet + mint + buyTxSig",
            txt.contains("walletAddress + mintAddress + confirmedBuyTransactionSignature") ||
            txt.contains("wallet, mint, buyTxSig") ||
            txt.contains("walletAddress, mintAddress, buyTxSig"))
        // Append-only guarantee.
        assertTrue(txt.contains("fun appendBuy("))
        assertTrue(txt.contains("fun appendSell("))
        // First-write-wins.
        assertTrue(txt.contains("if (existing != null)"))
    }

    @Test
    fun realized_pnl_conduit_6344_delegates_to_authority() {
        val txt = src("RealizedPnlConduit6344.kt")
        assertTrue("conduit must delegate to CanonicalPnLAuthority6343",
            txt.contains("CanonicalPnLAuthority6343.computeRealizedSol"))
        assertTrue("must emit divergence health label",
            txt.contains("CANONICAL_PNL_DIVERGENCE_6344"))
        assertTrue("must expose finalize entry point",
            txt.contains("fun finalize("))
    }

    @Test
    fun pre_entry_decision_record_6345_emits_receipt_with_verdict() {
        val txt = src("PreEntryDecisionRecord6345.kt")
        assertTrue(txt.contains("fun emit("))
        assertTrue(txt.contains("PRE_ENTRY_DECISION_RECORD_6345"))
        assertTrue(txt.contains("enum class Verdict { PASS, WARN, VETO }"))
    }

    @Test
    fun executable_price_stop_preflight_6345_clamps_above_bid_and_unreachable() {
        val txt = src("ExecutablePriceStopPreflight6345.kt")
        assertTrue(txt.contains("STOP_PREFLIGHT_ABOVE_BID_CLAMPED_6345"))
        assertTrue(txt.contains("STOP_PREFLIGHT_UNREACHABLE_CLAMPED_6345"))
        assertTrue(txt.contains("fun preflight("))
    }

    @Test
    fun canonical_learning_contract_6346_enforces_three_pillars() {
        val txt = src("CanonicalLearningContract6346.kt")
        assertTrue("must delegate parity to 6343",
            txt.contains("CanonicalPnLAuthority6343.assertPriceCostQtyParity"))
        assertTrue("must reject LIVE_BROADCAST canonical",
            txt.contains("PROOF_LIVE_BROADCAST_NOT_CANONICAL_6346"))
        assertTrue("must include exact-decimals guard",
            txt.contains("EXACT_DECIMALS_MISMATCH_6346"))
    }

    @Test
    fun scanner_hydration_queues_6347_defines_five_buckets() {
        val txt = src("ScannerHydrationQueues6347.kt")
        assertTrue(txt.contains("LIVE_READY,"))
        assertTrue(txt.contains("HYDRATING,"))
        assertTrue(txt.contains("PROBATION,"))
        assertTrue(txt.contains("SHADOW,"))
        assertTrue(txt.contains("REJECTED_WITH_TTL,"))
        assertTrue(txt.contains("fun drain("))
        assertTrue(txt.contains("fun rejectWithTtl("))
    }

    @Test
    fun first_trade_readiness_6348_exposes_five_priority_pillars() {
        val txt = src("FirstTradeReadiness6348.kt")
        assertTrue(txt.contains("GOVERNOR_NOT_HOLD"))
        assertTrue(txt.contains("SCANNER_LIVE_READY_QUEUE"))
        assertTrue(txt.contains("FILL_LOT_LEDGER_INIT"))
        assertTrue(txt.contains("CANONICAL_BUY_FILL_REGISTRY_INIT"))
        assertTrue(txt.contains("NO_OUTSTANDING_QUARANTINES"))
        assertTrue("must expose snapshotLine for the health tile",
            txt.contains("fun snapshotLine("))
    }

    @Test
    fun health_tile_wires_the_first_trade_readiness_line() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("Pipeline health tile must surface FIRST_TRADE_READINESS",
            txt.contains("FirstTradeReadiness6348.snapshotLine"))
    }

    @Test
    fun executor_buy_path_wires_fill_lot_ledger_appendBuy() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Executor buy-verify path must append to FillLotLedger6344",
            txt.contains("FillLotLedger6344.appendBuy("))
    }

    @Test
    fun executor_sell_path_wires_realized_pnl_conduit_finalize() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Executor sell-finalize path must run through RealizedPnlConduit6344",
            txt.contains("RealizedPnlConduit6344.finalize("))
    }

    @Test
    fun bot_service_initialises_the_fill_lot_ledger_on_boot() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("BotService.onCreate must call FillLotLedger6344.init",
            txt.contains("FillLotLedger6344.init(applicationContext)"))
    }
}
