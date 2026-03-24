package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * NARRATIVE DETECTOR AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects trending narratives from token names/symbols and tracks which narratives
 * are currently profitable. Meme coins often follow narrative waves:
 * 
 * - AI tokens (GPT, NEURAL, AGENT, etc.)
 * - Political tokens (TRUMP, BIDEN, MAGA, etc.)
 * - Animal memes (DOGE, SHIB, PEPE, CAT, etc.)
 * - Celebrity/Influencer tokens
 * - Tech/Crypto narratives (ETH, BTC, DEFI, etc.)
 * - Food/Object memes (PIZZA, MOON, ROCKET, etc.)
 * - Cultural/Viral memes
 * 
 * The AI learns which narratives are hot RIGHT NOW and boosts entries for tokens
 * matching those narratives. It also tracks narrative fatigue - when a narrative
 * has been overplayed and starts underperforming.
 * 
 * LEARNING:
 * - Records PnL for each narrative category
 * - Tracks recent performance (last 24h rolling window)
 * - Identifies "hot" narratives (recent avg PnL > 20%)
 * - Identifies "cold" narratives (recent avg PnL < -10%)
 * - Adjusts entry scores based on narrative heat
 */
object NarrativeDetectorAI {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NARRATIVE CATEGORIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class Narrative(val label: String, val keywords: List<String>) {
        // AI & Technology
        AI("AI/Tech", listOf(
            "ai", "gpt", "neural", "agent", "brain", "smart", "bot", "auto",
            "chat", "llm", "openai", "anthropic", "claude", "gemini", "copilot",
            "model", "train", "learn", "deep", "machine", "algo", "quantum"
        )),
        
        // Political
        POLITICAL("Political", listOf(
            "trump", "biden", "maga", "usa", "america", "freedom", "patriot",
            "democrat", "republican", "vote", "election", "president", "congress",
            "government", "policy", "liberal", "conservative", "politics"
        )),
        
        // Animal Memes
        ANIMAL("Animal", listOf(
            "doge", "shib", "shiba", "inu", "dog", "cat", "pepe", "frog",
            "bear", "bull", "ape", "monkey", "fish", "whale", "bird", "eagle",
            "lion", "tiger", "wolf", "fox", "bunny", "rabbit", "hamster", "rat"
        )),
        
        // Celebrity/Influencer
        CELEBRITY("Celebrity", listOf(
            "elon", "musk", "kanye", "ye", "drake", "swift", "kim", "kardashian",
            "rogan", "tate", "logan", "jake", "paul", "pewdie", "mr beast",
            "snoop", "eminem", "beyonce", "rihanna", "bieber"
        )),
        
        // Crypto Native
        CRYPTO("Crypto", listOf(
            "btc", "bitcoin", "eth", "ethereum", "sol", "solana", "defi", "nft",
            "web3", "dao", "yield", "stake", "swap", "dex", "cex", "pump",
            "moon", "hodl", "wagmi", "ngmi", "gm", "wen", "lambo", "rekt"
        )),
        
        // Food/Objects
        FOOD_OBJECT("Food/Object", listOf(
            "pizza", "burger", "taco", "sushi", "coffee", "beer", "wine",
            "rocket", "moon", "star", "sun", "fire", "ice", "gold", "diamond",
            "car", "plane", "house", "money", "cash", "coin", "gem"
        )),
        
        // Viral/Cultural
        VIRAL("Viral/Meme", listOf(
            "meme", "lol", "lmao", "bruh", "based", "chad", "wojak", "npc",
            "sigma", "alpha", "beta", "gigachad", "cope", "seethe", "ratio",
            "sus", "amogus", "rick", "roll", "dank", "kek", "stonks", "brrr"
        )),
        
        // Gaming
        GAMING("Gaming", listOf(
            "game", "play", "gamer", "esport", "fortnite", "minecraft", "roblox",
            "mario", "sonic", "pokemon", "zelda", "cod", "gta", "fifa", "nba",
            "xbox", "playstation", "nintendo", "steam", "twitch", "discord"
        )),
        
