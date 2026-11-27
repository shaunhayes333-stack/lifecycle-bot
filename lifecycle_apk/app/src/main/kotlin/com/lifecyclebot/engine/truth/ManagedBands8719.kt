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
    private val seen9043 = LinkedHashSet<String>()
    private const val MAX_SEEN_9043 = 32_768
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val opened = ConcurrentHashMap<String, AtomicLong>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "managedBands8719").apply { isDaemon = true } }

    /** Pure: what a position with a [stopPct] stop and a [costPct] round trip banks of a label's [netPct]. */
    fun managed8719(netPct: Double, stopPct: Double, costPct: Double): Double {
        return FrozenEdgeTarget9043.compute(netPct, stopPct, costPct)
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

    private fun add(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double, stopPct: Double, decisionKey9043: String? = null) {
        if (!decisionKey9043.isNullOrBlank()) synchronized(seen9043) {
            if (!seen9043.add(decisionKey9043)) return
            if (seen9043.size > MAX_SEEN_9043) seen9043.iterator().run { next(); remove() }
        }
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
    fun learn8719(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double, stopPct9043: Double? = null,
                  decisionKey9043: String? = null) {
        if (!net.isFinite() || lane.isBlank()) return
        ensureLoaded()
        val l = lane.trim().uppercase()
        val frozenStop = stopPct9043?.takeIf { it.isFinite() && it > 0.0 }
        io.execute { try { DatasetReplay8863.afterSeed9043 {
            ready = true; add(l, liqUsd, mcapUsd, net, costPct, frozenStop ?: stopOf(l), decisionKey9043)
        } } catch (_: Throwable) {} }
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
            // V5.0.8863 — seeded by the one shared read of the dataset (DatasetReplay8863, job "datasetSeed"); this worker
            // waits for it, so a live label queued behind it is filed after the replay.
            try {
                ready = DatasetReplay8863.await8863()
                if (!ready) PipelineHealthCollector.labelInc("MANAGED_BANDS_8719_LOAD_FAILED")
            }
            catch (_: Throwable) { try { PipelineHealthCollector.labelInc("MANAGED_BANDS_8719_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { loading = false }
        }
    }

    // each lane's stop, read once per replay (DatasetReplay8863 runs one pass at a time)
    private val seedStops8863 = HashMap<String, Double>()

    /** DatasetReplay8863: one accepted row of the device's own labelled decisions, as PocketLedger8610 takes it. */
    internal fun seedRow8863(o: JSONObject) {
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val receipt = FrozenEdgeTarget9043.read9043(o)
        val net = receipt?.inputNet ?: d("net"); if (!net.isFinite()) return
        val lane = o.optString("lane").trim().uppercase(); if (lane.isBlank()) return
        val mint = o.optString("mint"); val at = o.optLong("t"); val horizon = o.optLong("horizonMs")
        val key = if (mint.isNotBlank() && at > 0L && horizon > 0L) "$mint|$at|$lane|$horizon" else null
        add(lane, d("liq"), d("mcap"), net, receipt?.costPct ?: d("cost"), receipt?.stopPct ?: seedStops8863.getOrPut(lane) { stopOf(lane) }, key)
    }

    /** DatasetReplay8863: the replay is over. */
    internal fun seedDone8863() { seedStops8863.clear() }

    fun statusLine8719(): String {
        val proven = cells.entries.filter { (_, c) -> synchronized(c) { proven8719(c.n, c.sum, c.sq) } }
            .sortedBy { it.key }.take(12).joinToString(",") { (k, c) -> synchronized(c) { "$k=n${c.n.toInt()}/${"%+.1f".format(c.sum / c.n)}%" } }.ifBlank { "-" }
        val used = opened.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "managedBands8719[ready=$ready cells=${cells.size} learned=${learned.get()} proven{$proven} floorsOpened{$used} rule=lane x band label, loss capped at stop+cost, n>=${MIN_N.toInt()} mean-se>0]"
    }

    internal fun resetForTest8719() { clearSeed9043(); opened.clear(); ready = true }
    internal fun addForTest8719(lane: String, liqUsd: Double, mcapUsd: Double, net: Double, costPct: Double, stopPct: Double) =
        add(lane.trim().uppercase(), liqUsd, mcapUsd, net, costPct, stopPct)

    // V5.0.9043: called only by the shared seed owner, before live-label workers leave await8863.
    internal fun writeSeed9043(out: java.io.DataOutputStream) {
        out.writeLong(learned.get())
        out.writeInt(cells.size)
        for ((key, cell) in cells) synchronized(cell) {
            out.writeUTF(key); out.writeDouble(cell.n); out.writeDouble(cell.sum); out.writeDouble(cell.sq)

        }
        synchronized(seen9043) { out.writeInt(seen9043.size); for (key in seen9043) out.writeUTF(key) }
    }

    internal fun readSeed9043(input: java.io.DataInputStream) {
        clearSeed9043()
        learned.set(input.readLong().also { require(it >= 0L) })
        repeat(DatasetSeedCheckpoint9043.count(input)) {
            val key = input.readUTF()
            val cell = Cell()
            cell.n = DatasetSeedCheckpoint9043.finite(input).also { require(it >= 0.0) }
            cell.sum = DatasetSeedCheckpoint9043.finite(input)
            cell.sq = DatasetSeedCheckpoint9043.finite(input).also { require(it >= 0.0) }

            require(cells.putIfAbsent(key, cell) == null) { "duplicate checkpoint cell" }
        }
        synchronized(seen9043) {
            repeat(DatasetSeedCheckpoint9043.count(input, MAX_SEEN_9043)) { require(seen9043.add(input.readUTF())) }
        }
    }

    internal fun clearSeed9043() {
        cells.clear(); learned.set(0L)



        seedStops8863.clear()
        synchronized(seen9043) { seen9043.clear() }
    }
}
