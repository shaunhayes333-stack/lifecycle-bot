package com.lifecyclebot.engine

/** Orders the existing PAPER profit rules before its existing three-minute stop warmup. */
internal object PaperExitOrder8527 {
    enum class Decision { PROFIT_FLOOR, TRAIL, WARMUP, CONTINUE }

    fun choose8527(heldMinutes: Double, profitFloorBreached: Boolean, trailBreached: Boolean): Decision =
        when {
            profitFloorBreached -> Decision.PROFIT_FLOOR
            trailBreached -> Decision.TRAIL
            heldMinutes < 3.0 -> Decision.WARMUP
            else -> Decision.CONTINUE
        }
}
