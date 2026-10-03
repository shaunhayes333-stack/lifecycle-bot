package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7731 §THE_SAMPLE_IS_THE_EDGE.
 *
 * The bot learns from live closes: 11 to 23 per session, spread over nineteen
 * learners that each want 8 to 60 before they act. It sees about 700 unique
 * tokens and 36,000 candidate offers in the same session and keeps none of
 * them. The one counterfactual it does keep (LaneShadowProof7307, refused
 * unproven-lane candidates, 60 open at most) is the clearest line in the
 * report: refused MOONSHOT ran -49.5%, SHITCOIN -49%, TREASURY +7.3%.
 *
 * This labeler follows EVERY candidate FinalDecisionGate rules on, admitted or
 * refused, and books its forward return at 15, 60 and 240 minutes, net of the
 * Field Manual's all-in cost at the routable minimum size, into a cell:
 *
 *     source family | lane | market-cap band | age band
 *
 * It spends nothing, opens nothing and touches no ledger. It reads prices the
 * loop already holds (the same closure LaneShadowProof7307 ticks on) and the
 * canonical mark registry; it never calls a provider. Cells persist across
 * restarts, so a thousand labels a day accumulate into the table a selection
 * authority can read (CellProofLadder7731), instead of a dozen closes a day
 * being asked to prove everything.
 *
 * A candidate whose mark disappears before its horizon is counted as
 * LOST_MARK on its cell and not booked: a watchlist eviction and a rug look
 * the same from here, and guessing which would poison the table. The ladder
 * reads the lost share next to the mean and refuses to act on a cell that
 * mostly vanishes.
 */
object ForwardReturnLabeler7731 {

    private const val H15_MS_7731 = 15L * 60_000L
    private const val H60_MS_7731 = 60L * 60_000L
    private const val H240_MS_7731 = 240L * 60_000L
    /** A mark older than this is not a price for this purpose (LaneShadowProof7307 uses the same). */
    private const val MARK_MAX_AGE_MS_7731 = 120_000L
    /** Lost-mark grace past the last horizon before the observation is dropped. */
    private const val LOST_GRACE_MS_7731 = 10L * 60_000L
    /** One observation per mint and lane per hour. */
    private const val REOBSERVE_MS_7731 = 60L * 60_000L
    private const val MAX_PENDING_7731 = 6_000
    private const val MAX_CELLS_7731 = 400
    private const val MAX_SEEN_7731 = 12_000
    private const val PERSIST_EVERY_BOOKINGS_7731 = 25
    private const val PREFS_7731 = "forward_return_labeler_7731"
    /** Routable-minimum ticket, the size every cell is costed at. */
    private const val COST_SIZE_USD_7731 = 5.0
    /** A 60-minute gross move above this is a runner. */
    private const val RUNNER_PCT_7731 = 50.0

    private class Obs(
        val mint: String,
        val symbol: String,
        val cell: String,
        val source: String,
        val lane: String,
        val admitted: Boolean,
        val entryPrice: Double,
        val costPct: Double,
        val atMs: Long,
    ) {
        @Volatile var done15 = false
        @Volatile var done60 = false
        @Volatile var done240 = false
        @Volatile var peakPct = 0.0
    }

    /** Per-horizon tallies for one cell (or one aggregate key). */
    class Tally {
        var n15 = 0; var sum15 = 0.0; var win15 = 0
        var n60 = 0; var sum60 = 0.0; var sumSq60 = 0.0; var win60 = 0; var runner60 = 0
        var n240 = 0; var sum240 = 0.0; var win240 = 0
        var lost = 0
        fun encode(): String = "$n15,$sum15,$win15,$n60,$sum60,$sumSq60,$win60,$runner60,$n240,$sum240,$win240,$lost"
        fun decode(s: String): Boolean {
            val f = s.split(',')
            if (f.size != 12) return false
            n15 = f[0].toIntOrNull() ?: 0; sum15 = f[1].toDoubleOrNull() ?: 0.0; win15 = f[2].toIntOrNull() ?: 0
            n60 = f[3].toIntOrNull() ?: 0; sum60 = f[4].toDoubleOrNull() ?: 0.0; sumSq60 = f[5].toDoubleOrNull() ?: 0.0
            win60 = f[6].toIntOrNull() ?: 0; runner60 = f[7].toIntOrNull() ?: 0
            n240 = f[8].toIntOrNull() ?: 0; sum240 = f[9].toDoubleOrNull() ?: 0.0; win240 = f[10].toIntOrNull() ?: 0
            lost = f[11].toIntOrNull() ?: 0
            return true
        }
    }

