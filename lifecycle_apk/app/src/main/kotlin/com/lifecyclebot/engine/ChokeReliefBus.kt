package com.lifecyclebot.engine

import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.4320 — bounded coroutine helper for non-critical hot-path side effects.
 *
 * Use this for report-only fanout from scanner/FDG/executor/specialist lanes:
 * counters may remain inline, but ForensicLogger / PipelineHealthCollector / UI
 * strings / audits that do not decide a trade should be pushed through this bus.
 * If saturated, it drops and labels the drop instead of choking the trading loop.
 */
object ChokeReliefBus {
    private const val MAX_IN_FLIGHT = 256
    private val inFlight = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val scope = CoroutineScope(AppDispatchers.sideEffect + SupervisorJob())

    fun launch(tag: String, mint: String = "", block: () -> Unit): Boolean {
        val cur = inFlight.incrementAndGet()
        if (cur > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            val d = dropped.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CHOKE_RELIEF_DROP_4320/${tag.take(40)}") } catch (_: Throwable) {}
            if (d % 100 == 1) {
                try { ForensicLogger.lifecycle("CHOKE_RELIEF_DROP_4320", "tag=${tag.take(80)} mint=${mint.take(10)} dropped=$d inFlight=${inFlight.get()} report_only=true") } catch (_: Throwable) {}
            }
            return false
        }
        scope.launch {
            try { block() }
            catch (t: Throwable) {
                try { ErrorLogger.debug("ChokeReliefBus", "4320 side-effect failed tag=$tag err=${t.message}") } catch (_: Throwable) {}
            } finally {
                inFlight.decrementAndGet()
            }
        }
        return true
    }

    fun status(): String = "CHOKE_RELIEF_BUS_4320 inFlight=${inFlight.get()} dropped=${dropped.get()} max=$MAX_IN_FLIGHT sideEffectDispatcher=true report_only=true"
}
