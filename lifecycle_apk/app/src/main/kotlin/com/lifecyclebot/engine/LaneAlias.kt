package com.lifecyclebot.engine

/**
 * V5.0.6312 — Canonical lane alias normalizer.
 *
 * Operator hotfix brief §9 requires that lane aliases collapse to a single
 * canonical form so the SAME wallet acquisition cannot become separate
 * STANDARD/QUALITY/MOONSHOT/BLUECHIP/BLUE_CHIP positions.
 *
 *   BLUE_CHIP → BLUECHIP
 *   SHIT_COIN → SHITCOIN
 *   MOON_SHOT → MOONSHOT
 *
 * Every downstream ledger, cap map, execution lock, and journal write
 * that touches "lane" should route through [normalize] first. This does
 * NOT eliminate lane-specific behaviour — it merely guarantees ownership
 * identity is stable across aliases.
 */
object LaneAlias {
    private val CANONICAL_MAP: Map<String, String> = mapOf(
        "BLUE_CHIP" to "BLUECHIP",
        "BLUECHIP" to "BLUECHIP",
        "SHIT_COIN" to "SHITCOIN",
        "SHITCOIN" to "SHITCOIN",
        "MOON_SHOT" to "MOONSHOT",
        "MOONSHOT" to "MOONSHOT",
    )

    fun normalize(lane: String?): String {
        if (lane.isNullOrBlank()) return ""
        val up = lane.trim().uppercase()
        val canonical = CANONICAL_MAP[up] ?: return up
        if (canonical != up) {
            try {
                PipelineHealthCollector.labelInc("POSITION_ALIAS_COLLISION_MERGED_6312")
            } catch (_: Throwable) {}
        }
        return canonical
    }

    /** True if two lane strings collapse to the same canonical identity. */
    fun sameCanonical(a: String?, b: String?): Boolean =
        normalize(a) == normalize(b) && normalize(a).isNotEmpty()
}
