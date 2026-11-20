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
 *
 * V5.0.8824 §THE_TEN_K_CROSSERS_THAT_KEEP_GOING — owner: "it has to focus in on the coins moving from 10k up in their market
 * cap looking at grabbing 100-500% profits and getting out". The owner's launch export (2,354 clean launches, 645 of them
 * crossed $10k) says which crossers: bought blind at the cross (next 10 s bar's high, 3.5 % cost) the best exit averaged
 * -1.4 %; only 16 % of crossers went +100 % after entry. The crossers that had printed at least 44 buys since birth
 * (n163) averaged +13.5 % under the bot's own DOCTRINE ladder with a 35 % stop, positive in both halves of the period
 * (+18.2 / +8.9), +16.2 % for the best fixed take, positive in every quarter. The 60-second window the grid had (at most
 * 15 buys) cannot see that: a coin with 44 buys over its life and 4 in the last minute is the one that keeps going. So a
 * rule carries [Rule.totalBuys], the live buys printed since birth before the cross, and the grid grades a focused
 * cumulative family ([TOTAL_FAMILY_8824]) on the same paths. Nothing is assumed from the export: the phone's own launches
 * prove (or refuse) it under the same bar as every launch rule.
 */
object LaunchReplay8744 {
    /**
     * V5.0.8747 — how the position is unwound (owner: "the bot should be able to hit 50x trades"):
     *  HALF_TRAIL  half at [Rule.takeX], the rest trailed 30 % from its peak;
     *  DOCTRINE    the bot's runner ladder today: 25 % at 2x, 18 % at 5x, 67 % of the rest at 10x, the rest trailed 45 %;
     *              V5.0.8873: read from RunnerGrab7967.TIERS_7967 ([doctrineRungs8873]) — a quarter of the rest at 10x, 30x
     *              and 100x, 22 % at 300x;
     *  MOONBAG     half at [Rule.takeX], 30 % at 10x, the last 20 % trailed 60 % from its peak (a 50x keeps a real bag).
     */
    /**
     * V5.0.8756 — SCALE_OUT is the exit the runner literature recommends (sell a quarter at 3x and another at each further
     * doubling, 6x and 12x, the last quarter a moonbag on a 50 % trail). It is graded beside the bot's own DOCTRINE ladder
     * on the same paths so the record, not an opinion, says which one keeps more of a runner; only DOCTRINE (what the live
     * exits run) can be proven for the sniper.
     */
    /**
     * V5.0.8854 §THE_EXIT_THAT_MADE_MONEY — owner: "previously apks have made money. doubled the cash balance and worked fine
     * 5.0.8096". APK 5.0.8096 (cddf86c17, profit factor 2.15, about 2.6x the balance) and 5.0.8114 (379ad2fa0, 32W/47L,
     * 0.195 -> 0.434 SOL) held a MOONSHOT / QUALITY / BLUECHIP / CORE buy for its lifecycle FROM ENTRY (LifecycleHold8065):
     * no tick stops, a -[LifecycleHold8065.FLOOR_PCT] % floor at any time, half at 2x then 30 % of the rest at 5x and at 10x
     * ([LifecycleHold8065.ladder8071]), out on a 45 %-of-peak give-back after a +300 % peak ([LifecycleHold8065.peakGiveback8065]);
     * its base and trend breaks run on 15-minute bars after two hours, beyond a launch hour. Since 8189 the hold needs
     * runner evidence first, so a fresh buy takes the lane stop. LIFECYCLE is that exit, read from the hold's own constants
     * and give-back so it cannot drift from it: its own floor whatever the rule's stop, no trail, no time stop, whatever is
     * still held sold at the hour's last print. It is graded beside DOCTRINE on the same launches (pairedLadders8788), at
     * the latency the bot has (rulesFor8829); it is measured, not traded (provenOf8748 stays DOCTRINE, the exit live runs).
     * Offline (the owner's export, 1,088 $10k crossers with 15 buys, bought 30 s later) it read +7.0 % at the 10 s bar's
     * close and -14 % at the bar's high: the bars cannot price the fill, the phone's prints can.
     */
    enum class Ladder { HALF_TRAIL, DOCTRINE, MOONBAG, SCALE_OUT, LIFECYCLE }
    data class Rule(val capUsd: Double, val minBuys: Int, val takeX: Double, val stopPct: Double, val ladder: Ladder = Ladder.HALF_TRAIL,
                    val delayMs: Long = 2_000L, val totalBuys: Int = 0) {
        val id: String get() = "cap\$${(capUsd / 1000).toInt()}k buys>=$minBuys${if (totalBuys > 0) " total>=$totalBuys" else ""} " +
            "tp${takeX.toInt()}x stop${stopPct.toInt()}% $ladder +${delayMs / 1000}s"
    }
    data class Result(val rule: Rule, val n: Int, val mean: Double, val se: Double, val win: Double, val twoX: Double, val tenX: Double = 0.0,
                      val median: Double = Double.NaN, val exTop3: Double = Double.NaN, val exTop3Se: Double = Double.NaN,
                      val fiftyX: Double = 0.0)

    /** A print received more than this after its chain time is a backfill: it never triggers an entry. */
    const val LIVE_LAG_MS_8748 = 15_000L
    /** The rule LaunchSniper8748 trades must hold at a latency the bot can meet (8 s loop + quote + send). */
    internal const val ARM_MIN_DELAY_MS_8748 = 10_000L
    internal const val PROVEN_MIN_N_8748 = 100
    private const val SELL_DELAY_MS_8748 = 1_500L
    private const val SPIKE_X_8748 = 3.0
    /** The live entry size the cost is charged at (SOL) and a fresh curve's depth (virtual 30 SOL + a few real). */
    private const val SIZE_SOL_8748 = 0.04
    private const val CURVE_SOL_8748 = 35.0
    private const val TRAIL_PCT = 30.0
    private const val TIME_STOP_MS = 10L * 60_000L
    /** V5.0.8828 — SignalReplay8828 unwinds the owner's signals on the same time stop. */
    internal const val TIME_STOP_MS_8828 = TIME_STOP_MS
    private const val MIN_N = 50
    private const val RUN_EVERY_MS = 30L * 60_000L
    internal const val MAX_LAUNCHES = 6_000
    internal const val SUPPLY = 1.0e9

    /** V5.0.8749 — 5 s and 20 s join 2 / 10 / 30 s: the armed rule must match the latency the bot measures (LaunchSniper8748). */
    private val DELAYS_8749 = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
    /**
     * V5.0.8824 — the cumulative family: the $10k / $15k cross after 25 / 45 / 80 live buys since birth, still bought in the
     * last minute (1 / 8), at the latencies the bot can meet; DOCTRINE and SCALE_OUT once (their rungs ignore the take),
     * HALF_TRAIL at each take. 2 x 2 x 3 x 3 stops x 5 exits x 2 delays = 360 rules.
     */
    internal val TOTAL_FAMILY_8824: List<Rule> = buildList {
        for (cap in listOf(10_000.0, 15_000.0)) for (b in listOf(1, 8)) for (tot in listOf(25, 45, 80))
            for (st in listOf(20.0, 35.0, 50.0)) for (d in listOf(10_000L, 20_000L)) {
                add(Rule(cap, b, 2.0, st, Ladder.DOCTRINE, d, tot))
                add(Rule(cap, b, 2.0, st, Ladder.SCALE_OUT, d, tot))
                for (tx in listOf(2.0, 3.0, 5.0)) add(Rule(cap, b, tx, st, Ladder.HALF_TRAIL, d, tot))
            }
    }
    /**
     * V5.0.8854 — the 8096 exit on every trigger the grid has: window rules at each latency and the cumulative family at its
     * latencies; it carries its own floor and rungs, so one rule per trigger and latency (3 x 3 x 5 + 2 x 2 x 3 x 2 = 69).
     */
    internal val LIFECYCLE_8854: List<Rule> = buildList {
        val floor = com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT
        for (cap in listOf(5_000.0, 10_000.0, 15_000.0)) for (b in listOf(3, 8, 15)) for (d in DELAYS_8749)
            add(Rule(cap, b, 2.0, floor, Ladder.LIFECYCLE, d))
        for (cap in listOf(10_000.0, 15_000.0)) for (b in listOf(1, 8)) for (tot in listOf(25, 45, 80)) for (d in listOf(10_000L, 20_000L))
            add(Rule(cap, b, 2.0, floor, Ladder.LIFECYCLE, d, tot))
    }
    val GRID: List<Rule> = buildList {
        for (cap in listOf(5_000.0, 10_000.0, 15_000.0)) for (b in listOf(3, 8, 15))
            for (tx in listOf(2.0, 3.0, 5.0)) for (st in listOf(20.0, 35.0)) for (l in Ladder.values()) if (l != Ladder.LIFECYCLE)
                for (d in DELAYS_8749) add(Rule(cap, b, tx, st, l, d))
        addAll(TOTAL_FAMILY_8824)
        addAll(LIFECYCLE_8854)
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
    /** V5.0.8756 — [sol] / [wallet] (an id, -1 = unnamed) per print (SmartMoney8756), [bornMs] the launch's wall-clock birth. */
    internal class Path8748(val t: LongArray, val p: DoubleArray, val buy: BooleanArray, val live: BooleanArray, val spikes: Int,
                            val sol: DoubleArray? = null, val wallet: IntArray? = null, val bornMs: Long = 0L)

    internal fun clean8748(t: LongArray, p: DoubleArray, buy: BooleanArray, live: BooleanArray?,
                           sol: DoubleArray? = null, wallet: IntArray? = null, bornMs: Long = 0L): Path8748 {
        val drop = spikes8748(p)
        val keep = p.indices.filter { !drop[it] }
        return Path8748(LongArray(keep.size) { t[keep[it]] }, DoubleArray(keep.size) { p[keep[it]] },
            BooleanArray(keep.size) { buy[keep[it]] }, BooleanArray(keep.size) { live?.get(keep[it]) ?: true }, p.size - keep.size,
            sol?.let { v -> DoubleArray(keep.size) { v[keep[it]] } }, wallet?.let { w -> IntArray(keep.size) { w[keep[it]] } }, bornMs)
    }

    /**
     * Pure: the first live print where the cap is reached with [minBuys] live buys in the 60 s before it and (V5.0.8824)
     * [totalBuys] live buys since the first print; -1 when none.
     */
    internal fun trigger8748(x: Path8748, capUsd: Double, minBuys: Int, solUsd: Double, totalBuys: Int = 0): Int {
        var j = 0; var buys = 0; var total = 0
        for (i in x.t.indices) {
            if (x.live[i] && x.buy[i]) { buys++; total++ }
            while (x.t[j] < x.t[i] - 60_000L) { if (x.live[j] && x.buy[j]) buys--; j++ }
            if (x.live[i] && x.p[i] * SUPPLY * solUsd >= capUsd && buys >= minBuys && total >= totalBuys) return i
        }
        return -1
    }

    /** The curve's state at [atMs]: the last print at or before it, from index [from]. */
    private fun stateAt(t: LongArray, from: Int, atMs: Long): Int { var m = from; while (m + 1 < t.size && t[m + 1] <= atMs) m++; return m }

    /** Pure: the net % of [rule] on [x] entered at trigger [trig]. */
    private fun run8748(x: Path8748, trig: Int, rule: Rule, costPct: Double): Double? {
        val e = stateAt(x.t, trig, x.t[trig] + rule.delayMs)
        val entry = x.p[e]
        if (!(entry > 0.0)) return null
        return exitNet8754(x, e, rule.ladder, rule.takeX, entry * (1.0 - rule.stopPct / 100.0), TIME_STOP_MS, costPct)
    }

    /** V5.0.8755 — StrategyLab8755: one launch rule (any parameters, not only the grid's) on one path, or null when it never fired. */
    internal fun simulateRule8755(x: Path8748, rule: Rule, solUsd: Double, costPct: Double): Double? {
        if (x.t.isEmpty()) return null
        val trig = trigger8748(x, rule.capUsd, rule.minBuys, solUsd, rule.totalBuys)
        return if (trig < 0) null else run8748(x, trig, rule, costPct)
    }

    /** V5.0.8755 — the last run's launch paths, SOL price and cost, kept so the lab can grade the rules it invents. */
    @Volatile internal var paths8755: List<Path8748> = emptyList()
    @Volatile internal var solUsd8755 = Double.NaN
    @Volatile internal var cost8755 = Double.NaN

    /** V5.0.8754 — the entry print for a trigger at [trig] after [delayMs]: where the curve stood then (BaseFamily8754 too). */
    internal fun entryIndex8754(x: Path8748, trig: Int, delayMs: Long): Int = stateAt(x.t, trig, x.t[trig] + delayMs)

    /**
     * V5.0.8854 — LifecycleHold8065's takes as fractions of the ORIGINAL position: each take there sells a share of what is
     * still held (half at +100 %, then 30 % of the rest at +400 % and at +900 %), so 0.5, 0.15, 0.105 of the original.
     */
    internal val LIFECYCLE_RUNGS_8854: List<Pair<Double, Double>> by lazy {
        var left = 1.0
        com.lifecyclebot.engine.LifecycleHold8065.ladder8071().map { (pct, share) -> val f = left * share; left -= f; (1.0 + pct / 100.0) to f }
    }

    /**
     * Pure: the net % of a position entered at print [e] of [x] and unwound by [ladder] ([takeX] its first rung for HALF_TRAIL
     * and MOONBAG), with a hard stop at [stopPx] until the first rung, out at [timeStopMs] if it never reached +20 %; each sale
     * fills where the curve stood [SELL_DELAY_MS_8748] after the print that triggered it. V5.0.8754: shared by the launch
     * replay (stop = entry less the rule's stop %) and the base family (stop under the base's low).
     */
    /**
     * V5.0.8873 — DOCTRINE is the live runner ladder itself (RunnerGrab7967.TIERS_7967, each rung a share of what remains),
     * as (multiple, fraction of the ORIGINAL position). It was a hand copy (18 % of the original at 5x where live sells 18 %
     * of the remainder, two thirds at 10x) and would not have followed the ladder past 10x.
     */
    internal fun doctrineRungs8873(): List<Pair<Double, Double>> {
        var left = 1.0
        return com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967.map { (pct, f) -> val sell = left * f; left -= sell; (1.0 + pct / 100.0) to sell }
    }

    internal fun exitNet8754(x: Path8748, e: Int, ladder: Ladder, takeX: Double, stopPx: Double, timeStopMs: Long, costPct: Double): Double? {
        val t = x.t; val p = x.p
        val entry = p[e]
        if (!(entry > 0.0)) return null
        fun fill(k: Int) = p[stateAt(t, k, t[k] + SELL_DELAY_MS_8748)] / entry - 1.0
        var held = 1.0; var banked = 0.0; var peak = entry; var tookFirst = false
        // V5.0.8854 — LIFECYCLE: the hold's own floor at any time, no trail, no time stop, the 45 %-of-peak give-back
        val lifecycle = ladder == Ladder.LIFECYCLE
        val floorPx = if (lifecycle) entry * (1.0 - com.lifecyclebot.engine.LifecycleHold8065.FLOOR_PCT / 100.0) else stopPx
        val rungs = when (ladder) {   // (multiple, fraction of the ORIGINAL position)
            Ladder.HALF_TRAIL -> listOf(takeX to 0.5)
            Ladder.DOCTRINE -> doctrineRungs8873()
            Ladder.MOONBAG -> listOf(takeX to 0.5, 10.0 to 0.3)
            Ladder.SCALE_OUT -> listOf(3.0 to 0.25, 6.0 to 0.25, 12.0 to 0.25)
            Ladder.LIFECYCLE -> LIFECYCLE_RUNGS_8854
        }
        val trailPct = when (ladder) { Ladder.HALF_TRAIL -> TRAIL_PCT; Ladder.DOCTRINE -> 45.0; Ladder.MOONBAG -> 60.0; Ladder.SCALE_OUT -> 50.0; Ladder.LIFECYCLE -> Double.NaN }
        var nextRung = 0
        for (k in e + 1 until t.size) {
            val px = p[k]
            if (px > peak) peak = px
            while (nextRung < rungs.size && px >= entry * rungs[nextRung].first * (1.0 - 1e-9)) {
                val sell = minOf(rungs[nextRung].second, held); banked += sell * fill(k); held -= sell; nextRung++; tookFirst = true
            }
            val stopHit = (lifecycle || !tookFirst) && px <= floorPx
            val trailHit = if (lifecycle) com.lifecyclebot.engine.LifecycleHold8065.peakGiveback8065((peak / entry - 1.0) * 100.0, (px / entry - 1.0) * 100.0)
                else tookFirst && px <= peak * (1.0 - trailPct / 100.0)
            val timeHit = !lifecycle && !tookFirst && t[k] - t[e] >= timeStopMs && peak < entry * 1.2
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
        val trig = trigger8748(x, rule.capUsd, rule.minBuys, solUsd, rule.totalBuys)
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

    /**
     * V5.0.8829 §GRADED_AT_THE_LATENCY_THE_BOT_HAS — 5.0.8820 report: needDelay=38s (tape lag p75 30 s + decide-and-send).
     * Every rule must be graded at or beyond the required latency to be proven or watched (provenOf8748 / shadowOf8748), and
     * the grid's longest delay is 30 s: the sniper sat "IDLE (no replay rule with n>=50 yet)" on 313 graded launches, and
     * the base family the same. Pure: the extra delay to grade at — the required latency rounded up to 5 s (so a rule's id
     * holds between runs), or null when the grid already has it.
     */
    internal fun atLatency8829(gridDelays: Collection<Long>, needMs: Long): Long? {
        if (needMs <= 0L) return null
        val d = ((needMs + 4_999L) / 5_000L) * 5_000L
        return if (d in gridDelays) null else d
    }

    /**
     * V5.0.8829 — the grid plus every DOCTRINE rule (the only ladder that can be proven) at the required latency.
     * V5.0.8854 — and every LIFECYCLE rule: the 8096 exit is read at the latency the bot has, beside the one it trades.
     */
    internal fun rulesFor8829(needMs: Long): List<Rule> {
        val d = atLatency8829(GRID.map { it.delayMs }.toSet(), needMs) ?: return GRID
        return GRID + GRID.filter { it.ladder == Ladder.DOCTRINE || it.ladder == Ladder.LIFECYCLE }.map { it.copy(delayMs = d) }.distinct()
    }

    /** Each rule's result over the launches, best first: n >= MIN_N, then the ex-top-3 mean less its SE. */
    private fun evaluate8748(launches: List<Path8748>, solUsd: Double, costPct: Double, rules: List<Rule> = GRID): List<Result> {
        val values = HashMap<Rule, ArrayList<Double>>()
        val triggers = rules.map { Triple(it.capUsd, it.minBuys, it.totalBuys) }.distinct()
        for (x in launches) {
            val trig = triggers.associateWith { (c, b, tot) -> trigger8748(x, c, b, solUsd, tot) }
            for (r in rules) {
                val i = trig[Triple(r.capUsd, r.minBuys, r.totalBuys)] ?: -1
                if (i >= 0) run8748(x, i, r, costPct)?.let { values.getOrPut(r) { ArrayList() }.add(it) }
            }
        }
        return values.mapNotNull { (r, v) -> stats8748(r, v) }
            .sortedWith(compareByDescending<Result> { it.n >= MIN_N }.thenByDescending { (it.exTop3 - it.exTop3Se).takeIf { d -> d.isFinite() } ?: -1e18 })
    }

    /** Pure: the rule the live sniper may trade from [results], or null (see the class note). */
    internal fun provenOf8748(results: List<Result>, minDelayMs: Long = ARM_MIN_DELAY_MS_8748): Result? = results.filter {
        it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= maxOf(ARM_MIN_DELAY_MS_8748, minDelayMs) && it.n >= PROVEN_MIN_N_8748 &&
            it.exTop3.isFinite() && it.exTop3Se.isFinite() && it.exTop3 - it.exTop3Se > 0.0
    }.maxByOrNull { it.exTop3 - it.exTop3Se }

    /**
     * V5.0.8856 §PROOF_THAT_CAN_ACCUMULATE — 5.0.8853: the best rule the phone has graded on real prints (the $10k cross
     * with 3 buys in the last minute, bought 2 s later, half at 2x) read mean +15 %, 40 % winners, 13 % doubling over 130
     * launches; +13 % at 20 s. It can never be proven: the replay re-derives every record from LaunchHour8628's 48 hours
     * (about 130 entries for that rule, however long the bot runs), and the bar strips each rule's three best launches —
     * on a strategy whose profit IS its runners, of a sample that can never grow. And choosing the best of 1,500 rules on
     * 130 launches flatters whichever is chosen, which is what the strip was guarding.
     *
     * The honest test of a chosen rule is what it does AFTER it was chosen. The rule the sniper watches ([shadowOf8748]) is
     * NOMINATED when it is first named; from then on every launch born after the nomination, once its hour is complete, is
     * graded for it exactly once and added to a record that is kept (launch_forward_8856.json) — no window, no re-derivation,
     * nothing chosen with hindsight. A nominee is PROVEN on that record alone ([forwardProves8856]): the live ladder
     * (DOCTRINE), a latency the bot has, [PROVEN_MIN_N_8748]+ entries, the mean two standard errors above zero, and still
     * positive without its single best launch. Up to [MAX_NOMINEES_8856] nominees are followed; a rule named again keeps
     * the record it has. The 48-hour proof ([provenOf8748]) stands beside it unchanged.
     */
    internal class Forward8856(val rule: Rule, val nominatedAtMs: Long) {
        var n = 0; var sum = 0.0; var sq = 0.0; var best = Double.NEGATIVE_INFINITY; var wins = 0; var twoX = 0; var tenX = 0
        var lastBornMs = nominatedAtMs
        fun add(v: Double) { n++; sum += v; sq += v * v; if (v > best) best = v; if (v > 0.0) wins++; if (v >= 100.0) twoX++; if (v >= 900.0) tenX++ }
        val mean: Double get() = if (n > 0) sum / n else Double.NaN
        /** The standard error of the mean (sample variance). */
        val se: Double get() = if (n > 1) kotlin.math.sqrt(((sq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0) / n) else Double.NaN
        val exBest: Double get() = if (n > 1) (sum - best) / (n - 1) else Double.NaN
    }
    private const val MAX_NOMINEES_8856 = 12
    private const val HOUR_MS_8856 = 3_600_000L
    internal const val FORWARD_Z_8856 = 2.0
    private val forward8856 = LinkedHashMap<String, Forward8856>()
    @Volatile private var forwardRestored8856 = false

    /** Pure: is [f]'s forward record proof at the required latency [minDelayMs]? */
    internal fun forwardProves8856(f: Forward8856, minDelayMs: Long = ARM_MIN_DELAY_MS_8748): Boolean =
        f.rule.ladder == Ladder.DOCTRINE && f.rule.delayMs >= maxOf(ARM_MIN_DELAY_MS_8748, minDelayMs) && f.n >= PROVEN_MIN_N_8748 &&
            f.mean.isFinite() && f.se.isFinite() && f.mean - FORWARD_Z_8856 * f.se > 0.0 && f.exBest > 0.0

    /** Names [rule] a nominee at [nowMs] unless it already is one; the nominee with the fewest entries makes room. */
    @Synchronized internal fun nominate8856(rule: Rule, nowMs: Long) {
        if (forward8856.containsKey(rule.id)) return
        if (forward8856.size >= MAX_NOMINEES_8856) forward8856.entries.minByOrNull { it.value.n }?.key?.let { forward8856.remove(it) }
        forward8856[rule.id] = Forward8856(rule, nowMs)
    }

    /**
     * Grades every nominee on the launches of [launches] born after its last graded birth whose first hour is complete at
     * [nowMs] — each launch once per nominee, whether or not the rule fired on it.
     */
    @Synchronized internal fun gradeForward8856(launches: List<Path8748>, solUsd: Double, costPct: Double, nowMs: Long) {
        for (f in forward8856.values) {
            var newest = f.lastBornMs
            for (x in launches) {
                if (x.bornMs <= f.lastBornMs || x.bornMs + HOUR_MS_8856 > nowMs) continue
                if (x.bornMs > newest) newest = x.bornMs
                simulateRule8755(x, f.rule, solUsd, costPct)?.let { f.add(it) }
            }
            f.lastBornMs = newest
        }
    }

    /** The proven nominee with the highest lower bound, as the [Result] the sniper trades (its evidence: the mean without its best launch, and the SE). */
    @Synchronized internal fun forwardProven8856(minDelayMs: Long = ARM_MIN_DELAY_MS_8748): Result? =
        forward8856.values.filter { forwardProves8856(it, minDelayMs) }.maxByOrNull { it.mean - FORWARD_Z_8856 * it.se }?.let { f ->
            Result(f.rule, f.n, f.mean, f.se, f.wins.toDouble() / f.n, f.twoX.toDouble() / f.n, f.tenX.toDouble() / f.n, Double.NaN, f.exBest, f.se)
        }

    private fun forwardFile8856(): java.io.File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { java.io.File(it, "launch_forward_8856.json") }
    } catch (_: Throwable) { null }

    @Synchronized internal fun encodeForward8856(): String = org.json.JSONArray().also { a -> forward8856.values.forEach { f ->
        a.put(org.json.JSONObject().put("cap", f.rule.capUsd).put("buys", f.rule.minBuys).put("takeX", f.rule.takeX).put("stop", f.rule.stopPct)
            .put("ladder", f.rule.ladder.name).put("delay", f.rule.delayMs).put("total", f.rule.totalBuys).put("at", f.nominatedAtMs)
            .put("n", f.n).put("sum", f.sum).put("sq", f.sq).put("best", if (f.best.isFinite()) f.best else 0.0).put("wins", f.wins)
            .put("twoX", f.twoX).put("tenX", f.tenX).put("born", f.lastBornMs))
    } }.toString()

    @Synchronized internal fun decodeForward8856(raw: String?) {
        if (raw.isNullOrBlank()) return
        val a = org.json.JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val ladder = try { Ladder.valueOf(o.optString("ladder")) } catch (_: Throwable) { continue }
            val rule = Rule(o.optDouble("cap"), o.optInt("buys"), o.optDouble("takeX"), o.optDouble("stop"), ladder, o.optLong("delay"), o.optInt("total"))
            val n = o.optInt("n"); val sum = o.optDouble("sum"); val sq = o.optDouble("sq")
            if (!(rule.capUsd > 0.0) || n < 0 || !sum.isFinite() || !sq.isFinite() || sq < 0.0 || forward8856.size >= MAX_NOMINEES_8856) continue
            forward8856[rule.id] = Forward8856(rule, o.optLong("at")).also { f ->
                f.n = n; f.sum = sum; f.sq = sq; f.best = if (n > 0) o.optDouble("best") else Double.NEGATIVE_INFINITY
                f.wins = o.optInt("wins").coerceIn(0, n); f.twoX = o.optInt("twoX").coerceIn(0, n); f.tenX = o.optInt("tenX").coerceIn(0, n)
                f.lastBornMs = maxOf(o.optLong("born"), f.nominatedAtMs)
            }
        }
    }

    private fun restoreForward8856() {
        if (forwardRestored8856) return
        val f = forwardFile8856() ?: return
        forwardRestored8856 = true
        try { if (f.exists()) decodeForward8856(f.readText()) } catch (_: Throwable) {}
    }

    private fun saveForward8856() {
        val f = forwardFile8856() ?: return
        try { val tmp = java.io.File(f.parentFile, f.name + ".tmp"); tmp.writeText(encodeForward8856()); if (!tmp.renameTo(f)) tmp.delete() } catch (_: Throwable) {}
    }

    /** The report: every nominee's forward record, most entries first. */
    @Synchronized internal fun forwardLine8856(): String = "forward8856{" + forward8856.values.sortedByDescending { it.n }.joinToString(" | ") { f ->
        "${f.rule.id} n${f.n} mean${if (f.mean.isFinite()) "%+.1f".format(f.mean) else "-"}±${if (f.se.isFinite()) "%.1f".format(f.se) else "-"}% " +
            "exBest${if (f.exBest.isFinite()) "%+.1f".format(f.exBest) else "-"}% win${if (f.n > 0) f.wins * 100 / f.n else 0}% 2x+${if (f.n > 0) f.twoX * 100 / f.n else 0}% " +
            (if (forwardProves8856(f)) "PROVEN" else "watching")
    }.ifBlank { "-" } + "}"

    internal fun resetForwardForTest8856() = synchronized(this) { forward8856.clear(); forwardRestored8856 = true }

    /** Pure: the rule the sniper watches in shadow while none is proven: the best one it could trade, by the same order. */
    internal fun shadowOf8748(results: List<Result>, minDelayMs: Long = ARM_MIN_DELAY_MS_8748): Result? = results.firstOrNull {
        it.rule.ladder == Ladder.DOCTRINE && it.rule.delayMs >= maxOf(ARM_MIN_DELAY_MS_8748, minDelayMs) && it.n >= MIN_N
    }

    /**
     * V5.0.8825 §THE_BOT_LOOKS_WHERE_THE_MONEY_IS — the clean-epoch decisions (1,734 coins in 12 h): 973 were under a $5k cap
     * and 24 s old (median), fed by the new-token stream; 157 were $10-30k, and those a median 6 minutes old. The coins the
     * owner trades (crossing $10k with buyers behind them) were barely in front of the decision loop, so no label, cell or
     * learner ever measured them, and the sniper only puts a crosser on the watchlist once a rule is ARMED: before proof,
     * nothing looks; without looking, nothing is proven. [attentionOf8825] names the best graded rule of the cumulative family
     * (n >= MIN_N; the replay's own order); LaunchSniper8748 puts every coin meeting its TRIGGER on the watchlist to be
     * decided on and labelled like any other. No evidence is served for it and it buys nothing by itself.
     */
    internal fun attentionOf8825(results: List<Result>): Result? = results.filter {
        it.rule.totalBuys > 0 && it.n >= MIN_N && it.exTop3.isFinite() && it.exTop3Se.isFinite()
    }.maxByOrNull { it.exTop3 - it.exTop3Se }
    @Volatile private var attention: Result? = null
    /** LaunchSniper8748: the trigger whose crossers are watched (V5.0.8825), or null before the family is graded. */
    fun attention8825(): Result? = attention

    @Volatile private var last: List<Result> = emptyList()
    @Volatile private var proven: Result? = null
    @Volatile private var shadow: Result? = null
    @Volatile private var lastRunMs = 0L
    @Volatile private var status = "not-run"
    private val running = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "launch-replay-8744").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** LaunchSniper8748: the proven rule (trade it) and the shadow rule (watch it), from the last run. */
    fun proven8748(): Result? = proven
    /** V5.0.8756 — the last run's graded rules (the Lab's ladder comparison). */
    internal fun results8756(): List<Result> = last
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
                val mints8888 = ArrayList<String>()   // V5.0.8888 — the launch each path is, for the lane exit grade
                var spikes = 0L; var liveN = 0
                val walletNames = ArrayList<String>()
                LaunchHour8628.forEachLaunch8756(nowMs, MAX_LAUNCHES, LIVE_LAG_MS_8748, walletNames) { r ->
                    val x = clean8748(r.t, r.p, r.buy, r.live, r.sol, r.wallet, r.bornMs); spikes += x.spikes
                    if (x.live.any { it }) liveN++
                    set.add(x); mints8888.add(r.mint)
                    // V5.0.8861 — a launch that crossed the floor and whose hold window is over becomes one kept row of the runner model
                    try { RunnerModel8861.onLaunch8861(r, solUsd, nowMs) } catch (_: Throwable) {}
                    // V5.0.8822 — every completed launch hour is also one chart for the colour/shape setup learner
                    try { com.lifecyclebot.engine.chart.ColourShape8807.learnLaunch8822(r.mint, r.bornMs, x.t, x.p, x.sol, nowMs) } catch (_: Throwable) {}
                }
                val cost = FieldManual7715.roundTripCostPct7766(SIZE_SOL_8748, SIZE_SOL_8748 * solUsd, 2.0 * CURVE_SOL_8748 * solUsd)
                val need = try { LaunchSniper8748.requiredDelayMs8749() } catch (_: Throwable) { ARM_MIN_DELAY_MS_8748 }
                val res = evaluate8748(set, solUsd, cost, rulesFor8829(need))   // V5.0.8829 — and at the latency the bot has
                last = res; shadow = shadowOf8748(res, need); attention = attentionOf8825(res)
                // V5.0.8856 — the watched rule is graded forward, on launches born after it was named, with no window
                try { restoreForward8856(); shadow?.rule?.let { nominate8856(it, nowMs) }; gradeForward8856(set, solUsd, cost, nowMs); saveForward8856() } catch (_: Throwable) {}
                proven = provenOf8748(res, need) ?: forwardProven8856(need)
                // V5.0.8788 — the ladders on the same launches as the rule the sniper watches (or the best graded one)
                (shadow ?: res.firstOrNull { it.n >= MIN_N })?.rule?.let { base ->
                    try { paired = pairedLadders8788(set, base, solUsd, cost); pairedBase = base } catch (_: Throwable) {}
                }
                try { BaseFamily8754.evaluate8754(set, solUsd, cost, need) } catch (_: Throwable) {}   // V5.0.8754 the base family, same paths
                try { SmartMoney8756.evaluate8756(set, walletNames, cost, need) } catch (_: Throwable) {}            // V5.0.8756 the wallets, same paths
                try { SignalReplay8828.evaluate8828(set, solUsd, cost, need) } catch (_: Throwable) {}              // V5.0.8828 the owner's signals, same paths
                try { RunnerModel8861.evaluate8861(cost, need, nowMs) } catch (_: Throwable) {}                       // V5.0.8861 the runner model, the same launches on their whole tape
                try { LaneExitGrade8888.evaluate8888(set, mints8888, LaunchHour8628.firstDecisions8888(nowMs), nowMs) } catch (_: Throwable) {}  // V5.0.8888 each doctrine lane's give-back, same launches
                paths8755 = set; solUsd8755 = solUsd; cost8755 = cost
                val births = try { LaunchHour8628.births8749(nowMs) } catch (_: Throwable) { -1 }
                status = "ran births48h=$births launches>=3prints=${set.size} withLivePrints=$liveN needDelay=${need / 1000}s spikesDropped=$spikes solUsd=${"%.0f".format(solUsd)} cost=${"%.1f".format(cost)}%"
            } catch (t: Throwable) { status = "failed:${t.javaClass.simpleName}" } finally { running.set(false) }
        }
    }

    /** V5.0.8788 — one ladder against DOCTRINE on the same launches: entries, mean per-launch difference (points), its SE. */
    data class Paired8788(val ladder: Ladder, val n: Int, val meanDiff: Double, val se: Double) {
        /** Beats (or loses to) DOCTRINE by more than two SEs of the paired difference, on at least [PROVEN_MIN_N_8748] launches. */
        val verdict: String get() = when {
            n < PROVEN_MIN_N_8748 || !se.isFinite() -> "thin"
            meanDiff - 2.0 * se > 0.0 -> "BEATS_DOCTRINE"
            meanDiff + 2.0 * se < 0.0 -> "WORSE"
            else -> "same"
        }
    }

    /**
     * V5.0.8788 — pure: every ladder run on the SAME launches as [base] (its trigger, take, stop and delay), each compared with
     * DOCTRINE launch by launch. Ranking ladders by their own ex-top-3 means compared different noise: 8757 read DOCTRINE
     * -1.5 ± 5.2 against MOONBAG +9.4 ± 8.6 and could not say which exit keeps more. The paired difference cancels the coin
     * (the 8757 launch export, offline: ladder differences of 0-3 points at ±1-1.5, none past two SEs).
     */
    internal fun pairedLadders8788(launches: List<Path8748>, base: Rule, solUsd: Double, costPct: Double): List<Paired8788> {
        val diffs = Ladder.values().associateWith { ArrayList<Double>() }
        for (x in launches) {
            if (x.t.isEmpty()) continue
            val trig = trigger8748(x, base.capUsd, base.minBuys, solUsd, base.totalBuys)
            if (trig < 0) continue
            val d = run8748(x, trig, base.copy(ladder = Ladder.DOCTRINE), costPct) ?: continue
            for (l in Ladder.values()) if (l != Ladder.DOCTRINE) run8748(x, trig, base.copy(ladder = l), costPct)?.let { diffs.getValue(l).add(it - d) }
        }
        return diffs.filterKeys { it != Ladder.DOCTRINE }.map { (l, v) ->
            if (v.size < 2) Paired8788(l, v.size, Double.NaN, Double.NaN) else {
                val m = v.average(); val sd = kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
                Paired8788(l, v.size, m, sd / kotlin.math.sqrt(v.size.toDouble()))
            }
        }
    }

    @Volatile private var paired: List<Paired8788> = emptyList()
    @Volatile private var pairedBase: Rule? = null

    /** V5.0.8788 — the last run's paired ladder comparison as a report line. */
    internal fun pairedLine8788(): String = "pairedLadders{" + (pairedBase?.let { b ->
        "on ${b.id.substringBefore(" ${b.ladder}")} +${b.delayMs / 1000}s vs DOCTRINE: " + paired.joinToString(" ") {
            "${it.ladder}=${if (it.meanDiff.isFinite()) "%+.1f".format(it.meanDiff) else "-"}±${if (it.se.isFinite()) "%.1f".format(it.se) else "-"}/n${it.n}/${it.verdict}"
        }
    } ?: "-") + "}"

    /** V5.0.8756 — pure: each exit ladder's best rule (n >= MIN_N, by the ex-top-3 mean less its SE): which exit keeps more of a run. */
    internal fun ladderLine8756(results: List<Result>): String = "ladders{" + Ladder.values().joinToString(" ") { l ->
        val b = results.filter { it.rule.ladder == l && it.n >= MIN_N && it.exTop3.isFinite() && it.exTop3Se.isFinite() }
            .maxByOrNull { it.exTop3 - it.exTop3Se }
        "$l=" + (b?.let { "exTop3${"%+.1f".format(it.exTop3)}±${"%.1f".format(it.exTop3Se)}%/10x+${"%.1f".format(it.tenX * 100)}%/n${it.n}" } ?: "-")
    } + "}"

    /**
     * V5.0.8824 — the cumulative family beside the window-only rules: the best graded rule (n >= MIN_N) of each, ranked by
     * the ex-top-3 mean less its SE, so the report says which way of choosing the crossers holds on this phone's launches.
     */
    internal fun totalLine8824(results: List<Result>): String {
        fun best(cum: Boolean) = results.filter { (it.rule.totalBuys > 0) == cum && it.n >= MIN_N && it.exTop3.isFinite() && it.exTop3Se.isFinite() }
            .maxByOrNull { it.exTop3 - it.exTop3Se }
        fun fmt(r: Result?) = r?.let { "${it.rule.id} n${it.n} mean${"%+.1f".format(it.mean)}% exTop3${"%+.1f".format(it.exTop3)}±${"%.1f".format(it.exTop3Se)}%" } ?: "-"
        return "cumulative8824{best=${fmt(best(true))} vsWindowOnly=${fmt(best(false))}}"
    }

    private fun line(r: Result): String =
        "${r.rule.id} n${r.n} med${"%+.0f".format(r.median)}% mean${"%+.0f".format(r.mean)}% exTop3${"%+.1f".format(r.exTop3)}±${"%.1f".format(r.exTop3Se)}% " +
            "win${(r.win * 100).toInt()}% 2x+${(r.twoX * 100).toInt()}% 10x+${"%.1f".format(r.tenX * 100)}% 50x+${"%.1f".format(r.fiftyX * 100)}%"

    fun statusLine8744(): String {
        maybeRun8744()
        val top = last.take(5).joinToString(" · ") { line(it) }.ifBlank { "-" }
        return "launchReplay8744[$status rules=${GRID.size} proven{${proven?.let { line(it) } ?: "none"}} ${ladderLine8756(last)} ${pairedLine8788()} " +
            "${totalLine8824(last)} ${forwardLine8856()} " +
            "shadow{${shadow?.let { line(it) } ?: "none"}} best{$top} rule=live prints only, spikes dropped, fills at the curve after " +
            "the latency, ranked by the mean without each rule's 3 best launches; proven = DOCTRINE ladder, >=${ARM_MIN_DELAY_MS_8748 / 1000}s, " +
            "n>=$PROVEN_MIN_N_8748, ex-top-3 mean - SE > 0 on the 48 h window, or the watched rule's forward record " +
            "(launches born after it was named): mean - 2 SE > 0 and positive without its best launch]"
    }
}
