package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder

/**
 * V5.0.8754 §THE_BASE_FAMILY — owner: "ok so now we need to rebuild strategy on the new data yeah".
 *
 * The 8750 phone read settled the launch question honestly: on 882 launches seen live, no cap / buy-count entry pays (best
 * ex-top-3 -1.7 % ± 4.7, median -40 %, 50x never), and the bot's own stage records say the same — LAUNCH -15..-23 % at five
 * minutes in every meme lane — while the one stage that is positive in most of them is BASE (MOONSHOT n394 +2.1 % with 15 %
 * runners, MANIPULATED n316 +2.9 %, SHITCOIN n370 +1.4 %). A base is the consolidation after the first pump and dump.
 *
 * This family grades base entries on the same launch paths, with the same honesty as the launch replay (live prints only
 * trigger, spikes dropped, fills where the curve stood after the latency, cost at the live size, ranked by the mean without
 * each rule's three best launches):
 *  - the coin has made a peak of at least [Trigger.minPeakCapUsd];
 *  - at least [Trigger.baseWinMs] after that peak it trades at least [Trigger.pullback] under it;
 *  - over that window its prices stay inside [Trigger.maxRange] (high / low): it has stopped falling;
 *  - [Trigger.minBuys] live buys printed in the last minute: someone is still buying;
 *  - IN_BASE enters there, its stop 15 % under the base's low; BREAKOUT waits (up to [BREAKOUT_WINDOW_MS]) for a live print
 *    5 % over the base's high and enters on it, its stop 5 % under the base's low; a base that breaks down resets.
 * Exits: LaunchReplay8744.exitNet8754 — the same three ladders, out at [TIME_STOP_MS] if it never made +20 %.
 *
 * [State] is the trigger itself, fed one print at a time: the replay and the live sniper (LaunchSniper8748) run THIS code,
 * so what is graded is what fires. [proven8754] names the rule the sniper may trade under the launch replay's own bar (the
 * bot's DOCTRINE ladder, a latency the bot has, n >= 100, ex-top-3 mean clear of its SE).
 */
object BaseFamily8754 {
    enum class Mode { IN_BASE, BREAKOUT }
    data class Trigger(val minPeakCapUsd: Double, val pullback: Double, val baseWinMs: Long, val maxRange: Double, val minBuys: Int, val mode: Mode)
    data class Rule(val trigger: Trigger, val ladder: Ladder = Ladder.DOCTRINE, val delayMs: Long = 10_000L) {
        val id: String get() = with(trigger) {
            "BASE peak\$${(minPeakCapUsd / 1000).toInt()}k pull${(pullback * 100).toInt()}% base${baseWinMs / 1000}s range${maxRange}x " +
                "buys>=$minBuys $mode $ladder +${delayMs / 1000}s"
        }
    }
    data class Graded(val rule: Rule, val n: Int, val mean: Double, val median: Double, val exTop3: Double, val exTop3Se: Double,
                      val win: Double, val twoX: Double, val tenX: Double, val fiftyX: Double)

    private const val BREAKOUT_WINDOW_MS = 10L * 60_000L
    private const val TIME_STOP_MS = 20L * 60_000L
    private const val MIN_N = 50
    private const val PROVEN_MIN_N = 100
    private const val SUPPLY = 1.0e9

    val TRIGGERS: List<Trigger> = buildList {
        for (cap in listOf(10_000.0, 20_000.0, 40_000.0)) for (pb in listOf(0.4, 0.6)) for (w in listOf(120_000L, 300_000L))
            for (r in listOf(1.3, 1.6)) for (b in listOf(3, 10)) for (m in Mode.values()) add(Trigger(cap, pb, w, r, b, m))
    }
    val GRID: List<Rule> = TRIGGERS.flatMap { tr -> Ladder.values().flatMap { l -> listOf(2_000L, 10_000L, 30_000L).map { d -> Rule(tr, l, d) } } }

