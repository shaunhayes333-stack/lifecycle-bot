package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** A bounded executor must reject explicitly (AbortPolicy), never discard a keyed task silently. */
internal class KeyedTaskAdmission8239(private val executor: Executor) {
    enum class Result { ACCEPTED, COALESCED, REJECTED }
    private val keys = ConcurrentHashMap.newKeySet<String>()

    fun submit(key: String, task: () -> Unit): Result {
        if (!keys.add(key)) return Result.COALESCED
        return try {
            executor.execute { try { task() } finally { keys.remove(key) } }
            Result.ACCEPTED
        } catch (_: RejectedExecutionException) {
            keys.remove(key)
            Result.REJECTED
        } catch (t: Throwable) {
            keys.remove(key)
            throw t
        }
    }

    internal fun pending(key: String): Boolean = keys.contains(key)
    internal fun size(): Int = keys.size
}
