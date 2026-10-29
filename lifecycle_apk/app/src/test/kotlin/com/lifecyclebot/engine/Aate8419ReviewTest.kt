package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.TradePlan7739
import com.lifecyclebot.network.GradTicks8018
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** V5.0.8419 — review of the 8418 mark / stop / feed / plan changes (c92cda8e2..8e29fb1df). */
class Aate8419ReviewTest {
    @Before fun reset() { StopMarkConfirm8418.resetForTest8419(); MarkGate8418.resetForTest8418() }

    private fun heldOffBasis(mint: String): TokenState = TokenState(mint = mint, symbol = "S").apply {
        lastPriceSource = "DEXSCREENER_BATCH"; lastPricePoolAddr = "poolB"
        position = position.copy(entryPrice = 1.0e-5, entryTime = 1_000L, entryPoolAddress = "poolA", qtyToken = 10.0)
    }

    // ── 1. a loss stop whose executable ask is never answered fires on its mark after 15 s (8418: waited until -60 %) ──

    @Test fun anUnansweredExecutableAskFiresTheStopOnItsMarkAfterFifteenSeconds() {
        val ts = heldOffBasis("Aate8419StopMint1111111111111111111111111111")
        val t0 = 60_000L
        assertFalse("first ask: wait", StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = t0))
        assertFalse("still inside the bound", StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = t0 + 14_999L))
        assertTrue("no answer in 15 s: the stop fires on its mark", StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = t0 + 15_000L))
        assertTrue("paper is never gated", StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = true, nowMs = t0))
        assertTrue(StopMarkConfirm8418.awaitExpired8419(1_000L, 16_000L))
        assertFalse(StopMarkConfirm8418.awaitExpired8419(1_000L, 15_999L))
        assertFalse("no ask yet", StopMarkConfirm8418.awaitExpired8419(0L, 999_999L))
    }

    @Test fun aRefutingQuoteMustBeNewerThanTheAskAndABreachingOneFiresAtAnyAge() {
        val mint = "Aate8419QuoteMint111111111111111111111111111"
        val ts = heldOffBasis(mint)
        val now = System.currentTimeMillis()   // the quote cache is judged fresh (60 s) against the wall clock
        // -3 % quote 20 s old: it pre-dates the ask, so it cannot answer for the mark that followed it — wait, not fire
        MarkIdentityRepairAuthority7236.recordExecutableForTest8419(mint, 0.97e-5, now - 20_000L)
        assertFalse(StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = now))
        // the same price observed after the ask refutes the stop
        MarkIdentityRepairAuthority7236.recordExecutableForTest8419(mint, 0.97e-5, now + 1_000L)
        assertFalse(StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = now + 2_000L))
        // a breaching quote confirms whatever its age inside the cache window
        MarkIdentityRepairAuthority7236.recordExecutableForTest8419(mint, 0.70e-5, now - 30_000L)
        assertTrue(StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = now + 3_000L))
        // pure verdicts
        assertNull(StopMarkConfirm8418.quoteVerdict8419(null, 0L, 10L, 1.0e-5, -25.0))
        assertNull("older than the ask and not breached", StopMarkConfirm8418.quoteVerdict8419(0.97e-5, 5L, 10L, 1.0e-5, -25.0))
        assertEquals(false, StopMarkConfirm8418.quoteVerdict8419(0.97e-5, 10L, 10L, 1.0e-5, -25.0))
        assertEquals(true, StopMarkConfirm8418.quoteVerdict8419(0.70e-5, 5L, 10L, 1.0e-5, -25.0))
    }

    // ── 2. the coin's own chain state is on the position's basis (8418 read the curve-account tick as a trade stream) ──

    @Test fun theCurveAccountTickAndEveryHeldPoolTickAreOnBasis() {
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMP_PORTAL_TRADE_WS_7278", "", ""))
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMPSWAP_VAULT_WS_8018", "", ""))
        assertTrue(StopMarkConfirm8418.onBasis8418("POOL_VAULT_WS_8418_RaydiumV4", "", "MINT_ROUTE:x"))
        assertTrue(StopFeed8327.chainMark8327("POOL_VAULT_WS_8418_RaydiumV4"))
        assertTrue(StopFeed8327.chainMark8327("POOL_STATE_WS_8418_OrcaWhirlpool"))
        // the rapid monitor's 7735 heuristic (TRADE_WS) never overrides a chain mark; it still binds a poll of another pool
        assertFalse(StopMarkConfirm8418.offBasis8419(true, "PUMP_PORTAL_TRADE_WS_7278", "", "curveX"))
        assertTrue(StopMarkConfirm8418.offBasis8419(true, "DEXSCREENER_BATCH", "poolB", "poolA"))
        assertTrue(StopMarkConfirm8418.offBasis8419(false, "DEXSCREENER_BATCH", "poolB", "poolA"))
        assertFalse(StopMarkConfirm8418.offBasis8419(false, "DEXSCREENER_PAIR_POLL", "poolA", "poolA"))
        assertTrue(StopMarkConfirm8418.offBasis8419(false, "HELD_HOT_FANOUT_UNCORROBORATED_7419", "poolA", "poolA"))
        val ts = TokenState(mint = "Aate8419CurveMint111111111111111111111111111", symbol = "C").apply {
            lastPriceSource = "PUMP_PORTAL_TRADE_WS_7278"
            position = position.copy(entryPrice = 1.0e-5, entryTime = 1_000L, qtyToken = 10.0)
        }
        assertTrue("a curve-fed position's stop fires at once", StopMarkConfirm8418.confirmed8418(ts, -30.0, -25.0, paper = false, nowMs = 5_000L))
    }

    // ── 3. only a RISE is held for a second print; a rug-shaped drop reaches the row at once ──

    @Test fun aRugPrintIsNeverHeld() {
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418("RUG|1", 1.0e-5, 1_000L, 0.3e-5, 2_000L))
        // a held rise followed by the rug: the rug print is accepted and the candidate dropped
        assertEquals(MarkGate8418.Decision8418.HOLD, MarkGate8418.decide8418("R2|1", 1.0e-5, 1_000L, 4.0e-5, 2_000L))
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418("R2|1", 1.0e-5, 1_000L, 0.3e-5, 2_100L))
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418("R2|1", 0.3e-5, 2_100L, 0.31e-5, 2_200L))
        assertTrue(MarkGate8418.isRise8419(1.0, 3.0))
        assertFalse(MarkGate8418.isRise8419(3.0, 1.0))
        assertTrue("the two-way diagnostic is unchanged", MarkGate8418.isJump8418(3.0, 1.0))
        // a rise is still held (the 8418 journal's 24.5x print)
        assertEquals(MarkGate8418.Decision8418.HOLD, MarkGate8418.decide8418("UP|1", 1.2879e-5, 1_000L, 3.157e-4, 9_000L))
    }

    // ── 4. the held-pool feed prices by vault ratio only where the vault ratio is the price ──

    @Test fun aConcentratedOrDynamicPoolIsNeverPricedByItsVaults() {
        assertTrue(GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.PUMP_AMM))
        assertTrue(GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.RAYDIUM_V4))
        assertTrue(GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.RAYDIUM_CPMM))
        assertFalse("DAMM v2 keeps liquidity in a range", GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.METEORA_DAMM_V2_8418))
        assertFalse(GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertFalse(GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.ORCA_WHIRLPOOL_8418))
        assertFalse("DAMM v1 has no vault layout", GradTicks8018.vaultRatioPrices8419(OnChainDepth8038.METEORA_DAMM_V1_8418))
        // what the resolver may follow at all — decided before any mint / vault read
        assertTrue(GradTicks8018.followable8419(OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertTrue(GradTicks8018.followable8419(OnChainDepth8038.PUMP_AMM))
        assertFalse(GradTicks8018.followable8419(OnChainDepth8038.METEORA_DAMM_V2_8418))
        assertFalse("the pump bonding-curve program is CurveTicks7968's, not this feed's", GradTicks8018.followable8419("6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"))
        // the row's dex label names the tick's venue (8418 wrote PUMPSWAP for every pool)
        assertEquals("RAYDIUMV4", GradTicks8018.dexFor8419("POOL_VAULT_WS_8418_RaydiumV4"))
        assertEquals("ORCAWHIRLPOOL", GradTicks8018.dexFor8419("POOL_STATE_WS_8418_OrcaWhirlpool"))
        assertEquals("PUMPSWAP", GradTicks8018.dexFor8419("PUMPSWAP_VAULT_WS_8018"))
    }

    // ── 5. the evidence-admit wait is for an unretraced impulse, not for a read that is true of every three-bar tape ──

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = TradePlan7739.Bar(i * 15_000L, o, h, l, c)

    @Test fun onlyAnUnretracedImpulseWaits() {
        val tier = TradePlan7739.tierFor(50_000.0)   // minImpulsePct 30
        val rising = listOf(bar(0, 1.0, 1.1, 1.0, 1.1), bar(1, 1.1, 1.3, 1.1, 1.3), bar(2, 1.3, 1.5, 1.3, 1.5))
        val falling = listOf(bar(0, 1.5, 1.5, 1.4, 1.4), bar(1, 1.4, 1.4, 1.2, 1.2), bar(2, 1.2, 1.2, 1.0, 1.0))
        val mild = listOf(bar(0, 1.0, 1.02, 1.0, 1.02), bar(1, 1.02, 1.05, 1.02, 1.05), bar(2, 1.05, 1.08, 1.05, 1.08))
        assertTrue("+50 % with the high in the last bar is the owner's shape", TradePlan7739.unretracedImpulse8419(rising, tier))
        assertFalse("a decline from the first bar is not an impulse to chase", TradePlan7739.unretracedImpulse8419(falling, tier))
        assertFalse("+8 % is under the tier's impulse", TradePlan7739.unretracedImpulse8419(mild, tier))
        assertFalse(TradePlan7739.unretracedImpulse8419(emptyList(), tier))
        // the read 8418 waited on says the same thing about all three tapes at three bars
        assertEquals("NO_PULLBACK_YET", TradePlan7739.pullbackReclaim(rising, tier, 3).why)
        assertEquals("NO_PULLBACK_YET", TradePlan7739.pullbackReclaim(falling, tier, 3).why)
        assertEquals("NO_PULLBACK_YET", TradePlan7739.pullbackReclaim(mild, tier, 3).why)
    }

    // ── wiring ──

    @Test fun wiring() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("val offBasis8418 = StopMarkConfirm8418.offBasis8419(offBasis, ts.lastPriceSource, ts.lastPricePoolAddr, ts.position.entryPoolAddress)"))
        assertTrue(bot.contains("StopMarkConfirm8418.executableConfirms8419(ts, maxOf(catastropheThreshold, -HARD_FLOOR_STOP_PCT_CONST))"))
        assertFalse("the rapid monitor no longer waits without bound on its own quote read", bot.contains("return@run execPnl <= catastropheThreshold || execPnl <= -HARD_FLOOR_STOP_PCT_CONST"))
        assertTrue(bot.contains("ts.lastPriceDex = com.lifecyclebot.network.GradTicks8018.dexFor8419(source8018)"))
        val grad = java.io.File("src/main/kotlin/com/lifecyclebot/network/GradTicks8018.kt").readText()
        assertTrue(grad.contains("if (!followable8419(owner8418)) continue"))
        assertTrue(grad.contains("!vaultRatioPrices8419(owner8418) -> null"))
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MarkGate8418.kt").readText()
        assertTrue(gate.contains("if (inWindow && isRise8419(refPx, px))"))
        val mira = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkIdentityRepairAuthority7236.kt").readText()
        assertTrue(mira.contains("fun requestExecutableQuote7301(mint: String, tokenDecimals: Int, minGapMs: Long = EXEC_DEBOUNCE_MS_7301)"))
    }
}
