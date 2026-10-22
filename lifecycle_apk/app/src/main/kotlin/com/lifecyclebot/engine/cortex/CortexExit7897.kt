package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7897 — Cortex v3: the exit cortex.
 *
 * Every open position (paper and live) is sampled every [SAMPLE_EVERY_MS]:
 * all token voters (the entry Cortex's registry) plus the position's own state
 * (P&L, peak, give-back from peak, time held, share already sold). Each sample
 * is graded by what the price did over the next [HORIZON_MS] (runner lanes
 * [RUNNER_HORIZON_MS]) — the true value of HOLDING versus selling at that
 * moment (one exit cost is paid either way, so the label is the gross move).
 *
 * The same earned-authority ledger and fusion as the entry Cortex; its own
 * versioned bar (EXIT_BAR_V1), per lane, decaying, and needing BOTH n samples
 * and distinct positions (samples of one position are correlated):
 *
 *   HOLD authority  HOLD_STRONG (fused forward > +3%) n >= 60 over >= 15
 *                   positions, mean - SE > +2%: an ORDINARY exit (profit
 *                   protection, plan/time/strategy) of a position IN PROFIT is
 *                   held while the Cortex reads HOLD_STRONG, at most 60 min per
 *                   position. Never a stop, a hard floor or any emergency class.
 *   SELL authority  SELL_STRONG (fused forward < -3%) with the same n and
 *                   mean + SE < -2%: the Cortex exits the position itself
 *                   (CORTEX_EXIT_7897_*) through the existing plan-exit path.
 *
 * Until a lane clears the bar, both are counted as SHADOW_*.
 */
object CortexExit7897 {
    // V5.0.8082 §ADAPT_FASTER — per-trade read every minute (was 3): the hold/sell thinking turns within the trade.
    private const val SAMPLE_EVERY_MS = 60_000L
    private const val HORIZON_MS = 30L * 60_000L
    private const val RUNNER_HORIZON_MS = 120L * 60_000L
    private const val GRADE_GRACE_MS = 20L * 60_000L
    private const val MARK_MAX_AGE_MS = 120_000L
    private const val READ_TTL_MS = 6L * 60_000L
    private const val MAX_PENDING = 6_000
    private const val PERSIST_PENDING_7960 = 600
    private const val STRONG_PCT = 3.0
    private const val PROOF_PCT = 2.0
    // V5.0.8082 §ADAPT_FASTER — exit proof on 30 grades over 8 positions (was 60/15), still mean-SE gated.
    private const val MIN_N = 30
    private const val MIN_POSITIONS = 8
    private const val MAX_HOLD_VETO_MS = 60L * 60_000L
    private const val BOOK_DECAY = 0.99
    private const val LEGACY_PERSIST_KEY_8091 = "CORTEX_EXIT_7897"
    private const val PERSIST_KEY = "CORTEX_EXIT_FORWARD_8091"
    internal const val TARGET_8091 = "FUTURE_MARK_RETURN_V1"
    @Volatile private var lastPersistMs8091 = 0L
    @Volatile private var dirty8091 = false
    private const val PERSIST_EVERY = 30
    private const val FETCH_GAP_MS = 60_000L

    /** Operator, shutdown and housekeeping exits are never held. */
    private val OPERATOR_OR_SHUTDOWN = listOf(
        "MANUAL", "USER", "OPERATOR", "SHUTDOWN", "STOP_BOT", "KILL", "LIQUIDAT", "CLOSE_ALL", "DUST", "RECOVER", "ORPHAN", "RECONCIL",
    )

    enum class Bucket { SELL_STRONG, NEUTRAL, HOLD_STRONG }

    /** Pure: where a fused forward edge falls. */
    fun bucketOf(fwdPct: Double): Bucket = when {
        !fwdPct.isFinite() -> Bucket.NEUTRAL
        fwdPct > STRONG_PCT -> Bucket.HOLD_STRONG
        fwdPct < -STRONG_PCT -> Bucket.SELL_STRONG
        else -> Bucket.NEUTRAL
    }

    private fun se(s: CortexLedger7885.Stat): Double = if (s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY

    /** Pure: EXIT_BAR_V1 hold proof. */
    fun holdProven(s: CortexLedger7885.Stat, positions: Int): Boolean =
        s.n >= MIN_N && positions >= MIN_POSITIONS && s.mean() - se(s) > PROOF_PCT

    /** Pure: EXIT_BAR_V1 sell proof. */
    fun sellProven(s: CortexLedger7885.Stat, positions: Int): Boolean =
        s.n >= MIN_N && positions >= MIN_POSITIONS && s.mean() + se(s) < -PROOF_PCT

    // ── position voters (index-aligned with the token registry after it) ──
    private val POS_IDS = listOf("POS_PNL_PCT", "POS_PEAK_PCT", "POS_GIVEBACK_PTS", "POS_HOLD_MIN", "POS_PARTIAL_SOLD_PCT")
    private val POS_EDGES = listOf(
        doubleArrayOf(-10.0, -3.0, 0.0, 5.0, 20.0, 50.0),
        doubleArrayOf(5.0, 20.0, 50.0, 100.0, 300.0),
        doubleArrayOf(2.0, 5.0, 15.0, 30.0, 60.0),
        doubleArrayOf(5.0, 15.0, 45.0, 120.0, 360.0),
        doubleArrayOf(1.0, 30.0, 60.0),
    )

    class Read(
        val positionId: String,
        val mint: String,
        val lane: String,
        val runner: Boolean,
        val ids: List<String>,
        val edges: List<DoubleArray>,
        val raws: DoubleArray,
        val fused: CortexLedger7885.Fused,
        val bucket: Bucket,
        val px: Double,
        val atMs: Long,
        val regime: String = "",
    )

    private class Book {
        val byBucket = Array(Bucket.values().size) { CortexLedger7885.labelStat8187() }  // V5.0.8187 — robust read (fwd label = its own gross)
        val positions = Array(Bucket.values().size) { LinkedHashSet<String>() }
    }

    private val ledger = CortexLedger7885()
    /** V5.0.8309 — census: exit seat ledger + lane books (learning: counted, never trimmed). */
    fun ledgerBytes8309(): Long = ledger.estBytes8309().coerceAtLeast(0L) + (try { books.size + truthBooks8153.size } catch (_: Throwable) { 0 }) * 1_200L
    private val calibration = CortexCalibration7901()
    private val books = HashMap<String, Book>()
    private val latest = ConcurrentHashMap<String, Read>()        // positionId
    private val pending = ConcurrentHashMap<String, Read>()       // positionId|atMs
    private val lastSample = ConcurrentHashMap<String, Long>()
    private val holdVetoSince = ConcurrentHashMap<String, Long>()
    private val offMarks = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val graded = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastFetchMs = 0L
    @Volatile private var loaded = false

    private fun inc(k: String) { counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet() }

    private fun laneOf(ts: TokenState): String {
        val raw = ts.position.tradingMode.trim().uppercase()
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(raw).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) raw.ifBlank { "UNKNOWN" } else c
    }

    private fun posKey(ts: TokenState): String = ts.position.positionId.ifBlank { "${ts.mint}|${ts.position.entryTime}" }

    /** Called per open position per tick (BotService.planTickRead7739); samples every 3 min. */
    fun observe(ts: TokenState, pnlPct: Double, peakPct: Double, nowMs: Long = System.currentTimeMillis()) {
        if (!ts.position.isOpen || !pnlPct.isFinite()) return
        val key = posKey(ts)
        val last = lastSample[key] ?: 0L
        if (nowMs - last < SAMPLE_EVERY_MS) return
        // V5.0.8187 — a position priced through the canonical mark registry (stale lastPriceUpdate) was never sampled
        // (5.0.8175: exit cortex pending=0 with open positions); the forward label's mark chain prices it instead.
        val px = samplePx8187(ts.lastPrice, ts.lastPriceUpdate, nowMs)
            ?: (try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.markFor7948(ts.mint, { null }, nowMs) } catch (_: Throwable) { null })
                ?.takeIf { it.isFinite() && it > 0.0 }?.also { inc("SAMPLE_PRICED_BY_MARK_CHAIN_8187") }
            ?: run { inc("SAMPLE_SKIPPED_NO_MARK_8187"); return }
        lastSample[key] = nowMs
        ensureLoaded()
        val lane = laneOf(ts)
        // V5.0.7955 — a runner setup (learned exit profile) is graded on the runner horizon in any lane; a pop-and-fade one never.
        val runner = try {
            com.lifecyclebot.engine.ExitProfile7955.runnerExits7955(
                com.lifecyclebot.engine.ExitProfile7955.planFor7955(ts, nowMs), com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane),
            )
        } catch (_: Throwable) { false }
        val tokenRaws = CortexVoters7885.readAll(ts, lane, nowMs)
        val heldMin = if (ts.position.entryTime > 0L) (nowMs - ts.position.entryTime) / 60_000.0 else Double.NaN
        val posRaws = doubleArrayOf(pnlPct, peakPct, (peakPct - pnlPct).coerceAtLeast(0.0), heldMin, ts.position.partialSoldPct)
        val ids = POS_IDS + CortexVoters7885.IDS
        val edges = POS_EDGES + CortexVoters7885.EDGES
        val raws = DoubleArray(ids.size) { i -> if (i < posRaws.size) posRaws[i] else tokenRaws[i - posRaws.size] }
        val evidence = POS_IDS.map { setOf("position_state") } + CortexVoters7885.ALL.map { it.evidence }
        val votes = ids.indices.map { i -> CortexLedger7885.Vote(ids[i], edges[i], raws[i], evidence[i]) }
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" }
        val fused = synchronized(this) { ledger.fuse(lane, votes, regime) }
        // V5.0.7904 — the exit cortex reads its calibrated forward edge too.
        val calibrated = synchronized(this) { calibration.calibrate(lane, fused.edgePct, fused.laneMean) }
        val r = Read(key, ts.mint, lane, runner, ids, edges, raws, fused, bucketOf(calibrated), px, nowMs, regime)
        latest[key] = r
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > RUNNER_HORIZON_MS + GRADE_GRACE_MS }
        if (pending.size < MAX_PENDING) pending["$key|$nowMs"] = r
        if (latest.size > 2_000) latest.entries.removeIf { nowMs - it.value.atMs > READ_TTL_MS * 10 }
        if (lastSample.size > 4_000) lastSample.entries.removeIf { nowMs - it.value > RUNNER_HORIZON_MS }
        if (holdVetoSince.size > 2_000) holdVetoSince.entries.removeIf { nowMs - it.value > MAX_HOLD_VETO_MS * 4 }
        inc("SAMPLED_${r.bucket.name}")
        dirty8091 = true
        persistMaybe8091(nowMs)
    }

    /** Pure (V5.0.8187): the token state's own price when it is fresh enough to sample a position on, else null. */
    fun samplePx8187(lastPrice: Double, lastPriceUpdateMs: Long, nowMs: Long): Double? =
        lastPrice.takeIf { it.isFinite() && it > 0.0 && lastPriceUpdateMs > 0L && nowMs - lastPriceUpdateMs in 0L..MARK_MAX_AGE_MS }

    /** Called from ExitRegret7752.tick with the loop's fresh-price closure: grades due samples. */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        ensureLoaded()
        if (truthPending8153.isNotEmpty()) gradeTruth8153(priceFor, nowMs)  // V5.0.8153
        if (pending.isEmpty()) return
        val unpriced = ArrayList<String>()
        for ((k, r) in pending.entries.toList()) {
            val due = r.atMs + if (r.runner) RUNNER_HORIZON_MS else HORIZON_MS
            if (nowMs < due) continue
            val px = (try { priceFor(r.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 }
                ?: registryMark(r.mint, nowMs)
                ?: offMarks[r.mint]?.takeIf { nowMs - it.second in 0L..MARK_MAX_AGE_MS }?.first
            if (px == null) {
                if (nowMs - due > GRADE_GRACE_MS) { pending.remove(k); dirty8091 = true; inc("LOST_MARK") } else unpriced.add(r.mint)
                continue
            }
            pending.remove(k)
            dirty8091 = true
            if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.basisSuspect7738(r.px, px)) { inc("BASIS_SUSPECT"); continue }
            val fwd = (px / r.px - 1.0) * 100.0
            grade8082(r, fwd)
        }
        if (unpriced.isNotEmpty()) fetch(unpriced, nowMs)
        persistMaybe8091(nowMs)
    }

    /** Realised economics are a separate target. A close cannot supply a future HOLD mark. */
    fun onCanonicalClose8082(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        ensureLoaded()
        if (!env.terminal || env.positionId.isBlank()) return false
        if (!env.learningEligible || !env.realizedReturnPct.isFinite() || !env.realizedPnlSol.isFinite() ||
            !com.lifecyclebot.engine.truth.ExactStrategyPerformance7429.terminalEconomicsEligible8081(env.mode, env.proofState)) return false
        val samples = pending.values.filter { it.positionId == env.positionId }
        val record = org.json.JSONObject().put("positionId", env.positionId).put("eventId", env.economicEventId)
            .put("mode", env.mode).put("atMs", env.atMs).put("proof", env.proofState)
            .put("target", "ACTUAL_WHOLE_POSITION_REALIZED")
            .put("netSol", env.realizedPnlSol).put("returnPct", env.realizedReturnPct)
            .put("sampleTimes", org.json.JSONArray(samples.map { it.atMs })).toString()
        val key = "cortex_exit_actual_8090_${env.mode}_${env.positionId}"
        return when (LearningPersistence.commitOnce8090("${key}_receipt", mapOf(key to record))) {
            LearningPersistence.Commit8090.RETRY -> false
            LearningPersistence.Commit8090.ALREADY_COMMITTED -> true
            LearningPersistence.Commit8090.COMMITTED -> {
                inc("ACTUAL_REALIZED_RECORDED_8090")
                // Keep pending samples until timestamped future observations resolve their own horizons.
                true
            }
        }
    }

    // ── V5.0.8153 §EXIT_TRUTH_FROM_PROCEEDS (LearningExitTruth8153) ──
    //
    // At the canonical close (CanonicalFinalizedTradeBus6464.publish, before TailBag8034 retires the fallback-proceeds rows)
    // the position's actual sell legs — canonical net proceeds per leg plus the confirmed legacy-fallback proceeds the
    // canonical book does not carry — are frozen with its cost basis. One exit horizon after the close the sold quantity
    // is priced again: banked return, regret (what the sold quantity went on to be worth) and tail cuts per mode and lane.
    // A lane whose exits are proven to cut moves that kept going loses the Cortex's own SELL authority (sellReason).
    // Never mixed into the forward-mark ledger (FUTURE_MARK_RETURN_V1); paper and live books are kept apart.
    private const val TRUTH_MAX_PENDING_8153 = 400
    private class TruthPending8153(val mint: String, val lane: String, val mode: String, val costSol: Double,
                                   val slices: List<LearningExitTruth8153.Slice8153>, val dueMs: Long)
    private val truthPending8153 = ConcurrentHashMap<String, TruthPending8153>()   // positionId
    private val truthBooks8153 = HashMap<String, LearningExitTruth8153.Book8153>()  // MODE|lane

    // V5.0.8171: no later observation can be recovered by a real-time tick
    // for a historical close replayed long after its grading horizon.
    // The 8169 report showed 970/973 exit grades had NO_LATER_MARK; most
    // were replayed historical finality, not measured exit regret.
    fun recentClose8171(closeAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        closeAtMs > 0L && nowMs >= closeAtMs && nowMs - closeAtMs <= maxAgeMs


    /** CanonicalFinalizedTradeBus6464.publish: freeze a closed position's actual proceeds for its exit grade. */
    fun onClosePartials8153(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!env.terminal || env.positionId.isBlank() || env.mint.isBlank() || !env.learningEligible) return false
        if (!com.lifecyclebot.engine.truth.ExactStrategyPerformance7429.terminalEconomicsEligible8081(env.mode, env.proofState)) return false
        val mode = if (env.mode.equals("live", true)) "LIVE" else if (env.mode.equals("paper", true)) "PAPER" else return false
        val legs = try {
            com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot().asSequence()
                .filterIsInstance<com.lifecyclebot.engine.truth.EconomicEventSchema6464.Sell>()
                .filter { it.positionId == env.positionId && it.mode.equals(env.mode, true) }
                .map { LearningExitTruth8153.SellLeg8153(it.atMs, it.soldQty.toDouble(), it.soldQty.add(it.remainingQty).toDouble(),
                    it.netProceedsSol, it.allocatedCostBasisSol, it.remainingCostBasisSol, it.exitPriceUsd) }
                .toList()
        } catch (_: Throwable) { emptyList() }
        // Confirmed fallback fills the canonical book does not carry (read before TailBag8034 retires the rows).
        val fallback = if (mode == "LIVE") try { com.lifecyclebot.engine.RecoveryFallbackLedger8141.confirmedFallbackSol8141(env.positionId) } catch (_: Throwable) { 0.0 } else 0.0
        val built = LearningExitTruth8153.fromLegs8153(legs, fallback) ?: run { inc("EXIT_TRUTH_NO_LEGS_8153"); return false }
        val lane = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(env.lane).uppercase().ifBlank { env.lane.uppercase() } } catch (_: Throwable) { env.lane.uppercase() }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        val horizon8171 = if (runner) RUNNER_HORIZON_MS else HORIZON_MS
        if (!recentClose8171(env.atMs, System.currentTimeMillis(), horizon8171 + GRADE_GRACE_MS)) {
            inc("EXIT_TRUTH_HISTORICAL_NO_MARK_SKIPPED_8171")
            return false
        }
        if (truthPending8153.size >= TRUTH_MAX_PENDING_8153) { inc("EXIT_TRUTH_FULL_8153"); return false }
        truthPending8153[env.positionId] = TruthPending8153(env.mint, lane, mode, built.first, built.second,
            env.atMs + horizon8171)
        inc("EXIT_TRUTH_FROZEN_8153_$mode")
        return true
    }

    private fun gradeTruth8153(priceFor: (String) -> Double?, nowMs: Long) {
        val unpriced = ArrayList<String>()
        for ((pid, t) in truthPending8153.entries.toList()) {
            if (nowMs < t.dueMs) continue
            val px = (try { priceFor(t.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 }
                ?: registryMark(t.mint, nowMs)
                ?: offMarks[t.mint]?.takeIf { nowMs - it.second in 0L..MARK_MAX_AGE_MS }?.first
            if (px == null) {
                if (nowMs - t.dueMs > GRADE_GRACE_MS) {
                    // No later mark: the banked part is still true; regret stays unknown.
                    truthPending8153.remove(pid)
                    LearningExitTruth8153.grade8153(t.costSol, t.slices, 0.0, Double.NaN)?.let { bookTruth8153(t, it) }
                    inc("EXIT_TRUTH_NO_LATER_MARK_8153")
                } else unpriced.add(t.mint)
                continue
            }
            truthPending8153.remove(pid)
            LearningExitTruth8153.grade8153(t.costSol, t.slices, 0.0, px)?.let { bookTruth8153(t, it) }
        }
        if (unpriced.isNotEmpty()) fetch(unpriced, nowMs)
    }

    private fun bookTruth8153(t: TruthPending8153, g: LearningExitTruth8153.Grade8153) {
        synchronized(this) {
            if (truthBooks8153.size >= 200 && !truthBooks8153.containsKey("${t.mode}|${t.lane}")) return
            truthBooks8153.getOrPut("${t.mode}|${t.lane}") { LearningExitTruth8153.Book8153() }.add(g)
        }
        dirty8091 = true
        inc(if (g.tailCut) "EXIT_TRUTH_TAIL_CUT_8153" else "EXIT_TRUTH_GRADED_8153")
    }

    /** V5.0.8153 — this lane's exits (this account mode) measurably cut moves that kept going. */
    private fun tailCutting8153(lane: String): Boolean {
        val mode = try { if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }
        return synchronized(this) { truthBooks8153["$mode|$lane"]?.tailCutProven8153() == true }
    }

    private fun grade8082(r: Read, fwd: Double) {
        run {
            synchronized(this) {
                ledger.grade(r.lane, r.ids, r.edges, r.raws, fwd, fwd, r.regime)
                calibration.learn(r.lane, r.fused.edgePct, r.fused.laneMean, fwd.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX))  // V5.0.8322 — raw
                val b = books.getOrPut(r.lane) { Book() }
                for (st in b.byBucket) st.scale(BOOK_DECAY)
                b.byBucket[r.bucket.ordinal].add(fwd.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), fwd >= CortexLedger7885.RUNNER_GROSS_PCT)  // V5.0.8322 — raw
                val set = b.positions[r.bucket.ordinal]
                set.add(r.positionId)
                if (set.size > 400) set.remove(set.first())
            }
            dirty8091 = true
            graded.incrementAndGet()
            if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
        }
    }

    private fun registryMark(mint: String, nowMs: Long): Double? = try {
        val m = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(mint)
        if (m == null || nowMs - m.timestampMs !in 0L..MARK_MAX_AGE_MS) null else m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
    } catch (_: Throwable) { null }

    private fun fetch(mints: List<String>, nowMs: Long) {
        if (nowMs - lastFetchMs < FETCH_GAP_MS || !inFlight.compareAndSet(false, true)) return
        lastFetchMs = nowMs
        try {
            Thread({
                try {
                    val got = com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(mints.distinct().take(50))
                    val at = System.currentTimeMillis()
                    for ((m, p) in got) offMarks[m] = p to at
                    offMarks.entries.removeIf { at - it.value.second > MARK_MAX_AGE_MS }
                } catch (_: Throwable) {
                } finally { inFlight.set(false) }
            }, "cortex-exit-7897").apply { isDaemon = true }.start()
        } catch (_: Throwable) { inFlight.set(false) }
    }

    private fun proven(lane: String, bucket: Bucket): Boolean {
        synchronized(this) {
            val b = books[lane] ?: return false
            val s = b.byBucket[bucket.ordinal]
            val p = b.positions[bucket.ordinal].size
            if (calibration.suspends8322(lane, 0.5)) { inc("SUSPENDED_INCONSISTENT_$lane"); return false }  // V5.0.8322 — grade floor
            return if (bucket == Bucket.HOLD_STRONG) holdProven(s, p) else sellProven(s, p)
        }
    }

    private fun freshRead(ts: TokenState, nowMs: Long): Read? =
        latest[posKey(ts)]?.takeIf { nowMs - it.atMs <= READ_TTL_MS }

    /**
     * Executor.freshExitReason7835 (every full sell, paper and live): true holds
     * this ORDINARY exit of a position in profit while the Cortex's proven read
     * says holding pays. Emergency classes (capital preservation, structural,
     * hard SL) and any position at or below break-even are never held.
     */
    fun holdVeto(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            if (reason.startsWith("CORTEX_EXIT_7897")) return false
            val ru = reason.uppercase()
            if (OPERATOR_OR_SHUTDOWN.any { ru.contains(it) }) return false
            if (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason)) return false
            if (com.lifecyclebot.engine.RunnerExitContract8136.emergency8136(reason)) return false  // V5.0.8136 — tier 1 is never held
            val entry = ts.position.entryPrice
            val px = ts.lastPrice
            if (entry <= 0.0 || px <= 0.0 || nowMs - ts.lastPriceUpdate > MARK_MAX_AGE_MS) return false
            // V5.0.7925 — break-even is NET of the round trip (fees, priority, slippage,
            // impact): +2% gross on a 4% trip is a loss, and is never held.
            val costPct7925 = try {
                val sizeSol = ts.position.costSol
                val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                com.lifecyclebot.engine.truth.FieldManual7715.roundTripCostPct7766(
                    sizeSol, if (solUsd.isFinite() && solUsd > 0.0) sizeSol * solUsd else 0.0, com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts),
                ).takeIf { it.isFinite() } ?: 0.0
            } catch (_: Throwable) { 0.0 }
            if ((px / entry - 1.0) * 100.0 <= costPct7925) return false
            val r = freshRead(ts, nowMs) ?: return false
            if (r.bucket != Bucket.HOLD_STRONG) return false
            val key = posKey(ts)
            // V5.0.7955 — the position's learned exit profile: a pop-and-fade setup is never held
            // past its own exits; a runner setup may be held up to its learned max hold (15..120 min).
            val plan7955 = try { com.lifecyclebot.engine.ExitProfile7955.planFor7955(ts, nowMs) } catch (_: Throwable) { null }
            if (plan7955?.popFade == true) { inc("PROFILE_POPFADE_NOT_HELD_7955"); return false }
            if (!proven(r.lane, Bucket.HOLD_STRONG)) { inc("SHADOW_HOLD"); return false }
            val since = holdVetoSince.getOrPut(key) { nowMs }
            val cap7955 = plan7955?.takeIf { it.runner }?.maxHoldMs?.coerceIn(15L * 60_000L, 2L * MAX_HOLD_VETO_MS) ?: MAX_HOLD_VETO_MS
            if (nowMs - since > cap7955) { inc("HOLD_CAP_REACHED"); return false }
            inc("HELD_${r.lane}")
            try {
                PipelineHealthCollector.labelInc("CORTEX_EXIT_7897_HELD_${r.lane}")
                if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("CORTEX_EXIT_HOLD_7897", key)) {
                    ForensicLogger.lifecycle("CORTEX_EXIT_7897_HELD",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${r.lane} reason=${reason.take(60)} fwd=${"%.2f".format(r.fused.edgePct)} top=${r.fused.top.joinToString(",")}")
                }
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    /**
     * BotService.planTickRead7739: the Cortex's own exit when its proven read
     * says the next stretch loses. Null = no opinion / not proven.
     */
    fun sellReason(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        return try {
            val r = freshRead(ts, nowMs) ?: return null
            if (r.bucket != Bucket.SELL_STRONG) return null
            if (!proven(r.lane, Bucket.SELL_STRONG)) { inc("SHADOW_SELL"); return null }
            if (tailCutting8153(r.lane)) { inc("SUSPENDED_TAIL_CUT_8153_${r.lane}"); return null }  // V5.0.8153
            inc("SOLD_${r.lane}")
            "CORTEX_EXIT_7897_${r.lane}_FWD${r.fused.edgePct.toInt()}PCT"
        } catch (_: Throwable) { null }
    }

    // ── persistence ──

    private fun ensureLoaded() {
        if (loaded) return
        // V5.0.7930 — never latch "loaded" before the store opens: an early read came back
        // empty and the next save overwrote the real ledgers with it.
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val current = LearningPersistence.load(PERSIST_KEY)
                if (LearningPersistence.readFailed8086(PERSIST_KEY)) { loaded = false; return }
                if (current == null) {
                    val legacy = LearningPersistence.load(LEGACY_PERSIST_KEY_8091)
                    if (LearningPersistence.readFailed8086(LEGACY_PERSIST_KEY_8091)) { loaded = false; return }
                    legacy?.let { old ->
                        org.json.JSONObject(old).optJSONArray("pending7960")?.let {
                            decodePending7960(it, System.currentTimeMillis())
                        }
                        inc("LEGACY_MIXED_TARGET_RETAINED_8091")
                    }
                    dirty8091 = true
                    return
                }
                val o = org.json.JSONObject(current)
                require(forwardTarget8091(o)) { "Unexpected exit training target" }
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("calibration")?.let { calibration.decode(it) }
                o.optJSONArray("pending7960")?.let { decodePending7960(it, System.currentTimeMillis()) }
                o.optJSONObject("truth8153")?.let { j ->
                    for (k in j.keys()) LearningExitTruth8153.Book8153().also { b -> if (b.decode(j.optString(k))) truthBooks8153[k] = b }
                }
                o.optJSONObject("books")?.let { j ->
                    for (k in j.keys()) {
                        val f = j.optString(k).split('|')
                        if (f.size != Bucket.values().size * 2) continue
                        val b = Book()
                        for (i in Bucket.values().indices) {
                            b.byBucket[i].decode(f[i])
                            f[Bucket.values().size + i].split(',').filter { it.isNotBlank() }.forEach { b.positions[i].add(it) }
                        }
                        books[k] = b
                    }
                }
            } catch (e: Throwable) { LearningPersistence.decodeFailed8086(PERSIST_KEY, e) } finally {
                // V5.0.8086 — the row is on disk but would not read: stay unloaded (persist() is gated) and retry.
                if (LearningPersistence.readFailed8086(PERSIST_KEY)) loaded = false
            }
        }
    }

    /**
     * V5.0.7960 — pending samples survive a restart. A sample grades 30 min (runner 120 min)
     * after it was read; 5.0.7958 restarted with every sample dropped and read pending=41
     * graded=0, so builds installed faster than the horizon never graded an exit read.
     * Voter ids are stored with their raws; edges are re-read from the current voter set
     * and a voter that no longer exists is dropped from the sample.
     */
    private fun encodePending7960(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for ((k, r) in pending.entries.sortedByDescending { it.value.atMs }.take(PERSIST_PENDING_7960)) {
            arr.put(org.json.JSONObject()
                .put("k", k).put("pid", r.positionId).put("mint", r.mint).put("lane", r.lane).put("runner", r.runner)
                .put("ids", org.json.JSONArray(r.ids)).put("raws", org.json.JSONArray(r.raws.map { if (it.isFinite()) it else org.json.JSONObject.NULL }))
                .put("edge", r.fused.edgePct.takeIf { it.isFinite() } ?: 0.0).put("mean", r.fused.laneMean.takeIf { it.isFinite() } ?: 0.0).put("bucket", r.bucket.name)
                .put("px", r.px).put("at", r.atMs).put("regime", r.regime))
        }
        return arr
    }

    private fun decodePending7960(arr: org.json.JSONArray, nowMs: Long) {
        val allIds = POS_IDS + CortexVoters7885.IDS
        val allEdges = POS_EDGES + CortexVoters7885.EDGES
        val edgeById = allIds.indices.associate { allIds[it] to allEdges[it] }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at", 0L)
            if (at <= 0L || nowMs - at > RUNNER_HORIZON_MS + GRADE_GRACE_MS) continue
            val idsArr = o.optJSONArray("ids") ?: continue
            val rawsArr = o.optJSONArray("raws") ?: continue
            val ids = ArrayList<String>(); val edges = ArrayList<DoubleArray>(); val raws = ArrayList<Double>()
            for (j in 0 until minOf(idsArr.length(), rawsArr.length())) {
                val id = idsArr.optString(j); val e = edgeById[id] ?: continue
                ids += id; edges += e; raws += rawsArr.optDouble(j, Double.NaN)
            }
            val bucket = try { Bucket.valueOf(o.optString("bucket")) } catch (_: Throwable) { continue }
            val px = o.optDouble("px", 0.0)
            if (ids.isEmpty() || !(px > 0.0)) continue
            val fused = CortexLedger7885.Fused(o.optDouble("edge", 0.0), 0.0, o.optDouble("mean", 0.0), 0.0, 0.0, 0.0, 0.0, emptyList())
            pending[o.optString("k")] = Read(o.optString("pid"), o.optString("mint"), o.optString("lane"), o.optBoolean("runner"),
                ids, edges, raws.toDoubleArray(), fused, bucket, px, at, o.optString("regime"))
        }
        if (arr.length() > 0) inc("PENDING_RESTORED_7960")
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    fun persistNow7930() {
        if (!loaded) return
        persist()
    }

    internal fun forwardTarget8091(state: org.json.JSONObject): Boolean = state.optString("target8091") == TARGET_8091

    private fun persistMaybe8091(nowMs: Long) {
        if (dirty8091 && nowMs - lastPersistMs8091 >= 30_000L) persist()
    }

    @Synchronized private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                org.json.JSONObject().put("target8091", TARGET_8091).put("ledger", ledger.encode()).put("calibration", calibration.encode()).put("pending7960", encodePending7960())
                    .put("truth8153", org.json.JSONObject().also { j -> truthBooks8153.forEach { (k, b) -> j.put(k, b.encode()) } })
                    .put("books", org.json.JSONObject().also { j ->
                    books.forEach { (k, b) ->
                        j.put(k, (b.byBucket.map { it.encode() } + b.positions.map { s -> s.toList().takeLast(200).joinToString(",") { it.replace(",", "").replace("|", "") } }).joinToString("|"))
                    }
                }).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
            lastPersistMs8091 = System.currentTimeMillis()
            dirty8091 = false
        } catch (_: Throwable) {}
    }

    private fun fmt(s: CortexLedger7885.Stat): String = CortexLedger7885.fmt8187(s)  // V5.0.8187 — robust, raw beside

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val seated = ledger.seats.entries.map { it.key to it.value.authority() }.filter { it.second > 0.0 }.sortedByDescending { it.second }
            "bar=EXIT_BAR_V1 target=$TARGET_8091 legacy=retained_separate horizon=30m(runner 120m) sampled/1m pending=${pending.size} graded=${graded.get()} seated=${seated.size} slope=${calibration.line()}\n" +
                "      actions: ${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      seated: ${seated.take(10).joinToString(" · ") { (k, a) -> "$k a=${"%.2f".format(a)}" }.ifBlank { "none yet" }}\n" +
                "      lanes: ${books.entries.take(8).joinToString(" · ") { (lane, b) ->
                    "$lane sell=${fmt(b.byBucket[0])}/${b.positions[0].size}p neutral=${fmt(b.byBucket[1])} hold=${fmt(b.byBucket[2])}/${b.positions[2].size}p " +
                        "holdAuth=${holdProven(b.byBucket[2], b.positions[2].size)} sellAuth=${sellProven(b.byBucket[0], b.positions[0].size)}"
                }.ifBlank { "no graded samples yet" }}" +
                "\n      grades8136: ${dueLine8136(System.currentTimeMillis())}" +
                "\n      exitTruth8153 (actual proceeds, regret one horizon after the close): pending=${truthPending8153.size} " +
                truthBooks8153.entries.sortedBy { it.key }.take(8).joinToString(" · ") { (k, b) -> "$k ${b.line()}" }.ifBlank { "no graded closes yet" }
        }
    }

    /**
     * V5.0.8136 — a pending read is not a broken grader: it grades 30 min (runner 120 min) after it was read. Split the
     * queue into not-yet-due, due-inside-grace and overdue (past grace, mark never arrived), so only the last is a fault.
     */
    internal fun dueSplit8136(reads: Collection<Pair<Long, Boolean>>, nowMs: Long): Triple<Int, Int, Int> {
        var notDue = 0; var inGrace = 0; var overdue = 0
        for ((atMs, runner) in reads) {
            val due = atMs + if (runner) RUNNER_HORIZON_MS else HORIZON_MS
            when {
                nowMs < due -> notDue++
                nowMs - due <= GRADE_GRACE_MS -> inGrace++
                else -> overdue++
            }
        }
        return Triple(notDue, inGrace, overdue)
    }

    private fun dueLine8136(nowMs: Long): String {
        val (notDue, inGrace, overdue) = dueSplit8136(pending.values.map { it.atMs to it.runner }, nowMs)
        return "notYetDue=$notDue dueInGrace=$inGrace overdue=$overdue graded=${graded.get()}"
    }
}
