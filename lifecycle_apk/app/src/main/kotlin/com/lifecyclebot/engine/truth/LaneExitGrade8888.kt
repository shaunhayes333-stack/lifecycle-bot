package com.lifecyclebot.engine.truth

/**
 * V5.0.8888 §EACH_LANE_GRADES_ITS_OWN_GIVE_BACK (plan W5: "lane exits keep a runner's remainder, with learned give-back per
 * lane graded hourly") — owner: "fuck 4x. I want it to look far far beyond that". On 20 Nov the doctrine sold DQKmfa
 * (SHITCOIN) at +102 %, +402 % and +1,114 % and kept the rest riding on its 45 % give-back line: whether 45 % is the give-back
 * that keeps the most of a lane's runners is a question the device's own launches can answer, lane by lane.
 *
 * Each launch replay pass (LaunchReplay8744, every 30 min) now also takes every doctrine lane's FIRST decision on every stored
 * launch (LaunchHour8628.firstDecisions8888: admitted or refused, the round trip it was priced at) and runs, on that launch's
 * own cleaned prints from the decision on, the live doctrine exactly as the evidence shadow runs it (EvidenceShadow8344
 * doctrineWhy8878): entry at the decision's print plus the lane's measured decision-to-fill chase (ShadowFill8364); the
 * RunnerGrab7967.TIERS_7967 rungs on the gross (MemePaperTrader8544.rung8544); the level RunnerDoctrine8584.paperStopLevel8584
 * (the lane stop, then the doctrine line from a +10 % peak), sold on a second print at or under it
 * (MemePaperTrader8544.paperStopConfirmed8683); the lane clock only where the doctrine lets a time exit pass
 * (RunnerDoctrine8584.holds8584); each sale filled where the curve stood the live sell latency (ShadowAlign8366) after the
 * print that fired it, less the shadow's exit slip (ShadowFill8364.exitGrossPct8364); whatever is still held marked at the
 * hour's last print; less the decision's own round trip.
 *
 * The same decision is run again with the line keeping 70 % and 40 % of a 2x-plus peak (a 30 % / 60 % give-back) in place of
 * the doctrine's 55 % ([KEEPS_8888]); nothing else differs. Per lane, each variant's paired difference against the doctrine:
 * BETTER when it clears the family-deflated margin (CryptoTrend8886.familyZ8886 over every lane x variant graded, n >=
 * LaunchReplay8744.PROVEN_MIN_N_8748), WORSE when it is under it by that margin, otherwise SAME.
 *
 * Measurement and publication: the live doctrine is unchanged ([provenKeep8888] names a lane's proven share for the brief).
 * Not covered: the hour's end (a runner still held is marked there, as every launch-replay grade is); lanes the doctrine does
 * not own.
 */
object LaneExitGrade8888 {
    /** The kept shares graded: the doctrine's own and one on each side of it. */
    internal val KEEPS_8888 = doubleArrayOf(0.70, com.lifecyclebot.engine.RunnerDoctrine8584.KEEP_8584, 0.40)

    enum class Verdict8888 { BETTER, WORSE, SAME }

    /** One lane's grade: n decisions (a admitted), the doctrine's net and each other share's paired difference. */
    data class Lane8888(val lane: String, val n: Int, val admitted: Int, val doctrineMean: Double, val doctrineMedian: Double,
                        val doctrineExTop3: Double, val diffs: List<Diff8888>)
    data class Diff8888(val keep: Double, val n: Int, val differing: Int, val meanDiff: Double, val se: Double, val verdict: Verdict8888)

    // ── pure ──

    private fun stateAt(t: LongArray, from: Int, atMs: Long): Int { var m = from; while (m + 1 < t.size && t[m + 1] <= atMs) m++; return m }

