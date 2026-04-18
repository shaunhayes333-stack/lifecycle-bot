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

    // ── Initialize ────────────────────────────────────────────────────

    fun init(context: Context) {
        if (db != null) return
        val helper = ErrorDbHelper(context)
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

        // Always mirror to logcat immediately on the caller thread — cheap,
        // and invaluable for attaching debuggers.
        val tag = "AATE.$component"
        try {
            when (level) {
                Level.DEBUG -> android.util.Log.d(tag, message, throwable)
                Level.INFO -> android.util.Log.i(tag, message, throwable)
                Level.WARN -> android.util.Log.w(tag, message, throwable)
                Level.ERROR -> android.util.Log.e(tag, message, throwable)
                Level.CRASH -> android.util.Log.e(tag, "CRASH: $message", throwable)
            }
        } catch (_: Throwable) { /* never let logcat failures propagate */ }

        // Capture values that we need off-thread (throwable stack must be
        // rendered on the caller thread in case it gets mutated).
        val ts = System.currentTimeMillis()
        val lvlName = level.name
        val msg = message.take(1000)
        val stack = throwable?.stackTraceToString()?.take(2000)
        val sid = sessionId

        val handler = ioHandler
        if (handler == null) {
            // Init hasn't run yet — fall back to sync insert (startup only).
            writeToDb(database, ts, lvlName, component, msg, stack, sid)
            return
        }
        handler.post {
            writeToDb(database, ts, lvlName, component, msg, stack, sid)
        }
    }

    private fun writeToDb(
        database: SQLiteDatabase,
        ts: Long,
        lvlName: String,
        component: String,
        msg: String,
        stack: String?,
        sid: String,
    ) {
        try {
            val values = ContentValues().apply {
                put("timestamp", ts)
                put("level", lvlName)
                put("component", component)
                put("message", msg)
                put("stack_trace", stack)
                put("session_id", sid)
            }
            database.insert("error_logs", null, values)
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to log: ${e.message}")
        }
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

    fun exportToText(limit: Int = 200): String {
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
                    appendLine("  Stack: ${log.stackTrace.take(500)}")
                }
                appendLine()
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
