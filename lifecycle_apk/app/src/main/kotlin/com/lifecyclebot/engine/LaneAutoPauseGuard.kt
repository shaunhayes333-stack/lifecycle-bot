package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4588 — LANE AUTO-PAUSE GUARD (operator P0 tasks a + b).
 *
 * Operator directive on 2026-02:
 *   • "EXPRESS needs to find the right strategy and tokens to trade!
 *      theres real opportunity for profit its just dumb at the source."
 *   • "MANIPULATED can be paused until llm lab proves a strategy thats
 *      profitable and is introduced then unpaused"
 *
 * The lanes literally have 0/19 (EXPRESS) and 5/33 with -50% EV
 * (MANIPULATED) as of build 5.0.4587 — no size shaping short of
 * multiplying-by-zero recovers from that. Rather than force zero-size
 * (which hides the lane from telemetry and pattern learning), this
 * guard blocks admission at the FDG level and marks the lane
 * "quarantined — awaiting LLM Lab strategy".
 *
 * A paused lane can still be run in the LLM Lab sandbox / shadow paper
 * — the guard only blocks the LIVE admission path.
 *
 * Rules (auto-pause fires when ALL true for the lane):
 *   • Sample floor:  n >= 15 trainable closes
 *   • Verdict:       (wins == 0)  OR  (WR < 20% AND EV <= -40%)
 *   • Not manually resumed since the last auto-pause event
 *
 * Doctrine:
 *   • Never touches paper / sandbox / lab paths — only LIVE admission.
 *   • Fail-open: any exception returns false (no block).
 *   • Persists across restarts via LearningPersistence so a device
 *     reboot doesn't silently reopen a proven-toxic lane.
 *   • Manual resume via LaneAutoPauseGuard.manualResume(lane) once
 *     an LLM Lab shadow proof shows >30% WR (or operator override).
 */
object LaneAutoPauseGuard {
    const val VERSION = "V5.0.4588_LANE_AUTO_PAUSE"
    private const val PERSIST_KEY = "LANE_AUTO_PAUSE_STATE"

    // Triggers
    // V5.0.6067 — AGGRESSIVE LANE AUTO-PAUSE.
    // Operator P0 (V5.0.6066 report): PRESALE_SNIPE lane appeared fresh and
    // burned n=10 straight losses for -0.2473 SOL before the guard could
    // trigger (previous ZERO_WIN_MIN_SAMPLE=15). QUALITY at n=10 WR=0% also
    // slipped through. Wallet went 0.6022 -> 0.4938 SOL (-18%) in 40 min.
    // Lower thresholds so bleeders quarantine 5-8 trades sooner:
    //   ZERO_WIN_MIN_SAMPLE 15 -> 8   (pause after 8 straight losses)
    //   TOXIC_MIN_SAMPLE     20 -> 12  (pause toxic lanes at n=12)
    //   TOXIC_WR_PCT         20 -> 20  (unchanged)
    //   TOXIC_EV_PCT        -40 -> -20 (much stricter EV floor)
    // Non-priority safety: MOONSHOT/STANDARD are handled by the compound
    // sizing floor (V5.0.6066), not this guard. Manual resume remains.
    private const val MIN_SAMPLE = 5
    private const val ZERO_WIN_MIN_SAMPLE = 5
    private const val TOXIC_WR_PCT = 20.0
    // V5.0.7105 — the three RECOVERY_* constants that stood here were removed.
    // V5.0.6684 replaced WR-based auto-unpause with proof-gated reproof, but
    // left the constants and their doc comment behind, so the file described a
    // recovery rule it did not implement. Recovery now lives entirely in
    // AdaptiveLaneReproof6684; see the note in evaluateLive().
    private const val TOXIC_EV_PCT = -8.0
    private const val TOXIC_MIN_SAMPLE = 8

