package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CostSides8169
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.TradeCostMeasurement8090
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8169 — execution economics: a cost ledger whose sides add up and never go negative, a size floor where fixed
 * costs alone would make every trade negative-EV, a drawdown peak an unexplained equity jump cannot raise, and ordinary
 * buys that pay at least the market's medium priority (with the Sender tip at the endpoint's documented minimum).
 */
class Aate8169CostsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val l = CostLanding8169

    // ── (1) the cost ledger ──

    private fun sale(raw: Long, net: Double, markUsd: Double = 2.0, solUsd: Double = 200.0) =
        TradeCostMeasurement8090.Sale(BigInteger.valueOf(raw), net, markUsd, solUsd)

    @Test fun sidesAreBothOverSolSpentAndAddUpToTheRoundTrip() {
        // 100 tokens at $2 / $200 SOL = 1.0 SOL market; paid 1.03 incl. a 0.0001 network fee; sold back for 0.98 at the same mark.
        val s = CostSides8169.sides8169(1.03, 0.0001, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.98)))
        assertEquals(CostSides8169.Verdict8169.MEASURED, s.verdict)
        assertEquals(0.03 / 1.03 * 100.0, s.buyPct, 1e-9)
        assertEquals(0.02 / 1.03 * 100.0, s.sellPct, 1e-9)
        assertEquals(s.buyPct + s.sellPct, s.costPct, 1e-12)
        assertEquals(0.0001 / 1.03 * 100.0, s.entryFeePct, 1e-9)
        // The old measurement's total agrees; only the buy denominator differed (market value vs SOL spent).
        val old = TradeCostMeasurement8090.measure(1.03, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.98)))!!
        assertEquals(old.costPct, s.costPct, 1e-9)
        assertEquals(3.0, old.buyPct, 1e-9)
    }

    @Test fun aNegativeSideIsMarkNoiseNotACostAndIsNeverLearned() {
        // 8168 SHITCOIN: buySide -9.48% — the mark 3 s before the fill sat above the price actually paid.
        val neg = CostSides8169.sides8169(0.905, 0.0, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.9)))
        assertEquals(CostSides8169.Verdict8169.MARK_INCONSISTENT, neg.verdict)
        // A tick of noise (within 1%) is floored at zero, not learned as a subsidy.
        val tick = CostSides8169.sides8169(0.995, 0.0, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.97)))
        assertEquals(CostSides8169.Verdict8169.MEASURED, tick.verdict)
        assertEquals(0.0, tick.buyPct, 0.0)
        assertTrue(tick.sellPct > 0.0)
        assertEquals(tick.buyPct + tick.sellPct, tick.costPct, 1e-12)
        // The network fee was paid on the buy: the buy side is never below it.
        val fee = CostSides8169.sides8169(1.0, 0.002, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.98)))
        assertEquals(0.2, fee.buyPct, 1e-9)
    }

    @Test fun aBuySideBeyondAnySlippageBoundIsTheMarketMovingNotFriction() {
        // 8168 MOONSHOT buySide 23.69%: no live buy can pay 23% over the market inside a <=10% slippage bound.
        val chase = CostSides8169.sides8169(1.3, 0.0, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(100, 0.98)))
        assertEquals(CostSides8169.Verdict8169.MARK_INCONSISTENT, chase.verdict)
        assertEquals(CostSides8169.Verdict8169.INVALID,
            CostSides8169.sides8169(1.0, 0.0, BigInteger.valueOf(100), 0, 2.0, 200.0, listOf(sale(50, 0.49))).verdict)
    }

    @Test fun persistedRowsWithImpossibleComponentsAreNotLearned() {
        assertTrue(CostSides8169.learnableRow8169(doubleArrayOf(6.0, 0.029, 3.0), 60.0))                 // legacy, sane
        assertFalse(CostSides8169.learnableRow8169(doubleArrayOf(-13.84, 0.03, -9.48), 60.0))            // 8168 SHITCOIN
        assertFalse(CostSides8169.learnableRow8169(doubleArrayOf(9.39, 0.029, 23.69), 60.0))             // 8168 MOONSHOT
        assertTrue(CostSides8169.learnableRow8169(doubleArrayOf(6.0, 0.029, 3.0, 3.0, 0.3), 60.0))       // 8169: adds up
        assertFalse(CostSides8169.learnableRow8169(doubleArrayOf(6.0, 0.029, 3.0, 2.0, 0.3), 60.0))      // does not add up
        assertTrue(CostSides8169.learnableRow8169(doubleArrayOf(6.0, 0.029, 3.0, Double.NaN, Double.NaN), 60.0))
        val ledger = src("engine/truth/CostLedger7962.kt")
        assertTrue(ledger.contains("CostSides8169.sides8169(spent, buy.entryFeesSol * share"))
        assertTrue(ledger.contains("CostSides8169.learnableRow8169(it, MAX_SANE_COST_PCT)"))
        assertTrue(ledger.contains("markInconsistent8169="))
    }

    @Test fun theModelCostIsReportedPerSideWithItsContext() {
        val ne = src("engine/truth/NetEdge8030.kt")
        assertTrue(ne.contains("lastCost8169=[\$lastBreakdown8169]"))
        assertTrue(ne.contains("lastEntryCost8169=[\$lastEntryBreakdown8169]"))
        assertTrue(ne.contains("buySide=\${p(buySide)}% sellSide=\${p(sellSide)}%"))
    }

    // ── (2) size floor and drawdown basis ──

    @Test fun aSizeWhoseFixedLegsAloneExceedFivePercentIsRefusedNotTradedThinner() {
        // 8168: fixedRoundTrip 0.000531 SOL. At the route minimum (0.0287) that is 1.85%; at 0.0100 it would be 5.3%.
        assertFalse(l.fixedCostDominates8169(0.0287, 0.000531))
        assertTrue(l.fixedCostDominates8169(0.0100, 0.000531))
        assertFalse(l.fixedCostDominates8169(0.0100, Double.NaN))     // unknown fixed cost never refuses
        assertEquals(0.01062, l.fixedCostFloorSol8169(0.000531), 1e-9)
        val base = LiveRiskPolicy7807.Inputs(
            lane = "QUALITY", equitySol = 1.0, upstreamSol = 0.18, execMinSol = 0.025,
            planStopPct = 10.0, planFirstTargetPct = 30.0, planTargetPct = 40.0,
            solUsd = 200.0, liquidityUsd = 200_000.0, liveCloses = 0, liveMeanNetPct = 0.0,
            liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = 0.0, laneDailyLossSol = 0.0,
            governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
        )
        val open = LiveRiskPolicy7807.decide(base.copy(fixedRoundTripSol8169 = 0.000531))
        assertTrue(open.reason, open.open)
        // The same order when the fixed legs are 10x dearer than its own 5% budget: refused by name.
        val refused = LiveRiskPolicy7807.decide(base.copy(fixedRoundTripSol8169 = open.sizeSol * 0.06))
        assertFalse(refused.open)
        assertEquals(CostLanding8169.FIXED_COST_REFUSAL_8169, refused.reason)
        assertEquals(0.0, refused.sizeSol, 0.0)
    }

    @Test fun anUnexplainedEquityJumpCannotRaiseTheDrawdownPeak() {
        assertTrue(l.peakCandidate8169(0.4541, unexplainedPending = true).isNaN())
        assertEquals(0.30, l.peakCandidate8169(0.30, unexplainedPending = false), 0.0)
        val prior = com.lifecyclebot.engine.truth.CapitalDrawdown7948.Peak7948(0.3205, 1_000L)
        val kept = com.lifecyclebot.engine.truth.CapitalDrawdown7948.rollPeak7948(prior, l.peakCandidate8169(0.4541, true), 2_000L)
        assertEquals(0.3205, kept.peakSol, 0.0)
        val dd = src("engine/truth/CapitalDrawdown7948.kt")
        assertTrue(dd.contains("CostLanding8169.peakCandidate8169(confirmed7990, frozen8169)"))
        assertTrue(dd.contains("peakAgeMin8169="))
    }

    // ── (3) landing ──

    @Test fun anOrdinaryBuyNeverPaysLessThanTheMarketsMedium() {
        // NEYMAR 8168: 0.0295 SOL ordinary buy, cap 0.3% = 0.0000885 SOL. A medium of 0.0004 SOL used to be cut to the cap.
        val capped = CostTrim8151.ordinaryPumpSol8151(0.0004, 0.0008, 0.0295)
        assertEquals(0.0000885, capped, 1e-12)
        assertEquals(0.0004, l.atLeastMediumSol8169(capped, 0.0004, 0.0008), 1e-12)
        // Never above the urgent fee of the same moment, unchanged without a reading, and a cheap medium stays cheap.
        assertEquals(0.0008, l.atLeastMediumSol8169(capped, 0.0012, 0.0008), 1e-12)
        assertEquals(capped, l.atLeastMediumSol8169(capped, 0.0, 0.0008), 0.0)
        assertEquals(0.00003, l.atLeastMediumSol8169(CostTrim8151.ordinaryPumpSol8151(0.00003, 0.0002, 0.0295), 0.00003, 0.0002), 1e-12)
        // Aggregator CU price: the size cap (290k) no longer undercuts an 800k medium; the high reading still bounds it.
        assertEquals(800_000L, l.atLeastMediumMicro8169(290_000L, 800_000L, 1_500_000L))
        assertEquals(200_000L, l.atLeastMediumMicro8169(200_000L, 500_000L, 200_000L))
        assertEquals(25_000L, l.atLeastMediumMicro8169(25_000L, 0L, 0L))
        // The medium is priced at PumpPortal's own CU limit: 300k CU at 200k uL = 0.00006 SOL (not 0.00004 at 200k CU).
        assertEquals(0.00006, l.pumpSolFor8169(200_000L, 300_000L, 0.002), 1e-12)
        assertEquals(0.002, l.pumpSolFor8169(20_000_000L, 300_000L, 0.002), 1e-12)
        val pf = src("network/PriorityFee8009.kt")
        assertTrue(pf.contains("CostLanding8169.atLeastMediumSol8169(capped, mediumSol, legacy)"))
        assertTrue(pf.contains("CostLanding8169.atLeastMediumMicro8169(capped, mediumMicro8151, microLamports)"))
    }

    @Test fun theSenderTipMeetsTheDocumentedMinimumOfTheEndpointInUse() {
        assertEquals(5_000L, l.senderMinTipLamports8169("https://sender.helius-rpc.com/fast?swqos_only=true"))
        assertEquals(1_000_000L, l.senderMinTipLamports8169("https://sender.helius-rpc.com/fast"))
        assertTrue(src("network/HeliusSender.kt").contains("sender.helius-rpc.com/fast?swqos_only=true"))
        assertTrue(CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151 >= l.senderMinTipLamports8169("https://sender.helius-rpc.com/fast?swqos_only=true"))
        assertTrue(CostTrim8151.senderTipLamports8151(CostTrim8151.Urgency8151.ORDINARY, 200_000L) >= l.SENDER_SWQOS_MIN_TIP_LAMPORTS_8169)
    }

    private fun shortVec(o: ByteArrayOutputStream, v: Int) { var x = v; while (true) { val b = x and 0x7f; x = x ushr 7; if (x == 0) { o.write(b); return }; o.write(b or 0x80) } }
    private fun le(v: Long, n: Int) = ByteArray(n) { i -> ((v ushr (8 * i)) and 0xffL).toByte() }

    /** A v0 transaction with SetComputeUnitLimit(limit) and SetComputeUnitPrice(price). */
    private fun tx(limit: Long, price: Long, v0: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        shortVec(o, 1); o.write(ByteArray(64))
        if (v0) o.write(0x80)
        o.write(1); o.write(0); o.write(1)
        shortVec(o, 2); o.write(ByteArray(32) { 7 })
        o.write(io.github.novacrypto.base58.Base58.base58Decode("ComputeBudget111111111111111111111111111111"))
        o.write(ByteArray(32) { 9 })
        shortVec(o, 2)
        o.write(1); shortVec(o, 0); shortVec(o, 5); o.write(byteArrayOf(2)); o.write(le(limit, 4))
        o.write(1); shortVec(o, 0); shortVec(o, 9); o.write(byteArrayOf(3)); o.write(le(price, 8))
        if (v0) shortVec(o, 0)
        return o.toByteArray()
    }

    @Test fun whatABuiltBuyPaysIsReadFromItsOwnComputeBudget() {
        val cb = l.computeBudgetOf8169(tx(250_000L, 180_000L))
        assertNotNull(cb)
        assertEquals(250_000L, cb!!.cuLimit)
        assertEquals(180_000L, cb.cuPriceMicro)
        assertEquals(45_000L, l.priorityLamports8169(250_000L, 180_000L))
        assertEquals(250_000L, l.computeBudgetOf8169(tx(250_000L, 180_000L, v0 = false))!!.cuLimit)
        assertNull(l.computeBudgetOf8169(ByteArray(10)))
        LandingMeter8169.resetForTest8169()
        LandingMeter8169.noteBuiltPumpBuy8169(tx(300_000L, 100_000L), 0.00003, ordinary = true, mediumMicro = 150_000L)
        assertEquals(300_000L, LandingMeter8169.pumpCuLimit8169())
        val line = LandingMeter8169.statusLine8169(emptyList(), 5_000L, 50_000L, 150_000L)
        assertTrue(line, line.contains("belowMedium=1"))
        assertTrue(line, line.contains("meetsMin=true"))
        LandingMeter8169.resetForTest8169()
        assertEquals(200_000L, LandingMeter8169.pumpCuLimit8169())
        assertTrue(src("network/PumpFunDirectApi.kt").contains("LandingMeter8169.noteBuiltPumpBuy8169(bytes, effPriorityFee, ordinary8151"))
    }

    @Test fun aBuyThatExpiredUnseenRetriesUrgentOnceWithinTheWindow() {
        LandingMeter8169.resetForTest8169()
        assertFalse(LandingMeter8169.buyUnlandedRecently8169("MINT", 1_000L))
        LandingMeter8169.noteBuyUnlanded8169("MINT", 1_000L)
        assertTrue(LandingMeter8169.buyUnlandedRecently8169("MINT", 60_000L))
        assertTrue(LandingMeter8169.buyUnlandedRecently8169("MINT", 61_000L))
        assertFalse(LandingMeter8169.buyUnlandedRecently8169("MINT", 1_000L + 3 * 60_000L + 1))
        assertTrue(LandingMeter8169.statusLine8169(emptyList(), 5_000L, 50_000L, 0L).contains("escalatedAfterUnlanded=1"))
        LandingMeter8169.resetForTest8169()
        assertTrue(src("engine/Executor.kt").contains("!RunnerGrab7967.isGrabbed7980(mint) && !LandingMeter8169.buyUnlandedRecently8169(mint)"))
        assertTrue(src("network/SignedTxLedger8085.kt").contains("LandingMeter8169.noteBuyUnlanded8169(e.mint, nowMs)"))
    }

    // ── (4) landing per route ──

    @Test fun landingIsCountedPerSideAndRoute() {
        val rows = listOf(
            CostLanding8169.LandRow8169("BUY", "PUMPPORTAL", "CONFIRMED", true, "helius_sender"),
            CostLanding8169.LandRow8169("BUY", "PUMPPORTAL", "EXPIRED_PROVEN", true, "helius_sender"),
            CostLanding8169.LandRow8169("BUY", "PUMPPORTAL", "SIGNED", false, ""),
            CostLanding8169.LandRow8169("SELL", "JUPITER", "FAILED_ON_CHAIN", true, "rpc"),
            CostLanding8169.LandRow8169("SELL", "JUPITER", "SUBMIT_REJECTED", true, ""),
        )
        val t = l.landingByRoute8169(rows)
        val pump = t.getValue("BUY|PUMPPORTAL")
        assertEquals(2, pump.sent); assertEquals(1, pump.landed); assertEquals(1, pump.expired); assertEquals(1, pump.pending)
        val jup = t.getValue("SELL|JUPITER")
        assertEquals(1, jup.sent); assertEquals(1, jup.failed); assertEquals(1, jup.rejected)
        val line = l.landingLine8169(t)
        assertTrue(line, line.contains("BUY|PUMPPORTAL sent=2 landed=1 expired=1 failed=0 pending=1 rejected=0 landRate=50%"))
        assertTrue(src("engine/CostTrim8151.kt").contains("SignedTxLedger8085.landRows8169()"))
    }
}
