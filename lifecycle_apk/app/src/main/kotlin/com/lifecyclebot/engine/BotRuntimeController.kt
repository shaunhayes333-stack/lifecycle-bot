package com.lifecyclebot.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1098 — single runtime source of truth.
 *
 * This object owns the published runtime generation/state and job inventory.
 * BotService still contains the legacy implementation details, but UI,
 * forensics, smoke tests, and lifecycle gates read this controller rather
 * than stale local booleans or BotStatus.running alone.
 */
object BotRuntimeController {
    enum class RuntimeState { STOPPED, STARTING, RUNNING, STOPPING }

    data class Snapshot(
        val runtimeGeneration: Long = 0L,
        val state: RuntimeState = RuntimeState.STOPPED,
        val botLoopJobActive: Boolean = false,
        val scannerActive: Boolean = false,
        val hotExitJobActive: Boolean = false,
        val sellReconcilerStarted: Boolean = false,
        val hostTrackerOpenCount: Int = 0,
        val paperMode: Boolean = true,
        val enabledTraders: String = "",
        val stopSource: String = "",
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val runtimeActive: Boolean
            get() = state == RuntimeState.STARTING || state == RuntimeState.RUNNING || botLoopJobActive
    }

    private val generationSeq = AtomicLong(0L)
    private val jobs = ConcurrentHashMap<String, Pair<Long, Job?>>()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun snapshot(): Snapshot = refreshDerived(_state.value)

    fun currentGeneration(): Long = _state.value.runtimeGeneration

    /**
     * V5.9.1441 — P0 STOP-LIFECYCLE LEAK GUARD. A background loop/emitter is only
     * permitted to run/emit while BOTH (a) its captured generation matches the
     * live runtime generation AND (b) the runtime is actually RUNNING. The instant
     * Stop is pressed (state leaves RUNNING) or a new Start bumps the generation,
     * any stale loop that captured an old generation self-terminates on its next
     * tick — even if its own stop() was never called or raced. This is the
     * structural backstop for the confirmed leak where PerpsScanner / InsiderTracker
     * kept emitting after running=false / button=Start Bot.
     */
    fun isLiveGeneration(generation: Long): Boolean {
        val cur = _state.value
        return generation != 0L &&
            generation == cur.runtimeGeneration &&
            cur.state == RuntimeState.RUNNING
    }

    fun beginStart(paperMode: Boolean, enabledTraders: String): Long = synchronized(this) {
        val cur = refreshDerived(_state.value)
        if (cur.runtimeActive) {
            publish(cur.copy(
                state = RuntimeState.RUNNING,
                paperMode = paperMode,
                enabledTraders = enabledTraders,
            ))
            return cur.runtimeGeneration
        }
        val gen = generationSeq.incrementAndGet()
        jobs.clear()
        publish(Snapshot(
            runtimeGeneration = gen,
            state = RuntimeState.STARTING,
            paperMode = paperMode,
            enabledTraders = enabledTraders,
        ))
        return gen
    }

    fun publishRunning(generation: Long = currentGeneration(), paperMode: Boolean? = null, enabledTraders: String? = null) {
        updateForGeneration(generation) { old ->
            old.copy(
                state = RuntimeState.RUNNING,
                paperMode = paperMode ?: old.paperMode,
                enabledTraders = enabledTraders ?: old.enabledTraders,
            )
        }
    }

    fun beginStopping(source: String): Long = synchronized(this) {
        val cur = refreshDerived(_state.value)
        publish(cur.copy(state = RuntimeState.STOPPING, stopSource = source))
        return cur.runtimeGeneration
    }

    fun publishStopped(generation: Long = currentGeneration(), source: String = "") {
        synchronized(this) {
            if (generation != 0L && generation != _state.value.runtimeGeneration) return
            jobs.clear()
            publish(_state.value.copy(
                state = RuntimeState.STOPPED,
                botLoopJobActive = false,
                scannerActive = false,
                hotExitJobActive = false,
                sellReconcilerStarted = false,
                stopSource = source.ifBlank { _state.value.stopSource },
            ))
        }
    }

    fun registerJob(generation: Long, name: String, job: Job?) {
        if (generation == 0L || generation != _state.value.runtimeGeneration) return
        jobs[name] = generation to job
        job?.invokeOnCompletion {
            if (generation == _state.value.runtimeGeneration) refresh()
        }
        refresh()
    }

    fun markScannerActive(generation: Long, active: Boolean) {
        updateForGeneration(generation) { it.copy(scannerActive = active) }
    }

    fun markSellReconcilerStarted(generation: Long, started: Boolean) {
        updateForGeneration(generation) { it.copy(sellReconcilerStarted = started) }
    }

    fun refresh() {
        publish(refreshDerived(_state.value))
    }

    fun resetForTests() = synchronized(this) {
        jobs.clear()
        generationSeq.set(0L)
        publish(Snapshot())
    }

    fun runtimeJobActiveButUiStopped(uiRunning: Boolean): Boolean {
        val s = snapshot()
        return s.botLoopJobActive && !uiRunning
    }

    private fun updateForGeneration(generation: Long, block: (Snapshot) -> Snapshot) {
        synchronized(this) {
            if (generation == 0L || generation != _state.value.runtimeGeneration) return
            publish(block(refreshDerived(_state.value)))
        }
    }

    private fun refreshDerived(base: Snapshot): Snapshot {
        val gen = base.runtimeGeneration
        fun active(name: String): Boolean {
            val p = jobs[name] ?: return false
            return p.first == gen && p.second?.isActive == true
        }
        val botLoop = active("botLoop")
        val hotExit = active("hotExit")
        val hostOpen = try { HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { base.hostTrackerOpenCount }
        val sellStarted = try { com.lifecyclebot.engine.sell.SellReconciler.isStarted } catch (_: Throwable) { base.sellReconcilerStarted }
        val scanner = base.scannerActive
        val derivedState = when {
            base.state == RuntimeState.STOPPING -> RuntimeState.STOPPING
            botLoop -> RuntimeState.RUNNING
            base.state == RuntimeState.STARTING -> RuntimeState.STARTING
            else -> base.state
        }
        return base.copy(
            state = derivedState,
            botLoopJobActive = botLoop,
            hotExitJobActive = hotExit,
            sellReconcilerStarted = sellStarted,
            hostTrackerOpenCount = hostOpen,
            scannerActive = scanner,
        )
    }

    private fun publish(s: Snapshot) {
        _state.value = s.copy(updatedAtMs = System.currentTimeMillis())
    }
}
