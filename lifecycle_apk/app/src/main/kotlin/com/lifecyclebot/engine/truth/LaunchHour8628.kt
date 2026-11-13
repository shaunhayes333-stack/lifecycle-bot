package com.lifecyclebot.engine.truth

import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.AATEApp
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Measurement only. The first-hour clock starts at witnessed birth, even when no lane evaluates the mint. */
object LaunchHour8628 {
    private const val HOUR = 3_600_000L
    private const val WINDOW = 60_000L
    private const val RETENTION = 48 * HOUR
    private const val NAME = "launch_hour_8628.db"
    private class Write(val sql: String, val args: Array<out Any>)
    private val queue = ArrayBlockingQueue<Write>(8192)
    private val dropped = AtomicLong()
    private val rejectedUnitMismatch8642 = AtomicLong()
    private val worker by lazy {
        Thread({ drain() }, "AATE-launch-hour").apply { isDaemon = true; start() }
    }

    private fun record(sql: String, vararg args: Any) {
        worker
        if (!queue.offer(Write(sql, args))) dropped.incrementAndGet()
    }

    fun birth(mint: String, ms: Long) {
        if (mint.length in 32..44 && System.currentTimeMillis() - ms in 0L..HOUR)
            record("INSERT OR IGNORE INTO birth VALUES(?,?)", mint, ms)
    }

    /**
     * A signed trade's SOL/token quantity must agree with its independently decoded pool/curve price.
     * When one feed uses raw token units rather than UI-decimal tokens, sol/qty can print a phantom 10^6x peak.
     * Do not put that print into the first-hour runner/learning record. The tolerance accommodates steep curves.
     */
    internal fun observedPrice8642(sol: Double, qty: Double, referencePriceSol: Double): Double? {
        if (!sol.isFinite() || sol <= 0 || !qty.isFinite() || qty <= 0 ||
            !referencePriceSol.isFinite() || referencePriceSol <= 0) return null
        val px = sol / qty
        if (!px.isFinite() || px <= 0) return null
        val multiple = px / referencePriceSol
        return px.takeIf { multiple.isFinite() && multiple in (1.0 / 8.0)..8.0 }
    }

    fun trade(mint: String, at: Long, sig: String, buy: Boolean, sol: Double, qty: Double, referencePriceSol: Double) {
        if (sig.isBlank() || System.currentTimeMillis() - at !in 0L..RETENTION) return
        val px = observedPrice8642(sol, qty, referencePriceSol)
        if (px == null) { rejectedUnitMismatch8642.incrementAndGet(); return }
        // V5.0.8741 — the SOL size of every print too: a replay of exits needs the flow, not just the price
        record("INSERT OR IGNORE INTO trade(m,t,s,side,p,v) VALUES(?,?,?,?,?,?)", mint, at, sig, if (buy) 1 else 0, px, sol)
    }

    fun observeDecision(ts: com.lifecyclebot.data.TokenState, lane: String, admit: Boolean, at: Long) {
        val born = CanonicalTokenBirthTime7440.peekBirthMs8418(ts.mint, at) ?: return
        if (at - born !in 0L..HOUR) return
        birth(ts.mint, born)
        val snapshot = com.lifecyclebot.engine.cortex.LanePlaybook7907.peekMatched8323(ts.mint, lane, at)
        val setups = snapshot?.let { (matched, chosen) -> (matched + listOfNotNull(chosen)).distinct() }
            ?: listOf("UNATTRIBUTED")
        val cost = FieldManual7715.labelCostPct8534(ForwardReturnLabeler7731.COST_SIZE_USD_7731, ts.lastLiquidityUsd, ts.mint).first
        for (setup in setups.ifEmpty { listOf("NO_MATCH") }) decision(ts.mint, lane, setup, at, admit, cost)
    }

