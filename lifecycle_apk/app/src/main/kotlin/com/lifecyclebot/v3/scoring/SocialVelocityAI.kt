package com.lifecyclebot.v3.scoring

import com.lifecyclebot.network.SharedHttpClient

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * V3 Social Velocity AI
 * 
 * Detects sudden social interest spikes using DexScreener's social links
 * and CoinGecko's trending detection (both FREE, no API key required).
 * 
 * CONCEPT: Tokens that suddenly appear on trending lists or get social
 * traction often pump shortly after. Early detection = edge.
 * 
 * SIGNALS DETECTED:
 * 1. DexScreener Trending - Token appearing on DEXScreener trending
 * 2. Social Link Presence - Twitter/Telegram/Website presence
 * 3. Recent Listing - Token listed very recently (< 24h)
 * 
 * API: GET https://api.dexscreener.com/token-boosts/top/v1
 * Rate limit: Free tier (~300/min)
 */
class SocialVelocityAI : ScoringModule {
    override val name = "social"
    
    companion object {
        private const val TAG = "SocialVelocityAI"
        private const val BOOSTED_API = "https://api.dexscreener.com/token-boosts/top/v1"
        private const val CACHE_DURATION_MS = 2 * 60 * 1000L  // 2 minutes

        // V5.0.4111 — class-level learning brain (single instance shared across
        // every SocialVelocityAI invocation; LayerBrain.register is idempotent).
        private val brain = com.lifecyclebot.engine.LayerBrain.register("SocialVelocityAI", nFeatures = 3)
        
        private val client = SharedHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        
        // Cache of boosted/trending tokens
        private val boostedTokens = ConcurrentHashMap<String, Long>()  // mint -> boost amount
        @Volatile private var lastBoostedFetchMs = 0L
        private val refreshInFlight = AtomicBoolean(false)
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        /**
         * V5.0.4306 — hot-path safe refresh. Scoring must never synchronously
         * execute DexScreener HTTP; it reads cached boost data and kicks a
         * background refresh when stale. Free social/boost alpha is useful, but
         * not if it wedges UnifiedScorer / FDG / scanner flow.
         */
        fun refreshBoostedTokensAsync() {
            val now = System.currentTimeMillis()
            if (now - lastBoostedFetchMs < CACHE_DURATION_MS) return
            if (!refreshInFlight.compareAndSet(false, true)) return
            refreshScope.launch {
                try { refreshBoostedTokensBlocking() }
                finally { refreshInFlight.set(false) }
            }
        }

        /** Blocking refresh kept for explicit startup/background callers only. */
        fun refreshBoostedTokens() { refreshBoostedTokensBlocking() }

        private fun refreshBoostedTokensBlocking() {
            val now = System.currentTimeMillis()
            if (now - lastBoostedFetchMs < CACHE_DURATION_MS) return
            try {
                val request = Request.Builder()
                    .url(BOOSTED_API)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        ErrorLogger.debug(TAG, "Boosted API error: ${response.code}")
                        return
                    }
                    val body = response.body?.string() ?: return
                    val json = JSONObject("{\"data\":$body}")  // Wrap array in object
                    val dataArray = json.optJSONArray("data") ?: return
                    boostedTokens.clear()
                    for (i in 0 until minOf(dataArray.length(), 100)) {
                        val item = dataArray.optJSONObject(i) ?: continue
                        val tokenAddress = item.optString("tokenAddress", "")
                        val chainId = item.optString("chainId", "")
                        val amount = item.optLong("amount", 0)
                        if (chainId == "solana" && tokenAddress.isNotBlank()) {
                            boostedTokens[tokenAddress] = amount
                        }
                    }
                    lastBoostedFetchMs = now
                    ErrorLogger.debug(TAG, "Refreshed boosted tokens: ${boostedTokens.size}")
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Boosted fetch error: ${e.message}")
            }
        }
        
        /** Check if token is currently boosted on DexScreener using cached data. */
        fun isBoosted(mint: String): Boolean {
            refreshBoostedTokensAsync()
            return boostedTokens.containsKey(mint)
        }
        
        /** Get boost amount for token using cached data. */
        fun getBoostAmount(mint: String): Long {
            refreshBoostedTokensAsync()
            return boostedTokens[mint] ?: 0L
        }
    }
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        // V3.3: Scale penalties by learning progress
        // At bootstrap, social data penalties are reduced (many good tokens have no social yet)
        val learningProgress = try {
            FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        val penaltyMult = (0.3 + (learningProgress * 0.7)).coerceIn(0.3, 1.0)
        
        // 1. Check if token is boosted on DexScreener
        val boostAmount = getBoostAmount(candidate.mint)
        if (boostAmount > 0) {
            val boostScore = when {
                boostAmount >= 1000 -> 6   // Major boost
                boostAmount >= 500 -> 4    // Medium boost
                boostAmount >= 100 -> 2    // Small boost
                else -> 1
            }
            score += boostScore
            reasons += "DexScreener boosted (+$boostAmount)"
        }
        
        // 2. Check for social links from candidate data
        val hasTwitter = candidate.extraBoolean("hasTwitter") || 
                         candidate.extraString("twitter").isNotBlank()
        val hasTelegram = candidate.extraBoolean("hasTelegram") ||
                          candidate.extraString("telegram").isNotBlank()
        val hasWebsite = candidate.extraBoolean("hasWebsite") ||
                         candidate.extraString("website").isNotBlank()
        
        val socialCount = listOf(hasTwitter, hasTelegram, hasWebsite).count { it }
        when (socialCount) {
            3 -> { score += 4; reasons += "Full social presence" }
            2 -> { score += 2; reasons += "Partial social presence" }
            1 -> { score += 1; reasons += "Minimal social presence" }
            0 -> { 
                // V3.3: Reduced penalty during bootstrap
                score -= (2 * penaltyMult).toInt()
                reasons += "No social links"
            }
        }
        
        // 3. Check for identity signals (name recognition, meme potential)
        if (candidate.hasIdentitySignals) {
            score += 2
            reasons += "Identity signals detected"
        }
        
        // 4. Age factor - very new tokens with social = potential pump
        val ageMinutes = candidate.ageMinutes
        if (ageMinutes < 60 && socialCount >= 2) {
            score += 3
            reasons += "New token with social backing"
        } else if (ageMinutes < 30 && socialCount == 0) {
            // V3.3: Reduced penalty during bootstrap (many fresh launches lack socials initially)
            score -= (3 * penaltyMult).toInt()
            reasons += "Very new, no social = risky"
        }
        
        return ScoreComponent(
            name = name,
            value = brain.applyBias(score.coerceIn(-8, 12).toDouble(), doubleArrayOf(
                (socialCount.toDouble() / 3.0).coerceIn(0.0, 1.0),
                (boostAmount.toDouble() / 1000.0).coerceIn(0.0, 1.0),
                (1.0 - (ageMinutes / 240.0)).coerceIn(0.0, 1.0),
            )).toInt(),
            reason = reasons.ifEmpty { listOf("Neutral social") }.joinToString(", ")
        ).also {
            try {
                brain.stamp(candidate.mint, doubleArrayOf(
                    (socialCount.toDouble() / 3.0).coerceIn(0.0, 1.0),
                    (boostAmount.toDouble() / 1000.0).coerceIn(0.0, 1.0),
                    (1.0 - (ageMinutes / 240.0)).coerceIn(0.0, 1.0),
                ))
            } catch (_: Throwable) {}
        }
    }
}
