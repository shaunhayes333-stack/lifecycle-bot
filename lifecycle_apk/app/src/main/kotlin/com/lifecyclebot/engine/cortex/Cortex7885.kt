package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7885 — AATE Cortex runtime (plan v1 §2, v2 §E builds 4-10).
 *
 *   snapshot   one frozen read per (mint, lane) decision: every voter's raw
 *              opinion + feature provenance (OBSERVED / UNKNOWN / STALE)
 *   ledger     every voter graded prequentially on forward labels (net of
 *              cost, admitted AND refused candidates) — authority is earned
 *              per lane and decays when skill does
 *   fusion     authority x independence pooling around the lane prior
 *   rules      a Constitution that can only refuse, every refusal named
 *   authority  the Cortex refuses in PAPER once its REFUSE record is proven
 *              (bar V1, n>=20) and in LIVE at n>=40; it overrules a live
 *              edge-gate refusal only when its STRONG record is proven.
 *              Until then it shadows: what it would have done is counted.
 *
 * Horizon: runner lanes are graded on the 240-minute label (their edge is the
 * tail), every other lane on the 60-minute label.
 *
 * Nothing here sizes a trade. Every entry point fails open (null / false).
 */
object Cortex7885 {
    private const val ASSESS_TTL_MS = 20_000L
    private const val PENDING_TTL_MS = 5L * 60L * 60_000L
    private const val MAX_PENDING = 8_000
    private const val MAX_ASSESS_CACHE = 4_000
    private const val PERSIST_KEY = "CORTEX_7885"
    private const val PERSIST_EVERY = 40
    private const val MARK_STALE_MS = 180_000L

    class Assessment(
        val lane: String,
        val runnerLane: Boolean,
        val raws: DoubleArray,
        val ids: List<String>,
        val edges: List<DoubleArray>,
        val regime: String,
        val fused: CortexLedger7885.Fused,
        val calibratedEdge: Double,
        val bucket: CortexScoreboard7885.Bucket,
        val unknownFeatures: Int,
        val staleMark: Boolean,
        val atMs: Long,
    )

    private class Pending(val a: Assessment, val legacyAdmitted: Boolean, val atMs: Long, val vetoRule: String? = null)

    private val ledger = CortexLedger7885()
    private val board = CortexScoreboard7885()
    private val calibration = CortexCalibration7901()
    private val assessCache = ConcurrentHashMap<String, Assessment>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val voterFailures = ConcurrentHashMap<String, AtomicLong>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val assessed = AtomicLong(0)
    private val graded = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    private val assessNanos = AtomicLong(0)
    @Volatile private var loaded = false

    private fun inc(k: String) { counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet() }

