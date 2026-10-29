package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * V5.0.7972 — the specialist miner: hundreds of proven specialists per lane, found
 * by the labels instead of written by hand.
 *
 * Every decision the bot makes (admitted or refused) is already followed to its
 * 5-minute net label (ForwardReturnLabeler7731, ~3,400 a session). At the decision
 * this captures ~20 discrete facts about the token (market-cap band, age, buy share,
 * 5m / 1h move, liquidity per mcap, holder velocity, volume turnover, the launch
 * tape's buyers/min and largest buyer, the dev's record, 1m structure, the candle
 * colour read, regime, second wave, expert/callout entry, safety tier, source).
 * When the label lands it is booked to every pair and triple of those facts within
 * the lane: "MOONSHOT | dev=WIN | hv=1_5 | mc=10_30K".
 *
 * A combination becomes a live SPECIALIST when its own record proves the owner's
 * bar with room for the thousands of combinations being searched:
 *   n >= 25, mean - 2 SE >= +15% net, win rate >= 25%   (labels are net of cost).
 * From then on it keeps a separate out-of-sample record (labels after promotion):
 * it is CONFIRMED once that record is positive after one SE, and DEMOTED the moment
 * 10+ post-promotion labels say mean + SE < 0 — its discovery record is wiped so it
 * must re-prove on new data only.
 *
 * LiveEdgeGate7877 admits a live candidate that matches a promoted specialist of
 * its lane (hard safety and Mayhem still refuse first). A specialist whose labels
 * run (>= 20% of them +50% gross) also gets the runner hold.
 *
 * Dev reputation (new fact): each dev wallet's coins' 5-minute labels, so a dev
 * whose launches run is told apart from a serial rugger (NEW / WIN / LOSE / MIXED).
 */
object SpecialistMiner7972 {

    private const val MIN_N = 25
    private const val BAR_PCT = 15.0
    private const val MIN_WIN = 0.25
    private const val DEMOTE_N = 10
    private const val MAX_KEYS = 40_000
    // V5.0.8420 — was 300: +500% and +600% runners booked as the same +300%, so the miner could not tell a 6x from a 4x and
    // every runner combination's mean was cut to the cap (owner 8072: no win caps). The cap is now only a corrupt-unit
    // guard (a 50x net return); real runner magnitude reaches the books.
    private const val NET_CAP = 5_000.0
    private const val RUNNER_GROSS = 50.0
    private const val STAT_WINDOW_8014 = 300

