package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8756 §SMART_MONEY_GRADED — owner: "do all 3 ... go to the internet search what a bot needs to capture these big wins".
 *
 * What the research says a runner-catching bot needs, and what this bot did instead:
 *  - the pump.fun graduation study (arxiv 2602.14860): successful early traders give an uplift; bots and snipers do not;
 *  - mobula's sniper / bundler almanac: snipers buy in the first 0-3 blocks, bundlers in the same block from one funder,
 *    and a launch whose early supply sits with them dumps on whoever follows;
 *  - the smart-money guides: follow wallets with a RECORD, and two of them converging within seconds is the signal.
 * The bot's FOLLOW_SIZE family followed any large buy in a coin's first five minutes — snipers and bundlers by
 * construction — and its paper stops closed at -60.8 % after 1.9 minutes. Its "smart wallets" (SmartMoneyFeed6394) were
 * whichever whales CopyTradeEngine saw, never graded.
 *
 * This grades every wallet the market-wide pump.fun stream decodes, on the launch paths LaunchHour8628 keeps (48 h, wallet
 * per print from this build), with the launch replay's own honesty: live prints only, spikes dropped, the follower enters
 * where the curve stood after the bot's measured latency, exits on the DOCTRINE ladder (stop 30 % under entry, out at 10
 * minutes if it never made +20 %), cost at the live size. A wallet's record is what FOLLOWING it returned, not what it made.
 *
 * Three time-ordered sets keep the grade honest: wallets are fitted on the oldest 60 % of launches, the signal's variant
 * ([VARIANTS]: how much record a wallet needs, single wallet or two converging within [CONVERGE_MS], with or without the
 * sniper-share cap) is chosen on the next 20 %, and the chosen variant is graded on the newest 20 % with wallets fitted on
 * the 80 % before it. It is ARMED only when that untouched test record clears the sniper's bar (n >= [PROVEN_MIN_N],
 * ex-top-3 mean less its SE > 0). Armed, a proven buy puts the coin on the watchlist and its record is admission evidence
 * (CandidateAdmission8136 market cell); unarmed, it fires in shadow and is counted. Live safety, sellability, balance and
 * economics doors are untouched. [provenBuyers8756] replaces the ungraded whale count for the paper families
 * (FOLLOW_SIZE, WALLET_CLUSTER).
 */
object SmartMoney8756 {
    enum class Mode { SINGLE, CONVERGE }
    data class Variant(val minWalletN: Int, val z: Double, val mode: Mode, val maxSniperShare: Double) {
        val id: String get() = "SMART n>=$minWalletN z$z $mode" + (if (maxSniperShare < 1.0) " sniper<=${(maxSniperShare * 100).toInt()}%" else "")
    }
    data class Graded(val variant: Variant, val n: Int, val mean: Double, val median: Double, val exTop3: Double, val exTop3Se: Double,
                      val win: Double, val twoX: Double, val tenX: Double)

    internal class Acc {
        var n = 0; var mean = 0.0; var m2 = 0.0; var snipes = 0
        fun add(x: Double) { n++; val d = x - mean; mean += d / n; m2 += d * (x - mean) }
        val se: Double get() = if (n >= 2) kotlin.math.sqrt(m2 / (n - 1)) / kotlin.math.sqrt(n.toDouble()) else Double.NaN
    }

    private const val SNIPER_MS = 3_000L
    private const val CONVERGE_MS = 60_000L
    private const val FOLLOW_WINDOW_MS = 30L * 60_000L
    private const val STOP_FRACTION = 0.30
    private const val TIME_STOP_MS = 10L * 60_000L
    private const val MIN_BUY_SOL = 0.05
    private const val MIN_LAUNCHES = 300
    private const val MIN_N_VALID = 30
    private const val PROVEN_MIN_N = 100
    private const val MAX_LIVE_WALLETS = 5_000
    private const val EVIDENCE_TTL_MS = 3L * 60_000L
    private const val MAX_TRACKED = 2_000
    private const val FILE = "smart_money_8756.json"

