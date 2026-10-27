package com.lifecyclebot.engine.truth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.LinkedHashMap
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Per-key durable shadow state. No SharedPreferences instance or whole-book value map is opened. */
internal class ShadowColdStore8397(internal val db: SQLiteDatabase, private val namespace: String) {
    fun read(key: String): String? = db.rawQuery(
        "SELECT payload FROM shadow_kv WHERE namespace=? AND key=?", arrayOf(namespace, key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun keys(): Set<String> = db.rawQuery(
        "SELECT key FROM shadow_kv WHERE namespace=? ORDER BY key", arrayOf(namespace)
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** Keys only are retained while restore walks one value at a time. */
    fun entries(): Sequence<Pair<String, String>> = keys().asSequence().mapNotNull { k -> read(k)?.let { k to it } }

    fun write(batch: Map<String, String?>): Boolean = try {
        transaction {
            for ((key, value) in batch) if (value == null)
                db.execSQL("DELETE FROM shadow_kv WHERE namespace=? AND key=?", arrayOf(namespace, key))
            else db.execSQL("INSERT OR REPLACE INTO shadow_kv(namespace,key,payload) VALUES(?,?,?)", arrayOf(namespace, key, value))
        }
        true
    } catch (_: Exception) { false }

    /** A close's cohort updates and deduplication receipt commit together, before its ticket can be retired. */
    data class Receipt(val inserted: Boolean, val terminal: String)
    internal fun appendReceipt(event: String, payloads: Map<String, String>, terminal: String): Receipt = transaction {
        val prior = db.rawQuery("SELECT terminal FROM shadow_receipts WHERE namespace=? AND event=?", arrayOf(namespace, event))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (prior != null) return@transaction Receipt(false, prior)
        for ((key, value) in payloads)
            db.execSQL("INSERT OR REPLACE INTO shadow_kv(namespace,key,payload) VALUES(?,?,?)", arrayOf(namespace, key, value))
        db.execSQL("INSERT INTO shadow_receipts(namespace,event,terminal) VALUES(?,?,?)", arrayOf(namespace, event, terminal))
        Receipt(true, terminal)
    }

    private fun <T> transaction(action: () -> T): T {
        db.beginTransaction()
        try { val result = action(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }

    /** Stream the old XML transactionally. A failed parse/commit neither marks migration nor removes the original. */
    fun migrate(legacy: File) {
        val done = db.rawQuery("SELECT 1 FROM shadow_migrations WHERE namespace=?", arrayOf(namespace)).use { it.moveToFirst() }
        if (!done) transaction {
            val backup = File(legacy.path + ".bak")
            val source = if (backup.isFile) backup else legacy
            if (source.isFile) {
                val factory = SAXParserFactory.newInstance()
                factory.isNamespaceAware = false
                // Android and the JVM both support SAX. External content is never part of a preference document.
                for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities"))
                    try { factory.setFeature(feature, false) } catch (_: Exception) {}
                val handler = object : DefaultHandler() {
                    private var key: String? = null
                    private var value: StringBuilder? = null
                    override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                        if (qName == "string") { key = attrs.getValue("name"); value = StringBuilder() }
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        value?.let { v ->
                            check(v.length + length <= 2 * 1024 * 1024) { "Oversized shadow value" }
                            v.append(ch, start, length)
                        }
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        if (qName == "string") {
                            val k = key ?: error("Shadow preference without key")
                            db.execSQL("INSERT OR IGNORE INTO shadow_kv(namespace,key,payload) VALUES(?,?,?)",
                                arrayOf(namespace, k, value.toString()))
                            key = null; value = null
                        }
                    }
                }
                source.inputStream().buffered().use { factory.newSAXParser().parse(it, handler) }
            }
            db.execSQL("INSERT INTO shadow_migrations(namespace) VALUES(?)", arrayOf(namespace))
        }
        // Archive only after the commit. The original remains available for recovery, but Android never loads it.
        for (file in listOf(legacy, File(legacy.path + ".bak"))) if (file.isFile) {
            val archive = File(file.path + ".migrated8397")
            if (!archive.exists()) file.renameTo(archive)
        }
    }

    companion object {
        internal const val DB_NAME = "shadow_cold_8397.db"
        @Volatile private var shared: SQLiteDatabase? = null
        private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, 1) {
            override fun onCreate(db: SQLiteDatabase) = createSchema(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        internal fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS shadow_kv(namespace TEXT NOT NULL,key TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(namespace,key))")
            db.execSQL("CREATE TABLE IF NOT EXISTS shadow_receipts(namespace TEXT NOT NULL,event TEXT NOT NULL,terminal TEXT NOT NULL,PRIMARY KEY(namespace,event))")
            db.execSQL("CREATE TABLE IF NOT EXISTS shadow_migrations(namespace TEXT PRIMARY KEY)")
        }
        @Synchronized fun open(ctx: Context, legacyName: String): ShadowColdStore8397 {
            val db = shared ?: Helper(ctx.applicationContext).also { it.setWriteAheadLoggingEnabled(true) }.writableDatabase.also { shared = it }
            return ShadowColdStore8397(db, legacyName).also {
                it.migrate(File(ctx.applicationInfo.dataDir, "shared_prefs/$legacyName.xml"))
            }
        }
    }
}

internal class ShadowStorageUnavailable8397(cause: Throwable? = null) : RuntimeException("Shadow storage unavailable; retain receipt", cause)

/** Only a bounded parsed working set is resident. Every cohort remains discoverable and readable on disk. */
internal class ShadowCohortBook8397(private val fields: Int, private val maxRows: Int = 300, private val hotCapacity: Int = 64) {
    private val hot = LinkedHashMap<String, List<DoubleArray>>(16, .75f, true)
    private var store: ShadowColdStore8397? = null
    // V5.0.8398 — every key the store holds, resident. Keys are short; the values were the memory the cold store removed.
    // Runtime 5.0.8397/8398: the operator report fell to the 20 s watchdog fallback twice because `keys` re-ran a full
    // key query per call and the status walks (per lane × per cohort dimension) made hundreds of such calls, each read
    // then missing a 64-entry hot cache; the same monitor serialised the trading path's appends behind that walk.
    private val keyIndex = HashSet<String>()
    data class AppendResult(val inserted: Boolean, val terminal: DoubleArray)
    private data class Early(val keys: List<String>, val row: DoubleArray, val terminal: DoubleArray)
    private val preAttachReceipts = LinkedHashMap<String, Early>()

    @Synchronized fun attach(cold: ShadowColdStore8397) {
        // Preserve any early process-local closes; do not let init overwrite observations made during startup.
        // Keep the event identities as well as rows. Retrying a partial attach cannot contribute the same close twice.
        try {
            for ((event, early) in preAttachReceipts) {
                val payloads = early.keys.associateWith { k -> encode((decode(cold.read(k)) + listOf(early.row)).takeLast(maxRows)) }
                cold.appendReceipt(event, payloads, early.terminal.joinToString(","))
            }
        } catch (failure: Exception) { throw ShadowStorageUnavailable8397(failure) }
        store = cold; hot.clear(); preAttachReceipts.clear()
        keyIndex.clear(); try { keyIndex.addAll(cold.keys()) } catch (failure: Exception) { throw ShadowStorageUnavailable8397(failure) }
    }

    private fun decode(value: String?): List<DoubleArray> = value.orEmpty().lineSequence().flatMap { it.splitToSequence(';') }
        .mapNotNull { row ->
            val f = row.split(',')
            val at = f.getOrNull(0)?.toDoubleOrNull(); val net = f.getOrNull(1)?.toDoubleOrNull()
            if (at == null || net == null || !net.isFinite()) null
            else DoubleArray(fields) { i -> f.getOrNull(i)?.toDoubleOrNull() ?: 0.0 }
        }.toList().takeLast(maxRows)

    private fun encode(rows: List<DoubleArray>): String = rows.joinToString(";") { ShadowPersist8355.compactRow8396(it) }
    private fun putHot(key: String, rows: List<DoubleArray>) {
        hot[key] = rows
        while (hot.size > hotCapacity) { val it = hot.entries.iterator(); it.next(); it.remove() }
    }

    @Synchronized operator fun get(key: String): List<DoubleArray>? {
        hot[key]?.let { return it }
        val value = try { store?.read(key) } catch (failure: Exception) { throw ShadowStorageUnavailable8397(failure) } ?: return null
        return decode(value).also { putHot(key, it) }
    }
    val keys: Set<String> @Synchronized get() = if (store == null) hot.keys.toSet() else (keyIndex + hot.keys)
    @Synchronized fun containsKey(key: String): Boolean = hot.containsKey(key) || (store != null && key in keyIndex)

    /** One event's variants/cohorts are atomic and idempotent, including replay after a crash before ticket deletion. */
    @Synchronized fun append(keys: Collection<String>, row: DoubleArray, event: String, terminal: DoubleArray = row): AppendResult {
        if (keys.isEmpty()) return AppendResult(false, terminal)
        val cold = store
        if (cold == null) preAttachReceipts[event]?.let { return AppendResult(false, it.terminal.copyOf()) }
        if (cold == null && preAttachReceipts.size >= 1024) throw ShadowStorageUnavailable8397()
        if (cold == null && (hot.keys + keys).size > hotCapacity) throw ShadowStorageUnavailable8397()
        val updated = keys.distinct().associateWith { k -> (get(k).orEmpty() + listOf(row.copyOf())).takeLast(maxRows) }
        try {
            if (cold != null) {
                val result = cold.appendReceipt(event, updated.mapValues { encode(it.value) }, terminal.joinToString(","))
                if (!result.inserted) return AppendResult(false, result.terminal.split(',').map { it.toDouble() }.toDoubleArray())
            }
        } catch (failure: Exception) { throw ShadowStorageUnavailable8397(failure) }
        updated.forEach { (k, v) -> putHot(k, v) }
        if (cold != null) keyIndex.addAll(updated.keys)
        if (cold == null) preAttachReceipts[event] = Early(keys.distinct(), row.copyOf(), terminal.copyOf())
        return AppendResult(true, terminal.copyOf())
    }

    @Synchronized fun trim(): Int { val n = hot.size; if (store != null) hot.clear(); return if (store != null) n else 0 }
    @Synchronized fun hotCount(): Int = hot.size
    @Synchronized fun bytes(): Long = hot.entries.sumOf { (k, v) -> 64L + k.length * 2L + v.sumOf { 32L + it.size * 8L } } +
        preAttachReceipts.entries.sumOf { (k, v) -> 96L + k.length * 2L + v.row.size * 8L + v.keys.sumOf { 32L + it.length * 2L } }
    @Synchronized fun clear() { hot.clear(); preAttachReceipts.clear(); keyIndex.clear() } // test-local reset; durable learning is never evicted
}