    /** One coin under one trigger, fed print by print (chain time [t] ms, price SOL/token, buy flag, received live). */
    class State(private val tr: Trigger) {
        private val buys = ArrayDeque<Long>()
        private val win = ArrayDeque<Long>()
        private val maxQ = ArrayDeque<Pair<Long, Double>>()   // monotonic: the window's high and low in O(1) per print
        private val minQ = ArrayDeque<Pair<Long, Double>>()
        private var peak = 0.0; private var peakT = 0L
        private var baseHi = Double.NaN; private var baseLo = Double.NaN; private var baseT = -1L
        var fired = false; private set

        /** The stop price when this print fires the trigger, else null. */
        fun onPrint(t: Long, p: Double, buy: Boolean, live: Boolean, solUsd: Double): Double? {
            if (fired || !(p > 0.0) || !p.isFinite()) return null
            if (live && buy) buys.addLast(t)
            while (buys.isNotEmpty() && buys.first() < t - 60_000L) buys.removeFirst()
            if (p > peak) { peak = p; peakT = t }
            win.addLast(t)
            while (maxQ.isNotEmpty() && maxQ.last().second <= p) maxQ.removeLast()
            maxQ.addLast(t to p)
            while (minQ.isNotEmpty() && minQ.last().second >= p) minQ.removeLast()
            minQ.addLast(t to p)
            val from = t - tr.baseWinMs
            while (win.isNotEmpty() && win.first() < from) win.removeFirst()
            while (maxQ.isNotEmpty() && maxQ.first().first < from) maxQ.removeFirst()
            while (minQ.isNotEmpty() && minQ.first().first < from) minQ.removeFirst()
            if (tr.mode == Mode.BREAKOUT && baseT >= 0L) {
                when {
                    t - baseT > BREAKOUT_WINDOW_MS || p < baseLo * 0.85 -> baseT = -1L   // no break, or it broke down: look again
                    live && p > baseHi * 1.05 -> { fired = true; return baseLo * 0.95 }
                    else -> return null
                }
            }
            if (!live || peak * SUPPLY * solUsd < tr.minPeakCapUsd || t - peakT < tr.baseWinMs) return null
            if (p > peak * (1.0 - tr.pullback) || win.size < 3) return null
            val hi = maxQ.first().second; val lo = minQ.first().second
            if (!(lo > 0.0) || hi / lo > tr.maxRange || buys.size < tr.minBuys) return null
            if (tr.mode == Mode.IN_BASE) { fired = true; return lo * 0.85 }
            baseHi = hi; baseLo = lo; baseT = t
            return null
        }
    }

    /** Pure: n, mean, median, the mean and SE without the three best, and the win / 2x / 10x / 50x shares of [v]. */
    internal fun grade8754(rule: Rule, v: List<Double>): Graded? {
        if (v.size < 2) return null
        val s = v.sorted()
        val ex = s.dropLast(3)
        val m3 = if (ex.size >= 2) ex.average() else Double.NaN
        val se3 = if (ex.size >= 2) kotlin.math.sqrt(ex.sumOf { (it - m3) * (it - m3) } / (ex.size - 1)) / kotlin.math.sqrt(ex.size.toDouble()) else Double.NaN
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        fun share(f: (Double) -> Boolean) = s.count(f).toDouble() / s.size
        return Graded(rule, s.size, s.average(), med, m3, se3, share { it > 0.0 }, share { it >= 100.0 }, share { it >= 900.0 }, share { it >= 4_900.0 })
    }

