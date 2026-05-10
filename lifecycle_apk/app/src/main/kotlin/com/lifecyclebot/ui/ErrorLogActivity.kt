package com.lifecyclebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        val logs = ErrorLogger.getRecentLogs(200, currentFilter)
        adapter.updateLogs(logs)
        
        val stats = ErrorLogger.getStats()
        statsText.text = "Total: ${stats["total"]} | Errors: ${stats["errors"]} | Crashes: ${stats["crashes"]} | Session: ${stats["sessionId"]}"
        
        emptyText.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showLogDetail(log: ErrorLogger.LogEntry) {
        val message = buildString {
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
        
        AlertDialog.Builder(this)
            .setTitle("${log.levelIcon} ${log.component}")
            .setMessage(message)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Log", message))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportLogs() {
        val exportText = ErrorLogger.exportToText()
        
        // Copy to clipboard
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Error Logs", exportText))
        
        // Also offer to share
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

    private class LogAdapter(
        private var logs: List<ErrorLogger.LogEntry>,
        private val onClick: (ErrorLogger.LogEntry) -> Unit
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
            holder.messageText.text = log.message.take(100) + if (log.message.length > 100) "..." else ""
            
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

        fun updateLogs(newLogs: List<ErrorLogger.LogEntry>) {
            logs = newLogs
            notifyDataSetChanged()
        }
    }
}
