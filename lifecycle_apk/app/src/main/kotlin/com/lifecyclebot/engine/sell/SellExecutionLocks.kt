package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SellExecutionLocks {
    private val locks = ConcurrentHashMap<String, AtomicBoolean>()
    fun tryAcquire(mint: String): Boolean = mint.isNotBlank() && locks.getOrPut(mint) { AtomicBoolean(false) }.compareAndSet(false, true)
    fun release(mint: String) { if (mint.isNotBlank()) locks[mint]?.set(false) }
    fun isLocked(mint: String): Boolean = locks[mint]?.get() == true
}
