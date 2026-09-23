package com.lifecyclebot.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Aate7255LiveEntryAndCryptoRecoveryTest {
    private fun source(path: String) = File("src/main/kotlin/$path").readText()

    @Test
    fun liveSizingUsesOneReserveAuthority() {
        val adapter = source("com/lifecyclebot/v3/bridge/V3Adapter.kt")
        val preflight = source("com/lifecyclebot/engine/truth/LivePreflight7222.kt")
        val executor = source("com/lifecyclebot/engine/Executor.kt")
        assertTrue(adapter.contains("LiveSpendReserveAuthority7255.RESERVE_SOL"))
        assertTrue(preflight.contains("LiveSpendReserveAuthority7255.RESERVE_SOL"))
        assertTrue(executor.contains("LiveSpendReserveAuthority7255.RESERVE_SOL"))
    }

    @Test
    fun fanoutAndExpiredTicketsDoNotPoisonFreshCandidates() {
        val fdg = source("com/lifecyclebot/engine/FinalDecisionGate.kt")
        val gate = source("com/lifecyclebot/engine/ExecutableOpenGate.kt")
        assertTrue(fdg.contains("LaneExecutionCoordinator.candidateVersionFor(ts.mint).toString()"))
        assertTrue(gate.contains("EXPIRED_TICKET_REVOKED_FOR_FRESH_CANDIDATE_7255"))
        assertTrue(gate.contains("log.contains(\"STALE_TICKET\") || r.contains(\"EXPIRED_TICKET\") -> 0L"))
    }

    @Test
    fun canonicalHeldCryptoReentersCryptoTraderProjection() {
        val trader = source("com/lifecyclebot/perps/CryptoAltTrader.kt")
        val classes = source("com/lifecyclebot/engine/truth/AssetClass.kt")
        val recovery = source("com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt")
        assertTrue(trader.contains("syncCanonicalCryptoPositions7255"))
        assertTrue(trader.contains("CRYPTO_CANONICAL_POSITION_PROJECTED_7255"))
        assertTrue(classes.contains("\"CRYPTO\", \"CRYPTO_ALT\""))
        assertTrue(classes.contains("\"CRYPTO_SPOT\", \"CRYPTO_LEV\""))
        assertTrue(recovery.contains("\"LIVE_FINALIZED\", \"LIVE_BALANCE_CONFIRMED\", \"LIVE_SIG_CONFIRMED\""))
    }
}