    /** Read-only view of a cell at the 60-minute horizon, the ladder's horizon. */
    data class CellStat(
        val key: String,
        val n60: Int,
        val meanNet60Pct: Double,
        val winRate60: Double,
        val runnerRate60: Double,
        val stderr60Pct: Double,
        val lost: Int,
    ) {
        /** Share of observations that reached a 60-minute label rather than losing their mark. */
        val resolvedShare: Double get() = if (n60 + lost > 0) n60.toDouble() / (n60 + lost) else 0.0
    }

    private val pending = ConcurrentHashMap<String, Obs>()          // key = mint|lane
    private val lastSeenAt = ConcurrentHashMap<String, Long>()       // key = mint|lane
    private val cells = ConcurrentHashMap<String, Tally>()
    @Volatile private var prefs: SharedPreferences? = null
    private val observed = AtomicLong(0)
    private val skippedNoPrice = AtomicLong(0)
    private val skippedRecent = AtomicLong(0)
    private val skippedFull = AtomicLong(0)
    private val booked15 = AtomicLong(0)
    private val booked60 = AtomicLong(0)
    private val booked240 = AtomicLong(0)
    private val lostMark = AtomicLong(0)
    private val bookingsSincePersist = AtomicLong(0)
    @Volatile private var lastPersistMs = 0L

