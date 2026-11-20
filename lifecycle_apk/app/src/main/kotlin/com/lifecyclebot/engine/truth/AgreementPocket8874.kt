package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8874 §WHERE_THE_SCORE_AND_THE_FORECAST_AGREE — owner, 20 Nov: "please finish out the full list of work from our
 * document please and remember this has to boost profits and return to capturing runners". The plan's first admit path:
 * the coins on which the V3 score and the edge model's forecast agree.
 *
 * The owner's edge datasets, 9-19 Nov (178,113 labelled decisions after EdgeModel8537.replayAccept8642's own rules: filled,
 * venue-scoped, a new price seen), one label per coin per lane per 15 minutes, gain kept to the pocket cap: coins NOT minted
 * on pump.fun, at a $5k-$20k cap, with a V3 score of 23 or more and a forecast of +10 % or more. 611 lane labels, +35.2 %
 * net each at the label. Per lane, mean less two standard errors: MANIPULATED n81 +40.5 % (+23.8), CORE n71 +44.3 (+24.9),
 * EXPRESS n70 +34.6 (+16.3), MOONSHOT n68 +36.2 (+17.3), SHITCOIN n66 +40.1 (+20.8), CYCLIC n58 +36.7 (+14.9), QUALITY n47
 * +26.4 (+3.6), BLUECHIP n45 +30.9 (+8.0), TREASURY n35 +23.2 (+2.7) — each positive in both halves of the period, on 7-10
 * separate days; CASHGEN and PROJECT_SNIPER are positive but not yet proven. One coin per day: positive on every day from
 * 9 to 19 Nov. The same rule on pump.fun mints: n47, +0.8 %. Nearly every one of these coins is under five minutes old.
 * About three in four of them already sat in a proven PayingCell8723 cell at the moment of decision; the rest (141 lane
 * labels, +31.4 %) carried no admitting evidence.
 *
 * This is PayingCell8723's machinery on that pocket: one record per LANE (8724: a lane is authorised only by its own labels,
 * at its own horizon and cost), decaying, net as printed (no stop assumed), the gain kept to the pocket cap, PROVEN at 30+
 * labels with the mean above zero by two standard errors, seeded from the device's own dataset (DatasetReplay8863) and then
 * filed from every live label (EdgeModel8537.learn). A coin in the pocket whose lane's record is proven carries it as
 * market-cell evidence (CandidateAdmission8136), with the same precedence as a paying cell: it answers the lane-wide priors
 * and the generic unobserved-flow / unread-stage waits; hard safety, structure, sellability, the LP-lock rule, executable
 * economics, the coin's own chart shapes and the losing record of the setup it trades as all still outrank it. A lane
 * whose pocket stops paying stops admitting by itself. The sizing is the lane's own.
 */
object AgreementPocket8874 {
    private const val V3_MIN_8874 = 23.0
    private const val FORECAST_MIN_PCT_8874 = 10.0
    private const val MCAP_LO_USD_8874 = 5_000.0
    private const val MCAP_HI_USD_8874 = 20_000.0
    const val KIND_8874 = "AGREEMENT_POCKET"
    private const val MIN_N = PayingCell8723.MIN_N
    private const val PROOF_SE = 2.0
    private const val DECAY = PocketLedger8610.DECAY
    private const val REFILE_MS = 15L * 60_000L

