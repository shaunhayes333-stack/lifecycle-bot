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
        /**
         * V5.0.7035 — does this learner READ paper's cells when live has none?
         *
         * Declared by the census, because only the learner's own read path
         * knows. It cannot be inferred from key counts, and inferring it from
         * key counts is exactly what made this line wrong for three builds.
         */
        val inheritsFromPaper: Boolean = false,
    ) {
        /**
         * Paper has learned something that live will not inherit.
         *
         * V5.0.7035 §THE_PARITY_GUARD_COULD_NOT_BE_SATISFIED_EXCEPT_BY_GOING_LIVE.
         *
         * This was `paperKeys > 0 && liveKeys == 0`, which does not test
         * inheritance at all — it tests whether live has any keys YET, and in
         * a paper-only session that is false for every learner by definition,
         * forever, no matter how well its fallback works.
         *
         * So the snapshot printed RESETS_ON_FLIP against all three learners on
         * every run, including the two that had already been repaired:
         * ForwardOutcomeModel reads the other-mode cell as a thin-evidence
         * prior (6869) and shrinks its strength (6991), and ColdStreakDamper
         * seeds its loss streak through PaperSeededPrior6991 (6991). The 6991
         * source comment says so outright — "my V5.0.6988 note claiming this
         * model resolves to zero samples on the flip was wrong; it does not" —
         * and this line kept asserting the retracted claim anyway.
         *
         * It cost real work: the label sent me at two already-correct learners
         * and would have sent anyone else the same way. A guard that fires on
         * every session cannot distinguish a fault from a normal Tuesday.
         */
        val resetsOnFlip: Boolean get() = paperKeys > 0 && liveKeys == 0 && !inheritsFromPaper
    }

    fun census6988(): List<LearnerCensus6988> {
        val out = ArrayList<LearnerCensus6988>(3)
        try {
            val (p, l) = com.lifecyclebot.engine.ForwardOutcomeModel.modeCensus6988()
            // Inherits: forecast() falls through to the other-mode fine/coarse
            // cell ("fine_paper_prior" / "coarse_paper_prior") when its own has
            // fewer than MIN_SAMPLES, at a strength damped by 6991.
            out.add(LearnerCensus6988("ForwardOutcomeModel.signatures", p, l, inheritsFromPaper = true))
        } catch (_: Throwable) {}
        try {
            val (p, l) = ExecutableEntryAuthority6450.modeCensus6988()
            // Inherits as of V5.0.7035 — gate() seeds the live cohort's loss
            // streak and cooldown from PAPER through PaperSeededPrior6991.
            // Before 7035 this was the only one of the three that genuinely
            // reset, and it was the one nobody looked at.
            out.add(LearnerCensus6988("ExecutableEntryAuthority6450.streakCohorts", p, l, inheritsFromPaper = true))
        } catch (_: Throwable) {}
        try {
            // Inherits: effectiveLossStreak6991 seeds the live streak from the
            // paper streak via PaperSeededPrior6991.seedProtective.
            val (p, l) = com.lifecyclebot.engine.runtime.ColdStreakDamper.modeCensus6988()
            out.add(LearnerCensus6988("ColdStreakDamper.laneStreaks", p, l, inheritsFromPaper = true))
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
            // V5.0.7035 — say which it is. "paper=14 live=0" alone reads as a
            // fault; "paper=14 live=0 SEEDS_LIVE" reads as the design working.
            "${r.learner.substringBefore('.')}[paper=${r.paperKeys} live=${r.liveKeys}" +
                (if (r.resetsOnFlip) " RESETS_ON_FLIP"
                 else if (r.inheritsFromPaper) " SEEDS_LIVE" else "") + "]"
        }
        val gated = rows.count { it.resetsOnFlip }
        return "artefactsDeclared=${ARTEFACTS.size}(unverified) measuredLearners=${rows.size} " +
            "modeGated=$gated · $detail"
    }
}
