package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PositionStateLedger6454
import com.lifecyclebot.engine.truth.SellQtyBoundaryClamp6427
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8184 — no paper position may be opened while the runtime is LIVE.
 * Owner: "there's also paper trading happening while live is running. this is unacceptable."
 */
class Aate8184LiveNoPaperTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun resetPaperBook() {
        com.lifecyclebot.engine.truth.FillLotLedger6504.setTestMemoryMode6641(true)
        PaperAccountLedger6430.resetForTest(); PaperAccountLedger6430.initialize(10.0)
        CanonicalPositionAuthority6441.resetForTest(); CanonicalLotQuantity6464.resetForTest()
        EconomicEventSchema6464.resetForTest(); SellQtyBoundaryClamp6427.resetForTest()
        PositionStateLedger6454.resetForTest()
    }

    @Before fun setUp() {
        LiveNoPaper8184.resetForTest8184()
    }

    @After fun tearDown() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
        LiveNoPaper8184.resetForTest8184()
    }

    @Test fun gate_is_false_in_live_and_counts_each_refusal_by_source() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = true)
        assertFalse(LiveNoPaper8184.paperEntryAllowed8184("SRC_A"))
        assertFalse(LiveNoPaper8184.paperEntryAllowed8184("SRC_A"))
        assertFalse(LiveNoPaper8184.paperEntryAllowed8184("SRC_B"))
        assertEquals(2L, LiveNoPaper8184.refusalCount8184("SRC_A"))
        assertEquals(1L, LiveNoPaper8184.refusalCount8184("SRC_B"))
        assertEquals(3L, LiveNoPaper8184.totalRefusals8184())
        assertFalse(LiveNoPaper8184.paperManageAllowed8184("LLM_LAB"))
        val line = LiveNoPaper8184.statusLine8184()
        assertTrue(line, line.startsWith("mode=LIVE"))
        assertTrue(line, line.contains("SRC_A=2") && line.contains("SRC_B=1") && line.contains("frozenCanonicalPaper="))
    }

    @Test fun gate_is_true_in_paper_and_counts_nothing() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = true)
        assertTrue(LiveNoPaper8184.paperEntryAllowed8184("SRC_A"))
        assertTrue(LiveNoPaper8184.paperManageAllowed8184("LLM_LAB"))
        assertEquals(0L, LiveNoPaper8184.totalRefusals8184())
        assertTrue(LiveNoPaper8184.statusLine8184().startsWith("mode=PAPER"))
    }

    @Test fun switching_live_then_back_to_paper_restores_paper_entries() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = true)
        assertFalse(LiveNoPaper8184.paperEntryAllowed8184("SRC_SWITCH"))
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        assertTrue(LiveNoPaper8184.paperEntryAllowed8184("SRC_SWITCH"))
        assertEquals(1L, LiveNoPaper8184.refusalCount8184("SRC_SWITCH"))
    }

    @Test fun canonical_paper_open_is_refused_in_live_without_debit_and_works_in_paper() {
        resetPaperBook()
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = true)
        val n = System.nanoTime()
        val mint = "LIVENOPAPER8184_$n"; val pid = "PAPER:8184:$n"
        val raw = BigInteger.valueOf(1_000_000L)
        val cashBefore = PaperAccountLedger6430.cashSol()
        val refused = CanonicalPaperTransaction6486.open(pid, mint, "LNP", "TEST", "test8184", 0.10, 0.0, raw, 6)
        assertFalse(refused.applied)
        assertEquals("LIVE_NO_PAPER_8184", refused.reason)
        assertNull(CanonicalPositionAuthority6441.getPosition(pid))
        assertEquals(cashBefore, PaperAccountLedger6430.cashSol(), 0.0)
        assertEquals(1L, LiveNoPaper8184.refusalCount8184("CANONICAL_PAPER_OPEN:test8184"))

        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = true)
        assertTrue(CanonicalPaperTransaction6486.open(pid, mint, "LNP", "TEST", "test8184", 0.10, 0.0, raw, 6).applied)
        assertTrue(CanonicalPositionAuthority6441.getPosition(pid) != null)
    }

    // ── source checks: every paper ENTRY path calls the gate ──

    @Test fun canonical_paper_transaction_open_and_add_call_the_gate() {
        val tx = src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(tx.contains("LiveNoPaper8184.paperEntryAllowed8184(\"CANONICAL_PAPER_OPEN:\$source\")) return@withLock Result(false, positionId, \"LIVE_NO_PAPER_8184\")"))
        assertTrue(tx.contains("LiveNoPaper8184.paperEntryAllowed8184(\"CANONICAL_PAPER_ADD\")) return@withLock Result(false, positionId, \"LIVE_NO_PAPER_8184\")"))
        // The gate runs before the paper ledger debit.
        val open = tx.substring(tx.indexOf("fun open(positionId: String"))
        assertTrue(open.indexOf("CANONICAL_PAPER_OPEN:") < open.indexOf("PaperAccountLedger6430.onBuyAtomic6632("))
    }

    @Test fun every_cross_asset_paper_open_goes_through_the_gated_canonical_open() {
        listOf("ForexTrader", "PerpsTraderAI", "CryptoAltTrader", "MetalsTrader", "TokenizedStockTrader", "CommoditiesTrader").forEach {
            assertTrue(it, src("perps/$it.kt").contains("CanonicalPaperTransaction6486.open("))
        }
    }

    @Test fun meme_executor_paper_route_counts_through_the_gate() {
        val guard = src("engine/ExecutionRouteGuard.kt")
        assertTrue(guard.contains("LiveNoPaper8184.paperEntryAllowed8184(\"MEME_EXECUTOR_PAPER_BUY\")"))
        assertTrue(src("engine/Executor.kt").contains("ExecutionRouteGuard.requirePaperRoute("))
    }

    @Test fun llm_crypto_paper_buy_calls_the_gate_first() {
        val crypto = src("perps/CryptoAltTrader.kt")
        val fn = crypto.substring(crypto.indexOf("fun llmOpenPaperBuy("))
        assertTrue(fn.indexOf("LiveNoPaper8184.paperEntryAllowed8184(\"CRYPTO_LLM_PAPER_BUY\")") in 0 until fn.indexOf("isPaperMode.get()"))
    }

    @Test fun llm_lab_open_is_gated_and_existing_lab_positions_freeze_in_live() {
        val lab = src("engine/lab/LlmLabTrader.kt")
        val open = lab.substring(lab.indexOf("fun openPaper("))
        assertTrue(open.indexOf("LiveNoPaper8184.paperEntryAllowed8184(\"LLM_LAB_PAPER_OPEN\")") in 0 until open.indexOf("adjustPaperBalance(-sizeSol)"))
        assertTrue(lab.contains("LiveNoPaper8184.paperManageAllowed8184(\"LLM_LAB\")) return"))
    }

    @Test fun multichain_paper_open_is_gated_labels_kept_and_live_closes_paper_quietly() {
        val mc = src("perps/crypto/MultiChainMemeLane7962.kt")
        val open = mc.substring(mc.indexOf("private fun openPaper("))
        assertTrue(open.indexOf("LiveNoPaper8184.paperEntryAllowed8184(\"MULTICHAIN_PAPER_OPEN_7962\")") in 0 until open.indexOf("positions[p.id] = p"))
        // The counterfactual label is still recorded before the open decision.
        assertTrue(mc.contains("ForwardReturnLabeler7731.observe(ts, lane, admitted, refusal, now, scoreOf(c))"))
        // A paper position in LIVE closes unbooked (NaN), with no chain action.
        assertTrue(mc.contains("if (closePaperInLive8184(p, now)) continue"))
        assertTrue(mc.contains("close(p, \"LIVE_NO_PAPER_8184\", Double.NaN, now)"))
        val helper = mc.substring(mc.indexOf("private fun closePaperInLive8184("), mc.indexOf("private suspend fun runSell("))
        assertFalse(helper.contains("sell7962(") || helper.contains("buy7962("))
        assertTrue(helper.contains("if (p.live ||"))
    }

    @Test fun live_exit_scope_and_slots_stay_mode_scoped() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val activeExitMode7254 = if (RuntimeModeAuthority.isPaper()) \"paper\" else \"live\""))
        assertTrue(bot.contains("protectiveExitScope7809(activeExitMode7254)"))
    }

    @Test fun report_line_comes_from_a_helper_not_dump_text() {
        val phc = src("engine/PipelineHealthCollector.kt")
        assertTrue(phc.contains("Live no-paper (§8184):        \").append(LiveNoPaper8184.statusLine8184())"))
        val dump = phc.substring(phc.indexOf("fun dumpText(): String {"))
        val dumpBody = dump.substring(0, dump.indexOf("\n    fun ").takeIf { it > 0 } ?: dump.length)
        assertFalse(dumpBody.contains("LiveNoPaper8184"))
    }
}

/** V5.0.8184 — an LLM paper sell can never reach a LIVE position through a stale local mode copy. */
class Aate8184LlmSellAuthorityTest {
    @org.junit.Test fun llmSellChecksTheRuntimeAuthorityFirst() {
        val s = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val body = s.substringAfter("fun llmClosePaperSell(").substringBefore("botService.manualSell(")
        org.junit.Assert.assertTrue(body.contains("com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()"))
        org.junit.Assert.assertTrue(body.indexOf("RuntimeModeAuthority.isPaper()") < body.indexOf("isPaperMode.get()"))
    }
}
