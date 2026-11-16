package com.lifecyclebot.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ErrorLogger — In-app error logging with SQLite database
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Captures all errors, crashes, and important events directly in the app.
 * No external tools needed - view errors right from the app UI.
 *
 * FEATURES:
 * - Persistent SQLite storage (survives app restart)
 * - Automatic stack trace capture
 * - Severity levels (DEBUG, INFO, WARN, ERROR, CRASH)
 * - Component tagging (BotService, Wallet, Trade, etc.)
 * - Timestamp and session tracking
 * - Export to text for sharing
 * - Auto-cleanup of old logs (keeps last 500)
 */
object ErrorLogger {

    enum class Level {
        DEBUG, INFO, WARN, ERROR, CRASH
    }

    data class LogEntry(
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val component: String,
        val message: String,
        val stackTrace: String?,
        val sessionId: String,
    ) {
        val timeFormatted: String
            get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        
        val levelIcon: String
            get() = when (level) {
                Level.DEBUG -> "🔍"
                Level.INFO -> "ℹ️"
                Level.WARN -> "⚠️"
                Level.ERROR -> "❌"
                Level.CRASH -> "💥"
            }
    }

    private var db: SQLiteDatabase? = null
    private var sessionId: String = UUID.randomUUID().toString().take(8)
    private const val MAX_LOGS = 500

    // V5.9.36: Off-thread DB writes to prevent main-thread ANR when the
    // app is flooded with log lines from parallel trader engines.
    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private val pendingWrites = AtomicInteger(0)
    private val droppedUnderPressure = AtomicLong(0L)
    private const val MAX_PENDING_WRITES = 300
    private const val HARD_MAX_PENDING_8821 = 2_000
    private val droppedHard8821 = AtomicLong(0L)
    // V5.0.8821 — rows offered per component, so the report names what floods the log (bounded to 256 components).
    private val byComponent8821 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private fun noteComponent8821(component: String) {
        val c = byComponent8821[component] ?: if (byComponent8821.size >= 256) return else byComponent8821.computeIfAbsent(component) { AtomicLong(0L) }
        c.incrementAndGet()
    }

    // ── Initialize ────────────────────────────────────────────────────

