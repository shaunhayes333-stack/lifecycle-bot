package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * NetworkRetry — Exponential Backoff + Circuit Breaker  V5.9
 *
 * Usage:
 *   val retrier = NetworkRetry("JupiterPerps", maxRetries = 3)
 *   val result = retrier.call { fetchSomething() }
 *
 * Circuit breaker opens after [failureThreshold] consecutive failures.
 * It stays open for [openDurationMs] then moves to HALF_OPEN for one probe.
 * If probe succeeds -> CLOSED; if probe fails -> OPEN again.
 */
class NetworkRetry(
    private val tag: String,
    private val maxRetries: Int       = 3,
    private val baseDelayMs: Long     = 500L,
    private val maxDelayMs: Long      = 16_000L,
    private val failureThreshold: Int = 5,
    private val openDurationMs: Long  = 60_000L,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt            = AtomicLong(0L)
    @Volatile private var state     = State.CLOSED

    val isOpen get() = state == State.OPEN

    suspend fun <T> call(block: suspend () -> T): T? {
        when (state) {
            State.OPEN -> {
                val elapsed = System.currentTimeMillis() - openedAt.get()
                if (elapsed < openDurationMs) {
                    ErrorLogger.debug(tag, "Circuit OPEN — skipping (${(openDurationMs - elapsed) / 1000}s remaining)")
                    return null
                }
                state = State.HALF_OPEN
            }
            else -> {}
        }

        var lastEx: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val result = block()
                onSuccess()
                return result
            } catch (e: Exception) {
                lastEx = e
                val d = (baseDelayMs * (1L shl attempt.coerceAtMost(5))).coerceAtMost(maxDelayMs)
                ErrorLogger.warn(tag, "Attempt ${attempt + 1}/$maxRetries failed: ${e.message} — retry in ${d}ms")
                if (attempt < maxRetries - 1) delay(d)
            }
        }
        onFailure()
        ErrorLogger.warn(tag, "All $maxRetries attempts exhausted: ${lastEx?.message}")
        return null
    }

    suspend fun run(block: suspend () -> Unit) { call<Unit> { block() } }

    fun recordSuccess() = onSuccess()
    fun recordFailure() = onFailure()

    private fun onSuccess() {
        val was = state
        consecutiveFailures.set(0)
        state = State.CLOSED
        if (was != State.CLOSED) ErrorLogger.info(tag, "Circuit CLOSED — connection restored")
    }

    private fun onFailure() {
        val f = consecutiveFailures.incrementAndGet()
        if (f >= failureThreshold && state == State.CLOSED) {
            state = State.OPEN
            openedAt.set(System.currentTimeMillis())
            ErrorLogger.warn(tag, "Circuit OPEN after $f failures — pausing ${openDurationMs / 1000}s")
        } else if (state == State.HALF_OPEN) {
            state = State.OPEN
            openedAt.set(System.currentTimeMillis())
            ErrorLogger.warn(tag, "Circuit OPEN again — probe failed")
        }
    }
}
