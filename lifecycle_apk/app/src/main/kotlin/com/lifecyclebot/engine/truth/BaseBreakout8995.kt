package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8995 §THE_BASE_BREAKOUT_PAST_THE_FIRST_HOUR — owner, 25 Nov 01:40: "do that now bro" (the base breakout for graduated
 * and off-pump coins, not only launches).
 *
 * 8994 reads the shape on a launch's first hour, where the phone stores the prints and SignalReplay8828 can grade it. Past
 * the first hour nothing stores a coin's prints, so there is no replay and no offline figure: the owner's exports hold bars
 * for launches only. What the bot does have is the live stream itself: every witnessed print of every coin it hears
 * (TradeTape8100.launchEvidence8750: pump.fun curve, PumpSwap pools, the DEX feeds) passes one place. So here the shape is
 * read on that stream and proven FORWARD, on the phone, before it may carry a coin:
 *
 *  - each coin past its first hour gets a SignalTape8828 (the same 10-second bars and the same two base settings, anchored
 *    at the first print this tape heard), bounded at [MAX_COINS_8995];
 *  - a fire opens a VIRTUAL position, never an order: entered at the first print after the bot's required latency
 *    (LaunchSniper8748.requiredDelayMs8749), followed print by print on the runner line the meme lanes hold (a
 *    [STOP_PCT_8995] % stop; from +10 % the lower of half the peak gain and the peak less the stop distance, never under
 *    +5 %; from +100 % a 45 % give-back), filled at the print that crossed it, closed at [MAX_HOLD_MS_8995] or after
 *    [IDLE_EXIT_MS_8995] without a print, and charged the round trip (FieldManual7715.roundTripCostPct7766);
 *  - the closes are kept per venue class and setting (the last [WINDOW_8995]), and graded by SignalReplay8828's own record
 *    and bar: PROVEN at n >= 100, ex-top-3 mean - SE > 0, both halves of the closes positive;
 *  - only a PROVEN record arms: its next fire puts the coin on the watchlist, runs its pipeline at once and is market-cell
 *    evidence ([KIND_8995]) for [EVIDENCE_TTL_MS_8995], through admission and every safety door, like a proven launch rule.
 * Nothing here buys, and no gate is added or loosened. Stated: the virtual fill is the print's price (no own impact); the
 * ladder's partial sales are not modelled (one line, whole position); a coin heard only now and then makes few bars.
 */
object BaseBreakout8995 {
    const val KIND_8995 = "BASE_BREAKOUT_8995"
    private const val KEY_8995 = "BASE_BREAKOUT_8995"
    private const val HOUR = 3_600_000L
    internal const val MAX_COINS_8995 = 600
    internal const val STOP_PCT_8995 = 20.0
    internal const val MAX_HOLD_MS_8995 = HOUR
    internal const val IDLE_EXIT_MS_8995 = 10L * 60_000L
    internal const val WINDOW_8995 = 400
    internal const val EVIDENCE_TTL_MS_8995 = 120_000L
    private const val REARM_MS_8995 = 30L * 60_000L
    private const val SWEEP_MS_8995 = 30_000L
    private const val SIZE_SOL_8995 = 0.04
    private const val DEFAULT_DEPTH_SOL_8995 = 70.0
    private val IDS_8995 = listOf(SignalTape8828.BASE_WIDE_8994, SignalTape8828.BASE_TIGHT_8994)

    /** One virtual position. */
    internal class Virtual8995(val id: String, val cohort: String, val enterAfterMs: Long) {
        var entry = 0.0; var entryMs = 0L; var peak = 0.0; var lastPx = 0.0; var lastMs = 0L
    }

    private class Coin(val anchorMs: Long) {
        var tape = SignalTape8828(); var tapeAnchor = anchorMs
        var lastMs = 0L
        val open = ArrayList<Virtual8995>(2)
        var lastFireMs = 0L
    }

    internal class Fire8995(val atMs: Long, val key: String, val n: Int, val exTop3: Double, val se: Double)

    private val coins = ConcurrentHashMap<String, Coin>()
    private val books = HashMap<String, ArrayDeque<DoubleArray>>()   // "COHORT|id" -> (closedMs, net %)
    private val armed = ConcurrentHashMap<String, Fire8995>()
    private val prints = AtomicLong(0L); private val fires = AtomicLong(0L); private val armedFires = AtomicLong(0L)
    private val closes = AtomicLong(0L); private val noEntry = AtomicLong(0L); private val full = AtomicLong(0L); private val served = AtomicLong(0L)
    @Volatile private var loaded = false
    @Volatile private var sweptAtMs = 0L
    @Volatile private var dirtyCloses = 0

