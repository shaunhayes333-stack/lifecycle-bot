package com.lifecyclebot.engine.truth

/** Small, rotating evidence-only budget for otherwise skipped discovery cohorts.
 * It does not alter admission, safety, sizing, or execution.
 */
internal class DiscoveryProbe9048(private val perMinute: Int = 2, private val divisor: Int = 32) {
    private var minute = Long.MIN_VALUE
    private val admitted = HashSet<String>()
    @Synchronized fun permit(mint: String, nowMs: Long): Boolean {
        if (mint.isBlank() || nowMs <= 0L || perMinute <= 0 || divisor <= 0) return false
        val bucket = nowMs / 60_000L
        if (bucket < minute) return false
        if (bucket != minute) { minute = bucket; admitted.clear() }
        if (admitted.size >= perMinute || mint in admitted) return false
        var h = mint.hashCode() xor (nowMs / 900_000L).toInt()
        h = h xor (h ushr 16)
        h *= -1640531527
        h = h xor (h ushr 16)
        if (Math.floorMod(h, divisor) != 0) return false
        admitted.add(mint)
        return true
    }
}