    /**
     * Pure: the net % of one decision on [t]/[p] (ms from birth, price) entered at print index [e] at [entryPx], under the
     * doctrine with the line keeping [keep] of a 2x-plus peak. [stopPct] the lane stop, [timeoutMs] the lane clock, [costPct]
     * the round trip. Null when the entry price is unusable.
     */
    internal fun doctrineNet8888(t: LongArray, p: DoubleArray, e: Int, entryPx: Double, lane: String, stopPct: Double,
                                 timeoutMs: Long, costPct: Double, keep: Double, sellDelayMs: Long): Double? {
        if (!(entryPx > 0.0) || e !in t.indices) return null
        fun gross(k: Int) = (p[k] / entryPx - 1.0) * 100.0
        // a sale fills where the curve stood the live sell latency after the print that fired it, less the shadow's exit slip
        fun fill(k: Int) = ShadowFill8364.exitGrossPct8364(gross(stateAt(t, k, t[k] + sellDelayMs)))
        var held = 1.0
        var banked = 0.0
        var peak = 0.0
        var tiers = 0
        var breachAt = -1L
        for (k in e + 1 until t.size) {
            val g = gross(k)
            if (g > peak) peak = g
            val level = com.lifecyclebot.engine.RunnerDoctrine8584.paperStopLevel8584(stopPct, peak, keep)
            val breached = level.isFinite() && g <= level
            if (!breached) breachAt = -1L else if (breachAt < 0L) breachAt = t[k]
            if (breached && MemePaperTrader8544.paperStopConfirmed8683(breachAt, t[k], g)) { banked += held * fill(k); held = 0.0; break }
            if (!breached) {
                val rung = MemePaperTrader8544.rung8544(com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967, tiers, g)
                if (rung != null) {
                    // the shadow banks a rung and waits for the next mark (WAIT): the clock is not read on that print
                    val sell = held * rung.second
                    banked += sell * fill(k); held -= sell; tiers = rung.first
                    if (held <= 1e-9) break
                    continue
                }
            }
            if (t[k] - t[e] >= timeoutMs &&
                !com.lifecyclebot.engine.RunnerDoctrine8584.holds8584(lane, "CLOCK_TIME", g, peak, stopPct)) {
                banked += held * fill(k); held = 0.0; break
            }
            if (held <= 1e-9) break
        }
        if (held > 1e-9) banked += held * ShadowFill8364.exitGrossPct8364(gross(t.size - 1))
        return banked - (if (costPct.isFinite()) costPct else 0.0)
    }

    /** Pure: (mean, sample SE) of [x]; NaN SE under two values. */
    private fun meanSe(x: List<Double>): Pair<Double, Double> {
        if (x.isEmpty()) return Double.NaN to Double.NaN
        val m = x.average()
        if (x.size < 2) return m to Double.NaN
        val sd = kotlin.math.sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
        return m to sd / kotlin.math.sqrt(x.size.toDouble())
    }

    /** One graded decision: its lane, admitted flag and the net under each of [KEEPS_8888] (same order). */
    internal class Graded8888(val lane: String, val admitted: Boolean, val nets: DoubleArray)

    /** Pure: the per-lane grades of [rows], deflated across every lane x non-doctrine share holding [minN]+ decisions. */
    internal fun grade8888(rows: List<Graded8888>, minN: Int = LaunchReplay8744.PROVEN_MIN_N_8748): List<Lane8888> {
        // (floating-point arrays have no indexOf in this Kotlin: the doctrine's share is found by exact comparison)
        val doctrineIdx = KEEPS_8888.indexOfFirst { it == com.lifecyclebot.engine.RunnerDoctrine8584.KEEP_8584 }
        val byLane = rows.groupBy { it.lane }
        val k = byLane.values.count { it.size >= minN } * (KEEPS_8888.size - 1)
        val z = com.lifecyclebot.perps.CryptoTrend8886.familyZ8886(k)
        return byLane.map { (lane, rs) ->
            val doc = rs.map { it.nets[doctrineIdx] }
            val sorted = doc.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            val exTop3 = if (sorted.size > 3) sorted.dropLast(3).average() else Double.NaN
            val diffs = KEEPS_8888.indices.filter { it != doctrineIdx }.map { i ->
                val d = rs.map { it.nets[i] - it.nets[doctrineIdx] }
                val (m, se) = meanSe(d)
                val verdict = when {
                    rs.size < minN || !se.isFinite() -> Verdict8888.SAME
                    m - z * se > 0.0 -> Verdict8888.BETTER
                    m + z * se < 0.0 -> Verdict8888.WORSE
                    else -> Verdict8888.SAME
                }
                Diff8888(KEEPS_8888[i], rs.size, d.count { kotlin.math.abs(it) > 1e-9 }, m, se, verdict)
            }
            Lane8888(lane, rs.size, rs.count { it.admitted }, doc.average(), median, exTop3, diffs)
        }.sortedByDescending { it.n }
    }

    // ── state ──

