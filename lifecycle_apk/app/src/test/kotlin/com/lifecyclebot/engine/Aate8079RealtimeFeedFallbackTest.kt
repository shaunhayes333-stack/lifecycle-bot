package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** Existing authenticated RPC can supply held-position ticks when Helius WS fails. */
class Aate8079RealtimeFeedFallbackTest {
    @Test fun preservesExistingRpcAuthPathAndDoesNotInventEndpoint() {
        val runtime = RuntimeProviderAuthority6685
        assertEquals("wss://solana-mainnet.g.alchemy.com/v2/test-key",
            runtime.websocketFromRpc8079("https://solana-mainnet.g.alchemy.com/v2/test-key"))
        assertEquals("wss://mainnet.helius-rpc.com/?api-key=test-key",
            runtime.websocketFromRpc8079("https://mainnet.helius-rpc.com/?api-key=test-key"))
        assertNull(runtime.websocketFromRpc8079("http://insecure-host"))
        assertNull(runtime.websocketFromRpc8079(""))
    }

    @Test fun curveAndGraduatedTickStreamsShareOneProviderAuthority() {
        fun src(name: String) = java.io.File("src/main/kotlin/com/lifecyclebot/network/$name").readText()
        assertTrue(src("CurveTicks7968.kt").contains("RuntimeProviderAuthority6685.websocketCandidates8079"))
        assertTrue(src("GradTicks8018.kt").contains("RuntimeProviderAuthority6685.websocketCandidates8079"))
        assertFalse(src("CurveTicks7968.kt").contains(".url(\"wss://mainnet.helius-rpc.com/?api-key=$"))
        assertFalse(src("GradTicks8018.kt").contains(".url(\"wss://mainnet.helius-rpc.com/?api-key=$"))
    }
}
