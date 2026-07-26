package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6373 — Fanout Same-Mint Cut · Trade-1 Catastrophic Rotation ·
 *              Skew-Taint Learning Quarantine · CryptoAlt Content-Diff Skip.
 *
 * P0.1: V3 execute route must skip doBuy when EmergentGuardrails already
 *       has an open position for the mint (source of 523 EXEC_GATE blocks
 *       observed in operator snapshot).
 *
 * P0.2: TacticSwitcher must rotate ANY tactic on a single n=1 close with
 *       pnl <= -90% (operator: "self-learning from trade 1"). Was
 *       gated to MOMENTUM-only in V5.0.6367a; now unconditional.
 *
 * P0.3: V3JournalRecorder must skip all learning writes when buy/sell qty
 *       ratio exceeds 10× AND resulting pnl% <= -80% (decimal skew
 *       contamination — μ pollution from wallet-verified vs heuristic-
 *       inferred qty divergence).
 *
 * P0.4: CryptoAltActivity.renderTokenList must skip the full LinearLayout
 *       rebuild when the page signature (tab/sort/sector/search/page +
 *       per-token symbol/price/mcap) is identical to the last render.
 */
class Bundle6373InvariantsTest {

    @Test
    fun v3_execute_route_preempts_same_mint_doBuy_at_source() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6373: V3 execute route must consult EmergentGuardrails.getPositionLayer BEFORE executor.doBuy so 523 same-mint blocks stop at source",
            txt.contains("V5.0.6373 — SOURCE-OF-CREATION same-mint suppression") &&
                txt.contains("EmergentGuardrails.getPositionLayer(ts.mint)") &&
                txt.contains("V3_EXEC_SAME_MINT_PREEMPT_6373"),
        )
        assertTrue(
            "V5.0.6373: preempt must short-circuit before doBuy work with a distinct error tag",
            txt.contains("SAME_MINT_ALREADY_OPEN_6373_V3_PREEMPT"),
        )
    }

    @Test
    fun tacticSwitcher_rotates_on_single_catastrophic_trade() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "V5.0.6373: TRADE_ONE_CATASTROPHIC_PNL constant must be present at -90.0",
            txt.contains("private const val TRADE_ONE_CATASTROPHIC_PNL = -90.0"),
        )
        assertTrue(
            "V5.0.6373: onTradeClosed must rotate on tradesIn==1 && pnlPct <= TRADE_ONE_CATASTROPHIC_PNL regardless of pivoted state",
            txt.contains("tradesIn == 1 && pnlPct <= TRADE_ONE_CATASTROPHIC_PNL"),
        )
        assertTrue(
            "V5.0.6373: rotation reason must mark trade1-catastrophic for forensic clarity",
            txt.contains("trade1-catastrophic"),
        )
    }

    @Test
    fun v3JournalRecorder_quarantines_skew_tainted_learning_writes() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue(
            "V5.0.6373: skew-taint check must gate all learning writes (TacticSwitcher / RetrainingDecay / ExplorationBudget etc.)",
            txt.contains("V5.0.6373 — SKEW-TAINT LEARNING QUARANTINE") &&
                txt.contains("val skewTainted6373") &&
                txt.contains("SKEW_TAINT_LEARNING_QUARANTINE_6373"),
        )
        assertTrue(
            "V5.0.6373: quarantine gate must return before feeding ScoreExpectancy/TacticSwitcher/RetrainingDecay",
            // return must appear BEFORE the ScoreExpectancyTracker.record call so learners are skipped
            txt.indexOf("if (skewTainted6373) return") in 0..txt.indexOf("ScoreExpectancyTracker.record(layer"),
        )
        assertTrue(
            "V5.0.6373: quarantine must trigger only when ratio > 10× AND pnl <= -80% (source-of-creation precision)",
            txt.contains("ratio > 10.0 && pnlPctLearn <= -80.0"),
        )
    }

    @Test
    fun cryptoAlt_renderTokenList_has_content_diff_skip() {
        val txt = File("src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt").readText()
        assertTrue(
            "V5.0.6373: renderTokenList must compute a page signature and skip rebuild when unchanged",
            txt.contains("V5.0.6373 — SOURCE-OF-CREATION content-diff early-out") &&
                txt.contains("val pageHash6373: Long") &&
                txt.contains("CRYPTO_ALT_TOKEN_LIST_RENDER_SKIPPED_6373"),
        )
        assertTrue(
            "V5.0.6373: lastRenderedTokenListHash field must be present",
            txt.contains("private var lastRenderedTokenListHash: Long"),
        )
        assertTrue(
            "V5.0.6373: hash must include per-token price / mcap so genuine data changes still trigger rebuild",
            txt.contains("java.lang.Double.doubleToLongBits(t.price)") &&
                txt.contains("java.lang.Double.doubleToLongBits(t.mcap)"),
        )
    }
}
