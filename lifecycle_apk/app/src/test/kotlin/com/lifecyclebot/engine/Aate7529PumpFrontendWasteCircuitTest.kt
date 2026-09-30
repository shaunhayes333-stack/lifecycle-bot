package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7529PumpFrontendWasteCircuitTest {
    @Test fun frontend_observation_has_pass_level_half_open_circuit() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        assertTrue(s.contains("PUMPFUN_FRONTEND_FANOUT_CIRCUIT_OPEN_7529"))
        assertTrue(s.contains("PUMPFUN_FRONTEND_FANOUT_CIRCUIT_SKIP_7529"))
        assertTrue(s.contains("PUMPFUN_FRONTEND_FANOUT_HALF_OPEN_7529"))
        assertTrue(s.contains("allTargets7529.take(1)"))
        assertTrue(s.contains("PUMP_FRONTEND_COOLDOWN_MS_7529 = 5L * 60_000L"))
    }

    @Test fun circuit_is_scoped_to_frontend_not_execution_or_curve_rpc() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        val block = s.substringAfter("V5.0.7529 — pump.fun FRONTEND observation feed circuit only")
            .substringBefore("fun installRpc7088")
        assertFalse(block.contains("PumpFunDirectApi.buildBuyTx"))
        assertFalse(block.contains("PumpFunDirectApi.buildSellTx"))
        assertTrue(s.contains("PUMP_CURVE_RPC"))
    }
}
