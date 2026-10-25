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
            .setNegativeButton("Save") { _, _ -> saveToDownloads8381(activity, fullText, fullFile, subject) }
            .show()
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
            activity.runOnUiThread {
                Toast.makeText(app, if (where.startsWith("FAILED")) "Save failed ($where); use Share" else "Saved complete report (${fullText.length} chars) to $where", Toast.LENGTH_LONG).show()
            }
        }, "AATE-ReportSave-8381").start()
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
