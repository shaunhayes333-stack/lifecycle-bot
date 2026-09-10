package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6373 — Fanout Same-Mint Cut · Trade-1 Catastrophic Rotation ·
 *              Skew-Taint Learning Quarantine · CryptoAlt Content-Diff Skip.
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
            "V5.0.6715 supersedes the 6373 rug-only threshold: trade-one policy failure must pivot at -25.0",
            txt.contains("private const val TRADE_ONE_CATASTROPHIC_PNL = -25.0"),
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
            "V5.0.6373: skew-taint predicate and quarantine telemetry must remain active",
            txt.contains("val skewTainted6373: Boolean") &&
                txt.contains("SKEW_TAINT_LEARNING_QUARANTINE_6373"),
        )
        val quarantineReturn = txt.indexOf("if (skewTainted6373) return")
        val firstLearningWrite = txt.indexOf("ScoreExpectancyTracker.record(layer")
        assertTrue(
            "V5.0.6373: quarantine must return before feeding ScoreExpectancy/TacticSwitcher/RetrainingDecay",
            quarantineReturn >= 0 && firstLearningWrite > quarantineReturn,
        )
        assertTrue(
            "V5.0.6373: quarantine must trigger only when ratio > 10× AND pnl <= -80%",
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
