package com.lifecyclebot.network

/** Keep a candidate subscribed long enough to observe a real multi-bar path. Repeated requests do not renew a lease. */
internal object CandidateTapeLease8239 {
    private const val MIN_RESIDENCE_MS = 45_000L
    const val ROTATE_AFTER_MS = 180_000L
    const val DEMAND_TTL_MS = 120_000L

    fun mayReplace(residentHeld: Boolean, incomingHeld: Boolean, subscribedAtMs: Long,
                   residentPriority: Int, incomingPriority: Int, nowMs: Long): Boolean {
        if (residentHeld) return false
        if (incomingHeld) return true
        val age = nowMs - subscribedAtMs
        if (subscribedAtMs > 0L && age >= 5_000L && incomingPriority >= 2 && incomingPriority > residentPriority) return true
        return subscribedAtMs > 0L && age >= MIN_RESIDENCE_MS &&
            (incomingPriority >= residentPriority || age >= ROTATE_AFTER_MS)
    }
}
