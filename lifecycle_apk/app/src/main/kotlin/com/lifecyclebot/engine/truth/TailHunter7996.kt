package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7996 — THE TAIL HUNTER: catch the life-changing runners every day.
 *
 * Owner: "those trades captured would be life changing ... these have to be captured daily.
 * it would literally allow the bot to be fully self sufficient and self funding."
 *
 * Every learner in the stack grades a decision at 5 minutes. A fresh pump.fun launch cell that
 * loses 10% on 99 coins and goes 300x on the 100th reads -9% there, so every refusal that
 * stands between the bot and BUM (+32,922%), hehe (+2,906%), ORA, AIA, CCOW, SXSN, wApe ... is
 * "right" on the 5-minute book and catastrophically wrong on the money: 21 runners of +400% or
 * more were refused in one day's diags (plan wait 4, no-trigger 6, Mayhem 6, edge veto 2, ...).
 *
 * MEASURE (no risk): every live decision, admitted or refused, opens a ladder replay on the
 * coin's real price prints for up to 4 hours: -15% stop until 2x, half off at 2x (rest stopped at
 * break-even), 35% at 5x, 35% at 11x, the rest out 35% below its peak once it has run 3x, or at
 * the 4-hour / dead-tape mark. Targets fill at the target, stops at the print (gaps count), 3%
 * round-trip cost. The replay result is booked to the decision's cell, to (refusal rule | cell),
 * and to the lane's single facts and fact pairs (the specialist features), so the bot learns
 * WHAT the runners looked like at the moment it saw them.
 *
 * ACT (lottery): a key is TAIL-PROVEN on 30+ replays with a positive ladder total and 2+ runners
 * (peak >= 5x). A live candidate whose cell, refusal-rule cell or a fact pair is tail-proven is a
 * lottery ticket: it clears watch-first, rides the chart-admit path past the soft refusals (plan
 * wait, no-trigger, edge veto, Cortex), Mayhem only when the Mayhem rule's own cell is tail-proven,
 * opens at the executable minimum, and is held on the runner ladder. At most [MAX_OPEN_TICKETS]
 * tickets are open at once. Hard safety always refuses.
 */
object TailHunter7996 {

    private const val HORIZON_MS = 4L * 60L * 60_000L
    private const val SILENT_MS = 30L * 60_000L
    private const val COST_PCT = 3.0
    private const val STOP_X = 0.85
    private const val RUNNER_X = 5.0
    private const val MAX_OBS = 4_000
    private const val MAX_KEYS = 40_000
    private const val TAIL_MIN_N = 30
    private const val TAIL_MIN_RUNNERS = 2
    private const val MAX_OPEN_TICKETS = 3
    private const val TICKET_TTL_MS = 3L * 60_000L
    private const val SWEEP_MS = 30_000L
    private const val FILE = "tail_hunter_7996.txt"
    private val PAIR_FACTS = setOf("mc", "age", "src", "bp", "c5", "bpm", "lb", "dev", "st", "grad", "live", "mh", "hv", "top", "to")

    // ── pure: the ladder replay ──

    /** One position replayed on the owner's runner ladder. Prices are in any consistent unit. */
    /**
     * V5.0.7997 — [crypto]: the swing ladder for crypto alts (no 300x tails there): -8% stop until
     * +15%, half off at +15% (rest stopped at break-even), 35% at +40% and +100%, the rest out 20%
     * below a +30%-or-better peak.
     */
    class Ladder7996(private val entry: Double, private val crypto: Boolean = false) {
        private val stopX = if (crypto) 0.92 else STOP_X
        private val t1X = if (crypto) 1.15 else 2.0
        private val t2X = if (crypto) 1.40 else 5.0
        private val t3X = if (crypto) 2.00 else 11.0
        private val trailFromX = if (crypto) 1.30 else 3.0
        private val trailKeep = if (crypto) 0.80 else 0.65
        var remaining = 1.0
            private set
        var banked = 0.0
            private set
        var peakX = 1.0
            private set
        var lastX = 1.0
            private set
        var done = false
            private set
        private var t1 = false
        private var t2 = false
        private var t3 = false

        fun onPrice(px: Double) {
            if (done || !(entry > 0.0) || !(px > 0.0) || !px.isFinite()) return
            val x = px / entry
            lastX = x
            if (x > peakX) peakX = x
            if (!t1) {
                if (x <= stopX) { close(x); return }
                if (x < t1X) return
                banked += 0.5 * t1X; remaining = 0.5; t1 = true
            }
            if (!t2 && x >= t2X) { val s = remaining * 0.35; banked += s * t2X; remaining -= s; t2 = true }
            if (!t3 && x >= t3X) { val s = remaining * 0.35; banked += s * t3X; remaining -= s; t3 = true }
            if (x <= 1.0 || (peakX >= trailFromX && x <= trailKeep * peakX)) close(x)
        }

