package com.lifecyclebot.engine

import com.lifecyclebot.engine.SellLanding8368.Reason8368
import com.lifecyclebot.engine.sell.SellSafetyPolicy
import com.lifecyclebot.network.KeylessRpc8359
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8368 — sells that land: every sell input is an execution read (Helius-first ladder, never the keyless market
 * route), a stop never sends more than the wallet holds, partial / orphan sells never start under the policy slippage,
 * a stale quote is re-quoted before its first build, and every non-landed sell carries the chain's reason.
 */
class Aate8368SellLandingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()
    private fun bi(v: Long) = BigInteger.valueOf(v)

    // ── (a) route of every sell input ──

    @Test fun everySellInputIsAnExecutionReadNeverTheKeylessMarketRoute() {
        for (m in SellLanding8368.SELL_INPUT_METHODS_8368) {
            assertEquals(m, KeylessRpc8359.Route8359.EXECUTION, SellLanding8368.sellInputRoute8368(m))
        }
        // The execution set itself (no owner flag needed): blockhash, send, status, the owner's token balance.
        for (m in listOf("getLatestBlockhash", "isBlockhashValid", "sendTransaction", "getSignatureStatuses", "getTokenAccountBalance")) {
            assertEquals(m, KeylessRpc8359.Route8359.EXECUTION, KeylessRpc8359.route8359(m))
        }
        // An execution route is never re-ordered keyless-first.
        val ladder = listOf("https://mainnet.helius-rpc.com/?api-key=k", "https://api.mainnet-beta.solana.com")
        assertEquals(ladder, KeylessRpc8359.order8359(ladder, KeylessRpc8359.Route8359.EXECUTION, { true }))
    }

    @Test fun walletReadsBlockhashAndSendWalkTheHeliusFirstLadderNotTheMarketLadder() {
        val wallet = src("network/SolanaWallet.kt")
        val rpcBody = wallet.substringAfter("private fun rpc(method: String, params: JSONArray): JSONObject {").substringBefore("fun getTokenAccountsWithDecimals(")
        assertTrue(rpcBody.contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)"))
        assertFalse(rpcBody.contains("marketReadCandidates8359"))
        assertFalse(wallet.contains("KeylessRpc8359"))
        // The only market-ladder callers are market data (curve, supply, birth walk, cadence, keyless post).
        val probe = src("engine/sell/SellRouteProbe8144.kt")
        assertFalse(probe.contains("marketReadCandidates8359"))
        assertFalse(src("engine/PumpLanding8288.kt").contains("KeylessRpc8359"))
    }

    // ── (b) quantity clamp ──

    @Test fun stopQuantityIsNeverMoreThanTheWalletHolds() {
        // a wallet read after the last landed sell bounds the canonical quantity
        assertEquals(bi(600), SellLanding8368.clampRaw8368(bi(1_000), 6, bi(600), 6, 500L, 100L, 200L, false))
        assertEquals(bi(1_000), SellLanding8368.clampRaw8368(bi(1_000), 6, bi(5_000), 6, 500L, 100L, 200L, false))
        // no sell ever landed and no read: the confirmed fill is the holding
        assertEquals(bi(1_000), SellLanding8368.clampRaw8368(bi(1_000), 6, null, 6, 0L, 0L, 200L, false))
        // a read older than the last landed sell does not count; the canonical row absorbed that sell
        assertEquals(bi(1_000), SellLanding8368.clampRaw8368(bi(1_000), 6, bi(600), 6, 50L, 100L, 200L, false))
        // not provable
        assertNull("unresolved signed sell", SellLanding8368.clampRaw8368(bi(1_000), 6, bi(600), 6, 500L, 100L, 200L, true))
        assertNull("landed sell not absorbed", SellLanding8368.clampRaw8368(bi(1_000), 6, null, 6, 0L, 300L, 200L, false))
        assertNull("wallet last seen empty", SellLanding8368.clampRaw8368(bi(1_000), 6, BigInteger.ZERO, 6, 500L, 100L, 200L, false))
        assertNull("decimal skew", SellLanding8368.clampRaw8368(bi(1_000), 6, bi(600), 9, 500L, 100L, 200L, false))
        assertNull(SellLanding8368.clampRaw8368(BigInteger.ZERO, 6, null, 6, 0L, 0L, 0L, false))
    }

    @Test fun onlyALandedSellVoidsAnOlderWalletRead() {
        val confirmed = com.lifecyclebot.network.SignedTxLedger8085.Entry("s1", "", "bh", "M", "SELL", 0L, "", "", "JUPITER", 100L,
            com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED, 150L)
        val expired = com.lifecyclebot.network.SignedTxLedger8085.Entry("s2", "", "bh", "M", "SELL", 0L, "", "", "JUPITER", 400L,
            com.lifecyclebot.network.SignedTxLedger8085.State.EXPIRED_PROVEN, 480L)
        assertEquals(150L, SellLanding8368.lastMovingSellAt8368(listOf(confirmed, expired)))
        assertEquals(0L, SellLanding8368.lastMovingSellAt8368(listOf(expired)))
    }

    @Test fun stopRescueAndCanonicalFallbackAreClampedToTheWallet() {
        val exits = src("engine/Exits8363.kt")
        assertTrue(exits.contains("eligible8363(ts, reason, nowMs)?.let { SellLanding8368.clampToWallet8368(ts.mint, it, mutatedAt8368(ts)) }"))
        val wiring = src("engine/sell/StopLatencyWiring8153.kt")
        assertTrue(wiring.contains("val lastSellTouch = com.lifecyclebot.engine.SellLanding8368.lastMovingSellAt8368(rows)"))
        assertTrue(wiring.contains("// superseded by V5.0.8368"))
        // the planner itself still sizes min(canonical, wallet)
        assertTrue(src("engine/ProcessorAmountPlanner.kt").contains("val availableRaw = canonical.remainingQtyRaw.min(walletRaw)"))
    }

    // ── (c) slippage per reason ──

    @Test fun profitLockReasonsKeepTheNormalSellCapAfterThe8363Reclassification() {
        for (r in listOf("PROFIT_LOCK_8030", "PEAK_GIVEBACK_20", "SCALP_PROFIT_LOCK_8351", "RUNNER_GREEN_FLOOR_8363_120PK_55PCT")) {
            assertTrue(r, SellSafetyPolicy.maxSlippageBps(r) >= SellSafetyPolicy.HARD_MAX_SELL_SLIPPAGE_BPS)
            assertTrue(r, SellSafetyPolicy.initialSlippageBps(r) >= 200)
            assertEquals(r, SellSafetyPolicy.maxSlippageBps(r), SellSafetyPolicy.ladder(r).maxOrNull())
        }
    }

    @Test fun partialAndOrphanSellsNeverStartUnderThePolicyFloorAndKeepDynamicHeadroom() {
        assertEquals(200, SellLanding8368.partialSellSlipBps8368(100, "PARTIAL_TAKE_PROFIT"))
        assertEquals(200, SellLanding8368.partialSellSlipBps8368(10, "PARTIAL_TAKE_PROFIT"))
        assertEquals(400, SellLanding8368.partialSellSlipBps8368(200, "PARTIAL_TAKE_PROFIT"))
        assertEquals(500, SellLanding8368.partialSellSlipBps8368(400, "PARTIAL_TAKE_PROFIT"))
        assertEquals(300, SellLanding8368.partialSellSlipBps8368(100, "ORPHAN_SELL", 3))
        assertTrue(SellLanding8368.partialSellSlipBps8368(10, "ORPHAN_SELL", 3) >= 200)
        assertEquals(500, SellLanding8368.sellDynCapBps8368(200))
        assertEquals(500, SellLanding8368.sellDynCapBps8368(500))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("val sellSlippage = SellLanding8368.partialSellSlipBps8368(c.slippageBps, \"PARTIAL_TAKE_PROFIT\")"))
        assertTrue(exec.contains("dynamicSlippageMaxBps = SellLanding8368.sellDynCapBps8368(sellSlippage)"))
        assertTrue(exec.contains("val sellSlippage = SellLanding8368.partialSellSlipBps8368(c.slippageBps, \"ORPHAN_SELL\", 3)"))
        assertFalse(exec.contains("(c.slippageBps * 2).coerceAtMost(500)"))
    }

    // ── (d) sells keep their landing fees ──

    @Test fun sellsKeepUrgentFeesTheEntryOrdinaryChangeIsEntryOnly() {
        val exec = src("engine/Executor.kt")
        val ladder = exec.substringAfter("private fun liveSellInScope7314(")
            .substringAfter("val dynSlipCap = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason).coerceAtLeast(currentSlip)")
            .substringBefore("signDelayFor7948(reason)")
        assertTrue(ladder.contains("senderTipLamports = effectiveSenderTipLamports(c, urgent = fastLand7980)"))
        assertFalse(ladder.contains("ordinary8151 = true"))
        assertTrue(exec.contains("private fun entryOrdinary8151(mint: String): Boolean = false"))
    }

    // ── (e) blockhash / quote reuse ──

    @Test fun aStaleQuoteIsRequotedBeforeItsFirstBuildAndTheBlockhashCacheIsShort() {
        assertFalse(SellLanding8368.quoteStale8368(0L, 100_000L))
        assertFalse(SellLanding8368.quoteStale8368(95_000L, 100_000L))
        assertTrue(SellLanding8368.quoteStale8368(100_000L - SellLanding8368.QUOTE_MAX_AGE_MS_8368 - 1L, 100_000L))
        assertTrue(SellLanding8368.blockhashReusable8368(1_500L))
        assertFalse(SellLanding8368.blockhashReusable8368(SellLanding8368.BLOCKHASH_REUSE_MS_8368 + 1L))
        assertFalse(SellLanding8368.blockhashReusable8368(-1L))
        assertEquals(PumpLanding8288.BLOCKHASH_CACHE_MS_8337, SellLanding8368.BLOCKHASH_REUSE_MS_8368)
        SellLanding8368.resetForTest8368()
        val q = Any()
        SellLanding8368.noteQuoted8368(q, 1_000L)
        assertFalse(SellLanding8368.staleQuote8368(q, 5_000L))
        assertTrue(SellLanding8368.staleQuote8368(q, 1_000L + SellLanding8368.QUOTE_MAX_AGE_MS_8368 + 1L))
        assertFalse("an unknown quote is not stale", SellLanding8368.staleQuote8368(Any(), 999_999L))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("if (broadcastAttempts > 1 || SellLanding8368.staleQuote8368(quote)) {"))
        assertTrue(exec.contains("}.also { SellLanding8368.noteQuoted8368(it) }"))
    }

    // ── measurement ──

    @Test fun everyNonLandedSellIsClassifiedFromTheChainAnswer() {
        assertEquals(Reason8368.LANDED, SellLanding8368.classify8368("CONFIRMED", "await", true))
        assertEquals(Reason8368.SLIPPAGE_EXCEEDED, SellLanding8368.classify8368("FAILED_ON_CHAIN", "{\"InstructionError\":[3,{\"Custom\":6001}]}", true))
        assertEquals(Reason8368.SLIPPAGE_EXCEEDED, SellLanding8368.classify8368("FAILED_ON_CHAIN", "{\"InstructionError\":[2,{\"Custom\":6003}]}", true))
        assertEquals(Reason8368.INSUFFICIENT_FUNDS, SellLanding8368.classify8368("FAILED_ON_CHAIN", "{\"InstructionError\":[4,{\"Custom\":1}]}", true))
        assertEquals(Reason8368.INSUFFICIENT_FUNDS, SellLanding8368.classify8368("FAILED_ON_CHAIN", "\"InsufficientFundsForRent\"", true))
        assertEquals(Reason8368.PROGRAM_ERROR, SellLanding8368.classify8368("FAILED_ON_CHAIN", "{\"InstructionError\":[1,{\"Custom\":42}]}", true))
        assertEquals(Reason8368.EXPIRED_BLOCKHASH, SellLanding8368.classify8368("EXPIRED_PROVEN", "await_unseen_blockhash_invalid", true))
        assertEquals(Reason8368.SIMULATION_FAIL, SellLanding8368.classify8368("SUBMIT_REJECTED", "Transaction simulation failed: Error processing Instruction 2: invalid account data", false))
        assertEquals(Reason8368.SLIPPAGE_EXCEEDED, SellLanding8368.classify8368("SUBMIT_REJECTED", "Transaction simulation failed: Error processing Instruction 3: custom program error: 0x1771", false))
        assertEquals(Reason8368.EXPIRED_BLOCKHASH, SellLanding8368.classify8368("SUBMIT_REJECTED", "Transaction simulation failed: Blockhash not found", false))
        assertEquals(Reason8368.RPC_ERROR, SellLanding8368.classify8368("SUBMIT_REJECTED", "no_endpoint_reached: timeout", false))
        assertEquals(Reason8368.NOT_SENT, SellLanding8368.classify8368("SIGNED", "", false))
        assertEquals(Reason8368.PENDING, SellLanding8368.classify8368("OUTCOME_UNKNOWN", "", true))
    }

    @Test fun customProgramErrorsReadInBothForms() {
        assertEquals(6001L, SellLanding8368.customCode8368("{\"InstructionError\":[3,{\"Custom\":6001}]}"))
        assertEquals(6001L, SellLanding8368.customCode8368("custom program error: 0x1771"))
        assertEquals(-1L, SellLanding8368.customCode8368("await_unseen_blockhash_invalid"))
        assertNull(SellLanding8368.errorClass8368(""))
        assertNull(SellLanding8368.errorClass8368("fanout_8094"))
        assertEquals(Reason8368.SLIPPAGE_EXCEEDED, SellLanding8368.errorClass8368("custom program error: 0x1788"))
        assertEquals(Reason8368.INSUFFICIENT_FUNDS, SellLanding8368.errorClass8368("Transaction simulation failed: Error processing Instruction 4: insufficient funds"))
    }

    @Test fun anExpiryKeepsTheEarlierPreflightRejection() {
        val pre = "rpc_rejected_other_copy_in_flight: Transaction simulation failed: custom program error: 0x1771"
        val merged = SellLanding8368.mergeDetail8368(pre, "await_unseen_blockhash_invalid")
        assertTrue(merged.startsWith("await_unseen_blockhash_invalid"))
        assertEquals(Reason8368.SLIPPAGE_EXCEEDED, SellLanding8368.classify8368("EXPIRED_PROVEN", merged, true))
        // no preflight text: plain expiry; a chain err replaces the old text
        assertEquals("await_unseen_blockhash_invalid", SellLanding8368.mergeDetail8368("rpc", "await_unseen_blockhash_invalid"))
        assertEquals("{\"Custom\":1}", SellLanding8368.mergeDetail8368(pre, "{\"Custom\":1}"))
        assertTrue(src("network/SignedTxLedger8085.kt").contains("SellLanding8368.mergeDetail8368(e.detail, detail)"))
    }

    @Test fun perRouteLineNamesEveryReason() {
        val rows = listOf(
            SellLanding8368.Row8368("SELL", "JUPITER", "CONFIRMED", "", true),
            SellLanding8368.Row8368("SELL", "JUPITER", "EXPIRED_PROVEN", "await_unseen_blockhash_invalid", true),
            SellLanding8368.Row8368("SELL", "JUPITER", "FAILED_ON_CHAIN", "{\"InstructionError\":[3,{\"Custom\":6001}]}", true),
            SellLanding8368.Row8368("SELL", "PUMPPORTAL", "SIGNED", "", false),
            SellLanding8368.Row8368("BUY", "JUPITER", "CONFIRMED", "", true),
        )
        val line = SellLanding8368.line8368(SellLanding8368.table8368(rows))
        assertTrue(line, line.contains("JUPITER sent=3 landed=1 rate=33% EXPIRED_BLOCKHASH=1 SLIPPAGE_EXCEEDED=1"))
        assertTrue(line, line.contains("PUMPPORTAL sent=0 landed=0 rate=- NOT_SENT=1"))
        assertEquals("no signed sells yet", SellLanding8368.line8368(emptyMap()))
        assertTrue(src("engine/sell/SellRouteSpine8144.kt").contains("com.lifecyclebot.engine.SellLanding8368.statusLine8368()"))
        assertTrue(SellLanding8368.statusLine8368().startsWith("sellLanding8368["))
    }
}
