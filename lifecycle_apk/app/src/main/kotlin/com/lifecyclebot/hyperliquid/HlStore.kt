package com.lifecyclebot.hyperliquid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeMap

/**
 * V5.0.8797 — the desk's own market history, one file per coin, grown a little every day and never fetched twice:
 *  - bars: completed daily candles (open, high, low, close, notional volume) from the exchange;
 *  - funding: each completed day's summed hourly funding rate (longs pay a positive rate);
 *  - oi / premium: the exchange keeps no history of open interest or premium, so the desk records its own from the
 *    live context it reads every few minutes (open interest in USD at the day's last read, the day's mean premium).
 *    These start empty and fill in from the day the desk first runs; a feature that needs them reads "unknown" until then.
 */
class HlHistory(val coin: String) {
    val bars = TreeMap<Long, DoubleArray>()        // day -> [open, high, low, close, notional volume USD]
    val funding = TreeMap<Long, Double>()          // day -> summed hourly funding rate (complete days only)
    var fundingFromDay = -1L                       // first day funding was asked for (days before it: never listed)
    val oi = TreeMap<Long, Double>()               // day -> open interest USD (last read of the day)
    val premium = TreeMap<Long, DoubleArray>()     // day -> [sum, reads]

    fun lastBarDay(): Long = if (bars.isEmpty()) -1L else bars.lastKey()
    fun lastFundingDay(): Long = if (funding.isEmpty()) -1L else funding.lastKey()

    /** Records one live read of open interest (in coin units) and premium on [day]. */
    fun observe(day: Long, openInterest: Double, markPx: Double, prem: Double) {
        if (openInterest.isFinite() && markPx > 0.0) oi[day] = openInterest * markPx
        if (prem.isFinite()) premium.getOrPut(day) { doubleArrayOf(0.0, 0.0) }.let { it[0] += prem; it[1] += 1.0 }
    }

    /** Drops everything older than [keepFromDay]. */
    fun trim(keepFromDay: Long) {
        bars.headMap(keepFromDay).clear(); funding.headMap(keepFromDay).clear()
        oi.headMap(keepFromDay).clear(); premium.headMap(keepFromDay).clear()
    }
}

object HlStore {
    private const val DIR = "hl_store_8797"

    /** Pure: a history as JSON. */
    fun toJson(h: HlHistory): JSONObject {
        fun days(m: TreeMap<Long, Double>) = JSONArray().also { a -> for ((d, v) in m) a.put(JSONArray().put(d).put(v)) }
        return JSONObject().put("coin", h.coin).put("fundingFromDay", h.fundingFromDay)
            .put("bars", JSONArray().also { a -> for ((d, b) in h.bars) a.put(JSONArray().put(d).also { r -> for (x in b) r.put(x) }) })
            .put("funding", days(h.funding)).put("oi", days(h.oi))
            .put("premium", JSONArray().also { a -> for ((d, p) in h.premium) a.put(JSONArray().put(d).put(p[0]).put(p[1])) })
    }

    /** Pure: a history from JSON; malformed rows are skipped, never guessed. */
    fun fromJson(o: JSONObject): HlHistory {
        val h = HlHistory(o.getString("coin"))
        h.fundingFromDay = o.optLong("fundingFromDay", -1L)
        o.optJSONArray("bars")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                if (r.length() < 6) continue
                val b = DoubleArray(5) { r.optDouble(it + 1, Double.NaN) }
                if (b[3] > 0.0) h.bars[r.getLong(0)] = b
            }
        }
        fun days(k: String, m: TreeMap<Long, Double>) = o.optJSONArray(k)?.let { a ->
            for (i in 0 until a.length()) { val r = a.optJSONArray(i) ?: continue; val v = r.optDouble(1, Double.NaN); if (v.isFinite()) m[r.getLong(0)] = v }
        }
        days("funding", h.funding); days("oi", h.oi)
        o.optJSONArray("premium")?.let { a ->
            for (i in 0 until a.length()) { val r = a.optJSONArray(i) ?: continue; if (r.length() >= 3) h.premium[r.getLong(0)] = doubleArrayOf(r.getDouble(1), r.getDouble(2)) }
        }
        return h
    }

    private fun fileOf(dir: File, coin: String) = File(File(dir, DIR), coin.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun load(dir: File, coin: String): HlHistory = try {
        fileOf(dir, coin).takeIf { it.exists() }?.let { fromJson(JSONObject(it.readText())).takeIf { h -> h.coin == coin } } ?: HlHistory(coin)
    } catch (_: Throwable) { HlHistory(coin) }

    fun save(dir: File, h: HlHistory) {
        try {
            val f = fileOf(dir, h.coin)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(toJson(h).toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }
}
