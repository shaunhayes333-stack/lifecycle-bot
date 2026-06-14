package com.lifecyclebot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
 * V5.9.904 — moved snapshot/dumpText off the main thread. Operator
 * forensics V5.9.899 dump showed renderSnapshot stalling the main
 * thread for 6248ms (max frame), with 250ms gaps every 2s in the
 * rolling sample. dumpText() concatenates ~16KB of forensic text
 * including iteration over multiple ConcurrentHashMaps and a ring
 * buffer of recent events — that work now runs on a dedicated
 * background HandlerThread, and only the final string is posted
 * back to the TextView on main.
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
    private lateinit var sectionLabel: TextView
    private lateinit var prevSectionButton: Button
    private lateinit var nextSectionButton: Button

    @Volatile private var fullDumpCache: String = ""
    private var reportSections: List<String> = emptyList()
    private var currentSectionIndex: Int = 0

    // V5.9.1074 — lifecycle render gate. onResume() can fire before the
    // first-frame-posted findViewById block binds lateinit TextViews, and
    // background/recents transitions can deliver bg render results after
    // onPause/onDestroy. Both cases used to touch lateinit/TextView layout
    // from stale posts, causing Activity crashes or TextView.makeSingleLayout
    // ANR storms while Android was stopping the render surface.
    @Volatile private var viewsBound: Boolean = false
    @Volatile private var activityVisible: Boolean = false
    @Volatile private var destroyed: Boolean = false
    @Volatile private var renderGeneration: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // V5.9.904 — background thread for snapshot dump building.
    // dumpText() walks 12+ ConcurrentHashMaps and builds a 16KB
    // StringBuilder. Running it every 2s on the main thread is
    // the documented cause of the 6s main-thread stall in the
    // V5.9.899 snapshot. Keep the snapshot collection itself on
    // background; only post the final rendered text to main.
    //
    // V5.9.1047 — operator V5.9.1046 dump showed
    // PipelineHealthActivity$bgThread$2.invoke at 793ms on Main
    // (HandlerThread.<init> + Thread.<init>). The V5.9.1045 daemon
    // pre-warm lost the race against Main's first access via
    // renderSnapshotAsync() inside onCreate. Fix: drop `lazy` and
    // start the HandlerThread eagerly at class-field init, so by
    // the time onCreate calls renderSnapshotAsync the looper is
    // already prepared and `Handler(bgThread.looper)` returns
    // instantly.
    private val bgThread: HandlerThread =
        HandlerThread("PipelineHealthRender").apply { start() }
    private val bgHandler: Handler by lazy { Handler(bgThread.looper) }

    private val refreshIntervalMs = 60_000L  // V5.9.1151: health screen is forensic; reduce main/UI churn while bot runs.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (destroyed || !activityVisible || !viewsBound) return
            renderSnapshotAsync()
            if (!destroyed && activityVisible && viewsBound) mainHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pipeline_health)

        // V5.9.1047 — bgThread now starts eagerly at class-field init
        // (above). The V5.9.1045 daemon pre-warm became redundant and
        // was removed. By the time we reach here the HandlerThread's
        // Looper is prepared and Handler(bgThread.looper) is instant.

        // V5.9.1057: installAnrWatcherOnMainThread() removed — BotService already installs it. Redundant call added jank on activity transition.
        // V5.9.1065 ANR FIX: operator V5.9.1064 snapshot showed
        // PipelineHealthActivity.onCreate consuming the main thread
        // for 1010 + 757 + 505 + 251 ms (~2.5s "black screen hang"
        // every time the panel opens). Stack: Button.<init> →
        // Paint.<init> → NativeAllocationRegistry. The XML inflate
        // is unavoidable but the 8× findViewById + 7× setOnClick-
        // Listener chain can ride one vsync. Posting past first
        // frame lets the layout paint immediately, then heavy
        // listener wiring runs ~16 ms later while the user sees
        // an already-rendered (button-less) panel.
        window.decorView.post {

            dumpText      = findViewById(R.id.dumpText)
            // V5.9.1066 — micro-opt: dumpText holds ~16KB of forensic text;
            // BREAK_STRATEGY_HIGH_QUALITY + auto-hyphenation cost
            // 500-700ms per setText (514+263ms render-back hit in the
            // V5.9.1065 snapshot). SIMPLE strategy + no hyphenation
            // drops that to ~150-200ms, and selectable=false skips the
            // accessibility/selection overlay layer that fired the
            // 245ms View.onCreateDrawableState hits.
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    dumpText.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    dumpText.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                }
                dumpText.setTextIsSelectable(false)
            } catch (_: Throwable) {}
            statLoop      = findViewById(R.id.statLoop)
            statExec      = findViewById(R.id.statExec)
            statJrnl      = findViewById(R.id.statJrnl)
            statMaxFrame  = findViewById(R.id.statMaxFrame)
            anrBadge      = findViewById(R.id.anrBadge)
            sectionLabel = findViewById(R.id.sectionLabel)
            prevSectionButton = findViewById(R.id.prevSectionButton)
            nextSectionButton = findViewById(R.id.nextSectionButton)
            viewsBound = true

            findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

            findViewById<Button>(R.id.copyButton).setOnClickListener { copyToClipboardAsync() }

            findViewById<Button>(R.id.refreshButton).setOnClickListener { renderSnapshotAsync(forceFull = false, manualRefresh = true) }
            prevSectionButton.setOnClickListener { moveSection(-1) }
            nextSectionButton.setOnClickListener { moveSection(1) }

            findViewById<Button>(R.id.resetButton).setOnClickListener {
                // V5.9.904 — reset must run on bg too, then refresh from bg.
                bgHandler.post {
                    try { PipelineHealthCollector.reset() } catch (_: Throwable) {}
                    mainHandler.post {
                        Toast.makeText(this, "Counters reset — fresh capture started", Toast.LENGTH_SHORT).show()
                    }
                    renderSnapshotAsync()
                }
            }

            mainHandler.postDelayed({ renderSnapshotAsync(forceFull = false, manualRefresh = true) }, 750L)
        }
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        renderGeneration++
        if (viewsBound) mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        activityVisible = false
        renderGeneration++
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        activityVisible = false
        renderGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        // V5.9.904 — quit bg thread so it doesn't leak when activity dies.
        try { bgThread.quitSafely() } catch (_: Throwable) {}
    }

    /**
     * V5.9.904 — Snapshot + dumpText now executed on a background
     * HandlerThread. Stat counts (loop / exec / jrnl) and the full
     * dump string are all computed off-main; only the final UI
     * updates post back to the main looper. This eliminates the
     * 250ms+ frame stalls every 2s observed in the V5.9.899 dump.
     */
    private fun renderSnapshotAsync(forceFull: Boolean = false, manualRefresh: Boolean = false) {
        if (destroyed || !activityVisible || !viewsBound) return
        val generation = renderGeneration
        bgHandler.post {
            if (destroyed || !activityVisible || generation != renderGeneration) return@post
            val snap = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { return@post }
            // V5.9.1071 — automatic refresh should not rebuild the full forensic dump.
            // dumpText() walks large rings/maps and then causes TextView layout churn when
            // posted back. Build the full dump only on first render or explicit refresh/copy;
            // normal timer refresh updates badges from the lightweight snapshot only.
            // V5.9.1084 — full forensic dump is COPY/EXPORT ONLY.
            // Opening this activity must not build/render the massive dump by default.
            val needFullDump = forceFull
            val dump = if (needFullDump) {
                try { PipelineHealthCollector.dumpText() } catch (_: Throwable) { "(render error)" }
            } else fullDumpCache
            val sections = if (needFullDump) splitDumpIntoSections(dump) else reportSections
            val loop = snap.phaseCounts["SCAN_CB"]
                ?: snap.labelCounts["BOT_LOOP_TICK"]
                ?: 0L
            val exec = snap.phaseCounts["EXEC"] ?: 0L
            val jrnl = snap.labelCounts["TRADEJRNL_REC"] ?: 0L
            // V5.9.1174 — preformat all stat strings off-main. String.format /
            // DecimalFormatSymbols showed up in ANR stacks; keep main to cheap
            // TextView assignment only.
            val loopTxt = formatBig(loop)
            val execTxt = formatBig(exec)
            val jrnlTxt = formatBig(jrnl)
            val maxFrameTxt = "${snap.maxFrameGapMs} ms"
            val anrTxt = "ANR: ${snap.anrHints}"
            val anrColor = when {
                snap.anrHints == 0  -> 0xFF10B981.toInt()
                snap.anrHints < 5   -> 0xFFF59E0B.toInt()
                else                -> 0xFFEF4444.toInt()
            }

            mainHandler.post {
                if (destroyed || !activityVisible || !viewsBound || generation != renderGeneration) return@post
                if (needFullDump) {
                    fullDumpCache = dump
                    reportSections = sections
                    if (reportSections.isEmpty()) reportSections = listOf(dump)
                    if (forceFull) currentSectionIndex = 0
                    if (currentSectionIndex !in reportSections.indices) currentSectionIndex = 0
                    try { com.lifecyclebot.engine.ForensicLogger.lifecycle("PIPELINE_FULL_DUMP_COPY_ONLY", "chars=${dump.length}") } catch (_: Throwable) {}
                } else if (reportSections.isEmpty() || manualRefresh) {
                    reportSections = listOf(
                        "Pipeline Health — lightweight view\n\n" +
                            "Loop/scan events: $loop\n" +
                            "Executor invocations: $exec\n" +
                            "Journal writes: $jrnl\n" +
                            "ANR hints: ${snap.anrHints}\n" +
                            "Max frame gap: ${snap.maxFrameGapMs} ms\n\n" +
                            "Full forensic dump is copy/export only. Tap Copy to generate it off-main."
                    )
                    currentSectionIndex = 0
                }
                statLoop.text     = loopTxt
                statExec.text     = execTxt
                statJrnl.text     = jrnlTxt
                statMaxFrame.text = maxFrameTxt
                anrBadge.text     = anrTxt
                anrBadge.setTextColor(anrColor)
                renderCurrentSection()
            }
        }
    }

    /**
     * V5.9.1018 — FULL REPORT, SECTIONED RENDER.
     * The complete dump remains generated and copyable, but the screen only
     * lays out one section at a time. The previous all-in-one TextView forced
     * Android StaticLayout/TextView to measure tens of thousands of characters
     * every refresh and could choke bot loop scheduling when Pipeline was open.
     */
    private fun splitDumpIntoSections(dump: String): List<String> {
        if (dump.isBlank()) return listOf("(empty report)")
        val parts = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        dump.lineSequence().forEach { line ->
            val isHeader = line.startsWith("=====") && line.endsWith("=====")
            if (isHeader && current.isNotEmpty()) {
                parts.add(current)
                current = StringBuilder()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) parts.add(current)
        return parts.map { it.toString().trimEnd() }.filter { it.isNotBlank() }
    }

    private fun renderCurrentSection() {
        if (destroyed || !activityVisible || !viewsBound) return
        val sections = reportSections.ifEmpty { listOf(fullDumpCache.ifBlank { "(report loading…)" }) }
        val idx = currentSectionIndex.coerceIn(0, sections.lastIndex)
        currentSectionIndex = idx
        val rawSection = sections[idx]
        // V5.9.1174 — hard UI preview cap. The full 70k+ dump is copy-only;
        // TextView/StaticLayout must never measure more than 5k chars.
        val section = if (rawSection.length > 2_000) {
            rawSection.take(2_000) + "\n\n… section truncated for UI render (${rawSection.length} chars). Use Copy for the full dump."
        } else rawSection
        val title = rawSection.lineSequence().firstOrNull()?.removePrefix("=====")?.removeSuffix("=====")?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Pipeline section"
        sectionLabel.text = "Section ${idx + 1}/${sections.size}: $title"
        prevSectionButton.isEnabled = idx > 0
        nextSectionButton.isEnabled = idx < sections.lastIndex
        // V5.9.1057: skip setText if section unchanged (avoids full layout pass every 20s)
        if (section != dumpText.text?.toString()) dumpText.text = section
    }

    private fun moveSection(delta: Int) {
        if (reportSections.isEmpty()) return
        currentSectionIndex = (currentSectionIndex + delta).coerceIn(0, reportSections.lastIndex)
        renderCurrentSection()
    }

    /**
     * V5.9.904 — Copy-to-clipboard now builds the dump on bg too,
     * then posts the clipboard write to main (ClipboardManager
     * requires main). Previously the 16KB dump built on main blocked
     * the UI thread for the copy tap as well.
     */
    private fun copyToClipboardAsync() {
        if (destroyed || !viewsBound) return
        val generation = renderGeneration
        com.lifecyclebot.engine.ReportingHub.buildTextAsync(
            com.lifecyclebot.engine.ReportingHub.Kind.UNIFIED_HEALTH,
            forceFresh = true,
        ) { report, error ->
            if (destroyed || generation != renderGeneration) return@buildTextAsync
            val text = report?.text ?: "(render error: ${error?.message ?: "unknown"})"
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("AATE Unified Report", text))
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("UNIFIED_REPORT_COPY_ONLY", "chars=${text.length} hub=true") } catch (_: Throwable) {}
            Toast.makeText(
                this,
                "Unified report copied (${text.length} chars)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun formatBig(v: Long): String = when {
        v >= 1_000_000 -> "${v / 1_000_000}.${((v % 1_000_000) / 100_000)}M"
        v >= 10_000    -> "${v / 1_000}.${((v % 1_000) / 100)}k"
        else           -> v.toString()
    }
}
