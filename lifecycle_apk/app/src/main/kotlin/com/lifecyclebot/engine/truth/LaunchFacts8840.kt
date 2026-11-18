package com.lifecyclebot.engine.truth

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8840 §WHO_IS_BEHIND_THE_LAUNCH — owner: "add the data the bot isn't collecting. you can't guess here it has to be
 * exact". 5.0.8837 report, learner column coverage over 14,262 decisions: devTokens 0 %, devRugRate 0 %, insider 0 %, the
 * launch-tape wallet columns 14-22 %. Offline on 3,180 launches, a model of price, flow and buy/sell counts lifted the
 * +50 %-before--30 % rate from 19 % to ~30 % on launches it had not seen — short of the 41.6 % the round trip needs — so the
 * runners are not told apart by what the bot reads now. The chain says more, and every pump.fun print the bot decodes
 * already carries it: the creator (pump.fun's CreateEvent `user`), the trader of each swap (TradeEvent `user`), the
 * signature, the chain second, the SOL and token amounts.
 *
 * [Fold8840] folds one launch's prints, in chain order, into facts defined on chain data alone:
 *  - the creator's own buys and sells: initial buy (bought in the creation second), held now, share sold, sold in the
 *    first 10 minutes;
 *  - bundle: non-creator wallets whose first buy is in the creation second, and their tokens; snipers: first buy within
 *    two seconds of creation;
 *  - concentration: the largest one and five net holdings among the traders; distinct buyers.
 * Token amounts are % of pump.fun's fixed 1e9 supply. The creation second is the chain time of the create transaction's
 * own print (the creator's buy in that signature) when seen, else the launch's first print.
 *
 * [DevHistory8840] keeps, per creator, the launches this phone saw retire (an hour after birth): how many, how many reached
 * a $30k cap inside their hour, how many the creator sold >= 90 % of inside ten minutes. A launch reads its creator's
 * history as of its birth.
 *
 * The same Fold runs live (every witnessed print, TradeTape8100.launchEvidence8750) and in the launch-hour export (the
 * stored prints, LaunchHour8628), so the offline study and the live columns are one definition. Nothing here buys.
 */
object LaunchFacts8840 {
    const val SUPPLY_TOKENS = 1.0e9
    private const val HOUR = 3_600_000L
    // V5.0.8853 — a launch is live for its first hour and ~600 are born an hour on pump.fun: 1,500 holds them with room
    private const val MAX_LIVE = 1_500
    private const val MAX_WALLETS = 600
    internal const val WALLET_SLOTS_8853 = 1_024
    private const val RUNNER_CAP_USD = 30_000.0
    private const val DUMP_SOLD_FRAC = 0.90

    /** One launch's facts at the moment of the snapshot (NaN = not observable yet). */
    data class Facts(
        val creatorKnown: Boolean, val devInitPct: Double, val devHeldPct: Double, val devSoldFrac: Double, val devSold10mFrac: Double,
        val bundleBuyers: Int, val bundlePct: Double, val sniperBuyers: Int, val sniperPct: Double,
        val top1Pct: Double, val top5Pct: Double, val uniqueBuyers: Int, val prints: Int,
    )

    /** Pure (V5.0.8853): a wallet's 64-bit key (FNV-1a, OwnBars8816.hash8816); 0 is reserved for an empty slot. */
    internal fun walletKey8853(wallet: String): Long = com.lifecyclebot.engine.chart.OwnBars8816.hash8816(wallet).let { if (it == 0L) 1L else it }

    /**
     * Pure fold of one launch's prints (chain ms, in order). Not thread-safe: the caller synchronizes.
     *
     * V5.0.8853 §A_LAUNCH_IN_PRIMITIVES — 8840 kept each launch's traders in a HashMap of wallet strings to boxed records:
     * up to 3,000 live launches x 600 wallets, roughly 200 bytes a wallet (entry, record and the 44-character address).
     * The wallets are now 64-bit keys in an open-addressed table of primitive arrays that grows from 32 slots to at most
     * [WALLET_SLOTS_8853] (40 bytes a slot), the creator matched by the same key. Same definitions, same results.
     */
    class Fold8840(val creator: String, val createSig: String) {
        private val creatorKey = if (creator.isBlank()) 0L else walletKey8853(creator)
        private var keys = LongArray(32); private var bought = DoubleArray(32); private var sold = DoubleArray(32)
        private var firstBuyMs = LongArray(32) { Long.MAX_VALUE }; private var firstTokens = DoubleArray(32)
        private var used = 0
        private var createMs = Long.MIN_VALUE
        private var firstMs = Long.MIN_VALUE
        var peakPrice = 0.0; private set
        private var devBought = 0.0; private var devSold = 0.0; private var devSold10m = 0.0
        private var overflow = 0
        var prints = 0; private set

        /** The slot of [key] (inserting it when there is room), or -1 when the table is full. */
        private fun slot(key: Long, insert: Boolean): Int {
            if (insert && (used + 1) * 10 > keys.size * 6 && keys.size < WALLET_SLOTS_8853) grow()
            val mask = keys.size - 1
            var i = ((key xor (key ushr 32)).toInt() and Int.MAX_VALUE) and mask
            while (true) {
                val k = keys[i]
                if (k == key) return i
                if (k == 0L) {
                    if (!insert || used >= MAX_WALLETS) return -1
                    keys[i] = key; used++; return i
                }
                i = (i + 1) and mask
            }
        }

        private fun grow() {
            val ok = keys; val ob = bought; val os = sold; val of = firstBuyMs; val ot = firstTokens
            val n = ok.size * 2
            keys = LongArray(n); bought = DoubleArray(n); sold = DoubleArray(n); firstBuyMs = LongArray(n) { Long.MAX_VALUE }; firstTokens = DoubleArray(n)
            used = 0
            for (j in ok.indices) if (ok[j] != 0L) {
                val i = slot(ok[j], insert = true)
                bought[i] = ob[j]; sold[i] = os[j]; firstBuyMs[i] = of[j]; firstTokens[i] = ot[j]
            }
        }

        /** One print: chain time [tMs], price (SOL / token), SOL size, side, trader [wallet] ("" unnamed), signature. */
        fun add(tMs: Long, priceSol: Double, sol: Double, buy: Boolean, wallet: String, sig: String) {
            if (!(priceSol > 0.0) || !priceSol.isFinite() || !(sol > 0.0) || !sol.isFinite()) return
            prints++
            if (firstMs == Long.MIN_VALUE) firstMs = tMs
            if (createMs == Long.MIN_VALUE && createSig.isNotBlank() && sig == createSig) createMs = tMs
            if (priceSol > peakPrice) peakPrice = priceSol
            val tokens = sol / priceSol
            if (wallet.isBlank()) return
            val key = walletKey8853(wallet)
            val created = if (createMs != Long.MIN_VALUE) createMs else firstMs
            if (creatorKey != 0L && key == creatorKey) {
                if (buy) devBought += tokens else { devSold += tokens; if (tMs - created <= 600_000L) devSold10m += tokens }
            }
            val i = slot(key, insert = true)
            if (i < 0) { overflow++; return }
            if (buy) {
                bought[i] += tokens
                if (firstBuyMs[i] == Long.MAX_VALUE) { firstBuyMs[i] = tMs; firstTokens[i] = tokens }
                else if (tMs / 1000L == firstBuyMs[i] / 1000L) firstTokens[i] += tokens   // the same chain second
            } else sold[i] += tokens
        }

        fun snapshot(): Facts {
            val created = if (createMs != Long.MIN_VALUE) createMs else firstMs
            val createdSec = if (created == Long.MIN_VALUE) Long.MIN_VALUE else created / 1000L
            var bundleN = 0; var bundleTok = 0.0; var sniperN = 0; var sniperTok = 0.0; var buyers = 0
            val held = DoubleArray(used); var h = 0
            for (i in keys.indices) {
                if (keys[i] == 0L) continue
                if (bought[i] > 0.0) buyers++
                held[h++] = maxOf(0.0, bought[i] - sold[i])
                if (keys[i] == creatorKey || firstBuyMs[i] == Long.MAX_VALUE || createdSec == Long.MIN_VALUE) continue
                val sec = firstBuyMs[i] / 1000L
                if (sec == createdSec) { bundleN++; bundleTok += firstTokens[i] }
                if (sec - createdSec in 0L..2L) { sniperN++; sniperTok += firstTokens[i] }
            }
            held.sortDescending()
            fun pct(tok: Double) = 100.0 * tok / SUPPLY_TOKENS
            val dev = if (creatorKey != 0L) slot(creatorKey, insert = false) else -1
            val devInit = when {
                creatorKey == 0L -> Double.NaN
                dev < 0 || firstBuyMs[dev] == Long.MAX_VALUE -> 0.0
                createdSec != Long.MIN_VALUE && firstBuyMs[dev] / 1000L == createdSec -> pct(firstTokens[dev])
                else -> 0.0
            }
            var top5 = 0.0; for (k in 0 until minOf(5, held.size)) top5 += held[k]
            return Facts(
                creatorKnown = creatorKey != 0L,
                devInitPct = devInit,
                devHeldPct = if (creatorKey == 0L) Double.NaN else pct(maxOf(0.0, devBought - devSold)),
                devSoldFrac = if (devBought > 0.0) (devSold / devBought).coerceAtMost(1.0) else Double.NaN,
                devSold10mFrac = if (devBought > 0.0) (devSold10m / devBought).coerceAtMost(1.0) else Double.NaN,
                bundleBuyers = bundleN, bundlePct = pct(bundleTok), sniperBuyers = sniperN, sniperPct = pct(sniperTok),
                top1Pct = if (held.isEmpty()) Double.NaN else pct(held[0]),
                top5Pct = if (held.isEmpty()) Double.NaN else pct(top5),
                uniqueBuyers = buyers, prints = prints,
            )
        }
    }

    /** Pure: the facts as JSON fields (the export's and the dataset's names). */
    fun toJson8840(f: Facts): org.json.JSONObject = org.json.JSONObject().apply {
        fun put2(k: String, v: Double) { if (v.isFinite()) put(k, Math.round(v * 1000.0) / 1000.0) }
        put("creatorKnown", if (f.creatorKnown) 1 else 0)
        put2("devInitPct", f.devInitPct); put2("devHeldPct", f.devHeldPct); put2("devSoldFrac", f.devSoldFrac); put2("devSold10mFrac", f.devSold10mFrac)
        put("bundleBuyers", f.bundleBuyers); put2("bundlePct", f.bundlePct); put("sniperBuyers", f.sniperBuyers); put2("sniperPct", f.sniperPct)
        put2("top1Pct", f.top1Pct); put2("top5Pct", f.top5Pct); put("uniqueBuyers", f.uniqueBuyers); put("prints", f.prints)
    }

    // ── the creator's history ──

    /** Per creator: launches seen to retire, those that reached [RUNNER_CAP_USD], those dumped by the creator in 10 min. */
    object DevHistory8840 {
        class H(var launches: Int = 0, var runners: Int = 0, var dumps: Int = 0)
        private const val MAX = 50_000
        private val map = object : LinkedHashMap<String, H>(4_096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, H>?): Boolean = size > MAX
        }
        @Volatile private var loaded = false
        @Volatile private var dirty = false
        private var lastSaveMs = 0L

        /** The creator's record (launches, runners, dumps) as of now; zeros for an unseen creator. */
        fun of(creator: String): Triple<Int, Int, Int> {
            if (creator.isBlank()) return Triple(0, 0, 0)
            ensureLoaded()
            return synchronized(map) { map[creator]?.let { Triple(it.launches, it.runners, it.dumps) } ?: Triple(0, 0, 0) }
        }

        fun note(creator: String, runner: Boolean, dump: Boolean) {
            if (creator.isBlank()) return
            ensureLoaded()
            synchronized(map) { val h = map.getOrPut(creator) { H() }; h.launches++; if (runner) h.runners++; if (dump) h.dumps++ }
            dirty = true
            maybeSave(System.currentTimeMillis())
        }

        private fun file(): File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { File(it.filesDir, "dev_history_8840.tsv") }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                loaded = true
                val f = file() ?: return
                if (!f.exists()) return
                try {
                    f.forEachLine { l ->
                        val p = l.split('\t')
                        if (p.size == 4) synchronized(map) { map[p[0]] = H(p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0) }
                    }
                } catch (_: Throwable) {}
            }
        }

        private fun maybeSave(nowMs: Long) {
            if (!dirty || nowMs - lastSaveMs < 10 * 60_000L) return
            lastSaveMs = nowMs; dirty = false
            val f = file() ?: return
            try {
                val text = synchronized(map) { map.entries.joinToString("\n") { (k, h) -> "$k\t${h.launches}\t${h.runners}\t${h.dumps}" } }
                val tmp = File(f.path + ".tmp"); tmp.writeText(text); tmp.renameTo(f)
            } catch (_: Throwable) {}
        }

        fun size(): Int = synchronized(map) { map.size }
        internal fun resetForTest8840() { synchronized(map) { map.clear() }; loaded = true; dirty = false }
    }

    // ── live ──

    private class Live(val bornMs: Long, val fold: Fold8840, val prior: Triple<Int, Int, Int>)
    private val live = ConcurrentHashMap<String, Live>()
    private val creators = ConcurrentHashMap<String, Pair<String, String>>()   // mint -> (creator, create signature), until first print
    private val creates = AtomicLong(); private val printsSeen = AtomicLong(); private val retired = AtomicLong(); private val full = AtomicLong()

    /** A decoded create (pump.fun CreateEvent `user`, or PumpPortal's create frame): who created [mint], in which transaction. */
    fun onCreate8840(mint: String, creator: String, createSig: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || creator.isBlank()) return
        if (creators.putIfAbsent(mint, creator to createSig) != null) return
        creates.incrementAndGet()
        if (creators.size > MAX_LIVE * 2) creators.keys.take(creators.size - MAX_LIVE).forEach { creators.remove(it) }
        val prior = DevHistory8840.of(creator)
        try { LaunchHour8628.creator8840(mint, creator, createSig, prior.first, prior.second, prior.third, nowMs) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.OperatorRegistry.set(mint, creator) } catch (_: Throwable) {}
    }

    /** Every witnessed pump.fun print (TradeTape8100.launchEvidence8750). */
    fun onPrint8840(mint: String, eventMs: Long, priceSol: Double, sol: Double, isBuy: Boolean, wallet: String, sig: String,
                    nowMs: Long = System.currentTimeMillis()) {
        if (!mint.endsWith("pump") || eventMs <= 0L) return
        if (printsSeen.incrementAndGet() % 2_000L == 0L) sweep8840(nowMs)   // launches past their hour retire into their creator's history
        var l = live[mint]
        if (l == null) {
            if (live.size >= MAX_LIVE) sweep8840(nowMs)
            if (live.size >= MAX_LIVE) { full.incrementAndGet(); return }
            val (creator, sig0) = creators[mint] ?: ("" to "")
            val born = try { CanonicalTokenBirthTime7440.peekBirthMs8418(mint, nowMs) } catch (_: Throwable) { null } ?: eventMs
            if (nowMs - born > HOUR) return
            l = live.computeIfAbsent(mint) { Live(born, Fold8840(creator, sig0), DevHistory8840.of(creator)) }
        }
        synchronized(l.fold) { l.fold.add(eventMs, priceSol, sol, isBuy, wallet.trim(), sig.trim()) }
    }

    /** The live facts of [mint] with its creator's prior record (launches, runners, dumps), or null. */
    fun snapshot8840(mint: String): Pair<Facts, Triple<Int, Int, Int>>? {
        val l = live[mint] ?: return null
        return synchronized(l.fold) { l.fold.snapshot() } to l.prior
    }

    /** Retires launches past their hour into their creator's history. */
    internal fun sweep8840(nowMs: Long) {
        for ((m, l) in live) {
            if (nowMs - l.bornMs < HOUR) continue
            if (!live.remove(m, l)) continue
            retired.incrementAndGet()
            creators.remove(m)
            val f = synchronized(l.fold) { l.fold.snapshot() }
            val solUsd = try { ShadowMark8360.solUsd8756(nowMs) } catch (_: Throwable) { Double.NaN }
            val peakCap = synchronized(l.fold) { l.fold.peakPrice } * SUPPLY_TOKENS * solUsd
            if (l.fold.creator.isNotBlank() && solUsd.isFinite() && solUsd > 0.0)
                DevHistory8840.note(l.fold.creator, peakCap >= RUNNER_CAP_USD, f.devSold10mFrac.isFinite() && f.devSold10mFrac >= DUMP_SOLD_FRAC)
        }
    }

    fun statusLine8840(): String {
        return "launchFacts8840[creates=${creates.get()} prints=${printsSeen.get()} live=${live.size}/$MAX_LIVE full=${full.get()} retired=${retired.get()} " +
            "creatorsKnown=${DevHistory8840.size()} rule=creator, dev buy/hold/sell, creation-second bundle, 2 s snipers, top holders, distinct buyers — " +
            "from the decoded pump.fun create and trade events; a creator's history as of the launch's birth]"
    }

    internal fun resetForTest8840() { live.clear(); creators.clear(); creates.set(0); printsSeen.set(0); retired.set(0); full.set(0) }
}
