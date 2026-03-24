package com.lifecyclebot.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * BundleDetector — Analyzes launch transactions to detect bundled buys
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT ARE BUNDLES?
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * At token launch, multiple transactions can be "bundled" into the same block.
 * This means they all execute atomically before anyone else can buy.
 * 
 * TYPES OF BUNDLES:
 * 
 * 1. RUG BUNDLES (DANGEROUS) 🔴
 *    - Dev/insiders bundle 50-90% of supply at launch
 *    - Plan to dump on retail buyers
 *    - Pattern: Few wallets, huge % of supply, same block as launch
 *    
 * 2. SNIPER BUNDLES (NEUTRAL/NEGATIVE) 🟡
 *    - MEV bots bundle to get first buys
 *    - Usually sell quickly for small profit
 *    - Pattern: Known sniper wallets, quick sells
 *    
 * 3. VOLUME BUNDLES (NEUTRAL) 🟡
 *    - Bots creating fake volume/activity
 *    - Wash trading to attract attention
 *    - Pattern: Circular transactions, same amounts
 *    
 * 4. SECURITY BUNDLES (POSITIVE) 🟢
 *    - Dev bundles to prevent snipers from getting too much
 *    - Keeps supply distributed
 *    - Pattern: Dev wallet, reasonable %, tokens stay locked
 *    
 * 5. PUMP BUNDLES (POSITIVE) 🟢
 *    - Coordinated buys to push price
 *    - Community or influencer organized
 *    - Pattern: Multiple wallets, spread buys, HODL behavior
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * DETECTION STRATEGY
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * We analyze the first N transactions after token creation:
 * 1. Count unique wallets in first block
 * 2. Calculate % of supply bought by each
 * 3. Check if wallets are known snipers/rugs
 * 4. Track if bundled wallets SELL quickly (rug signal)
 * 5. Track if bundled wallets HOLD (pump signal)
 */
object BundleDetector {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Cache bundle analysis results (expensive to compute)
    private val analysisCache = ConcurrentHashMap<String, BundleAnalysis>()
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L  // 30 minutes
    
    // Known sniper/MEV bot patterns
    private val KNOWN_SNIPER_PATTERNS = setOf(
        "pump", "snipe", "bot", "mev", "jito", "bundle"
    )
    
    /**
     * Bundle analysis result
     */
    data class BundleAnalysis(
        val mint: String,
        val symbol: String,
        val analyzedAt: Long = System.currentTimeMillis(),
        
        // Bundle stats
        val hasBundles: Boolean,
        val bundleType: BundleType,
        val bundleRisk: BundleRisk,
        
        // Metrics
        val firstBlockBuyers: Int,           // Wallets that bought in first block
        val firstBlockSupplyPct: Double,     // % of supply bought in first block
        val largestBundlePct: Double,        // Largest single bundle %
        val uniqueWalletsFirst10: Int,       // Unique wallets in first 10 txs
        
        // Wallet behavior (tracked over time)
        val bundledWalletsSold: Int,         // How many bundle wallets have sold
        val bundledWalletsHolding: Int,      // How many are still holding
        val avgHoldTimeMinutes: Double,      // Average hold time of sold positions
        
        // Signals
        val isLikelyRug: Boolean,            // High % bundled + quick sells
        val isLikelyPump: Boolean,           // Coordinated + holding
        val isLikelySnipers: Boolean,        // Known bot patterns
        
        // Recommendation
        val recommendation: String,          // "SAFE", "CAUTION", "AVOID"
        val reason: String,
    ) {
        val isStale: Boolean
            get() = System.currentTimeMillis() - analyzedAt > CACHE_DURATION_MS
    }
    
    enum class BundleType {
        NONE,              // No significant bundles detected
        DEV_BUNDLE,        // Dev/team bundled supply
        SNIPER_BUNDLE,     // MEV/sniper bots
        VOLUME_BUNDLE,     // Fake volume bots
        PUMP_BUNDLE,       // Coordinated pump
        MIXED,             // Multiple types detected
    }
    
    enum class BundleRisk {
        LOW,       // Safe to trade
        MEDIUM,    // Proceed with caution
        HIGH,      // Likely rug
        UNKNOWN,   // Couldn't analyze
    }
    
    /**
     * Analyze a token's launch for bundle activity.
     * Uses Helius API for transaction history.
     */
    suspend fun analyze(
        mint: String,
        symbol: String,
        heliusApiKey: String,
    ): BundleAnalysis {
        // Check cache first
        val cached = analysisCache[mint]
        if (cached != null && !cached.isStale) {
            return cached
        }
        
        return try {
            val analysis = performAnalysis(mint, symbol, heliusApiKey)
            analysisCache[mint] = analysis
            analysis
        } catch (e: Exception) {
            ErrorLogger.error("BundleDetector", "Analysis failed for $symbol: ${e.message}")
            createUnknownAnalysis(mint, symbol, "Analysis failed: ${e.message}")
        }
    }
    
