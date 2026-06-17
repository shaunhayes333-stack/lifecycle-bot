package com.lifecyclebot.engine

/**
 * V5.0.3857 — LaneToxicityGuard.
 *
 * Source fix for WR collapse where a lane×score bucket is already a proven
 * net-negative danger bucket (example from operator report: MOONSHOT|S41-60
 * losses=38 wins=11 meanPnl=-35.82%) yet style/router fanout keeps electing
 * that lane as primary/rescue. FDG later shrinks size, but WR keeps bleeding.
 *
 * This guard never blocks a trade and never disables a lane globally. It only
 * lets routers avoid routing NEW exposure into a toxic lane when there is an
 * alternate lane available. If no alternate exists, downstream FDG train-first
 * micro/size shaping still owns execution.
 */
object LaneToxicityGuard {
    fun isNetNegativeDanger(lane: String, score: Int): Boolean = try {
        val s = LosingPatternMemory.stats(lane, score)
        s.isDangerous && s.meanPnl <= -8.0
    } catch (_: Throwable) { false }

    fun chooseNonToxicLane(mint: String, lanes: List<String>, score: Int): String? {
        val clean = lanes.filter { it.isNotBlank() && !isNetNegativeDanger(it, score) }.distinct()
        if (clean.isNotEmpty()) return clean[((mint.hashCode() and 0x7fffffff) % clean.size)]
        return lanes.firstOrNull { it.isNotBlank() }
    }

    fun filterNonToxic(lanes: Collection<String>, score: Int): List<String> =
        lanes.filter { it.isNotBlank() && !isNetNegativeDanger(it, score) }.distinct()
}
