package com.lifecyclebot.engine.truth

/**
 * V5.0.8160 — pure pacing for capped maps that evict by scanning: scan only after the map has grown by
 * [EVICT_SLACK_8160] past the cap (or past the size at the last scan), then evict down to 90% of the cap in one pass.
 */
object EvictionPacing8160 {
    const val EVICT_SLACK_8160 = 256

    /** True when a scan is worth doing: over [cap] + slack, and grown by slack since the last scan at [lastScanSize]. */
    fun due8160(size: Int, cap: Int, lastScanSize: Int): Boolean =
        size > cap + EVICT_SLACK_8160 && size >= lastScanSize + EVICT_SLACK_8160

    /** How many entries one pass should drop to land at 90% of [cap]. */
    fun dropCount8160(size: Int, cap: Int): Int = (size - cap * 9 / 10).coerceAtLeast(0)
}