    class Stat(var n: Int = 0, var sum: Double = 0.0, var sumSq: Double = 0.0, var wins: Int = 0, var runners: Int = 0) {
        /** V5.0.8144 — last label booked here (eviction rank tie-break; session-only, not persisted). */
        @Volatile var touchedAtMs8144: Long = 0L
        /** Returns this label's booking stamp (V5.0.8418): the decay coordinate a later revision reads its weight against. */
        /** V5.0.8420 — carried fractions of the faded win / runner counts (session-scoped; at most one count). */
        @Volatile var winCarry8420 = 0.0
        @Volatile var runnerCarry8420 = 0.0
        fun add(net: Double, gross: Double): Double {
            val stamp = logScale8418
            val y = net.coerceIn(-100.0, NET_CAP)
            n++; sum += y; sumSq += y * y
            if (y > 0.0) wins++
            if (gross.isFinite() && gross >= RUNNER_GROSS) runners++
            // V5.0.8014 — fresh edge: a combination's record is its most recent ~[STAT_WINDOW_8014] outcomes.
            if (n > STAT_WINDOW_8014) {
                val k = STAT_WINDOW_8014.toDouble() / n
                decayed8410 = true   // V5.0.8410 — this record has forgotten; a full-value revision would over-correct it
                logScale8418 += kotlin.math.ln(k)   // V5.0.8418 — every booked label now owns k of its weight
                n = STAT_WINDOW_8014; sum *= k; sumSq *= k
                // V5.0.8420 — counts fade exactly (round() never let a count below ~150 fall: 150 wins after 3,000 losses).
                DecayCount8420.fade(wins, winCarry8420, k).let { (c, r) -> wins = c.coerceIn(0, n); winCarry8420 = r }
                DecayCount8420.fade(runners, runnerCarry8420, k).let { (c, r) -> runners = c.coerceIn(0, n); runnerCarry8420 = r }
            }
            return stamp
        }
        /** V5.0.8418 — cumulative decay of this record (session-scoped; stamps carry the boot they were taken in). */
        @Volatile var logScale8418 = 0.0
        fun mean(): Double = if (n > 0) sum / n else 0.0
        fun se(): Double = if (n > 1) sqrt(((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
        /** V5.0.8410 — set once the window has faded this record (session + persisted books rebuilt from n==window). */
        @Volatile var decayed8410 = false
        /**
         * V5.0.7997 — a label revised on a later checkpoint replaces its earlier value.
         * V5.0.8410 — only while this record has never forgotten (every booked label still owns weight one, exactly). A
         * combination that has faded keeps its booked value rather than being over-corrected; the skip is counted.
         */
        /**
         * V5.0.8418 §MATURE_COMBINATIONS_REVISE — [weight8418] is the share the booked label still owns
         * (exp(logScale8418 - stamp), from [revisionWeight8418]); the revision applies at that weight, so a mature (faded)
         * combination keeps learning its 15m/30m/1h/4h/24h grades. b79bd844f skipped every revision once the window filled.
         * Without a stamp (null: a pre-8418 or cross-restart observation) the 8410 rule stands: exact only while never faded.
         * A known but non-positive / non-finite weight is skipped (never treated as full weight).
         */
        fun revise(oldNet: Double, newNet: Double, oldGross: Double, newGross: Double, weight8418: Double? = null) {
            if (n <= 0 || !oldNet.isFinite() || !newNet.isFinite()) return
            val w = weight8418 ?: if (decayed8410 || n >= STAT_WINDOW_8014) Double.NaN else 1.0
            if (!(w > 0.0 && w <= 1.0)) { decayedRevisionSkipped8410.incrementAndGet(); return }
            val yo = oldNet.coerceIn(-100.0, NET_CAP); val yn = newNet.coerceIn(-100.0, NET_CAP)
            sum += w * (yn - yo); sumSq = (sumSq + w * (yn * yn - yo * yo)).coerceAtLeast(0.0)
            val dw = (if (yn > 0.0) 1 else 0) - (if (yo > 0.0) 1 else 0)
            if (dw != 0) wins = (wins + kotlin.math.round(w * dw).toInt()).coerceIn(0, n)
            val ro = oldGross.isFinite() && oldGross >= RUNNER_GROSS; val rn = newGross.isFinite() && newGross >= RUNNER_GROSS
            if (ro != rn) runners = (runners + kotlin.math.round(w * (if (rn) 1 else -1)).toInt()).coerceIn(0, n)
        }
    }

    class Spec(val key: String, val promotedAtMs: Long, val disc: Stat, val live: Stat = Stat())

    // ── pure ──

    /** Pure: does a discovery record prove a specialist? */
    fun promotes7972(n: Int, mean: Double, se: Double, wins: Int): Boolean =
        n >= MIN_N && mean.isFinite() && se.isFinite() && mean - 2.0 * se >= BAR_PCT && wins >= MIN_WIN * n

    /** Pure: has the out-of-sample record proven the specialist wrong? */
    fun demotes7972(n: Int, mean: Double, se: Double): Boolean = n >= DEMOTE_N && mean.isFinite() && se.isFinite() && mean + se < 0.0

    /** The facts triples are built from (pairs use every fact): keeps the key table phone-sized. */
    private val CORE = setOf("mc", "age", "bp", "st", "hv", "dev", "bpm", "c5", "grad", "mh")

    /**
     * Pure: every pair of [facts], and every triple of its CORE facts (sorted, so one
     * combination has one key), prefixed by the lane.
     */
    fun combos7972(lane: String, facts: List<String>): List<String> {
        val f = facts.distinct().sorted()
        val core = f.filter { it.substringBefore('=') in CORE }
        val out = ArrayList<String>(f.size * f.size / 2 + core.size * core.size * core.size / 6)
        for (i in f.indices) for (j in i + 1 until f.size) out += "$lane|${f[i]}|${f[j]}"
        for (i in core.indices) for (j in i + 1 until core.size) for (k in j + 1 until core.size) out += "$lane|${core[i]}|${core[j]}|${core[k]}"
        return out
    }

    private fun band(v: Double, edges: DoubleArray, names: Array<String>): String {
        if (!v.isFinite()) return "NA"
        for (i in edges.indices) if (v < edges[i]) return names[i]
        return names.last()
    }

    /** Pure: the dev-record bin. */
    fun devBin7972(n: Int, mean: Double): String = when {
        n <= 0 -> "NEW"
        n < 2 -> "ONE"
        mean >= 15.0 -> "WIN"
        mean <= -10.0 -> "LOSE"
        else -> "MIXED"
    }

    /** Pure: holders gained per minute between two snapshots, NaN when unknown. */
    fun holderVelocity7972(nowHolders: Int, thenHolders: Int, minutes: Double): Double =
        if (nowHolders <= 0 || thenHolders <= 0 || !(minutes > 0.0)) Double.NaN else (nowHolders - thenHolders) / minutes

    // ── facts ──

    /** The token's discrete facts now (at a decision). Never throws. */
    fun features7972(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>(22)
        fun put(name: String, v: String) { out += "$name=$v".intern() }
        try {
            val mc = ts.lastMcap
            put("mc", if (mc > 0.0) band(mc, doubleArrayOf(10e3, 30e3, 100e3, 1e6, 10e6, 50e6), arrayOf("LT10K", "10_30K", "30_100K", "100K_1M", "1M_10M", "10M_50M", "GT50M")) else "NA")
            val ageMs = try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null }
            put("age", if (ageMs == null || ageMs < 0) "NA" else band(ageMs / 60_000.0, doubleArrayOf(5.0, 15.0, 60.0, 360.0, 1440.0), arrayOf("LT5M", "5_15M", "15_60M", "1_6H", "6_24H", "GT24H")))
            val src = ts.source.uppercase()
            put("src", when {
                "PUMP_PORTAL" in src || "PUMPPORTAL" in src -> "PP"
                "PUMP" in src -> "PUMP"
                "COPY" in src || "WHALE" in src || "INSIDER" in src || "SMART" in src -> "COPY"
                "DEX" in src || "TRENDING" in src || "BOOST" in src -> "DEX"
                else -> "OTHER"
            })
            put("bp", band(ts.lastBuyPressurePct, doubleArrayOf(40.0, 55.0, 70.0), arrayOf("LT40", "40_55", "55_70", "GE70")))
            put("c5", band(ts.lastPriceChange5m, doubleArrayOf(-10.0, 0.0, 10.0, 30.0), arrayOf("DN10", "DN", "UP", "UP10", "UP30")))
            put("c1h", band(ts.lastPriceChange1h, doubleArrayOf(-20.0, 0.0, 20.0, 100.0), arrayOf("DN20", "DN", "UP", "UP20", "UP100")))
            put("liqmc", if (mc > 0.0 && ts.lastLiquidityUsd > 0.0) band(ts.lastLiquidityUsd / mc, doubleArrayOf(0.05, 0.15, 0.3), arrayOf("LT05", "05_15", "15_30", "GE30")) else "NA")
            val h = ts.history
            val hv = try {
                val last = h.lastOrNull()
                val then = h.lastOrNull { it.ts <= (last?.ts ?: 0L) - 5 * 60_000L && it.holderCount > 0 }
                if (last == null || then == null) Double.NaN
                else holderVelocity7972(last.holderCount, then.holderCount, (last.ts - then.ts) / 60_000.0)
            } catch (_: Throwable) { Double.NaN }
            put("hv", band(hv, doubleArrayOf(0.01, 1.0, 5.0), arrayOf("FLAT", "0_1", "1_5", "GE5")))
            put("hg", if (ts.holderDataResolved) band(ts.holderGrowthRate, doubleArrayOf(0.0, 10.0), arrayOf("NEG", "0_10", "GE10")) else "NA")
            put("top", band(ts.topHolderPct ?: Double.NaN, doubleArrayOf(10.0, 25.0, 50.0), arrayOf("LT10", "10_25", "25_50", "GE50")))
            val volH1 = try { h.lastOrNull()?.volumeH1 ?: Double.NaN } catch (_: Throwable) { Double.NaN }
            put("to", if (mc > 0.0 && volH1.isFinite() && volH1 > 0.0) band(volH1 / mc, doubleArrayOf(0.25, 1.0, 3.0), arrayOf("LT025", "025_1", "1_3", "GE3")) else "NA")
            val tape = try { com.lifecyclebot.engine.market.LaunchTape7921.features(ts.mint, nowMs) } catch (_: Throwable) { null }
            put("bpm", band(tape?.buyersPerMin ?: Double.NaN, doubleArrayOf(2.0, 6.0), arrayOf("LT2", "2_6", "GE6")))
            put("lb", band(tape?.largestBuyerPct ?: Double.NaN, doubleArrayOf(15.0, 30.0), arrayOf("LT15", "15_30", "GE30")))
            put("dev", devFor(ts.mint))
            val (_, r60) = try { com.lifecyclebot.engine.chart.StructureTracker7962.reads7967(ts.mint, nowMs) } catch (_: Throwable) { null to null }
            put("st", when {
                r60 == null -> "NA"
                r60.brokeStructure -> "BROKEN"
                r60.higherLow && r60.hhHl >= 2 -> "HL2"
                r60.higherLow -> "HL"
                else -> "FLAT"
            })
            put("col", if (try { com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.colorBuy7968 } catch (_: Throwable) { null } == true) "BUY" else "NO")
            put("rg", try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "NA" })
            put("sw", if (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.secondWave7974(ts.lastExitTs, ts.lastExitPrice, ts.lastPrice, nowMs, ts.position.isOpen) } catch (_: Throwable) { false }) "Y" else "N")
            put("xp", if (try { com.lifecyclebot.engine.ExpertWallets7962.expertEntryLive7962(ts.mint, nowMs) } catch (_: Throwable) { false }) "Y" else "N")
            put("sf", try { ts.safety.tier.name } catch (_: Throwable) { "NA" })
            // V5.0.7973 — the meme meta: curve progress (graduation play), copycat theme, livestream, King of the Hill.
            val meta = com.lifecyclebot.engine.market.MemeMeta7973
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            put("grad", if (ts.mint.endsWith("pump")) meta.gradBin7973(meta.curveProgress7973(mc, solUsd)) else "NA")
            put("beta", if (try { meta.beta7973(ts.mint, ts.symbol, ts.name, nowMs) } catch (_: Throwable) { false }) "Y" else "N")
            put("live", if (try { meta.live7973(ts.mint, nowMs) || meta.koth7973(ts.mint, nowMs) } catch (_: Throwable) { false }) "Y" else "N")
            // V5.0.7979 — mayhem-mode coin: lets the labels find the GOOD mayhem combinations.
            put("mh", if (try { com.lifecyclebot.engine.MayhemMode7943.isMayhem7979(ts) } catch (_: Throwable) { false }) "Y" else "N")
        } catch (_: Throwable) {}
        return out
    }

