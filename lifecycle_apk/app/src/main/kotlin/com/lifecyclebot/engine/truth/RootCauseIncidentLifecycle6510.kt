package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/** V5.0.6510 — active incidents are distinct from immutable forensic history. */
object RootCauseIncidentLifecycle6510 {
    enum class State { OPEN, RESOLVED }
    data class Incident(val label: String, val state: State, val openedAtMs: Long, val resolvedAtMs: Long = 0L, val detail: String = "")
    private val incidents = ConcurrentHashMap<String, Incident>()
    fun open(label: String, detail: String = "") { incidents.compute(label) { _, prior -> Incident(label, State.OPEN, prior?.openedAtMs ?: System.currentTimeMillis(), 0L, detail) } }
    fun resolve(label: String, detail: String = "") { incidents.computeIfPresent(label) { _, prior -> prior.copy(state = State.RESOLVED, resolvedAtMs = System.currentTimeMillis(), detail = detail) } }
    fun isOpen(label: String): Boolean = incidents[label]?.state == State.OPEN
    fun stateOf(label: String): State? = incidents[label]?.state
    internal fun resetForTest() = incidents.clear()
}
