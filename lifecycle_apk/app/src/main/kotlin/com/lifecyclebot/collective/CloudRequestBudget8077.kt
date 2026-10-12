package com.lifecyclebot.collective

import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Cloud history replay must not occupy the trader/startup I/O pool. */
internal class CloudRequestBudget8077(
    permits: Int = 4,
    private val dispatcher: CoroutineDispatcher = AppDispatchers.sideEffect,
) {
    private val slots = Semaphore(permits)

    suspend fun <T> run(block: () -> T): T = slots.withPermit {
        runInterruptible(dispatcher) { block() }
    }
}
