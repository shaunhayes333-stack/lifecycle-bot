package com.lifecyclebot.engine

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Persists learned AI data to external storage so it survives app uninstall/reinstall.
 * 
 * Storage location: /sdcard/AATE/
 * Files:
 *   - edge_learning.json
 *   - entry_intelligence.json  
 *   - exit_intelligence.json
 *   - trading_memory.json
 *   - bot_brain.json
 */
object PersistentLearning {
    
    private const val TAG = "PersistentLearning"
    private const val FOLDER_NAME = "AATE"
    private const val VERSION = 1
    
    private var storageDir: File? = null
    
    /**
     * Initialize storage directory
     */
    fun init(context: Context): Boolean {
        return try {
            // Use external storage root - survives app uninstall
            val externalDir = Environment.getExternalStorageDirectory()
            storageDir = File(externalDir, FOLDER_NAME)
            
            if (storageDir?.exists() != true) {
                val created = storageDir?.mkdirs() ?: false
                if (created) {
                    ErrorLogger.info(TAG, "📁 Created persistent storage: ${storageDir?.absolutePath}")
                } else {
                    ErrorLogger.warn(TAG, "⚠️ Could not create storage dir, using app storage only")
                    return false
                }
            } else {
                ErrorLogger.info(TAG, "📁 Using existing storage: ${storageDir?.absolutePath}")
            }
            
            // Create a readme file
            val readme = File(storageDir, "README.txt")
            if (!readme.exists()) {
                readme.writeText("""
                    AATE Persistent Learning Data
                    =====================================
                    
                    This folder contains learned AI parameters that persist
                    across app reinstalls. Do not delete unless you want to
                    reset all learning.
                    
                    Files:
                    - edge_learning.json: Edge timing thresholds
                    - entry_intelligence.json: Entry timing patterns
                    - exit_intelligence.json: Exit strategy parameters
                    - trading_memory.json: Trade history patterns
                    
                    Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}
                """.trimIndent())
            }
            
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to init persistent storage: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if external storage is available and writable
     */
    fun isAvailable(): Boolean {
        val state = Environment.getExternalStorageState()
        return state == Environment.MEDIA_MOUNTED && storageDir?.canWrite() == true
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // EDGE LEARNING
    // ═══════════════════════════════════════════════════════════════════════
    
    fun saveEdgeLearning(
        paperBuyPctMin: Double,
        paperVolumeMin: Double,
        liveBuyPctMin: Double,
        liveVolumeMin: Double,
        totalTrades: Int,
        winningTrades: Int,
    ): Boolean {
        if (!isAvailable()) return false
        
        return try {
            val json = JSONObject().apply {
                put("version", VERSION)
                put("timestamp", System.currentTimeMillis())
                put("paperBuyPctMin", paperBuyPctMin)
                put("paperVolumeMin", paperVolumeMin)
                put("liveBuyPctMin", liveBuyPctMin)
                put("liveVolumeMin", liveVolumeMin)
                put("totalTrades", totalTrades)
                put("winningTrades", winningTrades)
            }
            
            File(storageDir, "edge_learning.json").writeText(json.toString(2))
            ErrorLogger.debug(TAG, "💾 Saved EdgeLearning to persistent storage")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to save EdgeLearning: ${e.message}", e)
            false
        }
    }
    
    fun loadEdgeLearning(): Map<String, Any>? {
        if (!isAvailable()) return null
        
        return try {
            val file = File(storageDir, "edge_learning.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            mapOf(
                "paperBuyPctMin" to json.optDouble("paperBuyPctMin", 40.0),
                "paperVolumeMin" to json.optDouble("paperVolumeMin", 10.0),
                "liveBuyPctMin" to json.optDouble("liveBuyPctMin", 50.0),
                "liveVolumeMin" to json.optDouble("liveVolumeMin", 15.0),
                "totalTrades" to json.optInt("totalTrades", 0),
                "winningTrades" to json.optInt("winningTrades", 0),
            ).also {
                ErrorLogger.info(TAG, "📂 Loaded EdgeLearning from persistent storage (${it["totalTrades"]} trades)")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load EdgeLearning: ${e.message}", e)
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // ENTRY INTELLIGENCE
    // ═══════════════════════════════════════════════════════════════════════
    
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
        if (!isAvailable()) return false
        
        return try {
            val hourlyJson = JSONObject()
            hourlyWinRates.forEach { (hour, rate) ->
                hourlyJson.put("rate_$hour", rate)
                hourlyJson.put("count_$hour", hourlyTradeCount[hour] ?: 0)
            }
            
            val patternJson = JSONObject()
            patternWinRates.forEach { (pattern, rate) ->
                patternJson.put("rate_$pattern", rate)
                patternJson.put("count_$pattern", patternTradeCount[pattern] ?: 0)
            }
            
            val json = JSONObject().apply {
                put("version", VERSION)
                put("timestamp", System.currentTimeMillis())
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
                put("patterns", patternJson)
            }
            
            File(storageDir, "entry_intelligence.json").writeText(json.toString(2))
            ErrorLogger.debug(TAG, "💾 Saved EntryIntelligence to persistent storage")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to save EntryIntelligence: ${e.message}", e)
            false
        }
    }
    
    fun loadEntryIntelligence(): Map<String, Any>? {
        if (!isAvailable()) return null
        
        return try {
            val file = File(storageDir, "entry_intelligence.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            
            val hourlyWinRates = mutableMapOf<Int, Double>()
            val hourlyTradeCount = mutableMapOf<Int, Int>()
            val hourlyJson = json.optJSONObject("hourly")
            if (hourlyJson != null) {
                for (hour in 0..23) {
                    val rate = hourlyJson.optDouble("rate_$hour", -1.0)
                    val count = hourlyJson.optInt("count_$hour", 0)
                    if (rate >= 0 && count > 0) {
                        hourlyWinRates[hour] = rate
                        hourlyTradeCount[hour] = count
                    }
                }
            }
            
            val patternWinRates = mutableMapOf<String, Double>()
            val patternTradeCount = mutableMapOf<String, Int>()
            val patternJson = json.optJSONObject("patterns")
            if (patternJson != null) {
                listOf("bullish_engulf", "hammer", "doji", "morning_star", "shooting_star", "none").forEach { pattern ->
                    val rate = patternJson.optDouble("rate_$pattern", -1.0)
                    val count = patternJson.optInt("count_$pattern", 0)
                    if (rate >= 0 && count > 0) {
                        patternWinRates[pattern] = rate
                        patternTradeCount[pattern] = count
                    }
                }
            }
            
            mapOf(
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
            ).also {
                ErrorLogger.info(TAG, "📂 Loaded EntryIntelligence from persistent storage (${it["totalTrades"]} trades)")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load EntryIntelligence: ${e.message}", e)
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // EXIT INTELLIGENCE
    // ═══════════════════════════════════════════════════════════════════════
    
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
        if (!isAvailable()) return false
        
        return try {
            val json = JSONObject().apply {
                put("version", VERSION)
                put("timestamp", System.currentTimeMillis())
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
            
            File(storageDir, "exit_intelligence.json").writeText(json.toString(2))
            ErrorLogger.debug(TAG, "💾 Saved ExitIntelligence to persistent storage")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to save ExitIntelligence: ${e.message}", e)
            false
        }
    }
    
    fun loadExitIntelligence(): Map<String, Any>? {
        if (!isAvailable()) return null
        
        return try {
            val file = File(storageDir, "exit_intelligence.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
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
            ).also {
                ErrorLogger.info(TAG, "📂 Loaded ExitIntelligence from persistent storage (${it["totalExits"]} exits)")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load ExitIntelligence: ${e.message}", e)
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // TOKEN WIN MEMORY
    // ═══════════════════════════════════════════════════════════════════════
    
    fun saveTokenWinMemory(winnersJson: String, patternsJson: String): Boolean {
        if (!isAvailable()) return false
        
        return try {
            val json = JSONObject().apply {
                put("version", VERSION)
                put("timestamp", System.currentTimeMillis())
                put("winners", winnersJson)
                put("patterns", patternsJson)
            }
            
            File(storageDir, "token_win_memory.json").writeText(json.toString(2))
            ErrorLogger.debug(TAG, "💾 Saved TokenWinMemory to persistent storage")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to save TokenWinMemory: ${e.message}", e)
            false
        }
    }
    
    fun loadTokenWinMemory(): Pair<String, String>? {
        if (!isAvailable()) return null
        
        return try {
            val file = File(storageDir, "token_win_memory.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            val winners = json.optString("winners", "[]")
            val patterns = json.optString("patterns", "{}")
            
            ErrorLogger.info(TAG, "📂 Loaded TokenWinMemory from persistent storage")
            Pair(winners, patterns)
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load TokenWinMemory: ${e.message}", e)
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Get storage path for UI display
     */
    fun getStoragePath(): String {
        return storageDir?.absolutePath ?: "Not initialized"
    }
    
    /**
     * Get summary of stored data
     */
    fun getStorageSummary(): String {
        if (!isAvailable()) return "Storage not available"
        
        val files = storageDir?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        val totalSize = files.sumOf { it.length() }
        
        return "Path: ${storageDir?.absolutePath}\n" +
               "Files: ${files.size} JSON files\n" +
               "Size: ${totalSize / 1024} KB"
    }
    
    /**
     * Export all learning data to a backup file
     */
    fun exportBackup(): File? {
        if (!isAvailable()) return null
        
        return try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
            val backupFile = File(storageDir, "backup_$timestamp.json")
            
            val allData = JSONObject().apply {
                put("exportTime", System.currentTimeMillis())
                put("version", VERSION)
                
                // Read all existing JSON files
                storageDir?.listFiles()?.filter { it.extension == "json" && !it.name.startsWith("backup") }?.forEach { file ->
                    try {
                        put(file.nameWithoutExtension, JSONObject(file.readText()))
                    } catch (e: Exception) {
                        // Skip invalid files
                    }
                }
            }
            
            backupFile.writeText(allData.toString(2))
            ErrorLogger.info(TAG, "📦 Exported backup: ${backupFile.name}")
            backupFile
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to export backup: ${e.message}", e)
            null
        }
    }
    
    /**
     * Clear all persistent data (use with caution!)
     */
    fun clearAll(context: Context? = null): Boolean {
        if (!isAvailable()) return false
        
        return try {
            storageDir?.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
            ErrorLogger.info(TAG, "🗑️ Cleared all persistent learning data")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to clear data: ${e.message}", e)
            false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.1: FULL EXPORT/IMPORT FOR APP REINSTALL
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Export ALL learning data to a single backup file in Downloads folder.
     * This file survives app uninstall and can be imported after reinstall.
     * 
     * Exports:
     * - Edge learning thresholds
     * - Entry/Exit intelligence
     * - Trading memory patterns
     * - FluidLearning progress
     * - Trade history
     * - Scanner learning stats
     * - BehaviorAI state
     */
    fun exportFullBackup(context: Context): File? {
        return try {
            // Use Downloads folder for better user access
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val aateDir = File(downloadsDir, "AATE_Backups")
            if (!aateDir.exists()) aateDir.mkdirs()
            
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
            val backupFile = File(aateDir, "AATE_backup_$timestamp.json")
            
            val fullBackup = JSONObject().apply {
                put("exportTime", System.currentTimeMillis())
                put("exportTimeReadable", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()))
                put("version", VERSION)
                put("appVersion", "5.1")
                
                // 1. Edge Learning
                loadEdgeLearning()?.let { 
                    put("edge_learning", JSONObject(it)) 
                }
                
                // 2. Entry Intelligence - read from file directly
                try {
                    val entryFile = File(storageDir, "entry_intelligence.json")
                    if (entryFile.exists()) {
                        put("entry_intelligence", JSONObject(entryFile.readText()))
                    }
                } catch (_: Exception) {}
                
                // 3. Exit Intelligence - read from file directly
                try {
                    val exitFile = File(storageDir, "exit_intelligence.json")
                    if (exitFile.exists()) {
                        put("exit_intelligence", JSONObject(exitFile.readText()))
                    }
                } catch (_: Exception) {}
                
                // 4. Token Win Memory
                loadTokenWinMemory()?.let { (winners, patterns) ->
                    put("token_win_memory", JSONObject().apply {
                        put("winners", winners)
                        put("patterns", patterns)
                    })
                }
                
                // 5. All persistent JSON files from storage dir
                storageDir?.listFiles()?.filter { it.extension == "json" && !it.name.startsWith("backup") }?.forEach { file ->
                    try {
                        val key = file.nameWithoutExtension
                        if (!has(key)) {
                            put(key, JSONObject(file.readText()))
                        }
                    } catch (_: Exception) {}
                }
                
                // 6. SharedPreferences data
                val prefsBackup = JSONObject()
                
                // Fluid Learning prefs
                try {
                    val fluidPrefs = context.getSharedPreferences("fluid_learning", Context.MODE_PRIVATE)
                    val fluidData = JSONObject().apply {
                        put("paperBalance", fluidPrefs.getFloat("paper_balance", 5.0f))
                        put("totalPaperPnl", fluidPrefs.getFloat("total_paper_pnl", 0.0f))
                        put("paperWins", fluidPrefs.getInt("paper_wins", 0))
                        put("paperLosses", fluidPrefs.getInt("paper_losses", 0))
                    }
                    prefsBackup.put("fluid_learning", fluidData)
                } catch (_: Exception) {}
                
                // Bot Brain prefs
                try {
                    val brainPrefs = context.getSharedPreferences("bot_brain_thresholds", Context.MODE_PRIVATE)
                    val brainData = JSONObject().apply {
                        put("dynamicBuyThreshold", brainPrefs.getFloat("dynamicBuyThreshold", 50f))
                        put("dynamicRsiLower", brainPrefs.getFloat("dynamicRsiLower", 30f))
                        put("dynamicRsiUpper", brainPrefs.getFloat("dynamicRsiUpper", 70f))
                        put("dynamicVolatilityMultiplier", brainPrefs.getFloat("dynamicVolatilityMultiplier", 1.0f))
                    }
                    prefsBackup.put("bot_brain", brainData)
                } catch (_: Exception) {}
                
                // FluidLearningAI stats
                try {
                    val fluidAIPrefs = context.getSharedPreferences("fluid_learning_ai", Context.MODE_PRIVATE)
                    val fluidAIData = JSONObject().apply {
                        put("totalWins", fluidAIPrefs.getInt("total_wins", 0))
                        put("totalLosses", fluidAIPrefs.getInt("total_losses", 0))
                        put("totalPnlPct", fluidAIPrefs.getFloat("total_pnl_pct", 0f))
                    }
                    prefsBackup.put("fluid_learning_ai", fluidAIData)
                } catch (_: Exception) {}
                
                // Trade history count
                try {
                    val historyPrefs = context.getSharedPreferences("trade_history", Context.MODE_PRIVATE)
                    val historyData = JSONObject().apply {
                        put("tradeCount", historyPrefs.getInt("trade_count", 0))
                        put("tradesJson", historyPrefs.getString("trades_json", "[]"))
                    }
                    prefsBackup.put("trade_history", historyData)
                } catch (_: Exception) {}
                
                // V5.2: Include API keys so user doesn't have to re-enter them
                try {
                    val configPrefs = context.getSharedPreferences("bot_config", Context.MODE_PRIVATE)
                    val apiKeysData = JSONObject().apply {
                        put("helius_api_key", configPrefs.getString("helius_api_key", "") ?: "")
                        put("birdeye_api_key", configPrefs.getString("birdeye_api_key", "") ?: "")
                        put("groq_api_key", configPrefs.getString("groq_api_key", "") ?: "")
                        put("gemini_api_key", configPrefs.getString("gemini_api_key", "") ?: "")
                        put("jupiter_api_key", configPrefs.getString("jupiter_api_key", "") ?: "")
                        // Also backup the private key (encrypted) for full restore
                        put("private_key_b58", configPrefs.getString("private_key_b58", "") ?: "")
                    }
                    prefsBackup.put("api_keys", apiKeysData)
                    ErrorLogger.info(TAG, "📦 API keys included in backup")
                } catch (_: Exception) {}
                
                put("shared_preferences", prefsBackup)
            }
            
            backupFile.writeText(fullBackup.toString(2))
            ErrorLogger.info(TAG, "📦 FULL BACKUP exported: ${backupFile.absolutePath}")
            backupFile
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to export full backup: ${e.message}", e)
            null
        }
    }
    
    /**
     * Import learning data from a backup file.
     * Call this after reinstalling the app.
     */
    fun importFullBackup(context: Context, backupFile: File): Boolean {
        return try {
            if (!backupFile.exists()) {
                ErrorLogger.warn(TAG, "Backup file not found: ${backupFile.absolutePath}")
                return false
            }
            
            val backupJson = JSONObject(backupFile.readText())
            var restoredCount = 0
            
            // 1. Restore Edge Learning
            if (backupJson.has("edge_learning")) {
                val edge = backupJson.getJSONObject("edge_learning")
                saveEdgeLearning(
                    paperBuyPctMin = edge.optDouble("paperBuyPctMin", 45.0),
                    paperVolumeMin = edge.optDouble("paperVolumeMin", 10.0),
                    liveBuyPctMin = edge.optDouble("liveBuyPctMin", 55.0),
                    liveVolumeMin = edge.optDouble("liveVolumeMin", 15.0),
                    totalTrades = edge.optInt("totalTrades", 0),
                    winningTrades = edge.optInt("winningTrades", 0),
                )
                restoredCount++
            }
            
            // 2. Restore Entry Intelligence - write file directly
            if (backupJson.has("entry_intelligence")) {
                try {
                    val entryFile = File(storageDir, "entry_intelligence.json")
                    entryFile.writeText(backupJson.getJSONObject("entry_intelligence").toString(2))
                    restoredCount++
                } catch (_: Exception) {}
            }
            
            // 3. Restore Exit Intelligence - write file directly
            if (backupJson.has("exit_intelligence")) {
                try {
                    val exitFile = File(storageDir, "exit_intelligence.json")
                    exitFile.writeText(backupJson.getJSONObject("exit_intelligence").toString(2))
                    restoredCount++
                } catch (_: Exception) {}
            }
            
            // 4. Restore Token Win Memory
            if (backupJson.has("token_win_memory")) {
                val winMem = backupJson.getJSONObject("token_win_memory")
                saveTokenWinMemory(
                    winMem.optString("winners", "{}"),
                    winMem.optString("patterns", "{}")
                )
                restoredCount++
            }
            
            // 5. Restore SharedPreferences
            if (backupJson.has("shared_preferences")) {
                val prefs = backupJson.getJSONObject("shared_preferences")
                
                // Fluid Learning
                if (prefs.has("fluid_learning")) {
                    val fluid = prefs.getJSONObject("fluid_learning")
                    context.getSharedPreferences("fluid_learning", Context.MODE_PRIVATE).edit().apply {
                        putFloat("paper_balance", fluid.optDouble("paperBalance", 5.0).toFloat())
                        putFloat("total_paper_pnl", fluid.optDouble("totalPaperPnl", 0.0).toFloat())
                        putInt("paper_wins", fluid.optInt("paperWins", 0))
                        putInt("paper_losses", fluid.optInt("paperLosses", 0))
                        apply()
                    }
                    restoredCount++
                }
                
                // Bot Brain
                if (prefs.has("bot_brain")) {
                    val brain = prefs.getJSONObject("bot_brain")
                    context.getSharedPreferences("bot_brain_thresholds", Context.MODE_PRIVATE).edit().apply {
                        putFloat("dynamicBuyThreshold", brain.optDouble("dynamicBuyThreshold", 50.0).toFloat())
                        putFloat("dynamicRsiLower", brain.optDouble("dynamicRsiLower", 30.0).toFloat())
                        putFloat("dynamicRsiUpper", brain.optDouble("dynamicRsiUpper", 70.0).toFloat())
                        putFloat("dynamicVolatilityMultiplier", brain.optDouble("dynamicVolatilityMultiplier", 1.0).toFloat())
                        apply()
                    }
                    restoredCount++
                }
                
                // FluidLearningAI
                if (prefs.has("fluid_learning_ai")) {
                    val fluidAI = prefs.getJSONObject("fluid_learning_ai")
                    context.getSharedPreferences("fluid_learning_ai", Context.MODE_PRIVATE).edit().apply {
                        putInt("total_wins", fluidAI.optInt("totalWins", 0))
                        putInt("total_losses", fluidAI.optInt("totalLosses", 0))
                        putFloat("total_pnl_pct", fluidAI.optDouble("totalPnlPct", 0.0).toFloat())
                        apply()
                    }
                    restoredCount++
                }
                
                // Trade History
                if (prefs.has("trade_history")) {
                    val history = prefs.getJSONObject("trade_history")
                    context.getSharedPreferences("trade_history", Context.MODE_PRIVATE).edit().apply {
                        putInt("trade_count", history.optInt("tradeCount", 0))
                        putString("trades_json", history.optString("tradesJson", "[]"))
                        apply()
                    }
                    restoredCount++
                }
                
                // V5.2: Restore API keys
                if (prefs.has("api_keys")) {
                    val apiKeys = prefs.getJSONObject("api_keys")
                    context.getSharedPreferences("bot_config", Context.MODE_PRIVATE).edit().apply {
                        val helius = apiKeys.optString("helius_api_key", "")
                        val birdeye = apiKeys.optString("birdeye_api_key", "")
                        val groq = apiKeys.optString("groq_api_key", "")
                        val gemini = apiKeys.optString("gemini_api_key", "")
                        val jupiter = apiKeys.optString("jupiter_api_key", "")
                        val privateKey = apiKeys.optString("private_key_b58", "")
                        
                        if (helius.isNotEmpty()) putString("helius_api_key", helius)
                        if (birdeye.isNotEmpty()) putString("birdeye_api_key", birdeye)
                        if (groq.isNotEmpty()) putString("groq_api_key", groq)
                        if (gemini.isNotEmpty()) putString("gemini_api_key", gemini)
                        if (jupiter.isNotEmpty()) putString("jupiter_api_key", jupiter)
                        if (privateKey.isNotEmpty()) putString("private_key_b58", privateKey)
                        apply()
                    }
                    ErrorLogger.info(TAG, "🔑 API keys restored from backup")
                    restoredCount++
                }
            }
            
            ErrorLogger.info(TAG, "✅ BACKUP RESTORED: $restoredCount components from ${backupFile.name}")
            true
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to import backup: ${e.message}", e)
            false
        }
    }
    
    /**
     * Find the most recent backup file in Downloads/AATE_Backups/
     */
    fun findLatestBackup(): File? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val aateDir = File(downloadsDir, "AATE_Backups")
            
            if (!aateDir.exists()) return null
            
            aateDir.listFiles()
                ?.filter { it.name.startsWith("AATE_backup_") && it.extension == "json" }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to find backup: ${e.message}", e)
            null
        }
    }
    
    /**
     * List all available backups
     */
    fun listBackups(): List<File> {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val aateDir = File(downloadsDir, "AATE_Backups")
            
            if (!aateDir.exists()) return emptyList()
            
            aateDir.listFiles()
                ?.filter { it.name.startsWith("AATE_backup_") && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
