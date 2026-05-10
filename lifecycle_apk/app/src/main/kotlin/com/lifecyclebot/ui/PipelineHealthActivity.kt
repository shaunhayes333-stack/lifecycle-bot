package com.lifecyclebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.9.666 — In-app Pipeline Health panel.
 *
 * Mirrors the CI runtime-test funnel summary on-device so the operator
 * can see exactly what's flowing through the bot pipeline (BOT_LOOP_TICK,
 * SCAN_CB, SAFETY, V3, LANE_EVAL, EXEC, TRADEJRNL_REC, gate allow/block
 * tallies, decision verdicts, ANR hints) and copy the entire dump to
 * the clipboard with a single tap for triage / agent-handoff.
 *
 * Auto-refreshes every 2s; Reset clears all counters; Copy puts the
 * full dump on the clipboard.
 */
class PipelineHealthActivity : AppCompatActivity() {

    private lateinit var dumpText: TextView
    private lateinit var statLoop: TextView
    private lateinit var statExec: TextView
    private lateinit var statJrnl: TextView
    private lateinit var statMaxFrame: TextView
    private lateinit var anrBadge: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 2_000L
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderSnapshot()
            handler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pipeline_health)

        // Best-effort: ensure ANR watcher is installed even if BotService
        // never started (e.g. user opened the panel before pressing Start).
        try { PipelineHealthCollector.installAnrWatcherOnMainThread() } catch (_: Throwable) {}

        dumpText      = findViewById(R.id.dumpText)
        statLoop      = findViewById(R.id.statLoop)
        statExec      = findViewById(R.id.statExec)
        statJrnl      = findViewById(R.id.statJrnl)
        statMaxFrame  = findViewById(R.id.statMaxFrame)
        anrBadge      = findViewById(R.id.anrBadge)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<Button>(R.id.copyButton).setOnClickListener { copyToClipboard() }

        findViewById<Button>(R.id.refreshButton).setOnClickListener { renderSnapshot() }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            PipelineHealthCollector.reset()
            renderSnapshot()
            Toast.makeText(this, "Counters reset — fresh capture started", Toast.LENGTH_SHORT).show()
        }

        renderSnapshot()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun renderSnapshot() {
        val snap = PipelineHealthCollector.snapshot()
        val loop = snap.phaseCounts["SCAN_CB"]
            ?: snap.labelCounts["BOT_LOOP_TICK"]
            ?: 0L
        val exec = snap.phaseCounts["EXEC"] ?: 0L
        val jrnl = snap.labelCounts["TRADEJRNL_REC"] ?: 0L
        statLoop.text     = formatBig(loop)
        statExec.text     = formatBig(exec)
        statJrnl.text     = formatBig(jrnl)
        statMaxFrame.text = "${snap.maxFrameGapMs} ms"
        anrBadge.text = "ANR: ${snap.anrHints}"
        // Color the ANR badge green / amber / red based on count.
        anrBadge.setTextColor(
            when {
                snap.anrHints == 0  -> 0xFF10B981.toInt() // green
                snap.anrHints < 5   -> 0xFFF59E0B.toInt() // amber
                else                -> 0xFFEF4444.toInt() // red
            }
        )
        dumpText.text = PipelineHealthCollector.dumpText()
    }

    private fun copyToClipboard() {
        val text = PipelineHealthCollector.dumpText()
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("AATE Pipeline Health", text))
        Toast.makeText(
            this,
            "Pipeline health dump copied (${text.length} chars)",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun formatBig(v: Long): String = when {
        v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
        v >= 10_000    -> String.format("%.1fk", v / 1_000.0)
        else           -> v.toString()
    }
}
