package com.lifecyclebot.engine.truth

/** Closed returns are already net. A high-score losing band cannot erase a profitable lower band. */
internal object EntryFloorEvidence8528 {
    fun profitableNetBand(samples: Int, meanNetPct: Double?): Boolean =
        samples >= 15 && meanNetPct != null && meanNetPct.isFinite() && meanNetPct > 2.0

    fun preserveProfitableBand(composed: Double, learnedFloor: Double?): Double =
        if (learnedFloor != null && learnedFloor.isFinite()) minOf(composed, learnedFloor) else composed
    fun nativeFloor(bootstrap: Int, bands: List<Triple<Int, Int, Double?>>): Int {
        val positive = bands.filter { profitableNetBand(it.second, it.third) }.minOfOrNull { it.first }
        if (positive != null) return positive.coerceIn(0, 95)
        var floor = bootstrap.coerceIn(0, 95)
        // A loss at 80 is not evidence against an untested 20. Stop at every unsupported gap.
        val maximum = (bootstrap + 20).coerceIn(0, 95)
        while (floor < maximum) {
            val start = (floor / 10) * 10
            val b = bands.firstOrNull { it.first == start } ?: break
            if (b.second < 15 || b.third?.let { it.isFinite() && it < -2.0 } != true) break
            floor = (start + 10).coerceAtMost(maximum)
        }
        return floor
    }
}