    // ── state ──

    private val keys = ConcurrentHashMap<String, Stat>()
    private val promoted = ConcurrentHashMap<String, Spec>()
    private val devs = ConcurrentHashMap<String, Stat>()
    private val devLabelled = ConcurrentHashMap.newKeySet<String>()
    private val labels = AtomicLong(0)
    private val promotions = AtomicLong(0)
    private val demotions = AtomicLong(0)
    private val liveMatches = AtomicLong(0)
    private val sinceSave = AtomicLong(0)
    @Volatile private var file: File? = null
    @Volatile private var loaded = false

    private fun devFor(mint: String): String {
        val dev = try { com.lifecyclebot.engine.OperatorRegistry.getDevWallet(mint) } catch (_: Throwable) { null } ?: return "NA"
        // V5.0.8001 — the hive's record of this dev rides on the local one.
        val hive = try { HiveEdge8000.net8000("DEV|$dev") } catch (_: Throwable) { null }
        val s = devs[dev] ?: if (hive == null) return "NEW" else Stat()
        return synchronized(s) {
            val n = s.n + (hive?.get(0)?.toInt() ?: 0)
            val sum = s.sum + (hive?.get(1) ?: 0.0)
            if (n <= 0) "NEW" else devBin7972(n, sum / n)
        }
    }

