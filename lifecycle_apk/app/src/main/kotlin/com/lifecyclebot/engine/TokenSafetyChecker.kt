package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Safety result
// ─────────────────────────────────────────────────────────────────────────────

enum class SafetyTier {
    SAFE,           // passes all checks
    CAUTION,        // soft penalties applied but tradeable
    HARD_BLOCK,     // never trade
}

data class SafetyReport(
    val tier: SafetyTier = SafetyTier.SAFE,
    val hardBlockReasons: List<String> = emptyList(),
    val softPenalties: List<Pair<String, Int>> = emptyList(), // reason → score penalty
    val entryScorePenalty: Int = 0,       // total penalty to subtract from entry score
    val rugcheckScore: Int = -1,          // 0-100, -1 = not fetched
    val mintAuthorityDisabled: Boolean? = null,
    val freezeAuthorityDisabled: Boolean? = null,
    val lpLockPct: Double = -1.0,
    val topHolderPct: Double = -1.0,
    val tokenAgeMinutes: Double = -1.0,
    val nameFlag: String = "",            // empty = clean
    val summary: String = "",
    val checkedAt: Long = 0L,
    // Bundle detection fields
    val bundleRisk: String = "UNKNOWN",           // LOW, MEDIUM, HIGH, UNKNOWN
    val bundleType: String = "NONE",              // NONE, DEV_BUNDLE, SNIPER_BUNDLE, PUMP_BUNDLE, etc.
    val firstBlockSupplyPct: Double = -1.0,       // % of supply bought in first block
    val firstBlockBuyers: Int = -1,               // Number of unique wallets in first block
    val bundleRecommendation: String = "UNKNOWN", // SAFE, CAUTION, AVOID
    val bundleReason: String = "",                // Explanation of bundle analysis
) {
    val isBlocked  get() = tier == SafetyTier.HARD_BLOCK
    val ageMinutes get() = (System.currentTimeMillis() - checkedAt) / 60_000.0
    val isStale    get() = ageMinutes > 10.0   // re-check every 10 min
    
    // Bundle risk helpers
    val hasDangerousBundles get() = bundleRisk == "HIGH"
    val hasModerateBundle get() = bundleRisk == "MEDIUM"
    val hasPumpBundle get() = bundleType == "PUMP_BUNDLE"
}

// ─────────────────────────────────────────────────────────────────────────────
// WHITELIST — Known legitimate token MINTS that should ALWAYS be allowed
// These are verified contract addresses of major Solana tokens
// ─────────────────────────────────────────────────────────────────────────────

private val WHITELISTED_MINTS = setOf(
    // Native SOL (wrapped)
    "So11111111111111111111111111111111111111112",
    // USDC
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    // USDT
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
    // Bonk
    "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
    // WIF (dogwifhat)
    "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm",
    // JUP (Jupiter)
    "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
    // RAY (Raydium)
    "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R",
    // ORCA
    "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE",
    // mSOL (Marinade)
    "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",
    // PYTH
    "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3",
    // JITO
    "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn",
    // RENDER
    "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof",
)

// ─────────────────────────────────────────────────────────────────────────────
// Known CLONE patterns — names/symbols that indicate FAKE versions of real tokens
// We only block if the symbol/name EXACTLY matches AND the mint is NOT whitelisted
// ─────────────────────────────────────────────────────────────────────────────

private val CLONE_INDICATOR_NAMES = setOf(
    // These are scam indicators when paired with major token names
    "official", "real", "og", "original", "v2", "v3", "relaunch", "relaunched",
    "new", "2.0", "2024", "2025", "redux", "classic", "legacy", "airdrop",
)

