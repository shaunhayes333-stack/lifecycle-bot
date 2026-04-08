package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TokenWinMemory - Remembers winning tokens and their characteristics
 * 
 * This system tracks:
 * 1. Specific tokens that generated profits (for re-entry opportunities)
 * 2. Token name/symbol patterns that correlate with wins
 * 3. Developer/creator addresses that launched winners
 * 4. Characteristics of winning tokens (liquidity, age, holder count at entry)
 * 
 * The goal is to:
 * - Boost confidence on tokens similar to past winners
 * - Enable re-entry on tokens that pumped before
 * - Learn which "themes" or patterns lead to wins
 */
object TokenWinMemory {
    
    private const val PREFS_NAME = "token_win_memory"
    private const val MAX_WINNERS = 5000     // Max tokens to remember
    private const val MAX_PATTERNS = 1000     // Max patterns to track
    private const val MIN_PNL_FOR_WIN = 0.5 // Min PnL% to count as a significant win
    
    private var ctx: Context? = null
    
    // ═══════════════════════════════════════════════════════════════════════
    // WINNING TOKEN RECORDS
    // ═══════════════════════════════════════════════════════════════════════
    
    data class WinningToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val pnlPercent: Double,
        val peakPnl: Double,           // Highest PnL reached during hold
        val entryMcap: Double,
        val exitMcap: Double,
        val entryLiquidity: Double,
        val holdTimeMinutes: Int,
        val buyPercent: Double,        // Buy pressure at entry
        val timestamp: Long,
        val source: String,            // Where we found it (pump.fun, dexscreener, etc)
        val phase: String,             // Phase at entry (early_accumulation, etc)
        var timesTraded: Int = 1,      // How many times we've traded this token
        var totalPnl: Double = pnlPercent,  // Cumulative PnL from this token
    )
    
    // Mint -> WinningToken
    private val winningTokens = ConcurrentHashMap<String, WinningToken>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // PATTERN LEARNING - What characteristics correlate with wins
    // ═══════════════════════════════════════════════════════════════════════
    
    data class PatternStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var avgWinPnl: Double = 0.0,
    ) {
        val winRate: Double get() = if (wins + losses > 0) wins.toDouble() / (wins + losses) else 0.5
        val isReliable: Boolean get() = wins + losses >= 5  // Enough data to trust
    }
    
    // Pattern type -> pattern value -> stats
    // e.g., "name_contains" -> "AI" -> PatternStats(wins=5, losses=2)
    private val patterns = ConcurrentHashMap<String, ConcurrentHashMap<String, PatternStats>>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // DEVELOPER TRACKING - Which creators launch winners
    // ═══════════════════════════════════════════════════════════════════════
    
    // Creator address -> win/loss stats
    private val creatorStats = ConcurrentHashMap<String, PatternStats>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════
    
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("TokenWinMemory", 
            "📊 Loaded: ${winningTokens.size} winning tokens, ${countPatterns()} patterns")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RECORD A TRADE OUTCOME
    // ═══════════════════════════════════════════════════════════════════════
    
    fun recordTradeOutcome(
        mint: String,
        symbol: String,
        name: String,
        pnlPercent: Double,
        peakPnl: Double,
        entryMcap: Double,
        exitMcap: Double,
        entryLiquidity: Double,
        holdTimeMinutes: Int,
        buyPercent: Double,
        source: String,
        phase: String,
        creatorAddress: String? = null,
    ) {
        val isWin = pnlPercent >= MIN_PNL_FOR_WIN
        val isLoss = pnlPercent <= -MIN_PNL_FOR_WIN
        val isScratch = !isWin && !isLoss
        
        // Skip scratch trades
        if (isScratch) {
            ErrorLogger.debug("TokenWinMemory", "$symbol: Scratch trade (${pnlPercent.toInt()}%) - not recorded")
            return
        }
        
        // ── Record winning token ─────────────────────────────────────────
        if (isWin) {
            val existing = winningTokens[mint]
            if (existing != null) {
                // Update existing winner
                existing.timesTraded++
                existing.totalPnl += pnlPercent
                ErrorLogger.info("TokenWinMemory", 
                    "🏆 REPEAT WINNER: $symbol | +${pnlPercent.toInt()}% | total: +${existing.totalPnl.toInt()}% over ${existing.timesTraded} trades")
            } else {
                // New winner
                winningTokens[mint] = WinningToken(
                    mint = mint,
                    symbol = symbol,
                    name = name,
                    pnlPercent = pnlPercent,
                    peakPnl = peakPnl,
                    entryMcap = entryMcap,
                    exitMcap = exitMcap,
                    entryLiquidity = entryLiquidity,
                    holdTimeMinutes = holdTimeMinutes,
                    buyPercent = buyPercent,
                    timestamp = System.currentTimeMillis(),
                    source = source,
                    phase = phase,
                )
                ErrorLogger.info("TokenWinMemory", 
                    "🏆 NEW WINNER: $symbol | +${pnlPercent.toInt()}% | mcap: $${entryMcap.toInt()} → $${exitMcap.toInt()}")
                
                // Trim if too many
                trimWinners()
            }
        }
        
        // ── Learn patterns from this trade ───────────────────────────────
        learnPatterns(symbol, name, entryMcap, entryLiquidity, buyPercent, phase, source, isWin, pnlPercent)
        
        // ── Track creator ────────────────────────────────────────────────
        if (!creatorAddress.isNullOrBlank()) {
            val stats = creatorStats.getOrPut(creatorAddress) { PatternStats() }
            if (isWin) {
                stats.wins++
                stats.totalPnl += pnlPercent
                stats.avgWinPnl = stats.totalPnl / stats.wins
            } else {
                stats.losses++
            }
        }
        
        // Save periodically
        if ((winningTokens.size + countPatterns()) % 10 == 0) {
            save()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PATTERN LEARNING
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun learnPatterns(
        symbol: String,
        name: String,
        mcap: Double,
        liquidity: Double,
        buyPercent: Double,
        phase: String,
        source: String,
        isWin: Boolean,
        pnl: Double,
    ) {
        // ── Name-based patterns ──────────────────────────────────────────
        val namePatterns = extractNamePatterns(name.lowercase())
        namePatterns.forEach { pattern ->
            recordPattern("name_contains", pattern, isWin, pnl)
        }
        
        // ── Symbol patterns ──────────────────────────────────────────────
        val symbolPatterns = extractSymbolPatterns(symbol.uppercase())
        symbolPatterns.forEach { pattern ->
            recordPattern("symbol_pattern", pattern, isWin, pnl)
        }
        
        // ── Mcap bucket ──────────────────────────────────────────────────
        val mcapBucket = when {
            mcap < 20_000 -> "micro_<20k"
            mcap < 50_000 -> "small_20k_50k"
            mcap < 100_000 -> "mid_50k_100k"
            mcap < 500_000 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        recordPattern("mcap_bucket", mcapBucket, isWin, pnl)
        
        // ── Liquidity ratio ──────────────────────────────────────────────
        val liqRatio = if (mcap > 0) liquidity / mcap else 0.0
        val liqBucket = when {
            liqRatio < 0.1 -> "liq_ratio_<10%"
            liqRatio < 0.3 -> "liq_ratio_10_30%"
            liqRatio < 0.5 -> "liq_ratio_30_50%"
            else -> "liq_ratio_50%+"
        }
        recordPattern("liq_ratio", liqBucket, isWin, pnl)
        
        // ── Buy pressure bucket ──────────────────────────────────────────
        val buyBucket = when {
            buyPercent < 40 -> "buy_weak_<40%"
            buyPercent < 55 -> "buy_neutral_40_55%"
            buyPercent < 70 -> "buy_strong_55_70%"
            else -> "buy_fomo_70%+"
        }
        recordPattern("buy_pressure", buyBucket, isWin, pnl)
        
        // ── Phase ────────────────────────────────────────────────────────
        recordPattern("phase", phase, isWin, pnl)
        
        // ── Source ───────────────────────────────────────────────────────
        recordPattern("source", source, isWin, pnl)
    }
    
    private fun recordPattern(type: String, value: String, isWin: Boolean, pnl: Double) {
        val typeMap = patterns.getOrPut(type) { ConcurrentHashMap() }
        val stats = typeMap.getOrPut(value) { PatternStats() }
        
        if (isWin) {
            stats.wins++
            stats.totalPnl += pnl
            stats.avgWinPnl = stats.totalPnl / stats.wins
        } else {
            stats.losses++
        }
    }
    
    private fun extractNamePatterns(name: String): List<String> {
        val patterns = mutableListOf<String>()
        
        // Common meme themes
        val themes = listOf(
            "ai", "gpt", "agent", "bot",           // AI theme
            "pepe", "frog", "wojak", "chad",       // Meme characters
            "doge", "shib", "inu", "dog", "cat",   // Animal coins
            "elon", "trump", "biden", "musk",      // Celebrity names
            "moon", "rocket", "mars", "space",     // Moon themes
            "baby", "mini", "mega", "giga", "ultra", // Size modifiers
            "safe", "fair", "based", "wagmi",      // Community terms
            "sol", "solana", "pump", "bonk",       // Solana specific
            "100x", "1000x", "gem",                // Moonshot language
        )
        
        themes.forEach { theme ->
            if (name.contains(theme)) {
                patterns.add("theme_$theme")
            }
        }
        
        // Length pattern
        val lengthBucket = when {
            name.length <= 4 -> "name_short"
            name.length <= 8 -> "name_medium"
            else -> "name_long"
        }
        patterns.add(lengthBucket)
        
        return patterns
    }
    
    private fun extractSymbolPatterns(symbol: String): List<String> {
        val patterns = mutableListOf<String>()
        
        // Symbol length
        val lenBucket = when {
            symbol.length <= 3 -> "sym_short"
            symbol.length <= 5 -> "sym_medium"
            else -> "sym_long"
        }
        patterns.add(lenBucket)
        
        // All caps vs mixed case (already uppercase)
        if (symbol.all { it.isUpperCase() || it.isDigit() }) {
            patterns.add("sym_standard")
        }
        
        return patterns
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // QUERY: Get confidence boost for a token based on memory
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Returns a confidence multiplier (0.5 to 2.0) based on how similar
     * this token is to past winners.
     */
    fun getConfidenceMultiplier(
        mint: String,
        symbol: String,
        name: String,
        mcap: Double,
        liquidity: Double,
        buyPercent: Double,
        phase: String,
        source: String,
    ): Double {
        var score = 0.0
        var factors = 0
        
        // ── Check if this exact token was a winner ───────────────────────
        val pastWin = winningTokens[mint]
        if (pastWin != null) {
            // HUGE boost for repeat winners
            val repeatBoost = when {
                pastWin.totalPnl >= 100 -> 50.0  // Big winner
                pastWin.totalPnl >= 50 -> 35.0
                pastWin.totalPnl >= 20 -> 25.0
                else -> 15.0
            }
            score += repeatBoost
            factors++
            ErrorLogger.info("TokenWinMemory", 
                "🔥 REPEAT WINNER DETECTED: $symbol | past pnl: +${pastWin.totalPnl.toInt()}% | boost: +$repeatBoost")
        }
        
        // ── Check name patterns ──────────────────────────────────────────
        val namePatterns = extractNamePatterns(name.lowercase())
        namePatterns.forEach { pattern ->
            val stats = patterns["name_contains"]?.get(pattern)
            if (stats != null && stats.isReliable) {
                val patternScore = (stats.winRate - 0.5) * 20.0  // -10 to +10
                score += patternScore
                factors++
            }
        }
        
        // ── Check mcap bucket ────────────────────────────────────────────
        val mcapBucket = when {
            mcap < 20_000 -> "micro_<20k"
            mcap < 50_000 -> "small_20k_50k"
            mcap < 100_000 -> "mid_50k_100k"
            mcap < 500_000 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        val mcapStats = patterns["mcap_bucket"]?.get(mcapBucket)
        if (mcapStats != null && mcapStats.isReliable) {
            score += (mcapStats.winRate - 0.5) * 15.0
            factors++
        }
        
        // ── Check phase ──────────────────────────────────────────────────
        val phaseStats = patterns["phase"]?.get(phase)
        if (phaseStats != null && phaseStats.isReliable) {
            score += (phaseStats.winRate - 0.5) * 15.0
            factors++
        }
        
        // ── Check source ─────────────────────────────────────────────────
        val sourceStats = patterns["source"]?.get(source)
        if (sourceStats != null && sourceStats.isReliable) {
            score += (sourceStats.winRate - 0.5) * 10.0
            factors++
        }
        
        // Convert score to multiplier (0.5 to 2.0)
        // score ranges roughly -30 to +50
        val multiplier = when {
            score >= 30 -> 2.0    // Very similar to past winners
            score >= 15 -> 1.5   
            score >= 5 -> 1.2
            score >= -5 -> 1.0   // Neutral
            score >= -15 -> 0.8
            else -> 0.5          // Very different from winners
        }
        
        if (factors > 0) {
            ErrorLogger.debug("TokenWinMemory", 
                "$symbol: memory score=${"%.1f".format(score)} multiplier=${"%.2f".format(multiplier)} (${factors} factors)")
        }
        
        return multiplier
    }
    
    /**
     * Check if a token is a known winner (for re-entry opportunities)
     */
    fun isKnownWinner(mint: String): Boolean = winningTokens.containsKey(mint)
    
    /**
     * Get past performance of a known winner
     */
    fun getWinnerStats(mint: String): WinningToken? = winningTokens[mint]
    
    /**
     * V3.2: Get memory score for a mint (for pre-score filtering)
     * Returns a score from -20 to +50 based on past performance
     * Negative = losing history, Positive = winning history
     */
    fun getMemoryScoreForMint(mint: String): Int {
        val stats = winningTokens[mint] ?: return 0
        
        return when {
            stats.totalPnl >= 100 -> 50   // Big winner
            stats.totalPnl >= 50 -> 35
            stats.totalPnl >= 20 -> 25
            stats.totalPnl >= 10 -> 15
            stats.totalPnl >= 0 -> 5
            stats.totalPnl >= -10 -> -5
            stats.totalPnl >= -20 -> -10
            stats.totalPnl >= -50 -> -15
            else -> -20  // Big loser
        }
    }
    
    /**
     * Get best performing pattern for display
     */
    fun getBestPatterns(limit: Int = 5): List<Pair<String, PatternStats>> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) -> "$type:$value" to stats }
        }
        .filter { it.second.isReliable }
        .sortedByDescending { it.second.winRate }
        .take(limit)
    }
    
    /**
     * Get worst performing patterns (to avoid)
     */
    fun getWorstPatterns(limit: Int = 5): List<Pair<String, PatternStats>> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) -> "$type:$value" to stats }
        }
        .filter { it.second.isReliable }
        .sortedBy { it.second.winRate }
        .take(limit)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STATS & UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun countPatterns(): Int {
        return patterns.values.sumOf { it.size }
    }
    
    private fun trimWinners() {
        if (winningTokens.size > MAX_WINNERS) {
            // Remove oldest/lowest PnL winners
            val toRemove = winningTokens.values
                .sortedBy { it.totalPnl }
                .take(winningTokens.size - MAX_WINNERS + 500)
                .map { it.mint }
            toRemove.forEach { winningTokens.remove(it) }
        }
    }
    
    fun getStats(): String {
        val totalWinners = winningTokens.size
        val totalPnl = winningTokens.values.sumOf { it.totalPnl }
        val avgPnl = if (totalWinners > 0) totalPnl / totalWinners else 0.0
        val repeatWinners = winningTokens.values.count { it.timesTraded > 1 }
        
        return "Winners: $totalWinners (${repeatWinners} repeat) | Total PnL: +${totalPnl.toInt()}% | Avg: +${avgPnl.toInt()}%"
    }
    
    /**
     * Get a summary of pattern stats for logging
     */
    fun getPatternSummary(): String {
        val totalPatterns = countPatterns()
        val reliablePatterns = patterns.values.sumOf { typeMap -> 
            typeMap.values.count { it.isReliable } 
        }
        
        val bestPatterns = getBestPatterns(3)
        val worstPatterns = getWorstPatterns(3)
        
        val bestStr = bestPatterns.joinToString(", ") { 
            "${it.first.substringAfter(":")}(${(it.second.winRate * 100).toInt()}%)" 
        }
        val worstStr = worstPatterns.joinToString(", ") { 
            "${it.first.substringAfter(":")}(${(it.second.winRate * 100).toInt()}%)" 
        }
        
        return "patterns=$totalPatterns (reliable=$reliablePatterns) | " +
            "winners=${winningTokens.size} | " +
            "best=[$bestStr] | worst=[$worstStr]"
    }
    
    /**
     * Force save current state to storage
     */
    fun forceSave() {
        save()
        ErrorLogger.debug("TokenWinMemory", "💾 Force saved ${winningTokens.size} winners, ${countPatterns()} patterns")
    }
    
    fun logDetailedStats() {
        ErrorLogger.info("TokenWinMemory", """
            |📊 TOKEN WIN MEMORY STATS
            |   Winning tokens: ${winningTokens.size}
            |   Total patterns: ${countPatterns()}
            |   
            |   🏆 TOP PATTERNS:
            |   ${getBestPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
            |   
            |   ⚠️ WORST PATTERNS:
            |   ${getWorstPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
        """.trimMargin())
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════
    
    fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // Save winning tokens
            val winnersJson = JSONArray()
            winningTokens.values.forEach { w ->
                winnersJson.put(JSONObject().apply {
                    put("mint", w.mint)
                    put("symbol", w.symbol)
                    put("name", w.name)
                    put("pnlPercent", w.pnlPercent)
                    put("peakPnl", w.peakPnl)
                    put("entryMcap", w.entryMcap)
                    put("exitMcap", w.exitMcap)
                    put("entryLiquidity", w.entryLiquidity)
                    put("holdTimeMinutes", w.holdTimeMinutes)
                    put("buyPercent", w.buyPercent)
                    put("timestamp", w.timestamp)
                    put("source", w.source)
                    put("phase", w.phase)
                    put("timesTraded", w.timesTraded)
                    put("totalPnl", w.totalPnl)
                })
            }
            
            // Save patterns
            val patternsJson = JSONObject()
            patterns.forEach { (type, typePatterns) ->
                val typeJson = JSONObject()
                typePatterns.forEach { (value, stats) ->
                    typeJson.put(value, JSONObject().apply {
                        put("wins", stats.wins)
                        put("losses", stats.losses)
                        put("totalPnl", stats.totalPnl)
                        put("avgWinPnl", stats.avgWinPnl)
                    })
                }
                patternsJson.put(type, typeJson)
            }
            
            prefs.edit()
                .putString("winners", winnersJson.toString())
                .putString("patterns", patternsJson.toString())
                .apply()
            
            // Also save to persistent storage (survives reinstall)
            PersistentLearning.saveTokenWinMemory(winnersJson.toString(), patternsJson.toString())
            
            ErrorLogger.debug("TokenWinMemory", "💾 Saved ${winningTokens.size} winners, ${countPatterns()} patterns")
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Save failed: ${e.message}")
        }
    }
    
    private fun load() {
        val c = ctx ?: return
        try {
            // First try persistent storage (survives reinstall)
            val persistent = PersistentLearning.loadTokenWinMemory()
            
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val winnersStr = persistent?.first ?: prefs.getString("winners", "[]") ?: "[]"
            val patternsStr = persistent?.second ?: prefs.getString("patterns", "{}") ?: "{}"
            
            // Load winners
            val winnersJson = JSONArray(winnersStr)
            for (i in 0 until winnersJson.length()) {
                val j = winnersJson.getJSONObject(i)
                val w = WinningToken(
                    mint = j.getString("mint"),
                    symbol = j.getString("symbol"),
                    name = j.optString("name", j.getString("symbol")),
                    pnlPercent = j.getDouble("pnlPercent"),
                    peakPnl = j.optDouble("peakPnl", j.getDouble("pnlPercent")),
                    entryMcap = j.getDouble("entryMcap"),
                    exitMcap = j.optDouble("exitMcap", j.getDouble("entryMcap")),
                    entryLiquidity = j.getDouble("entryLiquidity"),
                    holdTimeMinutes = j.getInt("holdTimeMinutes"),
                    buyPercent = j.getDouble("buyPercent"),
                    timestamp = j.getLong("timestamp"),
                    source = j.getString("source"),
                    phase = j.getString("phase"),
                    timesTraded = j.optInt("timesTraded", 1),
                    totalPnl = j.optDouble("totalPnl", j.getDouble("pnlPercent")),
                )
                winningTokens[w.mint] = w
            }
            
            // Load patterns
            val patternsJson = JSONObject(patternsStr)
            patternsJson.keys().forEach { type ->
                val typeJson = patternsJson.getJSONObject(type)
                val typeMap = ConcurrentHashMap<String, PatternStats>()
                typeJson.keys().forEach { value ->
                    val statsJson = typeJson.getJSONObject(value)
                    typeMap[value] = PatternStats(
                        wins = statsJson.getInt("wins"),
                        losses = statsJson.getInt("losses"),
                        totalPnl = statsJson.optDouble("totalPnl", 0.0),
                        avgWinPnl = statsJson.optDouble("avgWinPnl", 0.0),
                    )
                }
                patterns[type] = typeMap
            }
            
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Load failed: ${e.message}")
        }
    }
    
    fun reset() {
        winningTokens.clear()
        patterns.clear()
        creatorStats.clear()
        save()
        ErrorLogger.info("TokenWinMemory", "🔄 Reset all memory")
    }
}
