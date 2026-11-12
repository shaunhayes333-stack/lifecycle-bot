package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8719 §FLOORS_ARE_LEARNED_FROM_WHAT_THE_LANE_WOULD_HAVE_BANKED — owner: "the liq floor and market cap floor should be
 * fluid. there isnt meant to be anything fixed or unable to have a learnt edge help tune decide self correct".
 *
 * Both floors were already "fluid" on paper and could not move:
 *  - the market-cap floor (LaneHunter7297.fluidBand) steps down only when the lane's CLOSED trades in the bucket below it
 *    were profitable — and the floor refuses every coin in that bucket, so it never has a close to learn from;
 *  - the liquidity floor (LearnedLiquidityFloor8539) opens when a band's raw mean forward return is proven positive — and a
 *    launch band's raw mean is the average of many -60 % coins and a few runners, which a stop and a ladder do not bank.
 * 8712, 24 h: 13 of the 60 coins that went 4x were refused on these two floors.
 *
 * The evidence here is the forward label of EVERY coin the lane judged, refused ones included (EdgeModel8537.learn), read
 * as the outcome the lane's own exits would have banked: the loss stops at the lane's stop plus one more round trip (the
 * stop fills late), the gain is kept to PocketLedger8610's cap. One record per lane x liquidity band and per lane x
 * market-cap half-decade, decayed like the pocket ledger. A band whose managed record is proven positive ([MIN_N]+ labels,
 * mean - SE > 0) opens that lane's floor for coins in it; when the record turns, the floor closes again. Nothing here can
 * open the live exit-safe pool floor, safety or sellability.
 *
 * On the 8712 dataset (72k labels) this opens the $10k-31.6k market-cap bucket for MOONSHOT, EXPRESS, MANIPULATED, CORE and
 * PROJECT_SNIPER (+2.3 .. +10.8 % managed) and nothing under $10k (-1 .. -12 %): the floors under $10k are right today.
 */
object ManagedBands8719 {
    const val MIN_N = 30.0
    private const val DECAY = PocketLedger8610.DECAY

