package com.lifecyclebot.engine

/**
 * A native desk's identity and required evidence survive learning and runner
 * rescue. This classifies an actual refusal; it does not make optional features
 * mandatory, nor transfer one desk's bounds to another desk's independent setup.
 */
object NativeLaneConstraints8521 {
    enum class Kind { CAP, AGE, DATA, LIFECYCLE, SAFETY, LIQUIDITY, LANE_SCOPE }

    private fun normalized(reason: String): String = reason.trim().uppercase().replace(Regex("[\\s-]+"), "_")

    // These are adverse findings, not weak opportunity scores. Match named
    // families rather than every mention of holders/ownership: a sparse holder
    // count or low lane-ownership conviction is still an ordinary preference.
    private val safetyFamilies8522 = Regex("(?:^|[^A-Z0-9])(?:" + listOf(
        "LP_UNLOCKED", "LP_UNLOCKED_CONFIRMED", "UNLOCKED_LP", "LP_NOT_LOCKED",
        "LP_LOCK_BELOW_REQUIRED", "LP_LOCK_TOO_LOW", "LP_LOCK_INSUFFICIENT", "LP_OWNERSHIP_UNSAFE",
        "TOP_HOLDER_CONCENTRATION", "TOP_HOLDER_CONCENTRATION_HIGH", "TOP_HOLDER_TOO_DOMINANT",
        "TOP_HOLDER_DOMINANT", "TOP10_HOLDER_CONCENTRATION", "TOP_10_HOLDER_CONCENTRATION",
        "TOP_10_HOLDERS_HIGH_OWNERSHIP", "SINGLE_HOLDER", "SINGLE_HOLDER_OWNERSHIP", "HIGH_OWNERSHIP",
        "FATAL_WALLET_RISK_TEXT", "BUY_FINAL_AUTHORITY_ACTIVE", "AUTHORITY_RETAINED",
        "STRUCTURAL_RISK", "FIVE_MINUTE_CASCADE", "SELL_DOMINANT_TAPE", "ONE_WALLET_PUMP",
        "CHASING_3X_FROM_CREATE", "PEAK_EXHAUSTION", "EXHAUSTION_OR_SPIKE_TOP", "DUMPING", "FALLING_KNIFE",
    ).joinToString("|") { Regex.escape(it) } + ")(?=$|[^A-Z0-9_]|_[0-9])")
    // CashGenerationAI's combined threshold refusal includes this exact risk
    // comparison; do not confuse it with a low holder-count/quality score.
    private val holderLimit8522 = Regex("(?:^|[^A-Z0-9])HOLDER=[0-9]+(?:\\.[0-9]+)?%>[0-9]+(?:\\.[0-9]+)?%")
    // DipHunter's actual physical-danger verdict. Learned DANGER_BUCKET names
    // remain opportunity evidence; only the affirmative native `DANGER:` binds.
    private val dipDanger8522 = Regex("(?:^|[^A-Z0-9])DANGER:")
    private val safetyProofSubjects8522 = listOf("LP_LOCK", "LP_OWNERSHIP", "HOLDER_DATA", "HOLDER_DISTRIBUTION",
        "MINT_AUTHORITY", "FREEZE_AUTHORITY", "BUY_FINAL_AUTHORITY", "SAFETY", "LIVE_CRITICAL_PROOF",
        "BUY_ROUTE", "BUY_EXECUTION_QUOTE", "TOKEN_MAP", "LIQUIDITY_PROOF")
    private val missingProof8522 = listOf("PENDING", "UNKNOWN", "UNVERIFIED", "UNRESOLVED", "UNOBSERVED",
        "MISSING", "STALE", "NOT_FRESH", "REFRESH", "INCOMPLETE", "TIMEOUT", "INCONCLUSIVE", "CHANGED", "MISMATCH", "DEFER")

    internal fun kind8521(reason: String?): Kind? {
        val r = normalized(reason.orEmpty())
        if (r.isBlank()) return null
        if (safetyFamilies8522.containsMatchIn(r) || holderLimit8522.containsMatchIn(r) || dipDanger8522.containsMatchIn(r) ||
            (r.contains("LP_ONLY_") && r.contains("%_LOCKED") && r.contains("NEEDS_"))) return Kind.SAFETY
        if (safetyProofSubjects8522.any { r.contains(it) } && missingProof8522.any { r.contains(it) }) return Kind.DATA
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

    /** Cashflow native READY uses the same established-token mandate as hunters, election and playbooks. */
    internal fun requireCashflowRole8522(opinion: SpecialistBrainBridge7542.Opinion, capUsd: Double,
                                        liquidityUsd: Double): SpecialistBrainBridge7542.Opinion {
        if (!opinion.authoritative || !opinion.eligible || opinion.lane !in setOf("TREASURY", "CASHGEN")) return opinion
        val reason = when {
            !capUsd.isFinite() || capUsd <= 0.0 -> "NATIVE_MCAP_UNHYDRATED_DEFER_8522"
            !liquidityUsd.isFinite() || liquidityUsd <= 0.0 -> "NATIVE_LIQ_UNHYDRATED_DEFER_8522"
            capUsd < com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mcapMin8323(opinion.lane) -> "NATIVE_CASHFLOW_ROLE_MCAP_FLOOR_8522"
            liquidityUsd < com.lifecyclebot.engine.cortex.LaneCheatSheet8323.liqMin8389(opinion.lane) -> "NATIVE_CASHFLOW_ROLE_LIQ_FLOOR_8522"
            else -> return opinion
        }
        return opinion.copy(eligible = false, reason = "$reason:${opinion.lane}", gradeable = !missingData8521(reason))
    }

    /** An unknown cap cannot prove a specialist's band. The discovery resolver
     * already requests the authoritative supply/reserve read before this call.
     * CORE remains an independent generalist assessment, not a token-wide veto.
     */
    internal fun requireCap8521(opinion: SpecialistBrainBridge7542.Opinion, observedCapUsd: Double): SpecialistBrainBridge7542.Opinion {
        if (!opinion.authoritative || opinion.lane == "CORE" || (observedCapUsd.isFinite() && observedCapUsd > 0.0)) return opinion
        val kind = kind8521(opinion.reason)
        if (!opinion.eligible && kind != null && kind != Kind.CAP &&
            !(kind == Kind.LANE_SCOPE && opinion.reason.uppercase().contains("ROLE_FLOOR"))) return opinion
        val sheet = com.lifecyclebot.engine.cortex.LaneCheatSheet8323
        val min = sheet.mcapMin8323(opinion.lane)
        val max = sheet.mcapMax8323(opinion.lane)
        if (!(min > 0.0 || (max.isFinite() && max < Double.MAX_VALUE))) return opinion
        return opinion.copy(eligible = false, gradeable = false, reason = "${LaneInputTruth8236.MCAP_DEFER}:${opinion.lane}")
    }
}
