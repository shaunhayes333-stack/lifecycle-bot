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
    private const val MAX_KEYS = 80_000
    private const val NET_CAP = 300.0
    private const val RUNNER_GROSS = 50.0

    class Stat(var n: Int = 0, var sum: Double = 0.0, var sumSq: Double = 0.0, var wins: Int = 0, var runners: Int = 0) {
        fun add(net: Double, gross: Double) {
            val y = net.coerceIn(-100.0, NET_CAP)
            n++; sum += y; sumSq += y * y
            if (y > 0.0) wins++
            if (gross.isFinite() && gross >= RUNNER_GROSS) runners++
        }
        fun mean(): Double = if (n > 0) sum / n else 0.0
        fun se(): Double = if (n > 1) sqrt(((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
    }

    class Spec(val key: String, val promotedAtMs: Long, val disc: Stat, val live: Stat = Stat())

    // ── pure ──

    /** Pure: does a discovery record prove a specialist? */
    fun promotes7972(n: Int, mean: Double, se: Double, wins: Int): Boolean =
        n >= MIN_N && mean.isFinite() && se.isFinite() && mean - 2.0 * se >= BAR_PCT && wins >= MIN_WIN * n

    /** Pure: has the out-of-sample record proven the specialist wrong? */
    fun demotes7972(n: Int, mean: Double, se: Double): Boolean = n >= DEMOTE_N && mean.isFinite() && se.isFinite() && mean + se < 0.0

    /** The facts triples are built from (pairs use every fact): keeps the key table phone-sized. */
    private val CORE = setOf("mc", "age", "bp", "st", "hv", "dev", "bpm", "c5", "grad")

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
        fun put(name: String, v: String) { out += "$name=$v" }
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
        val s = devs[dev] ?: return "NEW"
        return synchronized(s) { devBin7972(s.n, s.mean()) }
    }

    /** ForwardReturnLabeler7731.attach: where the miner keeps its record. */
    fun attach7972(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "specialists7972.bin")
        file = f
        Thread({ try { load(f) } catch (_: Throwable) {} finally { loaded = true } }, "specialists-7972-load").apply { isDaemon = true }.start()
    }

    /** ForwardReturnLabeler7731 (5-minute label): book [net] to every combination of the decision's facts. */
    fun onLabel7972(lane: String, mint: String, facts: List<String>, net: Double, gross: Double, decidedAtMs: Long) {
        if (facts.size < 3 || !net.isFinite() || lane.startsWith("PLAN")) return
        labels.incrementAndGet()
        // Dev reputation: one label per coin.
        try {
            val dev = com.lifecyclebot.engine.OperatorRegistry.getDevWallet(mint)
            if (dev != null && devLabelled.add(mint)) {
                val s = devs.getOrPut(dev) { Stat() }
                synchronized(s) { s.add(net, gross) }
                if (devs.size > 20_000) devs.clear()
                if (devLabelled.size > 50_000) devLabelled.clear()
            }
        } catch (_: Throwable) {}
        for (k in combos7972(lane, facts)) {
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
                if (keys.size >= MAX_KEYS) prune()
                keys.getOrPut(k) { Stat() }
            }
            val promote = synchronized(s) { s.add(net, gross); !promoted.containsKey(k) && promotes7972(s.n, s.mean(), s.se(), s.wins) }
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
    }

    private fun prune() {
        val victims = keys.entries.filter { !promoted.containsKey(it.key) && it.value.n <= 3 }.take(MAX_KEYS / 5)
            .ifEmpty { keys.entries.filter { !promoted.containsKey(it.key) }.sortedBy { it.value.n }.take(MAX_KEYS / 10) }
        victims.forEach { keys.remove(it.key, it.value) }
    }

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
            liveMatches.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SPECIALIST_MATCH_7972_$l") } catch (_: Throwable) {}
        }
        return best
    }

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
            repeat(i.readInt().coerceIn(0, MAX_KEYS)) { keys.putIfAbsent(i.readUTF(), readStat(i)) }
            repeat(i.readInt().coerceIn(0, 10_000)) { val k = i.readUTF(); promoted.putIfAbsent(k, Spec(k, i.readLong(), readStat(i), readStat(i))) }
            repeat(i.readInt().coerceIn(0, 20_000)) { devs.putIfAbsent(i.readUTF(), readStat(i)) }
        }
    }

    fun saveNow7972() { file?.let { save(it) } }

    internal fun resetForTest7972() { keys.clear(); promoted.clear(); devs.clear(); devLabelled.clear(); labels.set(0); promotions.set(0); demotions.set(0); liveMatches.set(0) }
}
