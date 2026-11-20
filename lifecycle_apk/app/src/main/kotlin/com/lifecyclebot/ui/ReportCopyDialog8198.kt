package com.lifecyclebot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.lifecyclebot.engine.execution.PipelineReportFileExporter6401
import java.io.File

/**
 * Share prompt for the COMPLETE report. The clipboard already holds the readable copy; this only offers the full
 * uncapped file. If the file is missing or its share intent fails, the full text is sent as plain EXTRA_TEXT so an
 * export can never end with nothing to share.
 */
object ReportCopyDialog8198 {
    /** 8213 — text that fits one binder transaction; a longer report says the full file is the complete copy. */
    internal fun binderSafeText8213(text: String, max: Int = 60_000): String =
        if (text.length <= max) text else text.take(max) + "\n\n… truncated to $max of ${text.length} chars for Android's share limit; use the attached/exported file for the full report."

    /**
     * V5.0.8381 — owner: "I need it to send or copy the full report when asked; if I click Share I can't send it to the
     * clipboard or the notepad." The clipboard held a budgeted summary and the share sheet's Copy / Notes took the 60k
     * EXTRA_TEXT, so the complete report could reach neither. Now the dialog offers all three complete routes:
     *  - Copy full: the WHOLE report onto the clipboard, in plain parts of [COPY_PART_CHARS_8381] when it is larger (each
     *    part is complete lines, numbered "part i/n"; paste one, tap Next). Parts keep every clip one binder transaction.
     *  - Save: the whole report as a .txt in Downloads/AATE (MediaStore; any notepad / file manager opens it).
     *  - Share: the file (plus the binder-safe text for text-only receivers), as before.
     */
    fun show(activity: Activity, fullText: String, fullFile: File?, copyChars: Int, fullChars: Int, subject: String = "AATE report") {
        if (activity.isFinishing || activity.isDestroyed) return
        val trimmed = copyChars < fullChars
        val parts = copyParts8381(fullText)
        AlertDialog.Builder(activity)
            .setTitle(if (trimmed) "Full report available" else "Report copied")
            .setMessage(
                if (trimmed) "Clipboard has the readable summary ($copyChars of $fullChars chars).\n\n" +
                    "Copy full: the complete report to the clipboard" + (if (parts.size > 1) " in ${parts.size} parts" else "") + ".\n" +
                    "Save: a .txt in Downloads/AATE for any notepad.\nShare: send the complete file."
                else "Copied $copyChars chars (complete). Save it to Downloads/AATE or share it?"
            )
            .setPositiveButton("Share") { _, _ -> share(activity, fullText, fullFile, subject) }
            .setNeutralButton(if (trimmed) "Copy full" else "Done") { _, _ -> if (trimmed) copyPart8381(activity, parts, 0, fullText, fullFile, subject) }
            .setNegativeButton("Save") { _, _ -> saveToDownloads8381(activity, fullText, fullFile, subject); offerHeapMap8866(activity) }
            .show()
    }

    /** Pure (V5.0.8866): what the memory-map offer says, with the heap as it stands. */
    internal fun heapMapOffer8866(usedMb: Long, maxMb: Long): String =
        "The report is being saved to Downloads/AATE.\n\n" +
            "A memory map shows what is filling the heap (now $usedMb of $maxMb MB). Taking it stops the bot for up to a minute " +
            "while the heap is written, then reads it in the background for a minute or two. Do it when no trade is in flight, " +
            "and leave the screen alone until it says done.\n\n" +
            "Only class names and sizes are kept. The raw dump never leaves the app and is deleted. The map is saved to " +
            "Downloads/AATE and printed in the next report."

