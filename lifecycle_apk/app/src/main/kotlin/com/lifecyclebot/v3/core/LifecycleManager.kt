package com.lifecyclebot.v3.core

/**
 * V3 Lifecycle Manager
 * Tracks state per token
 */
class LifecycleManager {
    private val states = mutableMapOf<String, LifecycleState>()
    
    fun mark(mint: String, state: LifecycleState) {
        states[mint] = state
    }
    
    fun get(mint: String): LifecycleState? = states[mint]
    
    fun clear(mint: String) {
        states.remove(mint)
    }
    
    fun clearAll() {
        states.clear()
    }
}
