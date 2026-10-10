package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.0.7781 — RESET ALL LEARNING actually goes back to zero.
 *
 * Operator: "ive reset learning. its meant to reset back to 0. it hasnt. im
 * trying to give us a clean start."
 *
 * LearningPersistence.resetAll() zeroed the learning_kv table and ~30 in-memory
 * learners, but roughly fifty learners persist to their own SharedPreferences
 * file and reload it on boot. The 5.0.7779 snapshot after a reset still read
 * PROJECT_SNIPER n=68, lane shadow proof on 7 lanes, 1545 restored forward
 * labels, oracle historyCloses=206, TradingMemory 83 bad tokens, and a live
 * pause window that kept vetoing every buy (DISCIPLINE_VETO_V4132=94).
 *
 * Fix, in two halves so nothing can resurrect old state:
 *  1. requestFullReset() — clears the stores that have in-memory resets now,
 *     wipes the journal through the existing TradeHistoryStore.clearAllTrades()
 *     (fresh cohort), clears every learning prefs file, and arms a marker.
 *  2. applyPendingAtBoot() — runs first in AATEApp.onCreate, before any learner
 *     loads. If the marker is armed it clears the same files again (a learner
 *     still holding old memory may have saved it back before the restart),
 *     then disarms.
 *
 * NEVER touched: wallet, keys, config, open positions, fill/finality/idempotency
 * ledgers, paper and treasury balances, and real-world safety facts (rug-mint
 * blacklist, rugged contracts, banned tokens, dead-token quarantine, hard
 * scanner rejects, TradingMemory rug patterns and creator blacklist).
 * Field Manual: a fresh start relearns edges, it does not forget who rugged.
 */
object LearningResetSweep7781 {

    private const val MARKER_PREFS = "aate_learning_reset_7781"
    private const val KEY_PENDING = "pending"

    /** Learning-only stores. Each reloads on boot, so each must be cleared. */
    internal val LEARNING_PREFS_7781: List<String> = listOf(
        // discipline windows that veto/size live entries
        "live_pause_button", "lane_timeout_gate", "scanner_lane_bridge",
        "crypto_live_pause_button", "crypto_lane_timeout_gate", "crypto_scanner_lane_bridge",
        "live_entry_governor_window_6328", "live_lane_governor_v6247", "moonshot_adaptive_gate",
        // proof / label / oracle stores
        "forward_return_labeler_7731", "lane_shadow_proof_7307", "fresh_launch_selector_7737",
        "aate_signal_source_proof_7291", "aate_oracle_edge_proof_7287", "aate_lane_hunter_7297",
        // memories and brains
        "token_win_memory", "education_sublayer_ai", "behavior_ai_state", "live_win_dna_v6238",
        "bot_brain_decay", "bot_brain_memory", "bot_brain_thresholds", "layer_brains",
        "forward_outcome_model", "unified_policy_head", "unified_exit_policy_head",
        "strategy_hypothesis_engine", "autonomous_meta_policy", "ml_logit_weights", "ml_norm_stats",
        "pattern_classifier_v1", "pattern_auto_tuner", "smart_sizer_state", "symbolic_rule_trust",
        "scanner_source_brain", "scanner_learning", "adaptive_learning", "llm_trade_score",
        "ai_trust_network_v1", "exec_cost_predictor_v1", "session_edge_v1", "operator_fingerprint_v1",
        "personality_memory_v1", "collective_learning_cache",
        "edge_learning", "entry_intelligence", "exit_intelligence", "liquidity_depth_ai",
        "market_regime_ai", "momentum_predictor_ai", "narrative_detector_ai", "time_optimization_ai",
        "whale_tracker_ai", "ai_crosstalk",
        "fluid_learning", "fluid_learning_alts", "fluid_learning_markets",
        // lane trader brains (thresholds and stats only; positions live elsewhere)
        "bluechip_trader_ai", "moonshot_trader_ai", "project_sniper_ai", "quality_trader_ai",
        "shitcoin_express_ai", "shitcoin_trader_ai",
        "perps_learning_bridge", "perps_heatmap", "perps_trader_ai", "crypto_brain_v1",
        // journal lifetime totals (clearAllTrades deliberately keeps these)
        "trade_history_store",
        // V5.0.7975 — the 79xx learners that keep their own prefs
        "cell_allocator_7962",
    )