    private class Cell { var n = 0.0; var sum = 0.0; var sq = 0.0 }
    private val cells = ConcurrentHashMap<String, Cell>()
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val opened = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "managedBands8719").apply { isDaemon = true } }

    /** Pure: what a position with a [stopPct] stop and a [costPct] round trip banks of a label's [netPct]. */
    fun managed8719(netPct: Double, stopPct: Double, costPct: Double): Double {
        val stop = if (stopPct.isFinite() && stopPct > 0.0) stopPct else 100.0
        val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val distance = (stop + cost).coerceAtMost(100.0)
        // V5.0.8724 — owner's audit: "a simulated stop fill is not automatically a real stop fill". A label that fell through
        // twice the stop's distance was a pulled pool or a gap the stop never filled: it counts as it printed.
        if (netPct <= -2.0 * distance) return netPct.coerceAtLeast(-100.0)
        return PocketLedger8610.managed8610(netPct).coerceAtLeast(-distance)
    }

    private fun liqKey(lane: String, liqUsd: Double): String = "LIQ|${lane.trim().uppercase()}|${com.lifecyclebot.engine.LearnedLiquidityFloor8539.band(liqUsd)}"
    private fun capKey(lane: String, bucket: Int): String = "CAP|${lane.trim().uppercase()}|$bucket"

    /** Pure: a record of [n] labels (sum, sum of squares of the managed outcome) is proven positive. */
    fun proven8719(n: Double, sum: Double, sq: Double): Boolean {
        if (!(n >= MIN_N)) return false
        val m = sum / n
        val se = kotlin.math.sqrt(((sq / n) - m * m).coerceAtLeast(0.0) / n)
        return m.isFinite() && se.isFinite() && m - se > 0.0
    }

    private fun stopOf(lane: String): Double = try { FieldManual7715.laneStopPct8392(lane) } catch (_: Throwable) { Double.NaN }

    private fun add(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double, stopPct: Double) {
        val y = managed8719(net, stopPct, costPct)
        val keys = ArrayList<String>(2)
        if (liqUsd.isFinite() && liqUsd > 0.0) keys += liqKey(lane, liqUsd)
        val b = com.lifecyclebot.engine.market.LaneHunter7297.bucketOf(mcapUsd)
        if (b != Int.MIN_VALUE) keys += capKey(lane, b)
        for (k in keys) {
            val c = cells.getOrPut(k) { Cell() }
            synchronized(c) { c.n = c.n * DECAY + 1.0; c.sum = c.sum * DECAY + y; c.sq = c.sq * DECAY + y * y }
        }
        learned.incrementAndGet()
    }

    /** EdgeModel8537.learn: file one labelled decision of [lane]. */
    fun learn8719(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double) {
        if (!net.isFinite() || lane.isBlank()) return
        ensureLoaded()
        val l = lane.trim().uppercase()
        io.execute { try { add(l, liqUsd, mcapUsd, net, costPct, stopOf(l)) } catch (_: Throwable) {} }
    }

    private fun provenKey(key: String): Boolean {
        if (!ready) { ensureLoaded(); return false }
        val c = cells[key] ?: return false
        return synchronized(c) { proven8719(c.n, c.sum, c.sq) }
    }

    /** Does [lane]'s managed record open its liquidity floor for a pool of [liqUsd]? */
    fun liquidityOpen8719(lane: String, liqUsd: Double): Boolean {
        val open = liqUsd.isFinite() && liqUsd > 0.0 && provenKey(liqKey(lane, liqUsd))
        if (open) note("LIQ", lane)
        return open
    }

    /**
     * The market-cap floor of [lane] given the floor it holds now ([floorUsd]): moved down half a decade at a time, at most
     * [maxSteps], while the bucket just under it is proven on the lane's managed record.
     */
    fun capFloor8719(lane: String, floorUsd: Double, maxSteps: Int = 2): Double {
        if (!(floorUsd.isFinite() && floorUsd > 0.0)) return floorUsd
        var lo = floorUsd
        var moved = false
        repeat(maxSteps) {
            val below = com.lifecyclebot.engine.market.LaneHunter7297.bucketOf(lo * 0.9999)
            if (below != Int.MIN_VALUE && provenKey(capKey(lane, below))) { lo /= HALF_DECADE; moved = true } else return@repeat
        }
        if (moved) note("CAP", lane)
        return lo
    }
    private val HALF_DECADE = Math.pow(10.0, 0.5)

    private fun note(kind: String, lane: String) {
        opened.computeIfAbsent("$kind:${lane.trim().uppercase()}") { AtomicLong(0L) }.incrementAndGet()
    }

    private fun ensureLoaded() {
        if (ready || loading) return
        synchronized(this) { if (ready || loading) return; loading = true }
        io.execute {
            try { seedFromDataset(); ready = true }
            catch (_: Throwable) { try { PipelineHealthCollector.labelInc("MANAGED_BANDS_8719_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { loading = false }
        }
    }

    /** Replay the device's own labelled decisions (the dataset EdgeModel8537 exports), as PocketLedger8610 does. */
    private fun seedFromDataset() {
        val f = EdgeModel8537.datasetFile() ?: return
        if (!f.exists()) return
        fun d(o: JSONObject, k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val stops = HashMap<String, Double>()
        f.forEachLine { line ->
            try {
                if (!line.contains("\"horizonMs\"")) return@forEachLine
                val o = JSONObject(line)
                if (!EdgeModel8537.replayAccept8642(o)) return@forEachLine
                val net = d(o, "net"); if (!net.isFinite()) return@forEachLine
                val lane = o.optString("lane").trim().uppercase(); if (lane.isBlank()) return@forEachLine
                add(lane, d(o, "liq"), d(o, "mcap"), net, d(o, "cost"), stops.getOrPut(lane) { stopOf(lane) })
            } catch (_: Throwable) {}
        }
    }

    fun statusLine8719(): String {
        val proven = cells.entries.filter { (_, c) -> synchronized(c) { proven8719(c.n, c.sum, c.sq) } }
            .sortedBy { it.key }.take(12).joinToString(",") { (k, c) -> synchronized(c) { "$k=n${c.n.toInt()}/${"%+.1f".format(c.sum / c.n)}%" } }.ifBlank { "-" }
        val used = opened.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "managedBands8719[ready=$ready cells=${cells.size} learned=${learned.get()} proven{$proven} floorsOpened{$used} rule=lane x band label, loss capped at stop+cost, n>=${MIN_N.toInt()} mean-se>0]"
    }

    internal fun resetForTest8719() { cells.clear(); opened.clear(); learned.set(0L); ready = true }
    internal fun addForTest8719(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double, stopPct: Double) =
        add(lane.trim().uppercase(), liqUsd, mcapUsd, net, costPct, stopPct)
}
