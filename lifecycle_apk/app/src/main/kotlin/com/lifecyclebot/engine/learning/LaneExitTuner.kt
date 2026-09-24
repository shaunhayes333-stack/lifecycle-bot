package com.lifecyclebot.engine.learning

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.1379 — LANE EXIT TUNER (closed-loop exit-ladder auto-tuning).
 *
 * Closes the gap where V3 meme lanes (MOONSHOT/SHITCOIN/TREASURY/BLUECHIP/
 * MANIPULATED/SNIPER/QUALITY) read STATIC take-profit/stop-loss literals that
 * FluidLearningAI only lerps along a global bootstrap->mature curve (and leaves
 * high-upside modes unchanged). Nothing moved a lane's TP/SL based on how that
 * lane ACTUALLY performed. This makes the exit ladder a real feedback loop.
 *
 * Emits two BOUNDED multipliers per lane from realized outcomes:
 *   tpMult in [0.60,1.40] applied to the lane base take-profit
 *   slMult in [0.70,1.30] applied to the MAGNITUDE of the lane base stop
 *
 * DOCTRINE GUARANTEES:
 *  - The -15% unconditional hard floor is NEVER touched here. slMult only scales
 *    a lane's own (tighter-than-floor) stop; the caller still clamps to -15%.
 *  - Soft-shape only: never vetoes/zeroes a trade. Fail-open: errors return 1.0.
 *  - Bootstrap-safe: requires MIN_SAMPLE matured closes per lane before nudging
 *    (doctrine #5 - read the curve, not the noise).
 *  - Persisted via LearningPersistence (sacred-persistence rule).
 */
object LaneExitTuner {

    private const val TP_MIN = 0.60
    private const val TP_MAX = 1.40
    private const val SL_MIN = 0.70
    private const val SL_MAX = 1.30
    private const val STEP   = 0.04

    // V5.0.6044 — LOWERED FROM 20 TO 8 (operator throughput doctrine).
    // Report 2026-07-03 showed BLUECHIP n=9, MOONSHOT n=17, SHITCOIN n=7 all
    // stuck at neutral tpMult=1.00/slMult=1.00 because they hadn't crossed
    // the old n>=20 threshold. Bot was accumulating losses at these lanes'
    // hardcoded defaults with no closed-loop tuning kicking in. Lower the
    // sample floor so tuning engages earlier — still bootstrap-safe (needs
    // n>=8 real closes, not noise), but stops the bleed while lanes learn.
    private const val WINDOW       = 60
    private const val MIN_SAMPLE   = 8

    /**
     * V5.0.7186 §SIXTEEN_SPECIALISTS_ALL_STUCK_AT_EXACTLY_1.00.
     *
     * Every lane reported `tpMult=1.00 slMult=1.00 (bootstrap n=N - neutral)`
     * on the operator's 5.0.7176 run. Not "learned neutral" — never ran. With
     * 36 closes spread across 16 specialist lanes, no lane reaches
     * MIN_SAMPLE=8, so `recompute` returned at its first line and the whole
     * closed-loop TP/SL organ has produced nothing since it was written.
     *
     * A hard sample cliff is the wrong instrument here. The lanes are a hive:
     * the question is not "does THIS lane have 8 closes" but "how much should
     * this lane's own evidence move it away from neutral". That is a shrinkage
     * question, and the codebase already answers it elsewhere —
     * PredictiveEntryOracle6915 uses `weight = n / (n + SHRINK_K)` with
     * SHRINK_K = 6.0 and blends cell -> lane -> global. Same K reused so the
     * two organs shrink at the same rate.
     *
     * So the cliff becomes a ramp: recompute runs from MIN_SAMPLE_POOLED_7186
     * closes, and the resulting adjustment is scaled by the lane's evidence
     * weight. n=3 moves a third of the way, n=8 moves 57%, n=20 moves 77%,
     * n=60 (the window cap) moves 91%. A lane with thin evidence nudges;
     * a lane with real evidence commits.
     *
     * DELIBERATELY SAFE. This organ is soft-shape only — it multiplies TP/SL
     * and can never veto, deny or expand position size (see the header). Every
     * decision branch, every threshold and both clamps are untouched; only the
     * MAGNITUDE of the step is scaled. The audit that produced this change
     * specifically warned that dropping sample cliffs across the board would
     * mostly unlock UPSIDE multipliers, because four organs read the same ~36
     * closes and compound into size — so pooling is applied HERE and not to
     * LaneExpectancyDamper's boost paths, UnifiedExitPolicyHead's stop veto,
     * LearnedAdmissionAuthority6846's dump denial, or the lab promotion bars.
     *
     * MIN_SAMPLE itself is left at 8 and still governs `closedLoopMature`
     * below, so the closed-loop-vs-replay-bias authority question is unchanged.
     */
    private const val MIN_SAMPLE_POOLED_7186 = 3
    private const val SHRINK_K_7186 = 6.0

    /** Shrinkage weight for a lane holding [n] matured closes. */
    private fun evidenceWeight7186(n: Int): Double =
        if (n <= 0) 0.0 else (n.toDouble() / (n.toDouble() + SHRINK_K_7186)).coerceIn(0.0, 1.0)
    private const val RECALC_EVERY = 5

    // V5.0.7164 — outcome-window schema stamp. A persisted window is only
    // evidence under the contract it was collected with. Everything written
    // before 7161 was collected WITHOUT the non-strategy-exit filter, so it
    // contains stale-feed and timeout-scratch rows this tuner now refuses to
    // learn from. Restoring it verbatim would re-derive the same floors from
    // the same contaminated sample and the 7164 recovery path would never get
    // a clean read. Bump this whenever the admission contract changes.
    // V5.0.7167 — bumped again. 7164 retired windows collected before the
    // non-strategy-exit filter; 7167 retires windows collected while
    // unrecognised lanes were folded into STANDARD, for the same reason: the
    // rows in them are not attributable to the lane that holds them.
    // V5.0.7169 — bumped once more. Every window written before 7169 was fed
    // by FinalizedBusConsumerBridge6465's redelivery loop, which replayed a
    // refused close's attribution on every retry: ~5,289 recorded closes from
    // 344 real ones, weighted toward the refused rows. A window built from
    // that is not a sample of this lane's exits.
    private const val STATE_SCHEMA_7164 = 7169

    private data class Outcome(
        val pnlPct: Double,
        val peakPct: Double,
        val win: Boolean,
        val stopHit: Boolean,
    )

    private class LaneState {
        val window = ArrayDeque<Outcome>()
        var sinceRecalc = 0
        var lifetimeCloses = 0L
        // V5.0.7277 — the lane this state belongs to, so recompute can ask
        // RunnerExitProfile7277 for the lane's take-profit floor.
        @Volatile var lane: String = ""
        @Volatile var tpMult = 1.0
        @Volatile var slMult = 1.0
    }

    data class ReplayBias(
        val profile: String,
        val tpMult: Double,
        val slMult: Double,
        val netSol: Double,
        val n: Int,
    )

    private val lanes = ConcurrentHashMap<String, LaneState>()
    @Volatile private var replayBiasByLane: Map<String, ReplayBias> = emptyMap()
    @Volatile private var replayBiasAtMs: Long = 0L
    private val replayBiasInFlight = AtomicBoolean(false)

    private fun canon(lane: String): String {
        val u = lane.uppercase()
        return when {
            u.contains("MOONSHOT")                          -> "MOONSHOT"
            u.contains("MANIPUL")                           -> "MANIPULATED"
            u.contains("EXPRESS")                            -> "EXPRESS"
            u.contains("SHITCOIN")                           -> "SHITCOIN"
            u.contains("CYCLIC")                             -> "CYCLIC"
            u.contains("TREASURY") || u.contains("CASH")    -> "TREASURY"
            u.contains("PRESALE") || u.contains("SNIPER")   -> "PRESALE_SNIPE"
            u.contains("QUALITY")                            -> "QUALITY"
            u.contains("BLUE")                               -> "BLUECHIP"
            u.contains("DIP")                                -> "DIP_HUNTER"
            // V5.0.7167 §STANDARD WAS A JUNK DRAWER WEARING A LANE'S NAME.
            //
            // Operator's 5.0.7166:
            //
            //   STANDARD  tpMult=0.80  slMult=0.70  lifetime=1376
            //
            // 1,376 closes, and the bot barely trades a lane called STANDARD —
            // the funnel reports it as shadow/read-only, 404 evaluations and
            // zero executions. So almost none of those closes are STANDARD's.
            // They are every lane name this `when` failed to match, swept into
            // the default arm: CORE, CRYPTO_LEV, CRYPTO_SPOT, UNRESOLVED_OWNER
            // _6741, WALLET_RECOVERED. That last one closes at μ=-91.1% on
            // 0 wins from 9, because it is inventory the bot never bought and
            // has no real basis for.
            //
            // Then CORE asks getTpMult("CORE"), lands in the same bucket, and
            // is told to bank sooner and stop tighter because a wallet-recovery
            // write-off was averaged into its window. Unmatched names now keep
            // their own identity instead of inheriting a stranger's shape; a
            // lane with too few closes of its own reads neutral, which is the
            // honest answer.
            else -> u.filter { it.isLetterOrDigit() || it == '_' }.take(24).ifBlank { "STANDARD" }
        }
    }

    /**
     * V5.0.7167 — recovered inventory is not an exit decision.
     *
     * StrategyTruthLedger.isRecoveryInventory:246 removes these from strategy
     * truth — 12,100 exclusions on the operator's 5.0.7166 — because a
     * position the bot never opened has no entry, no basis and therefore no
     * strategy to judge. Its "-91%" is an accounting write-off, not a stop
     * that fired too late.
     *
     * This tuner had no such test, so those write-offs voted on take-profit
     * alongside real exits. Same vocabulary as the ledger's, read off the
     * fields the envelope actually carries here.
     */
    private val RECOVERY_INVENTORY_MARKERS_7167 = listOf(
        "WALLET_RECOVERED", "OPEN_RESTORED", "ADOPTED_FROM_WALLET",
        "RECOVERED_", "RESTORED_", "INVENTORY_RECON",
    )

    private fun refreshReplayBiasAsync(reason: String = "close") {
        val now = System.currentTimeMillis()
        if (now - replayBiasAtMs < 60_000L && replayBiasByLane.isNotEmpty()) return
        if (!replayBiasInFlight.compareAndSet(false, true)) return
        kotlinx.coroutines.GlobalScope.launch(com.lifecyclebot.util.AppDispatchers.sideEffect) {
            try {
                val best = com.lifecyclebot.engine.LaneStrategyEvaluator.bestPerLane()
                replayBiasByLane = best.mapNotNull { (lane, r) ->
                    val b = when (r.profile) {
                        "TIGHT_STOP_-5" -> ReplayBias(r.profile, tpMult = 0.92, slMult = 0.70, netSol = r.netSol, n = r.n)
                        "FLOOR_-15_LETRUN" -> ReplayBias(r.profile, tpMult = 1.10, slMult = 1.00, netSol = r.netSol, n = r.n)
                        "FLOOR_-15_TRAIL25" -> ReplayBias(r.profile, tpMult = 1.16, slMult = 1.08, netSol = r.netSol, n = r.n)
                        "EARLY_TP_+30" -> ReplayBias(r.profile, tpMult = 0.78, slMult = 0.85, netSol = r.netSol, n = r.n)
                        // V5.0.7203 §A_VERDICT_ABOUT_ENTERING_WAS_BEING_SPENT_ON_EXITING.
                        //
                        // NO_TRADE is not an exit profile. LaneStrategyEvaluator
                        // declares it as
                        //     ExitProfile("NO_TRADE", stopPct = null,
                        //                 trailFromPeakPct = null,
                        //                 fullTpPct = null, noTrade = true)
                        // and prints it as "STOP TRADING (no profile beats
                        // sitting out)". stopPct is null because the profile
                        // explicitly declines to recommend a stop. Mapping it to
                        // slMult = 0.70 invented a stop opinion out of a verdict
                        // that refused to give one — and applied it to positions
                        // already open, which is a statement about entry being
                        // spent on exit.
                        //
                        // MEASURED, 5.0.7202: BLUECHIP n=16 W/L=0/16 WR=0.0%
                        // PnL=-1.0961 SOL, and terminal-by-lane avgPct = -5.0%
                        // EXACTLY. Not approximately — every single BLUECHIP
                        // position exits at the same number, because a 30%
                        // tightened stop sits inside the asset's ordinary
                        // volatility and harvests it. Largest single loss
                        // contributor on a book that just went to -0.2005 SOL.
                        //
                        // The file already knew. UnifiedExitPolicyHead:227:
                        //   "the paper-hands pattern seen across BLUECHIP/
                        //    QUALITY/STANDARD where the STRICT_SL rule was
                        //    cutting real assets at -5% during normal
                        //    volatility while MOONSHOT held through and printed"
                        //
                        // And it is self-reinforcing: tighter stop -> more small
                        // losses -> replay concludes NO_TRADE harder -> stop
                        // tightens again. A lane cannot trade its way out of it.
                        //
                        // Returning null drops the lane from replayBiasByLane so
                        // getTpMult/getSlMult fall through to `?: 1.0` — the
                        // lane's DESIGNED stop, not a widened one. This removes
                        // an invented tightening; it does not loosen anything
                        // past default, and every catastrophic backstop
                        // (TICK_HARD_FLOOR, CATASTROPHIC_HARD_BACKSTOP_25,
                        // PROTECTIVE_EXIT_*) is untouched.
                        //
                        // NARROW BY CONSTRUCTION: getSlMult only consults the
                        // replay bias when the closed-loop learner is NOT mature
                        // (n < MIN_SAMPLE). QUALITY (lifetime=15) and
                        // PRESALE_SNIPE (lifetime=17) are mature, so replay is
                        // already ignored for them and they are unaffected. On
                        // the 7202 snapshot BLUECHIP is the only NO_TRADE lane
                        // with no closed-loop entry — the one lane that is
                        // 16-for-16 losing at exactly the tightened stop.
                        //
                        // Entry-side suppression of a NO_TRADE lane is unchanged
                        // and stays where it belongs: LosingPatternMemory's size
                        // ladder, LaneExpectancyDamper (BLUECHIP x0.46) and the
                        // brain consensus gate.
                        "NO_TRADE" -> null
                        else -> null
                    }
                    if (b != null) canon(lane) to b else null
                }.toMap()
                replayBiasAtMs = System.currentTimeMillis()
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "LANE_STRATEGY_REPLAY_BIAS_REFRESH_6093",
                        "reason=$reason lanes=${replayBiasByLane.entries.joinToString(";") { "${it.key}:${it.value.profile}:n=${it.value.n}:net=${"%.3f".format(it.value.netSol)}" }.take(400)}",
                    )
                } catch (_: Throwable) {}
            } catch (_: Throwable) {
            } finally {
                replayBiasInFlight.set(false)
            }
        }
    }


    private val STOP_REASONS = listOf(
        "STOP_LOSS", "HARD_FLOOR", "DISTRIBUTION_STOP", "V8_DISTRIBUTION",
        "RAPID_ENTRY_PROTECT_STOP", "SWEEP_FLUID_FLOOR", "PROTECT_STOP"
    )

    /**
     * V5.0.7161 §THE TUNER WAS LEARNING FROM EXITS THE LEDGER THROWS AWAY.
     *
     * StrategyTruthLedger.forensicRejectReason:278 refuses any close whose
     * reason contains STALE_FEED or DATA_QUALITY — 3,912 exclusions on the
     * operator's 5.0.7155 session — because a position evicted for a stale
     * price is a verdict on the PRICE FEED, not on the exit ladder.
     *
     * This tuner had no such filter. It takes every close from two separate
     * feeds (FinalizedBusConsumerBridge6465:323 and V3JournalRecorder:322),
     * including the scratch and timeout exits, and lets them vote on tpMult.
     *
     * That explains the anomaly 7158 instrumented and could not yet name.
     * The only branch able to lower tpMult needs avgReal <= -5.0, and
     * CYCLIC's strategy table reads EV=+48.92%/trade — because the TABLE
     * excludes these rows and the TUNER counts them. Stale-feed and
     * timeout-scratch exits are small, almost always negative, and
     * numerous; a window full of them drags avgReal under the trigger while
     * the lane's real trades are profitable. Four lanes — CYCLIC, STANDARD,
     * EXPRESS, PRESALE_SNIPE — reached BOTH floors that way.
     *
     * Tightening take-profit because the price feed went stale is not
     * learning. The tuner now honours the same contract the truth ledger
     * does, extended to the family the ledger's substring test misses by a
     * word: PAPER_STALE_PRICE_TIMEOUT_SCRATCH and DEAD_TOKEN_NO_PRICE_EXIT
     * are "we could not price it", not "the strategy exited".
     */
    private val NON_STRATEGY_EXIT_REASONS_7161 = listOf(
        "STALE_FEED", "DATA_QUALITY", "STALE_PRICE", "TIMEOUT_SCRATCH",
        "NO_PRICE", "DEAD_TOKEN",
    )

    fun recordClose(lane: String, pnlPct: Double, peakPct: Double, exitReason: String) {
        try {
            val reasonUpper7161 = exitReason.uppercase()
            val recoveryHay7167 = (lane + "|" + exitReason).uppercase()
            if (RECOVERY_INVENTORY_MARKERS_7167.any { recoveryHay7167.contains(it) }) {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_EXIT_TUNER_SKIPPED_RECOVERY_INVENTORY_7167")
                } catch (_: Throwable) {}
                return
            }
            if (NON_STRATEGY_EXIT_REASONS_7161.any { reasonUpper7161.contains(it) }) {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_EXIT_TUNER_SKIPPED_NON_STRATEGY_EXIT_7161")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                        "LANE_EXIT_TUNER_SKIPPED_NON_STRATEGY_EXIT_7161_${canon(lane).take(20)}",
                    )
                } catch (_: Throwable) {}
                return
            }
            val key = canon(lane)
            val st = lanes.getOrPut(key) { LaneState() }
            if (st.lane.isBlank()) st.lane = key
            val stopHit = STOP_REASONS.any { exitReason.uppercase().contains(it) }
            val peakSane = when {
                peakPct.isNaN() || peakPct.isInfinite() -> 0.0
                peakPct < 0.0 -> 0.0
                peakPct > 5000.0 -> 5000.0
                else -> peakPct
            }
            val o = Outcome(
                pnlPct = if (pnlPct.isNaN() || pnlPct.isInfinite()) 0.0 else pnlPct,
                peakPct = peakSane,
                win = pnlPct > 0.0,
                stopHit = stopHit,
            )
            synchronized(st) {
                st.window.addLast(o)
                while (st.window.size > WINDOW) st.window.removeFirst()
                st.lifetimeCloses++
                st.sinceRecalc++
                if (st.sinceRecalc >= RECALC_EVERY && st.window.size >= MIN_SAMPLE_POOLED_7186) {
                    st.sinceRecalc = 0
                    recompute(st)
                }
                refreshReplayBiasAsync("recordClose")
            }
        } catch (_: Throwable) { }
    }

    private fun recompute(st: LaneState) {
        val w = st.window.toList()
        val n = w.size
        if (n < MIN_SAMPLE_POOLED_7186) return
        // V5.0.7186 — how far this lane's own evidence may move it off neutral.
        val evidence7186 = evidenceWeight7186(n)
        val priorTp7186 = st.tpMult
        val priorSl7186 = st.slMult
        val wins = w.count { it.win }
        val wr = wins.toDouble() / n
        val avgPeak = w.map { it.peakPct }.average()
        val avgReal = w.map { it.pnlPct }.average()
        val giveBack = avgPeak - avgReal
        val slHitRate = w.count { it.stopHit }.toDouble() / n
        val losers = w.filter { !it.win }
        val avgLoss = if (losers.isEmpty()) 0.0 else losers.map { it.pnlPct }.average()

        // V5.9.1562 — RUNNER-PRESERVATION first principle (operator forensic 5.0.3659).
        // Bug in the old decision table: branch 2 fired on WR<0.30 + avgPeak≥20 + avgReal≤5,
        // which is the textbook signature of runner-cutting (the lane shows fat peaks but
        // realizes nothing). It then DECREASED tpMult → exits even SOONER, making the
        // bleed worse. MOONSHOT dump: WR 18.7%, avgPeak +630%, avgReal -1.4%, tpMult 0.84.
        // The learner was actively choking the lane that needed to be widened.
        //
        // New ordering: when giveBack is large in absolute terms (≥40pp) AND peaks are
        // real (avgPeak ≥ 30%), the ONLY correct adjustment is to widen TP (let winners
        // breathe) — regardless of WR. WR low + peaks fat = give the bot space to LET
        // them run, not pull the rip-cord earlier.
        var tp = st.tpMult
        when {
            // Strong runner evidence — widen TP no matter what WR looks like.
            giveBack >= 40.0 && avgPeak >= 30.0 -> tp += STEP
            // Healthy lane with moderate give-back — widen further.
            wr >= 0.45 && avgPeak >= 25.0 && giveBack >= 15.0 -> tp += STEP
            // Low-WR with small/no peaks — entry signal weak, exit shouldn't be widened
            // beyond neutral. Only bank-sooner when peaks themselves are tiny.
            // V5.0.7158 §NEVER BANK SOONER ON A LANE THAT IS MAKING MONEY.
            //
            // Operator's 5.0.7155 strategy table against this tuner's output:
            //
            //   CYCLIC  n=11 WR=27.3% EV=+48.92%/trade PnL=+0.9348 SOL
            //   tuner:  CYCLIC tpMult 1.00 -> 0.60   (TP_MIN)
            //
            // CYCLIC is the single most profitable lane in the book and the
            // tuner moved it to bank as early as it is allowed to. Four lanes
            // — CYCLIC, STANDARD, EXPRESS, PRESALE_SNIPE — now sit at exactly
            // (0.60, 0.70), BOTH floors at once, on very different records.
            // Independent learners converging on identical extremes is not
            // learning.
            //
            // This file already fought this once. V5.9.1562, forty lines up:
            // "the learner was actively choking the lane that needed to be
            // widened", after MOONSHOT was cut to 0.84 on WR 18.7% with
            // avgPeak +630%. The ordering was fixed so runner evidence wins.
            // It was not made safe against the low-WR arm firing on a lane
            // that is profitable anyway — which is the whole shape of a
            // runner-capture book: 27% WR and positive expectancy.
            //
            // A lane whose realised mean is positive has no bank-sooner case
            // by construction; cutting its TP can only reduce what it
            // realises. The `avgReal <= -5.0` term should already prevent
            // this, so if the guard below ever fires it means the tuner's
            // outcome window and the strategy table DISAGREE about the same
            // lane — and the counter says so out loud rather than silently
            // clipping the best performer.
            wr < 0.30 && avgPeak < 15.0 && avgReal <= -5.0 && avgReal < 0.0 -> tp -= STEP
            // V5.0.7164 §THE WAY BACK UP WAS GATED ON THE ONE NUMBER A
            // RUNNER LANE NEVER PRODUCES.
            //
            // 7161 stopped this tuner learning from stale-feed exits. It
            // could not undo what those exits had already done. The
            // operator's 5.0.7161 device still reads:
            //
            //   CYCLIC   tpMult=0.60  lifetime=614
            //   strategy CYCLIC  EV=+39.84%/trade  PnL=+0.9176 SOL
            //
            // Count what can raise tp above. Three branches: one needs
            // giveBack>=40 AND avgPeak>=30; the other two need wr>=0.45 and
            // wr>=0.50. CYCLIC wins 23% of its trades. A fat-tail lane does
            // not reach a 45% win rate — not reaching it is what makes it a
            // fat-tail lane — so for exactly the lanes carrying the book,
            // tpMult is a one-way downward ratchet. Once a contaminated
            // window pushed them to TP_MIN there was no path home, and four
            // lanes sat there.
            //
            // Apply the substitution 7159 already made for the WR sift: win
            // rate is a PROXY for profitability, realised expectancy is the
            // MEASUREMENT. A lane whose realised mean is positive has earned
            // neutral take-profit whatever its win rate — nothing below
            // neutral can be justified by a profit. This only climbs back TO
            // 1.0; widening beyond neutral still requires the runner
            // evidence at the top of this table.
            avgReal > 0.0 && tp < 1.0 -> tp += if (avgReal >= 10.0) STEP * 2.0 else STEP
            // Already tight lane that's banking too aggressively — nudge up.
            wr >= 0.50 && giveBack < 8.0 && tp < 1.0 -> tp += STEP * 0.5
        }
        // V5.0.7186 — shrink the step toward the prior by evidence weight. The
        // branch that fired and its threshold are unchanged; only how far the
        // lane travels on this recalc is scaled by how much it actually knows.
        val shrunkTp7186 = priorTp7186 + (tp - priorTp7186) * evidence7186
        // V5.0.7277 — a runner lane's take-profit may not be pulled below
        // neutral by its win rate; see RunnerExitProfile7277.
        val tpFloor7277 = com.lifecyclebot.engine.RunnerExitProfile7277.tpMultFloor(st.lane, TP_MIN)
        if (tpFloor7277 > TP_MIN && shrunkTp7186 < tpFloor7277) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_TP_MULT_HELD_AT_NEUTRAL_7277") } catch (_: Throwable) {}
        }
        st.tpMult = shrunkTp7186.coerceIn(tpFloor7277, TP_MAX)
        // V5.0.7158 — say what this recompute saw and what it did. Four lanes
        // arrived at both floors with no record of how, because nothing here
        // has ever logged its inputs. A closed loop that cannot be audited is
        // indistinguishable from a broken one, and on the operator's device
        // it clipped the best lane in the book.
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LANE_EXIT_TUNER_RECOMPUTE_7158",
                "n=$n wr=${"%.2f".format(wr)} avgPeak=${"%.1f".format(avgPeak)} " +
                    "avgReal=${"%.1f".format(avgReal)} giveBack=${"%.1f".format(giveBack)} " +
                    "slHitRate=${"%.2f".format(slHitRate)} avgLoss=${"%.1f".format(avgLoss)} " +
                    "tpOut=${"%.2f".format(st.tpMult)}",
            )
            if (st.tpMult <= TP_MIN + 1e-9) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_EXIT_TUNER_TP_AT_FLOOR_7158")
            }
            if (avgReal > 0.0 && st.tpMult < 1.0) {
                // A profitable window that is nonetheless banking sooner than
                // neutral. Should not happen; if it does, the feed is wrong.
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_EXIT_TUNER_TIGHT_ON_PROFITABLE_7158")
            }
        } catch (_: Throwable) {}

        var sl = st.slMult
        // V5.0.3921 — RUNNER-PRESERVATION SL FLOOR. Operator dump V5.0.3922
        // exit-reason P&L showed MOONSHOT n=200 STOP_LOSS μ=-24.4% vs n=25
        // TAKE_PROFIT μ=+1284.5%, SHITCOIN n=200 STOP_LOSS μ=-25.7% vs n=25
        // TAKE_PROFIT μ=+1796.6%. The TPs are ~50× the |SLs|, so even with
        // 8:1 stop:profit ratio the EV is hugely positive — but the lane
        // tuner had tightened MOONSHOT slMult to 0.92 (TIGHTER than neutral),
        // cutting more would-be runners. When winners are ≥10× the size of
        // losers, the stop should NEVER be tightened below 1.0× — let
        // runners breathe. Compute runner-strength on the win-only subset
        // because peakPct can be diluted by losers' near-zero peaks.
        val winners = w.filter { it.win }
        val avgWinPct = if (winners.isEmpty()) 0.0 else winners.map { it.pnlPct }.average()
        val runnerLane = avgWinPct >= 10.0 * kotlin.math.abs(avgLoss) && winners.size >= 3
        when {
            // V5.0.3765 — low-WR/no-runner bleed fix. The old rule widened stops
            // whenever stop-hit rate was high and avgPeak was low. In a sub-20% WR
            // negative-PF regime that is exactly backwards: there are no runners to
            // preserve, so widening only increases realized loss. Tighten the lane
            // until it proves it can produce peaks/wins again. Runner lanes are still
            // protected above by the TP/giveBack logic and by the avgPeak guard here.
            wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0 -> sl -= STEP * 2.0
            // V5.0.3973 — STOP-LOSS LEAK CLAMP. If a lane's stop rows are
            // repeatedly deep red, do not widen its stop just because some peak
            // evidence exists elsewhere. The 3971 report showed STOP_LOSS rows
            // around -25% to -34% across several lanes; widening those stops
            // directly leaks live wallet. Keep runner preservation via TP/trails,
            // but cap SL at neutral until stop leakage improves.
            slHitRate >= 0.50 && avgLoss <= -20.0 -> sl -= STEP
            slHitRate >= 0.50 && avgPeak < 8.0 && wr >= 0.30 -> sl += STEP
            slHitRate < 0.25 && avgLoss <= -10.0 -> sl -= STEP
        }
        val stopLeakClamp = slHitRate >= 0.35 && avgLoss <= -20.0
        val slCap = if ((wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0) || stopLeakClamp) 1.0 else SL_MAX
        // RUNNER-LANE FLOOR: never let the stop tighten below 1.0× when wins
        // dwarf losses by ≥10×. Widens further if existing tuner logic
        // already raised it; never pulls it back below neutral.
        // V5.0.7164 — the same ratchet on the stop side. `slHitRate < 0.25 &&
        // avgLoss <= -10.0` tightens a lane that is barely hitting its stop,
        // and nothing in this table ever loosens it again except the runner
        // floor, which needs avgWin >= 10x|avgLoss|. The four floored lanes
        // read slMult=0.70 alongside tpMult=0.60. A lane with positive
        // realised expectancy gets the same neutral floor the runner lanes
        // get — stop leakage still overrides it, so a lane that is genuinely
        // bleeding through its stop is unaffected.
        val profitableFloor7164 = avgReal > 0.0 && !stopLeakClamp
        val slFloor = if ((runnerLane || profitableFloor7164) && !stopLeakClamp) maxOf(SL_MIN, 1.0) else SL_MIN
        // V5.0.7186 — same shrinkage on the stop side. slFloor/slCap, which
        // carry the 7164 profitable-lane protection, are applied after the
        // blend exactly as before, so no clamp is weakened.
        val shrunkSl7186 = priorSl7186 + (sl - priorSl7186) * evidence7186
        st.slMult = shrunkSl7186.coerceIn(slFloor, slCap)
    }

    /**
     * V5.0.6747 §EXIT_TUNER_RESOLVED_AUTHORITY — operator directive:
     *   > "This should ultimately become one resolved TP/SL policy
     *   >  per position, rather than two independent multipliers
     *   >  influencing the same trade."
     *
     * The closed-loop learner (LaneExitTuner) and the strategy
     * replay bias (LaneStrategyReplay) used to multiply together, so
     * for EXPRESS the two authorities were pushing tpMult 0.72 ×
     * 1.10 in opposite directions and partially cancelling. Now:
     *   • If closed-loop is MATURE (n ≥ MIN_SAMPLE): closed-loop is
     *     authoritative; replay bias is IGNORED.
     *   • Else (bootstrap): replay bias is authoritative (closed-loop
     *     result would be neutral 1.0 anyway during bootstrap).
     */
    fun getTpMult(lane: String): Double = try {
        refreshReplayBiasAsync("getTpMult")
        val key = canon(lane)
        val laneSt = lanes[key]
        val closedLoopMature = laneSt != null && laneSt.window.size >= MIN_SAMPLE
        if (closedLoopMature) {
            laneSt!!.tpMult
        } else {
            replayBiasByLane[key]?.tpMult ?: 1.0
        }
    } catch (_: Throwable) { 1.0 }

    fun getSlMult(lane: String): Double = try {
        refreshReplayBiasAsync("getSlMult")
        val key = canon(lane)
        val laneSt = lanes[key]
        val closedLoopMature = laneSt != null && laneSt.window.size >= MIN_SAMPLE
        if (closedLoopMature) {
            laneSt!!.slMult
        } else {
            replayBiasByLane[key]?.slMult ?: 1.0
        }
    } catch (_: Throwable) { 1.0 }

    fun formatForPipelineDump(): String {
      return try {
        if (lanes.isEmpty()) return ""
        buildString {
            append("\n===== Lane Exit Tuner (V5.9.1379 - closed-loop TP/SL) =====\n")
            lanes.entries.sortedBy { it.key }.forEach { (lane, st) ->
                val n = st.window.size
                val matured = n >= MIN_SAMPLE
                val tag = if (matured) "" else "  (bootstrap n=$n - neutral)"
                append(String.format(
                    "  %-12s tpMult=%.2f  slMult=%.2f  lifetime=%d%s\n",
                    lane, st.tpMult, st.slMult, st.lifetimeCloses, tag))
            }
            append("  Read: tpMult>1 => lane lets winners run further; <1 => banks sooner.\n")
            append("        slMult>1 => wider stop (still clamped to -15%); <1 => tighter.\n")
            if (replayBiasByLane.isNotEmpty()) {
                append("  LaneStrategyReplay bias 6093: ")
                append(replayBiasByLane.entries.sortedBy { it.key }.joinToString(" · ") { (lane, b) ->
                    "${lane}:${b.profile} tp×=${"%.2f".format(b.tpMult)} sl×=${"%.2f".format(b.slMult)} n=${b.n}"
                })
                append('\n')
            }
        }
      } catch (_: Throwable) { "" }
    }

    fun exportState(): String = try {
        val root = JSONObject()
        lanes.forEach { (lane, st) ->
            synchronized(st) {
                val o = JSONObject()
                o.put("tp", st.tpMult)
                o.put("sl", st.slMult)
                o.put("life", st.lifetimeCloses)
                val arr = org.json.JSONArray()
                st.window.forEach { oc ->
                    arr.put(JSONObject().apply {
                        put("p", oc.pnlPct); put("k", oc.peakPct)
                        put("w", oc.win); put("s", oc.stopHit)
                    })
                }
                o.put("win", arr)
                o.put("sr", st.sinceRecalc)
                o.put("v", STATE_SCHEMA_7164)
                root.put(lane, o)
            }
        }
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank()) return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val lane = keys.next()
                val o = root.optJSONObject(lane) ?: continue
                val st = lanes.getOrPut(lane) { LaneState() }
                val schema7164 = o.optInt("v", 0)
                if (schema7164 < STATE_SCHEMA_7164) {
                    // Pre-7161 window: collected before non-strategy exits were
                    // filtered out. Retire the sample and the multipliers it
                    // produced, keep the lifetime count so the audit trail
                    // survives, and let the lane re-learn from clean closes.
                    synchronized(st) {
                        st.window.clear()
                        st.sinceRecalc = 0
                        st.lifetimeCloses = o.optLong("life", 0L)
                        st.tpMult = 1.0
                        st.slMult = 1.0
                    }
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_EXIT_TUNER_PRE7161_WINDOW_RETIRED_7164")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "LANE_EXIT_TUNER_PRE7161_WINDOW_RETIRED_7164",
                            "lane=$lane retiredTp=${"%.2f".format(o.optDouble("tp", 1.0))} " +
                                "retiredSl=${"%.2f".format(o.optDouble("sl", 1.0))} " +
                                "retiredN=${o.optJSONArray("win")?.length() ?: 0} life=${st.lifetimeCloses}",
                        )
                    } catch (_: Throwable) {}
                    continue
                }
                synchronized(st) {
                    st.tpMult = o.optDouble("tp", 1.0).coerceIn(TP_MIN, TP_MAX)
                    st.slMult = o.optDouble("sl", 1.0).coerceIn(SL_MIN, SL_MAX)
                    st.lifetimeCloses = o.optLong("life", 0L)
                    st.sinceRecalc = o.optInt("sr", 0)
                    st.window.clear()
                    val arr = o.optJSONArray("win")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            st.window.addLast(Outcome(
                                pnlPct = e.optDouble("p", 0.0),
                                peakPct = e.optDouble("k", 0.0),
                                win = e.optBoolean("w", false),
                                stopHit = e.optBoolean("s", false),
                            ))
                        }
                        while (st.window.size > WINDOW) st.window.removeFirst()
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    fun reset() { lanes.clear() }
}