    val VARIANTS: List<Variant> = buildList {
        for (n in listOf(3, 5, 8)) for (z in listOf(0.0, 1.0)) for (m in Mode.values()) for (s in listOf(1.0, 0.3)) add(Variant(n, z, m, s))
    }

    // ── pure grading ──

    /** Pure: what a follower of the buy at print [i] of [x] returned: entered [delayMs] later, DOCTRINE exits. */
    internal fun followNet8756(x: Path8748, i: Int, delayMs: Long, costPct: Double): Double? {
        if (i !in x.t.indices) return null
        val e = LaunchReplay8744.entryIndex8754(x, i, delayMs)
        val entry = x.p[e]
        if (!(entry > 0.0)) return null
        return LaunchReplay8744.exitNet8754(x, e, Ladder.DOCTRINE, 2.0, entry * (1.0 - STOP_FRACTION), TIME_STOP_MS, costPct)
    }

    /** Pure: each wallet's first qualifying buy in [x] (live, sized >= [MIN_BUY_SOL], inside the follow window) as print index. */
    internal fun firstBuys8756(x: Path8748): Map<Int, Int> {
        val w = x.wallet ?: return emptyMap()
        val v = x.sol
        val out = LinkedHashMap<Int, Int>()
        for (i in x.t.indices) {
            if (x.t[i] > FOLLOW_WINDOW_MS) break
            val wallet = w[i]
            if (!x.buy[i] || !x.live[i] || wallet < 0 || (v != null && v[i] < MIN_BUY_SOL)) continue
            out.putIfAbsent(wallet, i)
        }
        return out
    }

    /** Pure: the share of buy SOL bought inside the first [SNIPER_MS] of [x], among all buy SOL up to print [upto]. */
    internal fun sniperShare8756(x: Path8748, upto: Int): Double {
        val v = x.sol ?: return Double.NaN
        var early = 0.0; var all = 0.0
        for (i in 0..minOf(upto, x.t.size - 1)) if (x.buy[i]) { all += v[i]; if (x.t[i] < SNIPER_MS) early += v[i] }
        return if (all > 0.0) early / all else Double.NaN
    }

    /** Pure: every wallet's follower record over [launches]. */
    internal fun fit8756(launches: List<Path8748>, delayMs: Long, costPct: Double): HashMap<Int, Acc> {
        val acc = HashMap<Int, Acc>()
        for (x in launches) for ((w, i) in firstBuys8756(x)) {
            val net = followNet8756(x, i, delayMs, costPct) ?: continue
            val a = acc.getOrPut(w) { Acc() }
            a.add(net)
            if (x.t[i] < SNIPER_MS) a.snipes++
        }
        return acc
    }

    /** Pure: the wallets whose follower record clears [v]: n >= minWalletN, mean - z SE > 0, and not a sniper (most first buys <3 s). */
    internal fun provenSet8756(acc: Map<Int, Acc>, v: Variant): Set<Int> = acc.entries.filter { (_, a) ->
        a.n >= v.minWalletN && a.snipes * 2 < a.n && a.mean.isFinite() &&
            (if (v.z <= 0.0) a.mean > 0.0 else a.se.isFinite() && a.mean - v.z * a.se > 0.0)
    }.mapTo(HashSet()) { it.key }

    /** Pure: the print where [v] fires on [x] for wallets [proven] (SINGLE: the first proven buy; CONVERGE: a second distinct one within [CONVERGE_MS]); -1 = none. */
    internal fun fireIndex8756(x: Path8748, proven: Set<Int>, v: Variant): Int {
        if (proven.isEmpty()) return -1
        val buys = firstBuys8756(x).entries.filter { it.key in proven }.map { it.value }.sorted()
        for ((k, i) in buys.withIndex()) {
            val fires = when (v.mode) {
                Mode.SINGLE -> true
                Mode.CONVERGE -> k >= 1 && x.t[i] - x.t[buys[k - 1]] <= CONVERGE_MS
            }
            if (!fires) continue
            if (v.maxSniperShare < 1.0) { val s = sniperShare8756(x, i); if (s.isFinite() && s > v.maxSniperShare) continue }
            return i
        }
        return -1
    }