    // ── aggregate keys, kept beside the cells in the same table ──
    private const val AGG_ADMITTED = "AGG|ADMITTED"
    private const val AGG_REFUSED = "AGG|REFUSED"
    private fun laneKey(lane: String) = "LANE|$lane"
    private fun sourceKey(src: String) = "SRC|$src"

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS_7731, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs = p
        try {
            p.getString("cells", null)?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val key = row.substring(0, sep)
                val t = Tally()
                if (t.decode(row.substring(sep + 1))) cells[key] = t
            }
        } catch (_: Throwable) {}
    }

    private fun persist(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < 60_000L) return
        lastPersistMs = now
        bookingsSincePersist.set(0)
        try {
            val enc = cells.entries.joinToString(";") { (k, t) -> "$k=${synchronized(t) { t.encode() }}" }
            p.edit().putString("cells", enc).apply()
        } catch (_: Throwable) {}
    }

    // ── cell naming (pure) ──

    fun sourceFamily(source: String): String {
        val first = source.split(',').firstOrNull()?.trim().orEmpty().uppercase()
        val stripped = first.removePrefix("SCANNER_DIRECT_").removePrefix("SCANNER_HEAL_").removePrefix("SCANNER_")
        val clean = stripped.replace(Regex("[^A-Z0-9_]"), "_").trim('_')
        return clean.ifBlank { "UNKNOWN" }.take(28)
    }

    fun mcapBand(mcapUsd: Double): String = when {
        !mcapUsd.isFinite() || mcapUsd <= 0.0 -> "MC_UNKNOWN"
        mcapUsd < 10_000.0 -> "MC_LT10K"
        mcapUsd < 100_000.0 -> "MC_10K_100K"
        mcapUsd < 1_000_000.0 -> "MC_100K_1M"
        else -> "MC_GT1M"
    }

    fun ageBand(ageMs: Long): String = when {
        ageMs < 0L -> "AGE_UNKNOWN"
        ageMs < 15L * 60_000L -> "AGE_LT15M"
        ageMs < 2L * 60L * 60_000L -> "AGE_15M_2H"
        ageMs < 24L * 60L * 60_000L -> "AGE_2H_24H"
        else -> "AGE_GT24H"
    }

    fun cellKey(source: String, lane: String, mcapUsd: Double, ageMs: Long): String =
        "${sourceFamily(source)}|${lane.trim().uppercase().ifBlank { "UNKNOWN" }.take(24)}|${mcapBand(mcapUsd)}|${ageBand(ageMs)}"

    /** Pure: the net return of one observation at a mark, in percent. */
    fun netPct(entryPrice: Double, markPrice: Double, costPct: Double): Double =
        (markPrice / entryPrice - 1.0) * 100.0 - costPct

    // ── observation (called from ExecutableOpenGate.recordFdg for every distinct verdict) ──

    fun observe(mint: String, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return
        observe(ts, lane, admitted, reason, nowMs)
    }

    fun observe(ts: TokenState, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val l = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val key = "${ts.mint}|$l"
        val px = ts.lastPrice
        if (!px.isFinite() || px <= 0.0 || ts.lastPriceUpdate <= 0L || nowMs - ts.lastPriceUpdate > MARK_MAX_AGE_MS_7731) {
            skippedNoPrice.incrementAndGet()
            return
        }
        val seen = lastSeenAt[key]
        if (seen != null && nowMs - seen < REOBSERVE_MS_7731) { skippedRecent.incrementAndGet(); return }
        if (pending.containsKey(key)) { skippedRecent.incrementAndGet(); return }
        if (pending.size >= MAX_PENDING_7731) {
            skippedFull.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FORWARD_LABELER_PENDING_FULL_7731") } catch (_: Throwable) {}
            return
        }
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val liq = if (ts.lastLiquidityUsd.isFinite()) ts.lastLiquidityUsd else 0.0
        val cost = try { FieldManual7715.allInCostPct(COST_SIZE_USD_7731, liq) } catch (_: Throwable) { FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 }
        val cell = cellKey(ts.source, l, ts.lastMcap, ageMs)
        pending[key] = Obs(ts.mint, ts.symbol, cell, sourceFamily(ts.source), l, admitted, px, cost.coerceIn(0.0, 60.0), nowMs)
        lastSeenAt[key] = nowMs
        if (lastSeenAt.size > MAX_SEEN_7731) {
            val cutoff = nowMs - REOBSERVE_MS_7731
            lastSeenAt.entries.removeIf { it.value < cutoff }
        }
        observed.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FORWARD_LABEL_OBSERVED_7731")
            PipelineHealthCollector.labelInc(if (admitted) "FORWARD_LABEL_OBSERVED_ADMITTED_7731" else "FORWARD_LABEL_OBSERVED_REFUSED_7731")
        } catch (_: Throwable) {}
    }

    // ── ticking (called once per loop with the loop's price closure) ──

    private fun tallyFor(key: String): Tally = cells.getOrPut(key) {
        if (cells.size >= MAX_CELLS_7731) pruneCells()
        Tally()
    }

    private fun pruneCells() {
        // Drop the thinnest non-aggregate cells so the table stays bounded.
        val victims = cells.entries
            .filter { !it.key.startsWith("AGG|") && !it.key.startsWith("LANE|") && !it.key.startsWith("SRC|") }
            .sortedBy { synchronized(it.value) { it.value.n60 + it.value.n15 } }
            .take(MAX_CELLS_7731 / 10)
        victims.forEach { cells.remove(it.key, it.value) }
        try { PipelineHealthCollector.labelInc("FORWARD_LABELER_CELLS_PRUNED_7731") } catch (_: Throwable) {}
    }

    private fun book(o: Obs, horizon: Int, net: Double, gross: Double) {
        val keys = listOf(o.cell, laneKey(o.lane), sourceKey(o.source), if (o.admitted) AGG_ADMITTED else AGG_REFUSED)
        for (k in keys) {
            val t = tallyFor(k)
            synchronized(t) {
                when (horizon) {
                    15 -> { t.n15 += 1; t.sum15 += net; if (net > 0.0) t.win15 += 1 }
                    60 -> { t.n60 += 1; t.sum60 += net; t.sumSq60 += net * net; if (net > 0.0) t.win60 += 1; if (gross >= RUNNER_PCT_7731) t.runner60 += 1 }
                    else -> { t.n240 += 1; t.sum240 += net; if (net > 0.0) t.win240 += 1 }
                }
            }
        }
        when (horizon) { 15 -> booked15.incrementAndGet(); 60 -> booked60.incrementAndGet(); else -> booked240.incrementAndGet() }
        if (bookingsSincePersist.incrementAndGet() >= PERSIST_EVERY_BOOKINGS_7731) persist()
    }

    private fun markLost(o: Obs) {
        for (k in listOf(o.cell, laneKey(o.lane), sourceKey(o.source), if (o.admitted) AGG_ADMITTED else AGG_REFUSED)) {
            val t = tallyFor(k)
            synchronized(t) { t.lost += 1 }
        }
        lostMark.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORWARD_LABEL_LOST_MARK_7731") } catch (_: Throwable) {}
    }

    /** A price for [mint] from the loop's token states, else the canonical mark registry when fresh. */
    private fun markFor(mint: String, priceFor: (String) -> Double?, nowMs: Long): Double? {
        val fromLoop = try { priceFor(mint) } catch (_: Throwable) { null }
        if (fromLoop != null && fromLoop.isFinite() && fromLoop > 0.0) return fromLoop
        return try {
            val m = CanonicalPriceMarkRegistry6522.get(mint) ?: return null
            if (m.timestampMs <= 0L || nowMs - m.timestampMs > MARK_MAX_AGE_MS_7731) return null
            val px = m.priceUsd.value.toDouble()
            if (px.isFinite() && px > 0.0) px else null
        } catch (_: Throwable) { null }
    }

    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        for ((key, o) in pending.entries.toList()) {
            val age = nowMs - o.atMs
            val px = markFor(o.mint, priceFor, nowMs)
            if (px == null) {
                if (!o.done60 && age >= H60_MS_7731 + LOST_GRACE_MS_7731) {
                    // Nothing priced it through its 60-minute horizon: lost, not booked.
                    markLost(o)
                    pending.remove(key, o)
                } else if (o.done60 && age >= H240_MS_7731 + LOST_GRACE_MS_7731) {
                    // It earned its 60-minute label; the 240 is simply absent.
                    pending.remove(key, o)
                }
                continue
            }
            val gross = (px / o.entryPrice - 1.0) * 100.0
            if (gross > o.peakPct) o.peakPct = gross
            val net = netPct(o.entryPrice, px, o.costPct)
            if (!o.done15 && age >= H15_MS_7731) { o.done15 = true; book(o, 15, net, gross) }
            if (!o.done60 && age >= H60_MS_7731) {
                o.done60 = true
                book(o, 60, net, gross)
                try { SignalSourceProof7291.onForwardLabel7731(o.mint, net / 100.0, nowMs) } catch (_: Throwable) {}
            }
            if (!o.done240 && age >= H240_MS_7731) {
                o.done240 = true
                book(o, 240, net, gross)
                pending.remove(key, o)
            }
        }
    }

    // ── reads ──

    private fun cellStat(key: String): CellStat? {
        val t = cells[key] ?: return null
        return synchronized(t) {
            if (t.n60 <= 0 && t.lost <= 0) null else {
                val mean = if (t.n60 > 0) t.sum60 / t.n60 else 0.0
                val variance = if (t.n60 > 1) ((t.sumSq60 / t.n60) - mean * mean).coerceAtLeast(0.0) else 0.0
                val se = if (t.n60 > 1) kotlin.math.sqrt(variance / t.n60) else Double.POSITIVE_INFINITY
                CellStat(
                    key = key, n60 = t.n60, meanNet60Pct = mean,
                    winRate60 = if (t.n60 > 0) t.win60.toDouble() / t.n60 else 0.0,
                    runnerRate60 = if (t.n60 > 0) t.runner60.toDouble() / t.n60 else 0.0,
                    stderr60Pct = se, lost = t.lost,
                )
            }
        }
    }

    fun cellStatFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CellStat? {
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        return cellStat(cellKey(ts.source, lane, ts.lastMcap, ageMs))
    }

    private fun laneStat(lane: String): CellStat? = cellStat(laneKey(lane.trim().uppercase()))

    private fun fmtStat(s: CellStat?): String =
        if (s == null) "n=0" else "n=${s.n60} net=${"%+.1f".format(s.meanNet60Pct)}% wr=${"%.0f".format(s.winRate60 * 100)}% run=${"%.0f".format(s.runnerRate60 * 100)}% lost=${s.lost}"

    fun statusLine(): String {
        val cellStats = cells.keys
            .filter { !it.startsWith("AGG|") && !it.startsWith("LANE|") && !it.startsWith("SRC|") }
            .mapNotNull { cellStat(it) }
            .filter { it.n60 >= 30 }
        val best = cellStats.sortedByDescending { it.meanNet60Pct }.take(3)
        val worst = cellStats.sortedBy { it.meanNet60Pct }.take(3)
        val lanes = cells.keys.filter { it.startsWith("LANE|") }.map { it.removePrefix("LANE|") }.sorted()
            .mapNotNull { l -> laneStat(l)?.let { "$l[${fmtStat(it)}]" } }
        return "pending=${pending.size} observed=${observed.get()} booked15=${booked15.get()} booked60=${booked60.get()} booked240=${booked240.get()} " +
            "lostMark=${lostMark.get()} skipped[noPrice=${skippedNoPrice.get()} recent=${skippedRecent.get()} full=${skippedFull.get()}] cells=${cellStats.size}/${cells.size}\n" +
            "      admitted60[${fmtStat(cellStat(AGG_ADMITTED))}] refused60[${fmtStat(cellStat(AGG_REFUSED))}]\n" +
            "      best60: ${best.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      worst60: ${worst.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      lanes60: ${lanes.joinToString(" · ").ifBlank { "no labels yet" }}"
    }

    /** Called when the service stops: the table survives the restart. */
    fun persistNow7731() {
        persist(force = true)
        try {
            ForensicLogger.lifecycle(
                "FORWARD_LABELER_PERSISTED_7731",
                "cells=${cells.size} pending=${pending.size} booked60=${booked60.get()} lost=${lostMark.get()} " +
                    "admitted=[${fmtStat(cellStat(AGG_ADMITTED))}] refused=[${fmtStat(cellStat(AGG_REFUSED))}]",
            )
        } catch (_: Throwable) {}
    }
}