    // ── pure ──

    /** Pure: the venue class a base is graded in. */
    internal fun cohort8995(pumpMint: Boolean, graduated: Boolean): String = if (!pumpMint) "OTHER" else if (graduated) "PUMPSWAP" else "CURVE"

    /** Pure: the price the runner line stands at for a position entered at [entry] whose peak so far is [peak]. */
    internal fun line8995(entry: Double, peak: Double): Double {
        val g = (peak / entry - 1.0) * 100.0
        return when {
            g >= 100.0 -> entry * (1.0 + 0.55 * g / 100.0)
            g >= 10.0 -> entry * (1.0 + maxOf(5.0, minOf(g / 2.0, g - STOP_PCT_8995)) / 100.0)
            else -> entry * (1.0 - STOP_PCT_8995 / 100.0)
        }
    }

    /**
     * Pure: one print at [px] / [atMs] for [v]; the gross % when it closes the position, else null. Before the latency the
     * print is only watched; the first one after it is the entry.
     */
    internal fun step8995(v: Virtual8995, px: Double, atMs: Long): Double? {
        if (!(px > 0.0) || !px.isFinite()) return null
        v.lastPx = px; v.lastMs = atMs
        if (v.entry <= 0.0) {
            if (atMs < v.enterAfterMs) return null
            v.entry = px; v.entryMs = atMs; v.peak = px
            return null
        }
        if (px <= line8995(v.entry, v.peak) || atMs - v.entryMs >= MAX_HOLD_MS_8995) return (px / v.entry - 1.0) * 100.0
        if (px > v.peak) v.peak = px
        return null
    }

    /** Pure: the record of [key]'s closes by SignalReplay8828's own measure (null under 2). */
    internal fun record8995(key: String, rows: List<DoubleArray>): SignalReplay8828.Rec? =
        SignalReplay8828.stats8828(key, STOP_PCT_8995, rows.map { SignalReplay8828.Entry(it[0].toLong(), it[1], Double.NaN) })

    // ── the stream ──

