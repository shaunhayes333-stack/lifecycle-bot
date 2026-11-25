package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.9011 §DEGEN_HARVEST — owner, 25 Nov 19:37: "focussing in on the big big winners anything 200% plus ... a separate
 * module tied [to] work already built ... a proper degen crypto trader engineered spec logic specially for harvesting these
 * high wins that the bot already identifies."
 *
 * One module that owns the harvest of a position once it is a big winner: what stage it is in, which line sells it, and a
 * ledger of every live position that reached +200 % (what was banked, what took it). It adds no entry rule and no veto; it
 * is the exit doctrine's (RunnerDoctrine8584) plan for a winner, and the record that says whether the plan keeps the win.
 *
 * THE MEASUREMENT (launch-hour paths, 4,344 pump.fun launches, 22-25 Nov; bought at its open, filled one bar after the
 * decision, 3 % cost and 3 % slip on every sale; "rider" = the launch rider of 8941, the exits a position bought in its
 * first 15 minutes runs live):
 *
 *   lane stop 35 %, entries on a curve under 3 SOL (n 754)        every entry   peak +200..+900 %            peak +900 %+
 *     rider as built (nothing sells above the stop until 10x)     +22.7 %       n45 banked +199 %, 20 % of   n12 +1,335 %
 *                                                                               them ended under +50 %
 *     the doctrine's own line armed at a 2x peak (1.5x floor,     +21.6 %       n42 banked +204 %,  7 %      n11 +1,310 %
 *       then 55 % of the peak), rungs still from 10x                            ended under +50 %
 *   every at-its-open entry (n 1,344)
 *     rider as built                                              +17.2 %       n91 banked +216 %, 30 %      n18 +1,645 %
 *     line armed at a 2x peak                                     +17.2 %       n82 banked +227 %, 12 %      n17 +1,648 %
 *   lane stop 25 %: +15.7 -> +16.5 % and +10.2 -> +11.2 %, the same pattern (26 -> 8 % and 34 -> 13 % ended under +50 %).
 *
 * The average is the same within its error (+- 6..8 %); what changes is the winner that peaks between 3x and 10x, four in
 * five of all +200 % winners: ridden with nothing under it but the entry stop, one in five to one in three of them is
 * carried all the way back. 8941 measured "rungs and trail from 10x" against a trail from +25 % with rungs at 2x and 5x and
 * was right about both of those (they sell a launch on its way up); the line from a 2x peak was not one of its two cases.
 * The 10x-plus winners are untouched (their trail was already armed), and no rung is added below 10x.
 *
 * THE PLAN, by the position's confirmed peak:
 *   SEED    peak under 2x     a launch rider: the stop and every safety exit sell, every profit exit is held (8941,
 *                             unchanged). Any other meme position: the doctrine as before.
 *   RUNNER  peak 2x to 10x    the doctrine line is armed ([RUNNER_FROM_PCT_9011]): never under 1.5x, then 55 % of the peak
 *                             multiple (RunnerDoctrine8584.line8584), sold on two marks (8759). Every other profit exit
 *                             stays held above the line; rungs still wait (8941).
 *   MOON    peak 10x or more  the rungs (a quarter of what remains at 10x, 30x, 100x; RunnerGrab7967.TIERS_7967) and the
 *                             same line. Unchanged.
 * Stops, safety, liquidity, rug, kill switch and every tier-1 emergency always pass, as in the doctrine.
 *
 * THE LEDGER: every LIVE position whose own peak reached [BIG_WIN_PCT_9011] is booked at its close — banked %, share of its
 * peak, whether it was a round trip (banked under [ROUND_TRIP_PCT_9011]) and the family of the exit that closed it — and
 * kept across restarts. The status line states, for the positions that got there, how much the bot kept and what sold them.
 */
object DegenHarvest9011 {
    /** A position's own peak (%) from which it is a big win for the ledger. */
    const val BIG_WIN_PCT_9011 = 200.0
    /** A big win banked under this (%) was a round trip. */
    const val ROUND_TRIP_PCT_9011 = 50.0
    /** The peak (%) from which a launch rider's doctrine line is armed: a 2x. */
    const val RUNNER_FROM_PCT_9011 = 100.0

    enum class Stage9011 { SEED, RUNNER, MOON }

    /** Pure: the harvest stage of a position whose confirmed peak gain is [peakPct]. Unknown peak: SEED. */
    fun stage9011(peakPct: Double): Stage9011 = when {
        !peakPct.isFinite() || peakPct < RUNNER_FROM_PCT_9011 -> Stage9011.SEED
        peakPct < RunnerDoctrine8584.LAUNCH_RIDE_FROM_PCT_8941 -> Stage9011.RUNNER
        else -> Stage9011.MOON
    }

    /** Pure: the exit family of [reason] (figures dropped), so "SPIKE_CAPTURE_7943_T2_417PCT" and "..._T1_115PCT" count together. */
    fun family9011(reason: String?): String {
        val r = reason?.trim()?.uppercase().orEmpty()
        if (r.isBlank()) return "UNNAMED"
        return r.replace(Regex("[-+]?\\d+(\\.\\d+)?(PCT|%)?"), "").replace(Regex("[^A-Z]+"), "_").trim('_').take(36).ifBlank { "UNNAMED" }
    }

    internal class Book9011 {
        var reached = 0L; var kept = 0L; var roundTrips = 0L; var riders = 0L
        var sumBanked = 0.0; var sumPeak = 0.0; var sumShare = 0.0
        var sumHoldMin = 0.0; var holds = 0L   // V5.0.9012 — how long the big wins were held
        val byExit = LinkedHashMap<String, LongArray>()   // family -> [closes, round trips]