        // Finance/Business
        FINANCE("Finance", listOf(
            "stock", "trade", "invest", "bank", "fund", "hedge", "wall street",
            "nasdaq", "spy", "options", "calls", "puts", "short", "long",
            "bull", "bear", "market", "profit", "loss", "gain", "rich"
        )),
        
        // Unknown/Other
        UNKNOWN("Unknown", emptyList())
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class NarrativeOutcome(
        val narrative: Narrative,
        val pnlPct: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class NarrativeHeat(
        val narrative: Narrative,
        val recentAvgPnl: Double,
        val recentWinRate: Double,
        val tradeCount: Int,
        val isHot: Boolean,      // Recent avg PnL > 20%
        val isCold: Boolean,     // Recent avg PnL < -10%
        val entryBonus: Double   // Score adjustment
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Rolling window of outcomes (last 24h)
    private val recentOutcomes = java.util.concurrent.ConcurrentLinkedDeque<NarrativeOutcome>()
    private const val WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 hours
    
    // Cached narrative detection for tokens
    private val tokenNarrativeCache = ConcurrentHashMap<String, Narrative>()
    
    // All-time stats per narrative
    private val narrativeStats = ConcurrentHashMap<Narrative, MutableList<Double>>()
    
    // Current hot/cold narratives (refreshed periodically)
    @Volatile private var currentHeat = mapOf<Narrative, NarrativeHeat>()
    @Volatile private var lastHeatUpdate = 0L
    
    // Learning weights - adjusted based on accuracy
    private val narrativeWeights = ConcurrentHashMap<Narrative, Double>().apply {
        Narrative.values().forEach { put(it, 1.0) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // NARRATIVE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Detect which narrative(s) a token belongs to based on name/symbol.
     * Returns the PRIMARY narrative (strongest match).
     */
    fun detectNarrative(symbol: String, name: String = ""): Narrative {
        // Check cache first
        val cacheKey = "${symbol.lowercase()}_${name.lowercase()}"
        tokenNarrativeCache[cacheKey]?.let { return it }
        
        val searchText = "${symbol.lowercase()} ${name.lowercase()}"
        
        // Score each narrative by keyword matches
        val scores = mutableMapOf<Narrative, Int>()
        
        for (narrative in Narrative.values()) {
            if (narrative == Narrative.UNKNOWN) continue
            
            var score = 0
            for (keyword in narrative.keywords) {
                if (searchText.contains(keyword)) {
                    // Exact match = 3 points, partial = 1 point
                    score += if (searchText.split(" ", "_", "-").any { it == keyword }) 3 else 1
                }
            }
            if (score > 0) scores[narrative] = score
        }
        
        // Return highest scoring narrative, or UNKNOWN
        val detected = scores.maxByOrNull { it.value }?.key ?: Narrative.UNKNOWN
        
        // Cache result
        tokenNarrativeCache[cacheKey] = detected
        
        return detected
    }
    
    /**
     * Get all matching narratives for a token (may belong to multiple).
     */
    fun detectAllNarratives(symbol: String, name: String = ""): List<Narrative> {
        val searchText = "${symbol.lowercase()} ${name.lowercase()}"
        
        return Narrative.values().filter { narrative ->
            if (narrative == Narrative.UNKNOWN) return@filter false
            narrative.keywords.any { keyword -> searchText.contains(keyword) }
        }.ifEmpty { listOf(Narrative.UNKNOWN) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HEAT CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Refresh narrative heat calculations.
     * Called periodically from BotService.
     */
    fun refreshHeat() {
        // Clean old outcomes
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (recentOutcomes.isNotEmpty() && (recentOutcomes.peekFirst()?.timestamp ?: 0) < cutoff) {
            recentOutcomes.pollFirst()
        }
        
        // Calculate heat for each narrative
        val heat = mutableMapOf<Narrative, NarrativeHeat>()
        
        for (narrative in Narrative.values()) {
            val outcomes = recentOutcomes.filter { it.narrative == narrative }
            
            if (outcomes.isEmpty()) {
                heat[narrative] = NarrativeHeat(
                    narrative = narrative,
                    recentAvgPnl = 0.0,
                    recentWinRate = 0.0,
                    tradeCount = 0,
                    isHot = false,
                    isCold = false,
                    entryBonus = 0.0
                )
                continue
            }
            
            val avgPnl = outcomes.map { it.pnlPct }.average()
            val winRate = outcomes.count { it.pnlPct > 0 }.toDouble() / outcomes.size * 100
            val weight = narrativeWeights[narrative] ?: 1.0
            
            // Determine hot/cold status
            val isHot = avgPnl > 20.0 && outcomes.size >= 3
            val isCold = avgPnl < -10.0 && outcomes.size >= 3
            
            // Calculate entry bonus (scaled by weight and outcome count)
            val confidence = minOf(outcomes.size / 10.0, 1.0)  // More trades = more confidence
            val entryBonus = when {
                isHot -> minOf(avgPnl * 0.3, 15.0) * confidence * weight   // Up to +15
                isCold -> maxOf(avgPnl * 0.5, -10.0) * confidence * weight // Down to -10
                avgPnl > 5.0 -> avgPnl * 0.2 * confidence * weight         // Slight boost
                avgPnl < -5.0 -> avgPnl * 0.3 * confidence * weight        // Slight penalty
                else -> 0.0
            }
            
            heat[narrative] = NarrativeHeat(
                narrative = narrative,
                recentAvgPnl = avgPnl,
                recentWinRate = winRate,
                tradeCount = outcomes.size,
                isHot = isHot,
                isCold = isCold,
                entryBonus = entryBonus
            )
        }
        
        currentHeat = heat
        lastHeatUpdate = System.currentTimeMillis()
    }
    
    /**
     * Get current heat for a narrative.
     */
    fun getNarrativeHeat(narrative: Narrative): NarrativeHeat {
        // Refresh if stale (> 5 min)
        if (System.currentTimeMillis() - lastHeatUpdate > 5 * 60 * 1000) {
            refreshHeat()
        }
        return currentHeat[narrative] ?: NarrativeHeat(
            narrative = narrative,
            recentAvgPnl = 0.0,
            recentWinRate = 0.0,
            tradeCount = 0,
            isHot = false,
            isCold = false,
            entryBonus = 0.0
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRY SCORE ADJUSTMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get entry score adjustment for a token based on its narrative(s).
     * Returns bonus/penalty to add to entry score.
     */
    fun getEntryScoreAdjustment(symbol: String, name: String = ""): Double {
        val narrative = detectNarrative(symbol, name)
        val heat = getNarrativeHeat(narrative)
        return heat.entryBonus
    }
    
    /**
     * Check if token matches any currently hot narratives.
     */
    fun isHotNarrative(symbol: String, name: String = ""): Boolean {
        val narrative = detectNarrative(symbol, name)
        return getNarrativeHeat(narrative).isHot
    }
    
    /**
     * Check if token matches any currently cold narratives.
     */
    fun isColdNarrative(symbol: String, name: String = ""): Boolean {
        val narrative = detectNarrative(symbol, name)
        return getNarrativeHeat(narrative).isCold
    }
    
    /**
     * Get list of currently hot narratives.
     */
    fun getHotNarratives(): List<Narrative> {
        if (System.currentTimeMillis() - lastHeatUpdate > 5 * 60 * 1000) {
            refreshHeat()
        }
        return currentHeat.values.filter { it.isHot }.map { it.narrative }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record trade outcome for narrative learning.
     */
    fun recordOutcome(symbol: String, name: String, pnlPct: Double) {
        val narrative = detectNarrative(symbol, name)
        
        // Add to rolling window
        recentOutcomes.addLast(NarrativeOutcome(narrative, pnlPct))
        
        // Add to all-time stats
        narrativeStats.getOrPut(narrative) { mutableListOf() }.add(pnlPct)
        
        // Adjust weight based on outcome
        val currentWeight = narrativeWeights[narrative] ?: 1.0
        val adjustment = when {
            pnlPct > 50.0 -> 0.05   // Big win = trust this narrative more
            pnlPct > 10.0 -> 0.02   // Win = slight trust increase
            pnlPct < -30.0 -> -0.05 // Big loss = trust less
            pnlPct < -10.0 -> -0.02 // Loss = slight trust decrease
            else -> 0.0
        }
        narrativeWeights[narrative] = (currentWeight + adjustment).coerceIn(0.5, 2.0)
        
        // Log significant narrative trades
        if (kotlin.math.abs(pnlPct) > 20.0) {
            val heat = getNarrativeHeat(narrative)
            ErrorLogger.info("NarrativeAI", "📖 $symbol [${narrative.label}]: ${pnlPct.toInt()}% | " +
                "narrative_avg=${heat.recentAvgPnl.toInt()}% hot=${heat.isHot} weight=${currentWeight.fmt(2)}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        val hotNarratives = getHotNarratives()
        val coldNarratives = currentHeat.values.filter { it.isCold }.map { it.narrative }
        val totalTrades = recentOutcomes.size
        
        return buildString {
            append("NarrativeAI: ")
            append("trades_24h=$totalTrades ")
            if (hotNarratives.isNotEmpty()) {
                append("HOT=[${hotNarratives.joinToString(",") { it.label }}] ")
            }
            if (coldNarratives.isNotEmpty()) {
                append("COLD=[${coldNarratives.joinToString(",") { it.label }}] ")
            }
            if (hotNarratives.isEmpty() && coldNarratives.isEmpty()) {
                append("no_strong_trends")
            }
        }
    }
    
    fun getDetailedStats(): String {
        return buildString {
            appendLine("═══ NARRATIVE HEAT MAP ═══")
            for ((narrative, heat) in currentHeat.entries.sortedByDescending { it.value.recentAvgPnl }) {
                if (heat.tradeCount == 0) continue
                val status = when {
                    heat.isHot -> "🔥HOT"
                    heat.isCold -> "❄️COLD"
                    else -> "➖"
                }
                appendLine("${narrative.label}: avg=${heat.recentAvgPnl.toInt()}% " +
                    "wr=${heat.recentWinRate.toInt()}% n=${heat.tradeCount} $status")
            }
        }
    }
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            // Save recent outcomes
            val outcomesArray = JSONArray()
            recentOutcomes.forEach { outcome ->
                outcomesArray.put(JSONObject().apply {
                    put("narrative", outcome.narrative.name)
                    put("pnl", outcome.pnlPct)
                    put("ts", outcome.timestamp)
                })
            }
            put("outcomes", outcomesArray)
            
            // Save weights
            val weightsObj = JSONObject()
            narrativeWeights.forEach { (narrative, weight) ->
                weightsObj.put(narrative.name, weight)
            }
            put("weights", weightsObj)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            // Load outcomes
            recentOutcomes.clear()
            val outcomesArray = json.optJSONArray("outcomes")
            if (outcomesArray != null) {
                for (i in 0 until outcomesArray.length()) {
                    val obj = outcomesArray.getJSONObject(i)
                    val narrative = try {
                        Narrative.valueOf(obj.getString("narrative"))
                    } catch (_: Exception) { Narrative.UNKNOWN }
                    recentOutcomes.addLast(NarrativeOutcome(
                        narrative = narrative,
                        pnlPct = obj.getDouble("pnl"),
                        timestamp = obj.getLong("ts")
                    ))
                }
            }
            
            // Load weights
            val weightsObj = json.optJSONObject("weights")
            if (weightsObj != null) {
                weightsObj.keys().forEach { key ->
                    try {
                        val narrative = Narrative.valueOf(key)
                        narrativeWeights[narrative] = weightsObj.getDouble(key)
                    } catch (_: Exception) {}
                }
            }
            
            // Refresh heat after loading
            refreshHeat()
            
            ErrorLogger.info("NarrativeAI", "Loaded ${recentOutcomes.size} outcomes")
        } catch (e: Exception) {
            ErrorLogger.error("NarrativeAI", "Failed to load: ${e.message}", e)
        }
    }
    
    fun cleanup() {
        // Remove old outcomes
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (recentOutcomes.isNotEmpty() && (recentOutcomes.peekFirst()?.timestamp ?: 0) < cutoff) {
            recentOutcomes.pollFirst()
        }
        
        // Clear old cache entries (keep last 1000)
        if (tokenNarrativeCache.size > 1000) {
            val toRemove = tokenNarrativeCache.keys.take(500)
            toRemove.forEach { tokenNarrativeCache.remove(it) }
        }
    }
    
    private fun Double.fmt(decimals: Int) = "%.${decimals}f".format(this)
}
