package com.lifecyclebot.engine.truth

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8744 §THE_LAUNCH_REPLAY — owner, 14 Nov: "we need the bot to see these developing at 10k. even if it gets out at
 * 40k 50k that's still 5x. but the genuine runners are still caught and held" / "build both now".
 *
 * Every pump.fun trade the market-wide feed decodes is stored with the coin's birth (LaunchHour8628, 48 h). This replays
 * those real first hours against a grid of entry triggers and exit rules, with no look-ahead, before any live rule exists:
 *  - entry: the first LIVE print at which the curve market cap (price x 1e9 supply x SOL/USD) crosses [Rule.capUsd] and at
 *    least [Rule.minBuys] live buys printed in the preceding 60 s; filled at the curve's state [Rule.delayMs] later (the
 *    bot's decision and send latency);
 *  - exit: the [Ladder]'s rungs, a hard stop [Rule.stopPct] % under entry until the first rung, the ladder's trail after it,
 *    out at [TIME_STOP_MS] if it never reached +20 %; anything still held at the hour's last print is sold there. Each sale
 *    fills at the curve's state [SELL_DELAY_MS_8748] after the print that triggered it.
 *  - cost: the round trip at the bot's live size on a fresh curve (FieldManual7715.roundTripCostPct7766).
 *
 * V5.0.8748 §THE_REPLAY_TELLS_THE_TRUTH — 8747's first phone read printed "+7,243,209 % ± 7,111,582 %, win 34 %, 2x+ 20 %,
 * 10x+ 8 %" for $10k / 15 buys. Three faults made that number, and each is closed here:
 *  1. one bad print. A single print thousands of times off its neighbours became a "10x rung" and the whole mean. An
 *     isolated print more than [SPIKE_X_8748] x away from the prints on BOTH sides of it is dropped ([spikes8748]).
 *  2. hindsight. A pool-trades page read because a coin had ALREADY run writes that coin's early prints with their chain
 *     times; the replay then "entered" at $10k on a coin chosen for having reached $60k. Only a print this process
 *     received within [LIVE_LAG_MS_8748] of its chain time can trigger an entry or count as a trigger's buy.
 *  3. perfect fills. Rungs, stops and trails filled at the very print that crossed them; a sale now fills where the curve
 *     stood after the send latency. The entry latency is a grid axis (2 s / 10 s / 30 s): what the delay costs is measured.
 * The report ranks rules by the mean WITHOUT each rule's three best launches (a rule carried by one or two coins is not a
 * strategy) and prints the median, the 2x / 10x / 50x shares beside it. [proven8748] names the rule LaunchSniper8748 may
 * trade: the bot's own exit ladder (DOCTRINE), a latency the bot can meet (>= [ARM_MIN_DELAY_MS_8748]), n >= [PROVEN_MIN_N_8748]
 * and the ex-top-3 mean clear of its own standard error.
 */
object LaunchReplay8744 {
    /**
     * V5.0.8747 — how the position is unwound (owner: "the bot should be able to hit 50x trades"):
     *  HALF_TRAIL  half at [Rule.takeX], the rest trailed 30 % from its peak;
     *  DOCTRINE    the bot's runner ladder today: 25 % at 2x, 18 % at 5x, 67 % of the rest at 10x, the rest trailed 45 %;
     *  MOONBAG     half at [Rule.takeX], 30 % at 10x, the last 20 % trailed 60 % from its peak (a 50x keeps a real bag).
     */
    enum class Ladder { HALF_TRAIL, DOCTRINE, MOONBAG }
    data class Rule(val capUsd: Double, val minBuys: Int, val takeX: Double, val stopPct: Double, val ladder: Ladder = Ladder.HALF_TRAIL,
                    val delayMs: Long = 2_000L) {
        val id: String get() = "cap\$${(capUsd / 1000).toInt()}k buys>=$minBuys tp${takeX.toInt()}x stop${stopPct.toInt()}% $ladder +${delayMs / 1000}s"
    }
    data class Result(val rule: Rule, val n: Int, val mean: Double, val se: Double, val win: Double, val twoX: Double, val tenX: Double = 0.0,
                      val median: Double = Double.NaN, val exTop3: Double = Double.NaN, val exTop3Se: Double = Double.NaN,
                      val fiftyX: Double = 0.0)