        private fun close(x: Double) { banked += remaining * x; remaining = 0.0; done = true }

        /** Net return of the replay in percent (open remainder marked at the last print). */
        fun resultPct(): Double = (banked + remaining * lastX - 1.0) * 100.0 - COST_PCT
    }

    private class Stat7996 {
        @Volatile var n = 0; @Volatile var sum = 0.0; @Volatile var runners = 0; @Volatile var best = 0.0
        fun add(r: Double, runner: Boolean) {
            n++; sum += r; if (runner) runners++; if (r > best) best = r
            // V5.0.8014 — fresh edge: a key's replay record is its most recent ~200.
            if (n > 200) { val k = 200.0 / n; n = 200; sum *= k; runners = kotlin.math.round(runners * k).toInt().coerceIn(0, n) }
        }
        fun mean(): Double = if (n > 0) sum / n else 0.0
        fun encode(): String = "$n,$sum,$runners,$best"
        fun decode(s: String) {
            val f = s.split(','); if (f.size != 4) return
            n = f[0].toIntOrNull() ?: 0; sum = f[1].toDoubleOrNull() ?: 0.0; runners = f[2].toIntOrNull() ?: 0; best = f[3].toDoubleOrNull() ?: 0.0
        }
    }

    /** Pure: is a key tail-proven? 30+ replays, a positive ladder total, 2+ runners. */
    private fun tailProven7996(n: Int, sumPct: Double, runners: Int): Boolean =
        n >= TAIL_MIN_N && sumPct.isFinite() && sumPct > 0.0 && runners >= TAIL_MIN_RUNNERS

    /** Pure: single facts and fact pairs (specialist features) a replay is booked to. */
    private fun factKeys7996(lane: String, facts: List<String>): List<String> {
        val core = facts.filter { it.substringBefore('=') in PAIR_FACTS && !it.endsWith("=NA") }.sorted()
        val out = ArrayList<String>(core.size * (core.size + 1) / 2)
        for (i in core.indices) {
            out += "F|$lane|${core[i]}"
            for (j in i + 1 until core.size) out += "P|$lane|${core[i]}|${core[j]}"
        }
        return out
    }

    // ── state ──

    private class Obs(val mint: String, val lane: String, val keys: List<String>, val ladder: Ladder7996, val atMs: Long) {
        @Volatile var lastPrintMs = atMs
    }

    private val obs = ConcurrentHashMap<String, Obs>()
    private val stats = ConcurrentHashMap<String, Stat7996>()
    private val tickets = ConcurrentHashMap<String, Long>()
    private val observed = AtomicLong(0)
    private val finalized = AtomicLong(0)
    private val runnersSeen = AtomicLong(0)
    private val ticketsIssued = AtomicLong(0)
    private val sinceSave = AtomicLong(0)
    @Volatile private var lastSweepMs = 0L
    @Volatile private var anyProvenPair = false
    @Volatile private var loaded = false
    private val recentRunners = java.util.ArrayDeque<String>()

