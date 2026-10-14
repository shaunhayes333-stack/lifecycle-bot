package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8094ClosedPositionAndPretradeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aRuntimePositionIsRetiredOnlyWithEveryCloseProof() {
        // EMUZCh: entry 23:50, canonical close 23:56:57, wallet absent -> retire.
        assertTrue(closedRuntimeRetirable8094(entryTimeMs = 1_000L, closedAtMs = 2_000L, walletHolds = false))
        // A wallet that still holds the mint is never retired.
        assertFalse(closedRuntimeRetirable8094(1_000L, 2_000L, walletHolds = true))
        // A close of an EARLIER entry cannot retire a fresh buy.
        assertFalse(closedRuntimeRetirable8094(entryTimeMs = 3_000L, closedAtMs = 2_000L, walletHolds = false))
        // No canonical close at all.
        assertFalse(closedRuntimeRetirable8094(1_000L, 0L, walletHolds = false))
    }

    @Test fun requestSellRetiresBeforeAnySellWork() {
        val exec = src("engine/Executor.kt")
        val core = exec.substringAfter("private fun requestSellCore7948(").substringBefore("LifecycleHold8065.defer8065")
        assertTrue(core.contains("if (retireClosedRuntimePosition8094(ts, reason)) return SellResult.ALREADY_CLOSED"))
        val helper = exec.substringAfter("private fun retireClosedRuntimePosition8094").substringBefore("    /**")
        assertTrue(helper.contains("firstOpenForMint(mint)"))
        assertTrue(helper.contains("HostWalletTokenTracker.hasOpenPosition(mint)"))
    }

    @Test fun pretradeBlocksNameTheirExactCauseAndSealRelation() {
        assertEquals("TOP_HOLDER_FATAL", pretradeSubReason8094("TOP_HOLDER_FATAL: 41% > 35%"))
        assertEquals("LIQ_BELOW_HARD_FLOOR", pretradeSubReason8094("liq below hard floor"))
        assertEquals("UNSPECIFIED", pretradeSubReason8094("  "))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("SAFE", sealHardNo = true, sealRug = 80))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("HARD_BLOCK", false, 80))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("SAFE", false, 0))
        assertEquals("CHANGED_AFTER_SEAL", pretradeSealRelation8094("SAFE", false, 80))
        val exec = src("engine/Executor.kt")
        assertFalse(exec.contains("emitLiveBuyFail(ts, sol, \"PRETRADE_HARD_BLOCK\","))
        assertTrue(exec.contains("emitLiveBuyFail(ts, sol, \"PRETRADE_HARD_BLOCK_\$sub\", detail)"))
        // A named pre-trade block is still terminal, never a deferral.
        assertFalse(PipelineHealthCollector.isNonTerminalBuyDeferral7868("PRETRADE_HARD_BLOCK_TOP_HOLDER_FATAL"))
    }

    @Test fun freshTokenShapeComesFromItsObservedPrices() {
        val t0 = 1_000_000L
        val ticks = listOf(t0 to 1.0, t0 + 20_000L to 1.5, t0 + 40_000L to 2.0, t0 + 70_000L to 1.8)
        val s = com.lifecyclebot.engine.cortex.TickShape8094.of(ticks, t0 + 75_000L)!!
        assertEquals(80.0, s.runupPct, 1e-9)        // 1.8 vs low 1.0
        assertEquals(0.9, s.pxPeak, 1e-9)           // 1.8 vs high 2.0
        assertEquals(10.0, s.ddPct, 1e-9)
        assertEquals(80.0, s.chg5mPct, 1e-9)        // span < 5m: change since the first tick
        // Too few, too tight, or stale ticks vouch for nothing.
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(ticks.take(2), t0 + 75_000L))
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(listOf(t0 to 1.0, t0 + 3_000L to 1.1, t0 + 6_000L to 1.2), t0 + 7_000L))
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(ticks, t0 + 30L * 60_000L))
        // Under a minute of ticks gives shape but no change.
        val short = com.lifecyclebot.engine.cortex.TickShape8094.of(listOf(t0 to 1.0, t0 + 15_000L to 1.2, t0 + 30_000L to 1.1), t0 + 31_000L)!!
        assertTrue(short.chg5mPct.isNaN())
    }

    @Test fun heliusExhaustionIsRecognisedAndMovesHeliusToTheBackOfTheLadder() {
        val h = com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
        assertTrue(h.isExhaustionAnswer8094(429, "{\"error\":\"max usage reached for this plan, upgrade your plan\"}"))
        assertTrue(h.isExhaustionAnswer8094(403, "credits exhausted"))
        assertFalse(h.isExhaustionAnswer8094(429, "Too many requests"))
        assertFalse(h.isExhaustionAnswer8094(429, "AATE_HELIUS_BUDGET_8066"))
        assertFalse(h.isExhaustionAnswer8094(500, "credits"))
        val ladder = listOf("https://mainnet.helius-rpc.com/?api-key=x", "https://solana-mainnet.g.alchemy.com/v2/y", "https://api.mainnet-beta.solana.com")
        assertEquals(ladder, RuntimeProviderAuthority6685.heliusLastWhenExhausted8094(ladder, exhausted = false))
        assertEquals(listOf(ladder[1], ladder[2], ladder[0]), RuntimeProviderAuthority6685.heliusLastWhenExhausted8094(ladder, exhausted = true))
    }

    @Test fun aLanesEvRefusalStopsBindingOnlyWhenItsRefusedCohortProvablyOutEarns() {
        // Refused cohort clearly positive and better than admitted -> refusals are inverted.
        assertTrue(refusalInverted8094(refN = 25, refMeanPct = 40.0, refSePct = 15.0, admN = 12, admMeanPct = -3.5))
        // CRYPTO_ALT today: n=9 is not evidence yet.
        assertFalse(refusalInverted8094(refN = 9, refMeanPct = 121.0, refSePct = 90.0, admN = 7, admMeanPct = -3.5))
        // A noisy refused mean inside one SE of zero is not inversion.
        assertFalse(refusalInverted8094(25, 10.0, 12.0, 12, -3.5))
        // Admitted cohort earns more: refusing is right.
        assertFalse(refusalInverted8094(25, 20.0, 5.0, 12, 30.0))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        assertTrue(gate.contains("!livePositiveEv7828 && !evRefusalInverted8094"))
        assertTrue(gate.contains("!oracleHardSafety7287"))
    }

    @Test fun sellsAreNotBlockedForeverByAnUnreadablePreviousSignature() {
        val l = com.lifecyclebot.network.SignedTxLedger8085
        val lapse = l.SELL_GUARD_LAPSE_MS_8094
        assertTrue(lapse >= 150_000L)                                  // past any blockhash lifetime
        assertTrue(l.sellGuardLapsed8094("SELL", 1_000L, 1_000L + lapse))
        assertFalse(l.sellGuardLapsed8094("SELL", 1_000L, 1_000L + lapse - 1))
        assertFalse("a buy is never doubled", l.sellGuardLapsed8094("BUY", 1_000L, 1_000L + 10 * lapse))
        // An exhausted plan is a dead endpoint (fail over), not the caller's answer.
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "max usage reached"))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(402, ""))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "You have exceeded your credits"))
        assertFalse(com.lifecyclebot.network.rpcExhaustedReply8094(200, "Transaction simulation failed"))
        val w = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(w.contains("if (rpcExhaustedReply8094(code, errMsg)) {"))
        assertTrue(w.contains("SignedTxLedger8085.sellGuardLapsed8094(intent.side, prev.createdAtMs"))
    }
}
