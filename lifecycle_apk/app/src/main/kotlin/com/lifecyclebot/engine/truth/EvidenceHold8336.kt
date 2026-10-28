package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8336 §EVIDENCE_BEFORE_LIVE — owner: "the bot needs to hold off live trading until it can build sufficient
 * evidence."
 *
 * 5.0.8329 live (03:10): 12 closes, 2 wins, PF 0.12; every lane's graded labels negative; Cortex 0 STRONG cells. The
 * live entries came through doors that need no measured edge (ADMIT_8114_8326 137, exploration slot 266).
 *
 * The rule, per meme lane, while [enabled8336]:
 *   - a LIVE entry is refused (EVIDENCE_HOLD_8336_<lane>) until the lane's own PAPER record since [startMs8336] proves:
 *     at least [MIN_N_8336] whole closes, mean return minus one standard error above [MARGIN_PCT_8336] (paper fills are
 *     optimistic), and profit factor at least [MIN_PF_8336];
 *   - PAPER is never refused here: paper is where the evidence is built.
 * Only paper closes made after the hold started count ([startMs8336], persisted): older paper rows were made by a looser
 * entry policy. While [rehearsal8336] is on, retired compatibility mode decides PAPER meme entries the same way it decides LIVE
 * ones, so the paper record measures the live policy (see the canonical entry policy).
 * A lane that has proven goes live; its live closes then answer to EntryEconomics8329 (3+ closes, mean < 0 shuts it).
 * Hard safety, exits and sizing are untouched.
 */
