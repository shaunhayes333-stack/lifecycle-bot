package com.lifecyclebot.engine.cortex

import java.util.IdentityHashMap

/**
 * One capture's private edge arrays. Assessments often share the same immutable
 * voter-table arrays; their snapshot can share one detached copy as well.
 * Never retain this memo between captures or return an original live array.
 */
internal class CortexSnapshotArrays9045 {
    private val copies = IdentityHashMap<DoubleArray, DoubleArray>()
    var copied: Int = 0
        private set
    var reused: Int = 0
        private set

    fun copyOf(source: DoubleArray): DoubleArray {
        copies[source]?.let { reused++; return it }
        return source.copyOf().also { copies[source] = it; copied++ }
    }
}
