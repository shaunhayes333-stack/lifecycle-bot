package com.lifecyclebot.engine.truth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8681 — THE HIVE BOOK ON DISK.
 *
 * 5.0.8680 heap census: hiveBook +35.6 MB over a 97-minute session, the largest grower in the process. HiveEdge8000 kept
 * the whole network book (every key pulled from the Turso hive) and this install's whole upload ledger as two
 * ConcurrentHashMaps of DoubleArray(6), re-paged the full network book into them on every process start, and rewrote the
 * upload ledger as one text file every sync. On a 512 MB heap already churning ~3 GB/min that resident book pushed the
 * Helius stream to its 8-subscription floor and fed the GC pauses.
 *
 * The book now lives in an on-device SQLite database (the local mirror of the Turso hive): network totals, this install's
 * uploads and the sync cursor. Nothing is lost on restart, so a boot resumes from its cursor instead of re-paging the
 * network. HiveEdge8000 keeps only a small, bounded read cache. When no app context exists (JVM tests) or the database
 * cannot be opened, a bounded in-memory book stands in.
 */
internal interface HiveRows8681 {
    /** The network total for [key]: [n, sum, sumSq, wins, runners, best], or null. */
    fun aggregate(key: String): DoubleArray?
    /** What this install has uploaded for [key], or null. */
    fun uploaded(key: String): DoubleArray?
    fun putAggregate(rows: List<Pair<String, DoubleArray>>): Boolean
    fun putUploaded(rows: List<Pair<String, DoubleArray>>): Boolean
    /** Keys starting with [prefix] whose network total, less this install's uploads, has at least one outcome. */
    fun netKeys(prefix: String): List<String>
    fun aggregateCount(): Int
    fun uploadedCount(): Int
    fun meta(key: String): String?
    fun putMeta(key: String, value: String): Boolean
    /** Test hook only: remove network rows. */
    fun removeAggregate(keys: Collection<String>)
    /** Rows held on the heap by this book (0 for the disk book). */
    fun residentRows(): Int
}

/** SQLite book. One row per key; values read with getDouble, never parsed through strings. */
internal class HiveSqlRows8681(internal val db: SQLiteDatabase) : HiveRows8681 {
    override fun aggregate(key: String): DoubleArray? = row("SELECT n,s,ss,w,r,b FROM hive_agg WHERE k=?", key)
    override fun uploaded(key: String): DoubleArray? = row("SELECT n,s,ss,w,r,b FROM hive_up WHERE k=?", key)

    private fun row(sql: String, key: String): DoubleArray? = db.rawQuery(sql, arrayOf(key)).use { c ->
        if (c.moveToFirst()) DoubleArray(6) { i -> c.getDouble(i) } else null
    }

    override fun putAggregate(rows: List<Pair<String, DoubleArray>>): Boolean = put("hive_agg", rows)
    override fun putUploaded(rows: List<Pair<String, DoubleArray>>): Boolean = put("hive_up", rows)

    private fun put(table: String, rows: List<Pair<String, DoubleArray>>): Boolean {
        if (rows.isEmpty()) return true
        return try {
            transaction {
                for ((k, v) in rows) {
                    if (k.isBlank() || v.size < 6) continue
                    db.execSQL("INSERT OR REPLACE INTO $table(k,n,s,ss,w,r,b) VALUES(?,?,?,?,?,?,?)",
                        arrayOf<Any?>(k, v[0], v[1], v[2], v[3], v[4], v[5]))
                }
            }
            true
        } catch (_: Exception) { false }
    }

    override fun netKeys(prefix: String): List<String> =
        db.rawQuery("SELECT a.k FROM hive_agg a LEFT JOIN hive_up u ON u.k=a.k WHERE a.k>=? AND a.k<? AND a.n-IFNULL(u.n,0)>=1",
            arrayOf(prefix, upperBound8681(prefix))).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    override fun aggregateCount(): Int = count("hive_agg")
    override fun uploadedCount(): Int = count("hive_up")
    private fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    override fun meta(key: String): String? = db.rawQuery("SELECT v FROM hive_meta WHERE k=?", arrayOf(key)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    override fun putMeta(key: String, value: String): Boolean = try {
        db.execSQL("INSERT OR REPLACE INTO hive_meta(k,v) VALUES(?,?)", arrayOf<Any?>(key, value)); true
    } catch (_: Exception) { false }

    override fun removeAggregate(keys: Collection<String>) {
        for (k in keys) db.execSQL("DELETE FROM hive_agg WHERE k=?", arrayOf<Any?>(k))
    }
    override fun residentRows(): Int = 0

    private fun <T> transaction(action: () -> T): T {
        db.beginTransaction()
        try { val r = action(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }

    companion object {
        internal const val DB_NAME = "hive_book_8681.db"
        /** Pure: the exclusive upper key bound of a prefix range scan (keys are ASCII book tags + ids). */
        fun upperBound8681(prefix: String): String = prefix + '￿'

        internal fun createSchema(db: SQLiteDatabase) {
            for (t in listOf("hive_agg", "hive_up"))
                db.execSQL("CREATE TABLE IF NOT EXISTS $t(k TEXT PRIMARY KEY,n REAL NOT NULL,s REAL NOT NULL,ss REAL NOT NULL,w REAL NOT NULL,r REAL NOT NULL,b REAL NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS hive_meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
        }

        private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, 1) {
            override fun onCreate(db: SQLiteDatabase) = createSchema(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        @Volatile private var shared: HiveSqlRows8681? = null
        @Synchronized fun open(ctx: Context): HiveSqlRows8681 = shared ?: HiveSqlRows8681(
            Helper(ctx.applicationContext).also { it.setWriteAheadLoggingEnabled(true) }.writableDatabase.also { createSchema(it) }
        ).also { shared = it }
    }
}

/** Bounded heap book: the stand-in when no store can open. Never grows past [maxKeys] per table. */
internal class HiveMemoryRows8681(private val maxKeys: Int = 20_000) : HiveRows8681 {
    private val agg = ConcurrentHashMap<String, DoubleArray>()
    private val up = ConcurrentHashMap<String, DoubleArray>()
    private val meta = ConcurrentHashMap<String, String>()
    override fun aggregate(key: String): DoubleArray? = agg[key]?.copyOf()
    override fun uploaded(key: String): DoubleArray? = up[key]?.copyOf()
    override fun putAggregate(rows: List<Pair<String, DoubleArray>>): Boolean = put(agg, rows)
    override fun putUploaded(rows: List<Pair<String, DoubleArray>>): Boolean = put(up, rows)
    private fun put(m: ConcurrentHashMap<String, DoubleArray>, rows: List<Pair<String, DoubleArray>>): Boolean {
        for ((k, v) in rows) if (k.isNotBlank() && v.size >= 6) m[k] = v.copyOf(6)
        if (m.size > maxKeys) {
            val drop = m.size - maxKeys
            val it = m.keys.iterator()
            var n = 0
            while (n < drop && it.hasNext()) { it.next(); it.remove(); n++ }
        }
        return true
    }
    override fun netKeys(prefix: String): List<String> = agg.entries.filter { (k, v) ->
        k.startsWith(prefix) && v[0] - (up[k]?.get(0) ?: 0.0) >= 1.0
    }.map { it.key }
    override fun aggregateCount(): Int = agg.size
    override fun uploadedCount(): Int = up.size
    override fun meta(key: String): String? = meta[key]
    override fun putMeta(key: String, value: String): Boolean { meta[key] = value; return true }
    override fun removeAggregate(keys: Collection<String>) { keys.forEach { agg.remove(it) } }
    override fun residentRows(): Int = agg.size + up.size
}
