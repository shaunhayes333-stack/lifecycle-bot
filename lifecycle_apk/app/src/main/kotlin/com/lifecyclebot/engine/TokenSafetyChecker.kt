package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Safety result
// ─────────────────────────────────────────────────────────────────────────────

enum class SafetyTier {
    SAFE,
    CAUTION,
    HARD_BLOCK,
}

data class SafetyReport(
    val tier: SafetyTier = SafetyTier.SAFE,
    val hardBlockReasons: List<String> = emptyList(),
    val softPenalties: List<Pair<String, Int>> = emptyList(),
    val entryScorePenalty: Int = 0,
    val rugcheckScore: Int = -1,
    val mintAuthorityDisabled: Boolean? = null,
    val freezeAuthorityDisabled: Boolean? = null,
    val lpLockPct: Double = -1.0,
    val topHolderPct: Double = -1.0,
    val tokenAgeMinutes: Double = -1.0,
    val nameFlag: String = "",
    val summary: String = "",
    val checkedAt: Long = 0L,

    // Bundle detection
    val bundleRisk: String = "UNKNOWN",
    val bundleType: String = "NONE",
    val firstBlockSupplyPct: Double = -1.0,
    val firstBlockBuyers: Int = -1,
    val bundleRecommendation: String = "UNKNOWN",
    val bundleReason: String = "",

    // Data quality flags
    val rugcheckStatus: String = "UNKNOWN",
    val liqConfirmed: Boolean = false,
    val liqEstimated: Boolean = false,
    val dataConflictFlag: Boolean = false,
    val dataConflictReason: String = "",
    val rugcheckTimeoutPenalty: Int = 0,
) {
    val isBlocked get() = tier == SafetyTier.HARD_BLOCK
    val ageMinutes get() = (System.currentTimeMillis() - checkedAt) / 60_000.0
    val isStale get() = ageMinutes > 10.0

    val hasDangerousBundles get() = bundleRisk == "HIGH"
    val hasModerateBundle get() = bundleRisk == "MEDIUM"
    val hasPumpBundle get() = bundleType == "PUMP_BUNDLE"

    val hasRugcheckTimeout get() = rugcheckStatus == "TIMEOUT"
    val hasDataConflict get() = dataConflictFlag
    val isLiquidityReliable get() = liqConfirmed && !liqEstimated
    val needsReview get() = rugcheckStatus == "PENDING_REVIEW" || dataConflictFlag
}

// ─────────────────────────────────────────────────────────────────────────────
// Verified major Solana token mints
// ─────────────────────────────────────────────────────────────────────────────

private val WHITELISTED_MINTS = setOf(
    "So11111111111111111111111111111111111111112", // SOL
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
    "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", // BONK
    "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", // WIF
    "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", // JUP
    "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", // RAY
    "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", // ORCA
    "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",   // mSOL
    "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3", // PYTH
    "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", // JITO
    "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof", // RENDER
)

private val CLONE_INDICATOR_NAMES = setOf(
    "official", "real", "og", "original", "v2", "v3", "relaunch", "relaunched",
    "new", "2.0", "2024", "2025", "redux", "classic", "legacy", "airdrop",
)

private val CLONE_PATTERNS = listOf(
    Regex("""official\s+""", RegexOption.IGNORE_CASE),
    Regex("""real\s+""", RegexOption.IGNORE_CASE),
    Regex("""og\s+""", RegexOption.IGNORE_CASE),
    Regex("""\bv[2-9]\b""", RegexOption.IGNORE_CASE),
    Regex("""2\.0|3\.0"""),
    Regex("""relaunch""", RegexOption.IGNORE_CASE),
    Regex("""airdrop""", RegexOption.IGNORE_CASE),
    Regex("""fork of""", RegexOption.IGNORE_CASE),
    Regex("""by\s+(elon|vitalik|sam|sbf)""", RegexOption.IGNORE_CASE),
)

// ─────────────────────────────────────────────────────────────────────────────
// Checker
// ─────────────────────────────────────────────────────────────────────────────

