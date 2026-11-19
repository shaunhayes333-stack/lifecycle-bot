package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
import com.lifecyclebot.engine.truth.TerminalSellIdempotency6464
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8872 — owner, 20 Nov: "paper is meant to turn off when live is running close out all trades and the shadow trader
 * runs while live does! paper is dragging down live trading running at the same time" / "the llms are fine". Paper is off
 * while LIVE runs (the LLM books excepted), the canonical paper account closes out at observed marks, the meme paper book
 * stands its positions down, and a failed paper close is retried instead of wedging on its own claim.
 */
class Aate8872PaperOffInLiveTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() {
        LiveNoPaper8184.resetForTest8184()
        LiveNoPaper8184.enforced8184 = true
    }

    @After fun tearDown() {
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
        LiveNoPaper8184.resetForTest8184()
        LiveNoPaper8184.enforced8184 = true
    }

    @Test fun theFreezeIsOnByDefaultAndTheLlmBooksKeepTrading() {
        assertTrue(src("engine/LiveNoPaper8184.kt").contains("@Volatile var enforced8184: Boolean = true"))
        RuntimeModeAuthority.publishRuntimeStart(paperMode = false, autoTrade = true)
        assertTrue(LiveNoPaper8184.closeOutInLive8872())
        for (llm in listOf("LLM_LAB_PAPER_OPEN", "CRYPTO_LLM_PAPER_BUY")) assertTrue(llm, LiveNoPaper8184.paperEntryAllowed8184(llm))
        assertTrue(LiveNoPaper8184.paperManageAllowed8184("LLM_LAB"))
        for (book in listOf("MEME_PAPER_BOOK_8544", "MEME_FAMILY_PAPER_8546", "MULTICHAIN_PAPER_OPEN_7962", "CANONICAL_PAPER_OPEN:x"))
            assertFalse(book, LiveNoPaper8184.paperEntryAllowed8184(book))
        assertFalse(LiveNoPaper8184.paperManageAllowed8184("MULTICHAIN_7962"))
        assertEquals("the LLM books are never counted as refused", 0L, LiveNoPaper8184.refusalCount8184("LLM_LAB_PAPER_OPEN"))
        LiveNoPaper8184.noteCloseOut8872(3, nowMs = System.currentTimeMillis())
        val line = LiveNoPaper8184.statusLine8184()
        assertTrue(line, line.contains("closeOut8872[passes=1 pending=3") && line.contains("llmBooks8872=LLM_LAB_PAPER_OPEN/LLM_LAB/CRYPTO_LLM_PAPER_BUY"))
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        assertFalse("PAPER mode is untouched", LiveNoPaper8184.closeOutInLive8872())
        assertTrue(LiveNoPaper8184.paperEntryAllowed8184("MEME_PAPER_BOOK_8544"))
        LiveNoPaper8184.enforced8184 = false
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        assertFalse("switched off it is the 8336 behaviour", LiveNoPaper8184.closeOutInLive8872())
    }

    @Test fun theMemePaperBookOpensNothingAndStandsDownInLive() {
        val t = src("engine/truth/MemePaperTrader8544.kt")
        val c = t.substringAfter("fun consider8544(").substringBefore("@Synchronized private fun reserve(")
        val gate = c.indexOf("LiveNoPaper8184.paperEntryAllowed8184(SOURCE_8872)) return")
        assertTrue(gate > 0 && gate < c.indexOf("restore8544()"))
        val f = t.substringAfter("fun openFamily8546(")
        assertTrue(f.indexOf("LiveNoPaper8184.paperEntryAllowed8184(FAMILY_SOURCE_8872)) return") in 0 until f.indexOf("restore8544()"))
        val k = t.substringAfter("fun tick8544(").substringBefore("fun onPrint8756(")
        val down = k.indexOf("if (com.lifecyclebot.engine.LiveNoPaper8184.closeOutInLive8872()) { standDown8872(nowMs); return }")
        assertTrue("stands down before any mark is asked for", down > 0 && down < k.indexOf("wantMark8623(p.mint, nowMs)"))
        val s = t.substringAfter("private fun standDown8872(").substringBefore("/** The position never had a price")
        assertTrue("the stake returns", s.contains("if (open.remove(p.mint, p)) balanceSol += p.sizeSol"))
        assertFalse("nothing is booked", s.contains("fill8544(") || s.contains("laneStats") || s.contains("realisedSol"))
        assertTrue(s.contains("wantedAt8623.remove(p.mint)"))
    }

    @Test fun liveClosesOutTheCanonicalPaperAccountThroughTheDurableObservedMarkClose() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("RuntimeModeAuthority.publishConfig(loopCfg.paperMode, loopCfg.autoTrade)\n            ensureLivePaperCloseOut8872()"))
        val e = bot.substringAfter("private fun ensureLivePaperCloseOut8872(").substringBefore("private fun stopMarketsForLifecycle8521()")
        assertTrue(e.contains("if (!status.running || stopInProgress || !LiveNoPaper8184.closeOutInLive8872()) return"))
        assertTrue(e.contains("closePaperStopIntent8521(requestCurrent = first, source = if (first) \"LIVE_NO_PAPER_8872\" else \"LIVE_NO_PAPER_8872_RETRY\")"))
        assertTrue(e.contains("PaperStopLifecycle8521.reconcile(applicationContext)"))
        assertTrue(e.contains("waitMs = (waitMs * 2L).coerceAtMost(LIVE_PAPER_MAX_RETRY_MS_8872)"))
        assertTrue(bot.contains("private const val LIVE_PAPER_FIRST_RETRY_MS_8872 = 30_000L"))
        assertTrue(bot.contains("private const val LIVE_PAPER_MAX_RETRY_MS_8872 = 600_000L"))
        assertFalse("never touches a live position", e.contains("closeLivePositionsOnStop8521") || e.contains("liveSell("))
    }

    private fun event(id: String) = CanonicalEconomicEvent6635.Event(
        economicEventId = id, positionId = "P$id", mint = "M8872", canonicalMint = "M8872", symbol = "T", mode = "paper",
        lane = "SHITCOIN", side = CanonicalEconomicEvent6635.Side.SELL, timestampMs = System.currentTimeMillis(),
        qtyRaw = BigInteger.ONE, decimals = 6, executionPriceUsd = 0.0, executionPriceSol = 0.0, notionalSol = 0.1, feeSol = 0.0,
        cashDeltaSol = 0.1, positionQtyDeltaRaw = BigInteger.ONE.negate(), realizedPnlDeltaSol = 0.0, terminalFillIndex = 1,
    )

    @Test fun anAbandonedCloseWithNothingCommittedReopensAndAHalfCommitDoesNot() {
        CanonicalEconomicEvent6635.resetForTest()
        assertTrue(CanonicalEconomicEvent6635.openEvent(event("E8872")))
        assertFalse("an open event is still a duplicate", CanonicalEconomicEvent6635.openEvent(event("E8872")))
        assertTrue(CanonicalEconomicEvent6635.abandon8585("E8872", "POSITION_MUTATION_REJECTED"))
        assertTrue("the retry of the same close opens it again", CanonicalEconomicEvent6635.openEvent(event("E8872")))
        assertTrue(CanonicalEconomicEvent6635.openEvent(event("H8872")))
        CanonicalEconomicEvent6635.markCommitted("H8872", CanonicalEconomicEvent6635.Store.POSITION, "test")
        assertFalse(CanonicalEconomicEvent6635.abandon8585("H8872", "LEDGER_COMMIT_FAILED"))
        assertFalse("a half-commit is never reopened", CanonicalEconomicEvent6635.openEvent(event("H8872")))
        CanonicalEconomicEvent6635.resetForTest()
    }

    @Test fun aReleasedIdempotencyKeyLetsTheNextAttemptProceed() {
        TerminalSellIdempotency6464.resetForTest()
        val key = TerminalSellIdempotency6464.makeKey(sellExecutionId = "k8872", fillId = "k8872", signature = "k8872")
        assertEquals(TerminalSellIdempotency6464.Consume.PROCEED, TerminalSellIdempotency6464.beginTerminal(key, "P8872", "t"))
        assertEquals(TerminalSellIdempotency6464.Consume.DUPLICATE_IGNORED, TerminalSellIdempotency6464.beginTerminal(key, "P8872", "t"))
        TerminalSellIdempotency6464.releaseFailed7835(key, "P8872")
        assertEquals(TerminalSellIdempotency6464.Consume.PROCEED, TerminalSellIdempotency6464.beginTerminal(key, "P8872", "t"))
        TerminalSellIdempotency6464.resetForTest()
    }

    @Test fun theBridgeReleasesOnlyWhereNothingMovedAndNamesItsReason() {
        val b = src("engine/truth/CanonicalPaperTerminalBridge6469.kt")
        val claim = b.substringAfter("private fun claimBeforeSideEffects(").substringBefore("private val claimsReleased8872")
        assertTrue(claim.indexOf("TerminalSellIdempotency6464.releaseFailed7835(idKey, positionId)") in 0 until claim.indexOf("return Claim(idKey, granted = false, reason = claim.name)"))
        val fn = b.substringAfter("fun finalizeSell(").substringBefore("fun emitCanonicalFanout(")
        assertEquals(3, Regex("releaseUnmovedClaim8872\\(claim\\.idKey, positionId, generation, terminal\\)").findAll(fn).count())
        for (unmoved in listOf("return Result(false, true, false, \"CANONICAL_EVENT_OPEN_REJECTED\")",
                "return Result(false, true, false, \"LEDGER_BASIS_REJECTED\")", "reason = \"POSITION_MUTATION_REJECTED\")")) {
            val at = fn.indexOf(unmoved)
            assertTrue(unmoved, at > 0 && fn.substring((at - 260).coerceAtLeast(0), at).contains("releaseUnmovedClaim8872("))
        }
        for (moved in listOf("reason = \"SELL_QTY_COMMIT_FAILED_6498\")", "\"LEDGER_COMMIT_FAILED\")", "\"FILL_LOT_COMMIT_FAILED\")")) {
            val at = fn.indexOf(moved)
            val stmt = fn.substring(fn.lastIndexOf("if (", at), at)
            assertFalse("after the position moved the claim is kept: $moved", stmt.contains("releaseUnmovedClaim8872"))
        }
        assertTrue("the unapplied fee is still voided", fn.contains("if (!feePositionApplied8391) com.lifecyclebot.engine.PerformanceFees8391.abortUnappliedPaper(positionId)"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("paperBridgeWhy8872 = close6474.reason"))
        assertTrue(ex.contains("reason=\$reason why=\$paperBridgeWhy8872 action=no_successful_sell_journal"))
        assertTrue(ex.contains("PAPER_CLOSE_REFUSED_8872_\${paperBridgeWhy8872.take(48).ifBlank { \"UNKNOWN\" }}"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 61. Paper is off while LIVE runs, and a failed paper close is retried (8872)"))
    }
}