    /** Pure: [v]'s record on [launches] with [proven] wallets. */
    internal fun grade8756(v: Variant, launches: List<Path8748>, proven: Set<Int>, delayMs: Long, costPct: Double): Graded? {
        val vals = launches.mapNotNull { x -> fireIndex8756(x, proven, v).takeIf { it >= 0 }?.let { followNet8756(x, it, delayMs, costPct) } }
        return stats8756(v, vals)
    }

    /** Pure: n, mean, median, the mean and SE without the three best, and the win / 2x / 10x shares of [vals]. */
    internal fun stats8756(v: Variant, vals: List<Double>): Graded? {
        if (vals.size < 2) return null
        val s = vals.sorted()
        val ex = s.dropLast(3)
        val m3 = if (ex.size >= 2) ex.average() else Double.NaN
        val se3 = if (ex.size >= 2) kotlin.math.sqrt(ex.sumOf { (it - m3) * (it - m3) } / (ex.size - 1)) / kotlin.math.sqrt(ex.size.toDouble()) else Double.NaN
        val med = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        fun share(f: (Double) -> Boolean) = s.count(f).toDouble() / s.size
        return Graded(v, s.size, s.average(), med, m3, se3, share { it > 0.0 }, share { it >= 100.0 }, share { it >= 900.0 })
    }

    /** Pure: may [g] be traded? The sniper's bar on the untouched test set. */
    internal fun armed8756(g: Graded?): Boolean =
        g != null && g.n >= PROVEN_MIN_N && g.exTop3.isFinite() && g.exTop3Se.isFinite() && g.exTop3 - g.exTop3Se > 0.0

    /** Pure: the variant to test — best on the validation launches by ex-top-3 mean less its SE, n >= [MIN_N_VALID]. */
    internal fun choose8756(valid: List<Graded>): Graded? = valid.filter { it.n >= MIN_N_VALID && it.exTop3.isFinite() && it.exTop3Se.isFinite() }
        .maxByOrNull { it.exTop3 - it.exTop3Se }

    // ── the background run (LaunchReplay8744's worker, same paths) ──

    @Volatile private var chosen: Variant? = null
    @Volatile private var test: Graded? = null
    @Volatile private var armed = false
    @Volatile private var live: Set<String> = emptySet()
    @Volatile private var status = "not-run"
    @Volatile private var restored = false

    internal fun evaluate8756(set: List<Path8748>, walletNames: List<String>, costPct: Double, needDelayMs: Long) {
        restore8756()
        val paths = set.filter { it.bornMs > 0L && it.wallet?.any { w -> w >= 0 } == true }.sortedBy { it.bornMs }
        if (paths.size < MIN_LAUNCHES) { status = "waiting launchesWithWallets=${paths.size}/$MIN_LAUNCHES (wallets are stored from 8756)"; return }
        val delay = maxOf(5_000L, needDelayMs)
        val a = paths.size * 6 / 10; val b = paths.size * 8 / 10
        val train = paths.subList(0, a); val valid = paths.subList(a, b); val testSet = paths.subList(b, paths.size)
        val accTrain = fit8756(train, delay, costPct)
        val validGrades = VARIANTS.mapNotNull { v -> grade8756(v, valid, provenSet8756(accTrain, v), delay, costPct) }
        val pick = choose8756(validGrades)
        if (pick == null) { status = "ran launches=${paths.size} wallets=${accTrain.size} no variant with n>=$MIN_N_VALID on validation"; chosen = null; armed = false; return }
        val accTrainValid = fit8756(paths.subList(0, b), delay, costPct)
        val t = grade8756(pick.variant, testSet, provenSet8756(accTrainValid, pick.variant), delay, costPct)
        val accAll = fit8756(paths, delay, costPct)
        val liveSet = provenSet8756(accAll, pick.variant).sortedByDescending { w -> accAll[w]?.let { it.mean - (if (it.se.isFinite()) it.se else 0.0) } ?: -1e18 }
            .take(MAX_LIVE_WALLETS).mapNotNullTo(HashSet()) { walletNames.getOrNull(it) }
        chosen = pick.variant; test = t; armed = armed8756(t); live = liveSet
        status = "ran launches=${paths.size} wallets=${accAll.size} proven=${liveSet.size} delay=${delay / 1000}s valid{${line(pick)}}"
        save8756()
    }

