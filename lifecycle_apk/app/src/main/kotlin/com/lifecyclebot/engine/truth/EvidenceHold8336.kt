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
 * entry policy. While [rehearsal8336] is on, Entry8114Mode8326 decides PAPER meme entries the same way it decides LIVE
 * ones, so the paper record measures the live policy (see Entry8114Mode8326.liveActive8326).
 * A lane that has proven goes live; its live closes then answer to EntryEconomics8329 (3+ closes, mean < 0 shuts it).
 * Hard safety, exits and sizing are untouched.
 */
object EvidenceHold8336 {
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
    fun proof8336(returnsPct: List<Double>, pnlSol: List<Double>): Proof8336 {
        val r = returnsPct.filter { it.isFinite() }.map { it.coerceIn(-100.0, 1_000.0) }
        val n = r.size
        if (n == 0) return Proof8336(0, Double.NaN, Double.NaN, Double.NaN, false)
        val mean = r.average()
        val se = if (n > 1) kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / (n - 1)) / kotlin.math.sqrt(n.toDouble()) else Double.POSITIVE_INFINITY
        val wins = pnlSol.filter { it.isFinite() && it > 0.0 }.sum()
        val losses = -pnlSol.filter { it.isFinite() && it < 0.0 }.sum()
        val pf = if (losses > 0.0) wins / losses else if (wins > 0.0) Double.POSITIVE_INFINITY else 0.0
        val proven = n >= MIN_N_8336 && mean - se > MARGIN_PCT_8336 && pf >= MIN_PF_8336
        return Proof8336(n, mean, se, pf, proven)
    }

    private fun canon(lane: String): String =
        (try { CanonicalLaneIdentity6506.canonical(lane) } catch (_: Throwable) { "" }).uppercase().ifBlank { lane.trim().uppercase() }

    private fun proofs8336(nowMs: Long): Map<String, Proof8336> {
        cache?.let { (at, m) -> if (nowMs - at in 0L until CACHE_MS_8336) return m }
        val since = startMs8336
        val rows: List<Trade> = try {
            com.lifecyclebot.engine.TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 5_000, includePartials = false)
        } catch (_: Throwable) { emptyList() }
        val paper = rows.filter { it.mode.equals("paper", true) && it.side.equals("SELL", true) && it.ts >= since }
        val m = paper.groupBy { canon(it.tradingMode.ifBlank { "STANDARD" }) }.mapValues { (_, t) ->
            proof8336(t.map { it.pnlPct }, t.map { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol })
        }
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
        if (proofFor8336(l, nowMs).proven) return null
        if (laneAdmitted8337(l, nowMs)) { try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_EXCEPTION_PASS_8337_$l") } catch (_: Throwable) {}; return null }
        return refuse8336(l)
    }

    /**
     * V5.0.8337 — [refusal8336] for a caller that has the candidate (Entry8114Mode8326.decide8326): an unproven lane still
     * takes an exceptional runner opportunity ([exceptional8336]) live. Everything downstream (hard safety, economics,
     * sizing, the chase check) still binds: only the evidence hold is lifted.
     */
    fun refusal8336(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (!enabled8336 || paper) return null
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!live) return null
        val l = canon(lane)
        if (proofFor8336(l, nowMs).proven) return null
        val ex = try { exceptional8336(ts, l, nowMs) } catch (_: Throwable) { null }
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
    //       Entry8114Gate8326 / EntryEconomics8329 use (a LAUNCH_EARLY plan inside the launch window) and the reason says so;
    //   (d) the lane is not shut by its own realised live losses (EntryEconomics8329.laneRealisedRefusal8329 == null).
    // Bounded: one exceptional live entry per lane per [LANE_GAP_MS_8337], at most [MAX_PER_HOUR_8337] an hour across lanes.
    const val LANE_GAP_MS_8337 = 30 * 60_000L
    const val MAX_PER_HOUR_8337 = 3
    const val PASS_TTL_MS_8337 = 60_000L
    const val ODDS_CORTEX_STRONG_8337 = "CORTEX_RUN_ODDS_STRONG_8325"
    const val ODDS_NO_BOOK_EARLY_RUN_8337 = "NO_RUN_ODDS_BOOK_EARLY_RUN_8329"
    const val VENUE_ACTIVE_CURVE_8337 = "ACTIVE_CURVE_IN_WINDOW_REAL_SOL_8319"
    const val VENUE_POOL_FLOOR_8337 = "POOL_EXIT_FLOOR_500_MET"

    private val lastByLane8337 = ConcurrentHashMap<String, Long>()          // lane -> last exception granted
    private val grants8337 = java.util.ArrayDeque<Long>()                   // grant times, last hour (guarded by itself)
    private val passes8337 = ConcurrentHashMap<String, Pair<Long, String>>() // mint|lane -> (atMs, reason)
    private val exceptions8337 = AtomicLong(0)
    @Volatile private var lastException8337: String = "-"

    /** Pure: the reason the four legs make, or null when any leg is missing. */
    fun exceptionalReason8337(grab: Boolean, venue: String?, odds: String?, laneOpen: Boolean): String? {
        if (!grab || venue.isNullOrBlank() || odds.isNullOrBlank() || !laneOpen) return null
        return "grab=RUNNER_GRAB_7967 venue=$venue odds=$odds lane=OPEN"
    }

    /** Pure over the in-memory counters: may [lane] take an exception now (per-lane gap and the hourly cap)? */
    fun rateAllows8337(lane: String, nowMs: Long): Boolean {
        val last = lastByLane8337[canon(lane)]
        if (last != null && nowMs - last in 0L until LANE_GAP_MS_8337) return false
        synchronized(grants8337) {
            while (grants8337.isNotEmpty() && nowMs - grants8337.peekFirst() >= 3_600_000L) grants8337.pollFirst()
            return grants8337.size < MAX_PER_HOUR_8337
        }
    }

    /** Counts a granted exception for [lane] / [mint] (the rate counters and the 60 s pass for the TradePlan call). */
    fun noteException8337(lane: String, mint: String, reason: String, nowMs: Long) {
        val l = canon(lane)
        lastByLane8337[l] = nowMs
        synchronized(grants8337) { grants8337.addLast(nowMs) }
        passes8337["$mint|$l"] = nowMs to reason
        if (passes8337.size > 200) passes8337.entries.removeIf { nowMs - it.value.first > PASS_TTL_MS_8337 }
        exceptions8337.incrementAndGet()
        lastException8337 = "$l ${mint.take(10)} $reason"
    }

    /** The reason [mint] in [lane] was admitted by the exception inside the last [PASS_TTL_MS_8337]; null otherwise. */
    fun admitted8337(mint: String, lane: String, nowMs: Long): String? {
        val p = passes8337["$mint|${canon(lane)}"] ?: return null
        return if (nowMs - p.first in 0L..PASS_TTL_MS_8337) p.second else null
    }

    /** Any candidate of [lane] admitted by the exception inside the last [PASS_TTL_MS_8337] (the mint-less TradePlan call). */
    fun laneAdmitted8337(lane: String, nowMs: Long): Boolean {
        val suffix = "|${canon(lane)}"
        return passes8337.entries.any { it.key.endsWith(suffix) && nowMs - it.value.first in 0L..PASS_TTL_MS_8337 }
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
        val a = try { com.lifecyclebot.engine.cortex.Cortex7885.assess(ts, lane, nowMs) } catch (_: Throwable) { null }
        if (a != null && a.fused.pRun8325.isFinite() && a.fused.runVoters8325 > 0) {
            // The lane has a run-odds book: only its top bucket (STRONG = top fifth of the lane's own ordering) qualifies.
            return if (a.bucket == com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG)
                "${ODDS_CORTEX_STRONG_8337}_pRun${"%.2f".format(a.fused.pRun8325)}_voters${a.fused.runVoters8325}" else null
        }
        val early = try { EntryEconomics8329.earlyRunner8329(ts, nowMs) } catch (_: Throwable) { false }
        return if (early) ODDS_NO_BOOK_EARLY_RUN_8337 else null
    }

    /**
     * The reason [ts] in [lane] is an exceptional runner opportunity the hold lets through live, or null. A grant is
     * counted once per mint|lane per [PASS_TTL_MS_8337] (re-reads of the same candidate return the recorded reason).
     */
    fun exceptional8336(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): String? {
        val l = canon(lane)
        if (ts.mint.isBlank()) return null
        admitted8337(ts.mint, l, nowMs)?.let { return it }
        val grab = try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false }
        if (!grab) return null
        val laneOpen = (try { EntryEconomics8329.laneRealisedRefusal8329(l, nowMs) } catch (_: Throwable) { "ERR" }) == null
        val reason = exceptionalReason8337(grab, venueLeg8337(ts, l, nowMs), oddsLeg8337(ts, l, nowMs), laneOpen) ?: return null
        if (!rateAllows8337(l, nowMs)) {
            try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_EXCEPTION_RATE_BOUND_8337_$l") } catch (_: Throwable) {}
            return null
        }
        noteException8337(l, ts.mint, reason, nowMs)
        try { PipelineHealthCollector.labelInc("EVIDENCE_HOLD_EXCEPTION_8337_$l") } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "EVIDENCE_HOLD_EXCEPTION_8337",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$l $reason action=hold_lifted_downstream_gates_still_bind",
            )
        } catch (_: Throwable) {}
        return reason
    }

    fun exceptionCount8337(): Long = exceptions8337.get()

    internal fun resetForTests8337() {
        lastByLane8337.clear(); synchronized(grants8337) { grants8337.clear() }; passes8337.clear(); exceptions8337.set(0); lastException8337 = "-"
    }

    fun statusLine8336(nowMs: Long = System.currentTimeMillis()): String {
        val lanes = proofs8336(nowMs).entries.sortedByDescending { it.value.n }.joinToString(" · ") { (l, p) ->
            "$l n${p.n}/${MIN_N_8336} mean${"%+.1f".format(p.meanPct)}% se${"%.1f".format(p.sePct)} pf${"%.2f".format(p.pf)}${if (p.proven) " LIVE" else " held"}"
        }.ifBlank { "no paper closes since start" }
        return "evidenceHold8336[on=$enabled8336 rehearsal=$rehearsal8336 sinceMin=${if (startMs8336 > 0) (nowMs - startMs8336) / 60_000 else -1} " +
            "bar=n>=$MIN_N_8336,mean-se>$MARGIN_PCT_8336%,pf>=$MIN_PF_8336 refused=${refused.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "exceptions=${exceptions8337.get()} last=$lastException8337 $lanes]"  // V5.0.8337
    }
}
