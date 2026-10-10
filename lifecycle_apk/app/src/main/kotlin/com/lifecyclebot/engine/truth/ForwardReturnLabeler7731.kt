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
 * canonical mark registry; the loop never waits on a provider (7737 below). Cells persist across
 * restarts, so a thousand labels a day accumulate into the table a selection
 * authority can read (CellProofLadder7731), instead of a dozen closes a day
 * being asked to prove everything.
 *
 * A candidate whose mark disappears before its horizon is counted as
 * LOST_MARK on its cell and not booked: a watchlist eviction and a rug look
 * the same from here, and guessing which would poison the table. The ladder
 * reads the lost share next to the mean and refuses to act on a cell that
 * mostly vanishes.
 *
 * V5.0.7737 — a mark that leaves the watchlist is fetched. 5.0.7736 read
 * admitted60 n=71 against lost=1481: the table kept the survivors and dropped
 * the rugs, so its means flattered whatever stayed on the bench. An observation
 * due at a horizon with no loop or registry price now joins a batch (fifty
 * mints, one Jupiter Price call, off the loop, at most every
 * [OFFWATCH_FETCH_GAP_MS_7737]); a dead token Jupiter no longer prices stays lost.
 */
object ForwardReturnLabeler7731 {

    /**
     * V5.0.7946 — the read is FIVE minutes. Meme tokens spike and fade inside
     * minutes; a 60-minute read taught the Cortex an hour late, lost the mark of
     * every token that left the watchlist in that hour (5.0.7941: 1,928 lost), and
     * graded the price long after the bot's own exits had acted. The three label
     * slots keep their historical names (15 / 60 / 240, and n60 / meanNet60Pct in
     * CellStat) but now mean:
     *   slot "15"  -> 2 minutes  (the early read)
     *   slot "60"  -> 5 minutes  (THE read: lane proof, cells, stages, Cortex, playbooks)
     *   slot "240" -> 60 minutes (the late read that spares runner lanes)
     * The table is stored under new keys, so no 60-minute label mixes into it.
     */
    private const val H15_MS_7731 = 2L * 60_000L
    private const val H60_MS_7731 = 5L * 60_000L
    private const val H240_MS_7731 = 60L * 60_000L
    /** A mark older than this is not a price for this purpose (tightened for the 5-minute read). */
    private const val MARK_MAX_AGE_MS_7731 = 45_000L
    /** Lost-mark grace past the longest horizon before the observation is dropped. */
    private const val LOST_GRACE_MS_7731 = 10L * 60_000L
    /** V5.0.7946 — one observation per mint and lane per 15 minutes (the read is 5). */
    private const val REOBSERVE_MS_7731 = 15L * 60_000L
    private const val CELLS_KEY_7946 = "cells_7946"
    private const val PENDING_KEY_7946 = "pending_7946"

    /** V5.0.7946 — Pure: how late a horizon may still be read: half the horizon, between 1 and 10 minutes. */
    fun graceFor7946(horizonMs: Long): Long = (horizonMs / 2).coerceIn(60_000L, LOST_GRACE_MS_7731)
    private const val MAX_PENDING_7731 = 6_000
    private const val MAX_CELLS_7731 = 560 // V5.0.7928 — +lane x stage aggregates (never pruned)
    private const val MAX_SEEN_7731 = 12_000
    private const val PERSIST_EVERY_BOOKINGS_7731 = 25
    private const val PREFS_7731 = "forward_return_labeler_7731"
    /** Routable-minimum ticket, the size every cell is costed at. */
    private const val COST_SIZE_USD_7731 = 5.0
    /** A gross move at the read (spike tiers credited) above this is a runner. */
    private const val RUNNER_PCT_7731 = 50.0
    /** V5.0.7735 — oldest decision-time price a label may start from. */
    private const val ENTRY_MARK_MAX_AGE_MS_7735 = 90_000L // V5.0.7946 — a 5-minute read cannot start from a 10-minute-old price
    /**
     * V5.0.7735 — pending observations survive a restart. Every install or
     * restart inside the 60-minute horizon used to discard every open
     * observation, and the app is reinstalled about once an hour while it is
     * being built: 5.0.7734 at 54 minutes read booked15=65 booked60=0, the
     * ladder's horizon never reached. Persisted beside the cells, restored on
     * attach, bounded to the newest [MAX_PERSISTED_PENDING_7735].
     */
    private const val MAX_PERSISTED_PENDING_7735 = 2_000
    /** V5.0.8014 — labels a cell keeps per horizon before the oldest fade (fresh edge). */
    const val CELL_WINDOW_8014 = 300
    private const val FIELD_SEP_7735 = '\u001F'
    private const val ROW_SEP_7735 = '\u001E'
    private val restoredPending7735 = AtomicLong(0)
    /** V5.0.7737 — off-watchlist marks fetched for observations due at a horizon. */
    private const val OFFWATCH_FETCH_GAP_MS_7737 = 20_000L
    private const val OFFWATCH_BATCH_7737 = 50
    private val offWatchMarks7737 = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val offWatchInFlight7737 = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var offWatchLastFetchMs7737 = 0L
    private val offWatchPriced7737 = AtomicLong(0)
    private val offWatchMissed7737 = AtomicLong(0)
    /**
     * V5.0.7738 — basis guard. 5.0.7737 at 396 s: PROJECT_SNIPER labels read
     * n=44 net=+1116.5% wr=9%, and the forecast model's S|PROJECT_SNIPER cell
     * E=+1245% +-7073 at n=39: a mean that size beside a 9% win rate is one or
     * two marks tens of times the entry, an entry price and a mark on different
     * bases, not a token that ran 250x in an hour. A mark more than
     * [BASIS_MAX_RATIO_7738] times the entry, or under 1/[BASIS_MAX_RATIO_7738]
     * of it, is not booked; a booked net enters the means capped at
     * [NET_CEILING_PCT_7738] (the runner count still reads the gross move).
     */
    private const val BASIS_MAX_RATIO_7738 = 20.0
    // V5.0.7769 — 300 clipped every real 4x-20x launch to +300% before the cell
    // mean saw it; the corroboration check below is what keeps basis errors out.
    private const val NET_CEILING_PCT_7738 = 1000.0
    private val basisSuspect7738 = AtomicLong(0)
    private val purgedCells7738 = AtomicLong(0)

