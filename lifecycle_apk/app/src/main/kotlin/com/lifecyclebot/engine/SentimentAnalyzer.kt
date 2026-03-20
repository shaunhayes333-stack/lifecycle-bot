package com.lifecyclebot.engine

import com.lifecyclebot.data.MentionEvent

/**
 * Keyword-based sentiment scorer.
 *
 * Designed specifically for Solana/crypto Twitter and Telegram language.
 * Uses weighted keyword lists — not ML, but fast and transparent.
 *
 * Returns a score from -1.0 (very bearish/dangerous) to +1.0 (very bullish).
 */
object SentimentAnalyzer {

    // ── Bullish signals ───────────────────────────────────────────────

    private val STRONG_BULLISH = setOf(
        "100x", "1000x", "moon", "mooning", "moonshot", "breakout", "exploding",
        "pumping", "sending", "launching", "gem", "alpha", "early", "undervalued",
        "just launched", "stealth launch", "dev locked", "lp locked", "renounced",
        "parabolic", "ripping", "flying", "bullish af", "easy 10x", "low cap gem",
        "huge move", "next big", "loading", "accumulating", "dip buy", "buy the dip",
        "lfg", "let's go", "letsgoooo", "wagmi", "we're so early", "gigabull",
    )

    private val MILD_BULLISH = setOf(
        "bullish", "long", "buy", "buying", "entry", "good entry", "looking good",
        "bounce", "recovery", "support holding", "holding", "potential", "upside",
        "chart looks good", "volume picking up", "strong buy", "interesting",
        "watching", "on my radar", "dca", "adding", "accumulate", "ath incoming",
        "higher highs", "breakout incoming", "cup and handle", "consolidating",
        "building", "steady", "chart setup", "good project", "legit",
    )

    // ── Bearish / warning signals ─────────────────────────────────────

    private val STRONG_BEARISH = setOf(
        "rug", "rugpull", "rug pull", "scam", "honeypot", "honey pot", "exit scam",
        "dev dumped", "dev sold", "dev wallet", "team dumped", "insider dump",
        "avoid", "stay away", "warning", "caution", "red flag", "red flags",
        "unbuyable", "cant sell", "can't sell", "sell disabled", "blacklisted",
        "contract issue", "exploit", "hacked", "drain", "drained",
        "dump incoming", "getting dumped", "distribution", "top is in",
        "dead cat", "deadcat", "fading", "over", "it's over", "rekt",
    )

    private val MILD_BEARISH = setOf(
        "bearish", "short", "sell", "selling", "dump", "dumping", "dropping",
        "falling", "down only", "bleeding", "fud", "ponzi", "risky", "careful",
        "be careful", "take profit", "tp here", "resistance", "rejected",
        "lower lows", "lower highs", "death cross", "below support",
        "losing momentum", "volume dying", "fading volume", "reversal",
    )

    // ── Pump channel detection (neutral on price but signals hype) ────
    // These are common in Pump.fun / Solana pump channels

    private val PUMP_CHANNEL_SIGNALS = setOf(
        "🚀", "💎", "🔥", "📈", "💰", "🤑", "x10", "x100", "x1000",
        "still early", "get in now", "last chance", "fill your bags",
        "bags packed", "low market cap", "low mcap", "micro cap",
        "just deployed", "just launched", "fresh launch", "new launch",
        "pump incoming", "pre-pump", "sleeping giant", "hidden gem",
        "solana gem", "sol gem", "pump.fun", "pumpfun",
        "community driven", "fair launch", "no team wallet",
    )

    // ── Hard block keywords — never trade if these appear ─────────────

    private val HARD_BLOCK = setOf(
        "honeypot", "honey pot", "rugpull", "rug pull", "exit scam",
        "cant sell", "can't sell", "sell tax 100", "100% tax",
        "blacklisted", "mint authority", "freeze authority enabled",
        "unbuyable", "contract renounced but", "dev dumped all",
        "team wallet selling",
    )

    // ─────────────────────────────────────────────────────────────────

    data class TextScore(
        val score: Double,          // -1.0 to 1.0
        val hardBlock: Boolean,
        val blockReason: String,
        val isPumpChannel: Boolean,
        val matchedBullish: List<String>,
        val matchedBearish: List<String>,
    )

    fun scoreText(text: String): TextScore {
        val lower = text.lowercase()

        // Hard block check first
        val blockMatch = HARD_BLOCK.firstOrNull { lower.contains(it) }
        if (blockMatch != null) {
            return TextScore(
                score         = -1.0,
                hardBlock     = true,
                blockReason   = "Hard block keyword: \"$blockMatch\"",
                isPumpChannel = false,
                matchedBullish = emptyList(),
                matchedBearish = listOf(blockMatch),
            )
        }

        val strongBull  = STRONG_BULLISH.filter { lower.contains(it) }
        val mildBull    = MILD_BULLISH.filter   { lower.contains(it) }
        val strongBear  = STRONG_BEARISH.filter { lower.contains(it) }
        val mildBear    = MILD_BEARISH.filter   { lower.contains(it) }
        val pumpSigs    = PUMP_CHANNEL_SIGNALS.filter { lower.contains(it) }

        val bullScore = (strongBull.size * 0.8 + mildBull.size * 0.3 +
                         pumpSigs.size  * 0.4).coerceAtMost(3.0)
        val bearScore = (strongBear.size * 0.9 + mildBear.size * 0.35)
                         .coerceAtMost(3.0)

        val raw   = (bullScore - bearScore) / 3.0
        val score = raw.coerceIn(-1.0, 1.0)

        return TextScore(
            score          = score,
            hardBlock      = false,
            blockReason    = "",
            isPumpChannel  = pumpSigs.size >= 2,
            matchedBullish = strongBull + mildBull,
            matchedBearish = strongBear + mildBear,
        )
    }

    /**
     * Aggregate multiple mention events into a single [-100, +100] score.
     * Recent events weighted more heavily.
     * Velocity = mentions per minute over last 10 minutes.
     */
    fun aggregate(events: List<MentionEvent>): Triple<Double, Double, Double> {
        if (events.isEmpty()) return Triple(0.0, 0.0, 0.0)

        val now     = System.currentTimeMillis()
        val recent  = events.filter { now - it.ts < 30 * 60_000L }  // last 30 min
        if (recent.isEmpty()) return Triple(0.0, 0.0, 0.0)

        // Weighted average — newer = higher weight
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (e in recent) {
            val ageMins = (now - e.ts) / 60_000.0
            val w       = (1.0 / (1.0 + ageMins * 0.15)).coerceIn(0.1, 1.0)
            weightedSum += e.sentiment * w
            totalWeight += w
        }
        val score = if (totalWeight > 0) (weightedSum / totalWeight * 100.0).coerceIn(-100.0, 100.0) else 0.0

        // Confidence: more events = more confidence, up to 100
        val confidence = minOf(100.0, recent.size * 8.0)

        // Velocity: mentions in last 10 min / 10
        val last10min  = recent.count { now - it.ts < 10 * 60_000L }
        val velocity   = last10min / 10.0

        return Triple(score, confidence, velocity)
    }
}
