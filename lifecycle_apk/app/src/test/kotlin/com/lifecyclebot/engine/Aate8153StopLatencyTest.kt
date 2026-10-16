package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.BalanceProofPoller
import com.lifecyclebot.engine.sell.BalanceProofWaitState
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ChainStatus8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.SigDecision8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Timer8144
import com.lifecyclebot.engine.sell.SellRouteVenueProbe8144
import com.lifecyclebot.engine.sell.SellRouteVenueProbe8144.Probe8144
import com.lifecyclebot.engine.sell.StopLatencyCore8153
import com.lifecyclebot.engine.sell.StopLatencyCore8153.BalanceRoute8153
import com.lifecyclebot.engine.sell.StopLatencyCore8153.HeldProof8153
import com.lifecyclebot.engine.sell.StopLatencyCore8153.PrewarmTarget8153
import com.lifecyclebot.engine.sell.StopLatencyCore8153.StageSample8153
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.8153 — P5 catastrophic stop-to-broadcast delays: the emergency hot path answers a known holding without any
 * read or multi-provider corroboration, races provider reads in parallel under one bound, uses the prewarmed /
 * last-confirmed venue, keeps emergency reads off the prewarm lane, re-sizes every retry from a fresh wallet read and
 * never signs a second sell while one is unverified (an expired one is proven at once instead of waited out).
 *
 * The latency figures are a MODEL (StopLatencyCore8153 stage model with injected provider latencies), not device
 * measurements.
 */
class Aate8153StopLatencyTest {

    private val mint = "STOPLAT8153MINT1111111111111111111111111"

    private fun bi(v: Long) = BigInteger.valueOf(v)
    private fun proof(raw: Long, atMs: Long = System.currentTimeMillis(), decimals: Int = 6, source: String = "T") =
        HeldProof8153(bi(raw), decimals, source, atMs)
    private fun nowNs() = System.nanoTime()
    private fun msSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000L
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Before fun setUp() {
        StopLatencyCore8153.resetForTest8153()
        SellRouteSpine8144.resetForTest8144()
        SellRouteVenueProbe8144.resetForTest8144()
    }

    @After fun tearDown() {
        StopLatencyCore8153.resetForTest8153()
        SellRouteSpine8144.resetForTest8144()
        SellRouteVenueProbe8144.resetForTest8144()
    }

    // ── 1. a known holding never waits on corroboration ──

    @Test fun hotPathNeverBlocksOnCorroborationWhenTheHoldingIsKnown() {
        val calls = AtomicInteger()
        val slowCorroboration: () -> HeldProof8153? = { calls.incrementAndGet(); Thread.sleep(3_000); proof(500) }
        val known = proof(1_000)
        val t0 = nowNs()
        val a = StopLatencyCore8153.holdingForExit8153(true, { known }, listOf(slowCorroboration, slowCorroboration))
        assertTrue("answered without waiting: ${msSince(t0)} ms", msSince(t0) < 500L)
        assertEquals(BalanceRoute8153.PROVEN_HOLDING, a.route)
        assertEquals(known, a.proof)
        assertEquals(0, a.readsIssued)
        assertEquals(0, calls.get())
        // Corroboration is for proving ABSENCE only.
        assertFalse(StopLatencyCore8153.corroborationNeeded8153(known))
        assertTrue(StopLatencyCore8153.corroborationNeeded8153(null))
        assertTrue(StopLatencyCore8153.corroborationNeeded8153(proof(1)))   // dust is not a holding
        assertFalse(StopLatencyCore8153.positive8153(proof(1)))
        assertFalse(StopLatencyCore8153.positive8153(proof(5, decimals = 19)))
        assertTrue(StopLatencyCore8153.positive8153(known))
        // A non-emergency exit keeps its own read: nothing is issued here.
        val n = StopLatencyCore8153.holdingForExit8153(false, { known }, listOf(slowCorroboration))
        assertEquals(BalanceRoute8153.LEGACY_READ, n.route)
        assertNull(n.proof)
        assertEquals(0, calls.get())
        assertEquals(BalanceRoute8153.PARALLEL_READ, StopLatencyCore8153.balanceRoute8153(true, false))
    }

    // ── 2. parallel, bounded provider failover ──

