package com.lifecyclebot.engine

/**
 * A native desk's identity and required evidence survive learning and runner
 * rescue. This classifies an actual refusal; it does not make optional features
 * mandatory, nor transfer one desk's bounds to another desk's independent setup.
 */
object NativeLaneConstraints8521 {
    enum class Kind { CAP, AGE, DATA, LIFECYCLE, SAFETY, LIQUIDITY, LANE_SCOPE }

    private fun normalized(reason: String): String = reason.trim().uppercase().replace(Regex("[\\s-]+"), "_")

    internal fun kind8521(reason: String?): Kind? {
        val r = normalized(reason.orEmpty())
        if (r.isBlank()) return null
        if (listOf("UNHYDRATED", "UNOBSERVED", "HYDRATING", "DATA_INCOMPLETE", "DATA_PENDING", "_DEFER",
                "METADATA_PENDING", "UNRESOLVED", "UNKNOWN", "MISSING_REQUIRED", "UNSCORED",
                "PRICE_STALE", "PRICE_NOT_FRESH", "NO_PRICE", "HISTORY_SPARSE", "TOO_FEW_BARS").any { r.contains(it) }) return Kind.DATA
        if (listOf("RUG", "SAFETY", "HONEYPOT", "BLOCK", "DEV", "BUNDLE", "SCAM", "FREEZE", "FROZEN",
                "MINT_AUTH", "BLACKLIST", "ERROR", "TOXIC", "QUARANTIN", "IDENTITY", "SELLAB", "CANNOT_SELL",
                "TOKEN_2022", "TOKEN2022", "MAYHEM").any { r.contains(it) }) return Kind.SAFETY
        if (r.contains("LIQ")) return Kind.LIQUIDITY
        if (r.contains("MCAP") || r.contains("MARKET_CAP")) return Kind.CAP
        if (listOf("TOO_OLD", "TOO_NEW", "TOO_YOUNG", "TOO_FRESH", "AGE_UNKNOWN", "AGE_LIMIT", "AGE_BELOW",
                "AGE_ABOVE", "AGE_WINDOW", "AGE_FLOOR", "AGE_CEILING", "BIRTH_METADATA").any { r.contains(it) }) return Kind.AGE
        if (listOf("ALREADY_", "POSITION_ACTIVE", "POSITION_OPEN", "MAX_POS", "MAX_OPEN", "MAX_CONCURRENT",
                "DAILY_LIMIT", "RECENT_", "COOLDOWN", "DISABLED", "PAUSED").any { r.contains(it) }) return Kind.LIFECYCLE
        if (listOf("PHASE_HANDOFF", "GRADUATED", "SNIPER_WINDOW", "ROLE_FLOOR", "WRONG_LANE",
                "USE_BLUECHIP", "USE_SHITCOIN", "NO_KNOWN_MANIPULATION_EVIDENCE").any { r.contains(it) }) return Kind.LANE_SCOPE
        return null
    }

    fun mayAdapt8521(reason: String?): Boolean = !reason.isNullOrBlank() && kind8521(reason) == null

    fun missingData8521(reason: String): Boolean = kind8521(reason) == Kind.DATA
}
