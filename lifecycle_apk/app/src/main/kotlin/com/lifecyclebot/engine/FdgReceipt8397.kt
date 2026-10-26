package com.lifecyclebot.engine

/**
 * One receipt meaning at the FDG adapter boundary. A false execution decision can mean that
 * evidence is still pending; it must not consume a receiver task or its rescue slot.
 * Unknown policy/quality reasons remain refusals. This never authorizes an entry.
 */
object FdgReceipt8397 {
    enum class Outcome { ADMIT, REFUSE, DEFER_DATA }

    private val bindingReasons = listOf("SAFETY_REFUSED", "STRUCTURAL_RISK", "CONFIRMED_RUG", "RUG_PULL", "DUPLICATE_OPEN",
        "MINT_AUTHORITY_RETAINED", "FREEZE_AUTHORITY_RETAINED")
    private val dataReasons = listOf("DATA_INCOMPLETE", "DATA_DEFER_", "ADMISSION_COST_REQUOTE_UNAVAILABLE",
        "TOO_FEW_BARS", "PRICE_STALE", "PRICE_UNKNOWN", "PRICE_NOT_FRESH", "UNKNOWN_ECONOMICS",
        "CURVE_RESERVES_STALE", "CURVE_DEPTH_UNVERIFIED")
    private val economicsInputs = setOf("liq", "price", "quote", "route", "wallet")

    fun outcome(allow: Boolean, reason: String?): Outcome {
        if (allow) return Outcome.ADMIT
        val r = reason.orEmpty().uppercase()
        // An explicitly binding judgement cannot be weakened by incidental data words in its detail.
        if (bindingReasons.any { r.contains(it) }) return Outcome.REFUSE
        return if (dataReasons.any { r.contains(it) }) Outcome.DEFER_DATA else Outcome.REFUSE
    }

    fun featureKey(feature: String): String = when (val f = feature.substringBefore(':').lowercase()) {
        "liquidity" -> "liq"
        "candles", "bars", "history" -> "shape"
        "swaps" -> "tape"
        "buy_pressure", "buyers" -> "bp"
        else -> f
    }

    private fun answersKey(missingKey: String, arrived: Set<String>): Boolean = missingKey == "core" ||
        arrived.any {
            val key = featureKey(it)
            // A trade print can refresh buy pressure. An aggregate pair ratio does not supply a missing print.
            key == missingKey || (missingKey == "bp" && key == "tape") ||
                (missingKey == "economics" && key in economicsInputs)
        }

    fun answersFeature(missing: String, arrived: Set<String>): Boolean =
        arrived.isNotEmpty() && answersKey(featureKey(missing), arrived)

    /** Arrival permits another evaluation, never assumes every missing fact has become true. */
    fun answers(missing: Set<String>, arrived: Set<String>): Boolean =
        arrived.isNotEmpty() && (missing.isEmpty() || missing.any { answersKey(featureKey(it), arrived) })
}
