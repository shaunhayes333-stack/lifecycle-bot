package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * BundleDetector — Heuristic early-launch clustering detector
 *
 * Notes:
 * - This is NOT true on-chain bundle proof.
 * - It is a practical early-flow risk detector using Helius transfer history.
 * - Safer than estimating fake "% of supply" without actual supply metadata.
 */
object BundleDetector {

    private const val TAG = "BundleDetector"

    private val client = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val analysisCache = ConcurrentHashMap<String, BundleAnalysis>()
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L

    data class BundleAnalysis(
        val mint: String,
        val symbol: String,
        val analyzedAt: Long = System.currentTimeMillis(),

        val hasBundles: Boolean,
        val bundleType: BundleType,
        val bundleRisk: BundleRisk,

        val firstBlockBuyers: Int,
        val firstBlockSupplyPct: Double,   // Heuristic concentration %, not literal supply %
        val largestBundlePct: Double,      // Heuristic largest-wallet share of early flow
        val uniqueWalletsFirst10: Int,

        val bundledWalletsSold: Int,
        val bundledWalletsHolding: Int,
        val avgHoldTimeMinutes: Double,

        val isLikelyRug: Boolean,
        val isLikelyPump: Boolean,
        val isLikelySnipers: Boolean,

        val recommendation: String,
        val reason: String,
    ) {
        val isStale: Boolean
            get() = System.currentTimeMillis() - analyzedAt > CACHE_DURATION_MS
    }

    enum class BundleType {
        NONE,
        DEV_BUNDLE,
        SNIPER_BUNDLE,
        VOLUME_BUNDLE,
        PUMP_BUNDLE,
        MIXED,
    }

    enum class BundleRisk {
        LOW,
        MEDIUM,
        HIGH,
        UNKNOWN,
    }

    private data class TokenTx(
        val signature: String,
        val slot: Long,
        val timestampSec: Long,
        val wallet: String,
        val tokenAmountRaw: Double,
        val isOutboundFromWallet: Boolean,
    )

    suspend fun analyze(
        mint: String,
        symbol: String,
        heliusApiKey: String,
    ): BundleAnalysis {
        val cached = analysisCache[mint]
        if (cached != null && !cached.isStale) return cached

        return try {
            val analysis = performAnalysis(mint, symbol, heliusApiKey)
            analysisCache[mint] = analysis
            analysis
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Analysis failed for $symbol: ${e.message}")
            createUnknownAnalysis(mint, symbol, "Analysis failed: ${e.message}")
        }
    }

    fun quickRiskCheck(
        firstBlockBuyers: Int,
        firstBlockSupplyPct: Double,
        topHolderPct: Double,
    ): BundleRisk {
        return when {
            firstBlockSupplyPct > 70.0 && firstBlockBuyers <= 3 -> BundleRisk.HIGH
            firstBlockSupplyPct > 50.0 && topHolderPct > 60.0 -> BundleRisk.HIGH
            firstBlockSupplyPct > 40.0 && firstBlockBuyers < 8 -> BundleRisk.MEDIUM
            firstBlockSupplyPct > 30.0 && topHolderPct > 40.0 -> BundleRisk.MEDIUM
            else -> BundleRisk.LOW
        }
    }

