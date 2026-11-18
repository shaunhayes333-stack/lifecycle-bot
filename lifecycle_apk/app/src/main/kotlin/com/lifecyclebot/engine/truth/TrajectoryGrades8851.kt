package com.lifecyclebot.engine.truth

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * One decision, several genuinely separate market observations. The 8521 Cortex still has one
 * immutable terminal entry target; that is NOT enough to tell an ignition from a pop-and-fade.
 * This companion learner grades each observation at 1, 5 and 15 minutes; qualified survivors
 * additionally get 1-hour, 4-hour and 24-hour reads, with the entry stage,
 * fired setup and timing band frozen at the decision. Each horizon has its own cell and receipt.
 * No later price can repair an earlier missed window, and marks never masquerade as wallet PnL.
 * Its bounded adjustment only orders already-fired strategies; it grants no FDG/risk authority.
 */
internal object TrajectoryGrades8851 {
    const val ONE_MIN = 60_000L
    const val FIVE_MIN = 5L * ONE_MIN
    const val FIFTEEN_MIN = 15L * ONE_MIN
    const val ONE_HOUR = 60L * ONE_MIN
    const val FOUR_HOURS = 4L * ONE_HOUR
    const val TWENTY_FOUR_HOURS = 24L * ONE_HOUR
    val HORIZONS = listOf(ONE_MIN, FIVE_MIN, FIFTEEN_MIN, ONE_HOUR, FOUR_HOURS, TWENTY_FOUR_HOURS)
    private const val MIN_SAMPLES = 30
    private const val MAX_CELLS = 4_096
    private const val MAX_CELL_SAMPLES = 240

    enum class Grade { WAIT, OBSERVED, UNAVAILABLE }

    /** The labeler's exact priced horizon, not the read/decision wall clock. */
    fun grade(decisionAt: Long, horizon: Long, sourceAt: Long, nowMs: Long, eligible: Boolean,
              graceMs: Long): Grade = when {
        horizon !in HORIZONS || decisionAt <= 0L -> Grade.UNAVAILABLE
        eligible && sourceAt - decisionAt in horizon..(horizon + graceMs) -> Grade.OBSERVED
        nowMs > decisionAt + horizon + graceMs -> Grade.UNAVAILABLE
        else -> Grade.WAIT
    }