    /** Pure: true when a mark and an entry price cannot be on the same basis. */
    fun basisSuspect7738(entryPrice: Double, markPrice: Double): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || entryPrice <= 0.0 || markPrice <= 0.0) return true
        val r = markPrice / entryPrice
        return r > BASIS_MAX_RATIO_7738 || r < 1.0 / BASIS_MAX_RATIO_7738
    }

    /**
     * V5.0.7769 §A_REAL_RUN_IS_NOT_A_BASIS_ERROR. The 20x basis guard also threw
     * away every genuine runner: a $6.4k pump.fun launch that went to $1.2M (187x)
     * would be dropped unbooked, so the cell it came from could only ever read
     * its losers and the ladder refused that cell live. A move past 20x is a real
     * move when the market cap moved by the same multiple (within 2x) and the
     * ratio is not the SOL/USD factor that the 7738 basis mix-ups produced.
     * Field Manual §12: authority comes from finalized outcomes, all of them.
     */
    fun runCorroborated7769(entryPrice: Double, markPrice: Double, entryMcap: Double, nowMcap: Double, solUsd: Double): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || !entryMcap.isFinite() || !nowMcap.isFinite()) return false
        if (entryPrice <= 0.0 || markPrice <= 0.0 || entryMcap <= 0.0 || nowMcap <= 0.0) return false
        val r = markPrice / entryPrice
        val agree = (nowMcap / entryMcap) / r in 0.5..2.0
        val solBasis = solUsd.isFinite() && solUsd > 0.0 &&
            (kotlin.math.abs(EconomicUnitInvariant7061.usdToSol(r, solUsd) - 1.0) < 0.25 ||
                kotlin.math.abs(r / EconomicUnitInvariant7061.usdToSol(1.0, solUsd) - 1.0) < 0.25)
        return agree && !solBasis
    }

    private fun nowMcap7769(mint: String): Double =
        try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.lastMcap ?: 0.0 } catch (_: Throwable) { 0.0 }

    private class Obs(
        val mint: String,
        val symbol: String,
        val cell: String,
        val source: String,
        val lane: String,
        val admitted: Boolean,
        /** V5.0.7734 — the forecast signature this label teaches (ForwardOutcomeModel). */
        val score: Int,
        val quality: String,
        val regime: String,
        val phase: String,
        val entryPrice: Double,
        val costPct: Double,
        val atMs: Long,
    ) {
        @Volatile var done15 = false
        @Volatile var done60 = false
        @Volatile var done240 = false
        @Volatile var peakPct = 0.0
        /** V5.0.7769 — market cap at the decision, the second witness for a big move. */
        @Volatile var entryMcap = 0.0
        /** V5.0.7928 — the token's lifecycle stage at the decision (TokenMetricStageRouter). */
        @Volatile var stage = ""
        /** V5.0.7944 — the last price seen for it and when, so a vanished mark books at its real last value. */
        @Volatile var lastPx = 0.0
        @Volatile var lastPxAtMs = 0L
        /** V5.0.7955 — the setup at the decision (ExitProfile7955 key), when the peak was set, and the 5-minute give-back. */
        @Volatile var setup7955 = ""
        @Volatile var peakAtMs7955 = 0L
        @Volatile var giveback5_7955 = Double.NaN
        /** V5.0.7962 — lowest gross seen so far, and the lowest before the (latest) peak. */
        @Volatile var minPct7962 = 0.0
        @Volatile var dipBeforePeak7962 = Double.NaN
        /** V5.0.7967 — the refusal reason at the decision (missed-runner audit). */
        @Volatile var reason7967: String = ""
        /** V5.0.8006 — the exit-profile sample waits for the end of a followed run. */
        @Volatile var exitDeferred8006: Boolean = false
        /** V5.0.7972 — the decision's discrete facts (SpecialistMiner7972). */
        @Volatile var feats7972: List<String> = emptyList()
        /** V5.0.7997 — the label value last given to the learners, the next checkpoint, liquidity at the decision. */
        @Volatile var bookedNet7997 = Double.NaN
        @Volatile var bookedGross7997 = Double.NaN
        @Volatile var ck7997 = 0
        @Volatile var entryLiq7997 = 0.0
    }

    /** Per-horizon tallies for one cell (or one aggregate key). */
    class Tally {
        var n15 = 0; var sum15 = 0.0; var win15 = 0
        var n60 = 0; var sum60 = 0.0; var sumSq60 = 0.0; var win60 = 0; var runner60 = 0
        var n240 = 0; var sum240 = 0.0; var win240 = 0
        var lost = 0
        /**
         * V5.0.8014 — fresh edge: a horizon past [w] labels keeps its most recent ~[w] (the oldest fade
         * proportionally), so the cell record watch-first and every lane read is recent, not all-time.
         */
        fun window8014(w: Int) {
            if (n15 > w) { val k = w.toDouble() / n15; n15 = w; sum15 *= k; win15 = (win15 * k).roundToIntSafe8014().coerceIn(0, n15) }
            if (n60 > w) {
                val k = w.toDouble() / n60; n60 = w; sum60 *= k; sumSq60 *= k
                win60 = (win60 * k).roundToIntSafe8014().coerceIn(0, n60); runner60 = (runner60 * k).roundToIntSafe8014().coerceIn(0, n60)
            }
            if (n240 > w) { val k = w.toDouble() / n240; n240 = w; sum240 *= k; win240 = (win240 * k).roundToIntSafe8014().coerceIn(0, n240) }
        }
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

    /** Read-only view of a cell at THE read (slot "60", 5 minutes since V5.0.7946), the ladder's horizon. */
    data class CellStat(
        val key: String,
        val n60: Int,
        val meanNet60Pct: Double,
        val winRate60: Double,
        val runnerRate60: Double,
        val stderr60Pct: Double,
        val lost: Int,
        /** V5.0.7769 — the 4-hour record, where a launch cell's runners pay. */
        val n240: Int = 0,
        val meanNet240Pct: Double = 0.0,
    ) {
        /** Share of observations that reached a label at the read rather than losing their mark. */
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
    private val offWatchCurvePriced7753 = AtomicLong(0)
    private val bookingsSincePersist = AtomicLong(0)
    @Volatile private var lastPersistMs = 0L

    // ── aggregate keys, kept beside the cells in the same table ──
    private const val AGG_ADMITTED = "AGG|ADMITTED"
    private const val AGG_REFUSED = "AGG|REFUSED"
    private fun laneKey(lane: String) = "LANE|$lane"
    private fun sourceKey(src: String) = "SRC|$src"
    /** V5.0.7928 — lane x lifecycle stage: does this lane's play pay at this stage? */
    fun stageKey7928(lane: String, stage: String) = "STAGE|${lane.trim().uppercase()}|${stage.trim().uppercase()}"
    /** V5.0.8019 — lane x cap band across every source and age: the record that says "this lane loses under $10k". */
    fun bandKey8019(lane: String, mcapUsd: Double) = "BAND|${lane.trim().uppercase()}|${mcapBand(mcapUsd)}"
    /** V5.0.8019 — LiveEdgeGate7877: the lane's 60-minute record in [ts]'s cap band (local + hive). */
    fun bandStatFor8019(ts: TokenState, lane: String): CellStat? = cellStat(bandKey8019(lane, TrustedMcap8019.mcap8019(ts)))
    private fun keysOf7928(o: Obs): List<String> {
        val base = listOf(o.cell, laneKey(o.lane), sourceKey(o.source), if (o.admitted) AGG_ADMITTED else AGG_REFUSED,
            "BAND|${o.lane}|${o.cell.split('|').getOrNull(2) ?: "MC_UNKNOWN"}")  // V5.0.8019 — lane x cap band
        // V5.0.7930 — plan pseudo-lanes (PLANWAIT_/PLANADMIT_) carry no stage book.
        val pseudo = o.lane.startsWith("PLANWAIT_") || o.lane.startsWith("PLANADMIT_")
        return if (o.stage.isBlank() || pseudo) base else base + stageKey7928(o.lane, o.stage)
    }

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS_7731, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs = p
        try { FreshLaunchSelector7737.attach(context) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.CellAllocator7962.attach(context) } catch (_: Throwable) {}
        try { SpecialistMiner7972.attach7972(context) } catch (_: Throwable) {}
        try {
            p.getString(CELLS_KEY_7946, null)?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val key = row.substring(0, sep)
                val t = Tally()
                if (t.decode(row.substring(sep + 1))) cells[key] = t
            }
        } catch (_: Throwable) {}
        // V5.0.7738 — once: cells whose 60-minute mean was built on basis artefacts start over.
        try {
            if (!p.getBoolean("purged7738", false)) {
                for ((k, t) in cells.entries.toList()) {
                    val implausible = synchronized(t) { t.n60 > 0 && t.sum60 / t.n60 > 150.0 }
                    if (implausible) {
                        val fresh = Tally()
                        fresh.lost = synchronized(t) { t.lost }
                        cells[k] = fresh
                        purgedCells7738.incrementAndGet()
                    }
                }
                p.edit().putBoolean("purged7738", true).apply()
                if (purgedCells7738.get() > 0) {
                    PipelineHealthCollector.labelInc("FORWARD_LABEL_CELLS_PURGED_7738")
                    ForensicLogger.lifecycle("FORWARD_LABEL_CELLS_PURGED_7738", "purged=${purgedCells7738.get()} bar=mean60<=150% action=basis_artefact_cells_restarted")
                }
            }
        } catch (_: Throwable) {}
        try {
            val n = restorePending7735(p.getString(PENDING_KEY_7946, null), System.currentTimeMillis())
            if (n > 0) {
                restoredPending7735.addAndGet(n.toLong())
                PipelineHealthCollector.labelInc("FORWARD_LABELER_PENDING_RESTORED_7735")
                ForensicLogger.lifecycle("FORWARD_LABELER_PENDING_RESTORED_7735", "restored=$n action=open_observations_survive_the_restart")
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
            p.edit().putString(CELLS_KEY_7946, enc).putString(PENDING_KEY_7946, encodePending7735(now)).remove("cells").remove("pending").apply()
        } catch (_: Throwable) {}
    }

    private fun encodePending7735(nowMs: Long): String {
        val fs = FIELD_SEP_7735.toString()
        // V5.0.8013 — a coin still being followed past the hour (progressive grading) is saved for as
        // long as its schedule runs, and saved first: a restart used to drop every followed runner
        // after 70 minutes, so the 5 h / 24 h / beyond grades never happened across an app update.
        return pending.values.asSequence()
            .filter { nowMs - it.atMs <= H240_MS_7731 + LOST_GRACE_MS_7731 || followed8013(it, nowMs) }
            .sortedWith(compareByDescending<Obs> { followed8013(it, nowMs) }.thenByDescending { it.atMs })
            .take(MAX_PERSISTED_PENDING_7735)
            .joinToString(ROW_SEP_7735.toString()) { o ->
                listOf(
                    o.mint, o.symbol.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '), o.cell, o.source, o.lane,
                    if (o.admitted) "1" else "0", o.score.toString(), o.quality, o.regime, o.phase,
                    o.entryPrice.toString(), o.costPct.toString(), o.atMs.toString(),
                    if (o.done15) "1" else "0", if (o.done60) "1" else "0", if (o.done240) "1" else "0", o.peakPct.toString(),
                    o.entryMcap.toString(), o.stage, o.lastPx.toString(), o.lastPxAtMs.toString(),
                    o.setup7955.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '), o.peakAtMs7955.toString(),
                    // V5.0.8013 — the progressive-grading state rides along (fields 23..30).
                    o.ck7997.toString(), o.bookedNet7997.toString(), o.bookedGross7997.toString(), o.entryLiq7997.toString(),
                    if (o.exitDeferred8006) "1" else "0", o.minPct7962.toString(), o.dipBeforePeak7962.toString(),
                    o.feats7972.joinToString("\u0002") { it.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' ') },
                    // V5.0.8016 — the refusal reason survives a restart (missed runners read UNKNOWN without it).
                    o.reason7967.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '),
                ).joinToString(fs)
            }
    }

    /** V5.0.8013 — an observation the progressive schedule is still following past the hour. */
    private fun followed8013(o: Obs, nowMs: Long): Boolean =
        o.done240 && o.bookedNet7997.isFinite() && scheduleOpen8013(o.ck7997, nowMs - o.atMs)

    private fun checkpointDue8013(o: Obs, age: Long): Boolean =
        o.done240 && o.ck7997 in CK_MS_7997.indices && age >= CK_MS_7997[o.ck7997]

    /** Pure. V5.0.8013 — checkpoint [ck] of the schedule is still ahead (within its grace) at [ageMs]. */
    fun scheduleOpen8013(ck: Int, ageMs: Long): Boolean =
        ck in CK_MS_7997.indices && ageMs <= CK_MS_7997[CK_MS_7997.size - 1] + LOST_GRACE_MS_7731

    private fun restorePending7735(enc: String?, nowMs: Long): Int {
        if (enc.isNullOrBlank()) return 0
        var n = 0
        enc.split(ROW_SEP_7735).forEach { row ->
            val f = row.split(FIELD_SEP_7735)
            if (f.size !in 17..32) return@forEach
            val atMs = f[12].toLongOrNull() ?: return@forEach
            val ck8013 = if (f.size >= 24) f[23].toIntOrNull() ?: 0 else 0
            if (atMs <= 0L) return@forEach
            if (nowMs - atMs > H240_MS_7731 + LOST_GRACE_MS_7731 && !scheduleOpen8013(ck8013, nowMs - atMs)) return@forEach
            val px = f[10].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
            val o = Obs(f[0], f[1], f[2], f[3], f[4], f[5] == "1", f[6].toIntOrNull() ?: -1, f[7], f[8], f[9], px, f[11].toDoubleOrNull() ?: 0.0, atMs)
            o.done15 = f[13] == "1"; o.done60 = f[14] == "1"; o.done240 = f[15] == "1"
            o.peakPct = f[16].toDoubleOrNull() ?: 0.0
            if (f.size >= 18) o.entryMcap = f[17].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            if (f.size >= 19) o.stage = f[18]
            if (f.size >= 21) {
                o.lastPx = f[19].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                o.lastPxAtMs = f[20].toLongOrNull() ?: 0L
            }
            if (f.size >= 23) { o.setup7955 = f[21]; o.peakAtMs7955 = f[22].toLongOrNull() ?: 0L }
            if (f.size >= 31) {
                o.ck7997 = ck8013
                o.bookedNet7997 = f[24].toDoubleOrNull() ?: Double.NaN
                o.bookedGross7997 = f[25].toDoubleOrNull() ?: Double.NaN
                o.entryLiq7997 = f[26].toDoubleOrNull() ?: 0.0
                o.exitDeferred8006 = f[27] == "1"
                o.minPct7962 = f[28].toDoubleOrNull() ?: 0.0
                o.dipBeforePeak7962 = f[29].toDoubleOrNull() ?: Double.NaN
                o.feats7972 = f[30].split('\u0002').filter { it.isNotBlank() }
            }
            if (f.size >= 32) o.reason7967 = f[31]
            if (o.mint.isBlank() || o.lane.isBlank()) return@forEach
            val key = "${o.mint}|${o.lane}"
            if (pending.putIfAbsent(key, o) == null) { lastSeenAt[key] = atMs; n++ }
        }
        return n
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

    /**
     * V5.0.7961 — the price and time of the admitted decision for (mint, lane), the base
     * EntryChase7961 measures a fill against; any admitted decision on the mint when the
     * lane names differ. Null when none is pending.
     */
    fun decisionPx7961(mint: String, lane: String): Pair<Double, Long>? {
        val o = pending["$mint|${lane.trim().uppercase()}"]?.takeIf { it.admitted }
            ?: pending.values.filter { it.mint == mint && it.admitted }.maxByOrNull { it.atMs }
            ?: return null
        return o.entryPrice to o.atMs
    }

    fun observe(mint: String, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis(), score: Int = -1) {
        if (mint.isBlank()) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return
        observe(ts, lane, admitted, reason, nowMs, score)
    }

    fun observe(ts: TokenState, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis(), score: Int = -1) {
        val l = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val key = "${ts.mint}|$l"
        // V5.0.7733 — the entry mark comes from the token state when it is fresh,
        // else from the canonical registry (the same plumbing gap 7730 closed for
        // the Field Manual: a live canonical mark with lastPriceUpdate=0). 5.0.7732
        // at 367 s: observed=212, skipped noPrice=743 — three verdicts in four
        // were thrown away for want of a timestamp the registry already held.
        val tsAge = if (ts.lastPriceUpdate > 0L) nowMs - ts.lastPriceUpdate else Long.MAX_VALUE
        val tsPriced = ts.lastPrice.isFinite() && ts.lastPrice > 0.0
        val tsFresh = tsPriced && tsAge <= MARK_MAX_AGE_MS_7731
        val px = if (tsFresh) ts.lastPrice else {
            val fromRegistry = markFor(ts.mint, { null }, nowMs)
            when {
                fromRegistry != null -> {
                    try { PipelineHealthCollector.labelInc("FORWARD_LABEL_ENTRY_FROM_CANONICAL_MARK_7733") } catch (_: Throwable) {}
                    fromRegistry
                }
                // V5.0.7735 — the gate ruled on this very price. 5.0.7734 still
                // skipped 2,325 verdicts for want of a two-minute-fresh mark while
                // observing 505; a label may start from the price the decision
                // was made on when it is under ten minutes old. The exit marks
                // that close the label keep the two-minute bar.
                tsPriced && tsAge <= ENTRY_MARK_MAX_AGE_MS_7735 -> {
                    try { PipelineHealthCollector.labelInc("FORWARD_LABEL_ENTRY_STALE_MARK_7735") } catch (_: Throwable) {}
                    ts.lastPrice
                }
                else -> null
            }
        }
        if (px == null || !px.isFinite() || px <= 0.0) {
            skippedNoPrice.incrementAndGet()
            return
        }
        // V5.0.7930 — an ADMIT supersedes a pending refusal of the same (mint, lane): a
        // token refused earlier (or by an advisory gate) and then bought was labelled
        // and graded as "refused" for up to four hours.
        val prior7930 = pending[key]
        val upgrade7930 = admitted && prior7930 != null && !prior7930.admitted
        if (upgrade7930) {
            pending.remove(key, prior7930)
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_REFUSAL_SUPERSEDED_BY_ADMIT_7930") } catch (_: Throwable) {}
        }
        val seen = lastSeenAt[key]
        if (!upgrade7930 && seen != null && nowMs - seen < REOBSERVE_MS_7731) { skippedRecent.incrementAndGet(); return }
        if (pending.containsKey(key)) { skippedRecent.incrementAndGet(); return }
        if (pending.size >= MAX_PENDING_7731) {
            skippedFull.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FORWARD_LABELER_PENDING_FULL_7731") } catch (_: Throwable) {}
            return
        }
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val liq = if (ts.lastLiquidityUsd.isFinite()) ts.lastLiquidityUsd else 0.0
        // V5.0.7962 — the lane's measured live round trip once it has 8+ trades (CostLedger7962; excludes the chase).
        val cost = CostLedger7962.costOr7962(l, Double.NaN, (try { FieldManual7715.allInCostPct(COST_SIZE_USD_7731, liq) } catch (_: Throwable) { FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 })) +
            // V5.0.7961 — what the lane's fills cost above the decision price (EntryChase7961).
            (try { EntryChase7961.lanePenaltyPct7961(l) } catch (_: Throwable) { 0.0 })
        val cell = cellKey(ts.source, l, TrustedMcap8019.mcap8019(ts), ageMs)  // V5.0.8019 — the cap the price agrees with
        // V5.0.7734 — the regime is read now, at decision time, as the forecast
        // model keys it; the token state carries no setup quality or edge phase,
        // so the label lands on the coarse signature (lane | band | regime).
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }
        pending[key] = Obs(ts.mint, ts.symbol, cell, sourceFamily(ts.source), l, admitted, score, "U", regime, "UNKNOWN", px, cost.coerceIn(0.0, 60.0), nowMs)
            .also {
                it.entryMcap = if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) ts.lastMcap else 0.0
                it.reason7967 = reason.orEmpty().take(120)
                it.stage = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "" }
                it.feats7972 = try { SpecialistMiner7972.features7972(ts, l, nowMs) } catch (_: Throwable) { emptyList() }
                it.entryLiq7997 = if (ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd > 0.0) ts.lastLiquidityUsd else 0.0
            }
        lastSeenAt[key] = nowMs
        // V5.0.7883 — the lane's trade shape (tokenomics, timing, flow) at this decision.
        try { TradeShapeLearner7883.capture(ts, l, nowMs) } catch (_: Throwable) {}
        // V5.0.7885 — the Cortex snapshot + every voter's opinion at this decision.
        try { com.lifecyclebot.engine.cortex.Cortex7885.capture(ts, l, admitted, nowMs, reason) } catch (_: Throwable) {}
        // V5.0.7955 — the setup this decision is traded as keys its exit profile (classify is cached by the capture above).
        try { pending[key]?.setup7955 = com.lifecyclebot.engine.ExitProfile7955.entrySetup7955(ts, l, nowMs) } catch (_: Throwable) {}
        // V5.0.7737 — a fresh launch is also followed by first touch (+50% / -30%).
        try { FreshLaunchSelector7737.observe(ts, admitted, px, cost.coerceIn(0.0, 60.0), nowMs) } catch (_: Throwable) {}
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
            .filter { !it.key.startsWith("AGG|") && !it.key.startsWith("LANE|") && !it.key.startsWith("SRC|") && !it.key.startsWith("STAGE|") }
            .sortedBy { synchronized(it.value) { it.value.n60 + it.value.n15 } }
            .take(MAX_CELLS_7731 / 10)
        victims.forEach { cells.remove(it.key, it.value) }
        try { PipelineHealthCollector.labelInc("FORWARD_LABELER_CELLS_PRUNED_7731") } catch (_: Throwable) {}
    }

    private fun book(o: Obs, horizon: Int, net: Double, gross: Double) {
        val keys = keysOf7928(o)
        for (k in keys) {
            val t = tallyFor(k)
            synchronized(t) {
                when (horizon) {
                    15 -> { t.n15 += 1; t.sum15 += net; if (net > 0.0) t.win15 += 1 }
                    60 -> { t.n60 += 1; t.sum60 += net; t.sumSq60 += net * net; if (net > 0.0) t.win60 += 1; if (gross >= RUNNER_PCT_7731) t.runner60 += 1 }
                    else -> { t.n240 += 1; t.sum240 += net; if (net > 0.0) t.win240 += 1 }
                }
                t.window8014(CELL_WINDOW_8014)
            }
        }
        when (horizon) { 15 -> booked15.incrementAndGet(); 60 -> booked60.incrementAndGet(); else -> booked240.incrementAndGet() }
        if (bookingsSincePersist.incrementAndGet() >= PERSIST_EVERY_BOOKINGS_7731) persist()
    }

    private fun markLost(o: Obs) {
        for (k in keysOf7928(o)) {
            val t = tallyFor(k)
            synchronized(t) { t.lost += 1 }
        }
        lostMark.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORWARD_LABEL_LOST_MARK_7731") } catch (_: Throwable) {}
    }

    /**
     * V5.0.7945 — a label is what the bot's exits would have earned, not the price
     * at the hour. Every learner read the 60/240-minute price alone, so a token that
     * spiked +276% at minute two and was back at entry by the hour taught every
     * lane, cell, voter and source "break-even minus cost": the lanes that find
     * spikes were graded as if the spike never happened. The spike tiers
     * (SpikeCapture7943) now sell into those prints live, so the label books what
     * they bank on the observed peak plus the remainder at the horizon.
     */
    private fun captured7945(o: Obs, net: Double, gross: Double): Pair<Double, Double> {
        // V5.0.7955 review — labels are credited on the FIXED ladder: crediting on the learned
        // ladder (fit to these same labels) inflated the grades that drive authority and size.
        // V5.0.8029 — graded on THIS lane's play: its own stop first (a coin through the stop before its peak is a
        // stopped trade, not a run), then the spike ladder on the peak with the rest at the mark — or at the stop.
        val stop8029 = try { com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(o.lane) } catch (_: Throwable) { Double.NaN }
        val g = com.lifecyclebot.engine.LaneParticipation8029.playGross8029(stop8029, o.peakPct, o.dipBeforePeak7962, o.minPct7962, gross) { pk, rest ->
            com.lifecyclebot.engine.SpikeCapture7943.realisableGrossPct(pk, rest).let { if (it.isFinite() && it > rest) it else rest }
        }
        if (!g.isFinite() || g == gross) return net to gross
        val dipFirst8029 = if (o.dipBeforePeak7962.isFinite()) o.dipBeforePeak7962 else o.minPct7962
        if (g < gross || (stop8029 > 0.0 && o.minPct7962 <= -stop8029)) try {
            com.lifecyclebot.engine.LaneParticipation8029.noteGraded8029(first = dipFirst8029 <= -stop8029, afterPeak = dipFirst8029 > -stop8029)
        } catch (_: Throwable) {}
        if (g > gross) capturedLabels7945.incrementAndGet()
        return (net + (g - gross)).coerceAtMost(NET_CEILING_PCT_7738) to g
    }

    private val capturedLabels7945 = AtomicLong(0)

    private fun bookSixty7944(o: Obs, net0: Double, gross0: Double, nowMs: Long) {
        // V5.0.7955 — the give-back from the peak to the 5-minute read rides on the observation to its 60-minute sample.
        o.giveback5_7955 = com.lifecyclebot.engine.ExitProfile7955.giveback7955(o.peakPct, gross0)
        val (net, gross) = captured7945(o, net0, gross0)
        book(o, 60, net, gross)
        o.bookedNet7997 = net; o.bookedGross7997 = gross
        try { TradeShapeLearner7883.onLabel60(o.mint, o.lane, net, gross) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.cortex.Cortex7885.onLabel(o.mint, o.lane, 60, net, gross) } catch (_: Throwable) {}
        // V5.0.7962 — the same 5-minute net label grades the decision cell for slot / size / priority.
        try { com.lifecyclebot.engine.CellAllocator7962.onLabel7962(o.cell, o.lane, o.setup7955, net) } catch (_: Throwable) {}
        // V5.0.7972 — the same label grades every pair/triple of the decision's facts (specialist miner).
        try { SpecialistMiner7972.onLabel7972(o.lane, o.mint, o.feats7972, net, gross, o.atMs) } catch (_: Throwable) {}
        try { SignalSourceProof7291.onForwardLabel7731(o.mint, net / 100.0, nowMs) } catch (_: Throwable) {}
        // V5.0.7734 — the same label teaches the forecast model the admission stack reads.
        try { com.lifecyclebot.engine.ForwardOutcomeModel.recordLabel7734(o.lane, o.score, o.quality, o.regime, o.phase, net) } catch (_: Throwable) {}
        // V5.0.7813 — counterfactual entry-quality learning, graded whether FDG admitted or refused.
        try { com.lifecyclebot.engine.ExpertTraderKnowledge7813.recordForwardOutcome7813(o.mint, o.lane, net, o.admitted, o.atMs) } catch (_: Throwable) {}
    }

    // ── V5.0.7997 — PROGRESSIVE GRADING ──
    //
    // Owner: "grade coins at 5m, 15 min, 30 min, 1 hour, 4 hours, and only if it's still running,
    // hasn't died, holds liq and volume, then repeat ... a spread of learning data over the 4 hours
    // instead of one stamp." Every learner was graded once, at 5 minutes, so a coin that went 300x
    // over hours taught them "-9%". The 5-minute label still lands first (learning stays fast); at
    // each later checkpoint, while the coin still trades (a fresh mark, not down 60%, liquidity held
    // above 40% of the decision's), the label is REVISED to what the exits would have banked on its
    // path so far (spike tiers on the observed peak + the rest at the mark) in every book it went to:
    // the lane / cell tallies here, the setup books (LanePlaybook7907), the fact combinations
    // (SpecialistMiner7972) and the cell bandit (CellAllocator7962). Revision replaces the value; a
    // decision is never counted twice. Past 1 hour only coins still up 20%+ keep being followed.
    // V5.0.8013 — the owner's schedule: 5 m (the first label), 15 m, 30 m, 1 h, 5 h, 24 h, and beyond
    // (48 h, 72 h, 7 d) for a coin that is still running, holding liquidity and up 20%+ past the hour.
    private val CK_MS_7997 = longArrayOf(15L * 60_000L, 30L * 60_000L, 60L * 60_000L, 5L * 3_600_000L,
        24L * 3_600_000L, 48L * 3_600_000L, 72L * 3_600_000L, 7L * 24L * 3_600_000L)
    private const val RUNNING_GROSS_7997 = 20.0
    private const val DEAD_GROSS_7997 = -60.0
    private const val MAX_EXTENDED_7997 = 1_500
    private val revisions7997 = AtomicLong(0)
    private val upgraded7997 = AtomicLong(0)
    private val extended7997 = AtomicLong(0)
    private val diedEarly7997 = AtomicLong(0)

    /** Pure. V5.0.7997 — is a coin still alive at a checkpoint? Not collapsed, liquidity held (unknown = held). */
    private fun alive7997(grossPct: Double, entryLiq: Double, nowLiq: Double): Boolean =
        grossPct.isFinite() && grossPct > DEAD_GROSS_7997 &&
            (entryLiq <= 0.0 || !nowLiq.isFinite() || nowLiq <= 0.0 || nowLiq >= 0.4 * entryLiq)

    private fun nowLiq7997(mint: String): Double =
        try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.lastLiquidityUsd ?: Double.NaN } catch (_: Throwable) { Double.NaN }

    private fun keepRunning7997(o: Obs, gross: Double): Boolean =
        pending.size < MAX_PENDING_7731 && extended7997.get() - diedEarly7997.get() < MAX_EXTENDED_7997 &&
            gross >= RUNNING_GROSS_7997 && alive7997(gross, o.entryLiq7997, nowLiq7997(o.mint))

    /** Revises the 5-minute label at each due checkpoint; true when the coin is no longer followed. */
    private fun checkpoint7997(o: Obs, net0: Double, gross0: Double, age: Long, nowMs: Long): Boolean {
        if (o.ck7997 >= CK_MS_7997.size) return true
        if (age < CK_MS_7997[o.ck7997]) return false
        o.ck7997 += 1
        val (net, gross) = captured7945(o, net0, gross0)
        val oldNet = o.bookedNet7997
        val oldGross = o.bookedGross7997
        if (oldNet.isFinite() && net.isFinite() && kotlin.math.abs(net - oldNet) >= 0.5) {
            revise7997(o, oldNet, net, oldGross, gross)
            o.bookedNet7997 = net; o.bookedGross7997 = gross
            revisions7997.incrementAndGet()
            if (net > oldNet) upgraded7997.incrementAndGet()
        }
        val alive = alive7997(gross0, o.entryLiq7997, nowLiq7997(o.mint))
        if (!alive) { diedEarly7997.incrementAndGet(); o.ck7997 = CK_MS_7997.size }
        return !alive || (age >= H240_MS_7731 && gross0 < RUNNING_GROSS_7997) || o.ck7997 >= CK_MS_7997.size
    }

    private fun revise7997(o: Obs, oldNet: Double, newNet: Double, oldGross: Double, newGross: Double) {
        for (k in keysOf7928(o)) {
            val t = tallyFor(k)
            synchronized(t) {
                if (t.n60 <= 0) return@synchronized
                t.sum60 += newNet - oldNet
                t.sumSq60 = (t.sumSq60 + newNet * newNet - oldNet * oldNet).coerceAtLeast(0.0)
                t.win60 = (t.win60 + (if (newNet > 0.0) 1 else 0) - (if (oldNet > 0.0) 1 else 0)).coerceIn(0, t.n60)
                val ro = oldGross.isFinite() && oldGross >= RUNNER_PCT_7731
                val rn = newGross.isFinite() && newGross >= RUNNER_PCT_7731
                if (ro != rn) t.runner60 = (t.runner60 + if (rn) 1 else -1).coerceIn(0, t.n60)
            }
        }
        try { com.lifecyclebot.engine.cortex.LanePlaybook7907.reviseLabel7997(o.mint, o.lane, oldNet, newNet, oldGross, newGross) } catch (_: Throwable) {}
        try { SpecialistMiner7972.reviseLabel7997(o.lane, o.feats7972, oldNet, newNet, oldGross, newGross) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.CellAllocator7962.reviseLabel7997(o.cell, o.lane, o.setup7955, oldNet, newNet) } catch (_: Throwable) {}
        // V5.0.8006 — the Cortex (every voter seat, scoreboard bucket, veto audit, setup x verdict book) too.
        try { com.lifecyclebot.engine.cortex.Cortex7885.reviseLabel8006(o.mint, o.lane, oldNet, newNet, oldGross, newGross) } catch (_: Throwable) {}
    }

    fun progressiveLine7997(): String =
        "checkpoints=5m,15m,30m,1h,5h,24h,48h,72h,7d revisions=${revisions7997.get()} upgraded=${upgraded7997.get()} " +
            "followedPast1h=${extended7997.get()} stoppedDead=${diedEarly7997.get()} runExitSamples8006=${exitRunSamples8006.get()}"

    private fun bookTwoForty7944(o: Obs, net0: Double, gross0: Double, deferExit8006: Boolean = false) {
        // V5.0.7955 — one exit-profile sample per observation: peak, time to peak, give-back to the 60-minute read.
        // V5.0.7955 review — exits are learned from decisions the bot took (and realised closes),
        // not from the refused pool, which is mostly tokens that fade.
        // V5.0.7961 — a refused decision whose setup fired teaches that setup's own key only.
        // V5.0.8006 — a coin still running at the hour is followed on (7997); its exit sample waits
        // for the end of the run, so the exit plans learn the peak and time-to-peak of the whole
        // run instead of "peaked at minute 60" (which taught every runner key to sell early).
        if (deferExit8006) o.exitDeferred8006 = true else exitSample8006(o, gross0)
        val (net, gross) = captured7945(o, net0, gross0)
        book(o, 240, net, gross)
        try { com.lifecyclebot.engine.cortex.Cortex7885.onLabel(o.mint, o.lane, 240, net, gross) } catch (_: Throwable) {}
    }

    private val exitRunSamples8006 = AtomicLong(0)

    /** The observation's one exit-profile sample, at the hour or at the end of a followed run. */
    private fun exitSample8006(o: Obs, gross0: Double) {
        val setupFired7961 = try { com.lifecyclebot.engine.ExitProfile7955.setupFired7961(o.setup7955) } catch (_: Throwable) { false }
        if (o.admitted || setupFired7961) try {
            com.lifecyclebot.engine.ExitProfile7955.onLabel7955(
                o.lane, o.setup7955, maxOf(o.peakPct, gross0), if (o.peakAtMs7955 > o.atMs) o.peakAtMs7955 - o.atMs else 0L,
                com.lifecyclebot.engine.ExitProfile7955.giveback7955(maxOf(o.peakPct, gross0), gross0), o.giveback5_7955,
                keyOnly = !o.admitted, dipBeforePeakPct = o.dipBeforePeak7962,
            )
        } catch (_: Throwable) {}
    }

    /** V5.0.8006 — a followed run ended: its deferred exit sample is taken on the whole path. */
    private fun endRun8006(o: Obs, gross: Double) {
        if (!o.exitDeferred8006) return
        o.exitDeferred8006 = false
        exitRunSamples8006.incrementAndGet()
        exitSample8006(o, gross)
    }

    /**
     * V5.0.7944 — what a vanished mark was worth. 5.0.7941 dropped 1,928 refused
     * labels as LOST_MARK and 5.0.7942 then counted every one of them as -100%,
     * which was as wrong as dropping them. Every route and the curve stopped
     * pricing the token by its horizon, so its last price is the best read of
     * what it was worth:
     *  - DEAD: the last read was already a collapse (gross <= [DEAD_GROSS_PCT_7944])
     *    and nothing prices it now: the pool or curve is gone, booked at -100%.
     *  - LAST_MARK: a read from the second half of the horizon: booked at it.
     *  - GAP: no read late enough to say anything (the app was off, or it left the
     *    watchlist early): stays LOST_MARK and is not booked either way.
     */
    enum class Vanished7944 { DEAD, LAST_MARK, GAP }

    private const val DEAD_GROSS_PCT_7944 = -60.0

    /** Pure. [lastSeenAgeMs] is the observation's age at its last read, or <= 0 when it never had one. */
    fun classifyVanished7944(lastGrossPct: Double, lastSeenAgeMs: Long, horizonMs: Long): Vanished7944 = when {
        lastSeenAgeMs <= 0L || !lastGrossPct.isFinite() -> Vanished7944.GAP
        lastGrossPct <= DEAD_GROSS_PCT_7944 -> Vanished7944.DEAD
        lastSeenAgeMs >= horizonMs / 2 -> Vanished7944.LAST_MARK
        else -> Vanished7944.GAP
    }

    private val vanishedDead7944 = AtomicLong(0)
    private val vanishedLastMark7944 = AtomicLong(0)

    private fun resolveVanished7944(o: Obs, horizon: Int, nowMs: Long) {
        val horizonMs = if (horizon == 60) H60_MS_7731 else H240_MS_7731
        val cutoff = o.atMs + horizonMs + graceFor7946(horizonMs)
        var px = o.lastPx; var at = o.lastPxAtMs
        // A stale registry read newer than ours is still a later real price.
        try {
            val m = CanonicalPriceMarkRegistry6522.get(o.mint)
            val rp = m?.priceUsd?.value?.toDouble() ?: 0.0
            if (m != null && m.timestampMs > at && m.timestampMs in (o.atMs + 1)..cutoff && rp.isFinite() && rp > 0.0) { px = rp; at = m.timestampMs }
        } catch (_: Throwable) {}
        if (at > cutoff) at = 0L
        val gross = if (px > 0.0 && o.entryPrice > 0.0) (px / o.entryPrice - 1.0) * 100.0 else Double.NaN
        when (classifyVanished7944(gross, if (at > o.atMs) at - o.atMs else 0L, horizonMs)) {
            Vanished7944.GAP -> if (horizon == 60) markLost(o)
            Vanished7944.DEAD -> {
                vanishedDead7944.incrementAndGet()
                if (horizon == 60) bookSixty7944(o, -100.0, -100.0, nowMs) else bookTwoForty7944(o, -100.0, -100.0)
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_VANISHED_DEAD_7944") } catch (_: Throwable) {}
            }
            Vanished7944.LAST_MARK -> {
                vanishedLastMark7944.incrementAndGet()
                val net = netPct(o.entryPrice, px, o.costPct).coerceAtMost(NET_CEILING_PCT_7738)
                if (horizon == 60) bookSixty7944(o, net, gross, nowMs) else bookTwoForty7944(o, net, gross)
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_VANISHED_LAST_MARK_7944") } catch (_: Throwable) {}
            }
        }
    }

    /** A price for [mint] from the loop's token states, else the canonical mark registry when fresh. */
    /**
     * V5.0.7948 — the same mark a forward label is read on (loop price, else the
     * canonical registry, else the off-watch batch), for the learners that grade on
     * the label's clock (CortexTiming7900, the shadow book). They read the loop's
     * token-state closure alone, so a candidate that left the watchlist was never
     * priced at its window: Timing WINDOW_MISSED=544, shadow book 9 opens / 0 closes.
     */
    fun markFor7948(mint: String, priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()): Double? =
        try { markFor(mint, priceFor, nowMs) } catch (_: Throwable) { null }

    private fun markFor(mint: String, priceFor: (String) -> Double?, nowMs: Long): Double? {
        val fromLoop = try { priceFor(mint) } catch (_: Throwable) { null }
        if (fromLoop != null && fromLoop.isFinite() && fromLoop > 0.0) return fromLoop
        val fromRegistry = try {
            val m = CanonicalPriceMarkRegistry6522.get(mint)
            if (m == null || m.timestampMs <= 0L || nowMs - m.timestampMs > MARK_MAX_AGE_MS_7731) null
            else m.priceUsd.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
        } catch (_: Throwable) { null }
        if (fromRegistry != null) return fromRegistry
        val off = offWatchMarks7737[mint] ?: return null
        return if (nowMs - off.second <= MARK_MAX_AGE_MS_7731) off.first else null
    }

    /**
     * V5.0.7809 §THE_OLDEST_DEAD_MINTS_HELD_THE_BATCH. Off-watch marks are
     * fetched fifty at a time, oldest due observation first. A mint Jupiter and
     * the curve no longer price stays due (and oldest) until its 70-minute
     * lost-mark deadline, so once fifty dead mints sat at the head every batch
     * re-asked for exactly those fifty and no younger observation was ever
     * priced: large pending/observed, booked15/60/240 near zero. A mint just
     * asked for waits [OFFWATCH_RETRY_MS_7809] before it is asked again, so the
     * batch rotates through every due observation.
     */
    private const val OFFWATCH_RETRY_MS_7809 = 90_000L
    private val offWatchAttemptAt7809 = ConcurrentHashMap<String, Long>()
    private val offWatchDeferred7809 = AtomicLong(0)
    private val horizonMissed7809 = AtomicLong(0)

    /** Pure: a horizon label may book only from a mark at most the lost-mark grace past it. */
    fun horizonOpen7809(ageMs: Long, horizonMs: Long): Boolean =
        ageMs >= horizonMs && ageMs <= horizonMs + graceFor7946(horizonMs)

    private fun rotateDue7809(due: List<String>, nowMs: Long): List<String> {
        if (offWatchAttemptAt7809.size > MAX_SEEN_7731) {
            offWatchAttemptAt7809.entries.removeIf { nowMs - it.value > OFFWATCH_RETRY_MS_7809 }
        }
        val (recent, ready) = due.distinct().partition { m ->
            val at = offWatchAttemptAt7809[m]
            at != null && nowMs - at in 0L until OFFWATCH_RETRY_MS_7809
        }
        if (recent.isNotEmpty()) offWatchDeferred7809.addAndGet(recent.size.toLong())
        return ready
    }

    /** V5.0.7737 — true when [o] has reached a horizon it has not booked yet. */
    private fun dueAtHorizon7737(o: Obs, age: Long): Boolean =
        (!o.done15 && age >= H15_MS_7731) || (!o.done60 && age >= H60_MS_7731) || (!o.done240 && age >= H240_MS_7731)

    /** V5.0.7737 — prices the oldest due, unpriced mints (the 60-minute label first) in one batch on a background thread. */
    private fun fetchOffWatchMarks7737(due: List<String>, nowMs: Long) {
        if (due.isEmpty() || nowMs - offWatchLastFetchMs7737 < OFFWATCH_FETCH_GAP_MS_7737) return
        if (!offWatchInFlight7737.compareAndSet(false, true)) return
        offWatchLastFetchMs7737 = nowMs
        val batch = due.distinct().take(OFFWATCH_BATCH_7737)
        for (m in batch) offWatchAttemptAt7809[m] = nowMs
        try {
            Thread({
                try {
                    val got = HashMap(com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(batch))
                    // V5.0.7753 — Jupiter's price API misses most young tokens
                    // (5.0.7749 offWatch priced=1517 missed=7233); a pump.fun token
                    // still on its curve is priced from the curve account itself.
                    val missed7753 = batch.filter { it !in got }
                    if (missed7753.isNotEmpty()) {
                        val curve7753 = try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(missed7753) } catch (_: Throwable) { emptyMap() }
                        got.putAll(curve7753)
                        offWatchCurvePriced7753.addAndGet(curve7753.size.toLong())
                    }
                    val at = System.currentTimeMillis()
                    for ((m, px) in got) offWatchMarks7737[m] = px to at
                    offWatchPriced7737.addAndGet(got.size.toLong())
                    offWatchMissed7737.addAndGet((batch.size - got.size).toLong())
                    PipelineHealthCollector.labelInc("FORWARD_LABEL_OFFWATCH_BATCH_7737")
                    val cutoff = at - MARK_MAX_AGE_MS_7731
                    offWatchMarks7737.entries.removeIf { it.value.second < cutoff }
                } catch (_: Throwable) {
                } finally {
                    offWatchInFlight7737.set(false)
                }
            }, "fwd-label-offwatch-7737").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            offWatchInFlight7737.set(false)
        }
    }

    fun tick(priceFor0: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        // V5.0.7931 — crypto-universe and Markets identities are priced by their own feeds.
        val priceFor: (String) -> Double? = { m -> priceFor0(m) ?: com.lifecyclebot.engine.cortex.CrossAssetCortex7931.priceFor(m, nowMs) }
        val freshUnpriced7737 = try { FreshLaunchSelector7737.tick({ m -> markFor(m, priceFor, nowMs) }, nowMs) } catch (_: Throwable) { emptyList() }
        if (pending.isEmpty() && freshUnpriced7737.isEmpty()) return
        val dueUnpriced7737 = ArrayList<Pair<String, Long>>()
        for ((key, o) in pending.entries.toList()) {
            val age = nowMs - o.atMs
            val px = markFor(o.mint, priceFor, nowMs)
            if (px == null) {
                if (dueAtHorizon7737(o, age)) dueUnpriced7737.add(o.mint to (if (o.done60) o.atMs + H240_MS_7731 else o.atMs))
                // V5.0.8013 — a followed coin's due checkpoint asks for an off-watch mark too.
                else if (checkpointDue8013(o, age)) dueUnpriced7737.add(o.mint to (o.atMs + CK_MS_7997[o.ck7997]))
                if (!o.done60 && age >= H60_MS_7731 + graceFor7946(H60_MS_7731)) {
                    // V5.0.7944 — nothing priced it at its 60-minute horizon: booked at its
                    // real last price (or -100% when it died), lost only when there is no read.
                    resolveVanished7944(o, 60, nowMs)
                    pending.remove(key, o)
                } else if (o.done60 && age >= H240_MS_7731 + graceFor7946(H240_MS_7731)) {
                    if (!o.done240) { o.done240 = true; resolveVanished7944(o, 240, nowMs); pending.remove(key, o) }
                    // V5.0.8013 — a coin still on the progressive schedule is kept while unpriced (it left
                    // the watchlist; its checkpoint fetches an off-watch mark); it used to be dropped here,
                    // so the 5 h / 24 h grades almost never happened.
                    else if (!followed8013(o, nowMs)) {
                        if (o.lastPx > 0.0 && o.entryPrice > 0.0) endRun8006(o, (o.lastPx / o.entryPrice - 1.0) * 100.0)
                        pending.remove(key, o)
                    }
                }
                continue
            }
            if (basisSuspect7738(o.entryPrice, px) && !runCorroborated7769(
                    o.entryPrice, px, o.entryMcap, nowMcap7769(o.mint),
                    try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 },
                )) {
                // V5.0.7738 — not a price move; the observation is dropped unbooked.
                basisSuspect7738.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_BASIS_SUSPECT_7738") } catch (_: Throwable) {}
                pending.remove(key, o)
                continue
            }
            val gross = (px / o.entryPrice - 1.0) * 100.0
            if (gross < o.minPct7962) o.minPct7962 = gross
            // V5.0.8000 — a runner inside its first 30 minutes is broadcast to every installed instance.
            if (gross >= 100.0 && age <= 30L * 60_000L) try { HiveEdge8000.noteRunner8000(o.mint, o.symbol, gross, o.atMs) } catch (_: Throwable) {}
            if (gross >= 400.0 && o.peakPct < 400.0) try {
                com.lifecyclebot.engine.RunnerGrab7967.onRunnerLabel7967(o.mint, o.symbol, o.lane, o.admitted, o.reason7967, gross, nowMs)
                // V5.0.7973 — a +400% coin's name/ticker words become a 6-hour theme (copycat / beta rotation).
                com.lifecyclebot.engine.market.MemeMeta7973.noteLeader7973(o.mint, o.symbol,
                    try { com.lifecyclebot.engine.BotService.status.tokens[o.mint]?.name.orEmpty() } catch (_: Throwable) { "" }, nowMs)
            } catch (_: Throwable) {}
            // V5.0.8017 — every 4x+ runner joined to the bot's own position (held? entry vs start? share banked?).
            // V5.0.8018 — a confirmed run (+100% over the decision price inside the hour) can earn a second entry.
            if (gross >= com.lifecyclebot.engine.RunnerPlay8018.RUN_CONFIRM_PCT_8018) try { com.lifecyclebot.engine.RunnerPlay8018.onRun8018(o.mint, gross, nowMs - o.atMs, nowMs) } catch (_: Throwable) {}
            if (gross >= RunnerCapture8017.RUNNER_PEAK_PCT_8017 && gross > o.peakPct) try { RunnerCapture8017.onRunnerPeak8017(o.mint, o.symbol, o.lane, o.entryPrice, gross, if (o.admitted) "" else o.reason7967.take(40), nowMs) } catch (_: Throwable) {}
            if (gross > o.peakPct) { o.peakPct = gross; o.peakAtMs7955 = nowMs; o.dipBeforePeak7962 = o.minPct7962 }
            val priorPx7944 = o.lastPx to o.lastPxAtMs
            o.lastPx = px; o.lastPxAtMs = nowMs
            val net = netPct(o.entryPrice, px, o.costPct).coerceAtMost(NET_CEILING_PCT_7738)
            // V5.0.7809 — a horizon label is booked only from a mark inside its own
            // window (Field Manual L357): a 15-minute label first priced at minute 70
            // used to book the 70-minute move into the 15-minute cohort.
            if (!o.done15 && age >= H15_MS_7731) { o.done15 = true; if (horizonOpen7809(age, H15_MS_7731)) captured7945(o, net, gross).let { (n, g) -> book(o, 15, n, g) } else horizonMissed7809.incrementAndGet() }
            if (!o.done60 && age >= H60_MS_7731 && !horizonOpen7809(age, H60_MS_7731)) {
                // Restored after its 60-minute window closed: priced at the last mark seen before it
                // (V5.0.7944), lost only when there is none inside the window.
                o.lastPx = priorPx7944.first; o.lastPxAtMs = priorPx7944.second
                resolveVanished7944(o, 60, nowMs)
                pending.remove(key, o)
                continue
            }
            if (!o.done60 && age >= H60_MS_7731) {
                o.done60 = true
                bookSixty7944(o, net, gross, nowMs)
            }
            // V5.0.7997 — progressive grading: 15 m, 30 m, 1 h, 4 h, 8 h, 12 h, 24 h while the coin lives.
            val stop7997 = if (o.done60) checkpoint7997(o, net, gross, age, nowMs) else false
            if (!o.done240 && age >= H240_MS_7731) {
                o.done240 = true
                val follow8006 = !stop7997 && keepRunning7997(o, gross)
                if (horizonOpen7809(age, H240_MS_7731)) bookTwoForty7944(o, net, gross, deferExit8006 = follow8006)
                else horizonMissed7809.incrementAndGet()
                if (!follow8006) pending.remove(key, o) else extended7997.incrementAndGet()
            } else if (o.done240 && stop7997) {
                endRun8006(o, gross)
                pending.remove(key, o)
            }
        }
        val due7737 = rotateDue7809(dueUnpriced7737.sortedBy { it.second }.map { it.first } + freshUnpriced7737, nowMs)
        if (due7737.isNotEmpty()) fetchOffWatchMarks7737(due7737, nowMs)
    }

    // ── reads ──

    private fun cellStat(key: String): CellStat? {
        // V5.0.8000 — the rest of the hive's labels for this key ride on the local tally.
        val hive = try { HiveEdge8000.net8000("FRL|$key") } catch (_: Throwable) { null }
        val t = cells[key] ?: if (hive == null) return null else Tally()
        return synchronized(t) {
            val n = t.n60 + (hive?.get(0)?.toInt() ?: 0)
            val sum = t.sum60 + (hive?.get(1) ?: 0.0)
            val sumSq = t.sumSq60 + (hive?.get(2) ?: 0.0)
            val wins = t.win60 + (hive?.get(3)?.toInt() ?: 0)
            val runners = t.runner60 + (hive?.get(4)?.toInt() ?: 0)
            if (n <= 0 && t.lost <= 0) null else {
                val mean = if (n > 0) sum / n else 0.0
                val variance = if (n > 1) ((sumSq / n) - mean * mean).coerceAtLeast(0.0) else 0.0
                val se = if (n > 1) kotlin.math.sqrt(variance / n) else Double.POSITIVE_INFINITY
                CellStat(
                    key = key, n60 = n, meanNet60Pct = mean,
                    winRate60 = if (n > 0) wins.toDouble() / n else 0.0,
                    runnerRate60 = if (n > 0) runners.toDouble() / n else 0.0,
                    stderr60Pct = se, lost = t.lost,
                    n240 = t.n240, meanNet240Pct = if (t.n240 > 0) t.sum240 / t.n240 else 0.0,
                )
            }
        }
    }

    /** V5.0.8000 — HiveEdge8000: this instance's own 5-minute tallies ([n, sum, sumSq, wins, runners, 0]). */
    fun hiveSnapshot8000(): Map<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>(cells.size)
        for ((k, t) in cells) synchronized(t) {
            if (t.n60 >= 3) out[k] = doubleArrayOf(t.n60.toDouble(), t.sum60, t.sumSq60, t.win60.toDouble(), t.runner60.toDouble(), 0.0)
        }
        return out
    }

    fun cellStatFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CellStat? {
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        return cellStat(cellKey(ts.source, lane, TrustedMcap8019.mcap8019(ts), ageMs))  // V5.0.8019
    }

    private fun laneStat(lane: String): CellStat? = cellStat(laneKey(lane.trim().uppercase()))

    /** V5.0.7928 — the 60-minute record of every [lane] decision taken at lifecycle [stage]. */
    fun stageStatFor7928(lane: String, stage: String): CellStat? = cellStat(stageKey7928(lane, stage))

    /** V5.0.7737 — the lane's 60-minute label record (LaneAutoPauseGuard's label-proof release). */
    fun laneStatFor7737(lane: String): CellStat? = laneStat(lane)

    private fun fmtStat(s: CellStat?): String =
        if (s == null) "n=0" else "n=${s.n60} net=${"%+.1f".format(s.meanNet60Pct)}% wr=${"%.0f".format(s.winRate60 * 100)}% run=${"%.0f".format(s.runnerRate60 * 100)}% lost=${s.lost}"

    fun statusLine(): String {
        val cellStats = cells.keys
            .filter { !it.startsWith("AGG|") && !it.startsWith("LANE|") && !it.startsWith("SRC|") && !it.startsWith("STAGE|") }
            .mapNotNull { cellStat(it) }
            .filter { it.n60 >= 30 }
        val best = cellStats.sortedByDescending { it.meanNet60Pct }.take(3)
        val worst = cellStats.sortedBy { it.meanNet60Pct }.take(3)
        val lanes = cells.keys.filter { it.startsWith("LANE|") }.map { it.removePrefix("LANE|") }.sorted()
            .mapNotNull { l -> laneStat(l)?.let { "$l[${fmtStat(it)}]" } }
        return "read=5m(slot60) early=2m late=60m pending=${pending.size} restored7735=${restoredPending7735.get()} observed=${observed.get()} booked15=${booked15.get()} booked60=${booked60.get()} booked240=${booked240.get()} " +
            "lostMark=${lostMark.get()} vanished7944[lastMark=${vanishedLastMark7944.get()} dead=${vanishedDead7944.get()}] spikeCredited7945=${capturedLabels7945.get()} offWatch7737[priced=${offWatchPriced7737.get()} missed=${offWatchMissed7737.get()} curve7753=${offWatchCurvePriced7753.get()} deferred7809=${offWatchDeferred7809.get()}] horizonMissed7809=${horizonMissed7809.get()} basisSuspect7738=${basisSuspect7738.get()} purged7738=${purgedCells7738.get()} skipped[noPrice=${skippedNoPrice.get()} recent=${skippedRecent.get()} full=${skippedFull.get()}] cells=${cellStats.size}/${cells.size}\n" +
            "      admitted60[${fmtStat(cellStat(AGG_ADMITTED))}] refused60[${fmtStat(cellStat(AGG_REFUSED))}]\n" +
            "      best60: ${best.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      worst60: ${worst.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      lanes60: ${lanes.joinToString(" · ").ifBlank { "no labels yet" }}"
    }

    /** Called when the service stops: the table survives the restart. */
    fun persistNow7731() {
        persist(force = true)
        try { FreshLaunchSelector7737.persistNow7737() } catch (_: Throwable) {}
        try { SpecialistMiner7972.saveNow7972() } catch (_: Throwable) {}   // V5.0.7972
        try {
            ForensicLogger.lifecycle(
                "FORWARD_LABELER_PERSISTED_7731",
                "cells=${cells.size} pending=${pending.size} booked60=${booked60.get()} lost=${lostMark.get()} " +
                    "admitted=[${fmtStat(cellStat(AGG_ADMITTED))}] refused=[${fmtStat(cellStat(AGG_REFUSED))}]",
            )
        } catch (_: Throwable) {}
    }
}

/** V5.0.8014 — Double to Int, rounded half up (counts scaled by a fresh-edge window). */
private fun Double.roundToIntSafe8014(): Int = if (this.isFinite()) kotlin.math.round(this).toInt() else 0