    /**
     * Quick check - is this token likely bundled?
     * Returns risk level without full analysis.
     */
    fun quickRiskCheck(
        firstBlockBuyers: Int,
        firstBlockSupplyPct: Double,
        topHolderPct: Double,
    ): BundleRisk {
        return when {
            // Very high concentration in first block = likely rug
            firstBlockSupplyPct > 70 && firstBlockBuyers < 5 -> BundleRisk.HIGH
            firstBlockSupplyPct > 50 && topHolderPct > 60 -> BundleRisk.HIGH
            
            // Moderate concentration = caution
            firstBlockSupplyPct > 40 && firstBlockBuyers < 10 -> BundleRisk.MEDIUM
            firstBlockSupplyPct > 30 && topHolderPct > 40 -> BundleRisk.MEDIUM
            
            // Low concentration = likely safe
            else -> BundleRisk.LOW
        }
    }
    
    /**
     * Perform full bundle analysis using Helius transaction history.
     */
    private fun performAnalysis(
        mint: String,
        symbol: String,
        heliusApiKey: String,
    ): BundleAnalysis {
        // Fetch first 50 transactions for this token
        val transactions = fetchTokenTransactions(mint, heliusApiKey, limit = 50)
        
        if (transactions.isEmpty()) {
            return createUnknownAnalysis(mint, symbol, "No transactions found")
        }
        
        // Group transactions by block/slot
        val txsBySlot = transactions.groupBy { it.slot }
        val firstSlot = txsBySlot.keys.minOrNull() ?: 0L
        val firstBlockTxs = txsBySlot[firstSlot] ?: emptyList()
        
        // Analyze first block
        val firstBlockBuyers = firstBlockTxs.map { it.wallet }.distinct().size
        val firstBlockSupplyPct = firstBlockTxs.sumOf { it.tokenAmountPct }
        val largestBundlePct = firstBlockTxs.maxOfOrNull { it.tokenAmountPct } ?: 0.0
        
        // Analyze first 10 transactions
        val first10Wallets = transactions.take(10).map { it.wallet }.distinct().size
        
        // Track wallet behavior (who sold vs who's holding)
        val bundleWallets = firstBlockTxs.map { it.wallet }.toSet()
        val allWallets = transactions.map { it.wallet to it.isSell }.groupBy({ it.first }, { it.second })
        
        var soldCount = 0
        var holdingCount = 0
        var totalHoldTime = 0.0
        
        bundleWallets.forEach { wallet ->
            val walletTxs = allWallets[wallet] ?: emptyList()
            if (walletTxs.any { it }) {
                soldCount++
                // Estimate hold time based on tx position
                totalHoldTime += 5.0  // Simplified - would need timestamps
            } else {
                holdingCount++
            }
        }
        
        val avgHoldTime = if (soldCount > 0) totalHoldTime / soldCount else 0.0
        
        // Determine bundle type
        val bundleType = when {
            firstBlockSupplyPct < 10 -> BundleType.NONE
            firstBlockBuyers == 1 && firstBlockSupplyPct > 30 -> BundleType.DEV_BUNDLE
            firstBlockBuyers > 5 && soldCount > holdingCount -> BundleType.SNIPER_BUNDLE
            firstBlockBuyers > 10 && holdingCount > soldCount -> BundleType.PUMP_BUNDLE
            else -> BundleType.MIXED
        }
        
        // Determine risk
        val isLikelyRug = firstBlockSupplyPct > 50 && soldCount > holdingCount && avgHoldTime < 30
        val isLikelyPump = firstBlockBuyers > 5 && holdingCount > soldCount * 2
        val isLikelySnipers = bundleType == BundleType.SNIPER_BUNDLE
        
        val bundleRisk = when {
            isLikelyRug -> BundleRisk.HIGH
            firstBlockSupplyPct > 40 || isLikelySnipers -> BundleRisk.MEDIUM
            else -> BundleRisk.LOW
        }
        
        // Generate recommendation
        val (recommendation, reason) = when {
            isLikelyRug -> "AVOID" to "High bundle concentration (${firstBlockSupplyPct.toInt()}%) with quick sells - likely rug"
            isLikelyPump -> "SAFE" to "Coordinated buy ($firstBlockBuyers wallets) with holding behavior - potential pump"
            isLikelySnipers -> "CAUTION" to "Sniper activity detected - watch for quick dumps"
            bundleRisk == BundleRisk.HIGH -> "AVOID" to "Dangerous bundle pattern: ${firstBlockSupplyPct.toInt()}% in first block"
            bundleRisk == BundleRisk.MEDIUM -> "CAUTION" to "Moderate bundle activity: $firstBlockBuyers buyers, ${firstBlockSupplyPct.toInt()}%"
            else -> "SAFE" to "No dangerous bundles detected"
        }
        
        return BundleAnalysis(
            mint = mint,
            symbol = symbol,
            hasBundles = firstBlockSupplyPct > 10,
            bundleType = bundleType,
            bundleRisk = bundleRisk,
            firstBlockBuyers = firstBlockBuyers,
            firstBlockSupplyPct = firstBlockSupplyPct,
            largestBundlePct = largestBundlePct,
            uniqueWalletsFirst10 = first10Wallets,
            bundledWalletsSold = soldCount,
            bundledWalletsHolding = holdingCount,
            avgHoldTimeMinutes = avgHoldTime,
            isLikelyRug = isLikelyRug,
            isLikelyPump = isLikelyPump,
            isLikelySnipers = isLikelySnipers,
            recommendation = recommendation,
            reason = reason,
        )
    }
    