    data class Stat(var n: Int = 0, var mean: Double = 0.0, var m2: Double = 0.0,
                    var wins: Int = 0) {
        fun add(net: Double) {
            if (n >= MAX_CELL_SAMPLES) {
                val keep = (MAX_CELL_SAMPLES - 1).toDouble() / MAX_CELL_SAMPLES
                n = MAX_CELL_SAMPLES - 1
                m2 *= keep
                wins = (wins * keep).toInt()
            }
            n++
            val delta = net - mean
            mean += delta / n
            m2 += delta * (net - mean)
            if (net > 0.0) wins++
        }
        fun se(): Double = if (n > 1) sqrt((m2 / (n - 1)).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
        fun lower(): Double = mean - 2.0 * se()
        fun upper(): Double = mean + 2.0 * se()
    }

    private fun normalized(s: String): String = s.trim().uppercase().replace('|', '_').take(42)
    private fun key(lane: String, stage: String, setup: String, band: String, horizon: Long): String =
        "${normalized(lane)}|${normalized(stage)}|${normalized(setup)}|${normalized(band)}|$horizon"

    class Book {
        // V5.0.8859 — access order: at the cell cap the cell given up is the one no read has reached for the longest, not
        // the first one ever created. 5.0.8853: cells=4096 (the cap), 13,321 graded reads held of 45,897 observed — the
        // book was discarding its oldest cells, which are its most established ones, and regrowing them from zero.
        private val rows = LinkedHashMap<String, Stat>(256, 0.75f, true)
        var observed: Long = 0; private set
        var unavailable: Long = 0; private set
        /** V5.0.8859 — cells given up at the cap, and the graded reads they held. */
        var evictedCells: Long = 0; private set
        var evictedReads: Long = 0; private set

        /**
         * [done] is checkpointed on the exact episode alongside this book. An observation crossing
         * multiple windows is counted once PER DISTINCT HORIZON, never multiple times at one horizon.
         * A missing read is missing evidence, not a fabricated -100% return.
         */
        @Synchronized fun observe(lane: String, stage: String, setup: String, band: String,
                                  done: MutableSet<Long>, decisionAt: Long, basisAt: Long, sourceAt: Long,
                                  nowMs: Long, eligible: Boolean, netPct: Double, followLong: Boolean,
                                  grace: (Long) -> Long): List<Long> {
            // V5.0.8855 — the horizons this call OBSERVED (TrajectoryVerdict8855 revises the decision's outcome on them)
            var seen: ArrayList<Long>? = null
            for (h in HORIZONS) {
                // Long-horizon outcomes are a qualified survivor cohort, never a universal
                // all-token prior (or the disappearances would create survivorship bias).
                if (h > FIFTEEN_MIN && !followLong) continue
                if (h in done) continue
                when (grade(decisionAt, h, sourceAt, nowMs,
                        eligible && basisAt > 0L && basisAt <= decisionAt + h && netPct.isFinite(), grace(h))) {
                    Grade.OBSERVED -> {
                        if (lane.isNotBlank() && stage.isNotBlank() && setup.isNotBlank() && band.isNotBlank()) {
                            val k = key(lane, stage, setup, band, h)
                            if (k !in rows && rows.size >= MAX_CELLS) {
                                val lru = rows.keys.first()
                                rows.remove(lru)?.let { evictedCells++; evictedReads += it.n }
                            }
                            rows.getOrPut(k) { Stat() }.add(netPct)
                            observed++
                        }
                        done.add(h)
                        (seen ?: ArrayList<Long>(1).also { seen = it }).add(h)
                    }
                    Grade.UNAVAILABLE -> { unavailable++; done.add(h) }
                    Grade.WAIT -> Unit
                }
            }
            return seen ?: emptyList()
        }

        /**
         * Both the formation (1/5 m) and continuation (15 m) are independently sampled.
         * A strongly losing stage takes a small ranking penalty; a truly sustained pattern
         * gets a small bonus. Neither changes proof tiers, sizing, or live admission.
         */
        @Synchronized fun adjustment(lane: String, stage: String, setup: String, band: String): Double {
            val five = rows[key(lane, stage, setup, band, FIVE_MIN)] ?: return 0.0
            val fifteen = rows[key(lane, stage, setup, band, FIFTEEN_MIN)] ?: return 0.0
            if (five.n < MIN_SAMPLES || fifteen.n < MIN_SAMPLES) return 0.0
            return when {
                five.upper() < 0.0 && fifteen.upper() < 0.0 -> -2.0
                five.lower() > 0.0 && fifteen.lower() > 0.0 -> 2.0
                five.lower() > 0.0 && fifteen.upper() < 0.0 -> -1.0 // ignition that exhausts
                else -> 0.0
            }
        }

        /** Read-only per-horizon status; useful when verifying delayed/partial learning on-device. */
        @Synchronized fun line(): String {
            val n = HORIZONS.map { h -> rows.entries.filter { it.key.endsWith("|$h") }.sumOf { it.value.n } }
            return "trajectory8851[graded1m=${n[0]} graded5m=${n[1]} graded15m=${n[2]} " +
                "qualified1h=${n[3]} qualified4h=${n[4]} qualified24h=${n[5]} " +
                "observed=$observed unavailable=$unavailable cells=${rows.size} evicted8859=$evictedCells/reads$evictedReads]"
        }

        @Synchronized fun encode(): String = JSONObject().put("observed", observed).put("unavailable", unavailable)
            .put("evictedCells", evictedCells).put("evictedReads", evictedReads)
            .put("rows", JSONObject().also { out -> rows.forEach { (k, s) ->
                out.put(k, JSONArray(listOf(s.n, s.mean, s.m2, s.wins)))
            } }).toString()

        @Synchronized fun restore(raw: String?) {
            if (raw.isNullOrBlank()) return
            val root = JSONObject(raw)
            val saved = root.optJSONObject("rows") ?: return
            val recovered = LinkedHashMap<String, Stat>()
            for (k in saved.keys()) {
                if (recovered.size >= MAX_CELLS) break
                val a = saved.optJSONArray(k) ?: continue
                val s = Stat(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optInt(3))
                if (s.n in 1..MAX_CELL_SAMPLES && s.mean.isFinite() && s.m2.isFinite() && s.m2 >= 0.0 && s.wins in 0..s.n &&
                    HORIZONS.any { k.endsWith("|$it") }) recovered[k] = s
            }
            rows.clear(); rows.putAll(recovered)
            observed = root.optLong("observed").coerceAtLeast(0L)
            unavailable = root.optLong("unavailable").coerceAtLeast(0L)
            evictedCells = root.optLong("evictedCells").coerceAtLeast(0L)
            evictedReads = root.optLong("evictedReads").coerceAtLeast(0L)
        }

        /** Pure test seam for a fully graded cohort; not a live admission or trade decision. */
        @Synchronized internal fun recordForTest(lane: String, stage: String, setup: String, band: String,
                                                   horizon: Long, netPct: Double) {
            if (horizon in HORIZONS && netPct.isFinite()) rows.getOrPut(key(lane, stage, setup, band, horizon)) { Stat() }.add(netPct)
        }
    }
}