    @Test fun parallelFailoverTakesTheAnsweringProviderWithinTheBound() {
        val slow: () -> HeldProof8153? = { Thread.sleep(5_000); proof(900) }
        val fast: () -> HeldProof8153? = { Thread.sleep(50); proof(1_000) }
        val a = StopLatencyCore8153.holdingForExit8153(true, { null }, listOf(slow, fast), budgetMs = 2_500L, graceMs = 300L)
        assertEquals(BalanceRoute8153.PARALLEL_READ, a.route)
        assertEquals(bi(1_000), a.proof!!.raw)
        assertTrue("bounded by first answer + grace, not the slow provider: ${a.elapsedMs} ms", a.elapsedMs < 1_500L)
        assertEquals(2, a.readsIssued)
    }

    @Test fun noAnswerReturnsAtTheBudgetAndProvesNothing() {
        val hang: () -> HeldProof8153? = { Thread.sleep(5_000); proof(900) }
        val r = StopLatencyCore8153.parallelHeld8153(listOf(hang, hang), budgetMs = 400L)
        assertNull(r.held)
        assertEquals(2, r.unanswered)
        assertTrue("budget-bounded: ${r.elapsedMs} ms", r.elapsedMs < 1_500L)
        // A failed / absent read is null, never a zero holding.
        val failing: () -> HeldProof8153? = { throw IllegalStateException("rpc down") }
        val f = StopLatencyCore8153.parallelHeld8153(listOf(failing, { null }), budgetMs = 400L)
        assertNull(f.held)
        assertEquals(0, f.answered)
    }

    @Test fun readsRunInParallelNotSerially() {
        val r600: () -> HeldProof8153? = { Thread.sleep(600); proof(1_000) }
        val r = StopLatencyCore8153.parallelHeld8153(listOf(r600, r600), budgetMs = 2_500L, graceMs = 300L)
        assertNotNull(r.held)
        assertTrue("two 600 ms reads overlap (serial would be >= 1200 ms): ${r.elapsedMs} ms", r.elapsedMs < 1_150L)
    }

    @Test fun theSmallestPositiveAnswerSizesTheSale() {
        val a: () -> HeldProof8153? = { Thread.sleep(60); proof(1_000) }
        val b: () -> HeldProof8153? = { Thread.sleep(120); proof(900) }
        val r = StopLatencyCore8153.parallelHeld8153(listOf(a, b), budgetMs = 2_500L, graceMs = 1_000L)
        assertEquals("a lagging provider never sizes above the wallet", bi(900), r.held!!.raw)
        assertNull("decimals disagreement proves nothing", StopLatencyCore8153.minHeld8153(listOf(proof(5, decimals = 6), proof(7, decimals = 9))))
        assertNull(StopLatencyCore8153.minHeld8153(listOf(null, proof(1))))
    }

    // ── 3. fault-injection replay of the stage timer ──