        /** Books one closed position; false when it never reached the big-win peak (nothing booked). */
        fun add(peakPct: Double, bankedPct: Double, reason: String?, rider: Boolean, holdMs: Long = 0L): Boolean {
            if (!peakPct.isFinite() || !bankedPct.isFinite() || peakPct < BIG_WIN_PCT_9011) return false
            reached++
            if (bankedPct >= BIG_WIN_PCT_9011) kept++
            val trip = bankedPct < ROUND_TRIP_PCT_9011
            if (trip) roundTrips++
            if (rider) riders++
            sumBanked += bankedPct; sumPeak += peakPct
            sumShare += (bankedPct / peakPct).coerceIn(0.0, 1.0)
            if (holdMs > 0L) { sumHoldMin += holdMs / 60_000.0; holds++ }
            val k = family9011(reason)
            if (byExit.size < 40 || byExit.containsKey(k)) byExit.getOrPut(k) { LongArray(2) }.also { it[0]++; if (trip) it[1]++ }
            return true
        }

        fun line(): String {
            if (reached == 0L) return "reached+200%=0"
            val n = reached.toDouble()
            return "reached+200%=$reached kept>=+200%=$kept roundTrips(<+50%)=$roundTrips riders=$riders " +
                "meanPeak=${"%+.0f".format(sumPeak / n)}% meanBanked=${"%+.0f".format(sumBanked / n)}% shareOfPeak=${"%.0f".format(100.0 * sumShare / n)}% " +
                (if (holds > 0L) "meanHoldMin=${"%.0f".format(sumHoldMin / holds)} " else "") +
                "byExit{" + byExit.entries.sortedByDescending { it.value[0] }.take(10).joinToString(",") { "${it.key}=${it.value[0]}/rt${it.value[1]}" } + "}"
        }

        fun encode(): String = "T\t$reached\t$kept\t$roundTrips\t$riders\t$sumBanked\t$sumPeak\t$sumShare\t$sumHoldMin\t$holds\n" +
            byExit.entries.joinToString("\n") { "E\t${it.key}\t${it.value[0]}\t${it.value[1]}" }

        fun decode(body: String) {
            for (l in body.lineSequence()) {
                val f = l.split('\t')
                if ((f.size == 8 || f.size == 10) && f[0] == "T") {
                    if (f.size == 10) { sumHoldMin = f[8].toDoubleOrNull() ?: 0.0; holds = f[9].toLongOrNull() ?: 0L }
                    reached = f[1].toLongOrNull() ?: 0L; kept = f[2].toLongOrNull() ?: 0L; roundTrips = f[3].toLongOrNull() ?: 0L
                    riders = f[4].toLongOrNull() ?: 0L; sumBanked = f[5].toDoubleOrNull() ?: 0.0
                    sumPeak = f[6].toDoubleOrNull() ?: 0.0; sumShare = f[7].toDoubleOrNull() ?: 0.0
                } else if (f.size == 4 && f[0] == "E" && f[1].isNotBlank()) {
                    byExit[f[1]] = longArrayOf(f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: 0L)
                }
            }
        }
    }

    private const val PERSIST_KEY_9011 = "degen_harvest_ledger_9011"
    private val book = Book9011()
    @Volatile private var loaded = false
    private val riderMints = ConcurrentHashMap<String, Boolean>()   // position id -> was a launch rider while open

    private fun load() {
        if (loaded) return
        if (!(try { LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(book) {
            if (loaded) return
            loaded = true
            try { LearningPersistence.load(PERSIST_KEY_9011)?.let { book.decode(it) } } catch (_: Throwable) {}
        }
    }

    /** Executor's sell door: the open position [positionId] is (or is not) a launch rider, remembered for its close. */
    fun noteRider9011(positionId: String, rider: Boolean) {
        if (positionId.isBlank() || !rider) return
        if (riderMints.size > 2_000) riderMints.clear()
        riderMints[positionId] = true
    }

    /** RunnerCapture8017.onClose8017: a position closed. Booked when it is LIVE, terminal and its own peak reached +200 %. */
    fun onClose9011(mode: String, terminal: Boolean, positionId: String, exitReason: String, realizedPct: Double, mfePct: Double, holdingMs: Long = 0L) {
        if (!terminal || !mode.equals("live", true)) return
        val rider = riderMints.remove(positionId) == true
        load()
        val peak = if (mfePct.isFinite()) maxOf(mfePct, realizedPct) else realizedPct   // an unread excursion is not a peak of zero
        val booked = synchronized(book) { book.add(peak, realizedPct, exitReason, rider, holdingMs) }
        if (!booked) return
        try { PipelineHealthCollector.labelInc(if (realizedPct < ROUND_TRIP_PCT_9011) "DEGEN_HARVEST_ROUND_TRIP_9011" else "DEGEN_HARVEST_BANKED_9011") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("DEGEN_HARVEST_CLOSE_9011", "pos=${positionId.take(12)} peak=${peak.toInt()} banked=${realizedPct.toInt()} exit=${family9011(exitReason)} rider=$rider") } catch (_: Throwable) {}
        if (loaded) try { LearningPersistence.save(PERSIST_KEY_9011, synchronized(book) { book.encode() }) } catch (_: Throwable) {}
    }

    fun statusLine9011(): String {
        load()
        return "degenHarvest9011[" + synchronized(book) { book.line() } +
            " plan{seed <2x: stop only (rider) · runner 2x-10x: line 1.5x then 55% of peak · moon 10x+: rungs + line}]"
    }
}
