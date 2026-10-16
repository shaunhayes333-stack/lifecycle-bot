package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.market.LaunchTape7921
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.State8144
import com.lifecyclebot.engine.truth.TokenMapVersionGuard6411
import org.junit.Assert.*
import org.junit.Test

class Aate8167PipelineRepairTest {
    @Test fun finalBuyQuoteDetectsAdverseFillWhileCachedMarkHasNotMoved() {
        val q = SizingQuoteRecheck8153
        val ts = TokenState("quote-price-8167", symbol = "QUOTE")
        ts.lastPrice = 1.0
        ts.tokenMap.decimals = 6
        run {
            val now = System.currentTimeMillis()
            q.noteDecision8153(ts.mint, SizingQuoteRecheck8153.SealedEconomics8153(
                0.05, 12.0, 30.0, 4.0, 0.1, 1.0, now))
            q.bindSeal8153(ts.mint, 0.05, now)
            // Five dollars buys four tokens: $1.25 executable price despite a cached $1 mark.
            val quote = com.lifecyclebot.network.SwapQuote(org.json.JSONObject(), 4_000_000L, 0.1,
                inputMint = com.lifecyclebot.network.JupiterApi.SOL_MINT, outputMint = ts.mint, inAmount = 50_000_000L)
            assertEquals(1.25, q.quotePrice8167(quote.inAmount, quote.outAmount, 6, 100.0, ts.lastPrice), 1e-12)
            assertEquals("PRE_BROADCAST_NET_EV_NONPOSITIVE_8153", q.preBroadcastQuoteRefusal8167(ts, 0.05, quote, 100.0))
        }
    }

    @Test fun quotePriceKeepsUnitsAndDoesNotTreatAnExtremeAdverseFillAsZeroDrift() {
        val q = SizingQuoteRecheck8153
        assertEquals(1.25, q.quotePrice8167(50_000_000L, 4_000_000_000L, 9, 100.0, 1.0), 1e-12)
        assertEquals(1.0, q.quotePrice8167(50_000_000L, 10_000_000L, 6, 100.0, 1.0), 0.0)
        assertEquals(1.0, q.quotePrice8167(50_000_000L, 4_000_000L, null, 100.0, 1.0), 0.0)
        assertEquals(1.0, q.quotePrice8167(50_000_000L, 0L, 6, 100.0, 1.0), 0.0)
        val seal = SizingQuoteRecheck8153.SealedEconomics8153(0.05, 12.0, 30.0, 4.0, 0.1, 1.0, 1000L)
        assertEquals("PRE_BROADCAST_PRICE_DISCONTINUITY_8167", q.recheck8153(seal, 0.05, 50_000_000L, 0.1, 5.0, 2000L))
        assertNull(q.recheck8153(seal, 0.05, 50_000_000L, 0.1, 1.01, 2000L))
    }

    @Test fun repeatedExecutableRouteDoesNotInvalidateItsOwnGeneration() {
        val ts = TokenState("8167CacheMint11111111111111111111111111", symbol = "CACHE")
        ts.tokenMap.dexRouteOk = true
        ts.tokenMap.expectedOutAmount = 1.0
        TokenMapAuthority.ensureDiscoveryTokenMap(ts)
        val first = TokenMapVersionGuard6411.stamp7460(ts.mint)
        repeat(5) { TokenMapAuthority.ensureDiscoveryTokenMap(ts) }
        assertEquals(first, TokenMapVersionGuard6411.stamp7460(ts.mint))
        ts.lastPrice = 0.001
        ts.lastPriceUpdate = System.currentTimeMillis()
        TokenMapAuthority.ensureDiscoveryTokenMap(ts)
        assertTrue(TokenMapVersionGuard6411.stamp7460(ts.mint).mappingVersion > first.mappingVersion)
    }

    @Test fun freshPreFillPrintIsUsableInsideItsOpenBucketButFuturePrintIsNot() {
        val mint = "cost-open-bucket-8167"
        val start = 15_000L * 1000
        StructureTracker7962.onPrice7962(mint, 2.0, start + 1000)
        assertEquals(2.0, StructureTracker7962.markAt7962(mint, start + 4000)!!, 0.0)
        StructureTracker7962.onPrice7962(mint, 9.0, start + 8000)
        assertNull(StructureTracker7962.markAt7962(mint, start + 4000))
        assertNull(StructureTracker7962.markAt7962(mint, start + 150_000))
    }

    @Test fun verifiedBirthRestoresLaunchTapeWithoutInventingAge() {
        val now = System.currentTimeMillis()
        val mint = "launch-recovery-8167"
        assertTrue(LaunchTape7921.recoverBirth8167(mint, now - 10_000, now))
        LaunchTape7921.onPrice(mint, 1.0)
        LaunchTape7921.onTrade(mint, "buyer1", 0.2, true, now)
        val f = LaunchTape7921.features(mint, now)!!
        assertEquals(1, f.crowdBuyers)
        assertEquals(0.2, f.crowdNetSol, 0.000001)
        assertEquals(10_000 / 60_000.0, f.ageMin, 0.000001)
        assertFalse(LaunchTape7921.recoverBirth8167("unknown-8167", null, now))
        assertNull(LaunchTape7921.features("unknown-8167", now))
        assertFalse(LaunchTape7921.recoverBirth8167("old-8167", now - 60 * 60_000, now))
        assertFalse(LaunchTape7921.recoverBirth8167("future-8167", now + 1000, now))
    }

    @Test fun missingDataDoesNotPermanentlySettleAReceiverBeforeHydration() {
        val a = AdmissionHandoff8144
        val now = 100_000L
        val h = a.emit8144(null, "handoff-8167", "PROJECT_SNIPER", "MOONSHOT", "TOO_OLD", now)
        val pending = a.onNative8144(h, listOf(LaneRead8144("MOONSHOT", false, 0, "LANE_LIQ_UNHYDRATED_DEFER")), now + 1000)
        assertTrue(pending.open)
        val ready = a.onNative8144(pending, listOf(LaneRead8144("MOONSHOT", true, 60, "READY")), now + 2000)
        assertEquals(State8144.RECEIVER_QUALIFIED, ready.state)
        val waiting = a.onNative8144(ready, listOf(LaneRead8144("MOONSHOT", false, 0, "DATA_INCOMPLETE")), now + 3000)
        assertTrue(waiting.open)
        val blocked = a.onNative8144(waiting, listOf(LaneRead8144("MOONSHOT", false, 0, "TOKEN_SAFETY_BLOCKED")), now + 4000)
        assertEquals(State8144.ARRIVED_EVALUATED, blocked.state)
        assertFalse(blocked.open)
        assertFalse(a.expire8144(waiting, now + a.HANDOFF_TTL_MS_8144 + 1).open)
    }
}
