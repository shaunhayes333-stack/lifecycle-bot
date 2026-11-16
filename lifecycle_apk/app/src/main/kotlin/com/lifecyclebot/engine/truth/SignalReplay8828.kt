package com.lifecyclebot.engine.truth

/**
 * V5.0.8828 §THE_OWNERS_SIGNALS_ARE_GRADED — the owner's 300-signal book (SignalBible300) reached the strategy lab as text
 * (8826): nothing measured a single signal. Offline on the owner's launch export (4,602 launches, 10-second bars, DOCTRINE
 * exit, 35 % stop, 3.5 % cost) twelve of them, as near as bars allow, all lost more than buying every launch's first bar
 * blind once each one's five best trades were set aside (blind -6.4 %; S052 -8.7 %, S012 -8.9 %, S011 -9.5 %, S005 -9.8 %,
 * ... S009 -19.3 %). That is one export and approximate definitions; the phone has every launch's prints with their wallets.
 *
 * Every LaunchReplay8744 run hands its launch paths here. Each path's prints (spikes removed, live flags kept) are fed through
 * a SignalTape8828 — the same object the live tape feeds — and each signal's first live turn-on is entered as the replay
 * enters: at the curve's state the bot's required latency later (LaunchSniper8748.requiredDelayMs8749), out on the bot's
 * DOCTRINE ladder with a hard stop of [STOPS] % until the first rung, every sale filled after the sell latency, the
 * round trip charged (LaunchReplay8744.exitNet8754). Per signal and stop: n, mean, SE, median, the mean and SE without the
 * three best, the win and 10x shares, and the mean of each half of the launches by birth. A signal is PROVEN for the sniper
 * when n >= [PROVEN_MIN_N], the ex-top-3 mean clears its own SE and both halves are positive; the best unproven one with
 * n >= [SHADOW_MIN_N] is watched in shadow. A proven signal trades only through admission and every safety door, exactly
 * like a proven launch rule.
 */
object SignalReplay8828 {
    val STOPS: DoubleArray = doubleArrayOf(20.0, 35.0)
    internal const val PROVEN_MIN_N = 100
    internal const val SHADOW_MIN_N = 50

    data class Rec(val id: String, val stopPct: Double, val n: Int, val mean: Double, val se: Double, val median: Double,
                   val exTop3: Double, val exTop3Se: Double, val win: Double, val tenX: Double, val halfA: Double, val halfB: Double,
                   val capMedUsd: Double, val wallet: Boolean) {
        val key: String get() = "$id stop${stopPct.toInt()}%"
        val proven: Boolean get() = n >= PROVEN_MIN_N && exTop3.isFinite() && exTop3Se.isFinite() && exTop3 - exTop3Se > 0.0 &&
            halfA > 0.0 && halfB > 0.0
    }

    /** One graded entry: the launch's birth, the net %, the curve cap (USD) when the signal turned on. */
    internal class Entry(val bornMs: Long, val net: Double, val capUsd: Double)

    /** Pure: the record of [id] at [stopPct] from its entries (null under 2). */
    internal fun stats8828(id: String, stopPct: Double, v: List<Entry>): Rec? {
        if (v.size < 2) return null
        val nets = v.map { it.net }.sorted()
        fun meanSe(x: List<Double>): Pair<Double, Double> {
            if (x.size < 2) return Double.NaN to Double.NaN
            val m = x.average(); val sd = kotlin.math.sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
            return m to sd / kotlin.math.sqrt(x.size.toDouble())
        }
        val (m, se) = meanSe(nets)
        val (m3, se3) = meanSe(nets.dropLast(3))
        val med = if (nets.size % 2 == 1) nets[nets.size / 2] else (nets[nets.size / 2 - 1] + nets[nets.size / 2]) / 2.0
        val byBirth = v.sortedBy { it.bornMs }
        val half = byBirth.size / 2
        val a = byBirth.take(half).map { it.net }.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
        val b = byBirth.drop(half).map { it.net }.average()
        val caps = v.map { it.capUsd }.filter { it.isFinite() }.sorted()
        return Rec(id, stopPct, nets.size, m, se, med, m3, se3, nets.count { it > 0.0 }.toDouble() / nets.size,
            nets.count { it >= 900.0 }.toDouble() / nets.size, a, b, if (caps.isEmpty()) Double.NaN else caps[caps.size / 2],
            id in SignalTape8828.WALLET_IDS)
    }