    // ── live ──

    private class MintSt { val buys = ArrayDeque<Pair<Long, String>>(); var fired = false; var lastSeen = 0L }
    data class Fire(val mint: String, val atMs: Long, val wallets: Int, val armed: Boolean)

    private val st = ConcurrentHashMap<String, MintSt>()
    private val fires = ConcurrentHashMap<String, Fire>()
    private val provenBuys = AtomicLong(); private val shadowFires = AtomicLong(); private val armedFires = AtomicLong()
    private val watchAdds = AtomicLong(); private val served = AtomicLong()

    /** TradeTape8100.launchEvidence8750: a witnessed live print of [mint] by [wallet]. Cheap for an unproven wallet. */
    fun onPrint8756(mint: String, wallet: String, eventMs: Long, isBuy: Boolean, sol: Double, recvMs: Long) {
        if (!isBuy || !(sol >= MIN_BUY_SOL)) return
        restore8756()
        val set = live
        if (set.isEmpty() || wallet !in set) return
        if (recvMs - eventMs !in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748) return
        provenBuys.incrementAndGet()
        val v = chosen ?: return
        if (st.size >= MAX_TRACKED) st.entries.removeIf { recvMs - it.value.lastSeen > 10L * 60_000L }
        val s = st.computeIfAbsent(mint) { MintSt() }
        val fire = synchronized(s) {
            s.lastSeen = recvMs
            if (s.buys.none { it.second == wallet }) s.buys.addLast(eventMs to wallet)
            while (s.buys.isNotEmpty() && s.buys.first().first < eventMs - 5L * 60_000L) s.buys.removeFirst()
            if (s.fired) return
            val born = try { CanonicalTokenBirthTime7440.peekBirthMs8418(mint, eventMs) } catch (_: Throwable) { null } ?: return
            if (eventMs - born !in 0L..FOLLOW_WINDOW_MS) return
            val n = s.buys.count { eventMs - it.first in 0L..CONVERGE_MS }
            if (v.mode == Mode.CONVERGE && n < 2) return
            if (v.maxSniperShare < 1.0) {
                val sh = liveSniperShare8756(mint, born, recvMs)
                if (sh.isFinite() && sh > v.maxSniperShare) return
            }
            s.fired = true
            Fire(mint, recvMs, n, armed)
        }
        onFire(fire)
    }

    /** The live sniper share from the tape (only read when a proven wallet's buy could fire). */
    private fun liveSniperShare8756(mint: String, born: Long, nowMs: Long): Double {
        val prints = try { TradeTape8100.prints8128(mint, nowMs, (nowMs - born + 5_000L).coerceAtLeast(5_000L)) } catch (_: Throwable) { return Double.NaN }
        var early = 0.0; var all = 0.0
        for (p in prints) if (p.isBuy && !p.priceOnly) { all += p.sol; if (p.atMs - born < SNIPER_MS) early += p.sol }
        return if (all > 0.0) early / all else Double.NaN
    }

    private fun onFire(f: Fire) {
        if (!f.armed) { shadowFires.incrementAndGet(); try { PipelineHealthCollector.labelInc("SMART_MONEY_SHADOW_FIRE_8756") } catch (_: Throwable) {}; return }
        armedFires.incrementAndGet()
        if (fires.size > 2_000) fires.entries.removeIf { f.atMs - it.value.atMs > EVIDENCE_TTL_MS }
        fires[f.mint] = f
        try { PipelineHealthCollector.labelInc("SMART_MONEY_ARMED_FIRE_8756") } catch (_: Throwable) {}
        try { com.lifecyclebot.network.HeliusCurveTape8354.ask8354(f.mint, f.atMs) } catch (_: Throwable) {}
        try {
            val added = com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(f.mint, f.mint.take(6), "SMART_MONEY_8756")
            if (added.added) watchAdds.incrementAndGet()
        } catch (_: Throwable) {}
    }