    private fun canon(lane: String): String {
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) lane.trim().uppercase() else c
    }

    private fun credits7888(): Double = try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.creditsToday7888() } catch (_: Throwable) { 0.0 }

    private fun fmtStat(s: CortexLedger7885.Stat): String = if (s.n < 1.0) "-" else "n${s.n.toInt()}/${"%+.1f".format(s.mean())}%"

    private fun isRunner(lane: String): Boolean =
        try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }

    /** Snapshot + votes + fusion for (ts, lane); cached briefly so the gate and the labeler see one read. */
    fun assess(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Assessment? {
        if (ts.mint.isBlank() || laneRaw.isBlank()) return null
        val lane = canon(laneRaw)
        val key = "${ts.mint}|$lane"
        assessCache[key]?.let { if (nowMs - it.atMs <= ASSESS_TTL_MS) return it }
        ensureLoaded()
        val t0 = System.nanoTime()
        val staticRaws = CortexVoters7885.readAll(ts, lane, nowMs, voterFailures)
        val runner = isRunner(lane)
        val voters: List<CortexVoters7885.Voter> = CortexVoters7885.ALL
        // V5.0.7895 — dynamic voters: every V3 UnifiedScorer module recorded for this mint.
        val dyn = (try { CortexVoters7885.dynamicVotes(ts) } catch (_: Throwable) { emptyList() }) +
            (try { synchronized(this) { crossVotes(lane, staticRaws) } } catch (_: Throwable) { emptyList() })
        val ids = CortexVoters7885.IDS + dyn.map { it.voterId }
        val edges = CortexVoters7885.EDGES + dyn.map { it.edges }
        val raws = DoubleArray(ids.size) { i -> if (i < staticRaws.size) staticRaws[i] else dyn[i - staticRaws.size].raw }
        val votes = voters.mapIndexed { i, v -> CortexLedger7885.Vote(v.id, v.edges, staticRaws[i], v.evidence) } + dyn
        // V5.0.7898 — Cortex v4: the decision is read in the current market regime.
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" }
        val fused = synchronized(this) { ledger.fuse(lane, votes, regime) }
        // V5.0.7901 — Cortex v7: buckets read the calibrated edge, not the raw one.
        val calibrated = synchronized(this) { calibration.calibrate(lane, fused.edgePct, fused.laneMean) }
        val bucket = CortexScoreboard7885.bucketOf(calibrated, fused.runnerRate, runner)
        val priceAge = if (ts.lastPriceUpdate > 0L) nowMs - ts.lastPriceUpdate else Long.MAX_VALUE
        val a = Assessment(lane, runner, raws, ids, edges, regime, fused, calibrated, bucket, raws.count { !it.isFinite() }, priceAge > MARK_STALE_MS, nowMs)
        assessNanos.addAndGet(System.nanoTime() - t0)
        assessed.incrementAndGet()
        if (assessCache.size >= MAX_ASSESS_CACHE) assessCache.entries.removeIf { nowMs - it.value.atMs > ASSESS_TTL_MS }
        assessCache[key] = a
        return a
    }

    /** ForwardReturnLabeler7731.observe: one graded decision opens here (1:1 with its observation). */
    fun capture(ts: TokenState, labelLane: String, admitted: Boolean, nowMs: Long = System.currentTimeMillis(), reason: String? = null) {
        if (labelLane.startsWith("PLANWAIT_") || labelLane.startsWith("PLANADMIT_")) return
        val a = assess(ts, labelLane, nowMs) ?: return
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > PENDING_TTL_MS }
        if (pending.size >= MAX_PENDING) { inc("PENDING_FULL"); return }
        val veto = if (admitted || reason.isNullOrBlank()) null else vetoRuleOf(reason)
        pending["${ts.mint}|${labelLane.trim().uppercase()}"] = Pending(a, admitted, nowMs, veto)
        // V5.0.7900 — Cortex v6: the same decision opens a 5-minute timing label.
        try { CortexTiming7900.capture(ts, a, nowMs) } catch (_: Throwable) {}
        inc(if (a.bucket == CortexScoreboard7885.Bucket.REFUSE) "SEEN_REFUSE" else if (a.bucket == CortexScoreboard7885.Bucket.STRONG) "SEEN_STRONG" else "SEEN_NEUTRAL")
    }

    /** ForwardReturnLabeler7731.tick: a horizon label booked for (mint, labelLane). */
    fun onLabel(mint: String, labelLane: String, horizonMin: Int, netPct: Double, grossPct: Double) {
        val key = "$mint|${labelLane.trim().uppercase()}"
        val p = pending[key] ?: return
        val want = if (p.a.runnerLane) 240 else 60
        if (horizonMin != want) return
        pending.remove(key)
        if (!netPct.isFinite()) return
        synchronized(this) {
            ledger.grade(p.a.lane, p.a.ids, p.a.edges, p.a.raws, netPct, grossPct, p.a.regime)
            board.record(p.a.lane, p.a.bucket, p.legacyAdmitted, netPct, grossPct)
            calibration.learn(p.a.lane, p.a.fused.edgePct, p.a.fused.laneMean, netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX))
            p.vetoRule?.let { r ->
                vetoBook.getOrPut(r) { CortexLedger7885.Stat() }
                    .add(netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), grossPct >= CortexLedger7885.RUNNER_GROSS_PCT)
            }
        }
        graded.incrementAndGet()
        if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
    }

    // ── veto audit (Constitution Phase 3): every existing refusal, graded ──
    //
    // The stack's scattered vetoes each name a reason; the forward label of the
    // candidate they refused shows whether that rule refused losers or winners.
    // A rule whose refused set is PROVEN positive (n >= 40, mean - SE > +2%) is
    // flagged as refusing winners. Nothing is relaxed automatically: this is the
    // evidence for moving each veto into the Constitution or retiring it.
    private val vetoBook = HashMap<String, CortexLedger7885.Stat>()

    /** Pure: a stable rule id from a refusal reason (leading upper-case words, max 4). */
    fun vetoRuleOf(reason: String): String =
        reason.trim().split('_', ':', ' ').filter { w -> w.isNotEmpty() && !w.all { it.isDigit() } }
            .takeWhile { w -> w.all { it.isUpperCase() || it.isDigit() } }
            .take(4).joinToString("_").ifBlank { "UNNAMED" }

    // ── Constitution (refuse-only; every refusal names its rule) ──

    /** Pure rule check over an assessment. Returns the rule id that refuses, or null. */
    private fun constitutionRefusal(a: Assessment, ts: TokenState, paper: Boolean, refusalProven: Boolean): String? {
        if (!paper && ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return "C1_HARD_SAFETY"
        if (!paper && a.staleMark) return "C2_MARK_STALE"
        if (a.bucket == CortexScoreboard7885.Bucket.REFUSE && refusalProven) return "C3_PROVEN_NEGATIVE_EDGE"
        if (!paper && a.bucket == CortexScoreboard7885.Bucket.NEUTRAL && lastSlot(ts)) {
            if (synchronized(this) { board.slotPriorityProven(a.lane) && consistent(a.lane) }) return "C5_SAVE_LAST_SLOT_FOR_STRONG"
            inc("SHADOW_SAVE_SLOT")
        }
        return null
    }

    // ── Cortex v9: self-consistency (V5.0.7904) ──
    //
    // A record can clear the bar while the Cortex's own predictions are badly
    // calibrated (it said +8% where +1% happened). Authority — refusal,
    // overrule, conviction sizing, slot saving — is only exercised while the
    // lane's calibration slope is at least [MIN_CONSISTENT_SLOPE]: a Cortex
    // whose deviations have stopped meaning what they say loses its say until
    // they do again. Caller holds the lock.
    private const val MIN_CONSISTENT_SLOPE = 0.5

    private fun consistent(lane: String): Boolean {
        val ok = calibration.slope(lane) >= MIN_CONSISTENT_SLOPE
        if (!ok) inc("SUSPENDED_INCONSISTENT_$lane")
        return ok
    }

    // ── Cortex v8: capital allocation (V5.0.7902) ──
    //
    // The wallet funds only a few route-minimum trades at a time. When the free
    // cash left would fund just one more, and this lane's record proves its
    // STRONG reads beat its NEUTRAL reads, a NEUTRAL candidate does not take the
    // last slot: it stays free for a STRONG one (rule C5).
    private const val ROUTE_MIN_USD = 5.0
    private const val ROUTE_MIN_FALLBACK_SOL = 0.0435

    private fun lastSlot(ts: TokenState): Boolean = try {
        val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val routeMin = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(ROUTE_MIN_USD, solUsd)
            .takeIf { it.isFinite() && it > 0.0 } ?: ROUTE_MIN_FALLBACK_SOL
        val free = com.lifecyclebot.engine.WalletCapacitySeal7868.freeCashFor(ts.mint, com.lifecyclebot.engine.BotService.status.walletSol)
        free.isFinite() && free >= routeMin && free < 2.0 * routeMin
    } catch (_: Throwable) { false }

    /**
     * Called first by LiveEdgeGate7877.liveRefusal (both modes). Non-null means
     * the Cortex refuses this entry under a named rule.
     */
    fun entryRefusal(ts: TokenState, laneRaw: String, paper: Boolean): String? {
        return try {
            val a = assess(ts, laneRaw) ?: return null
            val proven = synchronized(this) { board.refusalAuthority(a.lane, a.runnerLane, paper) && consistent(a.lane) }
            val rule = constitutionRefusal(a, ts, paper, proven)
                ?: try { CortexTiming7900.waitRefusal(a) } catch (_: Throwable) { null }
            if (rule == null) {
                if (a.bucket == CortexScoreboard7885.Bucket.REFUSE) inc(if (paper) "SHADOW_REFUSE_PAPER" else "SHADOW_REFUSE_LIVE")
                noteEntryRead(ts.mint, paper, a)
                return null
            }
            val mode = if (paper) "PAPER" else "LIVE"
            inc("REFUSED_${mode}_$rule")
            try {
                PipelineHealthCollector.labelInc("CORTEX_7885_REFUSED_${mode}_$rule")
                if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("CORTEX_7885", "${a.lane}|${ts.mint}")) {
                    ForensicLogger.lifecycle(
                        "CORTEX_7885_REFUSED",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} mode=$mode rule=$rule edge=${"%.2f".format(a.fused.edgePct)} " +
                            "runner=${"%.2f".format(a.fused.runnerRate)} effVoters=${"%.1f".format(a.fused.effectiveVoters)} top=${a.fused.top.joinToString(",")}",
                    )
                }
            } catch (_: Throwable) {}
            "CORTEX_7885_${rule}_${a.lane}"
        } catch (_: Throwable) { null }
    }

    /**
     * Called by LiveEdgeGate7877 when it would refuse a LIVE entry. True means the
     * Cortex's proven STRONG record overrules that edge refusal for this candidate.
     */
    fun overrulesEdgeRefusal(ts: TokenState, laneRaw: String, refusal: String): Boolean {
        return try {
            val a = assess(ts, laneRaw) ?: return false
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return false
            if (a.staleMark || ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
            val proven = synchronized(this) { board.overruleAuthority(a.lane) && consistent(a.lane) }
            if (!proven) { inc("SHADOW_OVERRULE_LIVE"); return false }
            inc("OVERRULED_LIVE")
            try {
                PipelineHealthCollector.labelInc("CORTEX_7885_OVERRULED_EDGE_${a.lane}")
                ForensicLogger.lifecycle(
                    "CORTEX_7885_OVERRULED_EDGE",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} edge=${"%.2f".format(a.fused.edgePct)} was=${refusal.take(60)} top=${a.fused.top.joinToString(",")}",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    // ── Cortex v5: interaction discovery ──
    //
    // Edge often lives in combinations ("strong flow AND young AND thin
    // holders"), which single-voter bins cannot see. For each lane the four
    // currently seated voters with the most authority are crossed pairwise: the
    // cross's raw value is the joint bin (bin_a x bins_b + bin_b), graded and
    // seated exactly like any voter — so a combination earns authority only by
    // out-of-sample skill beyond its parts. Crosses follow the seats: as the
    // seated set changes, new combinations are tried.
    private const val CROSS_TOP = 4

    /** Caller holds the lock. */
    private fun crossVotes(lane: String, raws: DoubleArray): List<CortexLedger7885.Vote> {
        val ids = CortexVoters7885.IDS
        val edges = CortexVoters7885.EDGES
        val evidence = CortexVoters7885.ALL.map { it.evidence }
        val top = ids.indices
            .filter { raws.getOrNull(it)?.isFinite() == true }
            .map { it to (ledger.seats["${ids[it]}|$lane"]?.authority() ?: 0.0) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(CROSS_TOP)
            .map { it.first }
            .sortedBy { ids[it] }
        if (top.size < 2) return emptyList()
        val out = ArrayList<CortexLedger7885.Vote>()
        for (x in top.indices) for (y in x + 1 until top.size) {
            val a = top[x]; val b = top[y]
            val na = edges[a].size + 1; val nb = edges[b].size + 1
            val code = CortexLedger7885.binOf(edges[a], raws[a]) * nb + CortexLedger7885.binOf(edges[b], raws[b])
            val crossEdges = DoubleArray(na * nb - 1) { k -> k + 0.5 }
            out.add(CortexLedger7885.Vote("X_${ids[a]}__${ids[b]}", crossEdges, code.toDouble(), evidence[a] + evidence[b] + "cross"))
        }
        return out
    }

    // ── conviction sizing (plan v2 §D) ──

    private const val CONVICTION_MAX_MULT = 2.0
    private const val KELLY_FRACTION = 0.25

    /** Pure: quarter-Kelly stake (SOL) from a proven record's mean/variance (percent units). */
    fun kellyStakeSol(meanPct: Double, variancePct2: Double, equitySol: Double): Double {
        if (!meanPct.isFinite() || !variancePct2.isFinite() || variancePct2 <= 0.0 || meanPct <= 0.0 || equitySol <= 0.0) return 0.0
        val f = KELLY_FRACTION * (meanPct / 100.0) / (variancePct2 / 10_000.0)
        return f.coerceIn(0.0, 1.0) * equitySol
    }

    /**
     * Size multiplier for a request on (mint, lane): > 1 only when this
     * candidate's decision-time read is STRONG AND the lane's STRONG record is
     * proven (the overrule bar). Never below 1 (shrinking is not a learning
     * lever at the route minimum); at most 2x; downstream caps still apply.
     */
    fun convictionMult(mint: String, laneRaw: String, requestedSol: Double, equitySol: Double): Double {
        if (mint.isBlank() || !(requestedSol > 0.0)) return 1.0
        val a = assessCache["$mint|${canon(laneRaw)}"] ?: return 1.0
        if (System.currentTimeMillis() - a.atMs > 60_000L) return 1.0
        if (a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark) return 1.0
        val stake = synchronized(this) {
            val strong = board.books[a.lane]?.byBucket?.get(CortexScoreboard7885.Bucket.STRONG.ordinal) ?: return 1.0
            if (!CortexScoreboard7885.overruleProven(strong) || !consistent(a.lane)) { inc("SHADOW_SIZE_UP"); return 1.0 }
            kellyStakeSol(strong.mean(), strong.variance(), equitySol)
        }
        val mult = (stake / requestedSol).coerceIn(1.0, CONVICTION_MAX_MULT)
        if (mult > 1.0) {
            inc("SIZED_UP_${a.lane}")
            try { PipelineHealthCollector.labelInc("CORTEX_7893_CONVICTION_SIZE_UP_${a.lane}") } catch (_: Throwable) {}
        }
        return mult
    }

    // ── progressive enrichment (plan v2 §B): buy a paid feature only while it pays ──

    private const val ENRICH_LEARNING_SCORES = 300
    private const val ENRICH_EXPLORE_RATE = 0.10

    /**
     * Should a paid feature backing [voterId] still be fetched? Yes while it is
     * being learned (< 300 graded scores across lanes) or while any lane seats
     * it. Once it has been measured and seats nowhere, only a 10% exploration
     * slice is bought, so it can earn its way back. Fails open.
     */
    fun enrichmentWorth(voterId: String): Boolean {
        return try {
            ensureLoaded()
            val worth = synchronized(this) {
                val mine = ledger.seats.entries.filter { it.key.startsWith("$voterId|") }
                val scored = mine.sumOf { it.value.scored }
                scored < ENRICH_LEARNING_SCORES || mine.any { it.value.authority() > 0.0 }
            }
            if (worth) true else {
                val explore = kotlin.random.Random.nextDouble() < ENRICH_EXPLORE_RATE
                inc(if (explore) "ENRICH_EXPLORE_$voterId" else "ENRICH_SKIPPED_$voterId")
                explore
            }
        } catch (_: Throwable) { true }
    }

    // ── OutcomeTruth cross-check: entry verdict -> whole-position close ──

    private val entryReads = ConcurrentHashMap<String, Assessment>()   // mint|MODE
    private const val ENTRY_READ_MATCH_MS = 15L * 60_000L

    private fun noteEntryRead(mint: String, paper: Boolean, a: Assessment) {
        if (entryReads.size > 4_000) entryReads.entries.removeIf { System.currentTimeMillis() - it.value.atMs > PENDING_TTL_MS * 4 }
        entryReads["$mint|${if (paper) "PAPER" else "LIVE"}"] = a
    }

    /** CanonicalFinalizedTradeBus6464 publish: one whole-position outcome (all legs, fees once). */
    fun onCanonicalClose(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || !env.realizedReturnPct.isFinite()) return
        val mode = if (env.mode.equals("live", true)) "LIVE" else if (env.mode.equals("paper", true)) "PAPER" else return
        val a = entryReads.remove("${env.mint}|$mode") ?: return
        val entryAt = env.atMs - env.holdingTimeMs.coerceAtLeast(0L)
        if (kotlin.math.abs(entryAt - a.atMs) > ENTRY_READ_MATCH_MS) { inc("REALIZED_UNMATCHED"); return }
        synchronized(this) { board.recordRealized(mode, a.lane, a.bucket, env.realizedReturnPct) }
        inc("REALIZED_${mode}")
    }

    // ── persistence ──

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("board")?.let { board.decode(it) }
                o.optJSONObject("calibration")?.let { calibration.decode(it) }
                o.optJSONObject("vetoes")?.let { j -> for (k in j.keys()) vetoBook[k] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) } }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        try {
            val json = synchronized(this) {
                org.json.JSONObject().put("ledger", ledger.encode()).put("board", board.encode()).put("calibration", calibration.encode())
                    .put("vetoes", org.json.JSONObject().also { j -> vetoBook.forEach { (k, v) -> j.put(k, v.encode()) } }).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    // ── scoreboard (v1 §2.10) ──

    fun statusLine(): String {
        ensureLoaded()
        val credits = credits7888()
        return synchronized(this) {
            val n = assessed.get()
            val avgMs = if (n > 0) assessNanos.get() / n / 1_000_000.0 else 0.0
            val seats: List<Triple<String, CortexLedger7885.Seat, Double>> = ledger.seats.entries
                .filter { !it.key.contains('@') }
                .map { (k, s) -> Triple(k, s, s.authority()) }
            val seated = seats.filter { it.third > 0.0 }.sortedByDescending { it.third }
            val bestByVoter = seats.groupBy { it.first.substringBefore('|') }
                .mapValues { (_, l) -> l.maxByOrNull { it.second.skill() } }
                .values.filterNotNull().sortedByDescending { it.second.skill() }
            val laneLines = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8).joinToString("\n") { (lane, b) ->
                val runner = isRunner(lane)
                "      $lane${if (runner) "(240m)" else "(60m)"}: refuse=${fmtStat(b.byBucket[0])} neutral=${fmtStat(b.byBucket[1])} strong=${fmtStat(b.byBucket[2])} " +
                    "| legacyAdmit=${fmtStat(b.legacyAdmitted)} legacyRefuse=${fmtStat(b.legacyRefused)} missedStrong=${fmtStat(b.missedStrong)} " +
                    "| authority: paperRefuse=${board.refusalAuthority(lane, runner, true)} liveRefuse=${board.refusalAuthority(lane, runner, false)} liveOverrule=${board.overruleAuthority(lane)}"
            }
            "bar=${CortexScoreboard7885.BAR_VERSION} voters=${CortexVoters7885.ALL.size}+V3modules assessed=$n (${"%.2f".format(avgMs)}ms) pending=${pending.size} graded=${graded.get()} " +
                "seats=${seats.size} seated=${seated.size}\n" +
                "      data economy (§B.6): creditsToday=${"%.0f".format(credits)} perAssessedDecision=${if (n > 0) "%.1f".format(credits / n) else "-"} perGradedDecision=${if (graded.get() > 0) "%.1f".format(credits / graded.get()) else "-"}\n" +
                "      calibration v7 (§7901): slope=${calibration.line()}\n" +
                "      timing cortex v6 (§7900): ${try { CortexTiming7900.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      exit cortex v3 (§7897): ${try { CortexExit7897.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      stop authority (§7887): ${try { StopAuthority7887.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      actions: ${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      seated (authority): ${seated.take(12).joinToString(" · ") { (k, s, a) -> "$k a=${"%.2f".format(a)} skill=${"%.1f".format(s.skill() * 100)}% n=${s.scored}" }.ifBlank { "none yet — a seat needs ${CortexLedger7885.MIN_SCORED} graded predictions beating the lane mean out-of-sample" }}\n" +
                "      best skill per voter: ${bestByVoter.take(10).joinToString(" · ") { (k, s, _) -> "$k ${"%+.1f".format(s.skill() * 100)}%/n${s.scored}" }.ifBlank { "-" }}\n" +
                "      voter failures: ${voterFailures.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "0" }}\n" +
                laneLines.ifBlank { "      lanes: no graded decisions yet" } + "\n" +
                "      veto audit (refused candidates' forward return): " +
                vetoBook.entries.filter { it.value.n >= 5.0 }.sortedByDescending { it.value.n }.take(12).joinToString(" · ") { (r, st) ->
                    val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY
                    val flag = if (st.n >= 40.0 && st.mean() - se > 2.0) " REFUSING_WINNERS" else if (st.n >= 40.0 && st.mean() + se < -2.0) " ok" else ""
                    "$r ${fmtStat(st)}$flag"
                }.ifBlank { "none yet" } + "\n" +
                "      realised whole-position outcome by entry verdict: " +
                board.realized.entries.sortedBy { it.key }.joinToString(" · ") { (k, arr) ->
                    "$k refuse=${fmtStat(arr[0])} neutral=${fmtStat(arr[1])} strong=${fmtStat(arr[2])}"
                }.ifBlank { "none yet" }
        }
    }
}
