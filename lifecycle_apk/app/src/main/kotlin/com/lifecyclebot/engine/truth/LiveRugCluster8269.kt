package com.lifecyclebot.engine.truth

/**
 * 5.0.8269: RugCheck's risk ARRAY is independent from its numerical score.
 * A routable pool does not make a concentration + single-holder + thin-pool
 * combination safe. Match named, high-severity provider findings; do not
 * misinterpret unverified branding alone, an ordinary fresh launch, or raw
 * LP/bonding-curve custody as proven wallet ownership.
 *
 * Pure classification, without network, wallet access, or arbitrary scores.
 * The caller must establish that the flags are from this mint's RugCheck
 * response; no symbol-level or LLM report can set a terminal verdict.
 */
object LiveRugCluster8269 {
    data class Flag(val name: String, val level: String, val description: String = "")

    const val CLUSTER_REFUSAL = "CONFIRMED_WALLET_RUG_CLUSTER_8269"
    const val FIVE_DANGER_REFUSAL = "FIVE_CONFIRMED_DANGER_RISKS_8269"

    private fun normalized(s: String): String = s.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun severe(f: Flag): Boolean =
        normalized(f.level) in setOf("danger", "high", "critical", "severe")

    /** Null means this independently reviewed risk list did not prove a terminal risk combo. */
    fun refusal(flags: List<Flag>): String? {
        val confirmed = flags.filter(::severe)
        if (confirmed.isEmpty()) return null
        val names = confirmed.map { normalized(it.name + " " + it.description) }
        val top10 = names.any {
            Regex("\\btop\\s*(10|ten)\\b").containsMatchIn(it) &&
                (it.contains("holder") || it.contains("user") || it.contains("supply"))
        }
        val singleHolder = names.any {
            (it.contains("single holder") || it.contains("single user") || it.contains("one user holds") ||
                it.contains("one holder")) && (it.contains("ownership") || it.contains("holder") || it.contains("supply"))
        }
        val lowLiquidity = names.any {
            (it.contains("low") || it.contains("thin") || it.contains("insufficient")) &&
                (it.contains("liquidity") || it.contains("liquid pool") || it.contains("pool depth"))
        }
        val highOwnership = names.any {
            it.contains("high ownership") || it.contains("holder concentration") ||
                it.contains("high concentration") || it.contains("holders high") ||
                it.contains("users hold more than 80%")
        }
        // Three corroborating red findings (not merely the provider's risk score).
        if (lowLiquidity && ((top10 && singleHolder) || (singleHolder && highOwnership) ||
                    (top10 && highOwnership))) return CLUSTER_REFUSAL
        // Five distinct danger findings are independently unacceptable even
        // if positive but shallow liquidity makes a swap technically routable.
        if (confirmed.size >= 5) return FIVE_DANGER_REFUSAL
        return null
    }
}
