package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MemePaperTrader8544
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8544 — owner: "build the separate paper book beside live, properly". The meme lanes paper-trade while the runtime
 * is LIVE in a book of their own: entries decided the PAPER way on the live candidate, filled at a fresh corroborated mark,
 * exits on the lane's live levers after the sell latency, net of the priced round trip, persisted across restarts, and never
 * touching a TokenState, a journal, the canonical ledger, the wallet or a live slot.
 */
class Aate8544MemePaperTraderTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val trader get() = src("engine/truth/MemePaperTrader8544.kt")

    @Test fun onlySolanaMemeLanesAreTraded() {
        for (l in listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "MANIPULATED", "PROJECT_SNIPER", "DIP_HUNTER", "QUALITY", "BLUECHIP", "TREASURY", "CASHGEN", "CORE", "CYCLIC"))
            assertTrue(l, MemePaperTrader8544.memeLane8544(l))
        for (l in listOf("CRYPTO_ALT", "CRYPTO_TREASURY", "CHASEMISS_MOONSHOT", "PERPS_X", ""))
            assertFalse(l, MemePaperTrader8544.memeLane8544(l))
    }

    @Test fun theBookHasRoomRules() {
        assertNull(MemePaperTrader8544.roomFor8544(0, 0, false, Long.MAX_VALUE / 4, 10.0))
        assertEquals("MINT_OPEN", MemePaperTrader8544.roomFor8544(0, 0, true, Long.MAX_VALUE / 4, 10.0))
        assertEquals("BOOK_FULL", MemePaperTrader8544.roomFor8544(MemePaperTrader8544.MAX_OPEN_8544, 0, false, Long.MAX_VALUE / 4, 10.0))
        assertEquals("LANE_FULL", MemePaperTrader8544.roomFor8544(1, MemePaperTrader8544.MAX_OPEN_PER_LANE_8544, false, Long.MAX_VALUE / 4, 10.0))
        assertEquals("REOPEN_GAP", MemePaperTrader8544.roomFor8544(1, 1, false, MemePaperTrader8544.REOPEN_GAP_MS_8544 - 1, 10.0))
        assertEquals("NO_PAPER_BALANCE", MemePaperTrader8544.roomFor8544(1, 1, false, Long.MAX_VALUE / 4, MemePaperTrader8544.STAKE_SOL_8544 / 2))
        assertEquals(10.0, MemePaperTrader8544.START_BALANCE_SOL_8544, 0.0)
    }

    // 5 / 15 — exits ride the lane's ladder and the money is net of the round trip
    @Test fun ladderAndNetFollowTheLane() {
        val runner = RunnerGrab7967.TIERS_7967
        assertNull(MemePaperTrader8544.rung8544(runner, 0, 99.0))
        // V5.0.8644 — paper uses the same 25/18/70%-of-remainder ladder as live.
        assertEquals(1 to 0.25, MemePaperTrader8544.rung8544(runner, 0, 100.0))
        val gap = MemePaperTrader8544.rung8544(runner, 0, 1_100.0)!!
        assertEquals(3, gap.first)
        assertEquals(SpikeCapture7943.crossedTierFraction8079(runner, 0, 3), gap.second, 1e-12)
        assertNull("all rungs taken", MemePaperTrader8544.rung8544(runner, 3, 5_000.0))
        assertEquals(1 to 0.60, MemePaperTrader8544.rung8544(SpikeCapture7943.TIERS, 0, 40.0))
        assertEquals(50.0, MemePaperTrader8544.grossPct8544(1.0, 1.5), 1e-9)
        assertTrue(MemePaperTrader8544.grossPct8544(0.0, 1.5).isNaN())
        // half sold at +100 (banked 50), the rest out at +20 (0.5 x 20 = 10), less a 4 % round trip
        assertEquals(56.0, MemePaperTrader8544.netPct8544(50.0, 0.5, 20.0, 4.0), 1e-9)
        assertEquals(-19.0, MemePaperTrader8544.netPct8544(0.0, 1.0, -15.0, 4.0), 1e-9)
    }

    @Test fun entriesAreDecidedThePaperWayAndFilledOnACorroboratedFreshMark() {
        val t = trader
        val c = t.substringAfter("fun consider8544(").substringBefore("/** Pure: the gross %")
        assertTrue(c.contains("if (!candidate.live || !memeLane8544(candidate.lane)) return"))
        assertTrue(c.contains("CandidateAdmission8136.decide8136(candidate.copy(live = false, liveEdge8114 = null)) { false }"))
        assertTrue(c.contains("if (paper.terminal != CandidateAdmission8136.Terminal8136.ADMIT)"))
        assertTrue(c.contains("ShadowMark8360.observedTimed8372(ts, ts.mint, nowMs)"))
        assertTrue(c.contains("nowMs - mark.observedAtMs !in 0L..ENTRY_MARK_MAX_AGE_MS_8544"))
        assertTrue(c.contains("PaperFillTruth8539.refusal(ts.mint, mark.priceUsd, nowMs) != null"))
        assertTrue("unknown cost opens nothing", c.contains("if (!candidate.costPct.isFinite() || candidate.costPct < 0.0)"))
        val a = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(a.contains("if (mode == \"LIVE\" && !FdgGate8365.shadowOnly8393()) try { MemePaperTrader8544.consider8544(ts, candidate, nowMs) } catch (_: Throwable) {}"))
    }

    @Test fun exitsUseTheLiveLeversAndTheSellLatency() {
        val k = trader.substringAfter("fun tick8544(").substringBefore("private fun trigger8544(")
        assertTrue(k.contains("ShadowAlign8366.liveStopLevel8366(p.runner, stopPct, p.peak, p.costPct)"))
        // superseded: `stopPct8392(p.lane, …)` — V5.0.8546: a family position rides its exit lane's stop (p.exitLane = lane for lane picks)
        assertTrue(k.contains("FieldManual7715.stopPct8392(p.exitLane, p.costPct)"))
        assertTrue(k.contains("if (p.runner) com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967 else com.lifecyclebot.engine.SpikeCapture7943.TIERS"))
        assertTrue(k.contains("if (obs != null && obs.observedAtMs >= p.exitAtMs + latency) fill8544(p, g, nowMs)"))
        assertTrue(k.contains("nowMs - p.entryAtMs >= p.clockMs -> trigger8544(p, \"CLOCK\", 1.0, 0, nowMs)"))
        assertTrue(src("engine/BotService.kt").contains("com.lifecyclebot.engine.truth.MemePaperTrader8544.tick8544(tokenStatesCopy, nowShadow7307)"))
    }

    // 14 / integrity — the book is separate from every live and paper accounting surface
    @Test fun theBookNeverTouchesLiveOrJournalState() {
        val t = trader.substringAfter("object MemePaperTrader8544 {")
        for (forbidden in listOf("ts.position", "TradeHistoryStore", "CanonicalPaperTransaction6486", "CanonicalPositionAuthority6441",
                "Executor.", "WalletManager", "paperBuy(", "liveBuy"))
            assertFalse(forbidden, t.contains(forbidden))
        assertTrue("open positions and closes persist", t.contains("LearningPersistence.save(KEY_8544, raw)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("com.lifecyclebot.engine.truth.MemePaperTrader8544.statusLine8544()"))
        assertTrue("the 8543 shadow view is gone", !File("src/main/kotlin/com/lifecyclebot/engine/truth/MemePaperBook8543.kt").exists())
    }
}
