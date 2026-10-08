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
    private const val SAMPLE_EVERY_MS = 180_000L
    private const val HORIZON_MS = 30L * 60_000L
    private const val RUNNER_HORIZON_MS = 120L * 60_000L
    private const val GRADE_GRACE_MS = 20L * 60_000L
    private const val MARK_MAX_AGE_MS = 120_000L
    private const val READ_TTL_MS = 6L * 60_000L
    private const val MAX_PENDING = 6_000
    private const val STRONG_PCT = 3.0
    private const val PROOF_PCT = 2.0
    private const val MIN_N = 60
    private const val MIN_POSITIONS = 15
    private const val MAX_HOLD_VETO_MS = 60L * 60_000L
    private const val BOOK_DECAY = 0.997
    private const val PERSIST_KEY = "CORTEX_EXIT_7897"
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
        val byBucket = Array(Bucket.values().size) { CortexLedger7885.Stat() }
        val positions = Array(Bucket.values().size) { LinkedHashSet<String>() }
    }

    private val ledger = CortexLedger7885()
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
        val px = ts.lastPrice
        if (!px.isFinite() || px <= 0.0 || nowMs - ts.lastPriceUpdate > MARK_MAX_AGE_MS) return
        lastSample[key] = nowMs
        ensureLoaded()
        val lane = laneOf(ts)
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
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
    }

    /** Called from ExitRegret7752.tick with the loop's fresh-price closure: grades due samples. */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        ensureLoaded()
        val unpriced = ArrayList<String>()
        for ((k, r) in pending.entries.toList()) {
            val due = r.atMs + if (r.runner) RUNNER_HORIZON_MS else HORIZON_MS
            if (nowMs < due) continue
            val px = (try { priceFor(r.mint) } catch (_: Throwable) { null })?.takeIf { it.isFinite() && it > 0.0 }
                ?: registryMark(r.mint, nowMs)
                ?: offMarks[r.mint]?.takeIf { nowMs - it.second <= MARK_MAX_AGE_MS }?.first
            if (px == null) {
                if (nowMs - due > GRADE_GRACE_MS) { pending.remove(k); inc("LOST_MARK") } else unpriced.add(r.mint)
                continue
            }
            pending.remove(k)
            if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.basisSuspect7738(r.px, px)) { inc("BASIS_SUSPECT"); continue }
            val fwd = (px / r.px - 1.0) * 100.0
            synchronized(this) {
                ledger.grade(r.lane, r.ids, r.edges, r.raws, fwd, fwd, r.regime)
                calibration.learn(r.lane, r.fused.edgePct, r.fused.laneMean, fwd.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX))
                val b = books.getOrPut(r.lane) { Book() }
                for (st in b.byBucket) st.scale(BOOK_DECAY)
                b.byBucket[r.bucket.ordinal].add(fwd.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), fwd >= CortexLedger7885.RUNNER_GROSS_PCT)
                val set = b.positions[r.bucket.ordinal]
                set.add(r.positionId)
                if (set.size > 400) set.remove(set.first())
            }
            graded.incrementAndGet()
            if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
        }
        if (unpriced.isNotEmpty()) fetch(unpriced, nowMs)
    }

    private fun registryMark(mint: String, nowMs: Long): Double? = try {
        val m = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(mint)
        if (m == null || nowMs - m.timestampMs > MARK_MAX_AGE_MS) null else m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
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
            if (calibration.slope(lane) < 0.5) { inc("SUSPENDED_INCONSISTENT_$lane"); return false }
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
            val entry = ts.position.entryPrice
            val px = ts.lastPrice
            if (entry <= 0.0 || px <= 0.0 || nowMs - ts.lastPriceUpdate > MARK_MAX_AGE_MS) return false
            // V5.0.7925 — break-even is NET of the round trip (fees, priority, slippage,
            // impact): +2% gross on a 4% trip is a loss, and is never held.
            val costPct7925 = try {
                val sizeSol = ts.position.costSol
                val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
                com.lifecyclebot.engine.truth.FieldManual7715.roundTripCostPct7766(
                    sizeSol, if (solUsd.isFinite() && solUsd > 0.0) sizeSol * solUsd else 0.0, ts.lastLiquidityUsd,
                ).takeIf { it.isFinite() } ?: 0.0
            } catch (_: Throwable) { 0.0 }
            if ((px / entry - 1.0) * 100.0 <= costPct7925) return false
            val r = freshRead(ts, nowMs) ?: return false
            if (r.bucket != Bucket.HOLD_STRONG) return false
            val key = posKey(ts)
            if (!proven(r.lane, Bucket.HOLD_STRONG)) { inc("SHADOW_HOLD"); return false }
            val since = holdVetoSince.getOrPut(key) { nowMs }
            if (nowMs - since > MAX_HOLD_VETO_MS) { inc("HOLD_CAP_REACHED"); return false }
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
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("calibration")?.let { calibration.decode(it) }
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
            } catch (_: Throwable) {}
        }
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    fun persistNow7930() {
        if (!loaded) return
        persist()
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                org.json.JSONObject().put("ledger", ledger.encode()).put("calibration", calibration.encode()).put("books", org.json.JSONObject().also { j ->
                    books.forEach { (k, b) ->
                        j.put(k, (b.byBucket.map { it.encode() } + b.positions.map { s -> s.toList().takeLast(200).joinToString(",") { it.replace(",", "").replace("|", "") } }).joinToString("|"))
                    }
                }).toString()
            }
            LearningPersistence.save(PERSIST_KEY, json)
        } catch (_: Throwable) {}
    }

    private fun fmt(s: CortexLedger7885.Stat): String = if (s.n < 1.0) "-" else "n${s.n.toInt()}/${"%+.1f".format(s.mean())}%"

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val seated = ledger.seats.entries.map { it.key to it.value.authority() }.filter { it.second > 0.0 }.sortedByDescending { it.second }
            "bar=EXIT_BAR_V1 horizon=30m(runner 120m) sampled/3m pending=${pending.size} graded=${graded.get()} seated=${seated.size} slope=${calibration.line()}\n" +
                "      actions: ${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      seated: ${seated.take(10).joinToString(" · ") { (k, a) -> "$k a=${"%.2f".format(a)}" }.ifBlank { "none yet" }}\n" +
                "      lanes: ${books.entries.take(8).joinToString(" · ") { (lane, b) ->
                    "$lane sell=${fmt(b.byBucket[0])}/${b.positions[0].size}p neutral=${fmt(b.byBucket[1])} hold=${fmt(b.byBucket[2])}/${b.positions[2].size}p " +
                        "holdAuth=${holdProven(b.byBucket[2], b.positions[2].size)} sellAuth=${sellProven(b.byBucket[0], b.positions[0].size)}"
                }.ifBlank { "no graded samples yet" }}"
        }
    }
}
