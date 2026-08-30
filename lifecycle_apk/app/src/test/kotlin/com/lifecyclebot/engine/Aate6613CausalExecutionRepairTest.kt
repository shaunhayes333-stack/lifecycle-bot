package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class Aate6613CausalExecutionRepairTest {
    @Test fun `legacy UNKNOWN intent is rejected while sealed BUY is authoritative`() {
        val mint = "M6613_${System.nanoTime()}"
        val legacy = ExecutableOpenGate.ExecutionIntent(
            attemptId="LEGACY_6613", candidateId="$mint:1", candidateVersion=1L,
            mint=mint, mode="PAPER", canonicalLane="EXPRESS", fdgVerdict="BUY",
            fdgAllowed=true, authorityVersion=1L, resolvedSize=0.05,
            createdAt=System.currentTimeMillis(), symbol="X", hardNoReasons=emptyList(),
        )
        assertNull(ExecutableOpenGate.registerCanonicalIntent6554(legacy))
        val sealed = legacy.copy(
            attemptId="SEALED_6613",
            finalDecision6613=ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
            decisionAuthorityId6613="FDG:1", fdgDecisionId6613="$mint:1:EXPRESS:PAPER",
            fdgEvidence6613="verdict=BUY lane=EXPRESS authority=1",
        )
        assertSame(sealed, ExecutableOpenGate.registerCanonicalIntent6554(sealed))
        assertEquals("BUY", sealed.authoritativeSignal)
        assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(sealed, "UNKNOWN"))
    }

    @Test fun `valid source mint route observation promotes without redundant provider`() {
        val mint = "M6613_MARK_${System.nanoTime()}"; val now = System.currentTimeMillis()
        assertTrue(CanonicalPriceMarkRegistry6522.publish(CanonicalPriceMark6522(
            mint=mint, pairId="MINT_ROUTE:$mint", baseMint=mint, quoteMint="USD",
            source="DEXSCREENER_PAIR_POLL", timestampMs=now,
            priceUsd=PriceUsd(BigDecimal("0.0000123")), liquidityUsd=BigDecimal("5000"),
            purpose=CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )))
        val promoted = CanonicalPriceMarkRegistry6522.promoteObservationToExecutable6613(mint, now)
        assertTrue(promoted.promoted)
        assertEquals("CANONICAL_MINT_SOURCE_MARK_6613", promoted.mark?.identityProof6613)
        assertNotNull(CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE))
    }

    @Test fun `stale observation is rejected with exact reason`() {
        val mint = "M6613_STALE_${System.nanoTime()}"; val now = System.currentTimeMillis()
        assertTrue(CanonicalPriceMarkRegistry6522.publish(CanonicalPriceMark6522(
            mint, "MINT_ROUTE:$mint", mint, "USD", "DEXSCREENER_PAIR_POLL",
            now, PriceUsd(BigDecimal("1.25")), BigDecimal("10000"),
            CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )))
        assertEquals("STALE_SOURCE_MARK", CanonicalPriceMarkRegistry6522.promoteObservationToExecutable6613(mint, now + 600_000L).reason)
    }

    @Test fun `source contracts preserve specialist partial policy and crypto causality`() {
        val gate=File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val executor=File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sheet=File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val partial=File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperPartialOperation6510.kt").readText()
        val crypto=File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val bot=File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(gate.contains("resolveSealedIntent6613") && gate.contains("revalidateAndResealExpired6613"))
        assertTrue(gate.contains("RESTORED_ALLOW_TICKET_WITHOUT_BUY_DECISION"))
        assertTrue(executor.contains("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT"))
        assertTrue(sheet.contains("INTENT_CHOKED") && sheet.contains("MARK_CHOKED") && sheet.contains("LEARNING_CHOKED"))
        assertTrue(partial.contains("TierState6613") && partial.contains("QUANTITY_RESERVED") && partial.contains("COMPLETE"))
        val candidateStamp=crypto.indexOf("AssetClass.CRYPTO_ALT, \"CANDIDATE\"")
        val submit=crypto.indexOf("CanonicalEntryAuthority6551.submit", candidateStamp)
        assertTrue(candidateStamp >= 0 && submit > candidateStamp)
        assertTrue(crypto.contains("CRYPTO_LEARNED_SIZE_FLOORED_NONZERO_6613"))
        assertTrue(bot.contains("LEARNED_POLICY_NEGATIVE_LANE_WAIT_SHAPED_6613"))
    }
}
