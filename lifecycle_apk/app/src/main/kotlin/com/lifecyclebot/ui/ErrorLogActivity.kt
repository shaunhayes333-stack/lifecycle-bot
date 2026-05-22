package com.lifecyclebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lifecyclebot.R
import com.lifecyclebot.engine.ErrorLogger

/**
 * ErrorLogActivity — View in-app error logs
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Shows all captured errors, warnings, and crashes from the app.
 * Allows filtering by severity level and exporting logs.
 */
class ErrorLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LogAdapter
    private lateinit var filterSpinner: Spinner
    private lateinit var statsText: TextView
    private lateinit var emptyText: TextView
    
    private var currentFilter: ErrorLogger.Level = ErrorLogger.Level.DEBUG

    private companion object {
        const val LOG_DETAIL_PREVIEW_CHARS = 4_000
        const val LOG_ROW_MESSAGE_PREVIEW_CHARS = 140
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_log)
        
        // Initialize ErrorLogger if not already done
        ErrorLogger.init(this)
        
        setupViews()
        setupFilter()
        loadLogs()
    }

    private fun setupViews() {
        // Back button
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }
        
        // Title
        findViewById<TextView>(R.id.titleText).text = "Error Logs"
        
        // Stats
        statsText = findViewById(R.id.statsText)
        
        // Empty state
        emptyText = findViewById(R.id.emptyText)
        
        // RecyclerView
        recyclerView = findViewById(R.id.logsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter(emptyList()) { log ->
            showLogDetail(log)
        }
        recyclerView.adapter = adapter
        
        // Filter spinner
        filterSpinner = findViewById(R.id.filterSpinner)
        
        // Export button
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            exportLogs()
        }
        
        // Clear button
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            confirmClear()
        }
        
        // FIX: refreshButton is a TextView in the XML layout, not an ImageButton.
        // Using View (base class) avoids a ClassCastException crash on launch.
        findViewById<View>(R.id.refreshButton).setOnClickListener {
            loadLogs()
            Toast.makeText(this, "Logs refreshed", Toast.LENGTH_SHORT).show()
        }

        // V5.9.666 — Pipeline Health launcher.
        findViewById<Button>(R.id.openPipelineHealthButton)?.setOnClickListener {
            startActivity(Intent(this, PipelineHealthActivity::class.java))
        }
    }

    private fun setupFilter() {
        val levels = listOf("All (DEBUG+)", "Info+", "Warnings+", "Errors+", "Crashes Only")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterSpinner.adapter = spinnerAdapter
        
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFilter = when (position) {
                    0 -> ErrorLogger.Level.DEBUG
                    1 -> ErrorLogger.Level.INFO
                    2 -> ErrorLogger.Level.WARN
                    3 -> ErrorLogger.Level.ERROR
                    4 -> ErrorLogger.Level.CRASH
                    else -> ErrorLogger.Level.DEBUG
                }
                loadLogs()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadLogs() {
        // V5.9.724 — was synchronous SQLite cursor on main thread (43% uptime stall in V5.9.722 dump).
        // Now: query runs on a background thread; results posted back to the UI thread.
        statsText.text = "Loading…"
        Thread {
            val logs = try {
                ErrorLogger.getRecentLogs(200, currentFilter).map { UiLog.from(it) }
            } catch (t: Throwable) {
                android.util.Log.e("ErrorLogActivity", "loadLogs failed: ${t.message}")
                emptyList()
            }
            val stats = try { ErrorLogger.getStats() } catch (_: Throwable) { emptyMap<String, Any>() }
            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post
                adapter.updateLogs(logs)
                statsText.text = "Total: ${stats["total"]} | Errors: ${stats["errors"]} | Crashes: ${stats["crashes"]} | Session: ${stats["sessionId"]}"
                emptyText.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun showLogDetail(log: UiLog) {
        val fullMessage = log.fullText
        val preview = if (fullMessage.length > LOG_DETAIL_PREVIEW_CHARS) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "ERROR_LOG_PREVIEW_TRUNCATED",
                    "charsShown=$LOG_DETAIL_PREVIEW_CHARS totalChars=${fullMessage.length} component=${log.component} level=${log.level}",
                )
            } catch (_: Throwable) {}
            fullMessage.take(LOG_DETAIL_PREVIEW_CHARS) + "\n\n… truncated for UI preview (${fullMessage.length} chars). Use Copy for the full log."
        } else fullMessage
        
        AlertDialog.Builder(this)
            .setTitle("${log.levelIcon} ${log.component}")
            .setMessage(preview)
            .setPositiveButton("Copy full") { _, _ ->
                Thread {
                    Handler(Looper.getMainLooper()).post {
                        if (isFinishing || isDestroyed) return@post
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Log", fullMessage))
                        Toast.makeText(this, "Full log copied", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportLogs() {
        // V5.9.1049 ANR FIX: ErrorLogger.exportToText() walks the entire
        // SQLite log table and stringifies every entry — was running on
        // Main (top-3 ANR site in V5.9.1040 snapshot). Move to a background
        // thread; show the AlertDialog only after the text is ready.
        Toast.makeText(this, "Preparing logs…", Toast.LENGTH_SHORT).show()
        Thread {
            val exportText = try {
                ErrorLogger.exportToText()
            } catch (t: Throwable) {
                "Error exporting logs: ${t.message}"
            }
            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post

                // Copy to clipboard only after the export string was built off-main.
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Error Logs", exportText))
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("ERROR_LOG_EXPORT_COPIED", "chars=${exportText.length}") } catch (_: Throwable) {}

                // Also offer to share. Do not render the blob in any TextView/Dialog.
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, exportText)
                    putExtra(Intent.EXTRA_SUBJECT, "AATE Error Logs")
                }

                AlertDialog.Builder(this)
                    .setTitle("Export Complete")
                    .setMessage("Logs copied to clipboard. Would you like to share them?")
                    .setPositiveButton("Share") { _, _ ->
                        startActivity(Intent.createChooser(shareIntent, "Share logs"))
                    }
                    .setNegativeButton("Done", null)
                    .show()
            }
        }.start()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Logs?")
            .setMessage("This will delete all stored error logs. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                ErrorLogger.clearAll()
                loadLogs()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────

    private data class UiLog(
        val id: Long,
        val timeFormatted: String,
        val level: ErrorLogger.Level,
        val levelIcon: String,
        val component: String,
        val messagePreview: String,
        val fullText: String,
    ) {
        companion object {
            fun from(log: ErrorLogger.LogEntry): UiLog {
                val full = buildString {
                    appendLine("Time: ${log.timeFormatted}")
                    appendLine("Level: ${log.level}")
                    appendLine("Component: ${log.component}")
                    appendLine("Session: ${log.sessionId}")
                    appendLine()
                    appendLine("Message:")
                    appendLine(log.message)
                    if (!log.stackTrace.isNullOrBlank()) {
                        appendLine()
                        appendLine("Stack Trace:")
                        appendLine(log.stackTrace)
                    }
                }
                val preview = log.message.take(LOG_ROW_MESSAGE_PREVIEW_CHARS) + if (log.message.length > LOG_ROW_MESSAGE_PREVIEW_CHARS) "…" else ""
                return UiLog(log.id, log.timeFormatted, log.level, log.levelIcon, log.component, preview, full)
            }
        }
    }

    private class LogAdapter(
        private var logs: List<UiLog>,
        private val onClick: (UiLog) -> Unit
    ) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val timeText: TextView = view.findViewById(R.id.timeText)
            val levelText: TextView = view.findViewById(R.id.levelText)
            val componentText: TextView = view.findViewById(R.id.componentText)
            val messageText: TextView = view.findViewById(R.id.messageText)
            val container: View = view.findViewById(R.id.logContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_error_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            
            holder.timeText.text = log.timeFormatted
            holder.levelText.text = log.levelIcon
            holder.componentText.text = log.component
            holder.messageText.text = log.messagePreview
            
            // Color based on level
            val bgColor = when (log.level) {
                ErrorLogger.Level.DEBUG -> Color.parseColor("#1a1a2e")
                ErrorLogger.Level.INFO -> Color.parseColor("#1a2a1a")
                ErrorLogger.Level.WARN -> Color.parseColor("#2a2a1a")
                ErrorLogger.Level.ERROR -> Color.parseColor("#2a1a1a")
                ErrorLogger.Level.CRASH -> Color.parseColor("#3a0a0a")
            }
            holder.container.setBackgroundColor(bgColor)
            
            holder.itemView.setOnClickListener { onClick(log) }
        }

        override fun getItemCount() = logs.size

        fun updateLogs(newLogs: List<UiLog>) {
            val oldLogs = logs
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldLogs.size
                override fun getNewListSize() = newLogs.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldLogs[oldItemPosition].id == newLogs[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldLogs[oldItemPosition] == newLogs[newItemPosition]
            })
            logs = newLogs
            diff.dispatchUpdatesTo(this)
        }
    }
}