    /**
     * V5.0.8866 — after Save, the owner may also take a memory map (HeapMap8866). Never taken without this tap: the dump
     * stops every thread of the app while it is written.
     */
    private fun offerHeapMap8866(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || com.lifecyclebot.engine.HeapMap8866.isRunning8866()) return
        val rt = Runtime.getRuntime()
        val app = activity.applicationContext
        try {
            AlertDialog.Builder(activity)
                .setTitle("Memory map (optional)")
                .setMessage(heapMapOffer8866((rt.totalMemory() - rt.freeMemory()) shr 20, rt.maxMemory() shr 20))
                .setPositiveButton("Take memory map") { _, _ ->
                    Toast.makeText(app, "Taking the memory map. The app will stop responding for a moment.", Toast.LENGTH_LONG).show()
                    com.lifecyclebot.engine.HeapMap8866.start8866(app, "owner", save = { file, name -> saveDataset(app, file, name) }) { line ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { Toast.makeText(app, "Memory map: $line", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
                        }
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        } catch (_: Throwable) {}
    }

    /** One clipboard part (chars). Each is far under the binder limit (UTF-16: ~200 KB) and pastes into chat / notes apps. */
    internal const val COPY_PART_CHARS_8381 = 95_000

    /** Pure (V5.0.8381): [text] in parts of at most [max] chars, split at line ends, each headed "part i/n". One part = the text. */
    internal fun copyParts8381(text: String, max: Int = COPY_PART_CHARS_8381): List<String> {
        if (text.length <= max) return listOf(text)
        val body = max - 64
        val chunks = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(text.length, i + body)
            if (end < text.length) { val nl = text.lastIndexOf('\n', end - 1); if (nl > i) end = nl + 1 }
            chunks.add(text.substring(i, end)); i = end
        }
        return chunks.mapIndexed { k, c -> "[AATE report part ${k + 1}/${chunks.size}]\n" + c }
    }

    private fun copyPart8381(activity: Activity, parts: List<String>, index: Int, fullText: String, fullFile: File?, subject: String) {
        if (activity.isFinishing || activity.isDestroyed || index !in parts.indices) return
        val ok = try {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("AATE report ${index + 1}/${parts.size}", parts[index])); true
        } catch (_: Throwable) { false }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (ok) "REPORT_FULL_COPY_PART_OK_8381" else "REPORT_FULL_COPY_PART_FAIL_8381") } catch (_: Throwable) {}
        if (!ok) { Toast.makeText(activity, "Clipboard refused part ${index + 1}; use Save or Share", Toast.LENGTH_LONG).show(); return }
        if (parts.size == 1) { Toast.makeText(activity, "Complete report copied (${parts[0].length} chars)", Toast.LENGTH_LONG).show(); return }
        val last = index == parts.size - 1
        AlertDialog.Builder(activity)
            .setTitle("Part ${index + 1} of ${parts.size} copied")
            .setMessage(if (last) "All ${parts.size} parts copied. Paste this last one." else "Paste part ${index + 1} now, then tap Next for part ${index + 2}.")
            .setPositiveButton(if (last) "Done" else "Next") { _, _ -> if (!last) copyPart8381(activity, parts, index + 1, fullText, fullFile, subject) }
            .setNeutralButton("Save") { _, _ -> saveToDownloads8381(activity, fullText, fullFile, subject) }
            .setCancelable(false)
            .show()
    }

    /** V5.0.8381 — the complete report as a .txt in Downloads/AATE (background IO; a toast says where, or why not). */
    private fun saveToDownloads8381(activity: Activity, fullText: String, fullFile: File?, subject: String) {
        val app = activity.applicationContext
        val name = (fullFile?.name ?: "aate_report_${System.currentTimeMillis()}.txt").let { if (it.endsWith(".txt")) it else "$it.txt" }
        Thread({
            val where = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AATE")
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads entry")
                    app.contentResolver.openOutputStream(uri)?.use { it.write(fullText.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
                    "Downloads/AATE/$name"
                } else {
                    val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AATE").apply { mkdirs() }
                    File(dir, name).apply { writeText(fullText) }.absolutePath
                }
            } catch (t: Throwable) { "FAILED: ${t.javaClass.simpleName}" }
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (where.startsWith("FAILED")) "REPORT_SAVE_DOWNLOADS_FAIL_8381" else "REPORT_SAVE_DOWNLOADS_OK_8381") } catch (_: Throwable) {}
            // V5.0.8537 — the edge model's labelled dataset rides along, for the offline replay (ci/edge_replay_8537.py).
            val dataset8537 = if (where.startsWith("FAILED")) null else saveEdgeDataset8537(app)
            // V5.0.8877 — every decision's 60-minute read (plan W8), beside the edge dataset
            val research8877 = if (where.startsWith("FAILED")) null else com.lifecyclebot.engine.truth.ResearchRows8877.file8877()?.takeIf { it.exists() }?.let {
                saveDataset(app, it, "aate_edge_research_8877_${System.currentTimeMillis()}.jsonl")
            }
            // V5.0.8887 — the hourly authority table (plan W2), beside the rows it grades
            val table8887 = if (where.startsWith("FAILED")) null else com.lifecyclebot.engine.truth.AuthorityTable8887.file8887()?.takeIf { it.exists() }?.let {
                saveDataset(app, it, "aate_authority_8887_${System.currentTimeMillis()}.json")
            }
            val hour = if (where.startsWith("FAILED")) "" else try {
                com.lifecyclebot.engine.truth.LaunchHour8628.export(app.cacheDir, System.currentTimeMillis())?.let { file ->
                    try { saveDataset(app, file, "aate_launch_hour_${System.currentTimeMillis()}.jsonl") ?: "hour export failed" }
                    finally { file.delete() }
                } ?: "no launch observations yet"
            } catch (_: Exception) { "hour export failed" }
            // V5.0.8852: a SMALL, selected READY-only six-hour learning cohort;
            // keep it separate from the market-wide first-hour birth export.
            val runnerStudy = if (where.startsWith("FAILED")) "" else try {
                com.lifecyclebot.engine.truth.RunnerStudy8852.export(app.cacheDir, System.currentTimeMillis())?.let { file ->
                    try { saveDataset(app, file, "aate_runner_study_8852_${System.currentTimeMillis()}.jsonl") ?: "runner-study export failed" }
                    finally { file.delete() }
                } ?: "no runner-study observations yet"
            } catch (_: Exception) { "runner-study export failed" }
            activity.runOnUiThread {
                Toast.makeText(app, if (where.startsWith("FAILED")) "Save failed ($where); use Share" else "Saved complete report (${fullText.length} chars) to $where" + (dataset8537?.let { " + $it" } ?: "") + (research8877?.let { " + $it" } ?: "") + (table8887?.let { " + $it" } ?: "") + " · $hour · $runnerStudy", Toast.LENGTH_LONG).show()
            }
        }, "AATE-ReportSave-8381").start()
    }

    /** V5.0.8537 — copy EdgeModel8537's dataset to Downloads/AATE; the saved name, or null when there is none / it failed. */
    private fun saveEdgeDataset8537(app: android.content.Context): String? {
        val src = com.lifecyclebot.engine.truth.EdgeModel8537.datasetFile() ?: return null
        val name = "aate_edge_dataset_8537_${System.currentTimeMillis()}.jsonl"
        return saveDataset(app, src, name)
    }

    internal fun saveDataset(app: android.content.Context, src: File, name: String): String? {
      return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AATE")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            app.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return null
        } else {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AATE").apply { mkdirs() }
            src.copyTo(File(dir, name), overwrite = true)
        }
        name
      } catch (_: Throwable) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EDGE_DATASET_SAVE_FAIL_8537") } catch (_: Throwable) {}; null }
    }

    fun share(activity: Activity, fullText: String, fullFile: File?, subject: String) {
        val fileIntent = if (fullFile != null) try { PipelineReportFileExporter6401.shareIntent(activity, fullFile, subject = subject) } catch (_: Throwable) { null } else null
        val textIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, binderSafeText8213(fullText))
        }
        try {
            val chooser = Intent.createChooser(fileIntent ?: textIntent, "Share AATE report")
            if (fileIntent != null) chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(chooser)
        } catch (_: Throwable) {
            try { activity.startActivity(Intent.createChooser(textIntent, "Share AATE report")) }
            catch (_: Throwable) { Toast.makeText(activity, "Share unavailable on this device", Toast.LENGTH_LONG).show() }
        }
    }
}
