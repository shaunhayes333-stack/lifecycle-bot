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

    // ═══════════════════════════════════════════════════════════════════════
    // V5.0.6988 — THE CREED DECLARED PARITY AND NEVER ONCE MEASURED IT.
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Everything above this line is a list of intentions. logCreed() prints the
    // ten hardcoded names it was written with; statusLine() returned
    // `artefactsDeclared=${ARTEFACTS.size}` — the LENGTH OF THAT LIST. It never
    // opened a store, never read a key, never compared paper against live.
    //
    // So "Paper↔live parity (§6439): artefactsDeclared=10 mode=parity_required"
    // in every operator snapshot means nothing was checked. The module whose
    // entire purpose is to make a rogue mode-gated store "loud and visible" was
    // itself the reason none were visible.
    //
    // The operator's question is the right one: does the stack actually carry
    // its edge into real money, or is it paper fluff? A census answers it.
    //
    // MEASURED, NOT DECLARED. Two learners key their stores by runtime mode:
    //
    //   ForwardOutcomeModel        every signature is prefixed "P|" or "L|"
    //                              (modeTag6869). The counterfactual edge map —
    //                              pWin, E[pnl], pRug per lane×band×regime —
    //                              resolves to zero samples on the first live
    //                              trade.
    //   ExecutableEntryAuthority6450  cohortKey(mode, lane) prefixes every
    //                              losing-streak cohort with the mode. The
    //                              defensive reflex, its score-floor delta and
    //                              its size multiplier all restart from no
    //                              history at the exact moment real money is
    //                              at risk.
    //
    // Both are precisely what the creed above calls a bug. This does not change
    // either store — silently merging paper and live samples would assert that
    // simulated fills and real fills are the same evidence, which is a trading
    // decision and the operator's to make. It reports, loudly, with numbers.
    data class LearnerCensus6988(
        val learner: String,
        val paperKeys: Int,
        val liveKeys: Int,
    ) {
        /** Paper has learned something that live will not inherit. */
        val resetsOnFlip: Boolean get() = paperKeys > 0 && liveKeys == 0
    }

    fun census6988(): List<LearnerCensus6988> {
        val out = ArrayList<LearnerCensus6988>(2)
        try {
            val (p, l) = com.lifecyclebot.engine.ForwardOutcomeModel.modeCensus6988()
            out.add(LearnerCensus6988("ForwardOutcomeModel.signatures", p, l))
        } catch (_: Throwable) {}
        try {
            val (p, l) = ExecutableEntryAuthority6450.modeCensus6988()
            out.add(LearnerCensus6988("ExecutableEntryAuthority6450.streakCohorts", p, l))
        } catch (_: Throwable) {}
        try {
            // V5.0.6990 — third learner of this shape: key(lane, isPaper)
            // prefixes every streak with PAPER or LIVE.
            val (p, l) = com.lifecyclebot.engine.runtime.ColdStreakDamper.modeCensus6988()
            out.add(LearnerCensus6988("ColdStreakDamper.laneStreaks", p, l))
        } catch (_: Throwable) {}
        return out
    }

    /**
     * Run the census and emit one loud event per learner that would lose its
     * learning on the paper→live flip. Called alongside logCreed at boot, and
     * safe to call again at any time.
     */
    fun verify6988() {
        val rows = census6988()
        var gated = 0
        for (r in rows) {
            if (!r.resetsOnFlip) continue
            gated++
            try {
                PipelineHealthCollector.labelInc("PARITY_LEARNER_MODE_GATED_6988")
                ForensicLogger.lifecycle(
                    "PARITY_LEARNER_MODE_GATED_6988",
                    "learner=${r.learner} paperKeys=${r.paperKeys} liveKeys=${r.liveKeys} " +
                        "effect=this_learning_does_not_transfer_on_paper_to_live_flip " +
                        "creed=PaperLiveParityCreed6439_declares_mode_gated_stores_a_bug",
                )
            } catch (_: Throwable) {}
        }
        try {
            ForensicLogger.lifecycle(
                "PAPER_LIVE_PARITY_VERIFIED_6988",
                "learnersChecked=${rows.size} modeGated=$gated " +
                    "artefactsDeclaredButUnverified=${ARTEFACTS.size} " +
                    "read=declared_is_not_measured_only_the_census_is_evidence",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val rows = try { census6988() } catch (_: Throwable) { emptyList() }
        if (rows.isEmpty()) {
            return "artefactsDeclared=${ARTEFACTS.size} measured=0 " +
                "mode=parity_DECLARED_NOT_MEASURED"
        }
        val detail = rows.joinToString(" · ") { r ->
            "${r.learner.substringBefore('.')}[paper=${r.paperKeys} live=${r.liveKeys}" +
                (if (r.resetsOnFlip) " RESETS_ON_FLIP" else "") + "]"
        }
        val gated = rows.count { it.resetsOnFlip }
        return "artefactsDeclared=${ARTEFACTS.size}(unverified) measuredLearners=${rows.size} " +
            "modeGated=$gated · $detail"
    }
}
