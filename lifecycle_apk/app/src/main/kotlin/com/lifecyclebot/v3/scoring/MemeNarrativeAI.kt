package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * V5.9.404 — NarrativeAI
 *
 * The symbolic reading layer. Memecoins are linguistic objects as much as
 * financial ones — the symbol/name *is* the alpha. NarrativeAI clusters tokens
 * by memetic theme so the Moonshot scorer can lean into clusters that are
 * "alive" right now (see CultMomentumAI for the temporal half).
 *
 * Output: a `(cluster, baseBonus)` pair that scoreToken adds to its composite.
 * Defaults to (`UNKNOWN`, 0) when nothing matches.
 *
 * Clusters are intentionally simple keyword sets. The LLM layer can review the
 * deterministic narrative classification asynchronously via SentienceHooks;
 * V5.0.6678 consumes that cached review as a bounded score shape rather than
 * merely logging that an LLM review was requested.
 */
object MemeNarrativeAI {

    private const val TAG = "NarrativeAI"

    enum class Cluster(val emoji: String, val baseBonus: Int) {
        FROG     ("🐸",  8),    // pepe-class
        DOG      ("🐶",  8),    // doge/shiba/bonk-class
        CAT      ("🐱",  6),
        ANIMAL   ("🦁",  4),    // generic animals
        POLITICAL("🇺🇸", 6),    // trump/biden/putin/etc
        AI_THEME ("🤖",  6),    // grok/agi/chatgpt-class
        CULT     ("⛪",  6),    // jesus/satan/baby-jesus/wojak-class
        FOOD     ("🍔",  4),
        COLOR    ("🎨",  3),
        ROCKET   ("🚀",  5),    // moon/rocket/launch-class
        NUMBER   ("💯",  3),    // 100x/1000/420/69-class
        UNKNOWN  ("•",   0);

        companion object {
            fun parse(s: String?): Cluster = try {
                values().firstOrNull { it.name.equals(s, true) } ?: UNKNOWN
            } catch (_: Throwable) { UNKNOWN }
        }
    }

    data class NarrativeMatch(
        val cluster: Cluster,
        val baseBonus: Int,
        val matchedKeyword: String,
        val confidence: Int,   // 0..100
    )

    // Keyword dictionaries — case-insensitive substring match.
    private val DICT: Map<Cluster, List<String>> = mapOf(
        Cluster.FROG      to listOf("pepe", "frog", "frg", "kek", "ribbit", "wojak"),
        Cluster.DOG       to listOf("doge", "shib", "shiba", "bonk", "wif", "inu", "puppy", "pup", "woof"),
        Cluster.CAT       to listOf("cat", "kitty", "meow", "neko", "purr", "feline"),
        Cluster.ANIMAL    to listOf("monkey", "ape", "bear", "tiger", "lion", "wolf", "fox", "shark", "frog", "bird", "owl", "eagle"),
        Cluster.POLITICAL to listOf("trump", "biden", "maga", "potus", "elon", "musk", "putin", "kim", "vivek"),
        Cluster.AI_THEME  to listOf("ai", "gpt", "agi", "grok", "neural", "claude", "gemini", "anthropic", "openai", "meta", "llm"),
        Cluster.CULT      to listOf("jesus", "christ", "satan", "demon", "angel", "god", "godl", "cult", "wojak", "sigma", "based"),
        Cluster.FOOD      to listOf("pizza", "burger", "taco", "sushi", "ramen", "soup", "egg", "bacon", "beer", "milk"),
        Cluster.COLOR     to listOf("blue", "red", "green", "black", "white", "pink", "yellow", "orange", "purple"),
        Cluster.ROCKET    to listOf("moon", "rocket", "launch", "stars", "lambo", "mars", "saturn", "galaxy", "rip", "1000x"),
        Cluster.NUMBER    to listOf("100x", "1000x", "420", "69", "777", "888", "wagmi", "ngmi"),
    )