    private fun performAnalysis(
        mint: String,
        symbol: String,
        heliusApiKey: String,
    ): BundleAnalysis {
        val transactions = fetchTokenTransactions(mint, heliusApiKey, limit = 80)

        if (transactions.isEmpty()) {
            return createUnknownAnalysis(mint, symbol, "No transactions found")
        }

        val txsBySlot = transactions.groupBy { it.slot }
        val firstSlot = txsBySlot.keys.minOrNull() ?: 0L
        val firstBlockTxs = txsBySlot[firstSlot].orEmpty()

        if (firstBlockTxs.isEmpty()) {
            return createUnknownAnalysis(mint, symbol, "No first-slot transfers found")
        }

        val firstBlockWalletTotals = firstBlockTxs
            .groupBy { it.wallet }
            .mapValues { (_, txs) -> txs.sumOf { max(0.0, it.tokenAmountRaw) } }

        val firstBlockTotalFlow = firstBlockWalletTotals.values.sum().coerceAtLeast(1.0)
        val firstBlockBuyers = firstBlockWalletTotals.size

        // Heuristic "concentration %" of early flow, not literal token supply %
        val firstBlockSupplyPct = 100.0
        val largestBundlePct = ((firstBlockWalletTotals.values.maxOrNull() ?: 0.0) / firstBlockTotalFlow * 100.0)
            .coerceIn(0.0, 100.0)

        val uniqueWalletsFirst10 = transactions.take(10).map { it.wallet }.distinct().size

        val bundleWallets = firstBlockWalletTotals.keys

        val bundledWalletsSold = bundleWallets.count { wallet ->
            transactions.any { it.wallet == wallet && it.isOutboundFromWallet && it.slot > firstSlot }
        }
        val bundledWalletsHolding = (bundleWallets.size - bundledWalletsSold).coerceAtLeast(0)

        val sellHoldDurationsMin = bundleWallets.mapNotNull { wallet ->
            val entryTx = firstBlockTxs.firstOrNull { it.wallet == wallet }
            val firstOutbound = transactions.firstOrNull {
                it.wallet == wallet && it.isOutboundFromWallet && it.slot > firstSlot
            }
            if (entryTx != null && firstOutbound != null && firstOutbound.timestampSec > 0 && entryTx.timestampSec > 0) {
                ((firstOutbound.timestampSec - entryTx.timestampSec).toDouble() / 60.0).coerceAtLeast(0.0)
            } else null
        }

        val avgHoldTimeMinutes = if (sellHoldDurationsMin.isNotEmpty()) {
            sellHoldDurationsMin.average()
        } else 0.0

        val concentrationTop3Pct = firstBlockWalletTotals.values
            .sortedDescending()
            .take(3)
            .sum()
            .let { it / firstBlockTotalFlow * 100.0 }
            .coerceIn(0.0, 100.0)

        val rapidExitRatio = if (bundleWallets.isNotEmpty()) {
            bundledWalletsSold.toDouble() / bundleWallets.size.toDouble()
        } else 0.0

        val distributedEntry = firstBlockBuyers >= 6 && largestBundlePct < 35.0
        val concentratedEntry = firstBlockBuyers <= 4 || largestBundlePct >= 45.0 || concentrationTop3Pct >= 80.0

        val isLikelyRug =
            concentratedEntry &&
            rapidExitRatio >= 0.5 &&
            (avgHoldTimeMinutes in 0.0..30.0 || avgHoldTimeMinutes == 0.0 && bundledWalletsSold >= 2)

        val isLikelyPump =
            distributedEntry &&
            bundledWalletsHolding >= max(3, bundledWalletsSold * 2) &&
            largestBundlePct < 30.0

        val isLikelySnipers =
            firstBlockBuyers >= 4 &&
            firstBlockBuyers <= 12 &&
            rapidExitRatio >= 0.5 &&
            !isLikelyRug

        val bundleType = when {
            firstBlockBuyers <= 1 && largestBundlePct >= 70.0 -> BundleType.DEV_BUNDLE
            isLikelyRug -> BundleType.DEV_BUNDLE
            isLikelySnipers -> BundleType.SNIPER_BUNDLE
            isLikelyPump -> BundleType.PUMP_BUNDLE
            concentratedEntry && rapidExitRatio < 0.5 -> BundleType.MIXED
            firstBlockBuyers >= 10 && uniqueWalletsFirst10 <= 4 -> BundleType.VOLUME_BUNDLE
            else -> BundleType.NONE
        }

        val bundleRisk = when {
            isLikelyRug -> BundleRisk.HIGH
            concentratedEntry || isLikelySnipers -> BundleRisk.MEDIUM
            else -> BundleRisk.LOW
        }

        val hasBundles = firstBlockBuyers >= 2 || largestBundlePct >= 20.0

        val recommendationReason = when {
            isLikelyRug ->
                "Highly concentrated early flow with fast exits from first-slot wallets"
            isLikelyPump ->
                "Distributed first-slot accumulation with mostly holding behavior"
            isLikelySnipers ->
                "Clustered early entrants with elevated fast-exit behavior"
            concentratedEntry ->
                "Early flow heavily concentrated across few wallets"
            hasBundles ->
                "Some early clustering detected, but no severe dump pattern yet"
            else ->
                "No meaningful early clustering detected"
        }

        val recommendation = when (bundleRisk) {
            BundleRisk.HIGH -> "AVOID"
            BundleRisk.MEDIUM -> "CAUTION"
            BundleRisk.LOW -> "SAFE"
            BundleRisk.UNKNOWN -> "UNKNOWN"
        }

        return BundleAnalysis(
            mint = mint,
            symbol = symbol,
            hasBundles = hasBundles,
            bundleType = bundleType,
            bundleRisk = bundleRisk,
            firstBlockBuyers = firstBlockBuyers,
            firstBlockSupplyPct = firstBlockSupplyPct,
            largestBundlePct = largestBundlePct,
            uniqueWalletsFirst10 = uniqueWalletsFirst10,
            bundledWalletsSold = bundledWalletsSold,
            bundledWalletsHolding = bundledWalletsHolding,
            avgHoldTimeMinutes = avgHoldTimeMinutes,
            isLikelyRug = isLikelyRug,
            isLikelyPump = isLikelyPump,
            isLikelySnipers = isLikelySnipers,
            recommendation = recommendation,
            reason = recommendationReason,
        )
    }

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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ErrorLogger.warn(TAG, "Helius API error: ${response.code}")
                    return emptyList()
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()