    /**
     * V5.0.7975 — learning databases / files wiped at boot. The Cortex, lane playbooks,
     * exit profiles, timing / exit cortex and stop authority live in learning_kv.db
     * (LearningPersistence): resetAll() cleared it once, but those learners still held
     * their ledgers in memory and saved them back before the restart, so a reset kept
     * every "PROVEN_LOSING" refusal (5.0.7972 after a reset: PLAYBOOK_NO_TRIGGER_PROVEN_LOSING
     * 230, CORTEX C3 234) while the positive evidence was gone — and the bot would not trade.
     * Deleting them at boot, before anything opens them, makes the reset final.
     */
    internal val LEARNING_DBS_7975: List<String> = listOf("learning_kv.db")
    internal val LEARNING_FILES_7975: List<String> = listOf("specialists7972.bin", "runner_grab_7989.txt", "tail_hunter_7996.txt")

    @Volatile private var bootSweeps = 0
    @Volatile private var requestSweeps = 0

    fun requestFullReset(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PENDING, true).commit()
        } catch (_: Throwable) {}
        fun z(name: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { ErrorLogger.warn("LearningReset7781", "$name: ${e.message}") }
        }
        z("JOURNAL") { com.lifecyclebot.engine.TradeHistoryStore.clearAllTrades() }
        z("LIVE_PAUSE") { com.lifecyclebot.engine.LivePauseButton.reset() }
        z("LANE_TIMEOUT") { com.lifecyclebot.engine.LaneTimeoutGate.reset() }
        z("SCANNER_BRIDGE") { com.lifecyclebot.engine.ScannerLaneBridge.reset() }
        z("TOKEN_WIN_MEMORY") { com.lifecyclebot.engine.TokenWinMemory.reset() }
        z("TRADING_MEMORY") { com.lifecyclebot.engine.TradingMemory.clearLearned7781() }
        z("EDUCATION") { com.lifecyclebot.v3.scoring.EducationSubLayerAI.resetAllLearning() }
        wipe(app)
        requestSweeps++
        ErrorLogger.info("LearningReset7781", "Reset armed: ${LEARNING_PREFS_7781.size} stores cleared; finishes on next app start")
    }

    /** Call first in Application.onCreate, before any learner loads. */
    fun applyPendingAtBoot(ctx: Context) {
        val app = ctx.applicationContext
        val marker = try { app.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        if (!marker.getBoolean(KEY_PENDING, false)) return
        wipe(app)
        for (db in LEARNING_DBS_7975) try { app.deleteDatabase(db) } catch (_: Throwable) {}
        for (f in LEARNING_FILES_7975) try { java.io.File(app.filesDir, f).delete() } catch (_: Throwable) {}
        bootSweeps++
        try { marker.edit().putBoolean(KEY_PENDING, false).commit() } catch (_: Throwable) {}
        ErrorLogger.info("LearningReset7781", "Reset completed at boot: ${LEARNING_PREFS_7781.size} stores cleared")
    }

    private fun wipe(app: Context) {
        for (name in LEARNING_PREFS_7781) {
            try { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String = "requests=$requestSweeps bootSweeps=$bootSweeps stores=${LEARNING_PREFS_7781.size}+db${LEARNING_DBS_7975.size}+files${LEARNING_FILES_7975.size}" +
        (if (requestSweeps > 0 && bootSweeps == 0) " PENDING_RESTART(close and reopen AATE to finish the reset)" else "")
}