    private fun decision(mint: String, lane: String, setup: String, at: Long, admit: Boolean, cost: Double) {
        if (cost.isFinite() && cost >= 0)
            record("INSERT OR IGNORE INTO decision VALUES(?,?,?,?,?,?)", mint, at, lane, setup, if (admit) 1 else 0, cost)
    }

    private fun drain() {
        var db: SQLiteDatabase? = null
        var lastTrim = 0L
        val batch = ArrayList<Write>(128)
        while (true) {
            batch.clear()
            batch.add(queue.take())
            queue.drainTo(batch, 127)
            try {
                val store = db ?: run {
                    val ctx = requireNotNull(AATEApp.appContextOrNull())
                    ctx.openOrCreateDatabase(NAME, 0, null).apply {
                        enableWriteAheadLogging()
                        execSQL("CREATE TABLE IF NOT EXISTS birth(m TEXT,b INTEGER, PRIMARY KEY(m,b))")
                        execSQL("CREATE TABLE IF NOT EXISTS trade(m TEXT,t INTEGER,s TEXT,side INTEGER,p REAL, PRIMARY KEY(m,s,side))")
                        execSQL("CREATE INDEX IF NOT EXISTS trade_time ON trade(m,t)")
                        try { execSQL("ALTER TABLE trade ADD COLUMN v REAL") } catch (_: Exception) {}   // V5.0.8741 (exists after first run)
                        execSQL("CREATE TABLE IF NOT EXISTS decision(m TEXT,t INTEGER,l TEXT,k TEXT,a INTEGER,c REAL, PRIMARY KEY(m,t,l,k,a))")
                    }.also { db = it }
                }
                store.beginTransactionNonExclusive()
                try {
                    for (w in batch) store.execSQL(w.sql, w.args)
                    val now = System.currentTimeMillis()
                    if (now - lastTrim >= HOUR) {
                        val cutoff = arrayOf<Any>(now - RETENTION - HOUR)
                        store.execSQL("DELETE FROM birth WHERE b < ?", cutoff)
                        store.execSQL("DELETE FROM trade WHERE t < ?", cutoff)
                        store.execSQL("DELETE FROM decision WHERE t < ?", cutoff)
                        lastTrim = now
                    }
                    store.setTransactionSuccessful()
                } finally { store.endTransaction() }
            } catch (_: Exception) { dropped.addAndGet(batch.size.toLong()) }
        }
    }

