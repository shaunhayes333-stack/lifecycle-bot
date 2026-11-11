package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.network.HeliusWebSocket
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

/** Behavioral regressions for missing flow, discarded charts and exhausted data waits in the 8674 log. */
class Aate8675EvidencePathTest {
    private val now = 1_800_000_000_000L
    @Before fun reset() {
        BuyerFlow8666.clearForTest(); TradeTape8100.resetForTest8100()
        CandleCoverage8284.resetForTest8284(); HeliusSwapCandles7819.resetForTest(); LocalCandleSynthesis7055.resetForTest()
        EdgeModel8537.resetForTest8537()
    }
    @After fun cleanup() = reset()

    private fun trade(signature: String, event: Long = now, wallet: String = "alice", source: String = "helius",
                      qty: Double = 100.0): Boolean = TradeTape8100.note8285(
        "evidenceMint", 0.01, 1.0, qty, true, source, pool = "actualPool", signature = signature,
        eventMs = event, recvMs = now, wallet = wallet)

    @Test fun overlappingProvidersCountOneBuyerAndOneTrade() {
        assertTrue(trade("same"))
        assertFalse(trade("same", source = "pumpswap"))
        val view = BuyerFlow8666.read("evidenceMint", now)!!
        assertEquals(1, view.newBuyers15s)
        assertEquals(1.0, view.txPerMin, 0.0)
        assertEquals(1.0, view.netSol60s, 0.0)
    }

    @Test fun walletCanBeEnrichedWithoutDuplicatingTheSignedTrade() {
        assertTrue(trade("enrich", wallet = ""))
        assertNull(BuyerFlow8666.read("evidenceMint", now))
        assertFalse(trade("enrich", wallet = "alice", source = "pumpswap"))
        assertEquals(1, BuyerFlow8666.read("evidenceMint", now)!!.newBuyers15s)
        assertFalse(trade("enrich", wallet = "alice", source = "validator"))
        assertEquals(1.0, BuyerFlow8666.read("evidenceMint", now)!!.txPerMin, 0.0)
    }

    @Test fun backfillAndUnknownAmountsCannotInventFreshDemand() {
        trade("old", event = now - 120_000)
        trade("unknownQty", qty = 0.0)
        assertNull(BuyerFlow8666.read("evidenceMint", now))
    }

    @Test fun outOfOrderEarlierBuyCorrectsNewBuyerTime() {
        trade("newer", event = now - 2_000)
        trade("earlier", event = now - 25_000)
        val view = BuyerFlow8666.read("evidenceMint", now)!!
        assertEquals(0, view.newBuyers15s)
        assertEquals(1, view.repeatBuyers60s)
        assertEquals(1, view.buyTx15s)
        assertEquals(1, view.buyTxPrev15s)
    }

    @Test fun executedMarkRetainsVenueAndDoesNotRefreshFromOldOrDuplicateTrades() {
        val marks = mutableListOf<TradeTape8100.Trade>()
        TradeTape8100.setOnExecutedTrade { _, t -> marks.add(t) }
        trade("live")
        trade("live", source = "pumpswap")
        trade("late", event = now - 15_000)
        trade("invalidQty", qty = 0.0)
        assertEquals(1, marks.size)
        assertEquals("actualPool", marks.single().pool)
        assertEquals("helius", marks.single().source)
    }