    /** V5.0.8001 — HiveEdge8000: this instance's dev reputations ([n, sum, sumSq, wins, runners, 0]). */
    fun hiveDevSnapshot8001(): Map<String, DoubleArray> =
        devs.entries.asSequence().filter { it.value.n >= 1 }.sortedByDescending { it.value.n }.take(3_000)
            .associate { (k, s) -> k to synchronized(s) { doubleArrayOf(s.n.toDouble(), s.sum, s.sumSq, s.wins.toDouble(), s.runners.toDouble(), 0.0) } }

    /** ForwardReturnLabeler7731.attach: where the miner keeps its record. */
    fun attach7972(context: Context) {
        if (file != null) return
        // V5.0.8064 — the fact combinations restart on labels without spike credit.
        val f = File(context.applicationContext.filesDir, "specialists8076.bin")
        file = f
        Thread({ try { load(f) } catch (_: Throwable) {} finally { loaded = true } }, "specialists-7972-load").apply { isDaemon = true }.start()
    }

    /** ForwardReturnLabeler7731 (5-minute label): book [net] to every combination of the decision's facts. */
    fun onLabel7972(lane: String, mint: String, facts: List<String>, net: Double, gross: Double, decidedAtMs: Long): DoubleArray {
        if (facts.size < 3 || !net.isFinite() || lane.startsWith("PLAN")) return DoubleArray(0)
        // V5.0.8418 — one label per decision (lane, coin, decision time), across restarts.
        if (!LearnerAttribution8418.admit8418("SpecialistMiner7972", "$lane|$mint|$decidedAtMs", "LABEL")) return DoubleArray(0)
        val combos8418 = combos7972(lane, facts)
        // V5.0.8418 — [boot, stamp per combination]: a revision applies at the weight its label still owns in each one.
        val stamps8418 = DoubleArray(combos8418.size + 1) { Double.NaN }.also { it[0] = BOOT_8418 }
        labels.incrementAndGet()
        // Dev reputation: one label per coin.
        try {
            val dev = com.lifecyclebot.engine.OperatorRegistry.getDevWallet(mint)
            if (dev != null && devLabelled.add(mint)) {
                val s = devs.getOrPut(dev) { Stat() }
                synchronized(s) { s.add(net, gross) }
                if (devs.size > MAX_DEVS_8144) evictDevs8144(MAX_DEVS_8144)
                if (devLabelled.size > 10_000) devLabelled.clear()
            }
        } catch (_: Throwable) {}
        for ((ci8418, k) in combos8418.withIndex()) {
            promoted[k]?.let { sp ->
                if (decidedAtMs >= sp.promotedAtMs) {
                    val demote = synchronized(sp.live) { sp.live.add(net, gross); demotes7972(sp.live.n, sp.live.mean(), sp.live.se()) }
                    if (demote) {
                        promoted.remove(k); keys.remove(k); demotions.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("SPECIALIST_DEMOTED_7972") } catch (_: Throwable) {}
                        return@let
                    }
                }
            }
            val s = keys[k] ?: run {
                if (keys.size >= residentCap8144()) prune8144(residentCap8144())
                keys.getOrPut(k) { Stat() }
            }
            s.touchedAtMs8144 = decidedAtMs
            val promote = synchronized(s) { stamps8418[ci8418 + 1] = s.add(net, gross); !promoted.containsKey(k) && promotes7972(s.n, s.mean(), s.se(), s.wins) }
            if (promote) {
                val snap = synchronized(s) { Stat(s.n, s.sum, s.sumSq, s.wins, s.runners) }
                if (promoted.putIfAbsent(k, Spec(k, System.currentTimeMillis(), snap)) == null) {
                    promotions.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("SPECIALIST_PROMOTED_7972")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle("SPECIALIST_PROMOTED_7972", "key=$k n=${snap.n} mean=${"%.1f".format(snap.mean())} se=${"%.1f".format(snap.se())}")
                    } catch (_: Throwable) {}
                }
            }
        }
        if (sinceSave.incrementAndGet() >= 500) { sinceSave.set(0); file?.let { f -> Thread({ save(f) }, "specialists-7972-save").apply { isDaemon = true }.start() } }
        return stamps8418
    }

    /** V5.0.8418 — this process's stamp coordinate id: a stamp from another boot (the decay coordinate is session-scoped) is unknown. */
    private val BOOT_8418: Double = (System.currentTimeMillis() % 1_000_000_007L).toDouble()

    /**
     * Pure (V5.0.8418): the weight a label booked at [stamp] still owns in a record now at [logScale]; null when unknown
     * (no / non-finite stamp, or a coordinate that moved the wrong way: another boot). Never above one.
     */
    fun revisionWeight8418(stamp: Double, logScale: Double): Double? {
        if (!stamp.isFinite() || !logScale.isFinite() || logScale > stamp + 1e-9) return null
        return kotlin.math.exp(logScale - stamp).coerceIn(0.0, 1.0)
    }

    /**
     * V5.0.7997 — ForwardReturnLabeler7731: a later checkpoint (15 m, 30 m, 1 h, 4 h ... while the
     * coin is alive) revises the decision's label in every fact combination it was booked to, so a
     * runner's later path promotes the combinations that found it.
     */
    private val decayedRevisionSkipped8410 = java.util.concurrent.atomic.AtomicLong(0)
    fun decayedRevisionSkipped8410(): Long = decayedRevisionSkipped8410.get()

    fun reviseLabel7997(lane: String, facts: List<String>, oldNet: Double, newNet: Double, oldGross: Double, newGross: Double,
                        stamps8418: DoubleArray = DoubleArray(0)) {
        if (facts.size < 3 || !oldNet.isFinite() || !newNet.isFinite() || lane.startsWith("PLAN")) return
        val combos = combos7972(lane, facts)
        val stamped = stamps8418.size == combos.size + 1 && stamps8418[0] == BOOT_8418
        for ((ci, k) in combos.withIndex()) {
            val s = keys[k] ?: continue
            val promote = synchronized(s) {
                // V5.0.8418 — a stamped label revises at its remaining weight (a NaN stamp, e.g. a combination it never reached, skips).
                val w = if (stamped) (revisionWeight8418(stamps8418[ci + 1], s.logScale8418) ?: 0.0) else null
                s.revise(oldNet, newNet, oldGross, newGross, w)
                !promoted.containsKey(k) && promotes7972(s.n, s.mean(), s.se(), s.wins)
            }
            if (promote) {
                val snap = synchronized(s) { Stat(s.n, s.sum, s.sumSq, s.wins, s.runners) }
                if (promoted.putIfAbsent(k, Spec(k, System.currentTimeMillis(), snap)) == null) {
                    promotions.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("SPECIALIST_PROMOTED_ON_REVISION_7997") } catch (_: Throwable) {}
                }
            }
        }
    }

    /** V5.0.8000 — HiveEdge8000: this instance's combinations with 10+ labels ([n, sum, sumSq, wins, runners, 0]). */
    fun hiveSnapshot8000(): Map<String, DoubleArray> =
        keys.entries.asSequence().filter { it.value.n >= 10 }.sortedByDescending { it.value.n }.take(1_500)
            .associate { (k, s) -> k to synchronized(s) { doubleArrayOf(s.n.toDouble(), s.sum, s.sumSq, s.wins.toDouble(), s.runners.toDouble(), 0.0) } }

    /** V5.0.8000 — a combination the network (local + hive evidence) proves is promoted here too. Returns promotions. */
    fun hivePromote8000(): Int {
        var n = 0
        for (hk in HiveEdge8000.netKeys8000("SPEC|")) {
            val k = hk.removePrefix("SPEC|")
            if (promoted.containsKey(k)) continue
            val net = HiveEdge8000.net8000(hk) ?: continue
            val local = keys[k]
            val c = Stat()
            if (local != null) synchronized(local) { c.n = local.n; c.sum = local.sum; c.sumSq = local.sumSq; c.wins = local.wins; c.runners = local.runners }
            c.n += net[0].toInt(); c.sum += net[1]; c.sumSq += net[2]; c.wins += net[3].toInt(); c.runners += net[4].toInt()
            if (promotes7972(c.n, c.mean(), c.se(), c.wins) && promoted.putIfAbsent(k, Spec(k, System.currentTimeMillis(), c)) == null) {
                n++
                promotions.incrementAndGet()
                try { PipelineHealthCollector.labelInc("SPECIALIST_PROMOTED_BY_HIVE_8000") } catch (_: Throwable) {}
            }
        }
        return n
    }

    // ── V5.0.8144 — a hard, pressure-scaled resident cap with value-ranked eviction ──
    // 8142 died at 512/512 MB with the miner trimmed to 10,000 keys 71 times and refilled to 40,000 between trims (a
    // label books ~350 combinations). Resident keys are now capped at [RESIDENT_KEYS_8144] (~4.8 MB at ~160 B a key)
    // scaled down by heap pressure; the thinnest, least recently booked unpromoted combinations go first, down to 90%
    // of the cap so the next label does not re-sort. Promoted specialists (strategy identity) are never evicted.
    // The file keeps the n>=3 record of what is resident; [MAX_KEYS] stays the load bound of older files.
    const val RESIDENT_KEYS_8144 = 30_000
    private const val RESIDENT_FLOOR_8144 = 2_500
    const val MAX_DEVS_8144 = 16_000
    const val KEY_BYTES_8144 = 160L
    private val keysEvicted8144 = AtomicLong(0)
    private val devsEvicted8144 = AtomicLong(0)

    /** The resident key cap at the heap pressure now. */
    private fun residentCap8144(): Int = residentCapAt8144(try { com.lifecyclebot.engine.MemoryGuard7977.pressure8144() } catch (_: Throwable) { com.lifecyclebot.engine.RetentionPolicy8136.Pressure.NORMAL })

    /** Pure: the resident key cap at pressure [p]. */
    fun residentCapAt8144(p: com.lifecyclebot.engine.RetentionPolicy8136.Pressure): Int =
        com.lifecyclebot.engine.MemoryBounds8144.capFor8144(RESIDENT_KEYS_8144, p, RESIDENT_FLOOR_8144)

    /** Evict the least valuable unpromoted keys so at most 90% of [cap] stay. Returns keys evicted. */
    private fun prune8144(cap: Int): Int {
        val target = com.lifecyclebot.engine.MemoryBounds8144.trimTarget8144(cap)
        if (keys.size <= target) return 0
        val cands = keys.entries.map { (k, s) ->
            com.lifecyclebot.engine.MemoryBounds8144.Candidate8144(k, s.n.toDouble(), s.touchedAtMs8144, promoted.containsKey(k))
        }
        var n = 0
        for (k in com.lifecyclebot.engine.MemoryBounds8144.victims8144(cands, target)) if (keys.remove(k) != null) n++
        if (n > 0) keysEvicted8144.addAndGet(n.toLong())
        return n
    }

    /** Dev reputations past [cap]: the thinnest records go (the 20,000 clear() wiped every reputation at once). */
    private fun evictDevs8144(cap: Int) {
        val target = com.lifecyclebot.engine.MemoryBounds8144.trimTarget8144(cap)
        val cands = devs.entries.map { (d, s) -> com.lifecyclebot.engine.MemoryBounds8144.Candidate8144(d, s.n.toDouble(), s.touchedAtMs8144, false) }
        var n = 0
        for (d in com.lifecyclebot.engine.MemoryBounds8144.victims8144(cands, target)) if (devs.remove(d) != null) n++
        if (n > 0) devsEvicted8144.addAndGet(n.toLong())
    }

    fun promotedCount8144(): Int = promoted.size
    fun devCount8144(): Int = devs.size
    fun evicted8144(): Long = keysEvicted8144.get() + devsEvicted8144.get()

    /** V5.0.8144 — test seam: book [n] synthetic keys of evidence [evidence] each (an insert storm). */
    internal fun stormForTest8144(n: Int, evidence: Int, prefix: String = "T") {
        for (i in 0 until n) {
            val k = "$prefix|k$i"
            if (keys[k] == null && keys.size >= residentCap8144()) prune8144(residentCap8144())
            val s = keys.getOrPut(k) { Stat() }
            s.n = evidence; s.touchedAtMs8144 = i.toLong()
        }
    }

    /** V5.0.8144 — test seam: mark [key] promoted (strategy identity). */
    internal fun promoteForTest8144(key: String) { promoted[key] = Spec(key, 0L, Stat(30, 900.0, 40_000.0, 20, 0)) }

    internal fun hasKeyForTest8144(key: String): Boolean = keys.containsKey(key)

    /** The best promoted specialist of [lane] this token matches now, or null. */
    fun match7972(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Spec? {
        if (promoted.isEmpty()) return null
        val l = lane.trim().uppercase()
        val facts = features7972(ts, l, nowMs)
        var best: Spec? = null
        var bestLo = Double.NEGATIVE_INFINITY
        for (k in combos7972(l, facts)) {
            val sp = promoted[k] ?: continue
            val lo = sp.disc.mean() - 2.0 * sp.disc.se()
            if (lo > bestLo) { best = sp; bestLo = lo }
        }
        if (best != null) {
            matched[ts.mint] = best to nowMs
            if (matched.size > 2_000) matched.entries.removeIf { nowMs - it.value.second > MATCH_TTL_MS }
            liveMatches.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SPECIALIST_MATCH_7972_$l") } catch (_: Throwable) {}
        }
        return best
    }

    private val matched = ConcurrentHashMap<String, Pair<Spec, Long>>()
    private const val MATCH_TTL_MS = 10L * 60_000L

    /**
     * Pure. V5.0.7974 — size from the specialist's proven floor: 1 + (mean - 2 SE)/100, at
     * most 2x (a +15% floor sizes 1.15x, +50% sizes 1.5x). Never below the request.
     */
    fun edgeMult7974(lowerPct: Double): Double =
        if (!lowerPct.isFinite() || lowerPct <= 0.0) 1.0 else (1.0 + lowerPct / 100.0).coerceAtMost(2.0)

    /** TraderSizingBridge6444: a buy admitted by a specialist (within 10 min) is sized by its proven floor. */
    fun sizeMult7974(mint: String, mult: Double, nowMs: Long = System.currentTimeMillis()): Double {
        val m = if (mult.isFinite() && mult > 0.0) mult else 1.0
        val (sp, at) = matched[mint] ?: return m
        if (nowMs - at > MATCH_TTL_MS) return m
        return maxOf(m, edgeMult7974(sp.disc.mean() - 2.0 * sp.disc.se()))
    }

    /** V5.0.7978 — LanePlaybook7907: does a promoted specialist of [lane] match? No counters, no sizing record. */
    fun peek7978(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (promoted.isEmpty()) return false
        val l = lane.trim().uppercase()
        return combos7972(l, features7972(ts, l, nowMs)).any { promoted.containsKey(it) }
    }

    /**
     * V5.0.7979 — MayhemMode7943: a mayhem coin is "good" when a promoted specialist of a meme
     * lane whose combination includes mh=Y matches it now.
     */
    fun goodMayhem7979(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (promoted.keys.none { it.contains("|mh=Y") }) return false
        val facts = features7972(ts, "MEME", nowMs)
        for (lane in MAYHEM_LANES_7979) {
            if (combos7972(lane, facts).any { it.contains("|mh=Y") && promoted.containsKey(it) }) return true
        }
        return false
    }
    private val MAYHEM_LANES_7979 = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER")

    /** Pure-ish: a specialist whose labels run (>= 20% at +50% gross) is held like a runner. */
    fun runs7972(sp: Spec): Boolean = sp.disc.n > 0 && sp.disc.runners >= 0.2 * sp.disc.n

    fun statusLine7972(): String {
        val confirmed = promoted.values.count { synchronized(it.live) { it.live.n >= DEMOTE_N && it.live.mean() - it.live.se() > 0.0 } }
        val byLane = promoted.keys.groupingBy { it.substringBefore('|') }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
        val top = promoted.values.sortedByDescending { it.disc.mean() - 2.0 * it.disc.se() }.take(4).joinToString(" · ") {
            "${it.key.take(70)}[n${it.disc.n} ${"%+.0f".format(it.disc.mean())}% live n${it.live.n} ${"%+.0f".format(it.live.mean())}%]"
        }.ifBlank { "-" }
        return "loaded=$loaded labels=${labels.get()} keys=${keys.size} specialists=${promoted.size} [$byLane] confirmed=$confirmed " +
            "promoted=${promotions.get()} demoted=${demotions.get()} liveMatches=${liveMatches.get()} devs=${devs.size} bar=n>=$MIN_N,mean-2SE>=+$BAR_PCT% top[$top]"
    }

    // ── persistence ──

    private const val MAGIC = 0x7972A001

    private fun writeStat(o: DataOutputStream, s: Stat) { o.writeInt(s.n); o.writeDouble(s.sum); o.writeDouble(s.sumSq); o.writeInt(s.wins); o.writeInt(s.runners) }
    private fun readStat(i: DataInputStream) = Stat(i.readInt(), i.readDouble(), i.readDouble(), i.readInt(), i.readInt())

    private fun save(f: File) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
                o.writeInt(MAGIC)
                val ks = keys.entries.filter { it.value.n >= 3 || promoted.containsKey(it.key) }
                o.writeInt(ks.size)
                for ((k, s) in ks) { o.writeUTF(k); synchronized(s) { writeStat(o, s) } }
                val ps = promoted.values.toList()
                o.writeInt(ps.size)
                for (p in ps) { o.writeUTF(p.key); o.writeLong(p.promotedAtMs); writeStat(o, p.disc); synchronized(p.live) { writeStat(o, p.live) } }
                val ds = devs.entries.toList()
                o.writeInt(ds.size)
                for ((d, s) in ds) { o.writeUTF(d); synchronized(s) { writeStat(o, s) } }
            }
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    private fun load(f: File) {
        if (!f.exists()) return
        DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { i ->
            if (i.readInt() != MAGIC) return
            // V5.0.8144 — loaded in bounded slices: past the resident cap the weakest keys are evicted as reading goes.
            val cap8144 = residentCap8144()
            repeat(i.readInt().coerceIn(0, MAX_KEYS)) {
                keys.putIfAbsent(i.readUTF(), readStat(i))
                if (keys.size > cap8144 + 2_000) prune8144(cap8144)
            }
            repeat(i.readInt().coerceIn(0, 10_000)) { val k = i.readUTF(); promoted.putIfAbsent(k, Spec(k, i.readLong(), readStat(i), readStat(i))) }
            repeat(i.readInt().coerceIn(0, 20_000)) { devs.putIfAbsent(i.readUTF(), readStat(i)) }
        }
    }

    fun saveNow7972() { file?.let { save(it) } }

    /** V5.0.7977 — MemoryGuard7977: drop the thinnest unpromoted combinations (V5.0.8144: to the pressure-scaled cap). */
    fun trim7977(hard: Boolean) {
        // V5.0.8144 — the pressure-scaled cap, value-ranked (evidence, then recency); a hard trim is at least HARD.
        val now8144 = try { com.lifecyclebot.engine.MemoryGuard7977.pressure8144() } catch (_: Throwable) { com.lifecyclebot.engine.RetentionPolicy8136.Pressure.NORMAL }
        val floor8144 = if (hard) com.lifecyclebot.engine.RetentionPolicy8136.Pressure.HARD else com.lifecyclebot.engine.RetentionPolicy8136.Pressure.SOFT
        prune8144(residentCapAt8144(if (now8144.ordinal > floor8144.ordinal) now8144 else floor8144))
        devLabelled.clear(); matched.clear()
        if (hard && devs.size > 5_000) devs.entries.filter { it.value.n < 2 }.forEach { devs.remove(it.key, it.value) }
    }

    fun size7977(): Int = keys.size

    internal fun resetForTest7972() { keys.clear(); promoted.clear(); devs.clear(); devLabelled.clear(); labels.set(0); promotions.set(0); demotions.set(0); liveMatches.set(0); LearnerAttribution8418.forgetForTest8418("SpecialistMiner7972") }
}