    fun init(context: Context) {
        if (db != null) return
        val helper = ErrorDbHelper(context)
        try { helper.setWriteAheadLoggingEnabled(true) } catch (_: Throwable) {}   // V5.0.8818 — no journal sync per commit
        db = helper.writableDatabase
        sessionId = UUID.randomUUID().toString().take(8)

        // Spin up a dedicated low-priority background thread for log IO.
        if (ioThread == null) {
            val t = HandlerThread("ErrorLoggerIO", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            ioThread = t
            ioHandler = Handler(t.looper)
        }

        // Log session start
        log(Level.INFO, "System", "Session started: $sessionId")

        // Cleanup old logs (already off-thread below via ioHandler)
        cleanup()
    }

    // ── Logging methods ───────────────────────────────────────────────

    fun debug(component: String, message: String) = log(Level.DEBUG, component, message)
    fun info(component: String, message: String) = log(Level.INFO, component, message)
    fun warn(component: String, message: String) = log(Level.WARN, component, message)
    fun error(component: String, message: String, throwable: Throwable? = null) = 
        log(Level.ERROR, component, message, throwable)
    fun crash(component: String, message: String, throwable: Throwable) = 
        log(Level.CRASH, component, message, throwable)

    fun log(level: Level, component: String, message: String, throwable: Throwable? = null) {
        val database = db ?: return

        // V5.9.1106 — no main-thread logcat/stack/SQLite work. Android Log can
        // block on saturated buffers, and stackTraceToString() showed up in ANR
        // samples under forensic storms. Everything below is fire-and-forget.
        val handler = ioHandler
        if (handler == null) return
        if (pendingWrites.get() > MAX_PENDING_WRITES && level != Level.ERROR && level != Level.CRASH) {
            droppedUnderPressure.incrementAndGet()
            return
        }
        // V5.0.8821 — ERROR / CRASH rows bypass the pressure bound above, so a flood of them (each carrying a Throwable and its
        // stack) could queue without limit: bc4c42e1 crashed with OutOfMemoryError on ErrorLoggerIO after 2.7 h while the log
        // wrote 113,731 rows in 16 min. A hard ceiling holds for every level; a CRASH row still always queues.
        if (pendingWrites.get() > HARD_MAX_PENDING_8821 && level != Level.CRASH) {
            droppedHard8821.incrementAndGet()
            return
        }
        noteComponent8821(component)

        // V5.9.476 — BATTERY-USE FIX: skip the SQLite write for DEBUG-level
        // logs.
        //
        // Operator: 'users are complaining about very heavy battery use.'
        //
        // Root cause: 700+ ErrorLogger.debug call sites in the codebase,
        // each one queueing a SQLite INSERT into error_logs every loop tick
        // (8s default). At ~50 debug events per cycle that's ~22,000 DB
        // writes/hour just for traces nobody reads — keeps disk active and
        // ioHandler thread spinning, which prevents the CPU from settling
        // into deep sleep states. Plus MAX_LOGS=500 means a DELETE pass
        // every few minutes adding more writes.
        //
        // DEBUG logs are still emitted to logcat (free, only there if a
        // developer is attached) but no longer hit the DB. INFO/WARN/ERROR/
        // CRASH still write — those are the levels the in-app log viewer
        // shows by default and are the meaningful events.
        if (level == Level.DEBUG) return

        val ts = System.currentTimeMillis()
        pendingWrites.incrementAndGet()
        queue8818.add(Pending8818(ts, level, component, message.take(1000), throwable, sessionId))
        // V5.0.8818 — one flush runnable drains whatever has queued, in one transaction (see [flush8818]).
        if (flushPosted8818.compareAndSet(false, true)) handler.post { flush8818(database) }
    }

    // ── V5.0.8818 §LOG_ROWS_IN_ONE_TRANSACTION ──
    // 494b46bb (5.0.8817, 65 min): the CPU sampler's largest single frame was ErrorLogger.writeToDb inside SQLite's insert
    // (7.4 % of all CPU stacks, 8 % with its thread), more than any trading code. Each INFO/WARN/ERROR row was its own
    // auto-committed transaction (a journal write and sync per row) into a table trimmed to 500 rows only at app start, so
    // during a session it grew without bound under three indexes. Rows now queue and one runnable on the log thread writes
    // everything queued in a single transaction; the table is trimmed back to [MAX_LOGS] every [TRIM_EVERY_8818] rows by id
    // (ids only grow); the database runs write-ahead logging. Same rows, same columns, same logcat lines.
    private class Pending8818(val ts: Long, val level: Level, val component: String, val msg: String, val throwable: Throwable?, val sid: String)
    private val queue8818 = java.util.concurrent.ConcurrentLinkedQueue<Pending8818>()
    private val flushPosted8818 = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val TRIM_EVERY_8818 = 1_000
    private const val BATCH_MAX_8818 = 500
    private val rowsWritten8818 = AtomicLong(0L)
    private val batches8818 = AtomicLong(0L)
    private val trims8818 = AtomicLong(0L)
    private var sinceTrim8818 = 0   // log thread only

    private fun flush8818(database: SQLiteDatabase) {
        flushPosted8818.set(false)
        val batch = ArrayList<Pending8818>(minOf(BATCH_MAX_8818, 64))
        while (batch.size < BATCH_MAX_8818) batch.add(queue8818.poll() ?: break)
        if (batch.isEmpty()) return
        try {
            for (p in batch) {
                val tag = "AATE.${p.component}"
                try {
                    when (p.level) {
                        Level.DEBUG -> android.util.Log.d(tag, p.msg)
                        Level.INFO -> android.util.Log.i(tag, p.msg)
                        Level.WARN -> android.util.Log.w(tag, p.msg)
                        Level.ERROR -> android.util.Log.e(tag, p.msg, p.throwable)
                        Level.CRASH -> android.util.Log.e(tag, "CRASH: ${p.msg}", p.throwable)
                    }
                } catch (_: Throwable) {}
            }
            try {
                database.beginTransaction()
                try {
                    for (p in batch) writeToDb(database, p.ts, p.level.name, p.component, p.msg, p.throwable?.stackTraceToString()?.take(2000), p.sid)
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
                rowsWritten8818.addAndGet(batch.size.toLong()); batches8818.incrementAndGet()
            } catch (e: Exception) {
                android.util.Log.e("ErrorLogger", "Failed to log batch: ${e.message}")
            }
            sinceTrim8818 += batch.size
            if (sinceTrim8818 >= TRIM_EVERY_8818) { sinceTrim8818 = 0; trim8818(database) }
        } finally {
            pendingWrites.addAndGet(-batch.size)
            // more arrived than one batch holds, or while this one was written: drain again
            if (!queue8818.isEmpty() && flushPosted8818.compareAndSet(false, true)) ioHandler?.post { flush8818(database) } ?: flushPosted8818.set(false)
        }
    }

    /** Keeps the newest [MAX_LOGS] rows (ids are autoincrement, so the newest are the largest ids). */
    private fun trim8818(database: SQLiteDatabase) {
        try {
            database.execSQL("DELETE FROM error_logs WHERE id <= (SELECT MAX(id) FROM error_logs) - $MAX_LOGS")
            trims8818.incrementAndGet()
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to trim: ${e.message}")
        }
    }

    /** Report line: rows written, transactions, trims, rows dropped under pressure, rows waiting. */
    fun statusLine8818(): String =
        "errorLog8818[rows=${rowsWritten8818.get()} transactions=${batches8818.get()} trims=${trims8818.get()} " +
            "droppedUnderPressure=${droppedUnderPressure.get()} droppedHard8821=${droppedHard8821.get()} pending=${pendingWrites.get()} " +
            "top8821=${byComponent8821.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(8).joinToString(",") { "${it.first}:${it.second}" }} " +
            "rule=queued rows written in one transaction, table kept to $MAX_LOGS rows]"

    private fun writeToDb(
        database: SQLiteDatabase,
        ts: Long,
        lvlName: String,
        component: String,
        msg: String,
        stack: String?,
        sid: String,
    ) {
        val values = ContentValues().apply {
            put("timestamp", ts)
            put("level", lvlName)
            put("component", component)
            put("message", msg)
            put("stack_trace", stack)
            put("session_id", sid)
        }
        database.insert("error_logs", null, values)
    }

    // ── Query methods ─────────────────────────────────────────────────

    fun getRecentLogs(limit: Int = 100, minLevel: Level = Level.DEBUG): List<LogEntry> {
        val database = db ?: return emptyList()
        val logs = mutableListOf<LogEntry>()
        
        try {
            val levelIndex = Level.values().indexOf(minLevel)
            val validLevels = Level.values().drop(levelIndex).map { "'${it.name}'" }.joinToString(",")
            
            val cursor = database.rawQuery(
                "SELECT * FROM error_logs WHERE level IN ($validLevels) ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            
            cursor.use {
                while (it.moveToNext()) {
                    logs.add(LogEntry(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        level = Level.valueOf(it.getString(it.getColumnIndexOrThrow("level"))),
                        component = it.getString(it.getColumnIndexOrThrow("component")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        stackTrace = it.getString(it.getColumnIndexOrThrow("stack_trace")),
                        sessionId = it.getString(it.getColumnIndexOrThrow("session_id")),
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to query logs: ${e.message}")
        }
        
        return logs
    }

    fun getLogsByComponent(component: String, limit: Int = 50): List<LogEntry> {
        val database = db ?: return emptyList()
        val logs = mutableListOf<LogEntry>()
        
        try {
            val cursor = database.rawQuery(
                "SELECT * FROM error_logs WHERE component = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(component, limit.toString())
            )
            
            cursor.use {
                while (it.moveToNext()) {
                    logs.add(LogEntry(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        level = Level.valueOf(it.getString(it.getColumnIndexOrThrow("level"))),
                        component = it.getString(it.getColumnIndexOrThrow("component")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        stackTrace = it.getString(it.getColumnIndexOrThrow("stack_trace")),
                        sessionId = it.getString(it.getColumnIndexOrThrow("session_id")),
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to query logs: ${e.message}")
        }
        
        return logs
    }

    fun getErrorsAndCrashes(limit: Int = 50): List<LogEntry> {
        return getRecentLogs(limit, Level.ERROR)
    }

    fun getCrashesOnly(limit: Int = 20): List<LogEntry> {
        return getRecentLogs(limit, Level.CRASH)
    }

    fun getCurrentSessionLogs(): List<LogEntry> {
        val database = db ?: return emptyList()
        val logs = mutableListOf<LogEntry>()
        
        try {
            val cursor = database.rawQuery(
                "SELECT * FROM error_logs WHERE session_id = ? ORDER BY timestamp DESC",
                arrayOf(sessionId)
            )
            
            cursor.use {
                while (it.moveToNext()) {
                    logs.add(LogEntry(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        level = Level.valueOf(it.getString(it.getColumnIndexOrThrow("level"))),
                        component = it.getString(it.getColumnIndexOrThrow("component")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        stackTrace = it.getString(it.getColumnIndexOrThrow("stack_trace")),
                        sessionId = it.getString(it.getColumnIndexOrThrow("session_id")),
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to query logs: ${e.message}")
        }
        
        return logs
    }

    // ── Statistics ────────────────────────────────────────────────────

    fun getStats(): Map<String, Any> {
        val database = db ?: return emptyMap()
        
        return try {
            val totalCursor = database.rawQuery("SELECT COUNT(*) FROM error_logs", null)
            val total = if (totalCursor.moveToFirst()) totalCursor.getInt(0) else 0
            totalCursor.close()
            
            val errorsCursor = database.rawQuery(
                "SELECT COUNT(*) FROM error_logs WHERE level IN ('ERROR', 'CRASH')", null)
            val errors = if (errorsCursor.moveToFirst()) errorsCursor.getInt(0) else 0
            errorsCursor.close()
            
            val crashesCursor = database.rawQuery(
                "SELECT COUNT(*) FROM error_logs WHERE level = 'CRASH'", null)
            val crashes = if (crashesCursor.moveToFirst()) crashesCursor.getInt(0) else 0
            crashesCursor.close()
            
            mapOf(
                "total" to total,
                "errors" to errors,
                "crashes" to crashes,
                "sessionId" to sessionId,
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ── Export ────────────────────────────────────────────────────────

    fun exportToText(limit: Int = 200, completeStacks: Boolean = false): String {
        val logs = getRecentLogs(limit)
        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("LIFECYCLE BOT ERROR LOG EXPORT")
            appendLine("Session: $sessionId")
            appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            
            logs.reversed().forEach { log ->
                appendLine("${log.timeFormatted} [${log.level}] ${log.component}")
                appendLine("  ${log.message}")
                if (!log.stackTrace.isNullOrBlank()) {
                    appendLine("  Stack: ${if (completeStacks) log.stackTrace else log.stackTrace.take(500)}")
                }
                appendLine()
            }
        }
    }

    // V5.0.4088 — COMPACT ERROR-LOG TABLE (operator: "the error log needs to be
    // truncated at the end into a much more condensed table. heaps of good info
    // is cut off"). Drops the verbose 3-line-per-entry format in favour of a
    // single-line table that fits ~40-50 entries in the same ~3.2KB budget the
    // verbose version chewed through with ~12 entries — and prepends a level +
    // component frequency rollup so the operator can spot patterns at a glance.
    fun exportToCompactTable(limit: Int = 80, msgMax: Int = 96): String {
        val logs = getRecentLogs(limit)
        if (logs.isEmpty()) return "(no log entries captured)"
        val levelHist = LinkedHashMap<String, Int>()
        val componentHist = LinkedHashMap<String, Int>()
        for (log in logs) {
            levelHist.merge(log.level.name, 1) { a, b -> a + b }
            componentHist.merge(log.component, 1) { a, b -> a + b }
        }
        return buildString {
            appendLine("Session: $sessionId · entries: ${logs.size} (newest first)")
            val byLevel = levelHist.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key}=${it.value}" }
            appendLine("By level: $byLevel")
            val byComp = componentHist.entries.sortedByDescending { it.value }
                .take(8)
                .joinToString(" · ") { "${it.key}=${it.value}" }
            appendLine("Top components: $byComp")
            appendLine()
            appendLine("TIME     LVL  COMPONENT          MESSAGE")
            for (log in logs) {
                val time = log.timeFormatted.takeLast(8) // HH:MM:SS
                val lvl = log.level.name.padEnd(4).take(4)
                val comp = log.component.padEnd(18).take(18)
                val msg = log.message.replace('\n', ' ').replace('\r', ' ').trim()
                    .let { if (it.length > msgMax) it.take(msgMax - 1) + "…" else it }
                appendLine("$time $lvl $comp $msg")
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    fun cleanup() {
        val database = db ?: return
        val runner = Runnable {
            try {
                database.execSQL(
                    "DELETE FROM error_logs WHERE id NOT IN " +
                    "(SELECT id FROM error_logs ORDER BY timestamp DESC LIMIT $MAX_LOGS)"
                )
            } catch (e: Exception) {
                android.util.Log.e("ErrorLogger", "Failed to cleanup: ${e.message}")
            }
        }
        // V5.9.36: never block caller thread on the DELETE query
        ioHandler?.post(runner) ?: runner.run()
    }

    fun clearAll() {
        val database = db ?: return
        val runner = Runnable {
            try {
                database.execSQL("DELETE FROM error_logs")
            } catch (e: Exception) {
                android.util.Log.e("ErrorLogger", "Failed to clear: ${e.message}")
            }
        }
        ioHandler?.post(runner) ?: runner.run()
        log(Level.INFO, "System", "Logs cleared")
    }

    // ── Database helper ───────────────────────────────────────────────

    private class ErrorDbHelper(context: Context) : SQLiteOpenHelper(
        context, "error_logs.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE error_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    level TEXT NOT NULL,
                    component TEXT NOT NULL,
                    message TEXT NOT NULL,
                    stack_trace TEXT,
                    session_id TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_timestamp ON error_logs(timestamp)")
            db.execSQL("CREATE INDEX idx_level ON error_logs(level)")
            db.execSQL("CREATE INDEX idx_component ON error_logs(component)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS error_logs")
            onCreate(db)
        }
    }
}
