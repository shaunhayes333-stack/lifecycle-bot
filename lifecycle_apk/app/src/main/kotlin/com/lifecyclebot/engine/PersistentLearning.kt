package com.lifecyclebot.engine

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Persists learned AI data to external storage so it survives app uninstall/reinstall.
 * 
 * Storage location: /sdcard/LifecycleBot/
 * Files:
 *   - edge_learning.json
 *   - entry_intelligence.json  
 *   - exit_intelligence.json
 *   - trading_memory.json
 *   - bot_brain.json
 */
object PersistentLearning {
    
    private const val TAG = "PersistentLearning"
    private const val FOLDER_NAME = "LifecycleBot"
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
                    LifecycleBot Persistent Learning Data
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
}