    /** Pure: every signal of every path entered and unwound as the replay does; the records, best first. */
    internal fun grade8828(paths: List<LaunchReplay8744.Path8748>, solUsd: Double, costPct: Double, delayMs: Long): List<Rec> {
        val got = HashMap<String, ArrayList<Entry>>()
        for (x in paths) {
            if (x.t.isEmpty()) continue
            val tape = SignalTape8828()
            for (i in x.t.indices) {
                val w = x.wallet?.getOrNull(i)?.toLong() ?: -1L
                val on = tape.onPrint(x.t[i], x.p[i], x.buy[i], x.sol?.getOrNull(i) ?: 0.0, w, x.live[i])
                if (on.isEmpty()) continue
                val e = LaunchReplay8744.entryIndex8754(x, i, delayMs)
                val entry = x.p[e]
                if (!(entry > 0.0)) continue
                val cap = x.p[i] * 1.0e9 * solUsd
                for (id in on) for (st in STOPS) {
                    val net = LaunchReplay8744.exitNet8754(x, e, LaunchReplay8744.Ladder.DOCTRINE, 2.0, entry * (1.0 - st / 100.0),
                        LaunchReplay8744.TIME_STOP_MS_8828, costPct) ?: continue
                    got.getOrPut("$id|$st") { ArrayList() }.add(Entry(x.bornMs, net, cap))
                }
            }
        }
        return got.mapNotNull { (k, v) -> stats8828(k.substringBefore('|'), k.substringAfter('|').toDouble(), v) }
            .sortedWith(compareByDescending<Rec> { it.n >= SHADOW_MIN_N }.thenByDescending { (it.exTop3 - it.exTop3Se).takeIf { d -> d.isFinite() } ?: -1e18 })
    }

    @Volatile private var records: List<Rec> = emptyList()
    @Volatile private var proven: Rec? = null
    @Volatile private var shadow: Rec? = null
    @Volatile private var status = "not-run"

    /** LaunchReplay8744's run: the same paths, SOL price, cost and required latency. */
    internal fun evaluate8828(paths: List<LaunchReplay8744.Path8748>, solUsd: Double, costPct: Double, delayMs: Long) {
        val t0 = System.currentTimeMillis()
        val r = grade8828(paths, solUsd, costPct, delayMs)
        records = r
        proven = r.filter { it.proven }.maxByOrNull { it.exTop3 - it.exTop3Se }
        shadow = r.firstOrNull { it.n >= SHADOW_MIN_N && !it.proven }
        status = "ran launches=${paths.size} graded=${r.size} delay=${delayMs / 1000}s cost=${"%.1f".format(costPct)}% ms=${System.currentTimeMillis() - t0}"
    }

    /** LaunchSniper8748: the signal it may trade (proven) and the one it watches (shadow). */
    fun proven8828(): Rec? = proven
    fun shadow8828(): Rec? = shadow

    /** The live wallet key: a named buyer's 63-bit hash, or -1 when the feed named none. */
    fun walletKey8828(wallet: String): Long =
        if (wallet.isBlank()) -1L else com.lifecyclebot.engine.chart.OwnBars8816.hash8816(wallet) and Long.MAX_VALUE

    private fun fmt(r: Rec) = "${r.key} n${r.n} exTop3${"%+.1f".format(r.exTop3)}±${"%.1f".format(r.exTop3Se)}% med${"%+.0f".format(r.median)}% " +
        "halves${"%+.1f".format(r.halfA)}/${"%+.1f".format(r.halfB)} win${(r.win * 100).toInt()}% 10x${"%.1f".format(r.tenX * 100)}% cap\$${"%.0f".format(r.capMedUsd)}"

    /** The strategy lab's prompt: the owner's signals as measured on this phone's launches (empty before the first run). */
    fun labLine8828(): String {
        val r = records.filter { it.n >= SHADOW_MIN_N && it.stopPct == 35.0 }
        if (r.isEmpty()) return ""
        return "THE OWNER'S SIGNALS AS MEASURED ON THIS PHONE'S LAUNCHES (signal turned on -> entered after the bot's latency, DOCTRINE exit, " +
            "35 % stop, net of cost; exTop3 = mean without the 3 best; halves = older/newer launches):\n" +
            r.joinToString("\n") { "  ${fmt(it)}${if (it.proven) " PROVEN" else ""}" }
    }

    fun statusLine8828(): String {
        val p = proven; val s = shadow
        val top = records.take(6).joinToString(" | ") { fmt(it) }.ifBlank { "-" }
        val bottom = records.filter { it.n >= SHADOW_MIN_N }.takeLast(3).joinToString(" | ") { fmt(it) }.ifBlank { "-" }
        return "signalReplay8828[$status signals=${SignalTape8828.IDS.size} proven{${p?.let { fmt(it) } ?: "none"}} shadow{${s?.let { fmt(it) } ?: "none"}} " +
            "best{$top} worst{$bottom} rule=the owner's signals on the stored launch hours, entered at the bot's latency, DOCTRINE exit; " +
            "proven = n>=$PROVEN_MIN_N, ex-top-3 mean - SE > 0, both halves positive]"
    }
}