    /** A print received more than this after its chain time is a backfill: it never triggers an entry. */
    const val LIVE_LAG_MS_8748 = 15_000L
    /** The rule LaunchSniper8748 trades must hold at a latency the bot can meet (8 s loop + quote + send). */
    private const val ARM_MIN_DELAY_MS_8748 = 10_000L
    private const val PROVEN_MIN_N_8748 = 100
    private const val SELL_DELAY_MS_8748 = 1_500L
    private const val SPIKE_X_8748 = 3.0
    /** The live entry size the cost is charged at (SOL) and a fresh curve's depth (virtual 30 SOL + a few real). */
    private const val SIZE_SOL_8748 = 0.04
    private const val CURVE_SOL_8748 = 35.0
    private const val TRAIL_PCT = 30.0
    private const val TIME_STOP_MS = 10L * 60_000L
    private const val MIN_N = 50
    private const val RUN_EVERY_MS = 30L * 60_000L
    private const val MAX_LAUNCHES = 6_000
    private const val SUPPLY = 1.0e9

    val GRID: List<Rule> = buildList {
        for (cap in listOf(5_000.0, 10_000.0, 15_000.0)) for (b in listOf(3, 8, 15))
            for (tx in listOf(2.0, 3.0, 5.0)) for (st in listOf(20.0, 35.0)) for (l in Ladder.values())
                for (d in listOf(2_000L, 10_000L, 30_000L)) add(Rule(cap, b, tx, st, l, d))
    }

    /** Pure: the isolated spikes of a print series (true = drop): more than [SPIKE_X_8748] x off the prints on both sides. */
    fun spikes8748(p: DoubleArray): BooleanArray {
        val drop = BooleanArray(p.size)
        fun med(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }
        for (i in p.indices) {
            if (!(p[i] > 0.0) || !p[i].isFinite()) { drop[i] = true; continue }
            val before = (maxOf(0, i - 3) until i).map { p[it] }.filter { it > 0.0 && it.isFinite() }
            val after = (i + 1..minOf(p.size - 1, i + 3)).map { p[it] }.filter { it > 0.0 && it.isFinite() }
            if (before.isEmpty() || after.isEmpty()) continue
            val b = med(before); val a = med(after)
            drop[i] = (p[i] > SPIKE_X_8748 * b && p[i] > SPIKE_X_8748 * a) || (p[i] * SPIKE_X_8748 < b && p[i] * SPIKE_X_8748 < a)
        }
        return drop
    }

    /** One launch's first hour with its spikes removed. */
    internal class Path8748(val t: LongArray, val p: DoubleArray, val buy: BooleanArray, val live: BooleanArray, val spikes: Int)

    internal fun clean8748(t: LongArray, p: DoubleArray, buy: BooleanArray, live: BooleanArray?): Path8748 {
        val drop = spikes8748(p)
        val keep = p.indices.filter { !drop[it] }
        return Path8748(LongArray(keep.size) { t[keep[it]] }, DoubleArray(keep.size) { p[keep[it]] },
            BooleanArray(keep.size) { buy[keep[it]] }, BooleanArray(keep.size) { live?.get(keep[it]) ?: true }, p.size - keep.size)
    }

    /** Pure: the first live print where the cap is reached with [minBuys] live buys in the 60 s before it; -1 when none. */
    private fun trigger8748(x: Path8748, capUsd: Double, minBuys: Int, solUsd: Double): Int {
        var j = 0; var buys = 0
        for (i in x.t.indices) {
            if (x.live[i] && x.buy[i]) buys++
            while (x.t[j] < x.t[i] - 60_000L) { if (x.live[j] && x.buy[j]) buys--; j++ }
            if (x.live[i] && x.p[i] * SUPPLY * solUsd >= capUsd && buys >= minBuys) return i
        }
        return -1
    }

    /** The curve's state at [atMs]: the last print at or before it, from index [from]. */
    private fun stateAt(t: LongArray, from: Int, atMs: Long): Int { var m = from; while (m + 1 < t.size && t[m + 1] <= atMs) m++; return m }