    /**
     * V5.0.8744 — LaunchReplay8744's read: each stored launch (born in the retention window, >= [PATH_MIN_PRINTS_8741]
     * prints) with its first-hour prints in time order: times (ms from birth), prices (SOL/token), buy flags. Read-only.
     */
    internal fun forEachLaunch8744(now: Long, maxLaunches: Int, cb: (mint: String, t: LongArray, p: DoubleArray, buy: BooleanArray) -> Unit) {
        val path = AATEApp.appContextOrNull()?.getDatabasePath(NAME) ?: return
        if (!path.exists()) return
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT m,min(b) FROM birth GROUP BY m HAVING min(b)>=CAST(? AS INTEGER) ORDER BY min(b) DESC LIMIT $maxLaunches",
                arrayOf((now - RETENTION).toString())).use { births ->
                while (births.moveToNext()) {
                    val mint = births.getString(0); val born = births.getLong(1)
                    val ts = ArrayList<Long>(); val ps = ArrayList<Double>(); val bs = ArrayList<Boolean>()
                    db.rawQuery("SELECT t,p,side FROM trade WHERE m=? AND t BETWEEN ? AND ? ORDER BY t,s,side",
                        arrayOf(mint, born.toString(), (born + HOUR).toString())).use { c ->
                        while (c.moveToNext()) { ts.add(c.getLong(0) - born); ps.add(c.getDouble(1)); bs.add(c.getInt(2) == 1) }
                    }
                    if (ts.size >= PATH_MIN_PRINTS_8741) cb(mint, ts.toLongArray(), ps.toDoubleArray(), bs.toBooleanArray())
                }
            }
        }
    }

    private const val PATH_MIN_PRINTS_8741 = 20
    private const val BAR_MS_8741 = 10_000L

    /** One launch's first hour as 10-second bars: [secFromBirth, open, high, low, close, buys, sells, buySol, sellSol]. */
    private fun pathRow8741(db: SQLiteDatabase, mint: String, born: Long, until: Long, maxRow: Long): JSONObject {
        val bars = java.util.TreeMap<Long, DoubleArray>()
        db.rawQuery("SELECT t,p,side,v FROM trade WHERE rowid <= $maxRow AND m=? AND t BETWEEN ? AND ? ORDER BY t,s,side",
            arrayOf(mint, born.toString(), until.toString())).use { c ->
            while (c.moveToNext()) bars8741(bars, c.getLong(0) - born, c.getDouble(1), c.getInt(2) == 1,
                if (c.isNull(3)) Double.NaN else c.getDouble(3))
        }
        val arr = org.json.JSONArray()
        for ((k, b) in bars) arr.put(org.json.JSONArray().put(k * BAR_MS_8741 / 1000L).put(b[0]).put(b[1]).put(b[2]).put(b[3])
            .put(b[4].toLong()).put(b[5].toLong()).put(b[6]).put(b[7]))
        return JSONObject().put("kind", "PATH_8741").put("mint", mint).put("birthMs", born).put("barSec", BAR_MS_8741 / 1000L).put("bars", arr)
    }

    /** Pure: fold one print at [offsetMs] from birth into its 10-second bar (open, high, low, close, buys, sells, buySol, sellSol). */
    internal fun bars8741(bars: java.util.TreeMap<Long, DoubleArray>, offsetMs: Long, px: Double, buy: Boolean, sol: Double) {
        if (!px.isFinite() || px <= 0.0 || offsetMs < 0L) return
        val b = bars.getOrPut(offsetMs / BAR_MS_8741) { doubleArrayOf(px, px, px, px, 0.0, 0.0, 0.0, 0.0) }
        b[1] = maxOf(b[1], px); b[2] = minOf(b[2], px); b[3] = px
        if (buy) { b[4] += 1.0; if (sol.isFinite()) b[6] += sol } else { b[5] += 1.0; if (sol.isFinite()) b[7] += sol }
    }

    internal data class Grade(val entry: Pair<Long, Double>?, val last: Pair<Long, Double>?,
                              val peak: Pair<Long, Double>?, val net: Double?)

    internal class ObservedPath(private val prints: List<Pair<Long, Double>>, born: Long) {
        private val end = born + HOUR
        private val peaks = IntArray(prints.size)
        init {
            for (i in prints.indices.reversed()) {
                peaks[i] = if (i == prints.lastIndex || prints[i].second >= prints[peaks[i + 1]].second) i else peaks[i + 1]
            }
        }
        val coveredMinutes = prints.mapTo(HashSet()) { (it.first - born) / WINDOW }.size

        fun grade(at: Long, baseline: Boolean, now: Long, cost: Double): Grade {
            // Timestamps can tie at block-second resolution. A strategy needs a strictly later print.
            val cut = if (baseline) at else at + 1
            var lo = 0
            var hi = prints.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (prints[mid].first < cut) lo = mid + 1 else hi = mid
            }
            val first = prints.getOrNull(lo)?.takeIf { it.first - at <= WINDOW }
            val last = prints.lastOrNull()?.takeIf { now >= end && end - it.first <= WINDOW }
            val peak = if (lo < peaks.size) prints[peaks[lo]] else null
            val net = if (first != null && last != null && last.first > first.first)
                (last.second / first.second - 1.0) * 100.0 - cost else null
            return Grade(first, last, peak, net)
        }
    }

    /** Stream a consistent snapshot for Save report, including launches with no price or decision. */
    fun export(dir: File, now: Long): File? {
        val path = AATEApp.appContextOrNull()?.getDatabasePath(NAME) ?: return null
        if (!path.exists()) return null
        val out = File.createTempFile("launch_hour_", ".jsonl", dir)
        try {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // One read captures all three high-water marks. Later inserts cannot enter this export.
                val limits = db.rawQuery("SELECT (SELECT max(rowid) FROM birth),(SELECT max(rowid) FROM trade),(SELECT max(rowid) FROM decision)", null).use {
                    it.moveToFirst()
                    LongArray(3) { col -> it.getLong(col) }
                }

                out.bufferedWriter().use { w ->
                    w.appendLine(JSONObject().put("exportMs", now).put("droppedThisProcess", dropped.get())
                        .put("unitMismatchRejected8642", rejectedUnitMismatch8642.get()).toString())
                    db.rawQuery("SELECT m,min(b) FROM birth WHERE rowid <= ${limits[0]} GROUP BY m HAVING min(b)>=CAST(? AS INTEGER) ORDER BY min(b)",
                        arrayOf((now - RETENTION).toString())).use { births ->
                        while (births.moveToNext()) {
                            val mint = births.getString(0)
                            val born = births.getLong(1)
                            val end = born + HOUR
                            val prints = ArrayList<Pair<Long, Double>>()
                            val args = arrayOf(mint, born.toString(), minOf(now, end - 1L).toString())
                            db.rawQuery("SELECT t,p FROM trade WHERE rowid <= ${limits[1]} AND m=? AND t BETWEEN ? AND ? ORDER BY t,s,side", args).use { c ->
                                while (c.moveToNext()) prints.add(c.getLong(0) to c.getDouble(1))
                            }
                            val path = ObservedPath(prints, born)
                            fun row(at: Long, lane: String, setup: String, admitted: Int?, cost: Double) {
                                val (first, last, peak, net) = path.grade(at, admitted == null, now, cost)
                                val j = JSONObject().put("mint", mint).put("birthMs", born).put("decisionMs", at)
                                    .put("lane", lane).put("setup", setup).put("admitted", admitted ?: JSONObject.NULL)
                                    .put("costPct", cost).put("hourElapsed", now >= end).put("hourPrints", prints.size)
                                    .put("minutesWithPrints", path.coveredMinutes).put("windowEndMs", end)
                                    .put("entryMs", first?.first ?: JSONObject.NULL).put("entrySolPerToken", first?.second ?: JSONObject.NULL)
                                    .put("lastMs", last?.first ?: JSONObject.NULL).put("lastSolPerToken", last?.second ?: JSONObject.NULL)
                                    .put("observedPeakSolPerToken", peak?.second ?: JSONObject.NULL).put("peakMs", peak?.first ?: JSONObject.NULL)
                                    .put("hourNetPct", net?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                                w.appendLine(j.toString())
                            }
                            row(born, "ALL_LAUNCHES", "", null, FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715)
                            // V5.0.8741 — the coin's first-hour PATH for an offline replay of entries and exits (owner: "see these
                            // developing at 10k ... even if it gets out at 40k 50k"): 10-second bars of every observed print.
                            if (prints.size >= PATH_MIN_PRINTS_8741) w.appendLine(pathRow8741(db, mint, born, minOf(now, end - 1L), limits[1]).toString())
                            db.rawQuery("SELECT t,l,k,a,c FROM decision d WHERE rowid <= ${limits[2]} AND m=? AND t BETWEEN ? AND ? AND (k!='UNATTRIBUTED' OR NOT EXISTS (SELECT 1 FROM decision x WHERE x.rowid <= ${limits[2]} AND x.m=d.m AND x.t=d.t AND x.l=d.l AND x.a=d.a AND x.k!='UNATTRIBUTED')) ORDER BY t", args).use { c ->
                                while (c.moveToNext()) row(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getDouble(4))
                            }
                        }
                    }
                }

            }
            return out
        } catch (e: Exception) { out.delete(); throw e }
    }
}
