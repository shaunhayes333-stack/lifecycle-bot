package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TokenWinMemory
 *
 * Decisive-trade classification:
 * - WIN  = pnlPct >= 0.5
 * - LOSS = pnlPct <= -2.0
 * - Anything between is SCRATCH and is ignored for memory learning
 */
object TokenWinMemory {

    private const val PREFS_NAME = "token_win_memory"
    private const val KEY_WINNERS = "winners"
    private const val KEY_PATTERNS = "patterns"
    private const val KEY_CREATORS = "creators"
    private const val KEY_TOKEN_STATS = "token_stats"

    private const val MAX_WINNERS = 5000
    private const val MAX_PATTERNS = 1000

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    private var ctx: Context? = null

    // ═══════════════════════════════════════════════════════════════════════
    // WINNING TOKEN RECORDS
    // ═══════════════════════════════════════════════════════════════════════

    data class WinningToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val pnlPercent: Double,
        val peakPnl: Double,
        val entryMcap: Double,
        val exitMcap: Double,
        val entryLiquidity: Double,
        val holdTimeMinutes: Int,
        val buyPercent: Double,
        val timestamp: Long,
        val source: String,
        val phase: String,
        var timesTraded: Int = 1,
        var totalPnl: Double = pnlPercent,
    )

    data class PatternStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var avgWinPnl: Double = 0.0,
    ) {
        val winRate: Double
            get() = if (wins + losses > 0) wins.toDouble() / (wins + losses).toDouble() else 0.5

        val isReliable: Boolean
            get() = wins + losses >= 5
    }

    data class TokenStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
        var bestPnl: Double = Double.NEGATIVE_INFINITY,
        var lastPnl: Double = 0.0,
        var lastSeen: Long = 0L,
    ) {
        val decisiveTrades: Int
            get() = wins + losses

        val winRate: Double
            get() = if (decisiveTrades > 0) wins.toDouble() / decisiveTrades.toDouble() else 0.5
    }

    // Mint -> WinningToken
    private val winningTokens = ConcurrentHashMap<String, WinningToken>()

    // Pattern type -> pattern value -> stats
    private val patterns = ConcurrentHashMap<String, ConcurrentHashMap<String, PatternStats>>()

    // Creator address -> stats
    private val creatorStats = ConcurrentHashMap<String, PatternStats>()

    // Mint -> decisive trade history
    private val tokenStats = ConcurrentHashMap<String, TokenStats>()

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(
            "TokenWinMemory",
            "📊 Loaded: ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
        )
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
        val isWin = isWinPnl(pnlPercent)
        val isLoss = isLossPnl(pnlPercent)
        val isScratch = !isWin && !isLoss

        if (isScratch) {
            ErrorLogger.debug(
                "TokenWinMemory",
                "$symbol: Scratch trade (${pnlPercent.toInt()}%) - not recorded"
            )
            return
        }

        val now = System.currentTimeMillis()

        // ── Track exact mint stats (wins and losses) ──────────────────────
        val mintStats = tokenStats.getOrPut(mint) { TokenStats() }
        if (isWin) {
            mintStats.wins++
        } else {
            mintStats.losses++
        }
        mintStats.totalPnl += pnlPercent
        mintStats.bestPnl = maxOf(mintStats.bestPnl, pnlPercent)
        mintStats.lastPnl = pnlPercent
        mintStats.lastSeen = now

        // ── Record / update winning token memory ──────────────────────────
        val existingWinner = winningTokens[mint]

        if (isWin) {
            if (existingWinner != null) {
                existingWinner.timesTraded++
                existingWinner.totalPnl += pnlPercent
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🏆 REPEAT WINNER: $symbol | +${pnlPercent.toInt()}% | total: +${existingWinner.totalPnl.toInt()}% over ${existingWinner.timesTraded} trades"
                )
            } else {
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
                    timestamp = now,
                    source = source,
                    phase = phase,
                )
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🏆 NEW WINNER: $symbol | +${pnlPercent.toInt()}% | mcap: $${entryMcap.toInt()} → $${exitMcap.toInt()}"
                )
                trimWinners()
            }
        } else if (existingWinner != null) {
            existingWinner.timesTraded++
            existingWinner.totalPnl += pnlPercent
            ErrorLogger.debug(
                "TokenWinMemory",
                "📉 KNOWN WINNER LOST: $symbol | ${pnlPercent.toInt()}% | cumulative: ${existingWinner.totalPnl.toInt()}%"
            )
        }

        // ── Learn patterns from decisive trades only ──────────────────────
        learnPatterns(
            symbol = symbol,
            name = name,
            mcap = entryMcap,
            liquidity = entryLiquidity,
            buyPercent = buyPercent,
            phase = phase,
            source = source,
            isWin = isWin,
            pnl = pnlPercent,
        )

        // ── Track creator stats ───────────────────────────────────────────
        if (!creatorAddress.isNullOrBlank()) {
            val stats = creatorStats.getOrPut(creatorAddress) { PatternStats() }
            if (isWin) {
                stats.wins++
                stats.totalPnl += pnlPercent
                stats.avgWinPnl = stats.totalPnl / stats.wins.toDouble()
            } else {
                stats.losses++
            }
        }

        if (countPatterns() > MAX_PATTERNS + 50) {
            trimPatterns()
        }

        if ((winningTokens.size + tokenStats.size + countPatterns()) % 10 == 0) {
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
        extractNamePatterns(name.lowercase()).forEach { pattern ->
            recordPattern("name_contains", pattern, isWin, pnl)
        }

        extractSymbolPatterns(symbol.uppercase()).forEach { pattern ->
            recordPattern("symbol_pattern", pattern, isWin, pnl)
        }

        val mcapBucket = when {
            mcap < 20_000.0 -> "micro_<20k"
            mcap < 50_000.0 -> "small_20k_50k"
            mcap < 100_000.0 -> "mid_50k_100k"
            mcap < 500_000.0 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        recordPattern("mcap_bucket", mcapBucket, isWin, pnl)

        val liqRatio = if (mcap > 0.0) liquidity / mcap else 0.0
        val liqBucket = when {
            liqRatio < 0.1 -> "liq_ratio_<10%"
            liqRatio < 0.3 -> "liq_ratio_10_30%"
            liqRatio < 0.5 -> "liq_ratio_30_50%"
            else -> "liq_ratio_50%+"
        }
        recordPattern("liq_ratio", liqBucket, isWin, pnl)

        val buyBucket = when {
            buyPercent < 40.0 -> "buy_weak_<40%"
            buyPercent < 55.0 -> "buy_neutral_40_55%"
            buyPercent < 70.0 -> "buy_strong_55_70%"
            else -> "buy_fomo_70%+"
        }
        recordPattern("buy_pressure", buyBucket, isWin, pnl)

        recordPattern("phase", phase, isWin, pnl)
        recordPattern("source", source, isWin, pnl)
    }

    private fun recordPattern(type: String, value: String, isWin: Boolean, pnl: Double) {
        val typeMap = patterns.getOrPut(type) { ConcurrentHashMap() }
        val stats = typeMap.getOrPut(value) { PatternStats() }

        if (isWin) {
            stats.wins++
            stats.totalPnl += pnl
            stats.avgWinPnl = stats.totalPnl / stats.wins.toDouble()
        } else {
            stats.losses++
        }
    }

    private fun extractNamePatterns(name: String): List<String> {
        val found = mutableListOf<String>()

        val themes = listOf(
            "ai", "gpt", "agent", "bot",
            "pepe", "frog", "wojak", "chad",
            "doge", "shib", "inu", "dog", "cat",
            "elon", "trump", "biden", "musk",
            "moon", "rocket", "mars", "space",
            "baby", "mini", "mega", "giga", "ultra",
            "safe", "fair", "based", "wagmi",
            "sol", "solana", "pump", "bonk",
            "100x", "1000x", "gem",
        )

        themes.forEach { theme ->
            if (name.contains(theme)) {
                found.add("theme_$theme")
            }
        }

        val lengthBucket = when {
            name.length <= 4 -> "name_short"
            name.length <= 8 -> "name_medium"
            else -> "name_long"
        }
        found.add(lengthBucket)

        return found
    }

    private fun extractSymbolPatterns(symbol: String): List<String> {
        val found = mutableListOf<String>()

        val lenBucket = when {
            symbol.length <= 3 -> "sym_short"
            symbol.length <= 5 -> "sym_medium"
            else -> "sym_long"
        }
        found.add(lenBucket)

        if (symbol.all { it.isUpperCase() || it.isDigit() }) {
            found.add("sym_standard")
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY
    // ═══════════════════════════════════════════════════════════════════════

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

        val exactStats = tokenStats[mint]
        if (exactStats != null && exactStats.decisiveTrades > 0) {
            val exactScore = when {
                exactStats.totalPnl >= 100.0 -> 50.0
                exactStats.totalPnl >= 50.0 -> 35.0
                exactStats.totalPnl >= 20.0 -> 25.0
                exactStats.totalPnl >= 10.0 -> 15.0
                exactStats.totalPnl >= 0.5 -> 8.0
                exactStats.totalPnl <= -50.0 -> -25.0
                exactStats.totalPnl <= -20.0 -> -15.0
                exactStats.totalPnl <= -5.0 -> -8.0
                else -> 0.0
            }

            score += exactScore
            factors++

            if (exactScore > 0.0) {
                ErrorLogger.info(
                    "TokenWinMemory",
                    "🔥 KNOWN WINNER DETECTED: $symbol | total pnl: ${exactStats.totalPnl.toInt()}% | boost: +$exactScore"
                )
            } else if (exactScore < 0.0) {
                ErrorLogger.debug(
                    "TokenWinMemory",
                    "⚠️ KNOWN LOSER DETECTED: $symbol | total pnl: ${exactStats.totalPnl.toInt()}% | penalty: $exactScore"
                )
            }
        }

        extractNamePatterns(name.lowercase()).forEach { pattern ->
            val stats = patterns["name_contains"]?.get(pattern)
            if (stats != null && stats.isReliable) {
                score += (stats.winRate - 0.5) * 20.0
                factors++
            }
        }

        val mcapBucket = when {
            mcap < 20_000.0 -> "micro_<20k"
            mcap < 50_000.0 -> "small_20k_50k"
            mcap < 100_000.0 -> "mid_50k_100k"
            mcap < 500_000.0 -> "large_100k_500k"
            else -> "mega_500k+"
        }
        patterns["mcap_bucket"]?.get(mcapBucket)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 15.0
                factors++
            }
        }

        patterns["phase"]?.get(phase)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 15.0
                factors++
            }
        }

        patterns["source"]?.get(source)?.let { stats ->
            if (stats.isReliable) {
                score += (stats.winRate - 0.5) * 10.0
                factors++
            }
        }

        val multiplier = when {
            score >= 30.0 -> 2.0
            score >= 15.0 -> 1.5
            score >= 5.0 -> 1.2
            score >= -5.0 -> 1.0
            score >= -15.0 -> 0.8
            else -> 0.5
        }

        if (factors > 0) {
            ErrorLogger.debug(
                "TokenWinMemory",
                "$symbol: memory score=${"%.1f".format(score)} multiplier=${"%.2f".format(multiplier)} ($factors factors)"
            )
        }

        return multiplier
    }

    fun isKnownWinner(mint: String): Boolean = winningTokens.containsKey(mint)

    fun getWinnerStats(mint: String): WinningToken? = winningTokens[mint]

    fun getMemoryScoreForMint(mint: String): Int {
        val stats = tokenStats[mint] ?: return 0

        return when {
            stats.totalPnl >= 100.0 -> 50
            stats.totalPnl >= 50.0 -> 35
            stats.totalPnl >= 20.0 -> 25
            stats.totalPnl >= 10.0 -> 15
            stats.totalPnl >= 0.5 -> 5
            stats.totalPnl <= -50.0 -> -20
            stats.totalPnl <= -20.0 -> -15
            stats.totalPnl <= -10.0 -> -10
            stats.totalPnl <= -5.0 -> -5
            else -> 0
        }
    }

    fun getBestPatterns(limit: Int = 5): List<Pair<String, PatternStats>> {
        return patterns.flatMap { (type, typePatterns) ->
            typePatterns.map { (value, stats) -> "$type:$value" to stats }
        }
            .filter { it.second.isReliable }
            .sortedByDescending { it.second.winRate }
            .take(limit)
    }

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
        if (winningTokens.size <= MAX_WINNERS) return

        val toRemove = winningTokens.values
            .sortedBy { it.totalPnl }
            .take(winningTokens.size - MAX_WINNERS + 500)
            .map { it.mint }

        toRemove.forEach { winningTokens.remove(it) }
    }

    private fun trimPatterns() {
        val total = countPatterns()
        if (total <= MAX_PATTERNS) return

        val allPatterns = mutableListOf<Triple<String, String, PatternStats>>()
        patterns.forEach { (type, typePatterns) ->
            typePatterns.forEach { (value, stats) ->
                allPatterns.add(Triple(type, value, stats))
            }
        }

        val toRemove = allPatterns
            .sortedWith(
                compareBy<Triple<String, String, PatternStats>>(
                    { it.third.wins + it.third.losses },
                    { it.third.totalPnl }
                )
            )
            .take(total - MAX_PATTERNS)

        toRemove.forEach { (type, value, _) ->
            patterns[type]?.remove(value)
            if (patterns[type]?.isEmpty() == true) {
                patterns.remove(type)
            }
        }
    }

    fun getStats(): String {
        val totalWinners = winningTokens.size
        val totalPnl = winningTokens.values.sumOf { it.totalPnl }
        val avgPnl = if (totalWinners > 0) totalPnl / totalWinners.toDouble() else 0.0
        val repeatWinners = winningTokens.values.count { it.timesTraded > 1 }

        return "Winners: $totalWinners ($repeatWinners repeat) | Total PnL: ${totalPnl.toInt()}% | Avg: ${avgPnl.toInt()}%"
    }

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
            "winners=${winningTokens.size} | best=[$bestStr] | worst=[$worstStr]"
    }

    fun forceSave() {
        save()
        ErrorLogger.debug(
            "TokenWinMemory",
            "💾 Force saved ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
        )
    }

    fun logDetailedStats() {
        ErrorLogger.info(
            "TokenWinMemory",
            """
            |📊 TOKEN WIN MEMORY STATS
            |   Winning tokens: ${winningTokens.size}
            |   Exact token stats: ${tokenStats.size}
            |   Total patterns: ${countPatterns()}
            |
            |   🏆 TOP PATTERNS:
            |   ${getBestPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
            |
            |   ⚠️ WORST PATTERNS:
            |   ${getWorstPatterns(5).joinToString("\n|   ") { "${it.first}: ${(it.second.winRate * 100).toInt()}% win rate (${it.second.wins}W/${it.second.losses}L)" }}
            """.trimMargin()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

            val creatorsJson = JSONObject()
            creatorStats.forEach { (creator, stats) ->
                creatorsJson.put(creator, JSONObject().apply {
                    put("wins", stats.wins)
                    put("losses", stats.losses)
                    put("totalPnl", stats.totalPnl)
                    put("avgWinPnl", stats.avgWinPnl)
                })
            }

            val tokenStatsJson = JSONObject()
            tokenStats.forEach { (mint, stats) ->
                tokenStatsJson.put(mint, JSONObject().apply {
                    put("wins", stats.wins)
                    put("losses", stats.losses)
                    put("totalPnl", stats.totalPnl)
                    put("bestPnl", stats.bestPnl)
                    put("lastPnl", stats.lastPnl)
                    put("lastSeen", stats.lastSeen)
                })
            }

            prefs.edit()
                .putString(KEY_WINNERS, winnersJson.toString())
                .putString(KEY_PATTERNS, patternsJson.toString())
                .putString(KEY_CREATORS, creatorsJson.toString())
                .putString(KEY_TOKEN_STATS, tokenStatsJson.toString())
                .apply()

            PersistentLearning.saveTokenWinMemory(
                winnersJson.toString(),
                patternsJson.toString()
            )

            ErrorLogger.debug(
                "TokenWinMemory",
                "💾 Saved ${winningTokens.size} winners, ${tokenStats.size} token stats, ${countPatterns()} patterns"
            )
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Save failed: ${e.message}")
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            winningTokens.clear()
            patterns.clear()
            creatorStats.clear()
            tokenStats.clear()

            val persistent = PersistentLearning.loadTokenWinMemory()
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val winnersStr = persistent?.first ?: prefs.getString(KEY_WINNERS, "[]") ?: "[]"
            val patternsStr = persistent?.second ?: prefs.getString(KEY_PATTERNS, "{}") ?: "{}"
            val creatorsStr = prefs.getString(KEY_CREATORS, "{}") ?: "{}"
            val tokenStatsStr = prefs.getString(KEY_TOKEN_STATS, "{}") ?: "{}"

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

            val patternsJson = JSONObject(patternsStr)
            val patternTypeKeys = patternsJson.keys()
            while (patternTypeKeys.hasNext()) {
                val type = patternTypeKeys.next()
                val typeJson = patternsJson.getJSONObject(type)
                val typeMap = ConcurrentHashMap<String, PatternStats>()
                val valueKeys = typeJson.keys()
                while (valueKeys.hasNext()) {
                    val value = valueKeys.next()
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

            val creatorsJson = JSONObject(creatorsStr)
            val creatorKeys = creatorsJson.keys()
            while (creatorKeys.hasNext()) {
                val creator = creatorKeys.next()
                val statsJson = creatorsJson.getJSONObject(creator)
                creatorStats[creator] = PatternStats(
                    wins = statsJson.getInt("wins"),
                    losses = statsJson.getInt("losses"),
                    totalPnl = statsJson.optDouble("totalPnl", 0.0),
                    avgWinPnl = statsJson.optDouble("avgWinPnl", 0.0),
                )
            }

            val tokenStatsJson = JSONObject(tokenStatsStr)
            val tokenKeys = tokenStatsJson.keys()
            while (tokenKeys.hasNext()) {
                val mint = tokenKeys.next()
                val statsJson = tokenStatsJson.getJSONObject(mint)
                tokenStats[mint] = TokenStats(
                    wins = statsJson.getInt("wins"),
                    losses = statsJson.getInt("losses"),
                    totalPnl = statsJson.optDouble("totalPnl", 0.0),
                    bestPnl = statsJson.optDouble("bestPnl", Double.NEGATIVE_INFINITY),
                    lastPnl = statsJson.optDouble("lastPnl", 0.0),
                    lastSeen = statsJson.optLong("lastSeen", 0L),
                )
            }

            // Backfill tokenStats from winners if older storage had no token stats saved
            if (tokenStats.isEmpty() && winningTokens.isNotEmpty()) {
                winningTokens.values.forEach { winner ->
                    tokenStats[winner.mint] = TokenStats(
                        wins = winner.timesTraded.coerceAtLeast(1),
                        losses = 0,
                        totalPnl = winner.totalPnl,
                        bestPnl = winner.peakPnl,
                        lastPnl = winner.pnlPercent,
                        lastSeen = winner.timestamp,
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("TokenWinMemory", "Load failed: ${e.message}")
        }
    }

    fun reset() {
        winningTokens.clear()
        patterns.clear()
        creatorStats.clear()
        tokenStats.clear()
        save()
        ErrorLogger.info("TokenWinMemory", "🔄 Reset all memory")
    }

    private fun isWinPnl(pnlPercent: Double): Boolean = pnlPercent >= WIN_THRESHOLD_PCT

    private fun isLossPnl(pnlPercent: Double): Boolean = pnlPercent <= LOSS_THRESHOLD_PCT
}