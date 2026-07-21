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
    // V5.0.6305 — eager init (was `by lazy`). RCA (troubleshoot agent iteration_2)
    // showed a race where an early Copy tap could hit the handler before the
    // lazy delegate fired. Since bgThread is already .start()ed above, the
    // looper is ready immediately.
    private val bgHandler: Handler = Handler(bgThread.looper)

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

            // V5.0.6281 — Self-Healing LLM Advisor entry point.
            findViewById<Button>(R.id.advisorButton).setOnClickListener {
                onAdvisorButtonTapped()
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
     *
     * V5.0.6273 — SILENT CLIPBOARD FAILURE FIX. Operator reported the
     * pipeline report has "not copied" for the last ten asks. Root cause:
     * ReportingHub.buildTextAsync fires its callback on the render worker
     * thread, but ClipboardManager.setPrimaryClip AND Toast.show both
     * require the main thread — on Android 12+ they silently no-op when
     * called from a background thread. Wrap the clipboard + toast in
     * runOnUiThread so the copy actually lands. Diagnostic label emitted
     * both ways so we can see it firing next report.
     */
    private fun copyToClipboardAsync() {
        if (destroyed || !viewsBound) return
        // V5.0.6306 — HARD BYPASS OF REPORTINGHUB FOR COPY.
        // RCA V5.0.6306 (5th attempt, user extremely frustrated):
        //   V5.0.6305 removed the renderGeneration silent-return, but the copy
        //   was STILL failing with 'toast says it starts then does nothing'.
        //   Deep dive: ReportingHub.buildTextAsync uses
        //     scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        //   and the bot loop absolutely THRASHES Dispatchers.IO — cycle times
        //   in the op-report are 4-8s each with `ANR 22` on the Pipeline tile.
        //   The Copy coroutine gets queued behind dozens of IO scanner/API
        //   tasks and NEVER gets a thread. The withTimeout(6s) is inside the
        //   Mutex lock; if the Mutex holder or the queue is stalled, timeout
        //   never fires either. Callback never invoked → no clipboard, no
        //   second toast.
        //
        // NEW PATH: dedicated java Thread that:
        //   1. Reads the fast in-memory PipelineHealthCollector snapshot
        //      (bounded StringBuilder walk — no coroutine, no IO)
        //   2. Writes clipboard on the SAME background thread (setPrimaryClip
        //      is thread-safe on API 26+, and this activity is only reached
        //      on API 26+ devices).
        //   3. Posts the confirm Toast to Main.
        // Zero shared resources with the bot loop. This CANNOT hang because of
        // IO backlog.
        try {
            Toast.makeText(this, "Copying report…", Toast.LENGTH_SHORT).show()
            com.lifecyclebot.engine.ForensicLogger.lifecycle("UNIFIED_REPORT_COPY_TAP_6306", "src=pipeline_health path=direct_bypass")
        } catch (_: Throwable) {}
        val activityRef = this
        Thread({
            var text = ""
            var buildErr = ""
            try {
                // Fast path — PipelineHealthCollector.pasteSafeSnapshot() is a
                // bounded StringBuilder over already-collected counters. No IO,
                // no coroutines, no mutex. Returns in single-digit ms. Note:
                // this deliberately BYPASSES ReportingHub.Kind.UNIFIED_HEALTH
                // (the coroutine-dispatched build) because that path can be
                // starved by bot-loop Dispatchers.IO saturation and never fire
                // its callback (which is exactly the operator-reported bug).
                text = com.lifecyclebot.engine.PipelineHealthCollector.pasteSafeSnapshot()
            } catch (t: Throwable) {
                buildErr = t.javaClass.simpleName + ":" + (t.message?.take(80) ?: "")
                text = "AATE report build error: $buildErr\n\n(Direct-bypass path could not read PipelineHealthCollector.)"
            }
            // Cap to the safe budget so setPrimaryClip cannot stall.
            val safeText = com.lifecyclebot.engine.ReportingHub.clipboardSafeText(text)
            var ok = false
            var writeErr = ""
            try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("AATE Unified Report", safeText))
                ok = true
            } catch (t: Throwable) {
                writeErr = t.javaClass.simpleName + ":" + (t.message?.take(80) ?: "")
            }
            mainHandler.post {
                if (destroyed) return@post
                if (ok) {
                    try { com.lifecyclebot.engine.ForensicLogger.lifecycle("UNIFIED_REPORT_COPY_ONLY", "chars=${safeText.length} full=${text.length} hub=false thread=direct6306") } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("UNIFIED_REPORT_COPY_OK_6306") } catch (_: Throwable) {}
                    val msg = if (safeText.length < text.length) {
                        "Copied ${safeText.length} of ${text.length} chars (paste-safe cap)"
                    } else "Copied ${safeText.length} chars"
                    Toast.makeText(activityRef, msg, Toast.LENGTH_LONG).show()
                } else {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("UNIFIED_REPORT_COPY_FAIL_6306") } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.ForensicLogger.lifecycle("UNIFIED_REPORT_COPY_FAIL_6306", "buildErr=$buildErr writeErr=$writeErr") } catch (_: Throwable) {}
                    Toast.makeText(activityRef, "Copy failed: $writeErr", Toast.LENGTH_LONG).show()
                }
            }
        }, "PipelineHealth-DirectCopy-6306").start()
    }

    private fun formatBig(v: Long): String = when {
        v >= 1_000_000 -> "${v / 1_000_000}.${((v % 1_000_000) / 100_000)}M"
        v >= 10_000    -> "${v / 1_000}.${((v % 1_000) / 100)}k"
        else           -> v.toString()
    }

    // V5.0.6281 — Self-Healing LLM Advisor UI: tap → run → dialog → accept/dismiss.
    private fun onAdvisorButtonTapped() {
        if (com.lifecyclebot.engine.SelfHealingAdvisor.isRunning()) {
            Toast.makeText(this, "Advisor is already running…", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = com.lifecyclebot.engine.AdvisorInbox.pending()
        if (pending.isNotEmpty()) {
            showAdvisorDialog(pending)
            return
        }
        Toast.makeText(this, "🩺 Advisor analysing pipeline…", Toast.LENGTH_SHORT).show()
        com.lifecyclebot.engine.SelfHealingAdvisor.runNowAsync(applicationContext) { res ->
            mainHandler.post {
                if (!res.ok) {
                    Toast.makeText(this, "Advisor: ${res.error ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    return@post
                }
                if (res.suggestions.isEmpty()) {
                    Toast.makeText(this, "Advisor: nothing to tune right now — bot looks healthy.", Toast.LENGTH_LONG).show()
                    return@post
                }
                showAdvisorDialog(res.suggestions)
            }
        }
    }

    private fun showAdvisorDialog(suggestions: List<com.lifecyclebot.engine.SelfHealingAdvisor.Suggestion>) {
        val labels = suggestions.map { s ->
            val severity = when (s.severity) {
                "high" -> "🔴"
                "med"  -> "🟠"
                else   -> "🟡"
            }
            val delta = if (s.delta >= 0) "+${s.delta}" else s.delta.toString()
            "$severity ${s.key} $delta\n  ${s.reason}\n  → ${s.expectedImpact}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🩺 Self-Healing Advisor (${suggestions.size})")
            .setItems(labels) { _, which ->
                val chosen = suggestions[which]
                val (ok, msg) = com.lifecyclebot.engine.SelfHealingAdvisor.applySuggestion(applicationContext, chosen)
                Toast.makeText(
                    this,
                    if (ok) "✅ Applied — $msg" else "⚠️ Not applied — $msg",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton("Dismiss all") { _, _ ->
                for (s in suggestions) com.lifecyclebot.engine.AdvisorInbox.dismiss(s.id)
                Toast.makeText(this, "Dismissed ${suggestions.size} suggestion(s)", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Later", null)
            .show()
    }
}
