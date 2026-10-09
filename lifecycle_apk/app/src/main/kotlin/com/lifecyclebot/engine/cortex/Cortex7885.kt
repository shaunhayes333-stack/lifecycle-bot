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
 * Horizon: every lane is graded on THE read — label slot 60, the 5-minute
 * spike-credited label (V5.0.7946). V5.0.7948: a decision is filed under the
 * bucket assigned by the ledger of the same label epoch (see restorePending).
 *
 * Nothing here sizes a trade. Every entry point fails open (null / false).
 */
object Cortex7885 {
    private const val ASSESS_TTL_MS = 20_000L
    private const val PENDING_TTL_MS = 5L * 60L * 60_000L
    private const val MAX_PENDING = 8_000
    private const val MAX_ASSESS_CACHE = 4_000
    /**
     * V5.0.7947 — the forward-label ledgers moved to a new key. They were graded on
     * 60/240-minute, spike-blind labels; since 7945/7946 a label is the 5-minute read
     * with the spike tiers credited, so the old grades measured a different thing and
     * kept refusing on it (5.0.7944: C3 on CASHGEN 1,006). The realised-trade ledgers
     * ("real") are fills and exits, still true, and carry over. The old key is left in
     * place, untouched.
     */
    private const val PERSIST_KEY = "CORTEX_7885_V7947"
    private const val LEGACY_PERSIST_KEY_7947 = "CORTEX_7885"
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

    private class Pending(val a: Assessment, val legacyAdmitted: Boolean, val atMs: Long, val vetoRule: String? = null, val source: String = "")

