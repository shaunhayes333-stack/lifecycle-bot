package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.CapitalThroughput7951
import com.lifecyclebot.engine.truth.ExitThroughputAuthority6727
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697
import com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * V5.0.7962 — CELL ALLOCATOR. With 4-6 routable orders on a ~0.2 SOL wallet the money is in
 * putting slots and size on the best decision cells, not in each lane gating on its own.
 *
 * 5.0.7958 forward labels: PUMP_PORTAL_WS|MOONSHOT|MC_10K_100K|AGE_LT15M n39 net +157.5% wr51%,
 * admitted60 +10.5%, refused60 +8.5%, slow lanes negative. CASHGEN owned candidates it then
 * refused, and capital rotation never fired (the wallet never ran out of routable orders:
 * tradeable 0.2077 = 7 orders against 4 open, so the 7948 cheap exit returned every tick).
 *
 * A Thompson-sampling bandit over decision cells:
 *  - cell = ForwardReturnLabeler7731.cellKey (source|lane|mcap band|age band), with
 *    hierarchical shrinkage cell -> lane|setup (LanePlaybook7907 via ExitProfile7955.entrySetup7955)
 *    -> lane -> global, so sparse cells borrow strength from their parents;
 *  - rewards = the 5-minute net label (slot 60, already net of cost + EntryChase7961) for every
 *    labelled decision, plus realised whole-position returns (CanonicalFinalizedTradeBus6464),
 *    live weighted [LIVE_WEIGHT_7962]x. Labels are market truth (mode-neutral); live and paper
 *    outcomes are kept in separate books and never mix;
 *  - posterior: Normal-Inverse-Gamma on winsorized returns; Thompson draws are deterministic per
 *    (cell, 10 s bucket) so one sort / one gate pass sees one consistent draw.
 *
 * Used where it changes money:
 *  a) the LAST routable live slot goes to the better cell (never below a positive benchmark set by
 *     the best waiting candidate / the weakest open position); a top cell refused for capital is
 *     capital demand, and lets a flat/negative-EV open position past its useful-hold wait in the
 *     existing 7948 rotation (every other rotation rule unchanged);
 *  b) size: fractional-Kelly multiplier from the posterior (0.5x..2.5x), multiplied with
 *     Cortex7885.convictionMult and capped at the same 2.5x; unproven cells stay at 1x; a live
 *     shrink never goes under the routable minimum (shrinking is not a refusal);
 *  c) watchlist priority: the highest sampled EV per hold minute is processed first.
 * It never bypasses a hard safety, mark, route, wallet or FDG block: it only runs after them.
 */
object CellAllocator7962 {

    // ── constants ──────────────────────────────────────────────────────────
    const val LIVE_WEIGHT_7962 = 3.0
    const val WIN_LO_PCT_7962 = -100.0
    const val WIN_HI_PCT_7962 = 300.0
    const val MIN_MULT_7962 = 0.5
    /** Same ceiling as Cortex7885.CONVICTION_MAX_MULT: the combined size-up never exceeds it. */
    const val MAX_MULT_7962 = 2.5
    const val PROVEN_N_7962 = 20.0
    const val PROVEN_P_7962 = 0.80
    private const val FULL_N_7962 = 60.0
    private const val KELLY_FRACTION_7962 = 0.25
    private const val PRIOR_ALPHA_7962 = 2.0
    private const val GLOBAL_PRIOR_KAPPA_7962 = 2.0
    private const val SHRINK_KAPPA_7962 = 10.0
    private const val GLOBAL_PRIOR_VAR_7962 = 900.0
    private const val MIN_VAR_7962 = 25.0
    private const val MAX_VAR_7962 = 250_000.0
    private const val MAX_W_7962 = 600.0
    private const val LABEL_HOLD_MIN_7962 = 5.0
    private const val MIN_HOLD_MIN_7962 = 2.0
    private const val MAX_HOLD_MIN_7962 = 120.0
    private const val SLOT_MARGIN_7962 = 0.1
    private const val DRAW_BUCKET_MS_7962 = 10_000L
    private const val WAIT_FRESH_MS_7962 = 30_000L
    private const val WAIT_MAX_BLOCK_MS_7962 = 90_000L
    private const val TOP_DEMAND_MS_7962 = 120_000L
    const val MIN_ROTATE_AGE_MS_7962 = 10L * 60_000L
    private const val PRIORITY_PER_SCORE_7962 = 15.0
    private const val MIN_PRIORITY_N_7962 = 5.0
    private const val MAX_NODES_7962 = 4_000
    private const val PREFS_7962 = "cell_allocator_7962"

