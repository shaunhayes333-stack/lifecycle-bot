package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.execution.ForensicReportExporter
import com.lifecyclebot.engine.execution.PositionWalletReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.3700 — ReportingHub.
 *
 * Single reporting coordinator for heavy operator reports. This intentionally
 * excludes the Trade Journal accounting/export path. Existing collectors remain
 * the data sources; this object owns orchestration, background execution,
 * caching, and UI-safe callbacks so four screens do not each spin their own
 * reporting workload against the bot loop.
 */
object ReportingHub {
    enum class Kind { UNIFIED_HEALTH, PIPELINE_HEALTH, ERROR_LOG, FORENSIC_SUMMARY }

    data class TextReport(
        val kind: Kind,
        val title: String,
        val generatedAtMs: Long,
        val text: String,
    )

    private data class CacheEntry(val atMs: Long, val report: TextReport)

    private const val CACHE_TTL_MS = 2_500L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildMutex = Mutex()
    private val textCache = ConcurrentHashMap<Kind, CacheEntry>()

    fun buildTextAsync(
        kind: Kind,
        forceFresh: Boolean = false,
        callback: (report: TextReport?, error: Throwable?) -> Unit,
    ) {
        scope.launch {
            val result = try {
                buildText(kind, forceFresh) to null
            } catch (t: Throwable) {
                null to t
            }
            withContext(Dispatchers.Main) { callback(result.first, result.second) }
        }
    }

    suspend fun buildText(kind: Kind, forceFresh: Boolean = false): TextReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceFresh) {
            textCache[kind]?.let { cached ->
                if (now - cached.atMs <= CACHE_TTL_MS) return@withContext cached.report
            }
        }
        buildMutex.withLock {
            val now2 = System.currentTimeMillis()
            if (!forceFresh) {
                textCache[kind]?.let { cached ->
                    if (now2 - cached.atMs <= CACHE_TTL_MS) return@withLock cached.report
                }
            }
            val text = buildTextSync(kind)
            val report = TextReport(kind, titleFor(kind), now2, text)
            textCache[kind] = CacheEntry(now2, report)
            report
        }
    }

    fun exportForensicFileAsync(
        context: Context,
        callback: (file: File?, error: Throwable?) -> Unit,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val result = try {
                exportForensicFile(appContext) to null
            } catch (t: Throwable) {
                null to t
            }
            withContext(Dispatchers.Main) { callback(result.first, result.second) }
        }
    }

    suspend fun exportForensicFile(context: Context): File = withContext(Dispatchers.IO) {
        buildMutex.withLock {
            try { PositionWalletReconciler.forceTick() } catch (_: Throwable) {}
            ForensicReportExporter.dumpToFile(context.applicationContext)
        }
    }

    fun invalidate() {
        textCache.clear()
    }

    private fun titleFor(kind: Kind): String = when (kind) {
        Kind.UNIFIED_HEALTH -> "AATE Unified Operational Report"
        Kind.PIPELINE_HEALTH -> "AATE Pipeline Health"
        Kind.ERROR_LOG -> "AATE Error Logs"
        Kind.FORENSIC_SUMMARY -> "AATE Forensic Summary"
    }

    private fun buildTextSync(kind: Kind): String = when (kind) {
        Kind.PIPELINE_HEALTH -> safe("pipeline") { PipelineHealthCollector.dumpText() }
        Kind.ERROR_LOG -> safe("error_log") { ErrorLogger.exportToText() }
        Kind.FORENSIC_SUMMARY -> buildForensicSummary()
        Kind.UNIFIED_HEALTH -> buildUnifiedHealth()
    }

    private fun buildUnifiedHealth(): String = buildString(48 * 1024) {
        appendHeader("AATE UNIFIED OPERATIONAL REPORT")
        appendLine("Generated: ${stamp()}")
        appendLine("Scope: runtime / pipeline / errors / forensic summary")
        appendLine("Trade Journal: EXCLUDED by design")
        appendLine()
        appendSection("PIPELINE HEALTH")
        appendLine(safe("pipeline") { PipelineHealthCollector.dumpText() })
        appendLine()
        appendSection("FORENSIC SUMMARY")
        appendLine(buildForensicSummary())
        appendLine()
        appendSection("ERROR LOGS")
        appendLine(safe("error_log") { ErrorLogger.exportToText(limit = 120) })
    }

    private fun buildForensicSummary(): String = buildString(8 * 1024) {
        appendHeader("AATE FORENSIC SUMMARY")
        appendLine("Generated: ${stamp()}")
        appendLine(safe("forensic_summary") { ForensicReportExporter.textSummary() })
        appendLine()
        appendLine("Runtime:")
        val runtime = safeSnapshot { BotRuntimeController.snapshot() }
        if (runtime != null) {
            appendLine("  generation=${runtime.runtimeGeneration}")
            appendLine("  state=${runtime.state.name}")
            appendLine("  active=${runtime.runtimeActive}")
            appendLine("  botLoop=${runtime.botLoopJobActive}")
            appendLine("  scanner=${runtime.scannerActive}")
            appendLine("  paper=${runtime.paperMode}")
            appendLine("  enabled=${runtime.enabledTraders}")
        } else {
            appendLine("  unavailable")
        }
        appendLine()
        appendLine("Doctor:")
        val doctor = try { RuntimeDoctor.tick() } catch (_: Throwable) { null }
        if (doctor != null) {
            appendLine("  diagnosis=${doctor.diagnosis.faultCode}")
            appendLine("  confidence=${String.format(Locale.US, "%.2f", doctor.diagnosis.confidence)}")
            appendLine("  faults=${doctor.faults.size}")
            doctor.faults.take(12).forEach { f -> appendLine("  - ${f.code}/${f.severity}: ${f.detail.take(160)}") }
        } else {
            appendLine("  unavailable")
        }
    }

    private inline fun <T> safeSnapshot(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    private inline fun safe(label: String, block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "($label report unavailable: ${t.javaClass.simpleName}: ${t.message})"
    }

    private fun StringBuilder.appendHeader(title: String) {
        appendLine("═══════════════════════════════════════════")
        appendLine(title)
        appendLine("═══════════════════════════════════════════")
    }

    private fun StringBuilder.appendSection(title: String) {
        appendLine("===== $title =====")
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
