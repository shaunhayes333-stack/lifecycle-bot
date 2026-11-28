package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** A cleanup request may remove only the observed record in its own account. */
internal object ModeScopedRemoval9066 {
    fun <T : Any> remove(
        positions: ConcurrentHashMap<String, T>, mint: String, isPaper: Boolean,
        modeOf: (T) -> Boolean,
    ): Boolean {
        val observed = positions[mint] ?: return false
        if (modeOf(observed) != isPaper) return false
        var removed = false
        positions.computeIfPresent(mint) { _, current ->
            if (current === observed) {
                removed = true
                null
            } else current
        }
        return removed
    }
}