object EvidenceHold8336 {
    val HELD_LANES_8392 = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "MANIPULATED", "PROJECT_SNIPER", "QUALITY", "BLUECHIP", "DIP_HUNTER", "TREASURY", "CASHGEN", "CYCLIC", "CORE")

    const val MIN_N_8336 = 30
    const val MARGIN_PCT_8336 = 2.0
    const val MIN_PF_8336 = 1.3
    private const val CACHE_MS_8336 = 30_000L
    private const val PREFS_8336 = "aate_evidence_hold_8336"
    private const val KEY_START_8336 = "start_ms"

    /** On by default; the owner can switch it off (live trades without paper proof, as 8329). */
    @Volatile var enabled8336: Boolean = true
    /** Paper meme entries are decided by the live (8114-mode) decision while the hold is on. */
    @Volatile var rehearsal8336: Boolean = true
    @Volatile var startMs8336: Long = 0L
        private set

    data class Proof8336(val n: Int, val meanPct: Double, val sePct: Double, val pf: Double, val proven: Boolean)

    private val refused = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var cache: Pair<Long, Map<String, Proof8336>>? = null

    // ── V5.0.8342 — two records per lane: ALL paper closes (the bar) and the closes whose entry the live policy would have admitted ──
    data class LaneRecord8342(val all: Proof8336, val liveAdmit: Proof8336, val liveRefuse: Int, val untagged: Int)
    @Volatile private var records8342: Map<String, LaneRecord8342> = emptyMap()

    /** Pure: the hold covers the meme lanes retired compatibility mode decides (CRYPTO_ALT and the LLM desks are not held). */
    fun heldLane8342(lane: String): Boolean = canon(lane) in EvidenceHold8336.HELD_LANES_8392

    /** Pure: the lane's status fragment — both records; the bar is read on ALL closes. */
    fun laneLine8342(lane: String, r: LaneRecord8342): String =
        "$lane all ${proofText8342(r.all)}${if (r.all.proven) " LIVE" else " held"} · liveAdmit ${proofText8342(r.liveAdmit)} liveRefuse${r.liveRefuse} untagged${r.untagged}"

    private fun proofText8342(p: Proof8336): String =
        "n${p.n}/$MIN_N_8336 mean${"%+.1f".format(p.meanPct)}% se${"%.1f".format(p.sePct)} pf${"%.2f".format(p.pf)}"

    /** BotService.onCreate: the hold's start (first install of 8336) is persisted so a restart keeps counting. */
    fun init8336(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS_8336, Context.MODE_PRIVATE)
            var s = p.getLong(KEY_START_8336, 0L)
            if (s <= 0L) { s = System.currentTimeMillis(); p.edit().putLong(KEY_START_8336, s).apply() }
            startMs8336 = s
        } catch (_: Throwable) { if (startMs8336 <= 0L) startMs8336 = System.currentTimeMillis() }
    }

    /** Pure: the proof a lane's paper returns (% per whole close) and SOL results make. */
    // V5.0.8399 §LANE_BAR — operator (27 Oct 2026): the lanes that carried the 8096-8114 cash gains (CORE, MOONSHOT,
    // PROJECT_SNIPER) prove on 15 closes at PF 1.2 so live growth can restart while the proof population rebuilds; every
    // other lane keeps the 30 / 1.3 bar. The margin (mean - SE > MARGIN_PCT_8336) is unchanged for all.
    val FAST_BAR_LANES_8399: Set<String> = setOf("CORE", "MOONSHOT", "PROJECT_SNIPER")
    const val FAST_MIN_N_8399 = 15
    const val FAST_MIN_PF_8399 = 1.2
    fun minN8399(lane: String?): Int = if (lane != null && canon(lane) in FAST_BAR_LANES_8399) FAST_MIN_N_8399 else MIN_N_8336
    fun minPf8399(lane: String?): Double = if (lane != null && canon(lane) in FAST_BAR_LANES_8399) FAST_MIN_PF_8399 else MIN_PF_8336

    fun proof8336(returnsPct: List<Double>, pnlSol: List<Double>, lane: String? = null): Proof8336 {
        val r = returnsPct.filter { it.isFinite() }.map { it.coerceIn(-100.0, 1_000.0) }
        val n = r.size
        if (n == 0) return Proof8336(0, Double.NaN, Double.NaN, Double.NaN, false)
        val mean = r.average()
        val se = if (n > 1) kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / (n - 1)) / kotlin.math.sqrt(n.toDouble()) else Double.POSITIVE_INFINITY
        val wins = pnlSol.filter { it.isFinite() && it > 0.0 }.sum()
        val losses = -pnlSol.filter { it.isFinite() && it < 0.0 }.sum()
        val pf = if (losses > 0.0) wins / losses else if (wins > 0.0) Double.POSITIVE_INFINITY else 0.0
        val proven = n >= minN8399(lane) && mean - se > MARGIN_PCT_8336 && pf >= minPf8399(lane)   // V5.0.8399 — lane bar
        return Proof8336(n, mean, se, pf, proven)
    }

    /** V5.0.8344 — the lane's shadow record behind the live trader (EvidenceShadow8344) unlocks it on the same bar as paper. */
    /** V5.0.8347 — a lane whose best exploration variant (ExploreShadow8347) has proven unlocks too; live then enters that way. */
    fun explorationProven8347(lane: String): Boolean =
        try { ExploreShadow8347.bestFor8351(canon(lane), startMs8336) != null } catch (_: Throwable) { false }

    /**
     * V5.0.8392 — the lane has proven LANE-WIDE (its whole paper record, or its whole shadow book with real exits and costs),
     * not merely through one exploration niche. A niche/timing choice then shapes preference, it does not veto the lane:
     * MOONSHOT 5.0.8390 read shadow n30 +60.1% pf4.42 PROVEN lane-wide while NICHE_ONLY_8351 refused 462 of its live entries.
     */
    fun laneWideProven8392(lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val l = canon(lane)
        return proofFor8336(l, nowMs).proven || (try { EvidenceShadow8344.proof8344(l, startMs8336).proven } catch (_: Throwable) { false })
    }

    fun shadowProven8344(lane: String): Boolean = explorationProven8347(lane) ||
        try { EvidenceShadow8344.proof8344(canon(lane), startMs8336).proven } catch (_: Throwable) { false }

    private fun canon(lane: String): String = CanonicalLaneIdentity6506.desk8392(lane)   // V5.0.8365 — SHITCOIN_EXPRESS files as EXPRESS

    @Volatile private var cachedEpoch8394: Long = Long.MIN_VALUE
    private fun proofs8336(nowMs: Long): Map<String, Proof8336> {
        val epoch8394 = LearningEpoch8164.epochMs8164()
        if (cachedEpoch8394 != epoch8394) { cache = null; records8342 = emptyMap(); cachedEpoch8394 = epoch8394 }
        cache?.let { (at, m) -> if (nowMs - at in 0L until CACHE_MS_8336) return m }
        val since = maxOf(startMs8336, epoch8394)
        val rows: List<Trade> = try {
            com.lifecyclebot.engine.TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 5_000, includePartials = false)
        } catch (_: Throwable) { emptyList() }
        // V5.0.8342 — meme lanes only (EvidenceHold8336.HELD_LANES_8392): CRYPTO_ALT is an LLM desk, not a lane this hold decides.
        val paper = rows.filter { it.mode.equals("paper", true) && it.side.equals("SELL", true) && it.ts >= since && (epoch8394 <= 0L || it.entryTsMs >= epoch8394) && heldLane8342(it.tradingMode.ifBlank { "STANDARD" }) }
        val recs = paper.groupBy { canon(it.tradingMode.ifBlank { "STANDARD" }) }.mapValues { (lane, t) ->
            // V5.0.8342 — every paper close is tagged with the live policy's verdict on its entry (Rehearsal8339); the bar
            // stays on ALL closes (paper results never admit live on their own, and a liveAdmit-only bar is zero again).
            val tagged = t.map { it to Rehearsal8339.tagPaperClose8342("${it.ts}|${it.mint}|${it.positionId}", it.mint, lane, it.ts) }
            val admit = tagged.filter { it.second == Rehearsal8339.LIVE_ADMIT_8342 }.map { it.first }
            LaneRecord8342(
                all = proof8336(t.map { it.pnlPct }, t.map { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }, lane),
                liveAdmit = proof8336(admit.map { it.pnlPct }, admit.map { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }, lane),
                liveRefuse = tagged.count { it.second == Rehearsal8339.LIVE_REFUSE_8342 },
                untagged = tagged.count { it.second == Rehearsal8339.UNTAGGED_8342 },
            )
        }
        records8342 = recs
        val m = recs.mapValues { it.value.all }
        cache = nowMs to m
        return m
    }

    fun proofFor8336(lane: String, nowMs: Long = System.currentTimeMillis()): Proof8336 =
        proofs8336(nowMs)[canon(lane)] ?: Proof8336(0, Double.NaN, Double.NaN, Double.NaN, false)

    /**
     * The live refusal for [lane] while the hold is on and its paper record has not proven; null otherwise.
     * V5.0.8337 — the executor-chokepoint call (TradePlan7739.liveBlockReason) knows no TokenState: a lane whose candidate
     * was admitted by [exceptional8336] inside the last [PASS_TTL_MS_8337] passes here too ([laneAdmitted8337]).
     */
    fun refusal8336(lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (!enabled8336 || paper) return null
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live) return null
        val l = canon(lane)
        if (proofFor8336(l, nowMs).proven || shadowProven8344(l)) return null
        if (laneAdmitted8337(l, nowMs)) { try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_EXCEPTION_PASS_8337_$l") } catch (_: Throwable) {}; return null }
        return refuse8336(l)
    }

    /**
     * V5.0.8337 — [refusal8336] for a caller that has the candidate (LiveEdgeGate7877.liveRefusal): an unproven lane still
     * takes an exceptional runner opportunity ([exceptional8336]) live. Everything downstream (hard safety, economics,
     * sizing, the chase check) still binds: only the evidence hold is lifted.
     */
    fun refusal8336(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis(), grabLegs8345: String? = null,
                    candidateVersion8397: Long = versionForException8397(ts.mint)): String? {
        if (!enabled8336 || paper) return null
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live) return null
        val l = canon(lane)
        if (proofFor8336(l, nowMs).proven || shadowProven8344(l)) return null
        // V5.0.8345 — a candidate arriving through the grab lane brings its own forming-run legs ([grabLegs8345]).
        val version = candidateVersion8397.takeIf { it > 0L } ?: versionForException8397(ts.mint)
        val ex = try { exceptionalCore8345(ts, l, nowMs, grabLegs8345, version) } catch (_: Throwable) { null }
        if (ex != null) return null
        return refuse8336(l)
    }

    private fun refuse8336(l: String): String {
        refused.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_8336_$l") } catch (_: Throwable) {}
        return "EVIDENCE_HOLD_8336_$l"
    }

    // ── V5.0.8337 §EXCEPTIONAL_RUNNER — owner: "obviously if it sees a 100x opportunity take it but for the most part yes
    // it will be kind of quiet. its never for long." ──
    //
    // An unproven lane takes ONE kind of live entry through the hold: an exceptional runner opportunity, every leg provable
    // now (no labels, no paper record):
    //   (a) RunnerGrab7967.grab7967 — a forming runner in a proven runner cell, firming or standing (not a RunnerPlay ticket);
    //   (b) venue — inside the launch window on an ACTIVE pump.fun curve holding the 8319/8320 real-SOL exit-safety rule
    //       (LaneAgeLiquidityTruth8140.activeCurveExitSafe8319), or a graduated / AMM pool meeting the live $500 exit floor
    //       (LiquidityTruth8037.hydrate8037 >= LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140);
    //   (c) the Cortex run-odds read ranks the coin in the lane's top bucket (Cortex7885.assess: STRONG, with a run-odds book —
    //       pRun8325 finite from run-skilled seats); a lane with no run-odds book yet falls back to the early-run evidence
    //       retired compatibility gate / EntryEconomics8329 use (a LAUNCH_EARLY plan inside the launch window) and the reason says so;
    //   (d) the lane is not shut by its own realised live losses (EntryEconomics8329.laneRealisedRefusal8329 == null).
    // V5.0.8397 — bounded submitted live attempts: reserve only after the executable ticket passes,
    // commit when a signature exists (including unknown confirmation), release every pre-submit abort/defer.
    const val LANE_GAP_MS_8337 = 30 * 60_000L
    const val MAX_PER_HOUR_8337 = 3
    const val PASS_TTL_MS_8337 = 60_000L
    const val ODDS_CORTEX_STRONG_8337 = "CORTEX_RUN_ODDS_STRONG_8325"
    const val ODDS_NO_BOOK_EARLY_RUN_8337 = "NO_RUN_ODDS_BOOK_EARLY_RUN_8329"
    const val VENUE_ACTIVE_CURVE_8337 = "ACTIVE_CURVE_IN_WINDOW_REAL_SOL_8319"
    const val VENUE_POOL_FLOOR_8337 = "POOL_EXIT_FLOOR_500_MET"

    private val exceptionBudget8397 = ExceptionalEntryBudget8397(LANE_GAP_MS_8337, MAX_PER_HOUR_8337, PASS_TTL_MS_8337)
    private fun versionForException8397(mint: String): Long =
        try { com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(mint) } catch (_: Throwable) { 0L }

    private fun candidate8397(mint: String, lane: String, version: Long) = ExceptionalEntryBudget8397.Candidate(mint, canon(lane), version)

    /** Pure: the reason the four legs make, or null when any leg is missing. */
    fun exceptionalReason8337(grab: Boolean, venue: String?, odds: String?, laneOpen: Boolean): String? {
        if (!grab || venue.isNullOrBlank() || odds.isNullOrBlank() || !laneOpen) return null
        return "grab=RUNNER_GRAB_7967 venue=$venue odds=$odds lane=OPEN"
    }

    /** Pure over the in-memory counters: may [lane] take an exception now (per-lane gap and the hourly cap)? */
    fun rateAllows8337(lane: String, nowMs: Long): Boolean = exceptionBudget8397.rateAllows(canon(lane), nowMs)

    /** Historical test fixture only. Production qualification never calls this immediate-commit helper. */
    internal fun noteException8337(lane: String, mint: String, reason: String, nowMs: Long) {
        val c = candidate8397(mint, lane, 1L)
        val attempt = "test8337|$mint|${c.lane}|$nowMs"
        exceptionBudget8397.qualify(c, reason, false, nowMs)
        if (exceptionBudget8397.reserve(attempt, c, nowMs) == null) exceptionBudget8397.submitted(attempt, "TEST_ONLY", nowMs)
    }

    /** The reason [mint] in [lane] was admitted by the exception inside the last [PASS_TTL_MS_8337]; null otherwise. */
    fun admitted8337(mint: String, lane: String, nowMs: Long): String? {
        return exceptionBudget8397.recentProof(mint, canon(lane), nowMs)?.reason
    }

    /** Any candidate of [lane] admitted by the exception inside the last [PASS_TTL_MS_8337] (the mint-less TradePlan call). */
    fun laneAdmitted8337(lane: String, nowMs: Long): Boolean {
        return exceptionBudget8397.laneHasSubmittedPass(canon(lane), nowMs)
    }

    private fun venueLeg8337(ts: TokenState, lane: String, nowMs: Long): String? {
        val mint = ts.mint
        val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }
        val window = try { com.lifecyclebot.v3.V3EngineManager.launchAgeWindowMinutes8140() } catch (_: Throwable) { 360.0 }
        val inWindow = age != null && age.isFinite() && age >= 0.0 && age <= window
        val activeCurve = try { CurveReserves7951.realSolIfActive8046(mint, nowMs) } catch (_: Throwable) { null } != null
        if (activeCurve) {
            val safe = try { com.lifecyclebot.engine.LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(mint, nowMs) } catch (_: Throwable) { false }
            return if (inWindow && safe) VENUE_ACTIVE_CURVE_8337 else null
        }
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        val known = try { CurveReserves7951.known7951(mint) } catch (_: Throwable) { false }
        if (!graduated && known) return null   // a curve the bot knows but cannot read as active: stale, not a pool
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { 0.0 }
        return if (liq.isFinite() && liq >= com.lifecyclebot.engine.LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140) VENUE_POOL_FLOOR_8337 else null
    }

    private fun oddsLeg8337(ts: TokenState, lane: String, nowMs: Long): String? {
        // V5.0.8418 — the Cortex ordering votes here only where its walk-forward authority is granted and the lane is not
        // inverted (Cortex7885.orderingVotes8418). An unvalidated / inverted ordering abstains: it is never read (no STRONG
        // requirement that selects the measured losers) and the provable early-run evidence decides, as for a lane with no book.
        val votes = try { com.lifecyclebot.engine.cortex.Cortex7885.orderingVotes8418(lane) } catch (_: Throwable) { false }
        val a = if (!votes) null else try { com.lifecyclebot.engine.cortex.Cortex7885.assess(ts, lane, nowMs) } catch (_: Throwable) { null }
        val runBook = a != null && a.fused.pRun8325.isFinite() && a.fused.runVoters8325 > 0
        val strong = a != null && a.bucket == com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG
        val leg = oddsLeg8418(runBook, strong, a?.fused?.pRun8325 ?: Double.NaN, a?.fused?.runVoters8325 ?: 0, votes) {
            try { EntryEconomics8329.earlyRunner8329(ts, nowMs) } catch (_: Throwable) { false }
        }
        if (!votes && leg != null) try { PipelineHealthCollector.labelInc("EVIDENCE_ODDS_CORTEX_ABSTAINED_8418_${canon(lane)}") } catch (_: Throwable) {}
        return leg
    }

    /** V5.0.8418 — the odds leg's reason when the lane's Cortex ordering abstains and the early-run evidence carries it. */
    const val ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418 = "CORTEX_UNVALIDATED_ABSTAINS_EARLY_RUN_8418"

    /**
     * Pure (V5.0.8418): the exception door's odds leg (c). A validated ordering ([orderingVotes]) with a run-odds book decides by
     * its top bucket alone (STRONG qualifies, anything else does not). Otherwise the Cortex does not vote and the coin's own
     * early-run evidence ([earlyRun], read only then) carries the leg: [ODDS_NO_BOOK_EARLY_RUN_8337] when the lane has no book,
     * [ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418] when its ordering is unvalidated or inverted.
     */
    fun oddsLeg8418(runBook: Boolean, strong: Boolean, pRun: Double, voters: Int, orderingVotes: Boolean, earlyRun: () -> Boolean): String? {
        if (orderingVotes && runBook) {
            return if (strong) "${ODDS_CORTEX_STRONG_8337}_pRun${"%.2f".format(pRun)}_voters$voters" else null
        }
        if (!earlyRun()) return null
        return if (orderingVotes) ODDS_NO_BOOK_EARLY_RUN_8337 else ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418
    }

    /**
     * Qualification only: a bounded proof for this mint/lane/generation. No slot is consumed by this read.
     */
    fun exceptional8336(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): String? = exceptionalCore8345(ts, lane, nowMs, null)

    // ── V5.0.8345 §THE_GRAB_LANE_IS_THE_100X_DOOR — 5.0.8344 audit: exceptions granted 0 in 431 min; runner grab detected 8,
    // admitted 0. Leg (a) needed RunnerGrab7967.grab7967 (a PROVEN grab cell) and leg (c) a STRONG run-odds bucket or the
    // 8329 early-run read: on an unlearned book neither can be true, so the owner's "if it sees a 100x opportunity take it"
    // door could never open. A candidate that arrives THROUGH THE GRAB LANE (RunnerGrabLane8343, every other leg of its own
    // already passed: full safety proven, venue exit-safe, forming run, 1.5x economics, chase cap, open bound) satisfies (a)
    // and (c) with that evidence ([ODDS_GRAB_LANE_8345]); (b) venue, (d) lane not shut by live losses and the rate bounds
    // (1 per lane per 30 min, 3 an hour) are the same code. Counted apart: EVIDENCE_HOLD_EXCEPTION_8345_GRAB_<lane>.
    const val GRAB_LANE_LEGS_8345 = "GRAB_LANE_8343"
    const val ODDS_GRAB_LANE_8345 = "GRAB_LANE_FORMING_RUN_8343"

    /** Pure: the reason the grab lane's legs make with the venue / lane legs, or null when one is missing. */
    fun exceptionalReason8345(grabLegs: String?, venue: String?, odds: String?, laneOpen: Boolean): String? {
        if (grabLegs.isNullOrBlank() || venue.isNullOrBlank() || odds.isNullOrBlank() || !laneOpen) return null
        return "grab=$GRAB_LANE_LEGS_8345:$grabLegs venue=$venue odds=$odds lane=OPEN"
    }

    /** RunnerGrabLane8343 (LIVE, every grab leg passed): the exception read with the grab lane's own evidence as legs (a)+(c). */
    fun exceptionalViaGrab8345(ts: TokenState, lane: String, grabLegs: String, nowMs: Long = System.currentTimeMillis()): String? =
        exceptionalCore8345(ts, lane, nowMs, grabLegs.ifBlank { null })

    private fun exceptionalCore8345(ts: TokenState, lane: String, nowMs: Long, grabLegs: String?,
                                    version8397: Long = versionForException8397(ts.mint)): String? {
        val l = canon(lane)
        if (ts.mint.isBlank()) return null
        val identity8397 = candidate8397(ts.mint, l, version8397)
        exceptionBudget8397.proof(identity8397, nowMs)?.let {
            if (exceptionBudget8397.canQualify(identity8397, nowMs)) return it.reason
        }
        val grab = try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false }
        if (!grab && grabLegs == null) return null
        val laneOpen = (try { EntryEconomics8329.laneRealisedRefusal8329(l, nowMs) } catch (_: Throwable) { "ERR" }) == null
        val venue = venueLeg8337(ts, l, nowMs)
        val odds = oddsLeg8337(ts, l, nowMs)
        // The proven-cell door (8337) when the cell AND the odds are proven; else the grab lane's own forming-run evidence
        // stands in for the missing leg(s) (a) / (c) and the grant is counted on the grab door.
        val viaGrab = grabLegs != null && !(grab && odds != null)
        val reason = (if (viaGrab) exceptionalReason8345(grabLegs, venue, odds ?: ODDS_GRAB_LANE_8345, laneOpen)
            else exceptionalReason8337(grab, venue, odds, laneOpen)) ?: return null
        if (!rateAllows8337(l, nowMs)) {
            try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_EXCEPTION_RATE_BOUND_8337_$l") } catch (_: Throwable) {}
            return null
        }
        if (!exceptionBudget8397.qualify(identity8397, reason, viaGrab, nowMs)) return null
        return reason
    }

    /** Executor: after ticket validation, before preparation or any route can submit this LIVE attempt. */
    fun reserveEntry8397(ts: TokenState, intent: com.lifecyclebot.engine.ExecutableOpenGate.ExecutionIntent?,
                         nowMs: Long = System.currentTimeMillis()): String? {
        if (intent == null || intent.mint != ts.mint || !intent.mode.equals("LIVE", true) || !intent.fdgAllowed ||
            !intent.fdgVerdict.equals("BUY", true) || intent.hardNoReasons.isNotEmpty()) return "EVIDENCE_EXCEPTION_TICKET_INVALID_8397"
        val lane = canon(intent.canonicalLane.ifBlank { intent.lane })
        if (!enabled8336 || !heldLane8342(lane) || proofFor8336(lane, nowMs).proven || shadowProven8344(lane)) return null
        // A stale/missing proof is not silently reconstructed from a different generation at the buy door.
        return exceptionBudget8397.reserve(intent.attemptId, candidate8397(ts.mint, lane, intent.candidateVersion), nowMs)
    }

    /** Executor finally: blank signature releases; a sent/unknown signature commits once and must never be released. */
    fun finishEntry8397(attemptId: String, signature: String?, nowMs: Long = System.currentTimeMillis()) {
        if (signature.isNullOrBlank()) { exceptionBudget8397.release(attemptId); return }
        val charged = exceptionBudget8397.submitted(attemptId, signature, nowMs) ?: return
        val c = charged.reservation.candidate
        val proof = charged.reservation.proof
        try { PipelineHealthCollector.labelInc(if (proof.viaGrab) "EVIDENCE_HOLD_EXCEPTION_8345_GRAB_${c.lane}" else "EVIDENCE_HOLD_EXCEPTION_8337_${c.lane}") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EVIDENCE_EXCEPTION_SUBMITTED_8397",
            "mint=${c.mint.take(10)} lane=${c.lane} version=${c.version} attempt=$attemptId signature=${signature.take(16)} ${proof.reason}") } catch (_: Throwable) {}
    }

    /**
     * V5.0.8418 — the hold basis a candidate carries when the hold let it through on its OWN exception (the 100x door) in an
     * unproven held lane: FdgGate8365.BASIS_EXCEPTION_8418, else null. LiveEdgeGate7877.liveRefusal reads it after the hold.
     */
    fun exceptionBasis8418(mint: String, lane: String, holdPassed: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live || !holdPassed || mint.isBlank()) return null
        val l = canon(lane)
        val proven = proofFor8336(l, nowMs).proven || shadowProven8344(l)
        return FdgGate8365.exceptionBasisOf8418(live, enabled8336, heldLane8342(l), holdPassed, proven,
            if (proven) null else admitted8337(mint, l, nowMs))
    }

    fun exceptionCount8337(): Long = exceptionBudget8397.counts().first
    fun grabExceptionCount8345(): Long = exceptionBudget8397.counts().second

    internal fun qualifyForTest8397(mint: String, lane: String, version: Long, nowMs: Long): Boolean =
        exceptionBudget8397.qualify(candidate8397(mint, lane, version), "TEST_VERIFIED_LEGS", true, nowMs)

    internal fun resetForTests8337() {
        exceptionBudget8397.clear()
    }

    fun statusLine8336(nowMs: Long = System.currentTimeMillis()): String {
        proofs8336(nowMs)
        // V5.0.8342 — both records per meme lane; the bar is read on ALL paper closes (bar=all).
        val lanes = records8342.entries.sortedByDescending { it.value.all.n }.joinToString(" · ") { (l, r) -> laneLine8342(l, r) }
            .ifBlank { "no meme-lane paper closes since start" }
        return "evidenceHold8336[on=$enabled8336 rehearsal=$rehearsal8336 sinceMin=${if (startMs8336 > 0) (nowMs - startMs8336) / 60_000 else -1} " +
            "bar=all,n>=$MIN_N_8336,mean-se>$MARGIN_PCT_8336%,pf>=$MIN_PF_8336 fast8399=${FAST_BAR_LANES_8399.joinToString("/")}:n>=$FAST_MIN_N_8399,pf>=$FAST_MIN_PF_8399 lanes=${EvidenceHold8336.HELD_LANES_8392.size} refused=${refused.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "exceptions=${exceptionCount8337()} last=${exceptionBudget8397.last()} grabDoor8345=${grabExceptionCount8345()} reserved8397=${exceptionBudget8397.reservations()} meter8397=SUBMITTED_ATTEMPT $lanes" +
            (try { EvidenceShadow8344.recoveryLine8345(startMs8336, nowMs) } catch (_: Throwable) { " recovery8345{?}" }) + "]"  // V5.0.8345 — the bounded path to the bar
    }
}
