package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V3 Fear & Greed Index AI
 * 
 * Uses the Alternative.me Crypto Fear & Greed Index (FREE, no API key required)
 * https://alternative.me/crypto/fear-and-greed-index/
 * 
 * The index ranges from 0-100:
 *   0-24:  Extreme Fear (historically good buying opportunity)
 *   25-44: Fear
 *   45-55: Neutral
 *   56-75: Greed
 *   76-100: Extreme Greed (historically time to be cautious)
 * 
 * TRADING LOGIC:
 * - Extreme Fear + good setup = bonus points (buy when others are fearful)
 * - Extreme Greed + weak setup = penalty (don't FOMO)
 * - Neutral = no adjustment
 * 
 * API: GET https://api.alternative.me/fng/?limit=1
 * Rate limit: ~30 requests/minute (we cache for 5 minutes)
 */
class FearGreedAI : ScoringModule {
    override val name = "feargreed"
    
    companion object {
        private const val TAG = "FearGreedAI"
        private const val API_URL = "https://api.alternative.me/fng/?limit=1"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        
        // Cached data
        private data class FearGreedData(
            val value: Int,
            val classification: String,
            val timestamp: Long
        )
        
        private val cachedData = AtomicReference<FearGreedData?>(null)
        private var lastFetchMs = 0L
        
        /**
         * Fetch Fear & Greed Index (with caching)
         */
        private fun fetchIndex(): FearGreedData? {
            val now = System.currentTimeMillis()
            val cached = cachedData.get()
            
            // Return cached if fresh
            if (cached != null && now - lastFetchMs < CACHE_DURATION_MS) {
                return cached
            }
            
            return try {
                val request = Request.Builder()
                    .url(API_URL)
                    .get()
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        ErrorLogger.debug(TAG, "API error: ${response.code}")
                        return cached  // Return stale cache on error
                    }
                    
                    val body = response.body?.string() ?: return cached
                    val json = JSONObject(body)
                    val dataArray = json.optJSONArray("data")
                    
                    if (dataArray == null || dataArray.length() == 0) {
                        ErrorLogger.debug(TAG, "No data in response")
                        return cached
                    }
                    
                    val item = dataArray.getJSONObject(0)
                    val value = item.optString("value", "50").toIntOrNull() ?: 50
                    val classification = item.optString("value_classification", "Neutral")
                    val ts = item.optString("timestamp", "0").toLongOrNull() ?: 0L
                    
                    val data = FearGreedData(value, classification, ts)
                    cachedData.set(data)
                    lastFetchMs = now
                    
                    ErrorLogger.debug(TAG, "Fetched F&G: $value ($classification)")
                    data
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Fetch error: ${e.message}")
                cached  // Return stale cache on exception
            }
        }
    }
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val data = fetchIndex()
        
        if (data == null) {
            return ScoreComponent(
                name = name,
                value = 0,
                reason = "F&G data unavailable"
            )
        }
        
        val fgValue = data.value
        val classification = data.classification
        
        // Get setup quality from candidate
        val setupQuality = candidate.extraString("setupQuality").uppercase()
        val isGoodSetup = setupQuality in listOf("A", "B")
        val isWeakSetup = setupQuality == "C" || candidate.extraInt("v3Score") < 40
        
        val score: Int
        val reason: String
        
        when {
            // EXTREME FEAR (0-24): Good time to buy if setup is decent
            fgValue <= 24 -> {
                score = if (isGoodSetup) 8 else 4
                reason = "Extreme Fear ($fgValue) - ${if (isGoodSetup) "contrarian buy opportunity" else "cautious entry"}"
            }
            
            // FEAR (25-44): Slightly bullish
            fgValue in 25..44 -> {
                score = if (isGoodSetup) 4 else 2
                reason = "Fear ($fgValue) - market cautious"
            }
            
            // NEUTRAL (45-55): No edge
            fgValue in 45..55 -> {
                score = 0
                reason = "Neutral ($fgValue)"
            }
            
            // GREED (56-75): Be careful
            fgValue in 56..75 -> {
                score = if (isWeakSetup) -4 else -1
                reason = "Greed ($fgValue) - ${if (isWeakSetup) "avoid weak setups" else "caution advised"}"
            }
            
            // EXTREME GREED (76-100): High risk of pullback
            else -> {
                score = if (isWeakSetup) -8 else -3
                reason = "Extreme Greed ($fgValue) - ${if (isWeakSetup) "high FOMO risk" else "pullback likely"}"
            }
        }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-10, 10),
            reason = reason
        )
    }
}
