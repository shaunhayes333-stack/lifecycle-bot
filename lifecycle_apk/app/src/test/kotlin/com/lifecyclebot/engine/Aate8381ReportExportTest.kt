package com.lifecyclebot.engine

import com.lifecyclebot.ui.ReportCopyDialog8198
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** V5.0.8381 — the complete report reaches the clipboard (in parts), Downloads, or a share; the copy is written on Main. */
class Aate8381ReportExportTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")

    @Test fun fullReportSplitsIntoCompleteLinePartsThatRejoinExactly() {
        val text = (0 until 9_000).joinToString("\n") { "  line $it " + "x".repeat(25) } + "\n"
        val parts = ReportCopyDialog8198.copyParts8381(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= ReportCopyDialog8198.COPY_PART_CHARS_8381 })
        val joined = parts.joinToString("") { it.substringAfter("]\n") }
        assertEquals(text, joined)
        assertTrue(parts[0].startsWith("[AATE report part 1/${parts.size}]"))
        assertTrue(parts.dropLast(1).all { it.endsWith("\n") })
        assertEquals(listOf("small"), ReportCopyDialog8198.copyParts8381("small"))
    }

    @Test fun dialogOffersCopyFullSaveAndShareAndUnifiedCopyIsOnMain() {
        val d = File(root, "ui/ReportCopyDialog8198.kt").readText()
        assertTrue(d.contains("copyPart8381(activity, parts, 0, fullText, fullFile, subject)"))
        assertTrue(d.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"))
        assertTrue(d.contains("share(activity, fullText, fullFile, subject)"))
        val e = File(root, "ui/ErrorLogActivity.kt").readText()
        val post = e.indexOf("Handler(Looper.getMainLooper()).post {\n                    if (isFinishing || isDestroyed) return@post\n                    // V5.0.8381")
        assertTrue(post > 0)
        assertTrue(e.indexOf("ClipData.newPlainText(\"AATE Unified Report\", exportText)") > post)
    }
}