    private val ledger = CortexLedger7885()
    private val board = CortexScoreboard7885()
    private val calibration = CortexCalibration7901()
    private val realLedgers = HashMap<String, CortexLedger7885>()   // mode -> outcome-truth ledger
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
        val registryAt = try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(ts.mint)?.timestampMs ?: 0L } catch (_: Throwable) { 0L }
        val freshestAt = maxOf(ts.lastPriceUpdate, registryAt)
        val priceAge = if (freshestAt > 0L) nowMs - freshestAt else Long.MAX_VALUE
        val a = Assessment(lane, runner, raws, ids, edges, regime, fused, calibrated, bucket, raws.count { !it.isFinite() }, priceAge > MARK_STALE_MS, nowMs)
        try { CortexInvariants7911.recordProvenance(ts, nowMs) } catch (_: Throwable) {}
        assessNanos.addAndGet(System.nanoTime() - t0)
        assessed.incrementAndGet()
        if (assessCache.size >= MAX_ASSESS_CACHE) assessCache.entries.removeIf { nowMs - it.value.atMs > ASSESS_TTL_MS }
        assessCache[key] = a
        return a
    }

    // ── v1 §2.9 compute model (V5.0.7909): voters run off the decision path ──
    //
    // 5.0.7891: 1,073 assessments at 25.7 ms each, inline on the FDG thread. The
    // gate now only READS a fresh cached assessment; a miss schedules one on a
    // small bounded worker pool and the gate proceeds without a Cortex opinion
    // this cycle (the candidate is re-evaluated next cycle). Capture runs on the
    // pool too; its label still starts at the observation time.
    private val pool: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 2, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(256),
        { r -> Thread(r, "cortex-7909").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        // V5.0.7930 — a dropped job is counted (ASYNC_DROPPED), never silent.
        java.util.concurrent.RejectedExecutionHandler { r, ex ->
            if (!ex.isShutdown) {
                ex.queue.poll()
                inc("ASYNC_DROPPED")
                try { ex.execute(r) } catch (_: Throwable) {}
            }
        },
    )
    private val scheduled = ConcurrentHashMap<String, Long>()

    private fun submit(tag: String, job: () -> Unit) {
        try {
            pool.execute { try { job() } catch (_: Throwable) {} }
            inc("ASYNC_$tag")
        } catch (_: Throwable) { inc("ASYNC_REJECTED") }
    }

    /** V5.0.7948 — CortexTiming7900: an inverted lane's STRONG read is not exempt from the dip wait. */
    fun laneInverted7948(laneRaw: String): Boolean =
        try { ensureLoaded(); synchronized(this) { board.inverted7948(canon(laneRaw)) } } catch (_: Throwable) { false }

    /** For CortexInvariants7911. */
    fun queuedTasks(): Int = try { pool.queue.size } catch (_: Throwable) { 0 }
    fun pendingCount(): Int = pending.size

    /** The fresh cached assessment, or null after scheduling one (never computes inline). */
    private fun cachedOrSchedule(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Assessment? {
        if (ts.mint.isBlank() || laneRaw.isBlank()) return null
        val key = "${ts.mint}|${canon(laneRaw)}"
        assessCache[key]?.let { if (nowMs - it.atMs <= ASSESS_TTL_MS) return it }
        // A job the full queue dropped never reaches its finally: a key may be
        // rescheduled after 30 s, so a dropped assessment cannot block a mint forever.
        val prev = scheduled[key]
        if (prev == null || nowMs - prev > 30_000L) {
            scheduled[key] = nowMs
            submit("ASSESS") { try { assess(ts, laneRaw) } finally { scheduled.remove(key) } }
        } else inc("ASYNC_ALREADY_SCHEDULED")
        if (scheduled.size > 4_000) scheduled.entries.removeIf { nowMs - it.value > 30_000L }
        return null
    }

    /** ForwardReturnLabeler7731.observe: one graded decision opens here (1:1 with its observation). */
    fun capture(ts: TokenState, labelLane: String, admitted: Boolean, nowMs: Long = System.currentTimeMillis(), reason: String? = null) {
        if (labelLane.startsWith("PLANWAIT_") || labelLane.startsWith("PLANADMIT_")) return
        submit("CAPTURE") { captureNow(ts, labelLane, admitted, nowMs, reason) }
    }

    private fun captureNow(ts: TokenState, labelLane: String, admitted: Boolean, nowMs: Long, reason: String?) {
        val a = assess(ts, labelLane, nowMs) ?: return
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > PENDING_TTL_MS }
        if (pending.size >= MAX_PENDING) { inc("PENDING_FULL"); return }
        val veto = if (admitted || reason.isNullOrBlank()) null else vetoRuleOf(reason)
        pending["${ts.mint}|${labelLane.trim().uppercase()}"] = Pending(a, admitted, nowMs, veto, ts.source)
        // V5.0.7930 — every admitted decision is matched to its realised close (not only cached-read admits).
        if (admitted) noteEntryRead(ts.mint, try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }, a)
        // V5.0.7907 — the lane playbook tags the decision with its setup.
        try { LanePlaybook7907.capture(ts, a.lane, labelLane, nowMs) } catch (_: Throwable) {}
        // V5.0.7900 — Cortex v6: the same decision opens a 5-minute timing label.
        try { CortexTiming7900.capture(ts, a, nowMs) } catch (_: Throwable) {}
        inc(if (a.bucket == CortexScoreboard7885.Bucket.REFUSE) "SEEN_REFUSE" else if (a.bucket == CortexScoreboard7885.Bucket.STRONG) "SEEN_STRONG" else "SEEN_NEUTRAL")
        persistPendingMaybe(nowMs)
        try { LanePlaybook7907.persistPendingMaybe(nowMs) } catch (_: Throwable) {}
    }

    // ── V5.0.7920: pending decisions survive a restart ──
    //
    // Labels mature 60 min (240 for runner lanes) after the decision, and every
    // install or restart dropped the in-memory pending set, so a book restarted
    // more often than hourly graded nothing. The newest [PENDING_PERSIST_MAX]
    // pending decisions (finite votes only, sparse) are saved every 2 minutes
    // and restored on load; edges are rebuilt from voter ids.
    private const val PENDING_PERSIST_KEY = "CORTEX_PENDING_7920"
    private const val PENDING_PERSIST_MAX = 1_000
    private const val PENDING_PERSIST_EVERY_MS = 120_000L
    @Volatile private var lastPendingPersistMs = 0L

    private fun fin0(v: Double): Double = if (v.isFinite()) kotlin.math.round(v * 1e4) / 1e4 else 0.0

    private fun persistPendingMaybe(nowMs: Long) {
        // V5.0.7930 — restore before the first save, or the save erases what was pending.
        ensureLoaded()
        if (!loaded) return
        if (nowMs - lastPendingPersistMs < PENDING_PERSIST_EVERY_MS) return
        lastPendingPersistMs = nowMs
        try {
            // Voter ids are written once in a dictionary; each decision stores indices (keeps the blob well under 1 MB).
            val dict = LinkedHashMap<String, Int>()
            val arr = org.json.JSONArray()
            pending.entries.sortedByDescending { it.value.atMs }.take(PENDING_PERSIST_MAX).forEach { (k, p) ->
                val a = p.a
                val ids = org.json.JSONArray()
                val xs = org.json.JSONArray()
                for (i in a.ids.indices) {
                    val r = a.raws.getOrNull(i) ?: continue
                    if (r.isFinite()) { ids.put(dict.getOrPut(a.ids[i]) { dict.size }); xs.put(fin0(r)) }
                }
                arr.put(
                    org.json.JSONObject().put("k", k).put("l", a.lane).put("r", a.runnerLane).put("g", a.regime)
                        .put("b", a.bucket.ordinal).put("e", fin0(a.fused.edgePct)).put("m", fin0(a.fused.laneMean))
                        .put("ce", fin0(a.calibratedEdge)).put("adm", p.legacyAdmitted).put("at", p.atMs)
                        .put("v", p.vetoRule.orEmpty()).put("s", p.source).put("ep", PERSIST_KEY).put("i", ids).put("x", xs),
                )
            }
            val out = org.json.JSONObject().put("dict", org.json.JSONArray(dict.keys.toList())).put("p", arr)
            LearningPersistence.save(PENDING_PERSIST_KEY, out.toString())
            inc("PENDING_PERSISTED")
        } catch (_: Throwable) { inc("PENDING_PERSIST_FAILED") }
    }

    /** Caller holds the lock (ensureLoaded). */
    private fun restorePending(raw: String?, nowMs: Long) {
        val root = org.json.JSONObject(raw ?: return)
        val jd = root.optJSONArray("dict") ?: return
        val dict = List(jd.length()) { jd.optString(it) }
        val arr = root.optJSONArray("p") ?: return
        val buckets = CortexScoreboard7885.Bucket.values()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            val at = o.optLong("at")
            if (k.isBlank() || pending.containsKey(k) || nowMs - at > PENDING_TTL_MS || at > nowMs) continue
            // V5.0.7948 — a decision's bucket was assigned by the ledger of its build. One
            // saved before the 7947 label epoch was bucketed on 60/240-minute, spike-blind
            // grades and would be filed into books graded on the 5-minute read.
            if (o.optString("ep") != PERSIST_KEY) { inc("PENDING_EPOCH_DROPPED_7948"); continue }
            val ids = ArrayList<String>()
            val edges = ArrayList<DoubleArray>()
            val raws = ArrayList<Double>()
            val ji = o.optJSONArray("i") ?: continue
            val jx = o.optJSONArray("x") ?: continue
            for (j in 0 until minOf(ji.length(), jx.length())) {
                val id = dict.getOrNull(ji.optInt(j, -1)) ?: continue
                val e = CortexVoters7885.edgesFor(id) ?: continue
                ids.add(id); edges.add(e); raws.add(jx.optDouble(j))
            }
            val b = buckets.getOrNull(o.optInt("b", 1)) ?: CortexScoreboard7885.Bucket.NEUTRAL
            val fused = CortexLedger7885.Fused(o.optDouble("e", 0.0), 0.0, o.optDouble("m", 0.0), 0.0, 0.0, 0.0, 0.0, emptyList())
            val a = Assessment(o.optString("l"), o.optBoolean("r"), raws.toDoubleArray(), ids, edges, o.optString("g"),
                fused, o.optDouble("ce", 0.0), b, 0, false, at)
            pending[k] = Pending(a, o.optBoolean("adm"), at, o.optString("v").ifBlank { null }, o.optString("s"))
            inc("PENDING_RESTORED")
        }
    }

    private val sourceGraded = ConcurrentHashMap<String, Long>()   // mint -> decision time (ScannerSourceBrain once per mint)

    /** ForwardReturnLabeler7731.tick: a horizon label booked for (mint, labelLane). */
    fun onLabel(mint: String, labelLane: String, horizonMin: Int, netPct: Double, grossPct: Double) {
        ensureLoaded()
        val key = "$mint|${labelLane.trim().uppercase()}"
        val p = pending[key] ?: return
        // V5.0.7946 — every lane is graded at THE read (slot 60 = 5 minutes). Runner lanes
        // used to wait for the 4-hour label; with the spike tiers credited in the label,
        // a runner's first minutes carry its capture, and the Cortex learns 48x sooner.
        if (horizonMin != 60) return
        pending.remove(key)
        if (!netPct.isFinite()) return
        try { LanePlaybook7907.onLabel(mint, labelLane, netPct, grossPct) } catch (_: Throwable) {}
        // V5.0.7908 — discovery quality: the scanner source brain orders and weights
        // intake by each source's record, but learned only from closed trades (a
        // handful a day). Every graded decision now teaches it what that source's
        // tokens actually did, from trade one.
        // V5.0.7925 — once per mint (several lanes label the same token), and only
        // for decisions not traded: a traded token's close already teaches the brain.
        if (p.source.isNotBlank() && !p.legacyAdmitted && sourceGraded.putIfAbsent(mint, p.atMs) == null) {
            try { com.lifecyclebot.engine.ScannerSourceBrain.recordOutcome(p.source, netPct.coerceIn(-100.0, 200.0)) } catch (_: Throwable) {}
            if (sourceGraded.size > 6_000) sourceGraded.entries.removeIf { p.atMs - it.value > PENDING_TTL_MS }
        }
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
        // V5.0.7906 — C2_MARK_STALE removed: 5.0.7891 refused 852 live candidates on
        // ts.lastPriceUpdate alone, while most candidates are priced through the
        // canonical mark registry (FieldManual7715 already refuses an impaired
        // quote with the registry in view). Staleness stays an input to overrule
        // and conviction, never a refusal of its own.
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
            // V5.0.7930 — LIVE never proceeds without a read: a first-seen live candidate
            // (fast lane, launch-heat promotion) used to be admitted on a cache miss.
            val a = cachedOrSchedule(ts, laneRaw) ?: (if (!paper) assess(ts, laneRaw) else null) ?: return null
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
                            "pWin=${"%.2f".format(a.fused.pWin)} runner=${"%.2f".format(a.fused.runnerRate)} effVoters=${"%.1f".format(a.fused.effectiveVoters)} top=${a.fused.top.joinToString(",")}",
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
            val a = cachedOrSchedule(ts, laneRaw) ?: return false
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

    // ── Cortex v13: paper choice (v1 Phase 4, V5.0.7915) ──
    //
    // Until now the Cortex only refused. In PAPER it now also CHOOSES: a
    // candidate the legacy stack blocked on a soft (confidence / edge) reason,
    // or that its lane declined, is admitted as a paper position when the
    // Cortex reads it STRONG and that lane's STRONG record is proven on forward
    // labels (the overrule bar) and the Cortex is self-consistent. One choice
    // per lane per [CHOICE_SPACING_MS]. Never in LIVE, never over a hard block
    // (safety, rug, route, mode, size), never on a stale mark. Its positions are
    // booked as PAPER_CHOSEN on whole-position closes, so the choice itself is
    // graded on real exits before anything like it is let near live money.
    private const val CHOICE_SPACING_MS = 5L * 60_000L
    private val lastChoiceAt = ConcurrentHashMap<String, Long>()
    private val chosen = ConcurrentHashMap<String, Long>()   // mint -> chosen at

    /** Pure: is a legacy block soft enough for a proven Cortex read to override in paper? */
    fun softBlock(blockReason: String?, hardLevel: Boolean): Boolean {
        if (hardLevel) return false
        val r = (blockReason ?: return true).uppercase()
        return listOf("HARD", "RUG", "TOKEN_MAP", "ROUTE", "SAFETY", "HONEYPOT", "FREEZE", "MINT_AUTH", "KILL", "PAUSE", "WALLET", "BALANCE", "DUPLICATE", "OPEN_POSITION", "CAPACITY")
            .none { r.contains(it) }
    }

    /** FinalDecisionGate (PAPER only): true admits this soft-blocked candidate as the Cortex's choice. */
    fun paperChoice(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val a = cachedOrSchedule(ts, laneRaw, nowMs) ?: return false
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return false
            if (a.staleMark || ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
            val proven = synchronized(this) { board.overruleAuthority(a.lane) && consistent(a.lane) }
            if (!proven) { inc("SHADOW_PAPER_CHOICE"); return false }
            val last = lastChoiceAt[a.lane] ?: 0L
            if (nowMs - last < CHOICE_SPACING_MS) { inc("PAPER_CHOICE_SPACED"); return false }
            lastChoiceAt[a.lane] = nowMs
            if (chosen.size > 2_000) chosen.entries.removeIf { nowMs - it.value > PENDING_TTL_MS * 4 }
            chosen[ts.mint] = nowMs
            noteEntryRead(ts.mint, true, a)
            inc("PAPER_CHOSEN_${a.lane}")
            try {
                PipelineHealthCollector.labelInc("CORTEX_7915_PAPER_CHOSEN_${a.lane}")
                ForensicLogger.lifecycle(
                    "CORTEX_7915_PAPER_CHOSEN",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} edge=${"%.2f".format(a.calibratedEdge)} pWin=${"%.2f".format(a.fused.pWin)} top=${a.fused.top.joinToString(",")}",
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
        val lane = canon(laneRaw)
        val cortexStake = cortexStake7893(mint, lane, equitySol)
        // V5.0.7948 — the candidate's own proven evidence sizes it too (see provenEvidenceStake7948).
        val evidenceStake = provenEvidenceStake7948(mint, lane, equitySol)
        val mult = stakeMult7948(maxOf(cortexStake, evidenceStake), requestedSol)
        if (mult > 1.0) {
            inc("SIZED_UP_$lane")
            try {
                PipelineHealthCollector.labelInc(
                    if (cortexStake >= evidenceStake) "CORTEX_7893_CONVICTION_SIZE_UP_$lane" else "EVIDENCE_KELLY_SIZE_UP_7948_$lane",
                )
            } catch (_: Throwable) {}
        }
        return mult
    }

    /** The 7893 STRONG-record quarter-Kelly stake (SOL), or 0 when the read or the record does not qualify. */
    private fun cortexStake7893(mint: String, lane: String, equitySol: Double): Double {
        val a = assessCache["$mint|$lane"] ?: return 0.0
        if (System.currentTimeMillis() - a.atMs > 60_000L) return 0.0
        if (a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark) return 0.0
        return synchronized(this) {
            val strong = board.books[a.lane]?.byBucket?.get(CortexScoreboard7885.Bucket.STRONG.ordinal) ?: return 0.0
            // V5.0.7948 — the lane's overrule authority (proven AND not inverted), not the bare STRONG record.
            if (!board.overruleAuthority(a.lane) || !consistent(a.lane)) {
                inc(if (board.inverted7948(a.lane)) "SUSPENDED_INVERTED_SIZE_UP_7948" else "SHADOW_SIZE_UP"); return 0.0
            }
            kellyStakeSol(strong.mean(), strong.variance(), equitySol)
        }
    }

    /**
     * V5.0.7948 §SIZE_COMPOUNDS_WHERE_THE_EDGE_IS_PROVEN.
     *
     * Owner goal: exponential wallet growth. The 7893 conviction size-up never
     * fired (5.0.7947: Cortex seated 0, size-up shadow 101, real 0), so every
     * live order sat at the request whatever the evidence. A candidate whose lane
     * evidence is proven positive — its classified playbook setup in this lane (30+
     * labels, mean-SE > 0) or the lane's own forward labels (LiveEdgeGate7877.
     * laneProvenPositive7941) — is now sized toward quarter-Kelly of that record
     * (a pooled cross-lane cohort may admit a trade, but never sizes it up)
     * on the CURRENT wallet: as winners' proceeds return to the wallet the stake
     * grows with it (compounding), and as it shrinks the stake shrinks. Never
     * below the request (so never under the route minimum the request already
     * cleared), at most [CONVICTION_MAX_MULT]x; every downstream wallet, lane,
     * share and liquidity cap still bounds it. Unproven evidence changes nothing.
     */
    private fun provenEvidenceStake7948(mint: String, lane: String, equitySol: Double): Double {
        if (!(equitySol > 0.0)) return 0.0
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return 0.0
        val nowMs = System.currentTimeMillis()
        val setup = try { LanePlaybook7907.provenSetupRecord7948(ts, lane, nowMs) } catch (_: Throwable) { null }
        val laneStat = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null }
        val setupStake = if (setup != null && setup.size >= 2) kellyStakeSol(setup[0], setup[1], equitySol) else 0.0
        val laneStake = if (laneStat != null && com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(laneStat)) {
            kellyStakeSol(laneStat.meanNet60Pct, cohortVariance7948(laneStat.stderr60Pct, laneStat.n60), equitySol)
        } else 0.0
        return maxOf(setupStake, laneStake)
    }

    /** Pure: a label set's per-label variance (percent^2) from its standard error and count. */
    fun cohortVariance7948(stderrPct: Double, n: Int): Double =
        if (!stderrPct.isFinite() || stderrPct <= 0.0 || n <= 0) 0.0 else stderrPct * stderrPct * n

    /** Pure: the size multiplier a stake justifies over the request: at least 1, at most [CONVICTION_MAX_MULT]. */
    fun stakeMult7948(stakeSol: Double, requestedSol: Double): Double =
        if (!(requestedSol > 0.0) || !stakeSol.isFinite()) 1.0 else (stakeSol / requestedSol).coerceIn(1.0, CONVICTION_MAX_MULT)

    // ── Cortex v15: the legacy size stack, graded (v1 Phase 5, V5.0.7917) ──
    //
    // FinalDecisionGate multiplies the proposal by ~30 legacy factors (win-memory,
    // liquidity, tiers, Kelly, brain chain, consensus damps, policy heads...) and
    // 6552 collapses the product into one bounded shape [0.35, 1.5]. That shape
    // is now a voter, LEGACY_SIZE_SHAPE, graded like any other: does the stack's
    // shrink/grow predict the forward return? In a lane where it has been scored
    // [STACK_MEASURED] times and earned no seat, the stack loses its power to
    // SHRINK a candidate the Cortex reads STRONG on a proven record: that
    // candidate's shape is floored at 1.0 (the lane's own calculated size).
    // Absolute caps (live ceiling, pinned probes, wallet and route caps) still apply.
    const val LEGACY_SIZE_SHAPE = "LEGACY_SIZE_SHAPE"
    private const val STACK_MEASURED = 300
    private val legacyShape = ConcurrentHashMap<String, DoubleArray>()   // mint -> [shape, atMs]

    /** CortexVoters7885: the most recent legacy shape the FDG stack produced for this mint (10 min). */
    fun legacyShapeOf(mint: String, nowMs: Long): Double? =
        legacyShape[mint]?.takeIf { nowMs - it[1].toLong() <= 600_000L }?.get(0)

    /** Pure: the shape after Cortex v15 authority. */
    fun shapeAfterAuthority(bounded: Double, strongProven: Boolean, stackMeasuredNoSkill: Boolean): Double =
        if (strongProven && stackMeasuredNoSkill && bounded < 1.0) 1.0 else bounded

    /** FinalDecisionGate 6552: record the stack's raw shape and return the shape to use. */
    fun sizeShape(ts: TokenState, laneRaw: String, bounded: Double, raw: Double, nowMs: Long = System.currentTimeMillis()): Double {
        return try {
            if (raw.isFinite() && raw > 0.0) {
                if (legacyShape.size > 4_000) legacyShape.entries.removeIf { nowMs - it.value[1].toLong() > 600_000L }
                legacyShape[ts.mint] = doubleArrayOf(raw, nowMs.toDouble())
            }
            if (bounded >= 1.0) return bounded
            val a = assessCache["${ts.mint}|${canon(laneRaw)}"]?.takeIf { nowMs - it.atMs <= 60_000L } ?: return bounded
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark) return bounded
            val (strongProven, noSkill) = synchronized(this) {
                val seat = ledger.seats["$LEGACY_SIZE_SHAPE|${a.lane}"]
                (board.overruleAuthority(a.lane) && consistent(a.lane)) to
                    ((seat?.scored ?: 0) >= STACK_MEASURED && (seat?.authority() ?: 0.0) <= 0.0)
            }
            val out = shapeAfterAuthority(bounded, strongProven, noSkill)
            if (out != bounded) {
                inc("STACK_SHRINK_OVERRULED_${a.lane}")
                try { PipelineHealthCollector.labelInc("CORTEX_7917_STACK_SHRINK_OVERRULED_${a.lane}") } catch (_: Throwable) {}
            } else if (strongProven) inc("SHADOW_STACK_OVERRULE")
            out
        } catch (_: Throwable) { bounded }
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
        // V5.0.7930 — inferred-basis / quarantined rows are not learning truth; and load first.
        if (!env.learningEligible) { inc("REALIZED_NOT_ELIGIBLE"); return }
        ensureLoaded()
        val mode = if (env.mode.equals("live", true)) "LIVE" else if (env.mode.equals("paper", true)) "PAPER" else return
        // V5.0.7931 — cross-asset rows close under "solana|<mint>" / ticker / traded mint;
        // all map onto the identity the entry was captured under.
        val closeKey7931 = try { CrossAssetCortex7931.normalizeCloseKey(env.mint) } catch (_: Throwable) { env.mint }
        val a = entryReads.remove("$closeKey7931|$mode") ?: return
        // V5.0.7925 — many envelopes carry holdingTimeMs=0; then the entry time is unknown
        // and the read is matched on mint+mode alone (it is removed at the first close).
        if (env.holdingTimeMs > 0L) {
            val entryAt = env.atMs - env.holdingTimeMs
            if (kotlin.math.abs(entryAt - a.atMs) > ENTRY_READ_MATCH_MS) { inc("REALIZED_UNMATCHED"); return }
        } else if (env.atMs - a.atMs > PENDING_TTL_MS * 4) { inc("REALIZED_UNMATCHED"); return }
        val wasChosen = mode == "PAPER" && chosen.remove(env.mint) != null
        synchronized(this) {
            board.recordRealized(mode, a.lane, a.bucket, env.realizedReturnPct)
            if (wasChosen) board.recordRealized("PAPER_CHOSEN", a.lane, a.bucket, env.realizedReturnPct)
            // V5.0.7912 — Cortex v12, OutcomeTruth (v1 §2.7): every voter's entry-time
            // opinion is also graded on the WHOLE-POSITION realised return, per mode,
            // in its own ledger — skill on real fills and real exits, beside the
            // forward-label skill that grants authority.
            realLedgers.getOrPut(mode) { CortexLedger7885() }
                .grade(a.lane, a.ids, a.edges, a.raws, env.realizedReturnPct, env.realizedReturnPct, a.regime)
        }
        inc("REALIZED_${mode}")
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
            try { restorePending(LearningPersistence.load(PENDING_PERSIST_KEY), System.currentTimeMillis()) } catch (_: Throwable) { inc("PENDING_RESTORE_FAILED") }
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: run {
                    LearningPersistence.load(LEGACY_PERSIST_KEY_7947)?.let { legacy ->
                        org.json.JSONObject(legacy).optJSONObject("real")?.let { j -> for (k in j.keys()) j.optJSONObject(k)?.let { realLedgers[k] = CortexLedger7885().also { l -> l.decode(it) } } }
                        inc("LEGACY_REAL_CARRIED_7947")
                    }
                    return
                })
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("board")?.let { board.decode(it) }
                o.optJSONObject("calibration")?.let { calibration.decode(it) }
                o.optJSONObject("real")?.let { j -> for (k in j.keys()) j.optJSONObject(k)?.let { realLedgers[k] = CortexLedger7885().also { l -> l.decode(it) } } }
                o.optJSONObject("vetoes")?.let { j -> for (k in j.keys()) vetoBook[k] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) } }
            } catch (_: Throwable) {}
        }
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    fun persistNow7930() {
        if (!loaded) return
        persist()
        lastPendingPersistMs = 0L
        persistPendingMaybe(System.currentTimeMillis())
        try { LanePlaybook7907.persistNow7930() } catch (_: Throwable) {}
        try { CortexExit7897.persistNow7930() } catch (_: Throwable) {}
        try { CortexTiming7900.persistNow7930() } catch (_: Throwable) {}
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                org.json.JSONObject().put("ledger", ledger.encode()).put("board", board.encode()).put("calibration", calibration.encode())
                    .put("real", org.json.JSONObject().also { j -> realLedgers.forEach { (k, l) -> j.put(k, l.encode()) } })
                    .put("vetoes", org.json.JSONObject().also { j -> vetoBook.forEach { (k, v) -> j.put(k, v.encode()) } }).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    // ── scoreboard (v1 §2.10) ──

    /**
     * V5.0.7919 — the in-app scoreboard card (a section of its own on the
     * Pipeline screen, kept under the 2,000-char section render cap): per lane,
     * what the Cortex has learned and which powers it holds; what it has done.
     */
    fun scoreboardCard(): String {
        ensureLoaded()
        return try {
            synchronized(this) {
                val seated = ledger.seats.entries.count { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") && it.value.authority() > 0.0 }
                val sb = StringBuilder()
                sb.append("  assessed=${assessed.get()} graded=${graded.get()} seated=$seated pending=${pending.size}\n")
                sb.append("  lane      graded  strong    refuse    slope powers\n")
                board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8).forEach { (lane, b) ->
                    val runner = isRunner(lane)
                    val powers = buildString {
                        if (board.refusalAuthority(lane, runner, true)) append("Rp ")
                        if (board.refusalAuthority(lane, runner, false)) append("Rl ")
                        if (board.overruleAuthority(lane)) append("O ")
                        if (calibration.slope(lane) < MIN_CONSISTENT_SLOPE) append("SUSPENDED ")
                        if (board.inverted7948(lane)) append("INVERTED")
                    }.trim().ifBlank { "shadow" }
                    sb.append("  ${lane.take(9).padEnd(9)} ${b.byBucket.sumOf { it.n }.toInt().toString().padStart(6)}  " +
                        "${fmtStat(b.byBucket[2]).padEnd(9)} ${fmtStat(b.byBucket[0]).padEnd(9)} ${"%.2f".format(calibration.slope(lane))}  $powers\n")
                }
                fun sum(prefix: String) = counters.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value.get() }
                sb.append("  did: refusedPaper=${sum("REFUSED_PAPER")} refusedLive=${sum("REFUSED_LIVE")} overruled=${sum("OVERRULED_LIVE")} " +
                    "paperChosen=${sum("PAPER_CHOSEN_")} sizedUp=${sum("SIZED_UP_")} stackOverruled=${sum("STACK_SHRINK_OVERRULED_")}\n")
                sb.append("  shadow: refuse=${sum("SHADOW_REFUSE")} overrule=${sum("SHADOW_OVERRULE")} choice=${sum("SHADOW_PAPER_CHOICE")} size=${sum("SHADOW_SIZE_UP")}\n")
                sb.append("  realised: " + board.realized.entries.sortedBy { it.key }.take(6).joinToString(" · ") { (k, arr) ->
                    "$k ${fmtStat(arr[2])}/${fmtStat(arr[1])}/${fmtStat(arr[0])}"
                }.ifBlank { "none yet" } + "  (strong/neutral/refuse)\n")
                sb.append("  key: Rp/Rl refuse paper/live · O overrule+conviction · SUSPENDED calibration < 0.5 · INVERTED strong < neutral")
                sb.toString().take(1_900)
            }
        } catch (t: Throwable) { "  unavailable: ${t.javaClass.simpleName}" }
    }

    fun statusLine(): String {
        ensureLoaded()
        val credits = credits7888()
        return synchronized(this) {
            val n = assessed.get()
            val avgMs = if (n > 0) assessNanos.get() / n / 1_000_000.0 else 0.0
            val seats: List<Triple<String, CortexLedger7885.Seat, Double>> = ledger.seats.entries
                .filter { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") }
                .map { (k, s) -> Triple(k, s, s.authority()) }
            val seated = seats.filter { it.third > 0.0 }.sortedByDescending { it.third }
            val bestByVoter = seats.groupBy { it.first.substringBefore('|') }
                .mapValues { (_, l) -> l.maxByOrNull { it.second.skill() } }
                .values.filterNotNull().sortedByDescending { it.second.skill() }
            val laneLines = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8).joinToString("\n") { (lane, b) ->
                val runner = isRunner(lane)
                "      $lane(5m${if (runner) ",runner" else ""}${if (board.inverted7948(lane)) ",INVERTED" else ""}): refuse=${fmtStat(b.byBucket[0])} neutral=${fmtStat(b.byBucket[1])} strong=${fmtStat(b.byBucket[2])} " +
                    "| legacyAdmit=${fmtStat(b.legacyAdmitted)} legacyRefuse=${fmtStat(b.legacyRefused)} missedStrong=${fmtStat(b.missedStrong)} " +
                    "| authority: paperRefuse=${board.refusalAuthority(lane, runner, true)} liveRefuse=${board.refusalAuthority(lane, runner, false)} liveOverrule=${board.overruleAuthority(lane)}"
            }
            "bar=${CortexScoreboard7885.BAR_VERSION} voters=${CortexVoters7885.ALL.size}+V3modules assessed=$n (${"%.2f".format(avgMs)}ms) pending=${pending.size} graded=${graded.get()} " +
                "seats=${seats.size} seated=${seated.size}\n" +
                "      invariants & provenance v11 (§7911): ${try { CortexInvariants7911.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      compute (§2.9 v1, 7909): pool active=${pool.activeCount} queued=${pool.queue.size} done=${pool.completedTaskCount} slowVoters=${CortexVoters7885.slowLine()}\n" +
                "      data economy (§B.6): creditsToday=${"%.0f".format(credits)} perAssessedDecision=${if (n > 0) "%.1f".format(credits / n) else "-"} perGradedDecision=${if (graded.get() > 0) "%.1f".format(credits / graded.get()) else "-"}\n" +
                "      outcome truth v12 (§7912, whole-position closes): ${realLedgers.entries.joinToString(" · ") { (mode, l) ->
                    val seated = l.seats.entries.filter { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") && it.value.scored > 0 }
                    "$mode closes=${l.lanes.entries.filter { !it.key.contains('@') && it.key != CortexLedger7885.GLOBAL }.sumOf { it.value.n }.toInt()} " +
                        "best=[${seated.sortedByDescending { it.value.skill() }.take(4).joinToString(",") { "${it.key} ${"%+.1f".format(it.value.skill() * 100)}%/n${it.value.scored}" }}]"
                }.ifBlank { "no closes yet" }}\n" +
                "      launch tape v16 (§7921): ${try { com.lifecyclebot.engine.market.LaunchTape7921.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      lane playbooks (§7907): ${try { LanePlaybook7907.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      calibration v7 (§7901): slope=${calibration.line()}\n" +
                "      timing cortex v6 (§7900): ${try { CortexTiming7900.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      exit cortex v3 (§7897): ${try { CortexExit7897.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      stop authority (§7887): ${try { StopAuthority7887.statusLine() } catch (_: Throwable) { "unavailable" }}\n" +
                "      actions: ${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      seated (authority): ${seated.take(12).joinToString(" · ") { (k, s, a) -> "$k a=${"%.2f".format(a)} skill=${"%.1f".format(s.skill() * 100)}% n=${s.scored}" }.ifBlank { "none yet — a seat needs ${CortexLedger7885.MIN_SCORED} graded predictions beating the lane mean out-of-sample" }}\n" +
                "      best skill per voter (return R² · win-prob Brier · log-loss): ${bestByVoter.take(10).joinToString(" · ") { (k, s, _) -> "$k ${"%+.1f".format(s.skill() * 100)}%/${"%+.1f".format(s.brierSkill() * 100)}%/${"%+.1f".format(s.logLossSkill() * 100)}% n${s.scored}" }.ifBlank { "-" }}\n" +
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
