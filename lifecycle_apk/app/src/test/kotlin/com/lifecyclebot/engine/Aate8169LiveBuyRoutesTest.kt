package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8169 — owner 5.0.8168 live log: every Jupiter fallback buy died on "Jupiter POST 503: {}", which was the app's
 * own ApiBackoff lockout answering the swap build, and the signed-tx fan-out never reached the free public RPCs.
 */
class Aate8169LiveBuyRoutesTest {
    private fun src(p: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aLocalLockoutIsTransientSoTheBuyPivotsVenue() {
        assertTrue(JupiterApi.isTransientPostFailure8089("Jupiter POST LOCAL_LOCKOUT (503): ApiBackoff lockout"))
        assertFalse(JupiterApi.isTransientPostFailure8089("Jupiter POST 400: invalid quoteResponse"))
    }

    @Test fun broadcastTargetsArePaidFirstThenPublicWithoutDuplicates() {
        val paid = listOf("https://mainnet.helius-rpc.com/?api-key=k", "https://solana-mainnet.g.alchemy.com/v2/a", "https://api.mainnet-beta.solana.com")
        val pub = listOf("https://solana-rpc.publicnode.com", "https://api.mainnet-beta.solana.com", "")
        assertEquals(
            listOf("https://mainnet.helius-rpc.com/?api-key=k", "https://solana-mainnet.g.alchemy.com/v2/a",
                "https://api.mainnet-beta.solana.com", "https://solana-rpc.publicnode.com"),
            RuntimeProviderAuthority6685.broadcastTargets8169(paid, pub),
        )
        assertEquals(pub.filter { it.isNotBlank() }.distinct().toSet(), RuntimeProviderAuthority6685.broadcastTargets8169(emptyList(), pub).toSet())
    }

    @Test fun liveTradeRequestsAreNeverAnsweredByOurOwnLockout() {
        val jup = src("network/JupiterApi.kt")
        val pump = src("network/PumpFunDirectApi.kt")
        val hc = src("network/HostCircuitInterceptor.kt")
        assertTrue(jup.contains(".header(HostCircuitInterceptor.EXECUTION_HEADER_8169, \"1\")"))
        assertTrue(jup.contains("host = endpoint.lowercase(), allowDuringLockout = true)"))
        assertTrue("a synthetic reply is never noted as a Jupiter 5xx",
            jup.indexOf("if (HostCircuitInterceptor.isSyntheticBlock(resp)) {") in 1 until jup.indexOf("notePost5xx8089(endpoint, code)"))
        assertTrue(pump.contains(".header(HostCircuitInterceptor.EXECUTION_HEADER_8169, \"1\")"))
        assertTrue(pump.contains("host = \"pump_direct_build\", allowDuringLockout = true)"))
        assertTrue(hc.contains("req.header(EXECUTION_HEADER_8169) != null) && provider != \"birdeye\""))
        assertTrue("never sent on the wire", hc.contains(".removeHeader(EXECUTION_HEADER_8169)"))
    }

    @Test fun signedBytesFanOutToPublicNodesAndTheBuyLogNamesTheRealRoute() {
        val wal = src("network/SolanaWallet.kt")
        assertTrue(wal.contains("RuntimeProviderAuthority6685.publicBroadcastRpcs8169())"))
        assertTrue(wal.contains("TX_FANOUT_ACCEPTED_PUBLIC_RPC_8169"))
        val ex = src("engine/Executor.kt")
        val env = ex.indexOf("PUMP_BUY_HELIUS_SENDER_7311")
        val log = ex.indexOf("route=\${if (pumpEnv7311 != null) \"SENDER\" else if (useJito) \"JITO\" else \"RPC\"}+RPC_FANOUT")
        assertTrue(env in 1 until log)
    }
}
