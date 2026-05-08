package com.lifecyclebot.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.607 — app-level live transaction scope.
 *
 * Live quote/build/sign/send/confirm work must not be a child of a scanner
 * cycle, UI lifecycle, watchlist refresh, or short-lived service coroutine.
 * This scope is deliberately owned by the process-level execution layer and
 * uses SupervisorJob + Dispatchers.IO. Callers still await the result, but
 * caller cancellation does not tear down an in-flight transaction build.
 */
object LiveExecutionScope {
    private const val TAG = "LiveExecutionScope"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val seq = AtomicLong(0L)

    data class JobTag(val id: String)

    suspend fun <T> runAwaited(label: String, block: suspend (JobTag) -> T): T {
        val tag = JobTag("LIVE-${seq.incrementAndGet()}-${System.currentTimeMillis()}")
        val deferred = scope.async {
            ErrorLogger.info(TAG, "▶️ ${tag.id} START $label")
            try {
                block(tag).also { ErrorLogger.info(TAG, "✅ ${tag.id} DONE $label") }
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "❌ ${tag.id} FAILED $label | ${t.javaClass.simpleName}: ${t.message}")
                throw t
            }
        }
        // Await in NonCancellable so parent scan/UI cancellation cannot abort
        // the tx chain or cause the caller to record a premature failure.
        return withContext(NonCancellable) { deferred.await() }
    }
}
