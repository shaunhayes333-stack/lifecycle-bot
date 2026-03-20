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
) {
    val isBlocked  get() = tier == SafetyTier.HARD_BLOCK
    val ageMinutes get() = (System.currentTimeMillis() - checkedAt) / 60_000.0
    val isStale    get() = ageMinutes > 10.0   // re-check every 10 min
}

// ─────────────────────────────────────────────────────────────────────────────
// Known token blocklist — names/symbols we block clones of
// ─────────────────────────────────────────────────────────────────────────────

private val KNOWN_TOKEN_BLOCKLIST = setOf(
    // Tier 1 majors — any clone of these is almost certainly a scam
    "bonk", "wif", "dogwifhat", "pepe", "shib", "shibinu", "doge", "dogecoin",
    "floki", "samo", "samoyedcoin", "orca", "raydium", "serum", "mango",
    "solana", "sol", "usdc", "usdt", "dai", "eth", "bitcoin", "btc",
    "jupiter", "jup", "pyth", "drift", "jito", "marinade", "msol",
    "render", "helium", "hnt", "stepn", "gmt", "gene", "star atlas",
    "cope", "media", "monkey", "monke", "gorilla",
    // Common pump patterns
    "elon", "trump", "biden", "maga", "grok", "ai", "gpt", "openai",
    "official", "real", "og", "original", "v2", "v3", "relaunch", "relaunched",
    "new", "2.0", "2024", "2025", "redux", "classic", "legacy",
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

    /**
     * Run all safety checks for a token.
     * Returns cached result if < 10 min old.
     * Designed to run on a background thread.
     */
    fun check(
        mint: String,
        symbol: String,
        name: String,
        pairCreatedAtMs: Long = 0L,
    ): SafetyReport {
        val cached = cache[mint]
        if (cached != null && !cached.isStale) return cached

        val hard    = mutableListOf<String>()
        val soft    = mutableListOf<Pair<String, Int>>()
        var penalty = 0

        // ── 1. Rugcheck.xyz composite report (free, no key) ───────────
        val rugcheck = fetchRugcheck(mint)
        val rcScore  = rugcheck?.optInt("score", -1) ?: -1
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

            // Score-based hard block
            if (rcScore in 0..69) {
                hard.add("Rugcheck score $rcScore/100 (minimum 70 required)")
            } else if (rcScore in 70..79) {
                soft.add("Rugcheck score borderline ($rcScore/100)" to 10)
                penalty += 10
            }
        }

        // Fallback: check mint/freeze via Solana RPC if rugcheck didn't tell us
        if (mintDisabled == null || freezeDisabled == null) {
            val rpcResult = checkMintFreezeViaRpc(mint)
            if (mintDisabled == null)   mintDisabled   = rpcResult.first
            if (freezeDisabled == null) freezeDisabled = rpcResult.second
        }

        // ── 2. Mint authority hard block ──────────────────────────────
        when (mintDisabled) {
            false -> hard.add("Mint authority is ACTIVE — devs can print new tokens")
            null  -> { soft.add("Mint authority status unknown" to 5); penalty += 5 }
            true  -> { /* safe */ }
        }

        // ── 3. Freeze authority hard block ────────────────────────────
        when (freezeDisabled) {
            false -> hard.add("Freeze authority is ACTIVE — devs can freeze your tokens")
            null  -> { soft.add("Freeze authority status unknown" to 5); penalty += 5 }
            true  -> { /* safe */ }
        }

        // ── 4. LP lock hard block ─────────────────────────────────────
        when {
            lpLockPct < 0       -> { soft.add("LP lock status unknown" to 8); penalty += 8 }
            lpLockPct < 80.0    -> hard.add("LP only ${lpLockPct.toInt()}% locked (minimum 80%)")
            lpLockPct < 90.0    -> { soft.add("LP ${lpLockPct.toInt()}% locked (low)" to 5); penalty += 5 }
        }

        // ── 5. Holder concentration hard block ───────────────────────
        when {
            topHolderPct < 0    -> { soft.add("Holder concentration unknown" to 5); penalty += 5 }
            topHolderPct > 60.0 -> hard.add("Top 10 holders own ${topHolderPct.toInt()}% of supply")
            topHolderPct > 40.0 -> { soft.add("Top holders concentrated (${topHolderPct.toInt()}%)" to 15); penalty += 15 }
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
        val nameFlag = checkNameDuplicate(symbol, name)
        when {
            nameFlag.startsWith("HARD:") -> hard.add(nameFlag.removePrefix("HARD: "))
            nameFlag.startsWith("SOFT:") -> {
                val msg = nameFlag.removePrefix("SOFT: ")
                soft.add(msg to 25)
                penalty += 25
            }
        }

        // ── 8. ScalingMode tier safety gates ─────────────────────────
        val solPxSc  = WalletManager.lastKnownSolPrice
        val trsUsdSc = TreasuryManager.treasurySol * solPxSc
        val sTier    = ScalingMode.activeTier(trsUsdSc)
        if (rcScore in 0..99 && rcScore < sTier.minRugcheckScore) {
            if (sTier.minRugcheckScore > 80)
                hard.add("Rugcheck $rcScore < ${sTier.minRugcheckScore} (${sTier.label} min)")
            else { soft.add("Below tier rugcheck min ($rcScore/${sTier.minRugcheckScore})" to 15); penalty += 15 }
        }
        if (sTier.requireLpLock90 && lpLockPct in 0.0..89.9)
            hard.add("LP ${lpLockPct.toInt()}% — ${sTier.label} requires 90%+")
        if (sTier.requireTopHolder30 && topHolderPct > 30.0)
            hard.add("Top10 ${topHolderPct.toInt()}% — ${sTier.label} requires <30%")

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
        )
        cache[mint] = report
        return report
    }

    // ── Rugcheck.xyz API ──────────────────────────────────────────────

    /**
     * Rugcheck.xyz free API — no key required.
     * Returns full token report including risks, LP info, holder data.
     * Endpoint: https://api.rugcheck.xyz/v1/tokens/{mint}/report
     */
    private fun fetchRugcheck(mint: String): JSONObject? {
        val url  = "https://api.rugcheck.xyz/v1/tokens/$mint/report"
        val body = get(url) ?: return null
        return try { JSONObject(body) } catch (_: Exception) { null }
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
     */
    private fun checkNameDuplicate(symbol: String, name: String): String {
        val symLower  = symbol.lowercase().trim()
        val nameLower = name.lowercase().trim()

        // 1. Exact symbol match against blocklist
        if (symLower in KNOWN_TOKEN_BLOCKLIST) {
            return "HARD: Symbol \"$symbol\" matches known major token — likely clone/scam"
        }

        // 2. Clone pattern in name
        for (pattern in CLONE_PATTERNS) {
            if (pattern.containsMatchIn(nameLower)) {
                return "SOFT: Name contains clone pattern: \"${pattern.pattern}\""
            }
        }

        // 3. Fuzzy similarity: is this symbol suspiciously close to a known token?
        for (known in KNOWN_TOKEN_BLOCKLIST) {
            val sim = similarity(symLower, known)
            if (sim > 0.82 && symLower != known) {
                return "SOFT: Symbol \"$symbol\" is ${(sim * 100).toInt()}% similar to \"$known\""
            }
        }

        // 4. Name contains a known token name as substring
        for (known in KNOWN_TOKEN_BLOCKLIST) {
            if (known.length >= 4 && nameLower.contains(known) && symLower != known) {
                return "SOFT: Name \"$name\" references known token \"$known\""
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
        val rb   = okhttp3.body.toRequestBody(
            okhttp3."application/json".toMediaType())
        val req  = Request.Builder().url(url).post(rb)
            .header("Content-Type", "application/json").build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