    // ── sufficient statistics (pure) ───────────────────────────────────────

    /** Weighted sufficient statistics of winsorized net returns (percent), plus realised hold minutes. */
    class Stat7962 {
        var w = 0.0; var sx = 0.0; var sxx = 0.0; var holdW = 0.0; var holdSum = 0.0

        fun add(netPct: Double, weight: Double, holdMin: Double = Double.NaN) {
            if (!netPct.isFinite() || !(weight > 0.0)) return
            val x = winsorize7962(netPct)
            w += weight; sx += weight * x; sxx += weight * x * x
            if (holdMin.isFinite() && holdMin > 0.0) { holdW += 1.0; holdSum += holdMin }
            if (w > MAX_W_7962) {
                // Forgetting: the oldest evidence fades once a node is deep, so a cell can turn.
                val k = MAX_W_7962 / w
                w *= k; sx *= k; sxx *= k
            }
        }

        /** V5.0.7997 — a label revised on a later checkpoint replaces its earlier value (weight 1). */
        fun revise(oldNet: Double, newNet: Double) {
            if (!(w > 0.0) || !oldNet.isFinite() || !newNet.isFinite()) return
            val xo = winsorize7962(oldNet); val xn = winsorize7962(newNet)
            sx += xn - xo; sxx = (sxx + xn * xn - xo * xo).coerceAtLeast(0.0)
        }

        fun plus(o: Stat7962?): Stat7962 {
            val r = Stat7962()
            r.w = w + (o?.w ?: 0.0); r.sx = sx + (o?.sx ?: 0.0); r.sxx = sxx + (o?.sxx ?: 0.0)
            r.holdW = holdW + (o?.holdW ?: 0.0); r.holdSum = holdSum + (o?.holdSum ?: 0.0)
            return r
        }

        fun encode(): String = "$w,$sx,$sxx,$holdW,$holdSum"
    }

    fun winsorize7962(x: Double): Double = x.coerceIn(WIN_LO_PCT_7962, WIN_HI_PCT_7962)

    /** Normal-Inverse-Gamma posterior over a cell's mean net return (percent). */
    data class Posterior7962(val mean: Double, val kappa: Double, val alpha: Double, val beta: Double, val nEff: Double) {
        /** Predictive variance of one return (percent^2). */
        val predVar: Double get() = if (alpha > 1.0) beta / (alpha - 1.0) else Double.NaN
        /** P(mean > 0), Student-t approximated by a normal. */
        val pPos: Double get() {
            val scale = sqrt(beta / (alpha * kappa))
            return if (scale.isFinite() && scale > 0.0) phi7962(mean / scale) else 0.5
        }
    }

    /** Pure NIG update of the prior (m0, kappa0, variance0) with [stat]. */
    fun posterior7962(stat: Stat7962?, priorMean: Double, priorKappa: Double, priorVar: Double): Posterior7962 {
        val a0 = PRIOR_ALPHA_7962
        val b0 = priorVar.coerceIn(MIN_VAR_7962, MAX_VAR_7962) * (a0 - 1.0)
        val n = stat?.w ?: 0.0
        if (stat == null || !(n > 0.0)) return Posterior7962(priorMean, priorKappa, a0, b0, 0.0)
        val xbar = stat.sx / n
        val ss = (stat.sxx - n * xbar * xbar).coerceAtLeast(0.0)
        val kn = priorKappa + n
        val mn = (priorKappa * priorMean + n * xbar) / kn
        val an = a0 + n / 2.0
        val bn = b0 + ss / 2.0 + priorKappa * n * (xbar - priorMean) * (xbar - priorMean) / (2.0 * kn)
        return Posterior7962(mn, kn, an, bn, n)
    }

    /**
     * Pure hierarchical shrinkage: [chain] runs from the global node to the leaf; each level's
     * prior is its parent's posterior mean and predictive variance. nEff is the leaf's own.
     */
    fun hierarchy7962(chain: List<Stat7962?>): Posterior7962 {
        var m = 0.0
        var v = GLOBAL_PRIOR_VAR_7962
        var post = posterior7962(null, m, GLOBAL_PRIOR_KAPPA_7962, v)
        chain.forEachIndexed { i, s ->
            post = posterior7962(s, m, if (i == 0) GLOBAL_PRIOR_KAPPA_7962 else SHRINK_KAPPA_7962, v)
            m = post.mean
            val pv = post.predVar
            v = if (pv.isFinite()) pv.coerceIn(MIN_VAR_7962, MAX_VAR_7962) else GLOBAL_PRIOR_VAR_7962
        }
        return post
    }

