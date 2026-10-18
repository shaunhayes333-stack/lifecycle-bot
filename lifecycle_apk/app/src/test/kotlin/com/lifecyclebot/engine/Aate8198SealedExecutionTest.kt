package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** Behavioral tests of the production seal consumer, not a simulated replacement executor. */
class Aate8198SealedExecutionTest {
    private val now = 1_800_000_000_000L
    private fun intent() = ExecutableOpenGate.ExecutionIntent(
        attemptId = "LIVE:QUALITY:8198", candidateId = "candidate8198", candidateVersion = 8198L,
        mint = "mint8198", mode = "LIVE", canonicalLane = "QUALITY", fdgVerdict = "BUY", fdgAllowed = true,
        authorityVersion = 8198L, resolvedSize = 0.04, createdAt = now - 100L, symbol = "TEST",
        effectiveEntryScore7256 = 24, finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
        fdgDecisionId6613 = "fdg8198", expiresAtMs6613 = now + 15_000L,
    )
    private fun score(i: ExecutableOpenGate.ExecutionIntent?) =
        SealedExecutionSize7835.strategyScore8198(i, "LIVE:QUALITY:8198", "mint8198", "QUALITY", now)

    @Test fun sealed_score_is_consumed_without_current_strategy_floor() {
        // A score admitted under a 20 floor must not be re-voted under a later 30 floor.
        assertEquals(24.0, requireNotNull(score(intent())), 0.0)
        assertEquals(0.04, intent().resolvedSize, 0.0)
    }
    @Test fun another_attempt_cannot_borrow_sealed_strategy() {
        assertNull(score(intent().copy(attemptId = "other")))
        assertNull(score(intent().copy(mint = "otherMint")))
        assertNull(score(intent().copy(canonicalLane = "MOONSHOT")))
        assertNull(score(intent().copy(mode = "PAPER")))
    }
    @Test fun expired_future_or_unproven_authority_is_not_consumed() {
        assertNull(score(intent().copy(expiresAtMs6613 = now)))
        assertNull(score(intent().copy(createdAt = now + 1)))
        assertNull(score(intent().copy(candidateVersion = 0)))
        assertNull(score(intent().copy(fdgDecisionId6613 = "")))
        assertNull(score(intent().copy(finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.UNKNOWN)))
        assertNull(score(null))
    }
    @Test fun hard_safety_refusal_still_binds() {
        assertNull(score(intent().copy(hardNoReasons = listOf("CONFIRMED_RUG"))))
        assertNull(score(intent().copy(fdgAllowed = false)))
        assertNull(score(intent().copy(fdgVerdict = "WAIT")))
    }
    @Test fun sealed_size_and_current_capital_constraints_still_bind() {
        assertEquals("EXECUTION_TICKET_SIZE_MISMATCH_7835",
            SealedExecutionSize7835.refusal(intent(), "mint8198", "LIVE", "QUALITY", 0.08))
        assertEquals("SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835", SealedExecutionSize7835.boundsRefusal(0.04, 0.02, 0.01))
        assertEquals("SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835", SealedExecutionSize7835.boundsRefusal(0.04, 0.10, 0.05))
        assertNull(score(intent().copy(resolvedSize = Double.NaN)))
        assertNull(score(intent().copy(effectiveEntryScore7256 = -1)))
    }
    @Test fun pending_signature_is_owned_by_exact_attempt_not_by_mint_alone() {
        val ledger = com.lifecyclebot.network.SignedTxLedger8085
        ledger.resetForTest()
        try {
            ledger.record("sig8198", "fixture", "blockhash8198",
                com.lifecyclebot.network.SignedTxLedger8085.Intent("mint8198", "BUY", intentId = "attempt8198"))
            assertEquals("sig8198", ledger.unresolvedAttempt8198("attempt8198")?.signature)
            assertNull(ledger.unresolvedAttempt8198("laterAttempt8198"))
            assertNull(ledger.unresolvedAttempt8198(""))
            assertTrue(ledger.get("sig8198")!!.unresolved)
        } finally { ledger.resetForTest() }
    }
    @Test fun refusal_mode_is_event_local_not_ui_local() {
        RuntimeModeAuthority.publishConfig(true, true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
        val reason = "MODE_PROOF_8198_${System.nanoTime()}"
        ToolkitSignalSheet.recordPreSizeRefusal7809("QUALITY", reason, "LIVE")
        val field = ToolkitSignalSheet::class.java.getDeclaredField("preSizeRefusals7809")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val counts = field.get(ToolkitSignalSheet) as java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
        try {
            assertEquals(1L, counts["LIVE|QUALITY|$reason"]?.get())
            assertNull(counts["PAPER|QUALITY|$reason"])
        } finally { counts.remove("LIVE|QUALITY|$reason"); counts.remove("PAPER|QUALITY|$reason") }
    }

    @Test fun stale_attempt_and_preauthorization_cannot_release_newer_cash_reservation() {
        val book = WalletCapacitySeal7868
        val i = intent().copy(mint = "reserve8198", createdAt = System.currentTimeMillis())
        book.record(i.mint, i.resolvedSize, 1.0, 100.0, candidateVersion = i.candidateVersion, lane = i.canonicalLane)
        book.bindAttempt8198(i)
        assertFalse(book.release(i.mint, attemptId = "old-attempt"))
        assertFalse(book.release(i.mint, candidateVersion = i.candidateVersion, lane = i.canonicalLane))
        assertTrue(book.release(i.mint, attemptId = i.attemptId))
        assertFalse(book.release(i.mint, attemptId = i.attemptId))
        book.record(i.mint, i.resolvedSize, 1.0, 100.0, candidateVersion = i.candidateVersion + 1, lane = i.canonicalLane)
        assertFalse(book.release(i.mint, candidateVersion = i.candidateVersion, lane = i.canonicalLane))
        assertTrue(book.release(i.mint, candidateVersion = i.candidateVersion + 1, lane = i.canonicalLane))
    }

    @Test fun discovery_can_request_quotes_without_manufacturing_quote_output() {
        val ts = com.lifecyclebot.data.TokenState("Discovery8198Mint11111111111111111111111", pairAddress = "pool8198")
        ts.lastLiquidityUsd = 50_000.0
        ts.lastPrice = 0.25
        ts.lastPriceUpdate = System.currentTimeMillis()
        ts.lastPriceDex = "RAYDIUM"
        val map = TokenMapAuthority.ensureDiscoveryTokenMap(ts, "DEXSCREENER")
        assertEquals("DEX_ROUTABLE", map.routeStatus)
        assertEquals(0.0, map.expectedOutAmount, 0.0)
        assertFalse(map.jupiterQuoteOk)
        assertTrue(TokenMapAuthority.executableForLiveBuy(ts))
        assertEquals(0.0, ts.tokenMap.expectedOutAmount, 0.0)
        val pump = com.lifecyclebot.data.TokenState("Discovery8198Pump11111111111111111111111")
        pump.lastPrice = 0.01
        pump.lastPriceUpdate = System.currentTimeMillis()
        pump.lastLiquidityUsd = 10_000.0
        val curve = TokenMapAuthority.ensureDiscoveryTokenMap(pump, "PUMP_FUN_NEW")
        assertEquals("PUMPFUN_BONDING_CURVE_EXECUTABLE", curve.routeStatus)
        assertEquals(0.0, curve.expectedOutAmount, 0.0)
        assertTrue(TokenMapAuthority.executableForLiveBuy(pump))
    }

    @Test fun final_quote_refuses_wrong_mint_zero_output_and_newer_generation() {
        val ts = com.lifecyclebot.data.TokenState("quoteBoundary8198")
        ts.lastPrice = 1.0
        ts.tokenMap.decimals = 6
        fun quote(mint: String, out: Long) = com.lifecyclebot.network.SwapQuote(org.json.JSONObject(), out, 0.1,
            inputMint = com.lifecyclebot.network.JupiterApi.SOL_MINT, outputMint = mint, inAmount = 40_000_000L)
        val q = SizingQuoteRecheck8153
        assertEquals("PRE_BROADCAST_QUOTE_IDENTITY_MISMATCH_8198", q.preBroadcastQuoteRefusal8167(ts, 0.04, quote("wrong", 4_000_000L), 100.0))
        assertEquals("PRE_BROADCAST_QUOTE_AMOUNT_INVALID_8198", q.preBroadcastQuoteRefusal8167(ts, 0.04, quote(ts.mint, 0L), 100.0))
        val at = System.currentTimeMillis()
        q.noteDecision8153(ts.mint, SizingQuoteRecheck8153.SealedEconomics8153(0.04, 40.0, 40.0, 2.0, 0.1, 1.0, at, lane8198 = "QUALITY"))
        q.bindSeal8153(ts.mint, 0.04, at, candidateVersion8198 = 8199, lane8198 = "QUALITY")
        assertEquals("PRE_BROADCAST_SEAL_GENERATION_CHANGED_8198", q.preBroadcastQuoteRefusal8167(ts, 0.04, quote(ts.mint, 4_000_000L), 100.0, intent().copy(mint = ts.mint)))
        assertNull(q.preBroadcastQuoteRefusal8167(ts, 0.04, quote(ts.mint, 4_000_000L), 100.0, intent().copy(mint = ts.mint, candidateVersion = 8199)))
    }


    /** V5.0.8198 P4 — another lane's FDG economics never bind to this lane's seal (same mint, two judges). */
    @Test fun seal_economics_are_bound_to_the_deciding_lane_only() {
        val ts = com.lifecyclebot.data.TokenState("crossLaneSeal8198")
        ts.lastPrice = 1.0
        ts.tokenMap.decimals = 6
        val q = SizingQuoteRecheck8153
        val at = System.currentTimeMillis()
        val quote = com.lifecyclebot.network.SwapQuote(org.json.JSONObject(), 4_000_000L, 0.1,
            inputMint = com.lifecyclebot.network.JupiterApi.SOL_MINT, outputMint = ts.mint, inAmount = 40_000_000L)
        // QUALITY decided on +40% gross; MOONSHOT then decided the same mint on economics that cannot pay.
        q.noteDecision8153(ts.mint, SizingQuoteRecheck8153.SealedEconomics8153(0.04, 40.0, 40.0, 2.0, 0.1, 1.0, at, lane8198 = "QUALITY"))
        q.noteDecision8153(ts.mint, SizingQuoteRecheck8153.SealedEconomics8153(0.04, 0.5, 0.5, 9.0, 0.1, 1.0, at, lane8198 = "MOONSHOT"))
        q.bindSeal8153(ts.mint, 0.04, at, candidateVersion8198 = 8199, lane8198 = "QUALITY")
        // QUALITY's ticket is judged on QUALITY's own economics, not MOONSHOT's later record.
        assertNull(q.preBroadcastQuoteRefusal8167(ts, 0.04, quote, 100.0, intent().copy(mint = ts.mint, candidateVersion = 8199)))
    }
}
