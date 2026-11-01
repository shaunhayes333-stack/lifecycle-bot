package com.lifecyclebot.engine

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8501 §BIG_PREFS_LEAVE_THE_HEAP. Android SharedPreferences keeps every loaded file fully resident as a HashMap of
 * Java Strings (UTF-16, ~2x the XML) for the life of the process and rewrites the WHOLE file on every apply / commit.
 * 5.0.8499 memory census: prefsMirror8309=30 MB resident, prefsTop forward_return_labeler_7731 7.4 MB,
 * canonical_entry_evidence_8093 1.9 MB, scanner_hard_rejects 1.2 MB, chart_library_builder_7950 0.9 MB,
 * canonical_finality_6486 0.8 MB, whale_wallet_tracker 0.5 MB — at heap 512/512 MB.
 *
 * [of] returns a [SharedPreferences] backed by a SQLite table (prefs_offload_8501.db, its own file: never deleted by
 * LearningResetSweep7781's learning_kv.db reset, so immutable execution evidence keeps surviving it). Values are read
 * on demand, nothing is mirrored in memory except edits not yet written. A write upserts only the keys it changed.
 * The first open of a namespace migrates its XML file once, in one transaction, then deletes the XML (which also evicts
 * Android's in-process cache of it). apply() is visible to readers at once (pending overlay) and written by one
 * background writer; commit() writes in the caller's transaction. Same keys, same types, same semantics.
 */
object OffloadedPrefs8501 {
    /** The namespaces moved off the heap. */
    val OFFLOADED_8501: Set<String> = setOf(
        "forward_return_labeler_7731", "canonical_entry_evidence_8093", "scanner_hard_rejects",
        "chart_library_builder_7950", "canonical_finality_6486", "whale_wallet_tracker",
    )

    private const val DB_8501 = "prefs_offload_8501.db"
    private const val T_STR = 0; private const val T_INT = 1; private const val T_LONG = 2
    private const val T_FLOAT = 3; private const val T_BOOL = 4; private const val T_SET = 5

    @Volatile private var helper: Helper? = null
    private val stores = ConcurrentHashMap<String, Store>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "prefs-offload-8501").apply { isDaemon = true } }
    private val migratedKeys = AtomicLong(0); private val migratedNs = AtomicLong(0)
    private val writes = AtomicLong(0); private val writeFailures = AtomicLong(0)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_8501, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE kv (ns TEXT NOT NULL, k TEXT NOT NULL, t INTEGER NOT NULL, v TEXT, PRIMARY KEY(ns, k))")
            db.execSQL("CREATE TABLE migrated (ns TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    /** The store for [name]: offloaded when listed in [OFFLOADED_8501], else Android's own. */
    fun of(context: Context, name: String): SharedPreferences {
        val app = context.applicationContext ?: context
        if (name !in OFFLOADED_8501) return app.getSharedPreferences(name, Context.MODE_PRIVATE)
        return try {
            stores[name] ?: synchronized(this) {
                stores[name] ?: run {
                    val h = helper ?: Helper(app).also { helper = it }
                    val db = h.writableDatabase
                    try { db.enableWriteAheadLogging() } catch (_: Throwable) {}
                    migrate8501(app, db, name)
                    Store(name, db).also { stores[name] = it }
                }
            }
        } catch (t: Throwable) {
            try { PipelineHealthCollector.labelInc("PREFS_OFFLOAD_OPEN_FAILED_8501") } catch (_: Throwable) {}
            app.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /** LearningResetSweep7781: clearing a namespace clears its offloaded rows too. */
    fun clear8501(context: Context, name: String) {
        if (name !in OFFLOADED_8501) return
        try { of(context, name).edit().clear().commit() } catch (_: Throwable) {}
    }

    private fun migrate8501(app: Context, db: SQLiteDatabase, name: String) {
        val done = db.rawQuery("SELECT 1 FROM migrated WHERE ns=?", arrayOf(name)).use { it.moveToFirst() }
        if (done) return
        val xml = File(File(app.applicationInfo.dataDir, "shared_prefs"), "$name.xml")
        var n = 0L
        db.beginTransaction()
        try {
            if (xml.exists()) {
                val old = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                for ((k, v) in old) { if (k != null && v != null && upsert(db, name, k, v)) n++ }
            }
            db.insertWithOnConflict("migrated", null, ContentValues().apply { put("ns", name) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (xml.exists()) try { app.deleteSharedPreferences(name) } catch (_: Throwable) {}
        migratedKeys.addAndGet(n); migratedNs.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PREFS_OFFLOAD_MIGRATED_8501") } catch (_: Throwable) {}
    }

    private fun upsert(db: SQLiteDatabase, ns: String, k: String, v: Any): Boolean {
        val (t, s) = encode(v) ?: return false
        db.insertWithOnConflict("kv", null, ContentValues().apply { put("ns", ns); put("k", k); put("t", t); put("v", s) },
            SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /** Pure: a preference value as (type, text). */
    internal fun encode(v: Any): Pair<Int, String>? = when (v) {
        is String -> T_STR to v
        is Int -> T_INT to v.toString()
        is Long -> T_LONG to v.toString()
        is Float -> T_FLOAT to v.toString()
        is Boolean -> T_BOOL to v.toString()
        is Set<*> -> T_SET to org.json.JSONArray(v.filterIsInstance<String>()).toString()
        else -> null
    }

    /** Pure: (type, text) back to its value. */
    internal fun decode(t: Int, s: String?): Any? = try {
        when (t) {
            T_STR -> s
            T_INT -> s?.toInt()
            T_LONG -> s?.toLong()
            T_FLOAT -> s?.toFloat()
            T_BOOL -> s?.toBoolean()
            T_SET -> s?.let { val a = org.json.JSONArray(it); (0 until a.length()).map { i -> a.optString(i) }.toMutableSet() }
            else -> null
        }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8503 §ROW_TOO_BIG_FOR_THE_CURSOR. An Android cursor window holds ~2 MB; one value can be larger
     * (forward_return_labeler_7731 cells / pending / paired-Cortex checkpoints). `SELECT v` of such a row throws
     * "Row too big to fit into CursorWindow"; the 8501 reader swallowed it and returned null, so after a restart those
     * stores read EMPTY although every byte is on disk (8502: label coverage collapsed, Cortex graded 96/964).
     * Large values are read back through substr() in chunks well under the window. Nothing on disk is rewritten.
     */
    private const val CHUNK_CHARS_8503 = 256 * 1024
    private val readFailures8502 = AtomicLong(0)
    private val chunkedReads8503 = AtomicLong(0)

    private fun readRow8502(db: SQLiteDatabase, ns: String, key: String): Any? {
        var type = -1
        var len = -1L
        db.rawQuery("SELECT t, length(v) FROM kv WHERE ns=? AND k=?", arrayOf(ns, key)).use { c ->
            if (!c.moveToFirst()) return null
            type = c.getInt(0)
            len = if (c.isNull(1)) -1L else c.getLong(1)
        }
        if (len < 0) return decode(type, null)
        if (len <= CHUNK_CHARS_8503) {
            return db.rawQuery("SELECT v FROM kv WHERE ns=? AND k=?", arrayOf(ns, key)).use { c ->
                if (c.moveToFirst()) decode(type, c.getString(0)) else null
            }
        }
        chunkedReads8503.incrementAndGet()
        val sb = StringBuilder(len.toInt())
        var start = 1L
        while (start <= len) {
            db.rawQuery("SELECT substr(v, ?, ?) FROM kv WHERE ns=? AND k=?",
                arrayOf(start.toString(), CHUNK_CHARS_8503.toString(), ns, key)).use { c ->
                if (!c.moveToFirst()) return null
                sb.append(c.getString(0) ?: "")
            }
            start += CHUNK_CHARS_8503
        }
        return decode(type, sb.toString())
    }

    private object Removed

    private class Store(val ns: String, val db: SQLiteDatabase) : SharedPreferences {
        /** apply()'d edits not yet written: visible to readers at once. [Removed] marks a pending delete. */
        val pending = ConcurrentHashMap<String, Any>()
        @Volatile var pendingClear = false

        private fun read(key: String): Any? {
            pending[key]?.let { return if (it === Removed) null else it }
            if (pendingClear) return null
            return try { readRow8502(db, ns, key) } catch (_: Throwable) { readFailures8502.incrementAndGet(); null }
        }

        override fun getAll(): MutableMap<String, *> {
            val m = HashMap<String, Any?>()
            if (!pendingClear) try {
                val keys = ArrayList<String>()
                db.rawQuery("SELECT k FROM kv WHERE ns=?", arrayOf(ns)).use { c -> while (c.moveToNext()) keys += c.getString(0) }
                for (k in keys) m[k] = try { readRow8502(db, ns, k) } catch (_: Throwable) { readFailures8502.incrementAndGet(); null }
            } catch (_: Throwable) {}
            for ((k, v) in pending) if (v === Removed) m.remove(k) else m[k] = v
            return m
        }
        override fun getString(key: String, defValue: String?): String? = read(key) as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (read(key) as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = read(key) as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = read(key) as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = read(key) as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key) as? Boolean ?: defValue
        override fun contains(key: String): Boolean = read(key) != null
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        /** Writes [changes] (and a clear first) in one transaction. */
        fun write(clear: Boolean, changes: Map<String, Any>): Boolean = try {
            db.beginTransaction()
            try {
                if (clear) db.delete("kv", "ns=?", arrayOf(ns))
                for ((k, v) in changes) {
                    if (v === Removed) db.delete("kv", "ns=? AND k=?", arrayOf(ns, k)) else upsert(db, ns, k, v)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            writes.incrementAndGet(); true
        } catch (_: Throwable) {
            writeFailures.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PREFS_OFFLOAD_WRITE_FAILED_8501") } catch (_: Throwable) {}
            false
        }
    }

    private class Editor(val s: Store) : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value ?: Removed; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { changes[key] = values?.toSet() ?: Removed; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { changes[key] = Removed; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }

        override fun commit(): Boolean {
            val c = clear; val ch = LinkedHashMap(changes)
            val ok = synchronized(s) { s.write(c, ch) }
            if (ok) synchronized(s) { if (c) { s.pending.clear(); s.pendingClear = false }; ch.keys.forEach { k -> s.pending.remove(k) } }
            return ok
        }

        override fun apply() {
            val c = clear; val ch = LinkedHashMap(changes)
            synchronized(s) {
                if (c) { s.pending.clear(); s.pendingClear = true }
                s.pending.putAll(ch)
            }
            writer.execute {
                val ok = synchronized(s) { s.write(c, ch) }
                if (ok) synchronized(s) {
                    if (c) s.pendingClear = false
                    // drop only overlay entries still holding exactly this edit's value (a newer apply keeps its own)
                    for ((k, v) in ch) if (s.pending[k] === v) s.pending.remove(k)
                }
            }
        }
    }

    fun statusLine8501(): String = "prefsOffload8501[ns=${stores.size}/${OFFLOADED_8501.size} migratedNs=${migratedNs.get()} " +
        "migratedKeys=${migratedKeys.get()} writes=${writes.get()} writeFailed=${writeFailures.get()} " +
        "pendingApply=${stores.values.sumOf { it.pending.size }} chunkedReads8503=${chunkedReads8503.get()} readFailed8503=${readFailures8502.get()}]"
}