    @Volatile private var last: List<Lane8888> = emptyList()
    @Volatile private var status = "not run"
    @Volatile private var lastRunMs = 0L

    /**
     * LaunchReplay8744's pass: [paths] the cleaned launches and [mints] their mints (same order), [decisions] each lane's
     * first decision per mint. Grades every doctrine-lane decision that falls inside its launch's stored hour.
     */
    internal fun evaluate8888(paths: List<LaunchReplay8744.Path8748>, mints: List<String>, decisions: Map<String, List<LaunchHour8628.Decision8888>>,
                              nowMs: Long) {
        val rows = ArrayList<Graded8888>()
        var outside = 0
        val sellMs = try { ShadowAlign8366.sellLatencyMs8366(false) } catch (_: Throwable) { 16_000L }
        for (i in paths.indices) {
            val x = paths[i]
            val decs = decisions[mints.getOrNull(i) ?: continue] ?: continue
            if (x.t.isEmpty() || x.bornMs <= 0L) continue
            for (d in decs) {
                if (!(try { com.lifecyclebot.engine.RunnerDoctrine8584.rides8584(d.lane) } catch (_: Throwable) { false })) continue
                val offset = d.atMs - x.bornMs
                if (offset < x.t.first() || offset >= x.t.last()) { outside++; continue }
                val e = stateAt(x.t, 0, offset)
                val entryPx = try { ShadowFill8364.laneEntryPx8364(x.p[e], d.lane) } catch (_: Throwable) { x.p[e] }
                val cost = if (d.costPct.isFinite() && d.costPct >= 0.0) d.costPct else continue
                val stop = (try { FieldManual7715.stopPct8392(d.lane, cost) } catch (_: Throwable) { 10.0 }).coerceIn(3.0, 40.0)
                val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(d.lane) } catch (_: Throwable) { false }
                val timeout = try { com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.horizonMsFor8468(d.lane, runner) } catch (_: Throwable) { continue }
                val nets = DoubleArray(KEEPS_8888.size)
                var ok = true
                for (j in KEEPS_8888.indices) {
                    val n = doctrineNet8888(x.t, x.p, e, entryPx, d.lane, stop, timeout, cost, KEEPS_8888[j], sellMs)
                    if (n == null) { ok = false; break }
                    nets[j] = n
                }
                if (ok) rows += Graded8888(CanonicalLaneIdentity6506.desk8392(d.lane), d.admitted, nets)
            }
        }
        last = grade8888(rows)
        lastRunMs = nowMs
        status = "ran decisions=${rows.size} outsideStoredHour=$outside"
    }

    /** The kept share a [lane]'s launches proved better than the doctrine's (the best by its lower bound); null otherwise. */
    fun provenKeep8888(lane: String): Double? {
        val l = last.firstOrNull { it.lane.equals(lane.trim(), ignoreCase = true) } ?: return null
        return l.diffs.filter { it.verdict == Verdict8888.BETTER }.maxByOrNull { it.meanDiff - it.se }?.keep
    }

    /** Brief: per lane, the doctrine's net on its decisions and each other share's paired difference. */
    fun statusLine8888(): String {
        val lanes = last.joinToString(" ") { l ->
            val d = l.diffs.joinToString(",") { "gb${((1.0 - it.keep) * 100).toInt()}=${"%+.1f".format(it.meanDiff)}±${"%.1f".format(it.se)}/${it.verdict}(diff${it.differing})" }
            "${l.lane}{n${l.n} a${l.admitted} doctrine=${"%+.1f".format(l.doctrineMean)}% ex3=${"%+.1f".format(l.doctrineExTop3)} med=${"%+.1f".format(l.doctrineMedian)} $d}"
        }.ifBlank { "-" }
        val ago = if (lastRunMs > 0L) (System.currentTimeMillis() - lastRunMs) / 60_000L else -1L
        return "laneExits8888[$status agoMin=$ago $lanes rule=each doctrine lane's first decision on each stored launch run on its own prints by the live doctrine " +
            "(shadow exits and fills, hour-end mark, own round trip); gb30 / gb60 = the line keeping 70 % / 40 % of a 2x+ peak, paired against gb45; " +
            "BETTER / WORSE past the margin deflated across lanes x shares, n>=${LaunchReplay8744.PROVEN_MIN_N_8748}; live unchanged]"
    }

    internal fun setForTest8888(l: List<Lane8888>) { last = l }
}
