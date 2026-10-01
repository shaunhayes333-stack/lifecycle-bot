package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import org.junit.Assert.*
import org.junit.Test

class Aate7688SlippageSimulationTest {
    @Test fun onlyJupiterSlippageBeforeBroadcastCanRefresh() {
        val program = "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 failed:"
        assertTrue(JupiterApi.retryableSlippageSimulation7688("Simulate failed: error | $program custom program error: 0x1771"))
        assertFalse(JupiterApi.retryableSlippageSimulation7688("Simulate failed: Custom:6001 | Program OtherDex failed: 0x1771"))
        assertFalse(JupiterApi.retryableSlippageSimulation7688("RPC error: $program 0x1771"))
        assertFalse(JupiterApi.retryableSlippageSimulation7688("Simulate failed: $program custom program error: 0x177e"))
        assertFalse(JupiterApi.retryableSlippageSimulation7688(null))
    }
}