    /** Pure Thompson draw of the mean from the NIG posterior (sigma^2 ~ InvGamma, mu ~ N). */
    fun thompsonDraw7962(p: Posterior7962, rng: Random): Double {
        val g = gamma7962(p.alpha, rng)
        val sigma2 = if (g > 0.0) p.beta / g else p.beta
        val sd = sqrt((sigma2 / p.kappa.coerceAtLeast(1e-9)).coerceAtLeast(0.0))
        return p.mean + sd * rng.nextGaussian()
    }

    /** Pure ranking score: net EV (percent of capital) per expected hold minute. */
    fun score7962(evPct: Double, holdMin: Double): Double =
        if (!evPct.isFinite()) Double.NaN
        else evPct / (if (holdMin.isFinite()) holdMin else LABEL_HOLD_MIN_7962).coerceIn(MIN_HOLD_MIN_7962, MAX_HOLD_MIN_7962)

    /** Pure: the candidates ordered best-first (ties by key). */
    fun rank7962(scores: Map<String, Double>): List<String> =
        scores.entries.filter { it.value.isFinite() }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key }

    /**
     * Pure size multiplier from the posterior, in [MIN_MULT_7962, MAX_MULT_7962]:
     *  - unproven (leaf nEff < PROVEN_N) -> 1.0;
     *  - proven positive (P(mean>0) >= PROVEN_P): quarter-Kelly stake / base share, graduated by
     *    confidence (depth and P(mean>0));
     *  - proven negative (P(mean>0) <= 1 - PROVEN_P): shrinks toward MIN_MULT_7962.
     */
    fun kellySizeMult7962(p: Posterior7962, baseShare: Double): Double {
        if (!p.mean.isFinite() || p.nEff < PROVEN_N_7962) return 1.0
        val depth = (p.nEff / FULL_N_7962).coerceAtMost(1.0)
        val pp = p.pPos
        if (p.mean > 0.0 && pp >= PROVEN_P_7962) {
            val v = p.predVar
            if (!v.isFinite() || v <= 0.0 || !(baseShare > 0.0)) return 1.0
            val f = KELLY_FRACTION_7962 * (p.mean / 100.0) / (v / 10_000.0)
            val raw = (f / baseShare).coerceIn(1.0, MAX_MULT_7962)
            val g = depth * ((pp - PROVEN_P_7962) / 0.15).coerceIn(0.0, 1.0)
            return (1.0 + g * (raw - 1.0)).coerceIn(1.0, MAX_MULT_7962)
        }
        val negP = 1.0 - PROVEN_P_7962
        if (p.mean < 0.0 && pp <= negP) {
            val g = depth * ((negP - pp) / 0.15).coerceIn(0.0, 1.0)
            return (1.0 - g * (1.0 - MIN_MULT_7962)).coerceIn(MIN_MULT_7962, 1.0)
        }
        return 1.0
    }

    /** Pure: the allocator multiple times the Cortex conviction multiple, under the one size-up cap. */
    fun combinedMult7962(cortexMult: Double, allocMult: Double): Double {
        val c = if (cortexMult.isFinite() && cortexMult > 0.0) cortexMult else 1.0
        val a = if (allocMult.isFinite() && allocMult > 0.0) allocMult else 1.0
        return (c * a).coerceIn(MIN_MULT_7962, MAX_MULT_7962)
    }

    /**
     * Pure: does this candidate give up the LAST free routable slot? Only when exactly one is left
     * and a positive benchmark (the best other waiting candidate, or the weakest open position)
     * beats its sampled score by more than the margin.
     */
    fun lastSlotRefusal7962(candScore: Double, bestWaitingScore: Double?, openFloorScore: Double?, freeUnits: Int): Boolean {
        if (freeUnits != 1 || !candScore.isFinite()) return false
        val bench = listOfNotNull(bestWaitingScore, openFloorScore).filter { it.isFinite() }.maxOrNull() ?: return false
        if (bench <= 0.0) return false
        return candScore < bench - SLOT_MARGIN_7962
    }

    /** Pure: may a top cell waiting on capital rotate this held position before its useful hold? */
    fun earlyRotation7962(heldMeanPct: Double, heldScore: Double, topScore: Double, posAgeMs: Long): Boolean {
        if (posAgeMs < MIN_ROTATE_AGE_MS_7962 || !topScore.isFinite() || topScore <= 0.0) return false
        if (!heldMeanPct.isFinite()) return false
        return heldMeanPct <= 0.0 || (heldScore.isFinite() && heldScore < topScore * 0.25)
    }

    private fun phi7962(z: Double): Double {
        if (z.isNaN()) return 0.5
        if (z.isInfinite()) return if (z > 0.0) 1.0 else 0.0
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val d = 0.3989422804014327 * exp(-z * z / 2.0)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (z >= 0.0) 1.0 - p else p
    }

    /** Marsaglia-Tsang Gamma(alpha, 1), alpha >= 1. */
    private fun gamma7962(alpha: Double, rng: Random): Double {
        val a = if (alpha.isFinite()) alpha.coerceAtLeast(1.0) else 1.0
        val d = a - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        for (i in 0 until 64) {
            var x: Double
            var v: Double
            do { x = rng.nextGaussian(); v = 1.0 + c * x } while (v <= 0.0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (u > 0.0 && ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
        return a
    }

    // ── runtime books ──────────────────────────────────────────────────────

    private const val EV_LABEL = "L"
    private const val EV_LIVE = "V"
    private const val EV_PAPER = "P"

    private val nodes = ConcurrentHashMap<String, Stat7962>()

    private data class Ref7962(val cell: String, val band: String, val lane: String, val setup: String)
    private data class Waiting7962(val score: Double, val firstAtMs: Long, val lastAtMs: Long)
    private data class TopDemand7962(val score: Double, val atMs: Long)

    private val refs = ConcurrentHashMap<String, Pair<Ref7962, Long>>()       // mint|LIVE / mint|PAPER
    private val lastLane = ConcurrentHashMap<String, String>()                // mint -> lane last gated / sized
    private val waiting = ConcurrentHashMap<String, Waiting7962>()            // live candidates at the gate
    private val topDemand = ConcurrentHashMap<String, TopDemand7962>()        // top cells refused for capital
    private val priorityCache = ConcurrentHashMap<String, Pair<Long, Double>>()
    private val sizeMults = java.util.ArrayDeque<Double>()

    @Volatile private var liveMode7962 = false
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var lastPersistMs = 0L
    private val updatesSincePersist = AtomicLong(0)
    private val labelsIn = AtomicLong(0)
    private val liveCloses = AtomicLong(0)
    private val paperCloses = AtomicLong(0)
    private val slotRefusals = AtomicLong(0)
    private val topDemandNotes = AtomicLong(0)
    private val earlyRotationGrants = AtomicLong(0)
    private val rotations = AtomicLong(0)

    private fun nodeKey(ev: String, node: String) = "$ev|$node"
    private fun laneNode(lane: String) = "LANE|$lane"
    private fun setupNode(lane: String, setup: String) = "LS|$lane|$setup"
    private fun cellNode(cell: String) = "CELL|$cell"
    private fun bandNode(band: String) = "BAND|$band"
    private const val GLOBAL_NODE = "G"

    private fun canonLane(lane: String) = lane.trim().uppercase().ifBlank { "UNKNOWN" }.take(24)

    /** source|mcap|age: the cell without its lane (for candidates no lane has claimed yet). */
    private fun bandOf(cell: String): String {
        val f = cell.split('|')
        return if (f.size >= 4) "${f[0]}|${f[2]}|${f[3]}" else cell
    }

    private fun addTo(ev: String, node: String, net: Double, weight: Double, holdMin: Double = Double.NaN) {
        val k = nodeKey(ev, node)
        val s = nodes[k] ?: run {
            if (nodes.size >= MAX_NODES_7962) pruneNodes()
            nodes.getOrPut(k) { Stat7962() }
        }
        synchronized(s) { s.add(net, weight, holdMin) }
    }

    private fun pruneNodes() {
        val victims = nodes.entries
            .filter { !it.key.endsWith("|$GLOBAL_NODE") && !it.key.contains("|LANE|") }
            .sortedBy { synchronized(it.value) { it.value.w } }
            .take(MAX_NODES_7962 / 10)
        victims.forEach { nodes.remove(it.key, it.value) }
    }

    private fun addAll(ev: String, ref: Ref7962, net: Double, weight: Double, holdMin: Double = Double.NaN) {
        addTo(ev, GLOBAL_NODE, net, weight, holdMin)
        addTo(ev, laneNode(ref.lane), net, weight, holdMin)
        if (ref.setup.isNotBlank()) addTo(ev, setupNode(ref.lane, ref.setup), net, weight, holdMin)
        if (ref.cell.isNotBlank()) addTo(ev, cellNode(ref.cell), net, weight, holdMin)
        if (ref.band.isNotBlank()) addTo(ev, bandNode(ref.band), net, weight, holdMin)
        if (updatesSincePersist.incrementAndGet() >= 50) persist()
    }

    /** Label evidence + this mode's realised evidence (live and paper never mix). */
    private fun statFor(node: String, live: Boolean): Stat7962? {
        val l = nodes[nodeKey(EV_LABEL, node)]
        val m = nodes[nodeKey(if (live) EV_LIVE else EV_PAPER, node)]
        if (l == null && m == null) return null
        val a = l?.let { synchronized(it) { Stat7962().plus(it) } } ?: Stat7962()
        return m?.let { synchronized(it) { a.plus(it) } } ?: a
    }

    private fun posteriorFor(ref: Ref7962, live: Boolean, withSetup: Boolean = true): Posterior7962 {
        val chain = ArrayList<Stat7962?>(4)
        chain.add(statFor(GLOBAL_NODE, live))
        chain.add(statFor(laneNode(ref.lane), live))
        if (withSetup && ref.setup.isNotBlank()) chain.add(statFor(setupNode(ref.lane, ref.setup), live))
        chain.add(statFor(cellNode(ref.cell), live))
        return hierarchy7962(chain)
    }

    /** Expected hold of one trade in this lane and mode: realised closes, shrunk to the label horizon. */
    private fun holdFor(lane: String, live: Boolean): Double {
        val s = nodes[nodeKey(if (live) EV_LIVE else EV_PAPER, laneNode(lane))]
        val (hw, hs) = s?.let { synchronized(it) { it.holdW to it.holdSum } } ?: (0.0 to 0.0)
        return ((hs + LABEL_HOLD_MIN_7962 * 3.0) / (hw + 3.0)).coerceIn(MIN_HOLD_MIN_7962, MAX_HOLD_MIN_7962)
    }

    private fun drawFor(leaf: String, post: Posterior7962, live: Boolean, nowMs: Long): Double {
        val seed = leaf.hashCode().toLong() * 1_000_003L + nowMs / DRAW_BUCKET_MS_7962 + (if (live) 7L else 0L)
        return thompsonDraw7962(post, Random(seed))
    }

    private fun refFor(ts: TokenState, lane: String, nowMs: Long, withSetup: Boolean): Ref7962 {
        val l = canonLane(lane)
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val cell = ForwardReturnLabeler7731.cellKey(ts.source, l, ts.lastMcap, ageMs)
        val setup = if (!withSetup) "" else try { ExitProfile7955.entrySetup7955(ts, l, nowMs) } catch (_: Throwable) { "" }
        return Ref7962(cell, bandOf(cell), l, setup)
    }

    private fun remember(mint: String, live: Boolean, ref: Ref7962, nowMs: Long) {
        refs["$mint|${if (live) "LIVE" else "PAPER"}"] = ref to nowMs
        lastLane[mint] = ref.lane
        if (refs.size > 2_000) refs.entries.removeIf { nowMs - it.value.second > 6L * 60L * 60_000L }
        if (lastLane.size > 4_000) lastLane.clear()
    }

    private fun isTop(p: Posterior7962): Boolean = p.nEff >= PROVEN_N_7962 && p.mean > 0.0 && p.pPos >= PROVEN_P_7962

    // ── evidence hooks ─────────────────────────────────────────────────────

    /** ForwardReturnLabeler7731.bookSixty7944: one 5-minute net label (cost + chase already charged). */
    fun onLabel7962(cell: String, lane: String, setup: String, netPct: Double) {
        if (cell.isBlank() || !netPct.isFinite()) return
        val l = canonLane(lane)
        if (l.startsWith("PLANWAIT_") || l.startsWith("PLANADMIT_")) return
        addAll(EV_LABEL, Ref7962(cell, bandOf(cell), l, setup.trim()), netPct, 1.0)
        labelsIn.incrementAndGet()
    }

    /** V5.0.7997 — ForwardReturnLabeler7731: a later checkpoint revises a label in every node it was booked to. */
    fun reviseLabel7997(cell: String, lane: String, setup: String, oldNet: Double, newNet: Double) {
        if (cell.isBlank() || !oldNet.isFinite() || !newNet.isFinite()) return
        val l = canonLane(lane)
        if (l.startsWith("PLANWAIT_") || l.startsWith("PLANADMIT_")) return
        val ref = Ref7962(cell, bandOf(cell), l, setup.trim())
        val nodeList = buildList {
            add(GLOBAL_NODE); add(laneNode(ref.lane))
            if (ref.setup.isNotBlank()) add(setupNode(ref.lane, ref.setup))
            add(cellNode(ref.cell))
            if (ref.band.isNotBlank()) add(bandNode(ref.band))
        }
        for (node in nodeList) nodes[nodeKey(EV_LABEL, node)]?.let { s -> synchronized(s) { s.revise(oldNet, newNet) } }
    }

    /** CanonicalFinalizedTradeBus6464.publish: a realised close, booked in its own mode's book. */
    fun onClose7962(env: CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || !env.learningEligible || !env.realizedReturnPct.isFinite() || env.mint.isBlank()) return
        val live = when {
            env.mode.equals("live", ignoreCase = true) -> true
            env.mode.equals("paper", ignoreCase = true) -> false
            else -> return
        }
        val lane = canonLane(env.lane)
        if (lane == "WALLET_RECOVERED") return
        val ref = refs.remove("${env.mint}|${if (live) "LIVE" else "PAPER"}")?.first
            ?: Ref7962("", "", lane, "")
        val holdMin = if (env.holdingTimeMs > 0L) env.holdingTimeMs / 60_000.0 else Double.NaN
        addAll(if (live) EV_LIVE else EV_PAPER, ref, env.realizedReturnPct, if (live) LIVE_WEIGHT_7962 else 1.0, holdMin)
        (if (live) liveCloses else paperCloses).incrementAndGet()
    }

    // ── a) slot competition + rotation ─────────────────────────────────────

    /**
     * ExecutableOpenGate: the existing exit-throughput verdict, then (live, allowed only) the last
     * free routable slot goes to the better cell. Fails open.
     */
    fun throughput7962(modeUpper: String, lane: String, mint: String): ExitThroughputAuthority6727.Verdict {
        val v = ExitThroughputAuthority6727.evaluate(modeUpper, lane)
        if (!v.allow) return v
        val refusal = try { slotCompetition7962(modeUpper.equals("LIVE", ignoreCase = true), lane, mint, System.currentTimeMillis()) } catch (_: Throwable) { null }
        return if (refusal == null) v else v.copy(allow = false, reason = refusal)
    }

    private fun slotCompetition7962(live: Boolean, lane: String, mint: String, nowMs: Long): String? {
        if (mint.isBlank() || lane.isBlank()) return null
        liveMode7962 = live
        val ts = BotService.status.tokens[mint] ?: return null
        val ref = refFor(ts, lane, nowMs, withSetup = true)
        remember(mint, live, ref, nowMs)
        if (!live) return null
        val post = posteriorFor(ref, true)
        val cand = score7962(drawFor(ref.cell, post, true, nowMs), holdFor(ref.lane, true))
        val solUsd = WalletManager.lastKnownSolPrice
        if (!solUsd.isFinite() || solUsd <= 0.0) return null
        val liquid = BotService.status.walletSol
        val reserve = LiveSpendReserveAuthority7255.RESERVE_SOL
        val routableMin = com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(0.0, solUsd).routableMinSol
        val open = CanonicalPositionAuthority6441.openPositions().filter { it.mode.equals("live", ignoreCase = true) }
        val freeSlots = LiveConcentrationDoctrine7697.slots(0.0) - open.size
        val free = minOf(CapitalThroughput7951.routableCapacity7951(liquid, reserve, routableMin), freeSlots)
        val openMints = open.map { it.mint }.toSet()
        waiting.entries.removeIf { nowMs - it.value.lastAtMs > WAIT_MAX_BLOCK_MS_7962 || it.key in openMints }
        val best = waiting.entries
            .filter { it.key != mint && nowMs - it.value.lastAtMs <= WAIT_FRESH_MS_7962 && nowMs - it.value.firstAtMs <= WAIT_MAX_BLOCK_MS_7962 }
            .maxOfOrNull { it.value.score }
        val prior = waiting[mint]
        waiting[mint] = Waiting7962(cand, prior?.firstAtMs ?: nowMs, nowMs)
        if (free <= 0) {
            if (isTop(post)) {
                topDemand[mint] = TopDemand7962(score7962(post.mean, holdFor(ref.lane, true)), nowMs)
                topDemandNotes.incrementAndGet()
                CapitalThroughput7951.noteCapitalDemand7951(ref.lane, mint, "CELL_ALLOCATOR_TOP_CELL_7962", nowMs)
            }
            return null
        }
        val openFloor = open.filter { !it.lane.equals("WALLET_RECOVERED", ignoreCase = true) }.mapNotNull { p ->
            val r = refs["${p.mint}|LIVE"]?.first ?: BotService.status.tokens[p.mint]?.let { refFor(it, p.lane, nowMs, withSetup = false) }
            r?.let { score7962(posteriorFor(it, true).mean, holdFor(it.lane, true)) }
        }.filter { it.isFinite() }.minOrNull()
        if (!lastSlotRefusal7962(cand, best, openFloor, free)) return null
        slotRefusals.incrementAndGet()
        val reason = "CELL_ALLOCATOR_LAST_SLOT_TO_BETTER_CELL_7962"
        try {
            PipelineHealthCollector.labelInc(reason)
            ForensicLogger.lifecycle(
                reason,
                "mint=${mint.take(10)} cell=${ref.cell} setup=${ref.setup} cand=${"%.3f".format(cand)} " +
                    "bestWaiting=${best?.let { "%.3f".format(it) } ?: "-"} openFloor=${openFloor?.let { "%.3f".format(it) } ?: "-"} " +
                    "free=$free action=last_routable_slot_kept_for_the_better_cell",
            )
        } catch (_: Throwable) {}
        return reason
    }

    /**
     * Executor.capitalRotation7948: the age the rotation rule reads. While a top cell is waiting on
     * capital, a held position (>= 10 min) whose own cell is flat/negative EV (or far below the top
     * cell) reads as past its useful hold; every other rotation rule (not green, no runner lock,
     * no new high for 15 min, fresh mark, cooldown, frees one order) is untouched.
     */
    fun rotationAgeMs7962(ts: TokenState, lane: String, posAgeMs: Long, laneMaxHoldMinutes: Int): Long {
        return try {
            val now = System.currentTimeMillis()
            topDemand.entries.removeIf { now - it.value.atMs > TOP_DEMAND_MS_7962 }
            val top = topDemand.entries.filter { it.key != ts.mint }.maxOfOrNull { it.value.score } ?: return posAgeMs
            val ref = refs["${ts.mint}|LIVE"]?.first ?: refFor(ts, lane, now, withSetup = false)
            val mean = posteriorFor(ref, true).mean
            if (!earlyRotation7962(mean, score7962(mean, holdFor(ref.lane, true)), top, posAgeMs)) return posAgeMs
            earlyRotationGrants.incrementAndGet()
            maxOf(posAgeMs, CapitalDrawdown7948.usefulHoldMs7948(laneMaxHoldMinutes))
        } catch (_: Throwable) { posAgeMs }
    }

    /** Executor.capitalRotation7948: one rotation fired. */
    fun noteRotation7962() { rotations.incrementAndGet() }

    // ── b) size ────────────────────────────────────────────────────────────

    /**
     * TraderSizingBridge6444: [cortexMult] (Cortex7885.convictionMult) times the cell's Kelly
     * multiple, capped at the one 2.5x size-up ceiling. A live shrink stops at the routable
     * minimum. Fails back to [cortexMult].
     */
    fun combinedSizeMult7962(cortexMult: Double, mint: String, lane: String, paperMode: Boolean, requestedSol: Double): Double {
        val base = if (cortexMult.isFinite() && cortexMult > 0.0) cortexMult else 1.0
        if (mint.isBlank() || lane.isBlank()) return base
        return try {
            val ts = BotService.status.tokens[mint] ?: return base
            val now = System.currentTimeMillis()
            val live = !paperMode
            liveMode7962 = live
            val ref = refFor(ts, lane, now, withSetup = true)
            remember(mint, live, ref, now)
            val alloc = kellySizeMult7962(posteriorFor(ref, live), LiveConcentrationDoctrine7697.share(0.0))
            var total = combinedMult7962(base, alloc)
            if (live && total < 1.0 && requestedSol > 0.0) {
                val solUsd = WalletManager.lastKnownSolPrice
                val routableMin = if (solUsd.isFinite() && solUsd > 0.0)
                    com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(0.0, solUsd).routableMinSol else 0.0
                total = if (requestedSol <= routableMin) 1.0 else maxOf(total, routableMin / requestedSol)
            }
            synchronized(sizeMults) {
                sizeMults.addLast(total)
                while (sizeMults.size > 200) sizeMults.removeFirst()
            }
            total
        } catch (_: Throwable) { base }
    }

    // ── c) priority ────────────────────────────────────────────────────────

    /**
     * BotService watchlist priority: the existing entryScore / momentum / volume terms plus the
     * cell's sampled EV per hold minute. [nowMs] is the sort's one clock and the value is cached per
     * (mint, 10 s bucket), so one sort always sees one value per mint (a consistent comparator).
     */
    fun watchPriority7962(ts: TokenState, nowMs: Long): Double {
        val base = ts.entryScore + ts.meta.momScore * 0.8 + ts.meta.volScore * 0.5
        return base + try { priorityBoost7962(ts, nowMs) } catch (_: Throwable) { 0.0 }
    }

    private fun priorityBoost7962(ts: TokenState, nowMs: Long): Double {
        val bucket = nowMs / DRAW_BUCKET_MS_7962
        priorityCache[ts.mint]?.let { (b, v) -> if (b == bucket) return v }
        val live = liveMode7962
        val lane = lastLane[ts.mint]
        val boost = if (lane != null) {
            val ref = refFor(ts, lane, nowMs, withSetup = false)
            val laneN = statFor(laneNode(ref.lane), live)?.w ?: 0.0
            if (laneN < MIN_PRIORITY_N_7962) 0.0 else {
                val post = posteriorFor(ref, live, withSetup = false)
                score7962(drawFor(ref.cell, post, live, nowMs), holdFor(ref.lane, live))
            }
        } else {
            val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
            val band = bandOf(ForwardReturnLabeler7731.cellKey(ts.source, "X", ts.lastMcap, ageMs))
            val bs = statFor(bandNode(band), live)
            if ((bs?.w ?: 0.0) < MIN_PRIORITY_N_7962) 0.0 else {
                val post = hierarchy7962(listOf(statFor(GLOBAL_NODE, live), bs))
                score7962(drawFor(band, post, live, nowMs), LABEL_HOLD_MIN_7962)
            }
        }
        val v = if (boost.isFinite()) (boost * PRIORITY_PER_SCORE_7962).coerceIn(-120.0, 200.0) else 0.0
        if (priorityCache.size > 5_000) priorityCache.entries.removeIf { it.value.first != bucket }
        return priorityCache.putIfAbsent(ts.mint, bucket to v)?.takeIf { it.first == bucket }?.second
            ?: v.also { priorityCache[ts.mint] = bucket to v }
    }

    // ── persistence ────────────────────────────────────────────────────────

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try { context.applicationContext.getSharedPreferences(PREFS_7962, Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs = p
        try {
            p.getString("nodes", null)?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val f = row.substring(sep + 1).split(',')
                if (f.size != 5) return@forEach
                val s = Stat7962()
                s.w = f[0].toDoubleOrNull() ?: return@forEach
                s.sx = f[1].toDoubleOrNull() ?: 0.0
                s.sxx = f[2].toDoubleOrNull() ?: 0.0
                s.holdW = f[3].toDoubleOrNull() ?: 0.0
                s.holdSum = f[4].toDoubleOrNull() ?: 0.0
                if (s.w.isFinite() && s.w > 0.0) nodes[row.substring(0, sep)] = s
            }
        } catch (_: Throwable) {}
    }

    private fun persist() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < 60_000L) return
        lastPersistMs = now
        updatesSincePersist.set(0)
        try {
            val enc = nodes.entries.joinToString(";") { (k, s) -> "$k=${synchronized(s) { s.encode() }}" }
            p.edit().putString("nodes", enc).apply()
        } catch (_: Throwable) {}
    }

    // ── diag ───────────────────────────────────────────────────────────────

    fun statusLine(): String {
        val live = liveMode7962
        val cellKeys = nodes.keys.filter { it.contains("|CELL|") }.map { it.substringAfter("|CELL|") }.toSet()
        val posts = HashMap<String, Posterior7962>()
        for (c in cellKeys) {
            val lane = c.split('|').getOrNull(1) ?: continue
            val post = posteriorFor(Ref7962(c, bandOf(c), lane, ""), live, withSetup = false)
            if (post.nEff >= 10.0) posts[c] = post
        }
        val order = rank7962(posts.mapValues { it.value.mean })
        fun fmt(k: String): String {
            val p = posts[k] ?: return k
            return "$k n${p.nEff.toInt()}/${"%+.1f".format(p.mean)}%/p${(p.pPos * 100).toInt()}%"
        }
        val mults = synchronized(sizeMults) { sizeMults.sorted() }
        val sm = if (mults.isEmpty()) "-" else "${"%.2f".format(mults.first())}/${"%.2f".format(mults[mults.size / 2])}/${"%.2f".format(mults.last())}"
        return "cells=${cellKeys.size} book=${if (live) "LIVE" else "PAPER"} top=[${order.take(3).joinToString(", ") { fmt(it) }}] " +
            "bottom=[${order.takeLast(3).reversed().joinToString(", ") { fmt(it) }}] " +
            "slotRefusals=${slotRefusals.get()} rotations=${rotations.get()} earlyRotationGrants=${earlyRotationGrants.get()} " +
            "topCellCapitalDemand=${topDemandNotes.get()} sizeMult[min/med/max]=$sm " +
            "evidence[labels=${labelsIn.get()} liveCloses=${liveCloses.get()} paperCloses=${paperCloses.get()} nodes=${nodes.size}]"
    }
}