    // V5.0.7734 §LEARN_BEFORE_TIGHTEN.
    //
    // 5.0.7732: TREASURY paused zero-win on six live closes (six $5 tickets at
    // 3.8% fixed cost) while its own shadow book, the lane's refused candidates
    // marked to market for an hour, read +7.3% net over ten. It was the only
    // lane with a positive measured record and the one locked out. Operator
    // doctrine: a lane is not tightened before ten decisive closes. A pause on
    // fewer than that yields to the lane's shadow record when that record is
    // positive at ten or more; the pause predicate re-applies the moment the
    // live record reaches ten.
    private const val LEARN_BEFORE_TIGHTEN_MIN_CLOSES_7734 = 10
    private const val SHADOW_SUPPORT_MIN_N_7734 = 10
    private val shadowDeferLogAt7734 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun shadowOutranksThinLiveRecord7734(lane: String, liveSample: Int): Boolean {
        if (liveSample >= LEARN_BEFORE_TIGHTEN_MIN_CLOSES_7734) return false
        val s = try { com.lifecyclebot.engine.truth.LaneShadowProof7307.stat(lane) } catch (_: Throwable) { null } ?: return false
        if (s.n < SHADOW_SUPPORT_MIN_N_7734 || s.meanNetPct <= 0.0) return false
        try {
            PipelineHealthCollector.labelInc("LANE_PAUSE_DEFERRED_SHADOW_POSITIVE_7734")
            PipelineHealthCollector.labelInc("LANE_PAUSE_DEFERRED_SHADOW_POSITIVE_7734_$lane")
            val now = System.currentTimeMillis()
            if (now - (shadowDeferLogAt7734[lane] ?: 0L) >= 5 * 60_000L) {
                shadowDeferLogAt7734[lane] = now
                ForensicLogger.lifecycle(
                    "LANE_PAUSE_DEFERRED_SHADOW_POSITIVE_7734",
                    "lane=$lane liveCloses=$liveSample shadowN=${s.n} shadowNet=${"%+.1f".format(s.meanNetPct)}% " +
                        "action=no_pause_below_ten_closes_while_the_lanes_own_shadow_record_is_positive",
                )
            }
        } catch (_: Throwable) {}
        return true
    }

    // V5.0.7737 §THE_LANE'S_OWN_LABELS.
    //
    // 5.0.7736 at 2972 s: TREASURY liveQuarantine=true, reason
    // awaiting_exact_lab_proof, while the forward labeler had followed 28 of the
    // lane's candidates an hour forward at +1.8% net, 50% positive, and the
    // forecast model's label cell S|TREASURY read pWin 66%, E +5.2% at n=12. The
    // Lab path wants 30 sandbox paper trades from a seed strategy; the lane's
    // own market record was already larger and positive. A paused lane whose
    // 60-minute label record is positive at [LABEL_RELEASE_MIN_N60_7737] or more
    // is released into a fresh live epoch: closes before the release no longer
    // count, so the ordinary pause predicate re-arms on five straight losses or
    // eight toxic closes made after it. A lane re-paused after a label release
    // needs [LABEL_RELEASE_MIN_N60_7737] more labels, still positive, before a
    // second one, so losses cannot be laundered by a stale table.
    private const val LABEL_RELEASE_MIN_N60_7737 = 25
    private const val LABEL_RELEASE_MIN_WR_7737 = 0.45
    private const val LABEL_RELEASE_PERSIST_KEY_7737 = "LANE_LABEL_RELEASE_7737"
    private data class LabelRelease7737(val atMs: Long, val n60AtRelease: Int)
    private val labelRelease7737 = ConcurrentHashMap<String, LabelRelease7737>()

