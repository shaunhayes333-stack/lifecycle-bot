package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PersistentLearning
 *
 * Modernized persistent-learning store for Android.
 *
 * IMPORTANT:
 * - Uses app-specific external storage (scoped-storage compatible).
 * - Safe for normal persistence across app updates.
 * - NOT guaranteed to survive uninstall by itself.
 * - For uninstall-safe backup, use explicit export/import outside this module.
 *
 * This version intentionally avoids auto-exporting secrets like private keys.
 */
object PersistentLearning {

    private const val TAG = "PersistentLearning"
    private const val FOLDER_NAME = "AATE"
    private const val VERSION = 2

    @Volatile
    private var storageDir: File? = null

    // -------------------------------------------------------------------------
    // INIT / STORAGE
    // -------------------------------------------------------------------------

    fun init(context: Context): Boolean {
        return try {
            // Scoped-storage compatible app-specific external directory
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, FOLDER_NAME)

            if (!dir.exists() && !dir.mkdirs()) {
                ErrorLogger.warn(TAG, "Could not create storage dir: ${dir.absolutePath}")
                return false
            }

            storageDir = dir
            ensureReadme(dir)

            ErrorLogger.info(TAG, "📁 PersistentLearning ready: ${dir.absolutePath}")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to init persistent storage: ${e.message}", e)
            false
        }
    }

    fun isAvailable(): Boolean {
        val dir = storageDir
        return dir != null && dir.exists() && dir.canRead() && dir.canWrite()
    }

    fun getStoragePath(): String = storageDir?.absolutePath ?: "Not initialized"

    fun getStorageSummary(): String {
        val dir = storageDir ?: return "Storage not initialized"
        return try {
            val files = dir.listFiles()?.filter { it.isFile && it.extension.equals("json", true) } ?: emptyList()
            val totalSizeBytes = files.sumOf { it.length() }
            "Path: ${dir.absolutePath}\nFiles: ${files.size} JSON files\nSize: ${totalSizeBytes / 1024} KB"
        } catch (e: Exception) {
            "Storage summary unavailable"
        }
    }

    fun clearAll(context: Context? = null): Boolean {
        return try {
            if (storageDir == null && context != null) init(context)
            val dir = storageDir ?: return false
            dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", true) }
                ?.forEach { runCatching { it.delete() } }

            ErrorLogger.info(TAG, "🗑️ Cleared all persistent learning data")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to clear data: ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // EDGE LEARNING
    // -------------------------------------------------------------------------

    fun saveEdgeLearning(
        paperBuyPctMin: Double,
        paperVolumeMin: Double,
        liveBuyPctMin: Double,
        liveVolumeMin: Double,
        totalTrades: Int,
        winningTrades: Int,
    ): Boolean {
        val json = JSONObject().apply {
            putMeta(this)
            put("paperBuyPctMin", paperBuyPctMin)
            put("paperVolumeMin", paperVolumeMin)
            put("liveBuyPctMin", liveBuyPctMin)
            put("liveVolumeMin", liveVolumeMin)
            put("totalTrades", totalTrades)
            put("winningTrades", winningTrades)
        }
        return writeJsonFile("edge_learning.json", json)
    }

    fun loadEdgeLearning(): Map<String, Any>? {
        return readJsonFile("edge_learning.json")?.let { json ->
            mapOf(
                "paperBuyPctMin" to json.optDouble("paperBuyPctMin", 40.0),
                "paperVolumeMin" to json.optDouble("paperVolumeMin", 10.0),
                "liveBuyPctMin" to json.optDouble("liveBuyPctMin", 50.0),
                "liveVolumeMin" to json.optDouble("liveVolumeMin", 15.0),
                "totalTrades" to json.optInt("totalTrades", 0),
                "winningTrades" to json.optInt("winningTrades", 0),
            )
        }
    }

    // -------------------------------------------------------------------------
    // ENTRY INTELLIGENCE
    // -------------------------------------------------------------------------

    fun saveEntryIntelligence(
        buyPressureWeight: Double,
        volumeWeight: Double,
        momentumWeight: Double,
        rsiWeight: Double,
        optimalBuyPressureMin: Double,
        optimalBuyPressureMax: Double,
        optimalMomentumMin: Double,
        totalTrades: Int,
        winningTrades: Int,
        hourlyWinRates: Map<Int, Double>,
        hourlyTradeCount: Map<Int, Int>,
        patternWinRates: Map<String, Double>,
        patternTradeCount: Map<String, Int>,
    ): Boolean {
        val hourlyJson = JSONObject().apply {
            hourlyWinRates.forEach { (hour, rate) ->
                put("rate_$hour", rate)
                put("count_$hour", hourlyTradeCount[hour] ?: 0)
            }
        }

        val patternsJson = JSONObject().apply {
            patternWinRates.forEach { (pattern, rate) ->
                put("rate_$pattern", rate)
                put("count_$pattern", patternTradeCount[pattern] ?: 0)
            }
        }

        val json = JSONObject().apply {
            putMeta(this)
            put("buyPressureWeight", buyPressureWeight)
            put("volumeWeight", volumeWeight)
            put("momentumWeight", momentumWeight)
            put("rsiWeight", rsiWeight)
            put("optimalBuyPressureMin", optimalBuyPressureMin)
            put("optimalBuyPressureMax", optimalBuyPressureMax)
            put("optimalMomentumMin", optimalMomentumMin)
            put("totalTrades", totalTrades)
            put("winningTrades", winningTrades)
            put("hourly", hourlyJson)
            put("patterns", patternsJson)
        }

        return writeJsonFile("entry_intelligence.json", json)
    }

    fun loadEntryIntelligence(): Map<String, Any>? {
        val json = readJsonFile("entry_intelligence.json") ?: return null

        val hourlyWinRates = mutableMapOf<Int, Double>()
        val hourlyTradeCount = mutableMapOf<Int, Int>()
        json.optJSONObject("hourly")?.let { hourly ->
            for (hour in 0..23) {
                val rate = hourly.optDouble("rate_$hour", -1.0)
                val count = hourly.optInt("count_$hour", 0)
                if (rate >= 0.0 && count > 0) {
                    hourlyWinRates[hour] = rate
                    hourlyTradeCount[hour] = count
                }
            }
        }

        val patternWinRates = mutableMapOf<String, Double>()
        val patternTradeCount = mutableMapOf<String, Int>()
        json.optJSONObject("patterns")?.let { patterns ->
            val keys = patterns.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("rate_")) {
                    val pattern = key.removePrefix("rate_")
                    val rate = patterns.optDouble(key, -1.0)
                    val count = patterns.optInt("count_$pattern", 0)
                    if (rate >= 0.0 && count > 0) {
                        patternWinRates[pattern] = rate
                        patternTradeCount[pattern] = count
                    }
                }
            }
        }

        return mapOf(
            "buyPressureWeight" to json.optDouble("buyPressureWeight", 1.0),
            "volumeWeight" to json.optDouble("volumeWeight", 1.0),
            "momentumWeight" to json.optDouble("momentumWeight", 1.0),
            "rsiWeight" to json.optDouble("rsiWeight", 1.0),
            "optimalBuyPressureMin" to json.optDouble("optimalBuyPressureMin", 50.0),
            "optimalBuyPressureMax" to json.optDouble("optimalBuyPressureMax", 75.0),
            "optimalMomentumMin" to json.optDouble("optimalMomentumMin", 5.0),
            "totalTrades" to json.optInt("totalTrades", 0),
            "winningTrades" to json.optInt("winningTrades", 0),
            "hourlyWinRates" to hourlyWinRates,
            "hourlyTradeCount" to hourlyTradeCount,
            "patternWinRates" to patternWinRates,
            "patternTradeCount" to patternTradeCount,
        )
    }

    // -------------------------------------------------------------------------
    // EXIT INTELLIGENCE
    // -------------------------------------------------------------------------

    fun saveExitIntelligence(
        baseStopLoss: Double,
        baseTakeProfit: Double,
        greedFactor: Double,
        trailingStopDistance: Double,
        trailingActivationProfit: Double,
        maxHoldMinutes: Int,
        optimalHoldMinutes: Int,
        partialExit25Threshold: Double,
        partialExit50Threshold: Double,
        avgWinningHoldTime: Double,
        avgLosingHoldTime: Double,
        avgWinningPnl: Double,
        avgLosingPnl: Double,
        totalExits: Int,
        profitableExits: Int,
    ): Boolean {
        val json = JSONObject().apply {
            putMeta(this)
            put("baseStopLoss", baseStopLoss)
            put("baseTakeProfit", baseTakeProfit)
            put("greedFactor", greedFactor)
            put("trailingStopDistance", trailingStopDistance)
            put("trailingActivationProfit", trailingActivationProfit)
            put("maxHoldMinutes", maxHoldMinutes)
            put("optimalHoldMinutes", optimalHoldMinutes)
            put("partialExit25Threshold", partialExit25Threshold)
            put("partialExit50Threshold", partialExit50Threshold)
            put("avgWinningHoldTime", avgWinningHoldTime)
            put("avgLosingHoldTime", avgLosingHoldTime)
            put("avgWinningPnl", avgWinningPnl)
            put("avgLosingPnl", avgLosingPnl)
            put("totalExits", totalExits)
            put("profitableExits", profitableExits)
        }
        return writeJsonFile("exit_intelligence.json", json)
    }

    fun loadExitIntelligence(): Map<String, Any>? {
        return readJsonFile("exit_intelligence.json")?.let { json ->
            mapOf(
                "baseStopLoss" to json.optDouble("baseStopLoss", -8.0),
                "baseTakeProfit" to json.optDouble("baseTakeProfit", 15.0),
                "greedFactor" to json.optDouble("greedFactor", 1.0),
                "trailingStopDistance" to json.optDouble("trailingStopDistance", 5.0),
                "trailingActivationProfit" to json.optDouble("trailingActivationProfit", 8.0),
                "maxHoldMinutes" to json.optInt("maxHoldMinutes", 30),
                "optimalHoldMinutes" to json.optInt("optimalHoldMinutes", 10),
                "partialExit25Threshold" to json.optDouble("partialExit25Threshold", 10.0),
                "partialExit50Threshold" to json.optDouble("partialExit50Threshold", 20.0),
                "avgWinningHoldTime" to json.optDouble("avgWinningHoldTime", 8.0),
                "avgLosingHoldTime" to json.optDouble("avgLosingHoldTime", 15.0),
                "avgWinningPnl" to json.optDouble("avgWinningPnl", 12.0),
                "avgLosingPnl" to json.optDouble("avgLosingPnl", -10.0),
                "totalExits" to json.optInt("totalExits", 0),
                "profitableExits" to json.optInt("profitableExits", 0),
            )
        }
    }

    // -------------------------------------------------------------------------
    // TOKEN WIN MEMORY
    // -------------------------------------------------------------------------

    fun saveTokenWinMemory(winnersJson: String, patternsJson: String): Boolean {
        val json = JSONObject().apply {
            putMeta(this)
            put("winners", winnersJson)
            put("patterns", patternsJson)
        }
        return writeJsonFile("token_win_memory.json", json)
    }

    fun loadTokenWinMemory(): Pair<String, String>? {
        val json = readJsonFile("token_win_memory.json") ?: return null
        return Pair(
            json.optString("winners", "[]"),
            json.optString("patterns", "{}"),
        )
    }

    // -------------------------------------------------------------------------
    // BACKUP / EXPORT
    // -------------------------------------------------------------------------

    /**
     * Export all JSON learning files to a single backup file inside this module's directory.
     * Safe and simple. Caller can then share/copy that file wherever needed.
     *
     * This intentionally excludes secrets / API keys / wallet private keys.
     */
    fun exportBackup(): File? {
        val dir = storageDir ?: return null
        return try {
            val timestamp = timestamp()
            val backupFile = File(dir, "backup_$timestamp.json")

            val payload = JSONObject().apply {
                put("version", VERSION)
                put("exportTime", System.currentTimeMillis())

                dir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("json", true) && !it.name.startsWith("backup_") }
                    ?.forEach { file ->
                        runCatching {
                            put(file.nameWithoutExtension, JSONObject(file.readText()))
                        }
                    }
            }

            atomicWrite(backupFile, payload.toString(2))
            ErrorLogger.info(TAG, "📦 Exported backup: ${backupFile.absolutePath}")
            backupFile
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to export backup: ${e.message}", e)
            null
        }
    }

    fun exportFullBackup(context: Context): File? {
        // Kept for compatibility with existing callers.
        // Same behavior as exportBackup(), but ensures init first.
        if (storageDir == null) init(context)
        return exportBackup()
    }

    fun importFullBackup(context: Context, backupFile: File): Boolean {
        if (storageDir == null) init(context)

        return try {
            if (!backupFile.exists() || !backupFile.isFile) {
                ErrorLogger.warn(TAG, "Backup file not found: ${backupFile.absolutePath}")
                return false
            }

            val root = JSONObject(backupFile.readText())
            val dir = storageDir ?: return false
            var restored = 0

            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in setOf("version", "exportTime")) continue

                val obj = root.optJSONObject(key) ?: continue
                val outFile = File(dir, "$key.json")
                atomicWrite(outFile, obj.toString(2))
                restored++
            }

            ErrorLogger.info(TAG, "✅ BACKUP RESTORED: $restored components from ${backupFile.name}")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to import backup: ${e.message}", e)
            false
        }
    }

    fun findLatestBackup(): File? {
        val dir = storageDir ?: return null
        return try {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("backup_") && it.extension.equals("json", true) }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to find backup: ${e.message}", e)
            null
        }
    }

    fun listBackups(): List<File> {
        val dir = storageDir ?: return emptyList()
        return try {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("backup_") && it.extension.equals("json", true) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // INTERNAL HELPERS
    // -------------------------------------------------------------------------

    private fun ensureReadme(dir: File) {
        val readme = File(dir, "README.txt")
        if (readme.exists()) return

        runCatching {
            readme.writeText(
                """
                AATE Persistent Learning Data
                =====================================

                This folder contains learned AI parameters used by the bot.

                Notes:
                - App-specific external storage
                - Compatible with modern Android scoped storage
                - Backups are explicit and separate
                - Secrets/private keys are intentionally NOT exported here

                Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}
                """.trimIndent()
            )
        }
    }

    private fun putMeta(json: JSONObject) {
        json.put("version", VERSION)
        json.put("timestamp", System.currentTimeMillis())
    }

    private fun readJsonFile(fileName: String): JSONObject? {
        val dir = storageDir ?: return null
        val file = File(dir, fileName)
        if (!file.exists() || !file.isFile) return null

        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to read $fileName: ${e.message}", e)
            null
        }
    }

    private fun writeJsonFile(fileName: String, json: JSONObject): Boolean {
        val dir = storageDir ?: return false
        return try {
            val file = File(dir, fileName)
            atomicWrite(file, json.toString(2))
            ErrorLogger.debug(TAG, "💾 Saved $fileName")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to save $fileName: ${e.message}", e)
            false
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }

        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Could not replace ${target.name}")
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("Could not finalize ${target.name}")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}