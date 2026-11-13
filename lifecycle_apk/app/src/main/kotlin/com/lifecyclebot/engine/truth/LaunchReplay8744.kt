package com.lifecyclebot.engine.truth

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8744 §THE_LAUNCH_REPLAY — owner, 14 Nov: "we need the bot to see these developing at 10k. even if it gets out at
 * 40k 50k that's still 5x. but the genuine runners are still caught and held" / "build both now".
 *
 * Every pump.fun trade the market-wide feed decodes is stored with the coin's birth (LaunchHour8628, 48 h). This replays
 * those real first hours against a grid of entry triggers and exit rules, with no look-ahead, before any live rule exists:
 *  - entry: the first print at which the curve market cap (price x 1e9 supply x SOL/USD) crosses [capUsd] and at least
 *    [minBuys] buys printed in the preceding 60 s; filled at the first print >= [DELAY_MS] later (the bot's decision and
 *    send latency), plus half the round-trip cost;
 *  - exit: sell half at [takeX] x, trail the rest [TRAIL_PCT] % from its peak; a hard stop [stopPct] % under entry; out at
 *    [TIME_STOP_MS] if it never reached +20 %; anything still held at the hour's last print is sold there; minus half the
 *    round-trip cost.
 * Each rule's per-launch net % gives n, mean, SE, win rate and the share that returned >= 2x. Report: the best rules by
 * mean - SE (n >= [MIN_N]). Measurement only: it trades nothing.
 */
object LaunchReplay8744 {
    /**
     * V5.0.8747 — how the position is unwound (owner: "the bot should be able to hit 50x trades"):
     *  HALF_TRAIL  half at [Rule.takeX], the rest trailed 30 % from its peak;
     *  DOCTRINE    the bot's runner ladder today: 25 % at 2x, 18 % at 5x, 67 % of the rest at 10x, the rest trailed 45 %;
     *  MOONBAG     half at [Rule.takeX], 30 % at 10x, the last 20 % trailed 60 % from its peak (a 50x keeps a real bag).
     */
    enum class Ladder { HALF_TRAIL, DOCTRINE, MOONBAG }
    data class Rule(val capUsd: Double, val minBuys: Int, val takeX: Double, val stopPct: Double, val ladder: Ladder = Ladder.HALF_TRAIL)
    data class Result(val rule: Rule, val n: Int, val mean: Double, val se: Double, val win: Double, val twoX: Double, val tenX: Double = 0.0)

    private const val DELAY_MS = 2_000L
    private const val TRAIL_PCT = 30.0
    private const val TIME_STOP_MS = 10L * 60_000L
    private const val MIN_N = 50
    private const val RUN_EVERY_MS = 30L * 60_000L
    private const val MAX_LAUNCHES = 6_000
    private const val SUPPLY = 1.0e9

    val GRID: List<Rule> = buildList {
        for (cap in listOf(5_000.0, 10_000.0, 15_000.0)) for (b in listOf(3, 8, 15))
            for (tx in listOf(2.0, 3.0, 5.0)) for (st in listOf(20.0, 35.0)) for (l in Ladder.values()) add(Rule(cap, b, tx, st, l))
    }

