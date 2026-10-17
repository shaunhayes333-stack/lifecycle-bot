package com.lifecyclebot.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lifecyclebot.engine.execution.PipelineReportFileExporter6401
import java.io.File

/** Small presentation-only chooser. Text/parts/files are prepared off Main by the existing report workers. */
object ReportCopyDialog8198 {
    fun show(activity: Activity, parts: List<String>, compactFile: File?, originalFile: File?, compactChars: Int, originalChars: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val choices = ArrayList<Pair<String, () -> Unit>>()
        fun share(file: File?, title: String) {
            if (file == null) return
            choices += title to {
                val intent = PipelineReportFileExporter6401.shareIntent(activity, file)
                if (intent != null) try { activity.startActivity(Intent.createChooser(intent, "Share AATE report")) }
                catch (_: Throwable) { Toast.makeText(activity, "Share unavailable; use numbered copy parts", Toast.LENGTH_LONG).show() }
            }
        }
        share(compactFile, "Share complete compact file ($compactChars chars)")
        share(originalFile, "Share complete original file ($originalChars chars)")
        parts.forEachIndexed { index, text ->
            choices += "Copy part ${index + 1}/${parts.size} (${text.length} chars)" to {
                Thread({
                    val ok = try {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AATE report part ${index + 1}/${parts.size}", text))
                        true
                    } catch (_: Throwable) { false }
                    Handler(Looper.getMainLooper()).post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(activity, if (ok) "Copied part ${index + 1}/${parts.size}. Paste in order." else "Copy failed; share the complete file instead.", Toast.LENGTH_LONG).show()
                            show(activity, parts, compactFile, originalFile, compactChars, originalChars)
                        }
                    }
                }, "ReportPartClipboard8198").start()
            }
        }
        AlertDialog.Builder(activity).setTitle("Complete report: ${parts.size} copy part(s)")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .setNegativeButton("Done", null).show()
    }
}
