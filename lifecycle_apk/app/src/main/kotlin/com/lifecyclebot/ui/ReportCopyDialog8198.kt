package com.lifecyclebot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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

    fun show(activity: Activity, fullText: String, fullFile: File?, copyChars: Int, fullChars: Int, subject: String = "AATE report") {
        if (activity.isFinishing || activity.isDestroyed) return
        val trimmed = copyChars < fullChars
        AlertDialog.Builder(activity)
            .setTitle(if (trimmed) "Full report available" else "Report copied")
            .setMessage(
                if (trimmed) "Clipboard has the readable summary ($copyChars of $fullChars chars). Share the complete file?"
                else "Copied $copyChars chars. Share it?"
            )
            .setPositiveButton("Share") { _, _ -> share(activity, fullText, fullFile, subject) }
            .setNegativeButton("Done", null)
            .show()
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