    /**
     * Detect the dominant memetic cluster for a symbol/name pair.
     * Returns NarrativeMatch.UNKNOWN if nothing recognisable.
     */
    fun detect(symbol: String, name: String? = null): NarrativeMatch {
        val haystack = (symbol + " " + (name ?: "")).lowercase()
        if (haystack.isBlank()) return UNKNOWN_MATCH

        var best: Cluster = Cluster.UNKNOWN
        var bestKw: String = ""
        var bestKwLen = 0
        for ((cluster, words) in DICT) {
            for (w in words) {
                if (w.length < 2) continue
                if (haystack.contains(w)) {
                    if (w.length > bestKwLen) {
                        best = cluster
                        bestKw = w
                        bestKwLen = w.length
                    }
                }
            }
        }
        if (best == Cluster.UNKNOWN) return UNKNOWN_MATCH

        val confidence = when {
            bestKwLen >= 6 -> 90
            bestKwLen >= 4 -> 70
            bestKwLen >= 3 -> 55
            else           -> 40
        }

        // V5.0.6678 §TRADE_QUALITY_SENTIENCE_LOOP
        // The pre-existing hook was log-only, so the LLM could not influence an
        // entry even after V5.0.6677 restored provider connectivity. Prime the
        // async single-flight review for meaningful narrative matches, then use
        // any already-cached vote as a bounded score shape. Cache miss remains
        // neutral; no network call blocks this scorer.
        if (confidence >= 55) {
            try {
                com.lifecyclebot.engine.SentienceHooks.requestLlmMemeBuy(
                    symbol = symbol,
                    sizeSol = 0.0,
                    reason = "${best.emoji} ${best.name} cluster (kw=$bestKw conf=$confidence)",
                    score = confidence,
                    confidence = confidence,
                )
            } catch (_: Throwable) { /* fail-open */ }
        }
        val llmBias6678 = try {
            com.lifecyclebot.engine.SentienceHooks.entryQualityScoreBias6678(symbol)
        } catch (_: Throwable) { 0 }
        val shapedBonus6678 = (best.baseBonus + llmBias6678).coerceIn(-10, 12)
        if (llmBias6678 != 0) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_ENTRY_SCORE_SHAPED_6678")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "LLM_ENTRY_SCORE_SHAPED_6678",
                    "symbol=$symbol cluster=${best.name} base=${best.baseBonus} llmBias=$llmBias6678 final=$shapedBonus6678 vote=${com.lifecyclebot.engine.SentienceHooks.llmVote(symbol)}",
                )
            } catch (_: Throwable) {}
        }

        return NarrativeMatch(
            cluster = best,
            baseBonus = shapedBonus6678,
            matchedKeyword = bestKw,
            confidence = confidence,
        )
    }

    private val UNKNOWN_MATCH = NarrativeMatch(Cluster.UNKNOWN, 0, "", 0)

    // ────────────────────────────────────────────────────────────────────
    // Per-cluster lifetime stats (read by UI, for now)
    // ────────────────────────────────────────────────────────────────────
    private val clusterTrades = ConcurrentHashMap<Cluster, Int>()
    private val clusterWins   = ConcurrentHashMap<Cluster, Int>()
    private val clusterPnlPct = ConcurrentHashMap<Cluster, Double>()

    fun recordOutcome(cluster: Cluster, pnlPct: Double, isWin: Boolean) {
        if (cluster == Cluster.UNKNOWN) return
        clusterTrades.merge(cluster, 1) { a, b -> a + b }
        if (isWin) clusterWins.merge(cluster, 1) { a, b -> a + b }
        clusterPnlPct.merge(cluster, pnlPct) { a, b -> a + b }
        ErrorLogger.info(TAG, "${cluster.emoji} ${cluster.name} outcome ${"%+.1f".format(pnlPct)}% (${if (isWin) "WIN" else "LOSS"})")
    }

    fun winRatePct(cluster: Cluster): Double {
        val t = clusterTrades[cluster] ?: 0
        if (t == 0) return 0.0
        val w = clusterWins[cluster] ?: 0
        return w * 100.0 / t
    }

    fun summary(): String =
        clusterTrades.entries.sortedByDescending { it.value }.take(5).joinToString {
            "${it.key.emoji}${it.key.name}=${it.value}t/${"%.0f".format(winRatePct(it.key))}%"
        }

    // ────────────────────────────────────────────────────────────────────
    // V5.9.618 — closed feedback loop: cluster WR multiplier
    // ────────────────────────────────────────────────────────────────────
    /**
     * Returns a 0.7..1.3 multiplier callers can apply to their narrative
     * bonus, derived from the cluster's proven win-rate.
     *
     * Fail-open: returns 1.0 (no effect) until the cluster has at least
     * MIN_TRADES samples, so during bootstrap nothing is choked. Above
     * the threshold, the multiplier scales smoothly:
     *
     *   WR < 20%  → 0.70   (clearly bad cluster — shrink narrative bonus)
     *   WR < 30%  → 0.85
     *   WR 30-50% → 1.00   (par)
     *   WR 50-70% → 1.15
     *   WR ≥ 70%  → 1.30   (clearly good cluster — boost narrative bonus)
     *
     * Pure additive nudge — never blocks an entry, never inverts a score.
     */
    private const val CLUSTER_MIN_TRADES = 30

    fun getClusterMultiplier(cluster: Cluster): Double {
        if (cluster == Cluster.UNKNOWN) return 1.0
        val trades = clusterTrades[cluster] ?: 0
        if (trades < CLUSTER_MIN_TRADES) return 1.0      // bootstrap — fail-open
        val wr = winRatePct(cluster)
        return when {
            wr < 20.0 -> 0.70
            wr < 30.0 -> 0.85
            wr < 50.0 -> 1.00
            wr < 70.0 -> 1.15
            else      -> 1.30
        }
    }

    fun exportState(): String = try {
        val root = JSONObject()
        Cluster.values().forEach { c ->
            if (c != Cluster.UNKNOWN) {
                val obj = JSONObject()
                obj.put("trades", clusterTrades[c] ?: 0)
                obj.put("wins", clusterWins[c] ?: 0)
                obj.put("pnlPct", clusterPnlPct[c] ?: 0.0)
                root.put(c.name, obj)
            }
        }
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            val root = JSONObject(json)
            clusterTrades.clear(); clusterWins.clear(); clusterPnlPct.clear()
            Cluster.values().forEach { c ->
                if (c != Cluster.UNKNOWN && root.has(c.name)) {
                    val obj = root.optJSONObject(c.name) ?: return@forEach
                    val trades = obj.optInt("trades", 0)
                    val wins = obj.optInt("wins", 0)
                    val pnl = obj.optDouble("pnlPct", 0.0)
                    if (trades > 0) clusterTrades[c] = trades
                    if (wins > 0) clusterWins[c] = wins
                    if (pnl != 0.0) clusterPnlPct[c] = pnl
                }
            }
        } catch (_: Throwable) {}
    }

}
