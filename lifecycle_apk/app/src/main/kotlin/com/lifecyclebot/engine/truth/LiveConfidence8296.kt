package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8296 §CONFIDENCE_FOLLOWS_THE_LAST_REAL_CLOSES — the recent LIVE record of a lane as a sizing input.
 *
 * Owner: "it's gone from a money printer — doubling the wallet, growing confidence, building bigger trades — to just
 * losing money." LiveRiskPolicy7807's confidence inputs had no recency:
 *  - the expectancy multiplier (growth up to 1.5x) read the lane's last 2,500 clean live closes: a profitable era kept
 *    it at full growth while the newest closes all lost, and an old bleed kept a recovered lane shrunk for thousands of
 *    trades;
 *  - the proven-edge quarter-Kelly share (compounding size) was capped by the lane's whole realised record, so a run
 *    of fresh live losses never revoked it, and a setup whose own live closes lose still compounded on its labels.
 *
 * This object keeps the last [WINDOW_8296] verified live whole-position closes per lane (on-chain finality only:
 * LIVE_FINALIZED / LIVE_RECONCILED, net of every fee — CanonicalTradeFinalizedBus6450), persisted so a restart does
 * not forget them (never reset). Pure rules:
 *  - [recencyAdjusted8296]: a recent net loss or a 3-loss streak caps the expectancy multiplier at 1.0 and shrinks it
 *    by the recent mean (floor [RECENT_FLOOR_8296]); a full window that is statistically positive (mean - SE > 0, net
 *    SOL > 0) may grow it up to [RECENT_GROWTH_MAX_8296] even when older history is negative. Otherwise unchanged.
 *  - [kellyGate8296]: no compounding share while the lane's recent record is losing, or while the selected setup's own
 *    realised live closes average a loss. The gate only removes a size-up; it never adds one.
 * Paper closes never enter (mode filter), so paper results cannot size live orders.
 */
object LiveConfidence8296 {
    const val WINDOW_8296 = 12
    private const val MIN_RECENT_N_8296 = 3
    private const val LOSS_STREAK_8296 = 3
    const val RECENT_FLOOR_8296 = 0.50
    const val RECENT_GROWTH_MAX_8296 = 1.25
    private const val SETUP_MIN_N_8296 = 2.0
    private const val REALISED_ONLY_MIN_N_8296 = 3.0
    private const val CAP_PCT_8296 = 1_000.0
    private const val PERSIST_KEY_8296 = "LIVE_CONFIDENCE_8296"
    private const val MAX_IDS_8296 = 400
    /** Live compound pool (AutoCompoundEngine's live authority): share of a net live win reinvested, threshold, cap. */
    private const val LIVE_COMPOUND_SHARE_8296 = 0.45
    private const val LIVE_COMPOUND_THRESHOLD_SOL_8296 = 0.15
    const val LIVE_COMPOUND_MAX_MULT_8296 = 3.0

    data class Recent8296(val n: Int, val meanPct: Double, val sePct: Double, val netSol: Double, val lossStreak: Int)

    private val lanes8296 = HashMap<String, ArrayDeque<DoubleArray>>()   // lane -> [atMs, netPct, netSol], oldest first
    private val seen8296 = LinkedHashSet<String>()
    @Volatile private var loaded8296 = false
    @Volatile private var livePool8296 = 0.0
    private val accepted8296 = AtomicLong(0)
    private val rejected8296 = AtomicLong(0)
    private val kellyRevoked8296 = AtomicLong(0)

    // ── pure ──