    /** Pure: the net % of [rule] on [x] entered at trigger [trig]. */
    private fun run8748(x: Path8748, trig: Int, rule: Rule, costPct: Double): Double? {
        val t = x.t; val p = x.p
        val e = stateAt(t, trig, t[trig] + rule.delayMs)
        val entry = p[e]
        if (!(entry > 0.0)) return null
        fun fill(k: Int) = p[stateAt(t, k, t[k] + SELL_DELAY_MS_8748)] / entry - 1.0
        var held = 1.0; var banked = 0.0; var peak = entry; var tookFirst = false
        val rungs = when (rule.ladder) {   // (multiple, fraction of the ORIGINAL position)
            Ladder.HALF_TRAIL -> listOf(rule.takeX to 0.5)
            Ladder.DOCTRINE -> listOf(2.0 to 0.25, 5.0 to 0.18, 10.0 to 0.57 * 0.67)
            Ladder.MOONBAG -> listOf(rule.takeX to 0.5, 10.0 to 0.3)
        }
        val trailPct = when (rule.ladder) { Ladder.HALF_TRAIL -> TRAIL_PCT; Ladder.DOCTRINE -> 45.0; Ladder.MOONBAG -> 60.0 }
        var nextRung = 0
        for (k in e + 1 until t.size) {
            val px = p[k]
            if (px > peak) peak = px
            while (nextRung < rungs.size && px >= entry * rungs[nextRung].first * (1.0 - 1e-9)) {
                val sell = minOf(rungs[nextRung].second, held); banked += sell * fill(k); held -= sell; nextRung++; tookFirst = true
            }
            val stopHit = !tookFirst && px <= entry * (1.0 - rule.stopPct / 100.0)
            val trailHit = tookFirst && px <= peak * (1.0 - trailPct / 100.0)
            val timeHit = !tookFirst && t[k] - t[e] >= TIME_STOP_MS && peak < entry * 1.2
            if (stopHit || trailHit || timeHit || held <= 1e-9) { if (held > 1e-9) banked += held * fill(k); held = 0.0; break }
        }
        if (held > 0.0) banked += held * (p.last() / entry - 1.0)
        return banked * 100.0 - (if (costPct.isFinite()) costPct else 0.0)
    }

    /**
     * Pure: the net % of [rule] on one launch's prints, or null when it never triggered. [live] marks the prints received
     * live (null = all); spikes are removed first.
     */
    fun simulate8744(t: LongArray, p: DoubleArray, buy: BooleanArray, rule: Rule, solUsd: Double, costPct: Double,
                     live: BooleanArray? = null): Double? {
        if (t.isEmpty() || !(solUsd > 0.0)) return null
        val x = clean8748(t, p, buy, live)
        if (x.t.isEmpty()) return null
        val trig = trigger8748(x, rule.capUsd, rule.minBuys, solUsd)
        return if (trig < 0) null else run8748(x, trig, rule, costPct)
    }

    /** Pure: n, mean, SE, median, the mean and SE without the three best, and the win / 2x / 10x / 50x shares. */
    internal fun stats8748(rule: Rule, v: List<Double>): Result? {
        if (v.size < 2) return null
        val s = v.sorted()
        fun meanSe(x: List<Double>): Pair<Double, Double> {
            if (x.size < 2) return Double.NaN to Double.NaN
            val m = x.average(); val sd = kotlin.math.sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
            return m to sd / kotlin.math.sqrt(x.size.toDouble())
        }
        val (m, se) = meanSe(s)
        val (m3, se3) = meanSe(s.dropLast(3))
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        fun share(f: (Double) -> Boolean) = s.count(f).toDouble() / s.size
        return Result(rule, s.size, m, se, share { it > 0.0 }, share { it >= 100.0 }, share { it >= 900.0 }, med, m3, se3, share { it >= 4_900.0 })
    }

    /** Each rule's result over the launches, best first: n >= MIN_N, then the ex-top-3 mean less its SE. */
    private fun evaluate8748(launches: List<Path8748>, solUsd: Double, costPct: Double): List<Result> {
        val values = HashMap<Rule, ArrayList<Double>>()
        val triggers = GRID.map { it.capUsd to it.minBuys }.distinct()
        for (x in launches) {
            val trig = triggers.associateWith { (c, b) -> trigger8748(x, c, b, solUsd) }
            for (r in GRID) {
                val i = trig[r.capUsd to r.minBuys] ?: -1
                if (i >= 0) run8748(x, i, r, costPct)?.let { values.getOrPut(r) { ArrayList() }.add(it) }
            }
        }
        return values.mapNotNull { (r, v) -> stats8748(r, v) }
            .sortedWith(compareByDescending<Result> { it.n >= MIN_N }.thenByDescending { (it.exTop3 - it.exTop3Se).takeIf { d -> d.isFinite() } ?: -1e18 })
    }

