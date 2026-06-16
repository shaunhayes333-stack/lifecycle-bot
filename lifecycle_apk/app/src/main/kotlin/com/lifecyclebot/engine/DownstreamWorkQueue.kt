package com.lifecyclebot.engine

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * V5.0.3803 — coroutine split for downstream work.
 *
 * This queue is intentionally NOT used for pre-broadcast money authority:
 * processor amount planning, sell leases, route quote/build, and live wallet proof
 * stay synchronous at their existing source sites. This only moves slow downstream
 * callbacks — reconciler requeue/finality, proof-ready retry enqueue, and telemetry
 * fanout — off the caller's hot callback path.
 */
object DownstreamWorkQueue {
    private val handler = CoroutineExceptionHandler { _, t ->
        try { ErrorLogger.warn("DownstreamWorkQueue", "async downstream failure: ${t.message?.take(120)}") } catch (_: Throwable) {}
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    fun verification(label: String, mint: String, block: () -> Unit) = enqueue("VERIFY", label, mint, block)
    fun reconciliation(label: String, mint: String, block: () -> Unit) = enqueue("RECONCILE", label, mint, block)
    fun retry(label: String, mint: String, block: () -> Unit) = enqueue("RETRY", label, mint, block)
    fun telemetry(label: String, mint: String = "", block: () -> Unit) = enqueue("TELEMETRY", label, mint, block)

    private fun enqueue(kind: String, label: String, mint: String, block: () -> Unit) {
        try { PipelineHealthCollector.labelInc("DOWNSTREAM_${kind}_QUEUED") } catch (_: Throwable) {}
        scope.launch {
            val start = System.currentTimeMillis()
            try {
                try { ForensicLogger.lifecycle("DOWNSTREAM_${kind}_START", "label=$label mint=${mint.take(10)}") } catch (_: Throwable) {}
                block()
                try { PipelineHealthCollector.labelInc("DOWNSTREAM_${kind}_DONE") } catch (_: Throwable) {}
            } catch (t: Throwable) {
                try { PipelineHealthCollector.labelInc("DOWNSTREAM_${kind}_FAILED") } catch (_: Throwable) {}
                try { ErrorLogger.warn("DownstreamWorkQueue", "$kind/$label failed mint=${mint.take(10)} err=${t.message?.take(120)}") } catch (_: Throwable) {}
            } finally {
                val ms = System.currentTimeMillis() - start
                try { ForensicLogger.lifecycle("DOWNSTREAM_${kind}_DONE", "label=$label mint=${mint.take(10)} durMs=$ms") } catch (_: Throwable) {}
            }
        }
    }
}