    /**
     * Fetch token transactions from Helius.
     */
    private data class TokenTx(
        val slot: Long,
        val wallet: String,
        val tokenAmountPct: Double,
        val isSell: Boolean,
    )
    
    private fun fetchTokenTransactions(
        mint: String,
        heliusApiKey: String,
        limit: Int,
    ): List<TokenTx> {
        val url = "https://api.helius.xyz/v0/addresses/$mint/transactions?api-key=$heliusApiKey&limit=$limit"
        
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                ErrorLogger.error("BundleDetector", "Helius API error: ${response.code}")
                return emptyList()
            }
            
            val body = response.body?.string() ?: return emptyList()
            val txArray = JSONArray(body)
            
            val results = mutableListOf<TokenTx>()
            
            for (i in 0 until txArray.length()) {
                val tx = txArray.optJSONObject(i) ?: continue
                val slot = tx.optLong("slot", 0)
                val feePayer = tx.optString("feePayer", "")
                
                // Parse token transfers
                val tokenTransfers = tx.optJSONArray("tokenTransfers")
                if (tokenTransfers != null && tokenTransfers.length() > 0) {
                    for (j in 0 until tokenTransfers.length()) {
                        val transfer = tokenTransfers.optJSONObject(j) ?: continue
                        val tokenMint = transfer.optString("mint", "")
                        
                        if (tokenMint == mint) {
                            val fromUser = transfer.optString("fromUserAccount", "")
                            val toUser = transfer.optString("toUserAccount", "")
                            val amount = transfer.optDouble("tokenAmount", 0.0)
                            
                            // Determine if buy or sell
                            val isSell = fromUser.isNotEmpty() && toUser.isEmpty()
                            val wallet = if (isSell) fromUser else toUser
                            
                            // Estimate % of supply (would need total supply for accuracy)
                            // For now, use a heuristic based on amount
                            val estimatedPct = (amount / 1_000_000_000 * 100).coerceAtMost(100.0)
                            
                            results.add(TokenTx(
                                slot = slot,
                                wallet = wallet.ifEmpty { feePayer },
                                tokenAmountPct = estimatedPct,
                                isSell = isSell,
                            ))
                        }
                    }
                }
            }
            
            results.sortedBy { it.slot }
        } catch (e: Exception) {
            ErrorLogger.error("BundleDetector", "Failed to fetch transactions: ${e.message}")
            emptyList()
        }
    }
    
    private fun createUnknownAnalysis(mint: String, symbol: String, reason: String): BundleAnalysis {
        return BundleAnalysis(
            mint = mint,
            symbol = symbol,
            hasBundles = false,
            bundleType = BundleType.NONE,
            bundleRisk = BundleRisk.UNKNOWN,
            firstBlockBuyers = 0,
            firstBlockSupplyPct = 0.0,
            largestBundlePct = 0.0,
            uniqueWalletsFirst10 = 0,
            bundledWalletsSold = 0,
            bundledWalletsHolding = 0,
            avgHoldTimeMinutes = 0.0,
            isLikelyRug = false,
            isLikelyPump = false,
            isLikelySnipers = false,
            recommendation = "UNKNOWN",
            reason = reason,
        )
    }
    
    /**
     * Get summary for logging
     */
    fun BundleAnalysis.toLogString(): String {
        return buildString {
            append("Bundle[$symbol]: ")
            append("$bundleType ")
            append("risk=$bundleRisk ")
            append("| ${firstBlockBuyers} buyers, ${firstBlockSupplyPct.toInt()}% in block1 ")
            append("| sold=$bundledWalletsSold hold=$bundledWalletsHolding ")
            append("| $recommendation: $reason")
        }
    }
    
    /**
     * Clear cache (for testing)
     */
    fun clearCache() {
        analysisCache.clear()
    }
    
    /**
     * Get cache stats
     */
    fun getCacheStats(): String {
        return "BundleCache: ${analysisCache.size} tokens cached"
    }
}
