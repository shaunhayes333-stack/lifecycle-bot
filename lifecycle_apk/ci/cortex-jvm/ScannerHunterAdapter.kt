package com.lifecyclebot.engine.market

/** Source affinity service adapter; scanner/network/hunter execution is not exercised by the JVM model harness. */
object LaneHunter7297 {
    fun laneFromSource(source: String?): String? = source?.substringAfter("MARKET_HUNT_", "")?.takeIf { it.isNotBlank() }
}
