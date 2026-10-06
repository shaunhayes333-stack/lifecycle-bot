package com.lifecyclebot.engine

/** Synchronous canonical deliveries use the trade's mode, never the current UI mode. */
internal object LearningEnvironment7835 {
    private data class Scope(val mode: String, val canonical: Boolean)
    private val scope = ThreadLocal<Scope?>()
    fun mode(): String = scope.get()?.mode ?: if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
    fun isCanonical(): Boolean = scope.get()?.canonical == true
    fun <T> withMode(mode: String, canonical: Boolean = false, block: () -> T): T {
        require(mode.uppercase() in setOf("PAPER", "LIVE", "SHADOW", "UNKNOWN"))
        val previous = scope.get()
        scope.set(Scope(mode.uppercase(), canonical))
        return try { block() } finally { if (previous == null) scope.remove() else scope.set(previous) }
    }
}