    /** Pure: the net % of [rule] on one launch's prints, or null when it never triggered. */
    fun simulate8744(t: LongArray, p: DoubleArray, buy: BooleanArray, rule: Rule, solUsd: Double, costPct: Double): Double? {
        if (t.isEmpty() || !(solUsd > 0.0)) return null
        var trig = -1
        var j = 0
        var buys = 0
        for (i in t.indices) {
            if (buy[i]) buys++
            while (t[j] < t[i] - 60_000L) { if (buy[j]) buys--; j++ }
            if (p[i] * SUPPLY * solUsd >= rule.capUsd && buys >= rule.minBuys) { trig = i; break }
        }
        if (trig < 0) return null
        var e = trig
        while (e < t.size && t[e] < t[trig] + DELAY_MS) e++
        if (e >= t.size) return null
        val entry = p[e]
        if (!(entry > 0.0)) return null
        var held = 1.0; var banked = 0.0; var peak = entry; var tookHalf = false
        val rungs = when (rule.ladder) {   // (multiple, fraction of the ORIGINAL position)
            Ladder.HALF_TRAIL -> listOf(rule.takeX to 0.5)
            Ladder.DOCTRINE -> listOf(2.0 to 0.25, 5.0 to 0.18, 10.0 to 0.57 * 0.67)
            Ladder.MOONBAG -> listOf(rule.takeX to 0.5, 10.0 to 0.3)
        }
        val trailPct = when (rule.ladder) { Ladder.HALF_TRAIL -> TRAIL_PCT; Ladder.DOCTRINE -> 45.0; Ladder.MOONBAG -> 60.0 }
        var nextRung = 0
        for (k in e + 1 until t.size) {
            val px = p[k]
            if (!(px > 0.0)) continue
            val g = px / entry - 1.0
            if (px > peak) peak = px
            while (nextRung < rungs.size && px >= entry * rungs[nextRung].first) {
                val sell = minOf(rungs[nextRung].second, held); banked += sell * g; held -= sell; nextRung++; tookHalf = true
            }
            val stopHit = !tookHalf && px <= entry * (1.0 - rule.stopPct / 100.0)
            val trailHit = tookHalf && px <= peak * (1.0 - trailPct / 100.0)
            val timeHit = !tookHalf && t[k] - t[e] >= TIME_STOP_MS && peak < entry * 1.2
            if (stopHit || trailHit || timeHit || held <= 1e-9) { banked += held * g; held = 0.0; break }
        }
        if (held > 0.0) banked += held * (p.last() / entry - 1.0)
        return banked * 100.0 - (if (costPct.isFinite()) costPct else 0.0)
    }

    /** Pure: each rule's result over the launches, best by mean - SE with n >= MIN_N first. */
    private fun evaluate8744(launches: List<Triple<LongArray, DoubleArray, BooleanArray>>, solUsd: Double, costPct: Double,
                     rules: List<Rule> = GRID): List<Result> = rules.mapNotNull { r ->
        val v = launches.mapNotNull { (t, p, b) -> simulate8744(t, p, b, r, solUsd, costPct) }
        if (v.size < 2) return@mapNotNull null
        val m = v.average(); val sd = kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        Result(r, v.size, m, sd / kotlin.math.sqrt(v.size.toDouble()), v.count { it > 0.0 }.toDouble() / v.size,
            v.count { it >= 100.0 }.toDouble() / v.size, v.count { it >= 900.0 }.toDouble() / v.size)
    }.sortedWith(compareByDescending<Result> { it.n >= MIN_N }.thenByDescending { it.mean - it.se })

    @Volatile private var last: List<Result> = emptyList()
    @Volatile private var launches = 0
    @Volatile private var lastRunMs = 0L
    @Volatile private var status = "not-run"
    private val running = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "launch-replay-8744").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** The report's call: re-run in the background when due; never blocks the caller. */
    private fun maybeRun8744(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastRunMs < RUN_EVERY_MS || !running.compareAndSet(false, true)) return
        lastRunMs = nowMs
        exec.execute {
            try {
                val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                if (!(solUsd > 0.0)) { status = "no SOL price"; return@execute }
                val set = ArrayList<Triple<LongArray, DoubleArray, BooleanArray>>()
                LaunchHour8628.forEachLaunch8744(nowMs, MAX_LAUNCHES) { _, t, p, b -> set.add(Triple(t, p, b)) }
                launches = set.size
                last = evaluate8744(set, solUsd, FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 + FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715)
                status = "ran launches=${set.size} solUsd=${"%.0f".format(solUsd)}"
            } catch (t: Throwable) { status = "failed:${t.javaClass.simpleName}" } finally { running.set(false) }
        }
    }

    fun statusLine8744(): String {
        maybeRun8744()
        val top = last.take(6).joinToString(" · ") { r ->
            "cap\$${(r.rule.capUsd / 1000).toInt()}k buys>=${r.rule.minBuys} tp${r.rule.takeX.toInt()}x stop${r.rule.stopPct.toInt()}% ${r.rule.ladder} " +
                "n${r.n} ${"%+.1f".format(r.mean)}±${"%.1f".format(r.se)}% win${(r.win * 100).toInt()}% 2x+${(r.twoX * 100).toInt()}% 10x+${"%.1f".format(r.tenX * 100)}%"
        }.ifBlank { "-" }
        return "launchReplay8744[$status launches=$launches rules=${GRID.size} best{$top} " +
            "rule=entry on cap+buys/60s after ${DELAY_MS / 1000}s, half at tp, trail ${TRAIL_PCT.toInt()}%, stop, 10m time stop, cost in]"
    }
}