    @Test fun realMinuteBarsSurviveCandidateHistoryReplacementAndHolding() {
        val ts = TokenState(mint = "chartMint")
        val bars = HeliusSwapCandles7819.binPrints7819((1..6).flatMap { minute ->
            listOf(HeliusSwapCandles7819.SwapPrint7819(now - minute * 60_000L + 1000, 0.01, 1.0, true),
                HeliusSwapCandles7819.SwapPrint7819(now - minute * 60_000L + 2000, 0.012, 2.0, false))
        }, 100.0, now)
        CandleCoverage8284.merge8284(ts, bars, CandleCoverage8284.Quality8284.TRADE_AGGREGATED,
            "local_tape8100", "actualPool", now)
        synchronized(ts.history) { ts.history.clear() }
        ts.candleTimeframeMinutes = 240
        val chart = MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M1, now)
        assertEquals(6, chart.size)
        assertTrue(chart.all { it.intervalMs8521 == 60_000L && it.periodVolumeUsd8521 == 300.0 })
        assertEquals(1.0, chart.last().openUsd, 0.0)
        assertEquals(1.2, chart.last().priceUsd, 1e-9)
        assertTrue(MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M1, now + 180_000).isEmpty())
    }

    @Test fun accountSnapshotsMixedWithTradesDoNotBecomeExecutedOhlcv() {
        val ts = TokenState(mint = "mixedPrices")
        val at = now + 10_000L
        val minute = at / 60_000L * 60_000L
        LocalCandleSynthesis7055.note(ts, 1.0, 0.0, at)
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.1, 0.0, at)
        LocalCandleSynthesis7055.noteTrade7819(ts, 1.2, 0.0, minute + 60_001)
        val candle = ts.history.first()
        assertEquals(CandleCoverage8284.Quality8284.MARK_ONLY, CandleCoverage8284.qualityOf8284(candle))
        assertTrue(MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M1, minute + 60_002).isEmpty())
    }

    @Test fun futurePrintDoesNotEnterHistoricalCandles() {
        val bars = HeliusSwapCandles7819.binPrints7819(listOf(
            HeliusSwapCandles7819.SwapPrint7819(now - 1000, 0.01, 1.0, true),
            HeliusSwapCandles7819.SwapPrint7819(now + 1000, 0.02, 5.0, true)), 100.0, now)
        assertEquals(1, bars.size)
        assertEquals(100.0, bars.single().periodVolumeUsd8521, 0.0)
    }

    @Test fun realDataProgressReopensSpentWaitButRepeatedMissingDataDoesNot() {
        val mint = "dataProgress8675"
        val missing = listOf("shape:UNKNOWN", "bp:UNKNOWN")
        assertTrue(CandidateAdmission8136.deferBudgetFor8144(mint, missing, now))
        assertFalse(CandidateAdmission8136.deferBudgetFor8144(mint, missing, now + 601_000))
        assertFalse(CandidateAdmission8136.deferBudgetFor8144(mint, missing, now + 602_000))
        assertTrue(CandidateAdmission8136.deferBudgetFor8144(mint, listOf("bp:UNKNOWN"), now + 603_000))
        assertFalse(CandidateAdmission8136.deferUnanswered8204(mint, emptyList(), now + 604_000))
    }

    @Test fun buyerCountsAndDemandActuallyChangeLearnedFeatures() {
        val r = EdgeModel8537.Raw("MOONSHOT", "HELIUS", 10_000.0, 20_000.0, 3.0, 20.0,
            0.8, 5.0, 65.0, 5.0, Double.NaN, 60.0, 12.0, "IGNITION", 65.0, 2.0)
        val unknown = EdgeModel8537.vectorOf(r)
        val zero = EdgeModel8537.vectorOf(r.copy(newBuyers15s8666 = 0.0))
        val buyers = EdgeModel8537.vectorOf(r.copy(newBuyers15s8666 = 6.0))
        assertFalse(unknown.contentEquals(zero))
        assertFalse(zero.contentEquals(buyers))
        assertFalse(buyers.contentEquals(EdgeModel8537.vectorOf(r.copy(newBuyers15s8666 = 6.0, sustainedDemand = true))))
        assertFalse(unknown.contentEquals(EdgeModel8537.vectorOf(r.copy(marketReclaim = true))))
    }

    @Test fun observationLeaseAllowsAckAndLoadRecoveryPreservesSafetyCeiling() {
        val ws = HeliusWebSocket("", { _, _, _, _, _, _ -> }, { _, _, _, _ -> }, {})
        assertTrue(ws.observationLeaseActive(now, now + 59_999))
        assertFalse(ws.observationLeaseActive(now, now + 60_000))
        assertEquals(10, ws.capForLoad8666(8, 1_000_000, 0.64, true))
        assertEquals(8, ws.capForLoad8666(8, 1_000_000, 0.64, false))
        assertEquals(8, ws.capForLoad8666(16, 1_000_000, 0.72, true))
    }

    @Test fun aSignedBroadcastCannotTeachALossLockBeforeSettlement() {
        val sell = com.lifecyclebot.data.Trade("SELL", "live", 0.01, 1.0, now,
            mint = "mint", sig = "broadcast-signature", pnlSol = -0.005, entryCostSol = 0.01)
        assertFalse(TradeHistoryStore.terminalLiveCloseForRebuy(sell))
        assertFalse(TradeHistoryStore.terminalLiveCloseForRebuy(sell.copy(proofState = "LIVE_BROADCAST")))
        val settled = sell.copy(proofState = "LIVE_FINALIZED")
        assertTrue(TradeHistoryStore.terminalLiveCloseForRebuy(settled))
        assertFalse(TradeHistoryStore.terminalLiveCloseForRebuy(settled.copy(side = "PARTIAL_SELL")))
        assertFalse(TradeHistoryStore.terminalLiveCloseForRebuy(settled.copy(mode = "paper")))
    }

    @Test fun consensusCannotReDecideAnAdmittedMemeButCannotPromoteARefusal() {
        val admitted = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT,
            "SPECIFIC_EDGE_PROVEN_8136", true, "BREAKOUT", 8.0, emptyList(), costPct = 3.0)
        assertEquals(BrainConsensusGate.Verdict.ALLOW,
            BrainConsensusGate.executionVerdict(BrainConsensusGate.Verdict.HARD_BLOCK, admitted, "MOONSHOT"))
        assertEquals(BrainConsensusGate.Verdict.HARD_BLOCK,
            BrainConsensusGate.executionVerdict(BrainConsensusGate.Verdict.HARD_BLOCK,
                admitted.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE), "MOONSHOT"))
        assertEquals(BrainConsensusGate.Verdict.HARD_BLOCK,
            BrainConsensusGate.executionVerdict(BrainConsensusGate.Verdict.HARD_BLOCK, null, "MOONSHOT"))
    }

    @Test fun exactSetupLossAndNonEconomicAdmissionStillBind() {
        assertFalse(LaneAuthority8577.binding8577(LaneAuthority8577.PAPER_LOSING_8608))
        assertTrue(LaneAuthority8577.binding8577("SETUP_RECORD_LOSING_8136"))
        val c = CandidateAdmission8136.Candidate8136("costMint", "MOONSHOT", "BREAKOUT", live = true,
            nativeQualified8213 = true, expectedGrossPct = 2.0, costPct = 3.0)
        val d = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT,
            "NATIVE_QUALIFIED_ADMIT_8259", false, "BREAKOUT", -1.0, emptyList(), costPct = 3.0)
        assertFalse(LaneAuthority8577.apply8577(c, d).admitted)
    }
}