    private fun replay(s: StageSample8153, balanceMs: Long, venueMs: Long): Timer8144 {
        val t = Timer8144(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", 0L, true)
        var clock = 0L
        clock += s.leaseMs; t.mark(Stage8144.CLOSE_LEASE, clock)
        clock += balanceMs; t.mark(Stage8144.TOKEN_BALANCE, clock)
        clock += venueMs; t.mark(Stage8144.VENUE_RESOLVE, clock)
        clock += s.quoteMs + s.buildMs; t.mark(Stage8144.QUOTE_BUILD, clock)
        clock += s.signMs; t.mark(Stage8144.SIGN, clock)
        clock += s.sendMs; t.mark(Stage8144.BROADCAST, clock)
        return t
    }

    @Test fun stageTimerReplayWithSlowBalanceProviderAndSlowQuote() {
        // Injected faults: whole-wallet scan hangs, single-mint ladder slow, one provider healthy; slow quote; cold venue.
        val s = StageSample8153(leaseMs = 30, singleMintMs = 8_000, multiProviderMs = 400, wholeWalletMs = 60_000,
            venueRpcMs = 1_500, quoteMs = 2_500, buildMs = 600, signMs = 20, sendMs = 200)
        // Pre-8153 path: liveSell 2.5 s + (5 s + 6 s), planner (5 s + 6 s) + re-read (3 s + 6 s).
        val legacyBal = StopLatencyCore8153.legacyBalanceMs8153(s)
        assertEquals(33_500L, legacyBal)
        val legacy = replay(s, legacyBal, StopLatencyCore8153.legacyVenueMs8153(s, venueFresh = false))
        assertEquals(33_500L, legacy.balanceMs())
        assertEquals(1_500L + 3_100L, legacy.quoteMs())
        assertEquals(38_350L, legacy.triggerToBroadcastMs())

        // 8153, proof not yet warm: one parallel read — the healthy provider (400 ms) + grace, bounded.
        val hotBal = StopLatencyCore8153.hotBalanceMs8153(s, proofPrewarmed = false)
        assertEquals(700L, hotBal)
        val hot = replay(s, hotBal, StopLatencyCore8153.hotVenueMs8153(s, venueKnown = true))
        assertEquals(700L, hot.balanceMs())
        assertEquals("prewarmed venue: no venue read", 3_100L, hot.quoteMs())
        assertEquals(StopLatencyCore8153.triggerToBroadcastMs8153(s, true, false, true), hot.triggerToBroadcastMs())
        assertEquals(4_050L, hot.triggerToBroadcastMs())

        // 8153, proof prewarmed: the balance stage costs nothing; the slow quote is the only cost left.
        val warm = replay(s, StopLatencyCore8153.hotBalanceMs8153(s, proofPrewarmed = true), 0L)
        assertEquals(0L, warm.balanceMs())
        assertEquals(3_350L, warm.triggerToBroadcastMs())
        assertEquals(warm.accountedMs(), warm.triggerToBroadcastMs())

        // Every provider down: the parallel read gives up at its bound and the legacy read runs (no invented balance).
        val dead = s.copy(singleMintMs = 9_000, multiProviderMs = 9_000)
        assertEquals(StopLatencyCore8153.EMERGENCY_READ_BUDGET_MS_8153 + 5_000L + 6_000L, StopLatencyCore8153.hotBalanceMs8153(dead, false))
        // Unknown venue: one read bounded at 700 ms instead of 1.5 s.
        assertEquals(700L, StopLatencyCore8153.hotVenueMs8153(s, venueKnown = false))
    }

    @Test fun healthyProviderModelMeetsTheTriggerToBroadcastTargets() {
        // MODEL: seeded healthy-provider latencies (one slow provider in 20 samples), 90% of exits with a prewarmed
        // proof and 95% with a prewarmed venue. Not a device measurement.
        var seed = 8153L
        fun next(lo: Long, hi: Long): Long {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return lo + (hi - lo) * ((seed ushr 33) % 10_000L) / 10_000L
        }
        val hot = ArrayList<Long>()
        val legacy = ArrayList<Long>()
        for (i in 1..1_000) {
            val s = StageSample8153(
                leaseMs = next(0, 80), singleMintMs = if (i % 20 == 0) 4_000L else next(80, 900),
                multiProviderMs = next(120, 1_200), wholeWalletMs = next(400, 4_500), venueRpcMs = next(150, 900),
                quoteMs = next(200, 1_500), buildMs = next(100, 700), signMs = next(5, 40), sendMs = next(60, 450),
            )
            hot.add(StopLatencyCore8153.triggerToBroadcastMs8153(s, hot = true, proofPrewarmed = i % 10 != 0, venueKnown = i % 20 != 0))
            legacy.add(StopLatencyCore8153.triggerToBroadcastMs8153(s, hot = false, proofPrewarmed = false, venueKnown = i % 20 != 0))
        }
        val p50 = StopLatencyCore8153.quantile8153(hot, 0.50)
        val p95 = StopLatencyCore8153.quantile8153(hot, 0.95)
        assertTrue("model p50 $p50 ms < 5 s", p50 < 5_000L)
        assertTrue("model p95 $p95 ms < 10 s", p95 < 10_000L)
        assertTrue("hot path beats the serial path at p95", p95 < StopLatencyCore8153.quantile8153(legacy, 0.95))
        assertEquals(3L, StopLatencyCore8153.quantile8153(listOf(1L, 2L, 3L, 4L), 0.75))
    }

    // ── 4. prewarmed venue ──

    @Test fun exitUsesThePrewarmedOrLastConfirmedVenueWithoutBlocking() {
        val now = System.currentTimeMillis()
        SellRouteVenueProbe8144.putForTest8144(mint, Probe8144(Venue8144.PUMPSWAP, true, true, null, now - 3_600_000L))
        assertEquals(Venue8144.PUMPSWAP, SellRouteVenueProbe8144.resolveForExit8153(null, mint, now))
        val active = "STOPLAT8153ACTIVE11111111111111111111111"
        SellRouteVenueProbe8144.putForTest8144(active, Probe8144(Venue8144.PUMP_CURVE, true, false, null, now - 60_000L))
        val t0 = nowNs()
        assertEquals("stale probe still orders the routes", Venue8144.PUMP_CURVE, SellRouteVenueProbe8144.resolveForExit8153(null, active, now))
        assertTrue(msSince(t0) < 500L)
        assertEquals(Venue8144.UNKNOWN, SellRouteVenueProbe8144.resolveForExit8153(null, "STOPLAT8153NEVERSEEN1111111111111111111", now))
        // Refresh-ahead: an active curve read 20 s ago would be stale within 12 s; one read 10 s ago would not.
        val p20 = Probe8144(Venue8144.PUMP_CURVE, true, false, null, now - 20_000L)
        val p10 = Probe8144(Venue8144.PUMP_CURVE, true, false, null, now - 10_000L)
        assertTrue(SellRouteVenueProbe8144.needsRefresh8153(p20, now, StopLatencyCore8153.VENUE_REFRESH_AHEAD_MS_8153))
        assertFalse(SellRouteVenueProbe8144.needsRefresh8153(p10, now, StopLatencyCore8153.VENUE_REFRESH_AHEAD_MS_8153))
        assertFalse(SellRouteVenueProbe8144.needsRefresh8153(Probe8144(Venue8144.PUMPSWAP, true, true, null, 0L), now, 12_000L))
        assertEquals(Venue8144.UNKNOWN to true, SellRouteVenueProbe8144.exitVenue8153(null, now))
        assertEquals(Venue8144.PUMP_CURVE to false, SellRouteVenueProbe8144.exitVenue8153(p10, now))
        assertEquals(Venue8144.PUMP_CURVE to true, SellRouteVenueProbe8144.exitVenue8153(p20.copy(atMs = now - 40_000L), now))
    }

    // ── 5. emergency priority ──

    @Test fun emergencyReadsNeverQueueBehindPrewarmWork() {
        val lane = StopLatencyCore8153.backgroundLane8153
        repeat(40) { lane.execute(Runnable { Thread.sleep(300) }) }
        val t0 = nowNs()
        val r = StopLatencyCore8153.parallelHeld8153(listOf({ Thread.sleep(20); proof(10) }), budgetMs = 2_000L)
        assertNotNull(r.held)
        assertTrue("emergency lane is not the saturated prewarm lane: ${msSince(t0)} ms", msSince(t0) < 1_000L)
        assertEquals(StopLatencyCore8153.PREWARM_READS_PER_TICK_8153, StopLatencyCore8153.prewarmBudget8153(0))
        assertEquals(1, StopLatencyCore8153.prewarmBudget8153(2))
        assertTrue("dedicated emergency executor", (StopLatencyCore8153.emergencyLane8153 as Any) !== (lane as Any))
        // The proof refresh cadence keeps a proof well inside the window in which it may size an exit.
        assertTrue(StopLatencyCore8153.REFRESH_AHEAD_MS_8153 < StopLatencyCore8153.HOT_PROOF_TTL_MS_8153)
        assertTrue(StopLatencyCore8153.PARALLEL_GRACE_MS_8153 < StopLatencyCore8153.EMERGENCY_READ_BUDGET_MS_8153)
    }

    @Test fun prewarmRefreshesLatchedExitsFirstAndOnlyWhenDue() {
        val now = 1_000_000L
        val targets = listOf(PrewarmTarget8153("A", false), PrewarmTarget8153("B", true), PrewarmTarget8153("C", false))
        val last = mapOf("A" to now - 30_000L, "C" to now - 5_000L)
        assertEquals(listOf("B", "A"), StopLatencyCore8153.prewarmPick8153(targets, last, now, 5))
        assertEquals(listOf("B"), StopLatencyCore8153.prewarmPick8153(targets, last, now, 1))
        assertEquals(emptyList<String>(), StopLatencyCore8153.prewarmPick8153(targets, last, now, 0))
    }

    @Test fun proofPollerAndWorkOrderPutEmergenciesFirst() {
        fun w(m: String, reason: String) = BalanceProofWaitState.Wait(m, m, reason, 0, 0L, 0L)
        val ordered = BalanceProofPoller.emergencyFirst8153(listOf(
            w("t", "TIME_EXIT"), w("p", "TAKE_PROFIT"), w("h", "HARD_STOP"), w("c", "TICK_CATASTROPHIC_CONFIRMED_-70PCT"),
        ))
        assertEquals(listOf("c", "h", "p", "t"), ordered.map { it.mint })
    }

    @Test fun spineReportsPendingEmergenciesUntilBroadcast() {
        val now = System.currentTimeMillis()
        SellRouteSpine8144.trigger8144(mint, "TICK_CATASTROPHIC_CONFIRMED_-70PCT", "p1", now)
        assertTrue(SellRouteSpine8144.emergencyOpen8153(mint, now))
        assertEquals(1, SellRouteSpine8144.emergencyPending8153(now))
        SellRouteSpine8144.mark8144(mint, Stage8144.BROADCAST, now + 10L)
        assertEquals(0, SellRouteSpine8144.emergencyPending8153(now + 20L))
        val soft = "STOPLAT8153SOFT1111111111111111111111111"
        SellRouteSpine8144.trigger8144(soft, "TAKE_PROFIT", "p2", now)
        assertFalse(SellRouteSpine8144.emergencyOpen8153(soft, now))
        assertEquals(0, SellRouteSpine8144.emergencyPending8153(now + 20L))
    }

    // ── 6. every retry is re-sized from an authoritative quantity ──

    @Test fun retryRecomputesSizeFromAFreshWalletRead() {
        val read = proof(1_000_000, atMs = 10_000L)
        assertTrue(StopLatencyCore8153.usable8153(read, 12_000L, 0L, false))
        // The first attempt signed (or resolved) an order after the read: the quantity may have moved — spent.
        assertFalse(StopLatencyCore8153.usable8153(read, 12_000L, 11_000L, false))
        assertFalse(StopLatencyCore8153.usable8153(read, 12_000L, 10_000L, false))
        // A signed sell still unresolved: no proof sizes anything.
        assertFalse(StopLatencyCore8153.usable8153(read, 12_000L, 0L, true))
        // Too old.
        assertFalse(StopLatencyCore8153.usable8153(read, 10_000L + StopLatencyCore8153.HOT_PROOF_TTL_MS_8153 + 1L, 0L, false))
        // The retry's fresh read found a partial fill: the plan is min(canonical remaining, wallet), never more.
        assertEquals(bi(400_000), StopLatencyCore8153.fullExitRaw8153(bi(400_000), 6, bi(1_000_000), 6))
        assertEquals(bi(700_000), StopLatencyCore8153.fullExitRaw8153(bi(1_000_000), 6, bi(700_000), 6))
        assertNull("scale skew blocks", StopLatencyCore8153.fullExitRaw8153(bi(1_000_000), 6, bi(700_000), 9))
        assertNull("no canonical row", StopLatencyCore8153.fullExitRaw8153(bi(1_000_000), 6, null, null))
        assertNull(StopLatencyCore8153.fullExitRaw8153(BigInteger.ZERO, 6, bi(700_000), 6))
        // The newest usable read wins; a spent one is never picked.
        val older = proof(900, atMs = 20_000L)
        val newer = proof(800, atMs = 25_000L)
        assertEquals(newer, StopLatencyCore8153.newestUsable8153(listOf(older, newer, null), 30_000L, 0L, false))
        assertEquals(newer, StopLatencyCore8153.newestUsable8153(listOf(older, newer), 30_000L, 22_000L, false))
        assertNull(StopLatencyCore8153.newestUsable8153(listOf(older, newer), 30_000L, 26_000L, false))
        // Only an emergency FULL exit takes the hot plan.
        assertTrue(StopLatencyCore8153.fastPlanEligible8153(true, null, "JUPITER_ULTRA_METIS"))
        assertFalse(StopLatencyCore8153.fastPlanEligible8153(true, 0.5, "JUPITER_ULTRA_METIS"))
        assertFalse(StopLatencyCore8153.fastPlanEligible8153(true, null, "JupiterPartial"))
        assertFalse(StopLatencyCore8153.fastPlanEligible8153(false, null, "JUPITER_ULTRA_METIS"))
        // The proof cache keeps the newest positive read only.
        StopLatencyCore8153.record8153(mint, proof(5, atMs = 1_000L))
        StopLatencyCore8153.record8153(mint, proof(3, atMs = 500L))
        assertEquals(bi(5), StopLatencyCore8153.proof8153(mint)!!.raw)
        StopLatencyCore8153.record8153(mint, proof(1, atMs = 2_000L))   // dust is not recorded
        assertEquals(bi(5), StopLatencyCore8153.proof8153(mint)!!.raw)
        StopLatencyCore8153.retain8153(emptySet())
        assertNull(StopLatencyCore8153.proof8153(mint))
    }

    // ── 7. never two unverified sells; an inconclusive verification is not waited out ──

    @Test fun expiredSignatureIsProvenAtOnceAndAnUnverifiedOneBlocksASecondSell() {
        val expired = SellRouteFinality8144.statusWithExpiry8153(false, ChainStatus8144.NOT_FOUND, false)
        assertEquals(ChainStatus8144.EXPIRED_PROVEN, expired)
        assertEquals("signs again at 5 s, not after 180 s", SigDecision8144.SIGN_NEW,
            SellRouteFinality8144.signatureDecision8144("sigX", 5_000L, expired))
        for (valid in listOf(null, true)) {
            val st = SellRouteFinality8144.statusWithExpiry8153(false, ChainStatus8144.NOT_FOUND, valid)
            assertEquals(ChainStatus8144.NOT_FOUND, st)
            assertEquals(SigDecision8144.REUSE_PENDING, SellRouteFinality8144.signatureDecision8144("sigX", 5_000L, st))
        }
        assertEquals("a seen signature is never declared expired", ChainStatus8144.NOT_FOUND,
            SellRouteFinality8144.statusWithExpiry8153(true, ChainStatus8144.NOT_FOUND, false))
        assertEquals(ChainStatus8144.LANDED, SellRouteFinality8144.statusWithExpiry8153(true, ChainStatus8144.LANDED, false))

        val m = "STOPLAT8153LEDGER111111111111111111111111"
        SignedTxLedger8085.record("sigA8153", "bytesA", "hashA", SignedTxLedger8085.Intent(m, "SELL", 5L))
        try {
            SignedTxLedger8085.record("sigB8153", "bytesB", "hashB", SignedTxLedger8085.Intent(m, "SELL", 5L))
            fail("a second sell was signed while the first is unverified")
        } catch (e: SolanaWallet.DuplicateOrderPrevented8085) {
            assertEquals("sigA8153", e.existingSignature)
        } finally {
            SignedTxLedger8085.transition("sigA8153", SignedTxLedger8085.State.FAILED_ON_CHAIN, "test_cleanup")
        }
        assertNull(SignedTxLedger8085.unresolvedFor(m, "SELL"))
    }

    // ── wiring (supplement) ──

    @Test fun hotPathIsWiredIntoTheSellPathPrewarmAndPoller() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("StopLatencyWiring8153.hotPathBalances8153(wallet, ts.mint)"))
        assertTrue(exec.contains("hotSellPlan8153(ts, wallet, processor, exitReason, fraction, sellTradeKey, traderTag)?.let { return it }"))
        assertTrue(exec.contains("SellRouteVenueProbe8144.resolveForExit8153(wallet, ts.mint)"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planSellFromConfirmed("))
        assertTrue(src("engine/BotService.kt").contains("StopLatencyWiring8153.startPrewarm8153("))
        assertTrue(src("engine/sell/BalanceProofPoller.kt").contains("emergencyFirst8153(BalanceProofWaitState.all()"))
        assertTrue(src("engine/sell/SellRouteProbe8144.kt").contains("SellRouteFinality8144.statusWithExpiry8153("))
        assertTrue(src("engine/sell/StopLatencyWiring8153.kt").contains("SellRouteProbe8144.balanceFresh8153(wallet, mint)"))
    }
}
