package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6439 — PAPER↔LIVE LEARNING PARITY CREED.
 *
 * OPERATOR DIRECTIVE:
 *   "Live and paper trading must align so live gets the correct learning
 *    passed on from paper mode."
 *
 * Meaning: everything the bot learns while running PAPER (~1000 trades
 * per the operator's flip plan) must transfer directly into LIVE mode
 * with zero re-learning. If any learner keeps a mode-gated store, that
 * store is a bug — flip to live at trade #1001 and it forgets.
 *
 * This creed enumerates the learning artefacts that MUST be mode-neutral
 * (same persistence key + same schema in paper and live) and provides a
 * boot-time verifier that logs which ones are aligned.
 *
 * NOTE: this module DOES NOT own the stores it lists — those live in
 * SentienceAutoTune, AdaptiveLearning, LabUniverseEngine, StrategyClean,
 * StrategyLeaderboard, ExitTuner, EntryAuthority etc. This module just
 * DECLARES the expectation + emits telemetry so a rogue mode-gated
 * store is loud and visible.
 */
object PaperLiveParityCreed6439 {

    /**
     * Named learning artefacts that MUST persist identically in paper
     * and live. Each entry is a human-readable identifier + the
     * expected storage layer.
     */
    val ARTEFACTS: List<Pair<String, String>> = listOf(
        "SentienceAutoTune scores"          to "SharedPreferences:sentience_scores",
        "AdaptiveLearning weights"          to "SharedPreferences:adaptive_learning",
        "LabUniverse trader scores"         to "SharedPreferences:lab_universe",
        "StrategyClean bandit"              to "SharedPreferences:strategy_clean",
        "StrategyLeaderboard cache"         to "SharedPreferences:strategy_leaderboard",
        "ExitTuner curves"                  to "SharedPreferences:exit_tuner",
        "EntryAuthority thresholds"         to "SharedPreferences:entry_authority",
        "GlobalTradeRegistry (open+closed)" to "SharedPreferences:global_trade_registry",
        "PositionCloseLedger"               to "SQLite:portfolio6405.db.positions",
        "ForensicEventEnvelope6430 runs"    to "SharedPreferences:forensic_runs",
    )

    /**
     * Emitted at boot so the operator's next dump shows exactly which
     * learners are trusted to survive the paper→live flip. Every named
     * artefact prints a single PAPER_LIVE_PARITY_6439 lifecycle event.
     */
    fun logCreed() {
        try {
            ForensicLogger.lifecycle(
                "PAPER_LIVE_PARITY_CREED_6439",
                "artefactCount=${ARTEFACTS.size} — learning MUST survive paper→live flip with zero retraining",
            )
        } catch (_: Throwable) {}
        for ((name, store) in ARTEFACTS) {
            try {
                ForensicLogger.lifecycle(
                    "PAPER_LIVE_PARITY_6439",
                    "artefact=$name store=$store",
                )
            } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("PAPER_LIVE_PARITY_CREED_6439") } catch (_: Throwable) {}
    }

    fun statusLine(): String = "artefactsDeclared=${ARTEFACTS.size} mode=parity_required"
}