    private class Cell { var n = 0.0; var sum = 0.0; var sq = 0.0; var ran = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell>()
    private val lastFiled = ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val admission = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "agreement8874").apply { isDaemon = true } }

    /** Pure: is a coin minted at [mint], at [mcapUsd], with V3 score [v3] and forecast [predPct] (net %) in the pocket? */
    fun inPocket8874(mint: String, mcapUsd: Double, v3: Double, predPct: Double): Boolean =
        // V5.0.8878 — a Solana mint: the dataset also labels the EVM desks' coins ("bsc|0x..."), which are not this pocket
        com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint) && MarketCohort8701.origin8701(mint) == "OTHER" &&
            mcapUsd.isFinite() && mcapUsd >= MCAP_LO_USD_8874 && mcapUsd < MCAP_HI_USD_8874 &&
            v3.isFinite() && v3 >= V3_MIN_8874 && predPct.isFinite() && predPct >= FORECAST_MIN_PCT_8874

    /** Pure: the record [lane]'s pocket coins are filed under; null for a blank lane. */
    fun key8874(lane: String): String? = lane.trim().uppercase().takeIf { it.isNotBlank() }?.let { "${it}_AGREE" }

    private fun add(mint: String, key: String, atMs: Long, net: Double) {
        val last = lastFiled["$mint|$key"]
        if (last != null && atMs >= last && atMs - last < REFILE_MS) return
        if (lastFiled.size > 20_000) lastFiled.entries.removeIf { atMs - it.value > REFILE_MS }
        lastFiled["$mint|$key"] = atMs
        addCoin8895(mint, key.removeSuffix("_AGREE"), atMs, net)   // V5.0.8895 — and the coin record
        val y = PayingCell8723.outcome8723(net)
        val c = cells.getOrPut(key) { Cell() }
        synchronized(c) {
            c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y
            c.ran = c.ran * DECAY + (if (net >= 100.0) 1.0 else 0.0)
        }
        learned.incrementAndGet()
    }

    /** EdgeModel8537.learn: file one labelled decision when its coin was in the pocket at the decision. */
    fun learn8874(mint: String, lane: String, mcapUsd: Double, v3: Double, predPct: Double, net: Double, atMs: Long) {
        if (!net.isFinite() || !inPocket8874(mint, mcapUsd, v3, predPct)) return
        val key = key8874(lane) ?: return
        ensureLoaded()
        io.execute { try { add(mint, key, atMs, net) } catch (_: Throwable) {} }
    }

    private fun recordOf(key: String): DoubleArray? {
        if (!ready) { ensureLoaded(); return null }
        val c = cells[key] ?: return null
        return synchronized(c) { doubleArrayOf(c.n, c.sum, c.sq, c.ran) }
    }

    /** Pure: the admission record of a lane's pocket [r] = (n, sum, sum of squares, runners), when it is proven to pay. */
    fun evidenceOf8874(key: String, r: DoubleArray?): CandidateAdmission8136.Evidence8136? {
        if (r == null || r.size < 4 || !PayingCell8723.paying8723(r[0], r[1], r[2])) return null
        val m = r[1] / r[0]
        val se = kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
        // carried at the proof's own margin, as PayingCell8723 does: the admission's mean - SE is the proven lower bound
        return CandidateAdmission8136.Evidence8136(KIND_8874, key, r[0].toInt(), m, PROOF_SE * se, runnerRate = r[3] / r[0])
    }

    /**
     * CandidateAdmission8136.candidateFor8136: [ts] in [lane] as admission evidence when the coin is in the pocket now
     * ([predPct] = the edge model's forecast for this decision) and the lane's pocket record is proven, else null.
     */
    fun evidence8874(ts: TokenState, lane: String, predPct: Double?, nowMs: Long = System.currentTimeMillis()): CandidateAdmission8136.Evidence8136? = try {
        val pred = predPct ?: Double.NaN
        val solana = try { com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint) } catch (_: Throwable) { false }
        if (!solana || !pred.isFinite() || pred < FORECAST_MIN_PCT_8874 || MarketCohort8701.origin8701(ts.mint) != "OTHER") null else {
            val raw = EdgeModel8537.rawFor8610(ts, lane, nowMs)
            val key = key8874(raw.lane)
            if (key == null || !inPocket8874(ts.mint, raw.mcap, raw.v3, pred)) null
            else (evidenceOf8874(key, recordOf(key)) ?: coinEvidence8895(raw.lane, recordOf(key)))   // V5.0.8895 — then the coin record
                ?.also { e -> served.computeIfAbsent(e.id) { AtomicLong(0L) }.incrementAndGet() }
        }
    } catch (_: Throwable) { null }

    /** CandidateAdmission8136 (LIVE): what the admission decided of a coin that carried the pocket — counted, never acted on. */
    fun noteAdmission8874(terminal: String, reason: String) {
        val k = "$terminal:${reason.take(48)}"
        if (admission.size < 64 || admission.containsKey(k)) admission.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun ensureLoaded() {
        if (ready || loading) return
        synchronized(this) { if (ready || loading) return; loading = true }
        io.execute {
            // seeded by the one shared read of the dataset (DatasetReplay8863); a live label queued behind it is filed after.
            try {
                ready = DatasetReplay8863.await8863()
                if (!ready) PipelineHealthCollector.labelInc("AGREEMENT_POCKET_8874_LOAD_FAILED")
            }
            catch (_: Throwable) { try { PipelineHealthCollector.labelInc("AGREEMENT_POCKET_8874_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { loading = false }
        }
    }

    /** DatasetReplay8863: one accepted row of the device's own labelled decisions, oldest first as they were written. */
    internal fun seedRow8863(o: JSONObject) {
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val net = d("net"); if (!net.isFinite()) return
        val mint = o.optString("mint")
        if (!inPocket8874(mint, d("mcap"), d("v3"), d("pred"))) return
        val key = key8874(o.optString("lane")) ?: return
        add(mint, key, o.optLong("t", 0L), net)
    }

    /** Brief: every lane's pocket record, which are proven, how often each admitted evidence, and what the admission did. */
    fun statusLine8874(): String {
        val rows = cells.entries.map { (k, c) -> synchronized(c) { Triple(k, doubleArrayOf(c.n, c.sum, c.sq, c.ran), PayingCell8723.paying8723(c.n, c.sum, c.sq)) } }
            .sortedByDescending { it.second[0] }
        val recs = rows.joinToString(",") { (k, r, p) ->
            "${k.removeSuffix("_AGREE")}=n${r[0].toInt()}/${"%+.1f".format(r[1] / r[0])}%/run${(100.0 * r[3] / r[0]).toInt()}%${if (p) "*" else ""}" }.ifBlank { "-" }
        val used = served.entries.sortedBy { it.key }.joinToString(",") { "${it.key.removeSuffix("_AGREE")}=${it.value.get()}" }.ifBlank { "-" }
        val adm = admission.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val coin = coinRecord8895()
        val coinLine = if (coin[0] < 1.0) "n0" else "n${coin[0].toInt()}/${"%+.1f".format(coin[1] / coin[0])}%±${"%.1f".format(se8895(coin))}" +
            "/run${(100.0 * coin[3] / coin[0]).toInt()}%${if (PayingCell8723.paying8723(coin[0], coin[1], coin[2])) "*" else ""}"
        return "agreement8874[ready=$ready learned=${learned.get()} coins8895{$coinLine} lanes{$recs} carried{$used} liveAdmission{$adm} " +
            "rule=not pump.fun, cap \$5k-\$20k, V3>=${V3_MIN_8874.toInt()}, forecast>=+${FORECAST_MIN_PCT_8874.toInt()}%; per lane, one label per coin per 15 min, " +
            "* = n>=${MIN_N.toInt()} mean-${PROOF_SE.toInt()}SE>0 (admits as market-cell evidence); 8895: the last $COIN_WINDOW_8895 pocket coins, each at its " +
            "worst meme-lane label, prove the pocket for every meme lane whose own pocket labels are not proven losing]"
    }


    // ── V5.0.8895 §THE_POCKET_IS_PROVEN_ON_COINS ──
    // Owner, 20 Nov: "how do we improve the trade selection logic?? ... you have to stop saying it cant find runners". 8874's
    // pocket had admitted no coin: its proof is per lane at 30 labels, and the same coins are split over 16 lane records — on
    // 5.0.8893 the largest held 25 (MOONSHOT +42.7 %, MANIPULATED n24 +52.5 %, EXPRESS n22 +35.1 %, CORE n20 +61.5 % ...,
    // carried{-}); the phone's dataset replay starts at its learning line, so it never caught up. Counted on COINS the same
    // rule is already proven: 17-20 Nov edge dataset, first decision per coin, 46 coins at +42.8 % +-10.2 (median +30.7 %,
    // 80 % won, +28.9 % without the best three), 3 of them lost half or more inside 15 minutes.
    // The coin record: the last [COIN_WINDOW_8895] pocket coins, each held ONCE at its WORST meme-lane label (outcome8723:
    // gain kept to the pocket cap, a total loss -100 %) among the labels of its first 15 minutes — the 8724 audit's point that
    // one lane's outcome must not authorise another is met by giving every lane the outcome of the lane that did worst on
    // that coin. It proves on the paying cell's own bar (30 coins, mean above zero by two SE). A meme lane carries it when its
    // own per-lane pocket record has not been proven LOSING (30 labels, mean below zero by two SE); a lane whose own record
    // proves first keeps using that one. It is the same evidence kind with the same precedence as before: every hard safety
    // check, the LP-lock rule, sellability, executable economics, the coin's own avoid shapes and the named setup it trades as
    // still decide. Nothing is refused here that was not refused before.
    private const val COIN_WINDOW_8895 = 200
    private const val COIN_REVISE_MS_8895 = 15L * 60_000L
    const val COIN_ID_8895 = "COINS_AGREE"
    private class Coin8895(val firstMs: Long, var worstNet: Double)
    private val coins8895 = LinkedHashMap<String, Coin8895>()   // guarded by itself; oldest first

    /** Pure: is [lane] a meme lane whose labels file the coin record (EdgeModel8537.LANES)? */
    internal fun memeLane8895(lane: String): Boolean = lane.trim().uppercase() in EdgeModel8537.LANES

    private fun addCoin8895(mint: String, lane: String, atMs: Long, net: Double) {
        if (!memeLane8895(lane) || !net.isFinite()) return
        synchronized(coins8895) {
            val c = coins8895[mint]
            if (c == null) {
                coins8895[mint] = Coin8895(atMs, net)
                while (coins8895.size > COIN_WINDOW_8895) coins8895.remove(coins8895.keys.first())
            } else if (kotlin.math.abs(atMs - c.firstMs) < COIN_REVISE_MS_8895 && net < c.worstNet) c.worstNet = net
        }
    }

    /** (n, sum, sum of squares, runners) of the coin record: each coin at its worst meme-lane label. */
    internal fun coinRecord8895(): DoubleArray = synchronized(coins8895) {
        var n = 0.0; var sum = 0.0; var sq = 0.0; var ran = 0.0
        for (c in coins8895.values) {
            val y = PayingCell8723.outcome8723(c.worstNet)
            n += 1.0; sum += y; sq += y * y; if (c.worstNet >= 100.0) ran += 1.0
        }
        doubleArrayOf(n, sum, sq, ran)
    }

    private fun se8895(r: DoubleArray): Double {
        if (r[0] < 2.0) return Double.NaN
        val m = r[1] / r[0]
        return kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
    }

    /** Pure: is a lane pocket record [r] (n, sum, sq, ...) proven LOSING — the mirror of PayingCell8723.paying8723? */
    internal fun losing8895(r: DoubleArray?): Boolean {
        if (r == null || r.size < 3 || !(r[0] >= MIN_N)) return false
        val m = r[1] / r[0]
        val se = kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
        return m.isFinite() && se.isFinite() && m + PROOF_SE * se < 0.0
    }

    /** Pure: the coin record [coin] as [lane]'s evidence, given that lane's own pocket record [laneRecord]; null unless it proves. */
    internal fun coinEvidenceOf8895(lane: String, laneRecord: DoubleArray?, coin: DoubleArray): CandidateAdmission8136.Evidence8136? {
        if (!memeLane8895(lane) || losing8895(laneRecord) || !PayingCell8723.paying8723(coin[0], coin[1], coin[2])) return null
        val m = coin[1] / coin[0]
        return CandidateAdmission8136.Evidence8136(KIND_8874, COIN_ID_8895, coin[0].toInt(), m, PROOF_SE * se8895(coin), runnerRate = coin[3] / coin[0])
    }

    private fun coinEvidence8895(lane: String, laneRecord: DoubleArray?): CandidateAdmission8136.Evidence8136? =
        if (!ready) null else coinEvidenceOf8895(lane, laneRecord, coinRecord8895())

    internal fun addCoinForTest8895(lane: String, mint: String, net: Double, atMs: Long) = addCoin8895(mint, lane, atMs, net)

    internal fun resetForTest8874() { cells.clear(); lastFiled.clear(); served.clear(); admission.clear(); learned.set(0L); ready = true; synchronized(coins8895) { coins8895.clear() } }
    internal fun addForTest8874(lane: String, mint: String, net: Double, atMs: Long) { add(mint, key8874(lane) ?: return, atMs, net) }
    internal fun recordForTest8874(lane: String): DoubleArray? = key8874(lane)?.let { recordOf(it) }
}
