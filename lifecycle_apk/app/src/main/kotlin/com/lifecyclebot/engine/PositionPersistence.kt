package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * POSITION PERSISTENCE - V5.6.9
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM:
 * Positions were only stored in memory. If the app crashes or restarts (Android
 * killing the process, user force stop, etc.), active positions are lost.
 * This is CRITICAL for live trading — the bot could have SOL locked in tokens
 * that it forgets about, effectively losing those funds.
 * 
 * SOLUTION:
 * Persist open positions to SharedPreferences (fast, survives app restart).
 * On app start, reload positions and resume managing them.
 * 
 * PERSISTENCE STRATEGY:
 * - Save positions on every entry/exit
 * - Save periodically during tick loop (every 30 seconds)
 * - Load on bot start before scanning
 * - Also save TokenState minimal data (symbol, price, liquidity) for context
 * 
 * DATA SAVED PER POSITION:
 * - mint (token address)
 * - symbol 
 * - Position data class fields (qty, entry price, cost, highest price, etc.)
 * - Trading mode, entry phase
 * - Last known price and liquidity
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PositionPersistence {
    
    private const val TAG = "PositionPersist"
    private const val PREFS_NAME = "position_persistence_v1"
    private const val KEY_POSITIONS = "open_positions"
    private const val KEY_LAST_SAVE = "last_save_time"
    private const val KEY_VERSION = "persistence_version"
    private const val CURRENT_VERSION = 1
    
    // Minimum interval between periodic saves (don't spam disk)
    private const val MIN_SAVE_INTERVAL_MS = 10_000L  // 10 seconds
    
    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private var lastSaveTime = 0L
    
    /**
     * Initialize with application context.
     * Call this early in app startup (before bot starts).
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ErrorLogger.info(TAG, "Position persistence initialized")
    }
    
    /**
     * Data class for persisted position information.
     * Contains all fields needed to reconstruct position state.
     */
    data class PersistedPosition(
        // Token identification
        val mint: String,
        val symbol: String,
        val name: String,
        
        // Position core fields
        val qtyToken: Double,
        val entryPrice: Double,
        val entryTime: Long,
        val costSol: Double,
        val highestPrice: Double,
        val lowestPrice: Double,
        val peakGainPct: Double,
        val entryPhase: String,
        val entryScore: Double,
        val entryLiquidityUsd: Double,
        val entryMcap: Double,
        val isPaperPosition: Boolean,
        
        // Trading mode
        val tradingMode: String,
        val tradingModeEmoji: String,
        val modeHistory: String,
        
        // Special modes
        val isTreasuryPosition: Boolean,
        val isBlueChipPosition: Boolean,
        val isShitCoinPosition: Boolean,
        val isLongHold: Boolean,
        
        // Profit lock state
        val capitalRecovered: Boolean,
        val capitalRecoveredSol: Double,
        val profitLocked: Boolean,
        val profitLockedSol: Double,
        val isHouseMoney: Boolean,
        val lockedProfitFloor: Double,
        
        // Top-up tracking
        val topUpCount: Int,
        val topUpCostSol: Double,
        val lastTopUpTime: Long,
        val lastTopUpPrice: Double,
        val partialSoldPct: Double,
        
        // Build phase
        val buildPhase: Int,
        val targetBuildSol: Double,
        
        // Last known market data (for context on reload)
        val lastKnownPrice: Double,
        val lastKnownLiquidity: Double,
        val lastKnownMcap: Double,
        val savedAt: Long,
    )
    
    /**
     * Save a single position immediately.
     * Call this after every entry/exit.
     */
    fun savePosition(ts: TokenState) {
        val positions = loadPositionsInternal().toMutableMap()
        
        if (ts.position.isOpen) {
            positions[ts.mint] = createPersistedPosition(ts)
            ErrorLogger.debug(TAG, "💾 Saved position: ${ts.symbol} | qty=${ts.position.qtyToken}")
        } else {
            // Position closed, remove from persistence
            if (positions.remove(ts.mint) != null) {
                ErrorLogger.debug(TAG, "🗑️ Removed closed position: ${ts.symbol}")
            }
        }
        
        savePositionsInternal(positions)
    }
    
    /**
     * Save all open positions from BotStatus.
     * Call this periodically and on app pause.
     */
    fun saveAllPositions(tokens: Map<String, TokenState>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
            return  // Don't spam disk
        }
        
        val openPositions = tokens.values
            .filter { it.position.isOpen }
            .associate { it.mint to createPersistedPosition(it) }
        
        if (openPositions.isEmpty()) {
            // Clear persistence if no open positions
            prefs?.edit()?.remove(KEY_POSITIONS)?.apply()
            lastSaveTime = now
            return
        }
        
        savePositionsInternal(openPositions)
        lastSaveTime = now
        
        ErrorLogger.info(TAG, "💾 Batch saved ${openPositions.size} positions")
    }
    
    /**
     * Load all persisted positions.
     * Returns map of mint -> PersistedPosition.
     */
    fun loadPositions(): Map<String, PersistedPosition> {
        val positions = loadPositionsInternal()
        if (positions.isNotEmpty()) {
            ErrorLogger.info(TAG, "📂 Loaded ${positions.size} persisted positions")
            positions.forEach { (mint, pos) ->
                val ageHours = (System.currentTimeMillis() - pos.savedAt) / 3600_000.0
                ErrorLogger.info(TAG, "  • ${pos.symbol}: qty=${pos.qtyToken} cost=${pos.costSol} SOL | " +
                    "saved ${String.format("%.1f", ageHours)}h ago | paper=${pos.isPaperPosition}")
            }
        }
        return positions
    }
    
    /**
     * Restore positions to TokenState objects.
     * Call this during bot startup to restore positions.
     * Returns list of TokenStates with positions restored.
     */
    fun restorePositions(existingTokens: MutableMap<String, TokenState>): Int {
        val persisted = loadPositions()
        if (persisted.isEmpty()) return 0
        
        var restoredCount = 0
        
        for ((mint, saved) in persisted) {
            // V5.9.122: paper positions NEVER go stale — they represent real
            // simulated capital. Previously anything > 24h old was silently
            // dropped on restart, which wiped the paper balance attached to
            // it (the SOL was debited on BUY but never refunded on drop).
            // Users reported losing hundreds of thousands of paper dollars
            // across app updates because of this. Now:
            //   • Paper positions: NEVER dropped for age. They live until
            //     normal exit logic closes them.
            //   • Live positions: keep the 7-day (was 24h) cutoff — after
            //     a week an on-chain position most likely was manually
            //     closed outside the app and we shouldn't resurrect it.
            //   • If we DO drop a paper position (future path), we MUST
            //     refund the costSol to UnifiedPaperWallet before dropping.
            val ageHours = (System.currentTimeMillis() - saved.savedAt) / 3600_000.0
            if (!saved.isPaperPosition && ageHours > 24.0 * 7) {
                ErrorLogger.warn(TAG, "⚠️ STALE live position for ${saved.symbol}: ${String.format("%.1f", ageHours)}h old — skipping restore")
                continue
            }
            if (saved.isPaperPosition && ageHours > 24.0 * 60) {
                // 60-day sanity cap to prevent infinite growth of junk rows.
                // REFUND the paper SOL back so capital isn't lost.
                ErrorLogger.warn(TAG, "⚠️ Very old paper position for ${saved.symbol} (${String.format("%.1f", ageHours)}h) — refunding ${saved.costSol} SOL and dropping")
                try {
                    BotService.creditUnifiedPaperSol(saved.costSol,
                        source = "stale_paper_refund[${saved.symbol}]")
                } catch (_: Exception) { /* non-fatal */ }
                continue
            }
            
            // Check if we already have this token
            val existing = existingTokens[mint]
            if (existing != null && existing.position.isOpen) {
                // Already have an open position — don't overwrite
                ErrorLogger.debug(TAG, "Position ${saved.symbol} already exists — skipping restore")
                continue
            }
            
            // Create or update TokenState with restored position
            val position = Position(
                qtyToken = saved.qtyToken,
                entryPrice = saved.entryPrice,
                entryTime = saved.entryTime,
                costSol = saved.costSol,
                highestPrice = saved.highestPrice,
                lowestPrice = saved.lowestPrice,
                peakGainPct = saved.peakGainPct,
                entryPhase = saved.entryPhase,
                entryScore = saved.entryScore,
                entryLiquidityUsd = saved.entryLiquidityUsd,
                entryMcap = saved.entryMcap,
                isPaperPosition = saved.isPaperPosition,
                tradingMode = saved.tradingMode,
                tradingModeEmoji = saved.tradingModeEmoji,
                modeHistory = saved.modeHistory,
                isTreasuryPosition = saved.isTreasuryPosition,
                isBlueChipPosition = saved.isBlueChipPosition,
                isShitCoinPosition = saved.isShitCoinPosition,
                isLongHold = saved.isLongHold,
                capitalRecovered = saved.capitalRecovered,
                capitalRecoveredSol = saved.capitalRecoveredSol,
                profitLocked = saved.profitLocked,
                profitLockedSol = saved.profitLockedSol,
                isHouseMoney = saved.isHouseMoney,
                lockedProfitFloor = saved.lockedProfitFloor,
                topUpCount = saved.topUpCount,
                topUpCostSol = saved.topUpCostSol,
                lastTopUpTime = saved.lastTopUpTime,
                lastTopUpPrice = saved.lastTopUpPrice,
                partialSoldPct = saved.partialSoldPct,
                buildPhase = saved.buildPhase,
                targetBuildSol = saved.targetBuildSol,
            )
            
            if (existing != null) {
                // Update existing TokenState with restored position
                existing.position = position
                existing.lastPrice = saved.lastKnownPrice
                existing.lastLiquidityUsd = saved.lastKnownLiquidity
                existing.lastMcap = saved.lastKnownMcap
                ErrorLogger.info(TAG, "🔄 Restored position to existing ${saved.symbol}")
            } else {
                // Create new TokenState
                val ts = TokenState(
                    mint = mint,
                    symbol = saved.symbol,
                    name = saved.name,
                    position = position,
                    lastPrice = saved.lastKnownPrice,
                    lastLiquidityUsd = saved.lastKnownLiquidity,
                    lastMcap = saved.lastKnownMcap,
                    source = "RESTORED",
                )
                existingTokens[mint] = ts
                ErrorLogger.info(TAG, "✨ Created TokenState for restored position ${saved.symbol}")
            }
            
            // Register with GlobalTradeRegistry
            GlobalTradeRegistry.registerPosition(
                mint = mint,
                symbol = saved.symbol,
                layer = if (saved.isPaperPosition) "PAPER" else "LIVE",
                sizeSol = saved.costSol
            )
            
            restoredCount++
        }
        
        if (restoredCount > 0) {
            ErrorLogger.info(TAG, "✅ Successfully restored $restoredCount positions")
        }
        
        return restoredCount
    }
    
    /**
     * Clear all persisted positions.
     * Call this when doing a full reset.
     */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
        ErrorLogger.info(TAG, "🧹 Cleared all persisted positions")
    }
    
    /**
     * Get count of persisted positions (without full load).
     */
    fun getPersistedCount(): Int {
        val json = prefs?.getString(KEY_POSITIONS, null) ?: return 0
        return try {
            JSONArray(json).length()
        } catch (e: Exception) {
            0
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun createPersistedPosition(ts: TokenState): PersistedPosition {
        val pos = ts.position
        return PersistedPosition(
            mint = ts.mint,
            symbol = ts.symbol,
            name = ts.name,
            qtyToken = pos.qtyToken,
            entryPrice = pos.entryPrice,
            entryTime = pos.entryTime,
            costSol = pos.costSol,
            highestPrice = pos.highestPrice,
            lowestPrice = pos.lowestPrice,
            peakGainPct = pos.peakGainPct,
            entryPhase = pos.entryPhase,
            entryScore = pos.entryScore,
            entryLiquidityUsd = pos.entryLiquidityUsd,
            entryMcap = pos.entryMcap,
            isPaperPosition = pos.isPaperPosition,
            tradingMode = pos.tradingMode,
            tradingModeEmoji = pos.tradingModeEmoji,
            modeHistory = pos.modeHistory,
            isTreasuryPosition = pos.isTreasuryPosition,
            isBlueChipPosition = pos.isBlueChipPosition,
            isShitCoinPosition = pos.isShitCoinPosition,
            isLongHold = pos.isLongHold,
            capitalRecovered = pos.capitalRecovered,
            capitalRecoveredSol = pos.capitalRecoveredSol,
            profitLocked = pos.profitLocked,
            profitLockedSol = pos.profitLockedSol,
            isHouseMoney = pos.isHouseMoney,
            lockedProfitFloor = pos.lockedProfitFloor,
            topUpCount = pos.topUpCount,
            topUpCostSol = pos.topUpCostSol,
            lastTopUpTime = pos.lastTopUpTime,
            lastTopUpPrice = pos.lastTopUpPrice,
            partialSoldPct = pos.partialSoldPct,
            buildPhase = pos.buildPhase,
            targetBuildSol = pos.targetBuildSol,
            lastKnownPrice = ts.lastPrice,
            lastKnownLiquidity = ts.lastLiquidityUsd,
            lastKnownMcap = ts.lastMcap,
            savedAt = System.currentTimeMillis(),
        )
    }
    
    private fun savePositionsInternal(positions: Map<String, PersistedPosition>) {
        val jsonArray = JSONArray()
        
        for ((_, pos) in positions) {
            val json = JSONObject().apply {
                put("mint", pos.mint)
                put("symbol", pos.symbol)
                put("name", pos.name)
                put("qtyToken", pos.qtyToken)
                put("entryPrice", pos.entryPrice)
                put("entryTime", pos.entryTime)
                put("costSol", pos.costSol)
                put("highestPrice", pos.highestPrice)
                put("lowestPrice", pos.lowestPrice)
                put("peakGainPct", pos.peakGainPct)
                put("entryPhase", pos.entryPhase)
                put("entryScore", pos.entryScore)
                put("entryLiquidityUsd", pos.entryLiquidityUsd)
                put("entryMcap", pos.entryMcap)
                put("isPaperPosition", pos.isPaperPosition)
                put("tradingMode", pos.tradingMode)
                put("tradingModeEmoji", pos.tradingModeEmoji)
                put("modeHistory", pos.modeHistory)
                put("isTreasuryPosition", pos.isTreasuryPosition)
                put("isBlueChipPosition", pos.isBlueChipPosition)
                put("isShitCoinPosition", pos.isShitCoinPosition)
                put("isLongHold", pos.isLongHold)
                put("capitalRecovered", pos.capitalRecovered)
                put("capitalRecoveredSol", pos.capitalRecoveredSol)
                put("profitLocked", pos.profitLocked)
                put("profitLockedSol", pos.profitLockedSol)
                put("isHouseMoney", pos.isHouseMoney)
                put("lockedProfitFloor", pos.lockedProfitFloor)
                put("topUpCount", pos.topUpCount)
                put("topUpCostSol", pos.topUpCostSol)
                put("lastTopUpTime", pos.lastTopUpTime)
                put("lastTopUpPrice", pos.lastTopUpPrice)
                put("partialSoldPct", pos.partialSoldPct)
                put("buildPhase", pos.buildPhase)
                put("targetBuildSol", pos.targetBuildSol)
                put("lastKnownPrice", pos.lastKnownPrice)
                put("lastKnownLiquidity", pos.lastKnownLiquidity)
                put("lastKnownMcap", pos.lastKnownMcap)
                put("savedAt", pos.savedAt)
            }
            jsonArray.put(json)
        }
        
        prefs?.edit()
            ?.putString(KEY_POSITIONS, jsonArray.toString())
            ?.putLong(KEY_LAST_SAVE, System.currentTimeMillis())
            ?.putInt(KEY_VERSION, CURRENT_VERSION)
            ?.apply()
    }
    
    private fun loadPositionsInternal(): Map<String, PersistedPosition> {
        val json = prefs?.getString(KEY_POSITIONS, null) ?: return emptyMap()
        
        return try {
            val jsonArray = JSONArray(json)
            val result = mutableMapOf<String, PersistedPosition>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pos = PersistedPosition(
                    mint = obj.getString("mint"),
                    symbol = obj.optString("symbol", "???"),
                    name = obj.optString("name", ""),
                    qtyToken = obj.getDouble("qtyToken"),
                    entryPrice = obj.getDouble("entryPrice"),
                    entryTime = obj.getLong("entryTime"),
                    costSol = obj.getDouble("costSol"),
                    highestPrice = obj.optDouble("highestPrice", 0.0),
                    lowestPrice = obj.optDouble("lowestPrice", 0.0),
                    peakGainPct = obj.optDouble("peakGainPct", 0.0),
                    entryPhase = obj.optString("entryPhase", ""),
                    entryScore = obj.optDouble("entryScore", 0.0),
                    entryLiquidityUsd = obj.optDouble("entryLiquidityUsd", 0.0),
                    entryMcap = obj.optDouble("entryMcap", 0.0),
                    isPaperPosition = obj.optBoolean("isPaperPosition", false),  // V5.9.252 FIX: default LIVE not paper — old saved positions without this field are on-chain positions
                    tradingMode = obj.optString("tradingMode", "STANDARD"),
                    tradingModeEmoji = obj.optString("tradingModeEmoji", "📈"),
                    modeHistory = obj.optString("modeHistory", ""),
                    isTreasuryPosition = obj.optBoolean("isTreasuryPosition", false),
                    isBlueChipPosition = obj.optBoolean("isBlueChipPosition", false),
                    isShitCoinPosition = obj.optBoolean("isShitCoinPosition", false),
                    isLongHold = obj.optBoolean("isLongHold", false),
                    capitalRecovered = obj.optBoolean("capitalRecovered", false),
                    capitalRecoveredSol = obj.optDouble("capitalRecoveredSol", 0.0),
                    profitLocked = obj.optBoolean("profitLocked", false),
                    profitLockedSol = obj.optDouble("profitLockedSol", 0.0),
                    isHouseMoney = obj.optBoolean("isHouseMoney", false),
                    lockedProfitFloor = obj.optDouble("lockedProfitFloor", 0.0),
                    topUpCount = obj.optInt("topUpCount", 0),
                    topUpCostSol = obj.optDouble("topUpCostSol", 0.0),
                    lastTopUpTime = obj.optLong("lastTopUpTime", 0L),
                    lastTopUpPrice = obj.optDouble("lastTopUpPrice", 0.0),
                    partialSoldPct = obj.optDouble("partialSoldPct", 0.0),
                    buildPhase = obj.optInt("buildPhase", 0),
                    targetBuildSol = obj.optDouble("targetBuildSol", 0.0),
                    lastKnownPrice = obj.optDouble("lastKnownPrice", 0.0),
                    lastKnownLiquidity = obj.optDouble("lastKnownLiquidity", 0.0),
                    lastKnownMcap = obj.optDouble("lastKnownMcap", 0.0),
                    savedAt = obj.optLong("savedAt", 0L),
                )
                result[pos.mint] = pos
            }
            
            result
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to parse persisted positions: ${e.message}", e)
            emptyMap()
        }
    }
}
