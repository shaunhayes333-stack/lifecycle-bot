package com.lifecyclebot.engine.cortex

/**
 * Standalone-harness adapter only: production CrossAssetCortex owns Android and
 * service entry points. No prediction/learning/calibration algorithm is stubbed.
 * Cross-asset service routing is outside the scope of this harness.
 */
object CrossAssetCortex7931 {
    fun isCrossAssetLane(lane: String): Boolean {
        val value = lane.substringBefore('@').uppercase()
        return value == "CRYPTO_ALT" || value.startsWith("MKT_") ||
            value.startsWith("EVM_") || value.startsWith("CRYPTO_")
    }
}