// Patterns that in a token NAME strongly suggest a clone/scam
private val CLONE_PATTERNS = listOf(
    Regex("""official\s+""", RegexOption.IGNORE_CASE),
    Regex("""real\s+""",     RegexOption.IGNORE_CASE),
    Regex("""og\s+""",       RegexOption.IGNORE_CASE),
    Regex("""\bv[2-9]\b""",  RegexOption.IGNORE_CASE),
    Regex("""2\.0|3\.0"""),
    Regex("""relaunch""",    RegexOption.IGNORE_CASE),
    Regex("""airdrop""",     RegexOption.IGNORE_CASE),
    Regex("""fork of""",     RegexOption.IGNORE_CASE),
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

    // Cache: mint → report (valid for 10 min)
    private val cache = mutableMapOf<String, SafetyReport>()

    // ── public interface ──────────────────────────────────────────────
    
    companion object {
        /** Check if a token mint is in the whitelist of verified major tokens */
        fun isWhitelisted(mint: String): Boolean = mint in WHITELISTED_MINTS
        
        /** Get the list of all whitelisted mints */
        fun getWhitelistedMints(): Set<String> = WHITELISTED_MINTS
    }

    /**
     * Run all safety checks for a token.
     * Returns cached result if < 10 min old.
     * Designed to run on a background thread.
     * 
     * WHITELISTED tokens (major SOL coins like SOL, USDC, BONK, JUP, etc.)
     * are automatically marked SAFE without any checks.
     */
    fun check(
        mint: String,
        symbol: String,
        name: String,
        pairCreatedAtMs: Long = 0L,
    ): SafetyReport {
        // FAST PATH: Whitelisted tokens are always safe
        if (mint in WHITELISTED_MINTS) {
            val safeReport = SafetyReport(
                tier = SafetyTier.SAFE,
                summary = "✅ VERIFIED: $symbol is a whitelisted major token",
                checkedAt = System.currentTimeMillis(),
            )
            cache[mint] = safeReport
            return safeReport
        }
        
        val cached = cache[mint]
        if (cached != null && !cached.isStale) return cached

        val hard    = mutableListOf<String>()
        val soft    = mutableListOf<Pair<String, Int>>()
        var penalty = 0
        
        // Paper mode flag - used for lenient safety checks
        val isPaperMode = cfg().paperMode

        // ── 1. Rugcheck.xyz composite report (free, no key) ───────────
        val rugcheck = fetchRugcheck(mint)
        // Use score_normalised (0-100) not raw score (can be thousands)
        val rcScore  = rugcheck?.optInt("score_normalised", rugcheck.optInt("score", -1)) ?: -1
        var mintDisabled   : Boolean? = null
        var freezeDisabled : Boolean? = null
        var lpLockPct      = -1.0
        var topHolderPct   = -1.0

        if (rugcheck != null) {
            // Rugcheck returns risks as an array of {name, description, level}
            // level: "danger" | "warn" | "info"
            val risks = rugcheck.optJSONArray("risks")
            if (risks != null) {
                for (i in 0 until risks.length()) {
                    val risk  = risks.optJSONObject(i) ?: continue
                    val rName = risk.optString("name", "").lowercase()
                    val level = risk.optString("level", "").lowercase()

                    when {
                        rName.contains("mint") && rName.contains("enabled") ->
                            mintDisabled = false
                        rName.contains("freeze") && rName.contains("enabled") ->
                            freezeDisabled = false
                        rName.contains("mint") && rName.contains("disabled") ->
                            mintDisabled = true
                        rName.contains("freeze") && rName.contains("disabled") ->
                            freezeDisabled = true
                    }
                }
            }

            // LP lock percentage
            val markets = rugcheck.optJSONArray("markets")
            if (markets != null && markets.length() > 0) {
                val market   = markets.optJSONObject(0)
                val lp       = market?.optJSONObject("lp")
                val locked   = lp?.optDouble("lpLockedPct", -1.0) ?: -1.0
                if (locked >= 0) lpLockPct = locked
            }

            // Top holder concentration
            val topHolders = rugcheck.optJSONObject("topHolders")
            if (topHolders != null) {
                val pct = topHolders.optDouble("top10Pct", -1.0)
                if (pct >= 0) topHolderPct = pct
            }

            // V8+ SKEPTICAL scoring - don't blindly trust rugcheck scores
            // But also don't block aggressively - new tokens often have lower scores
            // Most meme coins have scores 10-40 which is normal for new tokens
            // Only HARD BLOCK truly dangerous tokens (score 0-9 = confirmed scams)
            when {
                rcScore in 0..9 -> {
                    // EXTREMELY dangerous - confirmed scam/rug - block in all modes
                    hard.add("Rugcheck score $rcScore/100 (EXTREMELY DANGEROUS)")
                }
                rcScore in 10..24 -> {
                    // Risky but not confirmed scam - soft penalty, let FDG decide
                    // Many legitimate new tokens have scores in this range
                    soft.add("Rugcheck score risky ($rcScore/100)" to 30)
                    penalty += 30
                }
                rcScore in 25..39 -> {
                    // Borderline risky - moderate penalty
                    soft.add("Rugcheck score low ($rcScore/100)" to 20)
                    penalty += 20
                }
                rcScore in 40..54 -> {
                    // Soft penalty instead of hard block - let other factors decide
                    soft.add("Rugcheck score cautious ($rcScore/100)" to 15)
                    penalty += 15
                }
                rcScore in 55..69 -> {
                    soft.add("Rugcheck score borderline ($rcScore/100)" to 8)
                    penalty += 8
                }
                rcScore in 70..79 -> {
                    // Mild skepticism
                    soft.add("Rugcheck score $rcScore/100 (moderate)" to 3)
                    penalty += 3
                }
                // 80+ = safe, no penalty
            }
        }

        // Fallback: check mint/freeze via Solana RPC if rugcheck didn't tell us
        if (mintDisabled == null || freezeDisabled == null) {
            val rpcResult = checkMintFreezeViaRpc(mint)
            if (mintDisabled == null)   mintDisabled   = rpcResult.first
            if (freezeDisabled == null) freezeDisabled = rpcResult.second
        }

        // ── 2. Mint authority — hard block in REAL mode, soft penalty in PAPER ──
        when (mintDisabled) {
            false -> {
                if (isPaperMode) {
                    soft.add("Mint authority ACTIVE (risky)" to 30)
                    penalty += 30
                } else {
                    hard.add("Mint authority is ACTIVE — devs can print new tokens")
                }
            }
            null  -> { soft.add("Mint authority status unknown" to 5); penalty += 5 }
            true  -> { /* safe */ }
        }

        // ── 3. Freeze authority — hard block in REAL mode, soft penalty in PAPER ──
        when (freezeDisabled) {
            false -> {
                if (isPaperMode) {
                    soft.add("Freeze authority ACTIVE (risky)" to 30)
                    penalty += 30
                } else {
                    hard.add("Freeze authority is ACTIVE — devs can freeze your tokens")
                }
            }
            null  -> { soft.add("Freeze authority status unknown" to 5); penalty += 5 }
            true  -> { /* safe */ }
        }

        // ── 4. LP lock — soft penalty only (no hard block) ───────────
        // Many legitimate tokens don't have 80% LP locked, especially early stage
        when {
            lpLockPct < 0       -> { /* unknown - don't penalize */ }
            lpLockPct < 50.0    -> { soft.add("LP only ${lpLockPct.toInt()}% locked (low)" to 15); penalty += 15 }
            lpLockPct < 70.0    -> { soft.add("LP ${lpLockPct.toInt()}% locked (moderate)" to 8); penalty += 8 }
            lpLockPct < 90.0    -> { soft.add("LP ${lpLockPct.toInt()}% locked" to 3); penalty += 3 }
        }

        // ── 5. Holder concentration — soft penalty only ───────────────
        // High concentration is a yellow flag, not a hard block
        when {
            topHolderPct < 0    -> { /* unknown - don't penalize */ }
            topHolderPct > 80.0 -> { soft.add("Top holders very concentrated (${topHolderPct.toInt()}%)" to 20); penalty += 20 }
            topHolderPct > 60.0 -> { soft.add("Top holders concentrated (${topHolderPct.toInt()}%)" to 12); penalty += 12 }
            topHolderPct > 40.0 -> { soft.add("Top holders moderate (${topHolderPct.toInt()}%)" to 5); penalty += 5 }
        }

        // ── 6. Token age — informational only, no penalty ────────────
        // We want early entries. Age is logged for context but never blocks
        // or penalises a trade. Other checks (mint auth, LP lock, rugcheck)
        // are sufficient protection on new tokens.
        val ageMinutes = if (pairCreatedAtMs > 0)
            (System.currentTimeMillis() - pairCreatedAtMs) / 60_000.0
        else -1.0
        // Age is stored in the report for UI display only — no score impact.

        // ── 7. Name / symbol duplicate detection ─────────────────────
        val nameFlag = checkNameDuplicate(symbol, name, mint)
        when {
            nameFlag.startsWith("HARD:") -> hard.add(nameFlag.removePrefix("HARD: "))
            nameFlag.startsWith("SOFT:") -> {
                val msg = nameFlag.removePrefix("SOFT: ")
                soft.add(msg to 25)
                penalty += 25
            }
        }

        // ── 8. ScalingMode tier safety gates ─────────────────────────
        // Skip tier gates for whitelisted tokens (they're verified major coins)
        if (mint !in WHITELISTED_MINTS) {
            val solPxSc  = WalletManager.lastKnownSolPrice
            val trsUsdSc = TreasuryManager.treasurySol * solPxSc
            val sTier    = ScalingMode.activeTier(trsUsdSc)
            if (rcScore in 0..99 && rcScore < sTier.minRugcheckScore) {
                // Only soft penalty, not hard block - allows trading with caution
                soft.add("Rugcheck $rcScore below tier min ${sTier.minRugcheckScore}" to 15)
                penalty += 15
            }
            // LP lock and top holder requirements are now soft penalties only
            if (sTier.requireLpLock90 && lpLockPct in 0.0..89.9) {
                soft.add("LP ${lpLockPct.toInt()}% — ${sTier.label} prefers 90%+" to 10)
                penalty += 10
            }
            if (sTier.requireTopHolder30 && topHolderPct > 30.0) {
                soft.add("Top10 ${topHolderPct.toInt()}% — ${sTier.label} prefers <30%" to 10)
                penalty += 10
            }
        }
        
        // ── 9. Bundle Detection (Quick Risk Check) ─────────────────────
        // Full bundle analysis is expensive, so we do a quick risk estimate
        // based on holder concentration (full analysis runs async later)
        var bundleRisk = "UNKNOWN"
        var bundleType = "NONE"
        var bundleRecommendation = "UNKNOWN"
        var bundleReason = ""
        var firstBlockSupplyPct = -1.0
        var firstBlockBuyers = -1
        
        // Quick bundle risk check based on holder data we already have
        if (topHolderPct > 0) {
            val quickRisk = BundleDetector.quickRiskCheck(
                firstBlockBuyers = 5,  // Estimate - real analysis runs async
                firstBlockSupplyPct = topHolderPct,  // Use holder% as proxy
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
                    // DANGEROUS bundle - add penalty but don't hard block
                    // (full analysis may reveal it's a PUMP bundle which is good)
                    if (isPaperMode) {
                        soft.add("Bundle risk HIGH: ${topHolderPct.toInt()}% concentrated" to 35)
                        penalty += 35
                    } else {
                        // In live mode, we're more cautious but still allow with penalty
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
            
            firstBlockSupplyPct = topHolderPct  // Proxy value
        }

        // ── Build report ──────────────────────────────────────────────
        val tier = if (hard.isNotEmpty()) SafetyTier.HARD_BLOCK
                   else if (penalty > 30) SafetyTier.CAUTION
                   else SafetyTier.SAFE

        val summary = buildSummary(tier, hard, soft, rcScore, ageMinutes)

        val report = SafetyReport(
            tier                    = tier,
            hardBlockReasons        = hard,
            softPenalties           = soft,
            entryScorePenalty       = penalty,
            rugcheckScore           = rcScore,
            mintAuthorityDisabled   = mintDisabled,
            freezeAuthorityDisabled = freezeDisabled,
            lpLockPct               = lpLockPct,
            topHolderPct            = topHolderPct,
            tokenAgeMinutes         = ageMinutes,
            nameFlag                = nameFlag,
            summary                 = summary,
            checkedAt               = System.currentTimeMillis(),
            // Bundle detection fields
            bundleRisk              = bundleRisk,
            bundleType              = bundleType,
            firstBlockSupplyPct     = firstBlockSupplyPct,
            firstBlockBuyers        = firstBlockBuyers,
            bundleRecommendation    = bundleRecommendation,
            bundleReason            = bundleReason,
        )
        cache[mint] = report
        return report
    }

    // ── Rugcheck.xyz API ──────────────────────────────────────────────

    /**
     * Rugcheck.xyz free API — no key required.
     * Returns full token report including risks, LP info, holder data.
     * Endpoint: https://api.rugcheck.xyz/v1/tokens/{mint}/report
     * 
     * RETRY MECHANISM: Attempts up to 3 times with 500ms delay between retries
     * to handle transient API timeouts that were blocking live trades.
     */
    private fun fetchRugcheck(mint: String): JSONObject? {
        val url = "https://api.rugcheck.xyz/v1/tokens/$mint/report"
        val maxRetries = 3
        val retryDelayMs = 500L
        
        repeat(maxRetries) { attempt ->
            try {
                val body = get(url)
                if (body != null) {
                    val json = JSONObject(body)
                    // Verify we got a valid response with a score
                    if (json.has("score") || json.has("score_normalised")) {
                        if (attempt > 0) {
                            ErrorLogger.info("SafetyChecker", 
                                "Rugcheck success on retry ${attempt + 1} for ${mint.take(12)}")
                        }
                        return json
                    }
                }
                
                // If we got here, response was null or invalid - retry if not last attempt
                if (attempt < maxRetries - 1) {
                    ErrorLogger.debug("SafetyChecker", 
                        "Rugcheck attempt ${attempt + 1}/$maxRetries failed for ${mint.take(12)}, retrying...")
                    Thread.sleep(retryDelayMs)
                }
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) {
                    ErrorLogger.debug("SafetyChecker", 
                        "Rugcheck attempt ${attempt + 1}/$maxRetries error: ${e.message?.take(40)}, retrying...")
                    Thread.sleep(retryDelayMs)
                } else {
                    ErrorLogger.warn("SafetyChecker", 
                        "Rugcheck failed after $maxRetries attempts for ${mint.take(12)}: ${e.message?.take(40)}")
                }
            }
        }
        
        // All retries failed
        ErrorLogger.warn("SafetyChecker", "Rugcheck exhausted $maxRetries retries for ${mint.take(12)}")
        return null
    }

    // ── Solana RPC mint/freeze check ─────────────────────────────────

    /**
     * getMint via Solana JSON-RPC.
     * Returns Pair(mintAuthorityDisabled, freezeAuthorityDisabled).
     * null = couldn't determine.
     */
    private fun checkMintFreezeViaRpc(mint: String): Pair<Boolean?, Boolean?> {
        val rpcUrl  = cfg().rpcUrl
        val payload = """
            {"jsonrpc":"2.0","id":1,"method":"getAccountInfo",
             "params":["$mint",{"encoding":"jsonParsed","commitment":"confirmed"}]}
        """.trimIndent()

        val body = post(rpcUrl, payload) ?: return Pair(null, null)
        return try {
            val json    = JSONObject(body)
            val info    = json.optJSONObject("result")
                ?.optJSONObject("value")
                ?.optJSONObject("data")
                ?.optJSONObject("parsed")
                ?.optJSONObject("info")
                ?: return Pair(null, null)

            // mintAuthority: null = disabled, string = active
            val mintAuth   = info.opt("mintAuthority")
            val freezeAuth = info.opt("freezeAuthority")

            val mintDisabled   = mintAuth   == null || mintAuth   == JSONObject.NULL
            val freezeDisabled = freezeAuth == null || freezeAuth == JSONObject.NULL

            Pair(mintDisabled, freezeDisabled)
        } catch (_: Exception) { Pair(null, null) }
    }

    // ── Name duplicate detection ──────────────────────────────────────

    /**
     * Returns:
     *   "HARD: <reason>"  — hard block
     *   "SOFT: <reason>"  — soft penalty
     *   ""                — clean
     *   
     * IMPORTANT: Whitelisted mints are ALWAYS clean - they are the real tokens
     */
    private fun checkNameDuplicate(symbol: String, name: String, mint: String = ""): String {
        // If this is a whitelisted mint, it's the REAL token - always allow
        if (mint.isNotBlank() && mint in WHITELISTED_MINTS) {
            return ""  // Clean - this is a verified legitimate token
        }
        
        val symLower  = symbol.lowercase().trim()
        val nameLower = name.lowercase().trim()

        // Check for clone indicators in the name (but not if it's a whitelisted token)
        for (indicator in CLONE_INDICATOR_NAMES) {
            if (nameLower.contains(indicator)) {
                return "SOFT: Name contains suspicious pattern: \"$indicator\""
            }
        }

        // Check for clone patterns
        for (pattern in CLONE_PATTERNS) {
            if (pattern.containsMatchIn(nameLower)) {
                return "SOFT: Name contains clone pattern: \"${pattern.pattern}\""
            }
        }

        return ""
    }

    /**
     * Normalized Levenshtein similarity: 0.0 (nothing in common) to 1.0 (identical)
     */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                           else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    // ── Summary builder ───────────────────────────────────────────────

    private fun buildSummary(
        tier: SafetyTier,
        hard: List<String>,
        soft: List<Pair<String, Int>>,
        rcScore: Int,
        ageMinutes: Double = -1.0,
    ): String {
        val ageStr = when {
            ageMinutes < 0    -> ""
            ageMinutes < 60   -> " | ${ageMinutes.toInt()}m old"
            ageMinutes < 1440 -> " | ${(ageMinutes / 60).toInt()}h old"
            else              -> " | ${(ageMinutes / 1440).toInt()}d old"
        }
        return when (tier) {
            SafetyTier.HARD_BLOCK -> "🚫 BLOCKED: ${hard.first()}"
            SafetyTier.CAUTION    -> {
                val top = soft.sortedByDescending { it.second }.take(2)
                    .joinToString(", ") { it.first }
                val rc  = if (rcScore >= 0) " RC:$rcScore" else ""
                "⚠️ CAUTION$rc$ageStr — $top"
            }
            SafetyTier.SAFE       -> {
                val rc = if (rcScore >= 0) " RC:$rcScore/100" else ""
                "✅ SAFE$rc$ageStr"
            }
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "LifecycleBot/1.0")
            .header("Accept",     "application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    private fun post(url: String, body: String): String? = try {
        val mediaType = "application/json".toMediaType()
        val rb = body.toRequestBody(mediaType)
        val req = Request.Builder().url(url).post(rb)
            .header("Content-Type", "application/json").build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
