package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.TrustedMcap8019
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8019TruthGuardsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theCapFollowsThePrice() {
        // Earth: $47,047 on the row, fill 3.127e-6 x 1e9 = $3,127 -> the price-implied cap
        assertEquals(3_127.0, TrustedMcap8019.reconcile8019(47_047.0, 3.127e-6, TrustedMcap8019.PUMP_SUPPLY_8019), 1.0)
        assertEquals(3_075.0, TrustedMcap8019.reconcile8019(3_075.0, 3.12e-6, 1e9), 0.0)          // agrees: row stands
        assertEquals(50_000.0, TrustedMcap8019.reconcile8019(50_000.0, 1.0, Double.NaN), 0.0)     // no supply: row stands
        assertEquals(3_127.0, TrustedMcap8019.reconcile8019(0.0, 3.127e-6, 1e9), 1.0)             // no row cap: implied
        assertFalse(TrustedMcap8019.independentPrice8019("PUMP_FUN_BC_SYNTHETIC"))
        assertEquals(TrustedMcap8019.PUMP_SUPPLY_8019, TrustedMcap8019.supplyFor8019(com.lifecyclebot.data.TokenState(mint = "465He1YecQuzyriK5BBMLANPh94CYpDtnWKRw14ppump", symbol = "Earth")), 0.0)
        assertEquals(0.0, TrustedMcap8019.supplyFor8019(com.lifecyclebot.data.TokenState(mint = "So11111111111111111111111111111111111111112", symbol = "SOL")), 0.0)
        assertTrue(TrustedMcap8019.independentPrice8019("LOCKED_VENUE_CURVE_7392"))
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("cellKey(ts.source, l, TrustedMcap8019.mcap8019(ts), ageMs)"))
        assertTrue(f.contains("cellKey(ts.source, lane, TrustedMcap8019.mcap8019(ts), ageMs)"))
        assertTrue(f.contains("\"BAND|${'$'}{o.lane}|"))
        assertFalse(src("engine/CellAllocator7962.kt").contains("ts.lastMcap, ageMs)"))
        assertFalse(src("engine/truth/TailHunter7996.kt").contains("ts.lastMcap, ageMs)"))
    }

    @Test fun aLosingCapBandRefusesAThinCell() {
        assertEquals("BAND|SHITCOIN|MC_LT10K", com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.bandKey8019("shitcoin", 3_127.0))
        assertTrue(LiveEdgeGate7877.bandLoses8019(27, -17.4, 4.0))
        assertFalse(LiveEdgeGate7877.bandLoses8019(12, -17.4, 4.0))
        assertFalse(LiveEdgeGate7877.bandLoses8019(40, -2.0, 3.0))      // upper bound +1%: not proven
        assertTrue(LiveEdgeGate7877.bandLoses8019(40, -4.0, 3.0))
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("bandNegative -> \"WATCH_FIRST_8019_BAND_NEGATIVE\""))
        assertTrue(g.contains("RebuyLockout8019.refusal8019(ts.mint, nowMs)?.let { return it }"))
        assertTrue(g.contains("Cortex7885.overrulesEdgeRefusal(ts, l, prior)) { priorsCleared8019.incrementAndGet()"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("refusal.contains(\"BAND_NEGATIVE\")) && fraction < 1.0"))
        assertTrue(src("engine/BotService.kt").contains("if (!cortexSignal8019 && !com.lifecyclebot.engine.learning.ExplorationBudget.allowProbe7951(lane, mintForProbe))"))
    }

    @Test fun relativeRefusalEngagesWhereStrongHoldsAuthority() {
        val refuse = CortexLedger7885.Stat(); repeat(19) { refuse.add(-3.0 + (it % 3) * 0.5, false) }
        val rest = CortexLedger7885.Stat(); repeat(60) { rest.add(15.0 + (it % 5) * 2.0, false) }
        assertTrue(CortexScoreboard7885.relativeRefuseProven8019(refuse, rest, 0.84, runnerLane = false))
        assertFalse(CortexScoreboard7885.relativeRefuseProven8019(refuse, rest, 0.3, false))      // no STRONG authority
        val few = CortexLedger7885.Stat(); repeat(5) { few.add(-3.0, false) }
        assertFalse(CortexScoreboard7885.relativeRefuseProven8019(few, rest, 0.84, false))
        val positive = CortexLedger7885.Stat(); repeat(19) { positive.add(1.0, false) }
        assertFalse(CortexScoreboard7885.relativeRefuseProven8019(positive, rest, 0.84, false))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("\"C3_RELATIVE_REFUSE_8019\""))
    }

    @Test fun aBrokenBasisNeverSellsAndAPhantomPeakNeverCuts() {
        assertTrue(BasisBreak8019.brokenBasis8019(24.83, 3.2e-6))
        assertTrue(BasisBreak8019.brokenBasis8019(6.8e-4, 185.3))
        assertFalse(BasisBreak8019.brokenBasis8019(3.7e-4, 7.7e-5))      // CXMT -79%: a real rug
        assertFalse(BasisBreak8019.brokenBasis8019(1.0, 99.0))            // a 99x runner still sells
        assertTrue(BasisBreak8019.priceDriven8019("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertTrue(BasisBreak8019.priceDriven8019("PEAK_CAPTURE_TRAIL_6394_peak23_now13"))
        assertFalse(BasisBreak8019.priceDriven8019("RUG_PULL_DETECTED"))
        assertFalse(BasisBreak8019.priceDriven8019("bot_shutdown"))
        assertFalse(BasisBreak8019.priceDriven8019("data_quality_stale_feed_evict_feedAge38m"))
        assertTrue(BasisBreak8019.phantomPeak8019("PEAK_SLIP_CUT_FULL", 6_000L, 111.8, -1.3))
        assertFalse(BasisBreak8019.phantomPeak8019("PEAK_SLIP_CUT_FULL", 600_000L, 111.8, -1.3))
        assertFalse(BasisBreak8019.phantomPeak8019("PEAK_SLIP_CUT_FULL", 6_000L, 111.8, 40.0))
        assertTrue(src("engine/Executor.kt").contains("BasisBreak8019.refuses8019(ts, reason, getActualPrice(ts))"))
    }

    @Test fun theCryptoUniverseRefusesPumpMintsAndClosesLockRebuys() {
        assertTrue(DynamicAltTokenRegistry.pumpMint8019("mGzw6zwvig3GitrCM6sGJXdRWeWz81X2vyzVH3Upump"))
        assertFalse(DynamicAltTokenRegistry.pumpMint8019("So11111111111111111111111111111111111111112"))
        val r = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(r.contains("registry.values.filter { !pumpMint8019(it.mint) || it.isStatic }"))
        assertTrue(r.contains("CRYPTO_UNIVERSE_PUMP_MINT_REFUSED_8019"))
        assertTrue(RebuyLockout8019.locked8019(100_000L, ticket = false))
        assertFalse(RebuyLockout8019.locked8019(100_000L, ticket = true))
        assertFalse(RebuyLockout8019.locked8019(11 * 60_000L, ticket = false))
        RebuyLockout8019.onClose8019("TESTMINT8019", 1_000L)
        assertEquals("REBUY_LOCKOUT_8019", RebuyLockout8019.refusal8019("TESTMINT8019", 2_000L))
        assertNull(RebuyLockout8019.refusal8019("TESTMINT8019", 2_000L + RebuyLockout8019.LOCK_MS_8019))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("RebuyLockout8019.onClose8019(env.mint, env.atMs, env.realizedReturnPct)"))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("RebuyLockout8019.refusal8019(mint)"))
    }

    @Test fun theReentryBarIsStandingNotFirming() {
        assertTrue(src("engine/RunnerPlay8018.kt").contains("RunnerGrab7967.runStandingNow8019(mint, nowMs)"))
        assertTrue(RunnerGrab7967.runStanding8019(null).not())
    }
}