    /** Pure: the recent record of closes given newest first as [netPct, netSol]; at most [window] are read. */
    fun recentOf8296(newestFirst: List<DoubleArray>, window: Int = WINDOW_8296): Recent8296 {
        val rows = newestFirst.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }.take(window.coerceAtLeast(1))
        if (rows.isEmpty()) return Recent8296(0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0)
        val pcts = rows.map { it[0].coerceIn(-100.0, CAP_PCT_8296) }
        val n = pcts.size
        val mean = pcts.average()
        val se = if (n > 1) kotlin.math.sqrt(pcts.sumOf { (it - mean) * (it - mean) } / (n - 1) / n) else Double.POSITIVE_INFINITY
        var streak = 0
        for (r in rows) { if (r[1] < 0.0) streak++ else break }
        return Recent8296(n, mean, se, rows.sumOf { it[1] }, streak)
    }

    /** Pure: is the recent record losing (net SOL below zero, or a [LOSS_STREAK_8296]-close losing streak)? */
    fun losing8296(r: Recent8296?): Boolean =
        r != null && r.n >= MIN_RECENT_N_8296 && (r.netSol < 0.0 || r.lossStreak >= LOSS_STREAK_8296)

    /** Pure: a full window, statistically positive (mean - SE > 0) and net positive in SOL, with no loss streak. */
    fun provenRecent8296(r: Recent8296?): Boolean =
        r != null && r.n >= WINDOW_8296 && r.netSol > 0.0 && r.sePct.isFinite() && r.meanPct - r.sePct > 0.0 &&
            r.lossStreak < 2

    /** Pure: the lane expectancy multiplier [lifetimeMult] after the recent live record [r]. */
    fun recencyAdjusted8296(lifetimeMult: Double, r: Recent8296?): Double {
        if (!lifetimeMult.isFinite()) return lifetimeMult
        if (losing8296(r)) {
            val rr = r!!
            var m = (1.0 + (if (rr.meanPct.isFinite()) rr.meanPct else 0.0) / 50.0).coerceIn(RECENT_FLOOR_8296, 1.0)
            if (rr.lossStreak >= LOSS_STREAK_8296) m = minOf(m, 0.80)
            return minOf(lifetimeMult, m)
        }
        if (provenRecent8296(r)) {
            val g = (1.0 + r!!.meanPct / 50.0).coerceIn(1.0, RECENT_GROWTH_MAX_8296)
            return maxOf(lifetimeMult, g)
        }
        return lifetimeMult
    }

    /**
     * Pure: the compounding share [frac] after the lane's recent record [laneRecent] and the selected setup's realised
     * live record ([setupN] closes, [setupMeanPct] mean). Zero while either is losing; never raised.
     */
    fun kellyGate8296(frac: Double, laneRecent: Recent8296?, setupN: Double, setupMeanPct: Double): Double {
        if (!frac.isFinite() || frac <= 0.0) return 0.0
        if (losing8296(laneRecent)) return 0.0
        if (setupN.isFinite() && setupN >= SETUP_MIN_N_8296 && setupMeanPct.isFinite() && setupMeanPct < 0.0) return 0.0
        return frac
    }

    /**
     * Pure (AutoCompoundEngine live authority): the live compound pool after one verified close of [netSol]. A net win adds
     * [LIVE_COMPOUND_SHARE_8296] of itself; a net loss drains the pool by its FULL amount (confidence falls faster than it
     * grows), never below zero.
     */
    fun nextPool8296(pool: Double, netSol: Double): Double {
        val p = if (pool.isFinite()) pool.coerceAtLeast(0.0) else 0.0
        if (!netSol.isFinite()) return p
        return if (netSol > 0.0) p + netSol * LIVE_COMPOUND_SHARE_8296 else (p + netSol).coerceAtLeast(0.0)
    }

    /** Pure: the size multiplier a live compound pool earns (AutoCompoundEngine's curve: +0.45x per threshold, max 3x). */
    fun compoundMultiplier8296(pool: Double): Double =
        if (!pool.isFinite() || pool <= 0.0) 1.0
        else minOf(LIVE_COMPOUND_MAX_MULT_8296, 1.0 + pool / LIVE_COMPOUND_THRESHOLD_SOL_8296 * 0.45)

    /** The live compound multiplier, fed only by verified live closes (never paper). */
    fun liveCompoundMultiplier8296(): Double = synchronized(this) {
        ensureLoaded8296()
        compoundMultiplier8296(livePool8296)
    }

    /**
     * Pure: the compounding share a setup earns from realised live closes alone — half the lane's mature realised-proof
     * stake [laneRealisedProofFrac] when the selected setup has [REALISED_ONLY_MIN_N_8296]+ of its own live closes averaging
     * a gain; 0 otherwise. Used only where no label-proven share exists.
     */
    fun realisedOnlyFrac8296(laneRealisedProofFrac: Double, setupN: Double, setupMeanPct: Double): Double {
        if (!laneRealisedProofFrac.isFinite() || laneRealisedProofFrac <= 0.0) return 0.0
        if (!setupN.isFinite() || setupN < REALISED_ONLY_MIN_N_8296 || !setupMeanPct.isFinite() || setupMeanPct <= 0.0) return 0.0
        return laneRealisedProofFrac * 0.5
    }

    // ── capture (verified live closes only) ──

    /** CanonicalTradeFinalizedBus6450 subscriber (LiveRiskPolicy7807.ensureSubscribed). */
    fun onLiveClose8296(e: CanonicalTradeFinalizedBus6450.Event) {
        val pct = if (e.netReturnPct.isFinite()) e.netReturnPct else e.returnFraction * 100.0
        note8296(e.mode, e.economicFinality, e.positionId, e.entryLane, pct, e.netRealizedPnlSol, e.settledAtMs)
    }

    /** Records one terminal close; false when it is not a verified live close (or already recorded). */
    @Synchronized
    fun note8296(mode: String, finality: String, positionId: String, laneRaw: String, netPct: Double, netSol: Double, atMs: Long): Boolean {
        ensureLoaded8296()
        if (!ExactStrategyPerformance7429.terminalEconomicsEligible8081(mode, finality) || !mode.equals("LIVE", true) ||
            positionId.isBlank() || !netPct.isFinite() || !netSol.isFinite()) {
            rejected8296.incrementAndGet(); return false
        }
        if (!seen8296.add(positionId)) return false
        while (seen8296.size > MAX_IDS_8296) seen8296.remove(seen8296.first())
        val lane = LiveRiskPolicy7807.canonicalLane(laneRaw)
        val q = lanes8296.getOrPut(lane) { ArrayDeque() }
        q.addLast(doubleArrayOf(atMs.toDouble(), netPct.coerceIn(-100.0, CAP_PCT_8296), netSol))
        while (q.size > WINDOW_8296) q.removeFirst()
        livePool8296 = nextPool8296(livePool8296, netSol)
        accepted8296.incrementAndGet()
        persist8296()
        return true
    }

    /** The lane's recent verified live record; the canonical bus fills in while the persisted ring is still short. */
    fun recentFor8296(laneRaw: String): Recent8296 {
        val lane = LiveRiskPolicy7807.canonicalLane(laneRaw)
        val ring = synchronized(this) {
            ensureLoaded8296()
            lanes8296[lane]?.toList()?.reversed().orEmpty().map { doubleArrayOf(it[1], it[2]) }
        }
        if (ring.size >= MIN_RECENT_N_8296) return recentOf8296(ring)
        val bus = try {
            CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live").filter {
                it.learningEligible && LiveRiskPolicy7807.canonicalLane(it.lane) == lane &&
                    ExactStrategyPerformance7429.terminalEconomicsEligible8081(it.mode, it.proofState)
            }.take(WINDOW_8296).map { doubleArrayOf(it.realizedReturnPct, it.realizedPnlSol) }
        } catch (_: Throwable) { emptyList() }
        return recentOf8296(if (bus.size > ring.size) bus else ring)
    }

    fun noteKellyRevoked8296(before: Double, after: Double) {
        if (before > 0.0 && after <= 0.0) {
            kellyRevoked8296.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_CONFIDENCE_KELLY_REVOKED_8296") } catch (_: Throwable) {}
        }
    }

    fun statusLine8296(): String {
        val body = synchronized(this) {
            ensureLoaded8296()
            lanes8296.keys.sorted().joinToString(" ") { l ->
                val r = recentOf8296(lanes8296[l]?.toList()?.reversed().orEmpty().map { doubleArrayOf(it[1], it[2]) })
                "$l[n${r.n} mean${"%+.1f".format(r.meanPct)}% net${"%+.4f".format(r.netSol)} streakL${r.lossStreak}" +
                    "${if (losing8296(r)) " LOSING" else if (provenRecent8296(r)) " PROVEN" else ""}]"
            }.ifBlank { "no verified live closes yet" }
        }
        return "liveConfidence8296[accepted=${accepted8296.get()} rejected=${rejected8296.get()} " +
            "kellyRevoked=${kellyRevoked8296.get()} compoundPool=${"%.4f".format(livePool8296)} " +
            "compoundMult=${"%.2f".format(compoundMultiplier8296(livePool8296))}x $body]"
    }

    // ── persistence (never reset) ──

    private fun ensureLoaded8296() {
        if (loaded8296) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        loaded8296 = true
        try {
            val raw = com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8296) ?: return
            val o = org.json.JSONObject(raw)
            o.optJSONObject("lanes")?.let { j ->
                for (k in j.keys()) {
                    val a = j.optJSONArray(k) ?: continue
                    val q = lanes8296.getOrPut(k) { ArrayDeque() }
                    val loadedRows = (0 until a.length()).mapNotNull { i ->
                        a.optJSONArray(i)?.takeIf { it.length() >= 3 }?.let { r -> doubleArrayOf(r.optDouble(0), r.optDouble(1), r.optDouble(2)) }
                    }.filter { r -> r.all { it.isFinite() } }
                    // Rows captured before the load (none normally) stay newest.
                    val merged = (loadedRows + q).sortedBy { it[0] }.takeLast(WINDOW_8296)
                    q.clear(); q.addAll(merged)
                }
            }
            // Closes recorded before the load (none normally) were already applied on top of a zero pool.
            livePool8296 = nextPool8296(o.optDouble("livePool", 0.0), 0.0) + livePool8296
            o.optJSONArray("ids")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { seen8296.add(it) } }
        } catch (_: Throwable) {}
    }

    private fun persist8296() {
        if (!loaded8296) return
        try {
            val lanes = org.json.JSONObject()
            lanes8296.forEach { (k, q) -> lanes.put(k, org.json.JSONArray(q.map { r -> org.json.JSONArray(r.toList()) })) }
            val json = org.json.JSONObject().put("lanes", lanes).put("ids", org.json.JSONArray(seen8296.toList().takeLast(MAX_IDS_8296)))
                .put("livePool", livePool8296).toString()
            com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8296, json)
        } catch (_: Throwable) {}
    }
}