    private fun labelsForLane7737(lane: String): com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat? {
        val direct = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null }
        if (direct != null || lane != "PRESALE_SNIPE") return direct
        return try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737("PROJECT_SNIPER") } catch (_: Throwable) { null }
    }

    private fun labelReleaseAllowed7737(lane: String, st: PauseState): com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat? {
        val s = labelsForLane7737(lane) ?: return null
        if (s.n60 < LABEL_RELEASE_MIN_N60_7737 || s.meanNet60Pct <= 0.0 || s.winRate60 < LABEL_RELEASE_MIN_WR_7737) return null
        val prior = labelRelease7737[lane]
        if (prior != null && st.pausedAt > prior.atMs && s.n60 < prior.n60AtRelease + LABEL_RELEASE_MIN_N60_7737) return null
        return s
    }

    private fun loadLabelRelease7737() {
        try {
            val blob = LearningPersistence.load(LABEL_RELEASE_PERSIST_KEY_7737) ?: return
            val o = org.json.JSONObject(blob)
            for (k in o.keys()) {
                val r = o.optJSONObject(k) ?: continue
                labelRelease7737[k] = LabelRelease7737(r.optLong("at"), r.optInt("n60"))
            }
        } catch (_: Throwable) {}
    }

    private fun persistLabelRelease7737() {
        try {
            val o = org.json.JSONObject()
            labelRelease7737.forEach { (k, r) -> o.put(k, org.json.JSONObject().put("at", r.atMs).put("n60", r.n60AtRelease)) }
            LearningPersistence.save(LABEL_RELEASE_PERSIST_KEY_7737, o.toString())
        } catch (_: Throwable) {}
    }

    data class PauseState(
        val lane: String,
        val pausedAt: Long,
        val reason: String,
        val sample: Int,
        val wins: Int,
        val wrPct: Double,
        val evPct: Double,
    )

    private val paused = ConcurrentHashMap<String, PauseState>()
    // V5.0.7193 — last observed post-pause record per paused lane, for the
    // status line. Rebuilt every evaluateLive tick; never a decision input.
    private val selfReproofProgress7193 = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    private val lastEvalMs = AtomicLong(0L)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            loadLabelRelease7737()
            try {
                val blob = LearningPersistence.load(PERSIST_KEY) ?: return
                val arr = org.json.JSONArray(blob)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lane = o.optString("lane", "").uppercase()
                    if (lane.isEmpty()) continue
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = o.optLong("pausedAt", System.currentTimeMillis()),
                        reason = o.optString("reason", "restored"),
                        sample = o.optInt("sample", 0),
                        wins = o.optInt("wins", 0),
                        wrPct = o.optDouble("wrPct", 0.0),
                        evPct = o.optDouble("evPct", 0.0),
                    )
                }
            } catch (_: Throwable) {}
            // V5.0.6687 — PATCH-ROT PURGE. Historical hard_seed_* pauses were
            // baked from old runtime samples and recreated after every restart,
            // contradicting the current adaptive tactic/reproof architecture. Remove
            // those persisted seeds once. Fresh runtime evidence may still create a
            // normal adaptive pause and AdaptiveLaneReproof can still promote it.
            val staleHardSeeds6687 = paused.entries
                .filter { it.value.reason.startsWith("hard_seed_", ignoreCase = true) }
                .map { it.key }
            if (staleHardSeeds6687.isNotEmpty()) {
                staleHardSeeds6687.forEach { paused.remove(it) }
                try {
                    PipelineHealthCollector.labelInc("LANE_HARD_SEED_PATCH_ROT_PURGED_6687")
                    ForensicLogger.lifecycle(
                        "LANE_HARD_SEED_PATCH_ROT_PURGED_6687",
                        "lanes=${staleHardSeeds6687.sorted().joinToString(",")} action=retain_adaptive_runtime_evidence_only",
                    )
                } catch (_: Throwable) {}
                try { persistAsync() } catch (_: Throwable) {}
            }
        }
    }

    private fun persistAsync() {
        try {
            val arr = org.json.JSONArray()
            paused.values.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("lane", s.lane)
                        .put("pausedAt", s.pausedAt)
                        .put("reason", s.reason)
                        .put("sample", s.sample)
                        .put("wins", s.wins)
                        .put("wrPct", s.wrPct)
                        .put("evPct", s.evPct),
                )
            }
            LearningPersistence.save(PERSIST_KEY, arr.toString())
        } catch (_: Throwable) {}
    }

    /**
     * Cheap read used by FDG on every admission. Fail-open on any
     * exception. Returns null if lane is not paused; returns the pause
     * state if it is.
     */
    /** V5.0.6072 — LANE CANON MERGE. PROJECT_SNIPER and PRESALE_SNIPE are the
     *  same lane written under two keys (ModeRouter fires PRESALE_SNIPE,
     *  EnabledTraderAuthority enables PROJECT_SNIPER). Split stats meant each
     *  key saw half the sample and quarantines latched twice as slow. All
     *  guard reads/aggregation collapse to PRESALE_SNIPE. */
    private fun canonLane(l: String): String {
        val u = l.uppercase()
        return if (u == "PROJECT_SNIPER" || u.contains("PRESALE")) "PRESALE_SNIPE" else u
    }

    fun statusFor(lane: String?): PauseState? {
        if (lane.isNullOrBlank()) return null
        ensureLoaded()
        return paused[canonLane(lane)]
    }

    /** True if the lane is currently auto-paused. */
    // V5.0.6069 — PAPER MODE = LEARN EVERYTHING. Operator directive: "make
    // sure every thing is readable in paper mode only. all lanes all traders
    // all trading g styles the cyclic trader everything." When the bot is in
    // paper mode, ignore all lane pauses so the AI accumulates learning data
    // across the entire doctrine surface without wasting real SOL. Live mode
    // still respects pauses — this only affects paper simulator loop.
    fun isPaused(lane: String?): Boolean {
        // Paper mode override: never blocked in paper so learning can span every
        // lane, style, and tactic surface — fastest brain rebuild after a wipe.
        if (com.lifecyclebot.engine.GlobalTradeRegistry.isPaperMode) return false
        return statusFor(lane) != null
    }

    /** Live-only check that bypasses the paper override. Used by internal
     *  bookkeeping (evaluateLive() short-circuits) so the guard still tracks
     *  paused state and can auto-resume once real live data reproves the lane. */
    fun isPausedLive(lane: String?): Boolean = statusFor(lane) != null

    /**
     * Snapshot all trainable lane telemetry from the CLEAN-TRUTH TradeHistoryStore
     * (V5.0.4592 — direct journal read; bypasses StrategyTelemetry cache which
     * was lagging behind live-terminal closes and letting proven-toxic lanes
     * add 4-6 more losers before the guard latched). Cheap; safe to call once
     * per bot-loop cycle (rate-limited to 30s).
     */
    fun evaluateLive() {
        val now = System.currentTimeMillis()
        val last = lastEvalMs.get()
        if (now - last < 30_000L) return
        if (!lastEvalMs.compareAndSet(last, now)) return
        ensureLoaded()
        try {
            // V5.0.4592 — DIRECT clean-truth journal read.
            // Operator P0: LaneAutoPauseGuard was reading via
            // LiveProbabilityEngine.laneSnapshots -> StrategyTelemetry which
            // filters/dedupes and can lag. Read TradeHistoryStore's
            // getRecentCleanStrategyTerminalTrades directly so wins=0 lanes
            // are quarantined on the very next tick after crossing n>=15.
            val clean = try {
                TradeHistoryStore.getRecentCleanStrategyTerminalTrades(limit = 2000)
            } catch (_: Throwable) { emptyList() }
            if (clean.isEmpty()) {
                try { ErrorLogger.debug("LaneAutoPauseGuard", "$VERSION evaluateLive: clean terminal trades empty (store cold)") } catch (_: Throwable) {}
                return
            }

            // V5.0.7209 §A_PAPER_RECORD_WAS_DISABLING_LIVE_LANES.
            //
            // Operator went live and reported "stupidly quiet". The snapshot's
            // second-largest counter, on a wallet with ONE closed live trade:
            //
            //   LANE_QUARANTINED_BLOCKED_ENTRY_6684                5503
            //   LANE_QUARANTINED_BLOCKED_ENTRY_6684_QUALITY        2753
            //   LANE_QUARANTINED_BLOCKED_ENTRY_6684_PROJECT_SNIPER 2750
            //     reason=awaiting_exact_lab_proof
            //
            // QUALITY and PROJECT_SNIPER — the two highest-volume lanes — were
            // quarantined, which is why both showed ownerSelected=0 buyIntent=0
            // against candidateN=1214 and 592.
            //
            // One live close cannot pause a lane: ZERO_WIN_MIN_SAMPLE is 5 and
            // TOXIC_MIN_SAMPLE is 8. Those pauses were earned in PAPER. This
            // function reads getRecentCleanStrategyTerminalTrades(2000) with no
            // mode filter and aggregates paper and live together, while
            // isBlocked() above returns false in paper (line 178). So the
            // consequence is invisible for the entire paper run and lands, in
            // full, the instant real money is connected. The class docstring
            // claims "Never touches paper / sandbox / lab paths — only LIVE
            // admission"; that was true of enforcement and false of learning.
            //
            // Same shape as V5.0.7091's maxPaperMicroTradesPerHour and 7154's
            // effN: a value measuring something other than what its name and
            // its docstring promise. evaluateLive() now evaluates live.
            //
            // A lane's record in one mode is not evidence about its behaviour
            // in the other — different sizes, different slippage, different
            // fills. Paper is where a lane earns a paper verdict.
            val modeTag7209 = try {
                if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "paper" else "live"
            } catch (_: Throwable) { "live" }
            val modeClean7209 = clean.filter { it.mode.equals(modeTag7209, ignoreCase = true) }
            try {
                PipelineHealthCollector.labelInc("LANE_PAUSE_EVIDENCE_MODE_SCOPED_7209")
                if (modeClean7209.size != clean.size) {
                    ForensicLogger.lifecycle(
                        "LANE_PAUSE_EVIDENCE_MODE_SCOPED_7209",
                        "mode=$modeTag7209 kept=${modeClean7209.size} of ${clean.size} " +
                            "droppedOtherMode=${clean.size - modeClean7209.size} " +
                            "action=a_lanes_record_in_one_mode_is_not_evidence_about_the_other",
                    )
                }
            } catch (_: Throwable) {}

            // Aggregate by tradingMode (lane) using the same win threshold
            // (V5.0.4102): pnlPct >= 5% counts as a win. V5.0.4593 — dropped
            // the side="SELL" filter because getRecentCleanStrategyTerminalTrades
            // already returns terminal (sell/partial-sell) rows only, and the
            // extra side check was silently dropping every row when TradeHistory
            // used side="EXIT" or similar in some code paths.
            data class Agg(var sample: Int = 0, var wins: Int = 0, var pnlSum: Double = 0.0)
            val byLane = HashMap<String, Agg>()
            // V5.0.7193 — same rows, restricted to closes AFTER each paused
            // lane's pausedAt. This is the lane's own recovery record.
            val byLanePostPause7193 = HashMap<String, Agg>()
            for (t in modeClean7209) {
                // V5.0.7053 §DO_NOT_DISABLE_A_LANE_FOR_THE_WINNERS_IT_GAVE_AWAY.
                //
                // t.tradingMode is the lane the position was in when it CLOSED.
                // A position that runs is promoted mid-hold (BotService:1615,
                // Executor:10739/10787/12060/15573) BEFORE it closes, so its
                // win is credited to the destination lane. Losers never promote,
                // because promotion is triggered by the gain.
                //
                // This aggregation decides `wins=0 && n>=15` — the auto-pause
                // trigger. Under the operator's new directive a paused lane
                // stays disabled until the lab reproves it, which turns that
                // mis-attribution from a reporting error into a lane being
                // switched off for picks that actually worked. The leak stops
                // being cosmetic the moment the verdict has teeth.
                //
                // Same authority as V5.0.7051 used for LosingPatternMemory:
                // LaneAttributionLedger6427 stamps the entry lane at buy time
                // and no promotion rewrites it.
                val lane = canonLane(
                    com.lifecyclebot.engine.truth.EntryCohortAttribution7051.entryLaneFor(t).trim()
                )
                if (lane.isBlank()) continue
                val promotionEpoch6684 = try { AdaptiveLaneReproof6684.activationEpochMs(lane) } catch (_: Throwable) { 0L }
                if (promotionEpoch6684 > 0L && t.ts < promotionEpoch6684) continue
                val labelEpoch7737 = labelRelease7737[lane]?.atMs ?: 0L
                if (labelEpoch7737 > 0L && t.ts < labelEpoch7737) continue
                val agg = byLane.getOrPut(lane) { Agg() }
                agg.sample += 1
                val outcome6684 = com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.classifyReadonly(t.pnlPct)
                if (outcome6684 == com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.WIN) agg.wins += 1
                agg.pnlSum += t.pnlPct

                // V5.0.7193 §THE_LANE'S_OWN_RECOVERY_EVIDENCE_WAS_COMPUTED_AND_DISCARDED.
                //
                // Only trades CLOSED AFTER the pause count toward recovery. The
                // 2000-row window that proves a lane has recovered also contains
                // the losses that paused it, so an unfiltered aggregate would let
                // the very evidence that switched a lane off switch it back on.
                val pausedState7193 = paused[lane]
                if (pausedState7193 != null && t.ts > pausedState7193.pausedAt) {
                    val postAgg7193 = byLanePostPause7193.getOrPut(lane) { Agg() }
                    postAgg7193.sample += 1
                    if (outcome6684 == com.lifecyclebot.engine.truth.CanonicalOutcomeClassifier6576.Class.WIN) {
                        postAgg7193.wins += 1
                    }
                    postAgg7193.pnlSum += t.pnlPct
                }
            }
            try {
                ErrorLogger.info(
                    "LaneAutoPauseGuard",
                    // V5.0.7209 — print BOTH counts. "clean=2000" next to a
                    // handful of live rows is what made the cross-mode
                    // aggregation invisible in every prior snapshot.
                    "$VERSION evaluateLive: mode=$modeTag7209 modeClean=${modeClean7209.size} " +
                        "allModes=${clean.size} lanes=${byLane.size} paused=${paused.size} snapshot=" +
                        byLane.entries.joinToString(" ") { (l, a) -> "$l(n=${a.sample},w=${a.wins},ev=${"%.0f".format(if (a.sample > 0) a.pnlSum / a.sample else 0.0)}%)" },
                )
            } catch (_: Throwable) {}

            var mutated = false
            for ((lane, agg) in byLane) {
                if (paused.containsKey(lane)) continue
                val wrPct = if (agg.sample > 0) agg.wins.toDouble() / agg.sample.toDouble() * 100.0 else 0.0
                val evPct = if (agg.sample > 0) agg.pnlSum / agg.sample else 0.0
                val zeroWin = agg.sample >= ZERO_WIN_MIN_SAMPLE && agg.wins == 0
                val toxic = agg.sample >= TOXIC_MIN_SAMPLE &&
                    wrPct < TOXIC_WR_PCT &&
                    evPct <= TOXIC_EV_PCT
                if ((zeroWin || toxic) && shadowOutranksThinLiveRecord7734(lane, agg.sample)) continue
                if (zeroWin || toxic) {
                    val reason = if (zeroWin) "zero_win_n${agg.sample}_direct_journal" else "toxic_wr${"%.0f".format(wrPct)}_ev${"%.0f".format(evPct)}_direct_journal"
                    paused[lane] = PauseState(
                        lane = lane,
                        pausedAt = now,
                        reason = reason,
                        sample = agg.sample,
                        wins = agg.wins,
                        wrPct = wrPct,
                        evPct = evPct,
                    )
                    mutated = true
                    try {
                        ErrorLogger.warn(
                            "LaneAutoPauseGuard",
                            "🛑 LANE_AUTO_PAUSED lane=$lane reason=$reason n=${agg.sample} wins=${agg.wins} wr=${"%.1f".format(wrPct)}% ev=${"%.1f".format(evPct)}% — awaiting LLM Lab shadow-proof to resume",
                        )
                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_$lane")
                        PipelineHealthCollector.labelInc("LANE_AUTO_PAUSED_DIRECT_JOURNAL_4592")
                    } catch (_: Throwable) {}
                    try { AdaptiveLaneReproof6684.onLaneFailed(lane, reason) } catch (_: Throwable) {}
                }
            }

            // V5.0.6684 — PROOF-GATED RECOVERY ONLY. A paused lane cannot
            // resurrect from aggregate WR alone; an exact Lab replacement must
            // paper-prove, promote, and open a fresh strategy epoch.
            //
            // V5.0.7105 §THIS_BLOCK_DESCRIBED_A_MECHANISM_THAT_IS_NOT_HERE.
            //
            // What stood here was eleven lines of present-tense prose for a
            // WR-based auto-unpause — "if the RECENT window shows
            // n>=RECOVERY_MIN_SAMPLE and WR>=RECOVERY_WR_PCT and
            // EV>=RECOVERY_EV_PCT, auto-unpause" — followed by one line
            // admitting 6684 had removed it. No code implemented any of it, and
            // RECOVERY_MIN_SAMPLE / RECOVERY_WR_PCT / RECOVERY_EV_PCT appeared
            // nowhere but inside that prose.
            //
            // It is not a stale comment on a cosmetic path. It is the recovery
            // contract for a guard that switches LIVE lanes off, and reading it
            // tells you lanes heal by a mechanism that does not exist. Deleted,
            // with the constants, and replaced by what actually happens:
            //
            //   AdaptiveLaneReproof6684.requestReproof(lane) is called for every
            //   paused lane. It seeds a DETERMINISTIC replacement strategy —
            //   deterministicSeed(), no LLM required — registers it as a reproof
            //   target, and only then additionally tries inventReplacementAsync()
            //   for an LLM-authored candidate. The seed paper-trades in the Lab,
            //   and when it clears MIN_TRADES / MIN_WR / MIN_PNL the reproof
            //   calls manualResume() and the lane returns.
            //
            // That path needs no operator tap and no LLM provider, which is the
            // property that matters for running unattended: a lane that bleeds
            // is switched off, re-proved from evidence, and switched back on by
            // the bot itself.
            //
            // ═════════════════════════════════════════════════════════════
            // V5.0.7193 §A_SECOND_ROAD_HOME, WHICH THE LANE WALKS ITSELF.
            //
            // The Lab path above is real and stays exactly as it is. This adds
            // a second one, because of what the loop above does on its FIRST
            // line: `if (paused.containsKey(lane)) continue`.
            //
            // This function reads the clean-truth journal every 30 seconds and
            // aggregates a fresh per-lane record for EVERY lane, paused ones
            // included. It then skips every paused lane before looking at it.
            // So the evidence of a lane's recovery is computed, held in memory,
            // and discarded one line later — on every tick, forever.
            //
            // Meanwhile the asymmetry that leaves is stark. A lane is switched
            // off by ITS OWN real record (8 straight losses, or n>=12 toxic).
            // It may only be switched back on by a DIFFERENT subsystem's
            // simulated record — a Lab seed reaching 30 sandbox paper trades.
            // And shouldRunBuyLaneForCycle lets a paused lane keep trading in
            // PAPER (PAPER_LANE_QUARANTINE_STILL_SAMPLING_6094), so the lane
            // goes on producing exactly the evidence that would exonerate it,
            // into a journal this function already reads, and nothing was ever
            // allowed to act on it.
            //
            // Operator doctrine is never disable a lane, and the lanes were
            // designed as specialist traders — a hive. A specialist that can be
            // switched off by its own record and can never be switched back on
            // by it is not paused, it is retired.
            //
            // THE BAR IS NOT LOWERED — IT IS THE SAME BAR, BY CONSTRUCTION.
            // These read LlmLabStore's own promotion constants rather than
            // copying their values, so this path and the Lab path cannot drift
            // apart later. A lane resumes only on >=MIN_TRADES_BEFORE_PROMOTION
            // closes that all happened AFTER it was paused, at or above
            // MIN_WR_FOR_PROMOTION_PCT, with positive expectancy.
            //
            // UNITS: the Lab's third condition is paperPnlSol >= 0.05 SOL. This
            // aggregate carries pnlPct, not SOL, so the analogous condition
            // here is positive mean expectancy in percent. Named evPct7193 so
            // the unit is legible and no later reader mistakes it for SOL.
            // ═════════════════════════════════════════════════════════════
            // V5.0.7209 §THE_PAUSE_OUTLIVED_ITS_EVIDENCE_AND_COULD_NOT_BE_UNDONE.
            //
            // Scoping the aggregate above stops NEW cross-mode pauses. It does
            // nothing about the ones already on disk, and those are a one-way
            // latch by construction:
            //
            //   lane paused -> isBlocked() true in live -> lane takes no trades
            //     -> byLanePostPause7193 stays empty -> the 7193 release needs
            //        MIN_TRADES_BEFORE_PROMOTION (30) post-pause closes
            //          -> never arrives -> lane paused forever
            //
            // V5.0.7193 built that recovery path and it cannot run for a lane
            // that is blocked from trading; it only ever worked because paper
            // ignores the pause and paper rows were being counted. Scoping the
            // evidence correctly would have turned my own 7193 fix into a
            // permanent retirement. Both halves have to land together.
            //
            // The rule is the pause predicate read backwards: a pause is a
            // claim about how this lane behaves in THIS mode, so it survives
            // only while this mode's own record still supports it. Same
            // aggregate, same ZERO_WIN_MIN_SAMPLE / TOXIC_* thresholds — the
            // bar is not lowered, it is applied to the evidence that belongs to
            // the mode doing the blocking. A lane with no record in this mode
            // has nothing held against it and is released.
            //
            // This cannot leak permission: the loop above re-pauses on the very
            // next 30s tick the moment this mode's evidence does satisfy
            // zeroWin or toxic. It is a release of an unevidenced claim, not an
            // amnesty.
            for (lane in paused.keys.toList()) {
                val a = byLane[lane]
                val wr7209 = if (a != null && a.sample > 0) a.wins.toDouble() / a.sample.toDouble() * 100.0 else 0.0
                val ev7209 = if (a != null && a.sample > 0) a.pnlSum / a.sample else 0.0
                val stillZeroWin7209 = a != null && a.sample >= ZERO_WIN_MIN_SAMPLE && a.wins == 0
                val stillToxic7209 = a != null && a.sample >= TOXIC_MIN_SAMPLE &&
                    wr7209 < TOXIC_WR_PCT && ev7209 <= TOXIC_EV_PCT
                if (stillZeroWin7209 || stillToxic7209) {
                    // V5.0.7734 — a pause earned on fewer than ten closes yields to a
                    // positive shadow record; it re-arms when the live record reaches ten.
                    if (!shadowOutranksThinLiveRecord7734(lane, a?.sample ?: 0)) continue
                    val st7734 = paused.remove(lane) ?: continue
                    mutated = true
                    try {
                        PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_SHADOW_POSITIVE_7734")
                        PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_SHADOW_POSITIVE_7734_$lane")
                        ForensicLogger.lifecycle(
                            "LANE_PAUSE_RELEASED_SHADOW_POSITIVE_7734",
                            "lane=$lane liveCloses=${a?.sample ?: 0} liveWins=${a?.wins ?: 0} pausedFor=${(now - st7734.pausedAt) / 60_000L}m " +
                                "originalReason=${st7734.reason} action=released_below_learn_before_tighten_floor_on_positive_shadow_record",
                        )
                    } catch (_: Throwable) {}
                    continue
                }
                val state = paused.remove(lane) ?: continue
                mutated = true
                try {
                    ErrorLogger.info(
                        "LaneAutoPauseGuard",
                        "✅ LANE_PAUSE_RELEASED_NO_EVIDENCE_IN_MODE_7209 lane=$lane mode=$modeTag7209 " +
                            "modeN=${a?.sample ?: 0} modeWins=${a?.wins ?: 0} " +
                            "wr=${"%.1f".format(wr7209)}% ev=${"%.1f".format(ev7209)}% " +
                            "pausedFor=${(now - state.pausedAt) / 60_000L}m " +
                            "originalReason=${state.reason} originalSample=${state.sample} " +
                            "action=pause_was_earned_on_other_mode_evidence",
                    )
                    PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_NO_EVIDENCE_IN_MODE_7209")
                    PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_NO_EVIDENCE_IN_MODE_7209_$lane")
                    ForensicLogger.lifecycle(
                        "LANE_PAUSE_RELEASED_NO_EVIDENCE_IN_MODE_7209",
                        "lane=$lane mode=$modeTag7209 modeN=${a?.sample ?: 0} " +
                            "originalReason=${state.reason} originalSample=${state.sample} " +
                            "action=released_claim_this_modes_record_does_not_support",
                    )
                } catch (_: Throwable) {}
            }

            for (lane in paused.keys.toList()) {
                val st7737 = paused[lane] ?: continue
                val s7737 = labelReleaseAllowed7737(lane, st7737) ?: continue
                paused.remove(lane, st7737)
                labelRelease7737[lane] = LabelRelease7737(now, s7737.n60)
                persistLabelRelease7737()
                mutated = true
                try {
                    PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_LABEL_PROOF_7737")
                    PipelineHealthCollector.labelInc("LANE_PAUSE_RELEASED_LABEL_PROOF_7737_$lane")
                    ForensicLogger.lifecycle(
                        "LANE_PAUSE_RELEASED_LABEL_PROOF_7737",
                        "lane=$lane labelsN60=${s7737.n60} net60=${"%+.1f".format(s7737.meanNet60Pct)}% wr60=${"%.0f".format(s7737.winRate60 * 100)}% " +
                            "lost=${s7737.lost} pausedFor=${(now - st7737.pausedAt) / 60_000L}m originalReason=${st7737.reason} " +
                            "action=released_into_a_fresh_live_epoch_on_the_lanes_own_forward_labels",
                    )
                } catch (_: Throwable) {}
            }

            val minTrades7193 = com.lifecyclebot.engine.lab.LlmLabStore.MIN_TRADES_BEFORE_PROMOTION
            val minWrPct7193 = com.lifecyclebot.engine.lab.LlmLabStore.MIN_WR_FOR_PROMOTION_PCT
            selfReproofProgress7193.keys.retainAll(paused.keys)
            for (lane in paused.keys) {
                val a = byLanePostPause7193[lane]
                selfReproofProgress7193[lane] = if (a == null || a.sample <= 0) {
                    "0/$minTrades7193"
                } else {
                    "${a.sample}/$minTrades7193 wr=${"%.0f".format(a.wins * 100.0 / a.sample)}% " +
                        "ev=${"%.0f".format(a.pnlSum / a.sample)}%"
                }
            }
            for ((lane, postAgg) in byLanePostPause7193) {
                val state = paused[lane] ?: continue
                if (postAgg.sample < minTrades7193) continue
                val wrPct7193 = postAgg.wins.toDouble() / postAgg.sample.toDouble() * 100.0
                val evPct7193 = postAgg.pnlSum / postAgg.sample
                if (wrPct7193 < minWrPct7193 || evPct7193 <= 0.0) continue
                paused.remove(lane)
                mutated = true
                try {
                    ErrorLogger.info(
                        "LaneAutoPauseGuard",
                        "✅ LANE_SELF_REPROVED_7193 lane=$lane " +
                            "postPauseN=${postAgg.sample} wins=${postAgg.wins} " +
                            "wr=${"%.1f".format(wrPct7193)}% ev=${"%.1f".format(evPct7193)}% " +
                            "pausedFor=${(now - state.pausedAt) / 60_000L}m " +
                            "originalReason=${state.reason} bar=n$minTrades7193/wr${minWrPct7193}/ev>0",
                    )
                    PipelineHealthCollector.labelInc("LANE_SELF_REPROVED_7193_$lane")
                    PipelineHealthCollector.labelInc("LANE_SELF_REPROVED_7193")
                    ForensicLogger.lifecycle(
                        "LANE_SELF_REPROVED_7193",
                        "lane=$lane postPauseN=${postAgg.sample} wr=${"%.1f".format(wrPct7193)} " +
                            "ev=${"%.1f".format(evPct7193)} originalReason=${state.reason} " +
                            "action=resumed_on_own_post_pause_record",
                    )
                } catch (_: Throwable) {}
            }

            if (mutated) persistAsync()
        } catch (_: Throwable) {}
    }

    /** Manual resume — for LLM Lab success or operator override. */
    fun manualResume(lane: String, note: String = "manual") {
        ensureLoaded()
        val removed = paused.remove(canonLane(lane))
        if (removed != null) {
            try {
                ErrorLogger.info(
                    "LaneAutoPauseGuard",
                    "✅ LANE_MANUAL_RESUMED lane=${removed.lane} originalReason=${removed.reason} pausedFor=${(System.currentTimeMillis() - removed.pausedAt) / 60_000L}m note=$note",
                )
                PipelineHealthCollector.labelInc("LANE_MANUAL_RESUMED_${removed.lane}")
            } catch (_: Throwable) {}
            persistAsync()
        }
    }

    /** Diagnostic status line for reports. */
    fun statusLine(): String {
        ensureLoaded()
        if (paused.isEmpty()) return "$VERSION: no lanes paused"
        val s = paused.values.joinToString(" | ") { p ->
            // V5.0.7193 — show how far each paused lane has walked back toward
            // the bar on its own record. Without this the operator sees only
            // "paused, reason=zero_win_n8" for hours and cannot tell a lane
            // that is 28/30 of the way home from one that has closed nothing
            // since it was switched off. Those look identical and are not.
            val progress7193 = selfReproofProgress7193[p.lane].orEmpty()
            "${p.lane}(n=${p.sample} wr=${"%.0f".format(p.wrPct)}% ev=${"%.0f".format(p.evPct)}% " +
                "reason=${p.reason}${if (progress7193.isBlank()) "" else " reproof7193=$progress7193"})"
        }
        return "$VERSION: ${paused.size} paused → $s"
    }

    fun pausedLanes(): Set<String> {
        ensureLoaded()
        return paused.keys.toSet()
    }
}