    /** Pure: the net % of every [GRID] rule on [x], keyed by rule (rules whose trigger never fired are absent). */
    internal fun simulate8754(x: LaunchReplay8744.Path8748, solUsd: Double, costPct: Double): Map<Rule, Double> {
        val out = HashMap<Rule, Double>()
        for (tr in TRIGGERS) {
            val st = State(tr)
            var fireAt = -1; var stop = Double.NaN
            for (i in x.t.indices) {
                val sp = st.onPrint(x.t[i], x.p[i], x.buy[i], x.live[i], solUsd)
                if (sp != null) { fireAt = i; stop = sp; break }
            }
            if (fireAt < 0) continue
            for (r in GRID) if (r.trigger == tr) {
                val e = LaunchReplay8744.entryIndex8754(x, fireAt, r.delayMs)
                LaunchReplay8744.exitNet8754(x, e, r.ladder, 2.0, stop, TIME_STOP_MS, costPct)?.let { out[r] = it }
            }
        }
        return out
    }

    /** V5.0.8755 — StrategyLab8755: one base rule (any parameters) on one path, or null when its trigger never fired. */
    internal fun simulateRule8755(x: LaunchReplay8744.Path8748, rule: Rule, solUsd: Double, costPct: Double): Double? {
        val st = State(rule.trigger)
        for (i in x.t.indices) {
            val sp = st.onPrint(x.t[i], x.p[i], x.buy[i], x.live[i], solUsd) ?: continue
            return LaunchReplay8744.exitNet8754(x, LaunchReplay8744.entryIndex8754(x, i, rule.delayMs), rule.ladder, 2.0, sp, TIME_STOP_MS, costPct)
        }
        return null
    }

    /** Pure: the rule the sniper may trade, or null. */
    internal fun provenOf8754(results: List<Graded>, minDelayMs: Long): Graded? = results.filter {
        it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= maxOf(10_000L, minDelayMs) && it.n >= PROVEN_MIN_N &&
            it.exTop3.isFinite() && it.exTop3Se.isFinite() && it.exTop3 - it.exTop3Se > 0.0
    }.maxByOrNull { it.exTop3 - it.exTop3Se }

    @Volatile private var last: List<Graded> = emptyList()
    @Volatile private var proven: Graded? = null
    @Volatile private var shadow: Graded? = null
    @Volatile private var status = "not-run"

    fun proven8754(): Graded? = proven
    fun shadow8754(): Graded? = shadow

    /** LaunchReplay8744's background run: grade the family on the same launch paths. */
    internal fun evaluate8754(set: List<LaunchReplay8744.Path8748>, solUsd: Double, costPct: Double, needDelayMs: Long) {
        val values = HashMap<Rule, ArrayList<Double>>()
        for (x in set) for ((r, v) in simulate8754(x, solUsd, costPct)) values.getOrPut(r) { ArrayList() }.add(v)
        val res = values.mapNotNull { (r, v) -> grade8754(r, v) }
            .sortedWith(compareByDescending<Graded> { it.n >= MIN_N }.thenByDescending { (it.exTop3 - it.exTop3Se).takeIf { d -> d.isFinite() } ?: -1e18 })
        last = res
        proven = provenOf8754(res, needDelayMs)
        shadow = res.firstOrNull { it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= maxOf(10_000L, needDelayMs) && it.n >= MIN_N }
        status = "ran launches=${set.size} rulesWithEntries=${res.size}"
    }

    private fun line(g: Graded): String =
        "${g.rule.id} n${g.n} med${"%+.0f".format(g.median)}% mean${"%+.0f".format(g.mean)}% exTop3${"%+.1f".format(g.exTop3)}±${"%.1f".format(g.exTop3Se)}% " +
            "win${(g.win * 100).toInt()}% 2x+${(g.twoX * 100).toInt()}% 10x+${"%.1f".format(g.tenX * 100)}% 50x+${"%.1f".format(g.fiftyX * 100)}%"

    fun statusLine8754(): String = "baseFamily8754[$status rules=${GRID.size} proven{${proven?.let { line(it) } ?: "none"}} " +
        "shadow{${shadow?.let { line(it) } ?: "none"}} best{${last.take(5).joinToString(" · ") { line(it) }.ifBlank { "-" }}} " +
        "rule=the sniper runs this trigger code live; proven = DOCTRINE, the bot's latency, n>=100, ex-top-3 mean - SE > 0]"
}