                val txArray = JSONArray(body)
                val results = mutableListOf<TokenTx>()

                for (i in 0 until txArray.length()) {
                    val tx = txArray.optJSONObject(i) ?: continue
                    val slot = tx.optLong("slot", 0L)
                    val signature = tx.optString("signature", "")
                    val timestampSec = tx.optLong("timestamp", 0L)
                    val feePayer = tx.optString("feePayer", "")

                    val tokenTransfers = tx.optJSONArray("tokenTransfers") ?: continue
                    for (j in 0 until tokenTransfers.length()) {
                        val transfer = tokenTransfers.optJSONObject(j) ?: continue
                        if (transfer.optString("mint", "") != mint) continue

                        val fromUser = transfer.optString("fromUserAccount", "")
                        val toUser = transfer.optString("toUserAccount", "")
                        val amount = parseTokenAmount(transfer)
                        if (amount <= 0.0) continue

                        // Heuristic:
                        // - inbound transfer to wallet => acquisition
                        // - outbound transfer from wallet => disposal
                        val inboundWallet = toUser.ifBlank { feePayer }
                        val outboundWallet = fromUser.ifBlank { feePayer }

                        if (inboundWallet.isNotBlank()) {
                            results += TokenTx(
                                signature = signature,
                                slot = slot,
                                timestampSec = timestampSec,
                                wallet = inboundWallet,
                                tokenAmountRaw = amount,
                                isOutboundFromWallet = false,
                            )
                        }

                        if (outboundWallet.isNotBlank()) {
                            results += TokenTx(
                                signature = signature,
                                slot = slot,
                                timestampSec = timestampSec,
                                wallet = outboundWallet,
                                tokenAmountRaw = amount,
                                isOutboundFromWallet = true,
                            )
                        }
                    }
                }

                results.sortedBy { it.slot }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to fetch transactions: ${e.message}")
            emptyList()
        }
    }

    private fun parseTokenAmount(transfer: org.json.JSONObject): Double {
        val direct = transfer.optDouble("tokenAmount", Double.NaN)
        if (!direct.isNaN() && direct > 0.0) return direct

        val rawTokenAmount = transfer.optJSONObject("rawTokenAmount")
        if (rawTokenAmount != null) {
            val rawStr = rawTokenAmount.optString("tokenAmount", "0")
            val decimals = rawTokenAmount.optInt("decimals", 0)
            return try {
                rawStr.toBigDecimal()
                    .movePointLeft(decimals)
                    .toDouble()
                    .coerceAtLeast(0.0)
            } catch (_: Exception) {
                0.0
            }
        }

        return 0.0
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

    fun BundleAnalysis.toLogString(): String {
        return buildString {
            append("Bundle[$symbol]: ")
            append("$bundleType ")
            append("risk=$bundleRisk ")
            append("| block1_buyers=$firstBlockBuyers ")
            append("| largest=${largestBundlePct.toInt()}% ")
            append("| sold=$bundledWalletsSold hold=$bundledWalletsHolding ")
            append("| $recommendation: $reason")
        }
    }

    fun clearCache() {
        analysisCache.clear()
    }

    fun getCacheStats(): String {
        return "BundleCache: ${analysisCache.size} tokens cached"
    }
}