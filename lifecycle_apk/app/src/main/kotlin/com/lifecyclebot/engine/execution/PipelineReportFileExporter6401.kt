package com.lifecyclebot.engine.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ReportingHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V5.0.6401 ANR-KILLER — file-based pipeline report export.
 *
 * Root cause of the operator's truncated 20k-of-60k report: the copy
 * path routes through the clipboard, which caps at
 * [ReportingHub.CLIPBOARD_SAFE_MAX_CHARS] (20_000) to avoid a
 * ClipboardService Binder-IPC ANR on multi-megabyte blobs.
 *
 * Fix: write the full unbounded report to the app cache dir on a
 * background thread, then share via `ACTION_SEND + EXTRA_STREAM` with
 * a FileProvider URI. The clipboard is never touched for the full
 * dump, so no truncation, no Binder freeze, no lost lines.
 *
 * The build+write happens on the caller's background thread (already
 * off-main via PipelineHealthActivity's `Thread(…) { … }` and
 * ErrorLogActivity's `ReportingHub.buildTextAsync` callback). This
 * helper only performs synchronous file IO — the caller must invoke
 * it from a background context.
 *
 * Report cache dir: `<cacheDir>/pipeline_reports/` (managed under the
 * existing `<cache-path name="csv_exports" …>` provider entry, which
 * exposes the entire cache root).
 */
object PipelineReportFileExporter6401 {

    private const val TAG = "PipelineReportFileExporter6401"
    private const val SUBDIR = "pipeline_reports"
    /** Cap the on-disk retention so we don't leak the cache. Newest wins. */
    private const val MAX_RETAINED_FILES = 5

    private fun authority(ctx: Context): String = "${ctx.packageName}.fileprovider"

    private fun ensureDir(ctx: Context): File =
        File(ctx.cacheDir, SUBDIR).apply { if (!exists()) mkdirs() }

    /**
     * Persist [text] to a timestamped file under [dir]. Returns the File
     * on success or null if writing failed. MUST be called from a
     * background thread — this method does synchronous disk IO.
     *
     * Package-visible so unit tests can drive it without an Android
     * [Context]; production callers use the public [writeReportSync]
     * overload that resolves the cache dir from Context.
     */
    internal fun writeReportSyncToDir(dir: File, text: String, prefix: String): File? {
        return try {
            if (!dir.exists()) dir.mkdirs()
            pruneOldFilesSync(dir)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val out = File(dir, "${prefix}_$stamp.txt")
            out.writeText(text)
            try {
                PipelineHealthCollector.labelInc("PIPELINE_REPORT_FILE_WRITTEN_6401")
                ForensicLogger.lifecycle(
                    "PIPELINE_REPORT_FILE_WRITTEN_6401",
                    "path=${out.absolutePath} bytes=${out.length()} chars=${text.length}",
                )
            } catch (_: Throwable) {}
            out
        } catch (t: Throwable) {
            try {
                PipelineHealthCollector.labelInc("PIPELINE_REPORT_FILE_WRITE_FAILED_6401")
                ErrorLogger.warn(TAG, "writeReportSyncToDir failed: ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Persist [text] to a timestamped file under `<cacheDir>/pipeline_reports/`.
     * Returns the File on success or null if writing failed. MUST be called
     * from a background thread — this method does synchronous disk IO.
     */
    fun writeReportSync(ctx: Context, text: String, prefix: String = "aate_report"): File? =
        writeReportSyncToDir(ensureDir(ctx), text, prefix)

    /**
     * Build a share intent that carries the file as a URI AND the
     * report text in EXTRA_TEXT for receivers that only read
     * EXTRA_TEXT (SMS, notes, most chat apps).
     *
     * V5.0.6464 fix — the prior implementation put a one-line
     * description ("AATE pipeline report attached (N bytes).") in
     * EXTRA_TEXT. Receivers that prefer EXTRA_TEXT over EXTRA_STREAM
     * (SMS, Signal, WhatsApp chat body, Notes) showed only that
     * single line and dropped the actual report. Now:
     *
     *   - EXTRA_STREAM  = file URI (unchanged; file-capable receivers
     *                     get the full unbounded report)
     *   - EXTRA_TEXT    = the ACTUAL report text, bounded to
     *                     BINDER_SAFE_MAX_CHARS to keep the Intent
     *                     under Android's ~1MB transaction cap
     *   - EXTRA_SUBJECT = human-readable subject
     *
     * The Binder-safe cap is 500_000 chars (≈500 KB) which is well
     * under the ~1MB Binder txn ceiling but large enough to carry
     * every pipeline report we've observed in this run (60–200 KB).
     */
    private const val BINDER_SAFE_MAX_CHARS = 500_000

    fun shareIntent(ctx: Context, file: File, subject: String = "AATE Pipeline Report"): Intent? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
            val body = try {
                if (file.length() <= BINDER_SAFE_MAX_CHARS.toLong()) file.readText()
                else file.readText().take(BINDER_SAFE_MAX_CHARS) +
                    "\n\n… (truncated to ${BINDER_SAFE_MAX_CHARS} chars for Binder safety — see attached file for the full ${file.length()}-byte report)"
            } catch (_: Throwable) {
                "AATE pipeline report attached (${file.length()} bytes). Full report is in the attached file."
            }
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$subject — ${file.name}")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (t: Throwable) {
            try {
                PipelineHealthCollector.labelInc("PIPELINE_REPORT_SHARE_INTENT_FAILED_6401")
                ErrorLogger.warn(TAG, "shareIntent failed: ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {}
            null
        }
    }

    private fun pruneOldFilesSync(dir: File) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
            if (files.size < MAX_RETAINED_FILES) return
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_RETAINED_FILES - 1)
                .forEach { runCatching { it.delete() } }
        } catch (_: Throwable) { /* pruning is best-effort */ }
    }
}