    private fun ensure8995() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try { decode8995(com.lifecyclebot.engine.LearningPersistence.load(KEY_8995)) } catch (_: Throwable) {}
            loaded = true
        }
    }

    /** TradeTape8100.launchEvidence8750: one witnessed print of [mint]. Cheap; never throws. */
    fun onPrint8995(mint: String, eventMs: Long, recvMs: Long, priceSol: Double, isBuy: Boolean, sol: Double) {
        try {
            if (mint.isBlank() || !(priceSol > 0.0) || !priceSol.isFinite() || eventMs <= 0L) return
            val pump = com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint)
            val graduated = pump && com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint)
            if (pump && !graduated) {
                // a launch's first hour is the launch tape's (8994): this one starts where the stored hour ends
                val born = CanonicalTokenBirthTime7440.peekBirthMs8418(mint, eventMs) ?: return
                if (eventMs - born <= HOUR) return
            }
            ensure8995()
            prints.incrementAndGet()
            var c = coins[mint]
            if (c == null) {
                if (coins.size >= MAX_COINS_8995) sweep8995(recvMs, force = true)
                if (coins.size >= MAX_COINS_8995) { full.incrementAndGet(); return }
                c = coins.computeIfAbsent(mint) { Coin(eventMs) }
            }
            val live = recvMs - eventMs in -5_000L..LaunchReplay8744.LIVE_LAG_MS_8748
            val cohort = cohort8995(pump, graduated)
            val closed = ArrayList<Pair<Virtual8995, Double>>(0)
            var on: List<String> = emptyList()
            synchronized(c) {
                c.lastMs = recvMs
                val iter = c.open.iterator()
                while (iter.hasNext()) { val v = iter.next(); val g = step8995(v, priceSol, eventMs); if (g != null) { closed.add(v to g); iter.remove() } }
                // one fire a setting per tape: a coin that based again later is read on a new tape
                if (c.lastFireMs > 0L && c.open.isEmpty() && eventMs - c.lastFireMs >= REARM_MS_8995) { c.tape = SignalTape8828(); c.tapeAnchor = eventMs; c.lastFireMs = 0L }
                if (eventMs >= c.tapeAnchor) on = c.tape.onPrint(eventMs - c.tapeAnchor, priceSol, isBuy, sol, -1L, live).filter { id -> id in IDS_8995 }
                if (on.isNotEmpty()) {
                    c.lastFireMs = eventMs
                    val delay = try { LaunchSniper8748.requiredDelayMs8749() } catch (_: Throwable) { 10_000L }
                    for (id in on) if (c.open.none { v -> v.id == id }) c.open.add(Virtual8995(id, cohort, eventMs + delay))
                }
            }
            for ((v, gross) in closed) close8995(mint, v, gross, recvMs)
            for (id in on) fire8995(mint, cohort, id, priceSol, recvMs)
            if (recvMs - sweptAtMs >= SWEEP_MS_8995) sweep8995(recvMs, force = false)
        } catch (_: Throwable) {}
    }

    private fun costPct8995(mint: String): Double {
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!(solUsd > 0.0)) return 4.0
        val liq = try { com.lifecyclebot.engine.HandoffRoute8180.tokenFor8180(mint)?.lastLiquidityUsd ?: 0.0 } catch (_: Throwable) { 0.0 }
        val depth = if (liq > 0.0) liq else DEFAULT_DEPTH_SOL_8995 * solUsd
        return FieldManual7715.roundTripCostPct7766(SIZE_SOL_8995, SIZE_SOL_8995 * solUsd, depth)
    }

    private fun close8995(mint: String, v: Virtual8995, grossPct: Double, nowMs: Long) {
        val net = grossPct - costPct8995(mint)
        if (!net.isFinite()) return
        synchronized(this) {
            val q = books.getOrPut("${v.cohort}|${v.id}") { ArrayDeque() }
            q.addLast(doubleArrayOf(nowMs.toDouble(), net))
            while (q.size > WINDOW_8995) q.removeFirst()
            dirtyCloses++
        }
        closes.incrementAndGet()
        if (dirtyCloses >= 5) persist8995()
    }

    private fun proven8995(key: String): SignalReplay8828.Rec? {
        val rows = synchronized(this) { books[key]?.toList() } ?: return null
        return record8995(key, rows)?.takeIf { it.proven }
    }

    private fun fire8995(mint: String, cohort: String, id: String, priceSol: Double, nowMs: Long) {
        fires.incrementAndGet()
        val key = "$cohort|$id"
        val rec = proven8995(key)
        if (rec == null) { try { PipelineHealthCollector.labelInc("BASE_BREAKOUT_SHADOW_FIRE_8995_$cohort") } catch (_: Throwable) {}; return }
        armedFires.incrementAndGet()
        if (armed.size > 2_000) armed.entries.removeIf { nowMs - it.value.atMs > EVIDENCE_TTL_MS_8995 }
        armed[mint] = Fire8995(nowMs, key, rec.n, rec.exTop3, rec.exTop3Se)
        try { PipelineHealthCollector.labelInc("BASE_BREAKOUT_ARMED_FIRE_8995_$cohort") } catch (_: Throwable) {}
        try {
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            com.lifecyclebot.engine.GlobalTradeRegistry.addToWatchlist(mint, mint.take(6), KIND_8995, initialMcap = priceSol * 1.0e9 * solUsd)
        } catch (_: Throwable) {}
        // the coin's full pipeline now, as an armed launch fire runs it (BotService's hook; every gate stands)
        try { LaunchSniper8748.onArmed8949?.invoke(mint) } catch (_: Throwable) {}
    }

    /** Idle coins leave; a position whose coin stopped printing is closed at its last print, one never entered is dropped. */
    private fun sweep8995(nowMs: Long, force: Boolean) {
        sweptAtMs = nowMs
        val closed = ArrayList<Triple<String, Virtual8995, Double>>(0)
        for ((mint, c) in coins) synchronized(c) {
            if (nowMs - c.lastMs < IDLE_EXIT_MS_8995) return@synchronized
            for (v in c.open) {
                if (v.entry > 0.0 && v.lastPx > 0.0) closed.add(Triple(mint, v, (v.lastPx / v.entry - 1.0) * 100.0)) else noEntry.incrementAndGet()
            }
            c.open.clear()
            coins.remove(mint, c)
        }
        for ((mint, v, g) in closed) close8995(mint, v, g, nowMs)
        if (force && coins.size >= MAX_COINS_8995) {
            // every coin is printing: the stalest tapes holding no position make room
            coins.entries.filter { synchronized(it.value) { it.value.open.isEmpty() } }.sortedBy { it.value.lastMs }
                .take(MAX_COINS_8995 / 10).forEach { coins.remove(it.key, it.value) }
        }
        if (dirtyCloses > 0) persist8995()
    }

    /** CandidateAdmission8136: the armed fire on [mint] as market-cell evidence, or null. */
    fun evidence8995(mint: String, nowMs: Long): CandidateAdmission8136.Evidence8136? {
        val f = armed[mint]?.takeIf { nowMs - it.atMs in 0L..EVIDENCE_TTL_MS_8995 } ?: return null
        served.incrementAndGet()
        return CandidateAdmission8136.Evidence8136(KIND_8995, f.key, f.n, f.exTop3, f.se, netOfCost = true)
    }

    // ── persistence ──

    internal fun encode8995(): String = synchronized(this) {
        val o = org.json.JSONObject()
        for ((k, q) in books) o.put(k, org.json.JSONArray().also { a -> q.forEach { r -> a.put(org.json.JSONArray().put(r[0]).put(r[1])) } })
        o.toString()
    }

    internal fun decode8995(raw: String?) {
        if (raw.isNullOrBlank()) return
        val o = org.json.JSONObject(raw)
        synchronized(this) {
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                val q = ArrayDeque<DoubleArray>()
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { r -> if (r.length() == 2 && r.optDouble(1).isFinite()) q.addLast(doubleArrayOf(r.optDouble(0), r.optDouble(1))) }
                while (q.size > WINDOW_8995) q.removeFirst()
                if (!books.containsKey(k)) books[k] = q
            }
        }
    }

    private fun persist8995() {
        if (!loaded) return   // never overwrite the stored record with an unrestored one
        dirtyCloses = 0
        try { com.lifecyclebot.engine.LearningPersistence.save(KEY_8995, encode8995()) } catch (_: Throwable) {}
    }

    /** Brief: each venue class and setting's forward record, and what is armed. */
    fun statusLine8995(): String {
        ensure8995()
        val keys = synchronized(this) { books.keys.sorted() }
        val body = keys.joinToString(" | ") { k ->
            val rows = synchronized(this) { books[k]?.toList() } ?: emptyList()
            val r = record8995(k, rows)
            if (r == null) "$k n${rows.size}" else "$k n${r.n} mean${"%+.1f".format(r.mean)}% exTop3${"%+.1f".format(r.exTop3)}±${"%.1f".format(r.exTop3Se)} " +
                "med${"%+.0f".format(r.median)}% win${(r.win * 100).toInt()}% halves${"%+.1f".format(r.halfA)}/${"%+.1f".format(r.halfB)}${if (r.proven) " ARMED" else ""}"
        }.ifBlank { "-" }
        val openN = coins.values.sumOf { synchronized(it) { it.open.size } }
        return "baseBreakout8995[coins=${coins.size} prints=${prints.get()} fires=${fires.get()} armedFires=${armedFires.get()} open=$openN closes=${closes.get()} " +
            "noEntry=${noEntry.get()} full=${full.get()} served=${served.get()} records{$body} rule=the base breakout (8994's two settings) on every " +
            "witnessed print of a coin past its first hour; a fire is a virtual position at the bot's latency on the runner line (stop " +
            "${STOP_PCT_8995.toInt()} %), net of cost; a venue class and setting arms at n>=${SignalReplay8828.PROVEN_MIN_N}, ex-top-3 mean - SE > 0, both halves positive]"
    }

    internal fun resetForTest8995() {
        coins.clear(); armed.clear(); synchronized(this) { books.clear() }
        prints.set(0L); fires.set(0L); armedFires.set(0L); closes.set(0L); noEntry.set(0L); full.set(0L); served.set(0L)
        loaded = true; sweptAtMs = 0L; dirtyCloses = 0
    }
    internal fun fileForTest8995(key: String, closedMs: Long, net: Double) = synchronized(this) { books.getOrPut(key) { ArrayDeque() }.addLast(doubleArrayOf(closedMs.toDouble(), net)) }
    internal fun armForTest8995(mint: String, key: String, nowMs: Long): Boolean {
        val rec = proven8995(key) ?: return false
        armed[mint] = Fire8995(nowMs, key, rec.n, rec.exTop3, rec.exTop3Se); return true
    }
}