    /** Pure: the rule the live sniper may trade from [results], or null (see the class note). */
    internal fun provenOf8748(results: List<Result>): Result? = results.filter {
        it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= ARM_MIN_DELAY_MS_8748 && it.n >= PROVEN_MIN_N_8748 &&
            it.exTop3.isFinite() && it.exTop3Se.isFinite() && it.exTop3 - it.exTop3Se > 0.0
    }.maxByOrNull { it.exTop3 - it.exTop3Se }

    /** Pure: the rule the sniper watches in shadow while none is proven: the best one it could trade, by the same order. */
    internal fun shadowOf8748(results: List<Result>): Result? = results.firstOrNull {
        it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= ARM_MIN_DELAY_MS_8748 && it.n >= MIN_N
    }

    @Volatile private var last: List<Result> = emptyList()
    @Volatile private var proven: Result? = null
    @Volatile private var shadow: Result? = null
    @Volatile private var lastRunMs = 0L
    @Volatile private var status = "not-run"
    private val running = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "launch-replay-8744").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** LaunchSniper8748: the proven rule (trade it) and the shadow rule (watch it), from the last run. */
    fun proven8748(): Result? = proven
    fun shadow8748(): Result? = shadow

    /** Re-run in the background when due; never blocks the caller. */
    private fun maybeRun8744(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastRunMs < RUN_EVERY_MS || !running.compareAndSet(false, true)) return
        lastRunMs = nowMs
        exec.execute {
            try {
                val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                if (!(solUsd > 0.0)) { status = "no SOL price"; return@execute }
                val set = ArrayList<Path8748>()
                var spikes = 0L; var liveN = 0
                LaunchHour8628.forEachLaunch8744(nowMs, MAX_LAUNCHES, LIVE_LAG_MS_8748) { _, t, p, b, l ->
                    val x = clean8748(t, p, b, l); spikes += x.spikes
                    if (x.live.any { it }) liveN++
                    set.add(x)
                }
                val cost = FieldManual7715.roundTripCostPct7766(SIZE_SOL_8748, SIZE_SOL_8748 * solUsd, 2.0 * CURVE_SOL_8748 * solUsd)
                val res = evaluate8748(set, solUsd, cost)
                last = res; proven = provenOf8748(res); shadow = shadowOf8748(res)
                status = "ran launches=${set.size} live=$liveN spikesDropped=$spikes solUsd=${"%.0f".format(solUsd)} cost=${"%.1f".format(cost)}%"
            } catch (t: Throwable) { status = "failed:${t.javaClass.simpleName}" } finally { running.set(false) }
        }
    }

    private fun line(r: Result): String =
        "${r.rule.id} n${r.n} med${"%+.0f".format(r.median)}% mean${"%+.0f".format(r.mean)}% exTop3${"%+.1f".format(r.exTop3)}±${"%.1f".format(r.exTop3Se)}% " +
            "win${(r.win * 100).toInt()}% 2x+${(r.twoX * 100).toInt()}% 10x+${"%.1f".format(r.tenX * 100)}% 50x+${"%.1f".format(r.fiftyX * 100)}%"

    fun statusLine8744(): String {
        maybeRun8744()
        val top = last.take(5).joinToString(" · ") { line(it) }.ifBlank { "-" }
        return "launchReplay8744[$status rules=${GRID.size} proven{${proven?.let { line(it) } ?: "none"}} " +
            "shadow{${shadow?.let { line(it) } ?: "none"}} best{$top} rule=live prints only, spikes dropped, fills at the curve after " +
            "the latency, ranked by the mean without each rule's 3 best launches; proven = DOCTRINE ladder, >=${ARM_MIN_DELAY_MS_8748 / 1000}s, " +
            "n>=$PROVEN_MIN_N_8748, ex-top-3 mean - SE > 0]"
    }
}
