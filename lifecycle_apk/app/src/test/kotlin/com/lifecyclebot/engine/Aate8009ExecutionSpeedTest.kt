package com.lifecyclebot.engine

import com.lifecyclebot.network.PriorityFee8009
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8009ExecutionSpeedTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun priorityFeesFollowTheNetworkWithinBounds() {
        assertEquals(PriorityFee8009.FLOOR_MICRO_LAMPORTS, PriorityFee8009.clampMicro8009(Double.NaN))
        assertEquals(PriorityFee8009.FLOOR_MICRO_LAMPORTS, PriorityFee8009.clampMicro8009(500.0))
        assertEquals(400_000L, PriorityFee8009.clampMicro8009(400_000.0))
        assertEquals(PriorityFee8009.CAP_MICRO_LAMPORTS, PriorityFee8009.clampMicro8009(9e9))
        assertEquals(0.0004, PriorityFee8009.pumpSolFor8009(2_000_000L), 1e-12)
        assertTrue(PriorityFee8009.pumpSolFor8009(Long.MAX_VALUE / 1_000_000) <= PriorityFee8009.PUMP_CAP_SOL)
    }

    @Test fun noFixedSleepsOnTheSendPath() {
        assertEquals(0L, SecurityGuard.SIGN_BROADCAST_DELAY_MS)
        assertEquals(250L, SlippageGuard.QUOTE_DELAY_MS)
    }

    @Test fun sendsAreEchoedAndRebroadcastUntilTheyLand() {
        val w = src("network/SolanaWallet.kt")
        assertTrue(w.contains("echoSend8009(senderSig, signedB64, viaSender = false)"))
        assertTrue(w.contains("echoSend8009(signature, echo.first, echo.second)"))
        assertTrue(w.contains("} finally { inFlight8009.remove(signature) }"))
        assertTrue(w.contains("solBalance8009?.let { (at, v) -> if (System.currentTimeMillis() - at < SOL_BALANCE_TTL_MS_8009) return v }"))
        assertTrue(w.contains("JitoMEVProtection.submitProtectedNoWait6489(signedB64, jitoTipLamports)"))
        assertTrue(src("network/SharedHttpClient.kt").contains(".connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))"))
        assertTrue(src("network/JupiterApi.kt").contains("PriorityFee8009.jupiterMicroLamports("))
        assertTrue(src("network/PumpFunDirectApi.kt").contains("PriorityFee8009.pumpPortalSol("))
        assertTrue(src("engine/BotService.kt").contains("com.lifecyclebot.network.ExecWarm8009.start()"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("if (jupiterCircuitOpen || emergencyDirect8009 || emergencyRouteEscalated7807(ts, reason)) emptyList() else slippageLevels"))
        assertTrue(ex.contains("Thread.sleep(if (pollNum == 1) 1_500L else pollIntervalMs)"))
    }
}