    /** CandidateAdmission8136: the armed fire on [mint] as market-cell evidence (the test-set record), or null. */
    fun evidence8756(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = fires[mint]?.takeIf { it.armed && nowMs - it.atMs in 0L..EVIDENCE_TTL_MS } ?: return null
        val g = test ?: return null
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136("SMART_MONEY_8756", g.variant.id, g.n, g.exTop3, g.exTop3Se, runnerRate = g.tenX, netOfCost = true)
    }

    /** MemeStrategies8546 / FlowRead8546: distinct PROVEN wallets that bought [mint] in the last [windowMs]. */
    fun provenBuyers8756(mint: String, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val s = st[mint] ?: return 0
        return synchronized(s) { s.buys.count { nowMs - it.first in 0L..windowMs } }
    }

    // ── persistence (the live wallets survive a restart; the record is re-graded every run) ──

    private fun file(): File? = AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) }

    private fun save8756() {
        try {
            val f = file() ?: return
            val v = chosen ?: return
            val o = JSONObject().put("minN", v.minWalletN).put("z", v.z).put("mode", v.mode.name).put("sniper", v.maxSniperShare)
                .put("armed", armed).put("wallets", JSONArray(live.toList()))
            test?.let { o.put("test", JSONArray().put(it.n).put(it.mean).put(it.median).put(it.exTop3).put(it.exTop3Se).put(it.win).put(it.twoX).put(it.tenX)) }
            val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    private fun restore8756() {
        if (restored) return
        restored = true
        try {
            val f = file()?.takeIf { it.exists() } ?: return
            val o = JSONObject(f.readText())
            val v = Variant(o.getInt("minN"), o.getDouble("z"), Mode.valueOf(o.getString("mode")), o.getDouble("sniper"))
            val w = o.getJSONArray("wallets"); val set = HashSet<String>(w.length() * 2)
            for (i in 0 until w.length()) set.add(w.getString(i))
            val t = o.optJSONArray("test")?.let { a ->
                Graded(v, a.getInt(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5), a.getDouble(6), a.getDouble(7))
            }
            chosen = v; test = t; armed = armed8756(t); live = set
            status = "restored proven=${set.size}"
        } catch (_: Throwable) {}
    }

    private fun line(g: Graded): String =
        "${g.variant.id} n${g.n} med${"%+.0f".format(g.median)}% exTop3${"%+.1f".format(g.exTop3)}±${"%.1f".format(g.exTop3Se)}% " +
            "win${(g.win * 100).toInt()}% 2x+${(g.twoX * 100).toInt()}% 10x+${"%.1f".format(g.tenX * 100)}%"

    fun statusLine8756(): String = "smartMoney8756[$status ${if (armed) "ARMED" else "SHADOW"} test{${test?.let { line(it) } ?: "-"}} " +
        "liveWallets=${live.size} provenBuys=${provenBuys.get()} fires{armed=${armedFires.get()} shadow=${shadowFires.get()}} " +
        "watchAdds=${watchAdds.get()} evidenceServed=${served.get()} rule=wallets fitted on the oldest 60% of launches, variant chosen on the " +
        "next 20%, graded on the newest 20% (follower enters after the bot's latency, DOCTRINE exits, cost); armed = test n>=$PROVEN_MIN_N, ex-top-3 mean - SE > 0]"

    internal fun resetForTest8756() { st.clear(); fires.clear(); chosen = null; test = null; armed = false; live = emptySet(); restored = true }
    internal fun setLiveForTest8756(v: Variant, wallets: Set<String>, g: Graded?) { chosen = v; live = wallets; test = g; armed = armed8756(g); restored = true }
}