    private fun file(): File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { File(it.filesDir, FILE) }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            file()?.takeIf { it.exists() }?.forEachLine { line ->
                val k = line.substringBefore('\t'); val v = line.substringAfter('\t', "")
                if (k.isNotBlank() && v.isNotBlank()) stats.getOrPut(k) { Stat7996() }.decode(v)
            }
            anyProvenPair = stats.any { (k, st) -> k.startsWith("P|") && tailProven7996(st.n, st.sum, st.runners) }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            file()?.writeText(buildString { for ((k, s) in stats) if (s.n > 0) append(k).append('\t').append(s.encode()).append('\n') })
        } catch (_: Throwable) {}
    }

    // ── feed ──

    /** LiveEdgeGate7877.liveRefusal: a live decision (admitted when [reason] is null). One replay per mint at a time. */
    fun observe7996(ts: TokenState, lane: String, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        ensureLoaded()
        val px = ts.lastPrice
        if (ts.mint.isBlank() || !(px > 0.0) || !px.isFinite() || obs.containsKey(ts.mint)) return
        if (obs.size >= MAX_OBS) return
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val cell = ForwardReturnLabeler7731.cellKey(ts.source, l, ts.lastMcap, ageMs)
        val mayhem = try { com.lifecyclebot.engine.MayhemMode7943.isMayhem7979(ts) } catch (_: Throwable) { false }
        val rule = when {
            mayhem -> "MAYHEM_MODE"
            reason == null -> "ADMIT"
            else -> try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRuleOf(reason) } catch (_: Throwable) { "UNNAMED" }
        }
        val facts = try { SpecialistMiner7972.features7972(ts, l, nowMs) } catch (_: Throwable) { emptyList() }
        // V5.0.7997 — the decision's setup and discovery source are graded on the ladder too.
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, l, nowMs) } catch (_: Throwable) { null } ?: "NONE"
        val src = ForwardReturnLabeler7731.sourceFamily(ts.source)
        val keys = ArrayList<String>(140).apply {
            add("C|$cell"); add("R|$rule|$cell"); add("S|$l|$setup"); add("SRC|$src|$l"); addAll(factKeys7996(l, facts))
        }
        obs[ts.mint] = Obs(ts.mint, l, keys, Ladder7996(px, crypto = l.startsWith("CRYPTO")), nowMs)
        observed.incrementAndGet()
    }

    /** ChartReader7950.onPrice: every observed price print. */
    fun onPrice7996(mint: String, priceUsd: Double, atMs: Long) {
        val o = obs[mint]
        if (o != null && atMs >= o.atMs) {
            o.ladder.onPrice(priceUsd)
            o.lastPrintMs = atMs
            if (o.ladder.done) finalize(o)
        }
        if (atMs - lastSweepMs >= SWEEP_MS) sweep(atMs)
    }

    private fun sweep(nowMs: Long) {
        lastSweepMs = nowMs
        for (o in obs.values.toList()) {
            if (nowMs - o.atMs >= HORIZON_MS || nowMs - o.lastPrintMs >= SILENT_MS) finalize(o)
        }
        tickets.entries.removeIf { nowMs - it.value > HORIZON_MS }
    }

    private fun finalize(o: Obs) {
        if (obs.remove(o.mint, o).not()) return
        val r = o.ladder.resultPct()
        if (!r.isFinite()) return
        val runner = o.ladder.peakX >= RUNNER_X
        for (k in o.keys) {
            val st = if (stats.size < MAX_KEYS) stats.getOrPut(k) { Stat7996() } else stats[k] ?: continue
            st.add(r, runner)
            if (k.startsWith("P|") && tailProven7996(st.n, st.sum, st.runners)) anyProvenPair = true
        }
        finalized.incrementAndGet()
        if (runner) {
            runnersSeen.incrementAndGet()
            synchronized(recentRunners) {
                recentRunners.addLast("${o.mint.take(6)}:${o.lane.take(8)}:${"%.0f".format((o.ladder.peakX - 1.0) * 100.0)}%pk/${"%+.0f".format(r)}%")
                while (recentRunners.size > 6) recentRunners.removeFirst()
            }
            try { PipelineHealthCollector.labelInc("TAIL_RUNNER_SEEN_7996") } catch (_: Throwable) {}
        }
        if (sinceSave.incrementAndGet() >= 25) { sinceSave.set(0); save() }
    }

    // ── act ──

    /** Local evidence plus the hive's (other instances' replays of the same key, HiveEdge8000). */
    private fun proven(key: String): Stat7996? {
        val local = stats[key]
        val hive = try { HiveEdge8000.net8000("TAIL|$key") } catch (_: Throwable) { null }
        if (local == null && hive == null) return null
        val c = Stat7996()
        c.n = (local?.n ?: 0) + (hive?.get(0)?.toInt() ?: 0)
        c.sum = (local?.sum ?: 0.0) + (hive?.get(1) ?: 0.0)
        c.runners = (local?.runners ?: 0) + (hive?.get(4)?.toInt() ?: 0)
        c.best = maxOf(local?.best ?: 0.0, hive?.get(5) ?: 0.0)
        return c.takeIf { tailProven7996(it.n, it.sum, it.runners) }
    }

    /**
     * LiveEdgeGate7877.watchFirst7994: is [ts] a lottery ticket now? Its cell, its refusal-free cell
     * or one of its fact pairs is tail-proven, and fewer than [MAX_OPEN_TICKETS] tickets are open.
     */
    fun ticket7996(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded()
        if (ts.mint.isBlank()) return false
        if (ticketActive7996(ts.mint, nowMs)) return true
        if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
        val open = tickets.keys.count { m -> try { com.lifecyclebot.engine.BotService.status.tokens[m]?.position?.isOpen == true } catch (_: Throwable) { false } }
        if (open >= MAX_OPEN_TICKETS) return false
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val cell = ForwardReturnLabeler7731.cellKey(ts.source, l, ts.lastMcap, ageMs)
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, l, nowMs) } catch (_: Throwable) { null } ?: "NONE"
        val hit = proven("C|$cell") != null || proven("S|$l|$setup") != null ||
            proven("SRC|${ForwardReturnLabeler7731.sourceFamily(ts.source)}|$l") != null || pairsWorthChecking(nowMs) && run {
            val facts = try { SpecialistMiner7972.features7972(ts, l, nowMs) } catch (_: Throwable) { emptyList() }
            factKeys7996(l, facts).any { it.startsWith("P|") && proven(it) != null }
        }
        if (!hit) return false
        tickets[ts.mint] = nowMs
        ticketsIssued.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("TAIL_TICKET_7996_$l")
            com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs)
        } catch (_: Throwable) {}
        return true
    }

    @Volatile private var hivePairsAtMs = 0L
    @Volatile private var hivePairs = false

    /** Any fact pair proven locally, or any pair the hive proves together with us (refreshed each minute). */
    private fun pairsWorthChecking(nowMs: Long): Boolean {
        if (anyProvenPair) return true
        if (nowMs - hivePairsAtMs > 60_000L) {
            hivePairsAtMs = nowMs
            hivePairs = try { HiveEdge8000.netKeys8000("TAIL|P|").any { proven(it.removePrefix("TAIL|")) != null } } catch (_: Throwable) { false }
        }
        return hivePairs
    }

    /** ChartReader7950.saysBuy / FinalDecisionGate edge veto: a ticket issued in the last few minutes. */
    fun ticketActive7996(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        tickets[mint]?.let { nowMs - it in 0L..TICKET_TTL_MS } == true

    /** MayhemMode7943: a Mayhem coin is a ticket only when the Mayhem rule's own cell is tail-proven. */
    fun goodMayhemTail7996(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded()
        val l = CanonicalLaneIdentity6506.canonical(ts.position.tradingMode.ifBlank { "SHITCOIN" }).uppercase()
        val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L
        val cells = listOf(l, "SHITCOIN", "MOONSHOT").distinct().map { ForwardReturnLabeler7731.cellKey(ts.source, it, ts.lastMcap, ageMs) }
        return cells.any { proven("R|MAYHEM_MODE|$it") != null }
    }

    /** TraderSizingBridge6444: a ticket opens at the executable minimum (the risk policy promotes the small request). */
    fun sizeMult7996(mint: String, mult: Double): Double {
        val m = if (mult.isFinite() && mult > 0.0) mult else 1.0
        return if (ticketActive7996(mint)) minOf(m, 0.5) else m
    }

    /** V5.0.8000 — HiveEdge8000: this instance's replay totals per key ([n, sum, 0, 0, runners, best]). */
    fun hiveSnapshot8000(): Map<String, DoubleArray> =
        stats.entries.asSequence().filter { it.value.n >= 3 }.sortedByDescending { it.value.n }.take(1_500)
            .associate { (k, s) -> k to doubleArrayOf(s.n.toDouble(), s.sum, 0.0, 0.0, s.runners.toDouble(), s.best) }

    // ── housekeeping / report ──

    fun trim7977() {
        val now = System.currentTimeMillis()
        if (stats.size > MAX_KEYS / 2) stats.entries.removeIf { it.value.n < 3 }
        tickets.entries.removeIf { now - it.value > HORIZON_MS }
    }

    fun statusLine7996(): String {
        val top = stats.entries.asSequence()
            .filter { tailProven7996(it.value.n, it.value.sum, it.value.runners) }
            .sortedByDescending { it.value.mean() }.take(4)
            .joinToString(" · ") { (k, s) -> "${k.take(60)}[n${s.n} ${"%+.0f".format(s.mean())}% run${s.runners}]" }.ifBlank { "-" }
        val rr = synchronized(recentRunners) { recentRunners.joinToString(",").ifBlank { "-" } }
        val rules = stats.entries.asSequence().filter { it.key.startsWith("R|") && it.value.n >= 10 }
            .groupBy { it.key.split('|').getOrElse(1) { "?" } }
            .map { (rule, es) -> val n = es.sumOf { it.value.n }; val sum = es.sumOf { it.value.sum }; val run = es.sumOf { it.value.runners }
                "$rule n$n ${"%+.1f".format(if (n > 0) sum / n else 0.0)}% run$run" }
            .sortedByDescending { it }.take(8).joinToString(" · ").ifBlank { "-" }
        return "replaying=${obs.size} observed=${observed.get()} finalized=${finalized.get()} runners=${runnersSeen.get()} tickets=${ticketsIssued.get()} keys=${stats.size} " +
            "bar=n>=$TAIL_MIN_N,sum>0,runners>=$TAIL_MIN_RUNNERS recentRunners[$rr]\n    tail-proven: $top\n    by refusal (ladder replay): $rules"
    }

    internal fun resetForTest7996() { obs.clear(); stats.clear(); tickets.clear(); loaded = true }
}
