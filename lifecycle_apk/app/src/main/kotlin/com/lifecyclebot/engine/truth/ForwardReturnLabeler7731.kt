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
    private fun keysOf7928(o: Obs): List<String> {
        val base = listOf(o.cell, laneKey(o.lane), sourceKey(o.source), if (o.admitted) AGG_ADMITTED else AGG_REFUSED)
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
        return pending.values.asSequence()
            .filter { nowMs - it.atMs <= H240_MS_7731 + LOST_GRACE_MS_7731 }
            .sortedByDescending { it.atMs }
            .take(MAX_PERSISTED_PENDING_7735)
            .joinToString(ROW_SEP_7735.toString()) { o ->
                listOf(
                    o.mint, o.symbol.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '), o.cell, o.source, o.lane,
                    if (o.admitted) "1" else "0", o.score.toString(), o.quality, o.regime, o.phase,
                    o.entryPrice.toString(), o.costPct.toString(), o.atMs.toString(),
                    if (o.done15) "1" else "0", if (o.done60) "1" else "0", if (o.done240) "1" else "0", o.peakPct.toString(),
                    o.entryMcap.toString(), o.stage, o.lastPx.toString(), o.lastPxAtMs.toString(),
                ).joinToString(fs)
            }
    }

    private fun restorePending7735(enc: String?, nowMs: Long): Int {
        if (enc.isNullOrBlank()) return 0
        var n = 0
        enc.split(ROW_SEP_7735).forEach { row ->
            val f = row.split(FIELD_SEP_7735)
            if (f.size !in 17..21) return@forEach
            val atMs = f[12].toLongOrNull() ?: return@forEach
            if (atMs <= 0L || nowMs - atMs > H240_MS_7731 + LOST_GRACE_MS_7731) return@forEach
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
        val cost = try { FieldManual7715.allInCostPct(COST_SIZE_USD_7731, liq) } catch (_: Throwable) { FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 }
        val cell = cellKey(ts.source, l, ts.lastMcap, ageMs)
        // V5.0.7734 — the regime is read now, at decision time, as the forecast
        // model keys it; the token state carries no setup quality or edge phase,
        // so the label lands on the coarse signature (lane | band | regime).
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }
        pending[key] = Obs(ts.mint, ts.symbol, cell, sourceFamily(ts.source), l, admitted, score, "U", regime, "UNKNOWN", px, cost.coerceIn(0.0, 60.0), nowMs)
            .also {
                it.entryMcap = if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) ts.lastMcap else 0.0
                it.stage = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "" }
            }
        lastSeenAt[key] = nowMs
        // V5.0.7883 — the lane's trade shape (tokenomics, timing, flow) at this decision.
        try { TradeShapeLearner7883.capture(ts, l, nowMs) } catch (_: Throwable) {}
        // V5.0.7885 — the Cortex snapshot + every voter's opinion at this decision.
        try { com.lifecyclebot.engine.cortex.Cortex7885.capture(ts, l, admitted, nowMs, reason) } catch (_: Throwable) {}
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
        val g = com.lifecyclebot.engine.SpikeCapture7943.realisableGrossPct(o.peakPct, gross)
        if (!g.isFinite() || g <= gross) return net to gross
        capturedLabels7945.incrementAndGet()
        return (net + (g - gross)).coerceAtMost(NET_CEILING_PCT_7738) to g
    }

    private val capturedLabels7945 = AtomicLong(0)

    private fun bookSixty7944(o: Obs, net0: Double, gross0: Double, nowMs: Long) {
        val (net, gross) = captured7945(o, net0, gross0)
        book(o, 60, net, gross)
        try { TradeShapeLearner7883.onLabel60(o.mint, o.lane, net, gross) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.cortex.Cortex7885.onLabel(o.mint, o.lane, 60, net, gross) } catch (_: Throwable) {}
        try { SignalSourceProof7291.onForwardLabel7731(o.mint, net / 100.0, nowMs) } catch (_: Throwable) {}
        // V5.0.7734 — the same label teaches the forecast model the admission stack reads.
        try { com.lifecyclebot.engine.ForwardOutcomeModel.recordLabel7734(o.lane, o.score, o.quality, o.regime, o.phase, net) } catch (_: Throwable) {}
        // V5.0.7813 — counterfactual entry-quality learning, graded whether FDG admitted or refused.
        try { com.lifecyclebot.engine.ExpertTraderKnowledge7813.recordForwardOutcome7813(o.mint, o.lane, net, o.admitted, o.atMs) } catch (_: Throwable) {}
    }

    private fun bookTwoForty7944(o: Obs, net0: Double, gross0: Double) {
        val (net, gross) = captured7945(o, net0, gross0)
        book(o, 240, net, gross)
        try { com.lifecyclebot.engine.cortex.Cortex7885.onLabel(o.mint, o.lane, 240, net, gross) } catch (_: Throwable) {}
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
                if (!o.done60 && age >= H60_MS_7731 + graceFor7946(H60_MS_7731)) {
                    // V5.0.7944 — nothing priced it at its 60-minute horizon: booked at its
                    // real last price (or -100% when it died), lost only when there is no read.
                    resolveVanished7944(o, 60, nowMs)
                    pending.remove(key, o)
                } else if (o.done60 && age >= H240_MS_7731 + graceFor7946(H240_MS_7731)) {
                    if (!o.done240) { o.done240 = true; resolveVanished7944(o, 240, nowMs) }
                    pending.remove(key, o)
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
            if (gross > o.peakPct) o.peakPct = gross
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
            if (!o.done240 && age >= H240_MS_7731) {
                o.done240 = true
                if (horizonOpen7809(age, H240_MS_7731)) bookTwoForty7944(o, net, gross)
                else horizonMissed7809.incrementAndGet()
                pending.remove(key, o)
            }
        }
        val due7737 = rotateDue7809(dueUnpriced7737.sortedBy { it.second }.map { it.first } + freshUnpriced7737, nowMs)
        if (due7737.isNotEmpty()) fetchOffWatchMarks7737(due7737, nowMs)
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
                    n240 = t.n240, meanNet240Pct = if (t.n240 > 0) t.sum240 / t.n240 else 0.0,
                )
            }
        }
    }

    fun cellStatFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CellStat? {
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        return cellStat(cellKey(ts.source, lane, ts.lastMcap, ageMs))
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
        try {
            ForensicLogger.lifecycle(
                "FORWARD_LABELER_PERSISTED_7731",
                "cells=${cells.size} pending=${pending.size} booked60=${booked60.get()} lost=${lostMark.get()} " +
                    "admitted=[${fmtStat(cellStat(AGG_ADMITTED))}] refused=[${fmtStat(cellStat(AGG_REFUSED))}]",
            )
        } catch (_: Throwable) {}
    }
}