class TokenSafetyChecker(private val cfg: () -> BotConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, SafetyReport>()

    companion object {
        private const val TAG = "SafetyChecker"

        private const val LIQUIDITY_HISTORY_MAX_SIZE = 10
        private const val LIQUIDITY_CONFLICT_THRESHOLD = 0.15
        private const val MIN_LIQUIDITY_FOR_CONFLICT_CHECK = 3_000.0

        fun isWhitelisted(mint: String): Boolean = mint in WHITELISTED_MINTS
        fun getWhitelistedMints(): Set<String> = WHITELISTED_MINTS
    }

    private val liquidityHistory =
        ConcurrentHashMap<String, MutableList<Pair<Long, Double>>>()

    fun checkLiquidityConflict(mint: String, currentLiquidity: Double): Pair<Boolean, String> {
        val history = liquidityHistory.getOrPut(mint) { mutableListOf() }
        val now = System.currentTimeMillis()

        history.add(now to currentLiquidity)

        while (history.size > LIQUIDITY_HISTORY_MAX_SIZE) {
            history.removeAt(0)
        }

        if (history.size < 2) return false to ""

        val fiveMinutesAgo = now - (5 * 60 * 1000L)
        val recentReadings = history.filter { it.first >= fiveMinutesAgo }
        if (recentReadings.size < 2) return false to ""

        val maxRecentLiq = recentReadings.maxOf { it.second }

        if (maxRecentLiq >= MIN_LIQUIDITY_FOR_CONFLICT_CHECK && currentLiquidity < 500.0) {
            val dropPct = ((maxRecentLiq - currentLiquidity) / maxRecentLiq * 100).toInt()
            val reason = "DATA_CONFLICT: Liquidity dropped ${dropPct}% ($${maxRecentLiq.toInt()} → $${currentLiquidity.toInt()})"
            ErrorLogger.warn(TAG, "🚨 $reason")
            return true to reason
        }

        if (maxRecentLiq >= MIN_LIQUIDITY_FOR_CONFLICT_CHECK) {
            val ratio = currentLiquidity / maxRecentLiq
            if (ratio <= LIQUIDITY_CONFLICT_THRESHOLD) {
                val dropPct = ((1 - ratio) * 100).toInt()
                val reason = "DATA_CONFLICT: Liquidity dropped ${dropPct}% ($${maxRecentLiq.toInt()} → $${currentLiquidity.toInt()})"
                ErrorLogger.warn(TAG, "🚨 $reason")
                return true to reason
            }
        }

        return false to ""
    }

    fun clearLiquidityHistory(mint: String) {
        liquidityHistory.remove(mint)
    }

    fun check(
        mint: String,
        symbol: String,
        name: String,
        pairCreatedAtMs: Long = 0L,
        currentLiquidityUsd: Double = -1.0,
    ): SafetyReport {
        if (mint in WHITELISTED_MINTS) {
            val safeReport = SafetyReport(
                tier = SafetyTier.SAFE,
                summary = "✅ VERIFIED: $symbol is a whitelisted major token",
                checkedAt = System.currentTimeMillis(),
                rugcheckStatus = "WHITELISTED",
                liqConfirmed = currentLiquidityUsd > 0,
            )
            cache[mint] = safeReport
            return safeReport
        }

        val cached = cache[mint]
        if (cached != null && !cached.isStale) return cached

        val isPaperMode = cfg().paperMode

        val hard = mutableListOf<String>()
        val soft = mutableListOf<Pair<String, Int>>()
        var penalty = 0

        var rugcheckStatus = "UNKNOWN"
        var liqConfirmed = false
        var liqEstimated = false
        var dataConflictFlag = false
        var dataConflictReason = ""
        var rugcheckTimeoutPenalty = 0

        if (currentLiquidityUsd >= 0) {
            val (hasConflict, conflictReason) = checkLiquidityConflict(mint, currentLiquidityUsd)
            if (hasConflict) {
                dataConflictFlag = true
                dataConflictReason = conflictReason
                soft.add(conflictReason to 50)
                penalty += 50

                if (!isPaperMode) {
                    hard.add(conflictReason)
                }
            }

            liqConfirmed = currentLiquidityUsd > 0
        }

        // ── 1. Rugcheck
        val rugcheck = fetchRugcheck(mint)
        val rcScore = rugcheck?.optInt("score_normalised", rugcheck.optInt("score", -1)) ?: -1

        var mintDisabled: Boolean? = null
        var freezeDisabled: Boolean? = null
        var lpLockPct = -1.0
        var topHolderPct = -1.0

        if (rugcheck == null || rcScore == -1) {
            rugcheckStatus = "TIMEOUT"
            if (isPaperMode) {
                rugcheckTimeoutPenalty = 10
                soft.add("Rugcheck API timeout (paper: -10 penalty)" to 10)
                penalty += 10
                ErrorLogger.debug(TAG, "Rugcheck TIMEOUT for $symbol: applying -10 penalty (paper mode)")
            } else {
                rugcheckTimeoutPenalty = 15
                rugcheckStatus = "PENDING_REVIEW"
                soft.add("Rugcheck API timeout (live: -15 penalty, PENDING_REVIEW)" to 15)
                penalty += 15
                ErrorLogger.warn(TAG, "Rugcheck TIMEOUT for $symbol: applying -15 penalty + PENDING_REVIEW (live mode)")
            }
        } else {
            rugcheckStatus = "CONFIRMED"
        }

        if (rugcheck != null) {
            val risks = rugcheck.optJSONArray("risks")
            if (risks != null) {
                for (i in 0 until risks.length()) {
                    val risk = risks.optJSONObject(i) ?: continue
                    val rName = risk.optString("name", "").lowercase()

                    when {
                        rName.contains("mint") && rName.contains("enabled") -> mintDisabled = false
                        rName.contains("freeze") && rName.contains("enabled") -> freezeDisabled = false
                        rName.contains("mint") && rName.contains("disabled") -> mintDisabled = true
                        rName.contains("freeze") && rName.contains("disabled") -> freezeDisabled = true
                    }
                }
            }

            val markets = rugcheck.optJSONArray("markets")
            if (markets != null && markets.length() > 0) {
                val market = markets.optJSONObject(0)
                val lp = market?.optJSONObject("lp")
                val locked = lp?.optDouble("lpLockedPct", -1.0) ?: -1.0
                if (locked >= 0) lpLockPct = locked
            }

            val topHolders = rugcheck.optJSONObject("topHolders")
            if (topHolders != null) {
                val pct = topHolders.optDouble("top10Pct", -1.0)
                if (pct >= 0) topHolderPct = pct
            }

            // ═══════════════════════════════════════════════════════════════════════
            // V5.6.9 FIX: RELAXED RC SCORING POLICY
            // 
            // PROBLEM: RC score=1 means "unknown/pending" (API hasn't processed yet).
            // Old policy hard-blocked score < 2, starving the bot of new token entries.
            // 
            // NEW POLICY:
            //   score=0: Confirmed dangerous → HARD BLOCK (both modes)
            //   score=1: Unknown/pending → SOFT PENALTY only (allow evaluation)
            //   score 2-4: Very dangerous → HARD BLOCK live, heavy penalty paper
            //   score 5-9: Dangerous → Heavy soft penalty (learn to avoid)
            //   score 10+: Normal penalty scaling
            // 
            // This ensures:
            // 1. New tokens (score=1) can be evaluated by other safety layers
            // 2. Paper mode learns realistic danger signals
            // 3. Live mode still protects real money
            // ═══════════════════════════════════════════════════════════════════════
            when {
                rcScore == 0 -> {
                    // Score 0 = CONFIRMED DANGEROUS - hard block in BOTH modes
                    hard.add("Rugcheck score 0/100 (CONFIRMED RUG RISK)")
                    ErrorLogger.error(TAG, "🚫 RC HARD BLOCK: $symbol score=0 (both paper and live)")
                }
                rcScore == 1 -> {
                    // V5.6.9 FIX: Score 1 = UNKNOWN/PENDING - apply soft penalty only
                    // The token is too new for Rugcheck to analyze. Let other safety
                    // layers (LP lock, mint auth, etc.) catch truly bad tokens.
                    soft.add("Rugcheck score pending ($rcScore/100 = unknown)" to 15)
                    penalty += 15
                    ErrorLogger.info(TAG, "⏳ RC PENDING: $symbol score=1 (unknown) → +15 penalty, allowing evaluation")
                }
                rcScore in 2..4 -> {
                    // Very dangerous - hard block in live, heavy penalty in paper
                    if (isPaperMode) {
                        soft.add("Rugcheck score $rcScore/100 (very dangerous)" to 40)
                        penalty += 40
                        ErrorLogger.warn(TAG, "RC PAPER PENALTY: $symbol score=$rcScore → +40 penalty (learning danger)")
                    } else {
                        hard.add("Rugcheck score $rcScore/100 (CONFIRMED RUG RISK)")
                        ErrorLogger.error(TAG, "🚫 RC HARD BLOCK: $symbol score=$rcScore (live mode)")
                    }
                }
                rcScore in 5..9 -> {
                    if (isPaperMode) {
                        // Paper: Heavy soft penalty to learn avoidance
                        soft.add("Rugcheck score $rcScore/100 (very dangerous)" to 35)
                        penalty += 35
                        ErrorLogger.warn(TAG, "RC PAPER PENALTY: $symbol score=$rcScore → +35 penalty (learning)")
                    } else {
                        // Live: Hard block for scores 5-9
                        hard.add("Rugcheck score $rcScore/100 (EXTREMELY DANGEROUS)")
                    }
                }
                rcScore in 10..24 -> {
                    soft.add("Rugcheck score risky ($rcScore/100)" to 30)
                    penalty += 30
                }
                rcScore in 25..39 -> {
                    soft.add("Rugcheck score low ($rcScore/100)" to 20)
                    penalty += 20
                }
                rcScore in 40..54 -> {
                    soft.add("Rugcheck score cautious ($rcScore/100)" to 15)
                    penalty += 15
                }
                rcScore in 55..69 -> {
                    soft.add("Rugcheck score borderline ($rcScore/100)" to 8)
                    penalty += 8
                }
                rcScore in 70..79 -> {
                    soft.add("Rugcheck score $rcScore/100 (moderate)" to 3)
                    penalty += 3
                }
            }
        }

        if (mintDisabled == null || freezeDisabled == null) {
            val rpcResult = checkMintFreezeViaRpc(mint)
            if (mintDisabled == null) mintDisabled = rpcResult.first
            if (freezeDisabled == null) freezeDisabled = rpcResult.second
        }

        // ── 2. Mint authority
        when (mintDisabled) {
            false -> {
                if (isPaperMode) {
                    soft.add("Mint authority ACTIVE (risky)" to 30)
                    penalty += 30
                } else {
                    hard.add("Mint authority is ACTIVE — devs can print new tokens")
                }
            }
            null -> {
                soft.add("Mint authority status unknown" to 5)
                penalty += 5
            }
            true -> Unit
        }

        // ── 3. Freeze authority
        when (freezeDisabled) {
            false -> {
                if (isPaperMode) {
                    soft.add("Freeze authority ACTIVE (risky)" to 30)
                    penalty += 30
                } else {
                    hard.add("Freeze authority is ACTIVE — devs can freeze your tokens")
                }
            }
            null -> {
                soft.add("Freeze authority status unknown" to 5)
                penalty += 5
            }
            true -> Unit
        }

        // ── 4. LP lock
        // V5.6.8 CRITICAL FIX: Unlocked liquidity should be HARD BLOCK in live mode!
        // User lost $200 to tokens with unlocked LP - they can rug at any time
        when {
            lpLockPct < 0 -> Unit  // Unknown, can't penalize
            lpLockPct < 30.0 -> {
                // EXTREMELY DANGEROUS: Almost no liquidity locked
                if (isPaperMode) {
                    // Paper: Learn this is dangerous but don't hard block
                    soft.add("LP only ${lpLockPct.toInt()}% locked (EXTREME RUG RISK)" to 40)
                    penalty += 40
                    ErrorLogger.warn(TAG, "⚠️ LP PAPER WARN: $symbol only ${lpLockPct.toInt()}% locked — high rug risk")
                } else {
                    // LIVE: HARD BLOCK - this is how rugs happen
                    hard.add("LP only ${lpLockPct.toInt()}% locked — EXTREME RUG RISK, devs can pull liquidity")
                    ErrorLogger.error(TAG, "🚫 LP HARD BLOCK: $symbol only ${lpLockPct.toInt()}% locked — blocking to prevent rug")
                }
            }
            lpLockPct < 50.0 -> {
                if (isPaperMode) {
                    soft.add("LP only ${lpLockPct.toInt()}% locked (HIGH rug risk)" to 25)
                    penalty += 25
                } else {
                    // LIVE: Block low-lock tokens
                    hard.add("LP only ${lpLockPct.toInt()}% locked — HIGH RUG RISK")
                }
            }
            lpLockPct < 70.0 -> {
                soft.add("LP ${lpLockPct.toInt()}% locked (moderate)" to 12)
                penalty += 12
            }
            lpLockPct < 90.0 -> {
                soft.add("LP ${lpLockPct.toInt()}% locked" to 5)
                penalty += 5
            }
        }

        // ── 5. Holder concentration
        when {
            topHolderPct < 0 -> Unit
            topHolderPct > 80.0 -> {
                soft.add("Top holders very concentrated (${topHolderPct.toInt()}%)" to 20)
                penalty += 20
            }
            topHolderPct > 60.0 -> {
                soft.add("Top holders concentrated (${topHolderPct.toInt()}%)" to 12)
                penalty += 12
            }
            topHolderPct > 40.0 -> {
                soft.add("Top holders moderate (${topHolderPct.toInt()}%)" to 5)
                penalty += 5
            }
        }

        // ── 6. Age
        val ageMinutes = if (pairCreatedAtMs > 0) {
            (System.currentTimeMillis() - pairCreatedAtMs) / 60_000.0
        } else {
            -1.0
        }

        // ── 7. Name duplicate detection
        val nameFlag = checkNameDuplicate(symbol, name, mint)
        when {
            nameFlag.startsWith("HARD:") -> hard.add(nameFlag.removePrefix("HARD: "))
            nameFlag.startsWith("SOFT:") -> {
                val msg = nameFlag.removePrefix("SOFT: ")
                soft.add(msg to 25)
                penalty += 25
            }
        }

        // ── 8. ScalingMode tier gates
        if (mint !in WHITELISTED_MINTS) {
            val solPxSc = WalletManager.lastKnownSolPrice
            val trsUsdSc = TreasuryManager.treasurySol * solPxSc
            val sTier = ScalingMode.activeTier(trsUsdSc)

            if (rcScore in 0..99 && rcScore < sTier.minRugcheckScore) {
                if (!(isPaperMode && rcScore in 0..9)) {
                    soft.add("Rugcheck $rcScore below tier min ${sTier.minRugcheckScore}" to 15)
                    penalty += 15
                }
            }

            if (sTier.requireLpLock90 && lpLockPct in 0.0..89.9) {
                soft.add("LP ${lpLockPct.toInt()}% — ${sTier.label} prefers 90%+" to 10)
                penalty += 10
            }

            if (sTier.requireTopHolder30 && topHolderPct > 30.0) {
                soft.add("Top10 ${topHolderPct.toInt()}% — ${sTier.label} prefers <30%" to 10)
                penalty += 10
            }
        }

        // ── 9. Bundle detection
        var bundleRisk = "UNKNOWN"
        var bundleType = "NONE"
        var bundleRecommendation = "UNKNOWN"
        var bundleReason = ""
        var firstBlockSupplyPct = -1.0
        var firstBlockBuyers = -1

        if (topHolderPct > 0) {
            val quickRisk = BundleDetector.quickRiskCheck(
                firstBlockBuyers = 5,
                firstBlockSupplyPct = topHolderPct,
                topHolderPct = topHolderPct,
            )

            bundleRisk = quickRisk.name
            bundleType = when {
                topHolderPct > 60 -> "DEV_BUNDLE"
                topHolderPct > 40 -> "MIXED"
                else -> "NONE"
            }

            when (quickRisk) {
                BundleDetector.BundleRisk.HIGH -> {
                    if (isPaperMode) {
                        soft.add("Bundle risk HIGH: ${topHolderPct.toInt()}% concentrated" to 35)
                        penalty += 35
                    } else {
                        soft.add("Bundle risk HIGH: ${topHolderPct.toInt()}% concentrated - monitor closely" to 40)
                        penalty += 40
                    }
                    bundleRecommendation = "CAUTION"
                    bundleReason = "High concentration (${topHolderPct.toInt()}%) - could be rug or pump"
                }
                BundleDetector.BundleRisk.MEDIUM -> {
                    soft.add("Bundle risk MEDIUM: ${topHolderPct.toInt()}% concentrated" to 15)
                    penalty += 15
                    bundleRecommendation = "CAUTION"
                    bundleReason = "Moderate concentration - watch for sells"
                }
                BundleDetector.BundleRisk.LOW -> {
                    bundleRecommendation = "SAFE"
                    bundleReason = "No dangerous bundle patterns"
                }
                else -> {
                    bundleRecommendation = "UNKNOWN"
                    bundleReason = "Could not analyze bundles"
                }
            }

            firstBlockSupplyPct = topHolderPct
        }

        val tier = when {
            hard.isNotEmpty() -> SafetyTier.HARD_BLOCK
            penalty > 30 -> SafetyTier.CAUTION
            else -> SafetyTier.SAFE
        }

        val summary = buildSummary(tier, hard, soft, rcScore, ageMinutes)

        val report = SafetyReport(
            tier = tier,
            hardBlockReasons = hard,
            softPenalties = soft,
            entryScorePenalty = penalty,
            rugcheckScore = rcScore,
            mintAuthorityDisabled = mintDisabled,
            freezeAuthorityDisabled = freezeDisabled,
            lpLockPct = lpLockPct,
            topHolderPct = topHolderPct,
            tokenAgeMinutes = ageMinutes,
            nameFlag = nameFlag,
            summary = summary,
            checkedAt = System.currentTimeMillis(),
            bundleRisk = bundleRisk,
            bundleType = bundleType,
            firstBlockSupplyPct = firstBlockSupplyPct,
            firstBlockBuyers = firstBlockBuyers,
            bundleRecommendation = bundleRecommendation,
            bundleReason = bundleReason,
            rugcheckStatus = rugcheckStatus,
            liqConfirmed = liqConfirmed,
            liqEstimated = liqEstimated,
            dataConflictFlag = dataConflictFlag,
            dataConflictReason = dataConflictReason,
            rugcheckTimeoutPenalty = rugcheckTimeoutPenalty,
        )

        cache[mint] = report
        return report
    }

    private fun fetchRugcheck(mint: String): JSONObject? {
        val url = "https://api.rugcheck.xyz/v1/tokens/$mint/report"
        val maxRetries = 3
        val retryDelayMs = 500L

        repeat(maxRetries) { attempt ->
            try {
                val body = get(url)
                if (body != null) {
                    val json = JSONObject(body)
                    if (json.has("score") || json.has("score_normalised")) {
                        if (attempt > 0) {
                            ErrorLogger.info(TAG, "Rugcheck success on retry ${attempt + 1} for ${mint.take(12)}")
                        }
                        return json
                    }
                }

                if (attempt < maxRetries - 1) {
                    ErrorLogger.debug(TAG, "Rugcheck attempt ${attempt + 1}/$maxRetries failed for ${mint.take(12)}, retrying...")
                    Thread.sleep(retryDelayMs)
                }
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) {
                    ErrorLogger.debug(TAG, "Rugcheck attempt ${attempt + 1}/$maxRetries error: ${e.message?.take(40)}, retrying...")
                    Thread.sleep(retryDelayMs)
                } else {
                    ErrorLogger.warn(TAG, "Rugcheck failed after $maxRetries attempts for ${mint.take(12)}: ${e.message?.take(40)}")
                }
            }
        }

        ErrorLogger.warn(TAG, "Rugcheck exhausted $maxRetries retries for ${mint.take(12)}")
        return null
    }

    private fun checkMintFreezeViaRpc(mint: String): Pair<Boolean?, Boolean?> {
        val rpcUrl = cfg().rpcUrl
        val payload = """
            {"jsonrpc":"2.0","id":1,"method":"getAccountInfo",
             "params":["$mint",{"encoding":"jsonParsed","commitment":"confirmed"}]}
        """.trimIndent()

        val body = post(rpcUrl, payload) ?: return Pair(null, null)

        return try {
            val info = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONObject("value")
                ?.optJSONObject("data")
                ?.optJSONObject("parsed")
                ?.optJSONObject("info")
                ?: return Pair(null, null)

            val mintAuth = info.opt("mintAuthority")
            val freezeAuth = info.opt("freezeAuthority")

            val mintDisabled = mintAuth == null || mintAuth == JSONObject.NULL
            val freezeDisabled = freezeAuth == null || freezeAuth == JSONObject.NULL

            Pair(mintDisabled, freezeDisabled)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun checkNameDuplicate(symbol: String, name: String, mint: String = ""): String {
        if (mint.isNotBlank() && mint in WHITELISTED_MINTS) {
            return ""
        }

        val nameLower = name.lowercase().trim()

        for (indicator in CLONE_INDICATOR_NAMES) {
            if (nameLower.contains(indicator)) {
                return "SOFT: Name contains suspicious pattern: \"$indicator\""
            }
        }

        for (pattern in CLONE_PATTERNS) {
            if (pattern.containsMatchIn(nameLower)) {
                return "SOFT: Name contains clone pattern: \"${pattern.pattern}\""
            }
        }

        return ""
    }

    @Suppress("unused")
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    @Suppress("unused")
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    private fun buildSummary(
        tier: SafetyTier,
        hard: List<String>,
        soft: List<Pair<String, Int>>,
        rcScore: Int,
        ageMinutes: Double = -1.0,
    ): String {
        val ageStr = when {
            ageMinutes < 0 -> ""
            ageMinutes < 60 -> " | ${ageMinutes.toInt()}m old"
            ageMinutes < 1440 -> " | ${(ageMinutes / 60).toInt()}h old"
            else -> " | ${(ageMinutes / 1440).toInt()}d old"
        }

        return when (tier) {
            SafetyTier.HARD_BLOCK -> "🚫 BLOCKED: ${hard.firstOrNull() ?: "unknown"}"
            SafetyTier.CAUTION -> {
                val top = soft.sortedByDescending { it.second }
                    .take(2)
                    .joinToString(", ") { it.first }
                val rc = if (rcScore >= 0) " RC:$rcScore" else ""
                "⚠️ CAUTION$rc$ageStr — $top"
            }
            SafetyTier.SAFE -> {
                val rc = if (rcScore >= 0) " RC:$rcScore/100" else ""
                "✅ SAFE$rc$ageStr"
            }
        }
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AATE/1.0")
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun post(url: String, body: String): String? = try {
        val mediaType = "application/json".toMediaType()
        val rb = body.toRequestBody(mediaType)

        val req = Request.Builder()
            .url(url)
            .post(rb)
            .header("Content-Type", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }
}