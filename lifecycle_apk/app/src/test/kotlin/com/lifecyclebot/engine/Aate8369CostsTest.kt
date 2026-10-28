package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8369 — what a 0.029 SOL round trip pays: the rent reclaimer on an always-on clock with a boot sweep and the
 * after-exit close on every full exit, curve buys routed to Jupiter when it quotes them, the model's route fee from
 * the route chosen, and an ordinary entry's tip + priority capped at 0.5 % of the ticket. Pure functions + source pins.
 */
class Aate8369CostsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val k = Costs8369
    private val min = 60_000L

    // ── rent ──

    @Test fun theReclaimerIsDueOnTheAlwaysOnTick() {
        val start = 1_000_000L
        // first delay 2 min, interval 15 min, live only
        assertFalse(k.reclaimPassDue8369(start + 2 * min - 1, start, 0L, live = true, firstDelayMs = 2 * min, intervalMs = 15 * min))
        assertTrue(k.reclaimPassDue8369(start + 2 * min, start, 0L, live = true, firstDelayMs = 2 * min, intervalMs = 15 * min))
        assertFalse(k.reclaimPassDue8369(start + 2 * min, start, 0L, live = false, firstDelayMs = 2 * min, intervalMs = 15 * min))
        val last = start + 2 * min
        assertFalse(k.reclaimPassDue8369(last + 15 * min - 1, start, last, live = true, firstDelayMs = 2 * min, intervalMs = 15 * min))
        assertTrue(k.reclaimPassDue8369(last + 15 * min, start, last, live = true, firstDelayMs = 2 * min, intervalMs = 15 * min))
    }

    @Test fun theBootSweepClosesOnlyUnprotectedClosableAccounts() {
        assertTrue(k.sweepEligible8369(protectedMint = false, unclosable = false))
        assertFalse(k.sweepEligible8369(protectedMint = true, unclosable = false))
        assertFalse(k.sweepEligible8369(protectedMint = false, unclosable = true))
    }

    @Test fun rentThatIsNotComingBackIsCharged() {
        // a reclaimer that never listed while live past its first interval reclaims nothing: the whole rent is a cost
        assertEquals(1.0, k.rentLossRate8369(0, 0L, 0, listedOnce = false, overdue = true), 1e-12)
        assertEquals(0.0, k.rentLossRate8369(0, 0L, 0, listedOnce = false, overdue = false), 1e-12)
        assertEquals(0.0, k.rentLossRate8369(0, 10L, 0, listedOnce = true, overdue = true), 1e-12)
        assertEquals(0.25, k.rentLossRate8369(1, 6L, 1, listedOnce = true, overdue = true), 1e-12)
        assertEquals(0.00203928, CostTrim8151.ATA_RENT_SOL_8151, 0.0)
    }

    // ── route ──

    @Test fun aCurveBuyGoesJupiterWhenJupiterQuotesIt() {
        assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = true, recentlyTried = false, jupiterLandFailing = false))
        assertTrue(k.jupiterFirstForBuy8369("UNKNOWN", jupiterQuotes = true, recentlyTried = false, jupiterLandFailing = false))
        // V5.0.8413 — only a definite "no Jupiter route" sends PumpPortal first; an unanswered probe stays Jupiter-first
        assertFalse(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = false, recentlyTried = false, jupiterLandFailing = false))
        assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = null, recentlyTried = false, jupiterLandFailing = false))
        // graduated pools always go Jupiter
        assertTrue(k.jupiterFirstForBuy8369("PUMPSWAP", jupiterQuotes = null, recentlyTried = false, jupiterLandFailing = false))
        assertTrue(k.jupiterFirstForBuy8369("RAYDIUM", jupiterQuotes = null, recentlyTried = false, jupiterLandFailing = false))
        // superseded by V5.0.8418 — a retry on a mint Jupiter quotes stays Jupiter (PumpPortal only when Jupiter has no route);
        // a retry whose probe answered no-route still goes PumpPortal, so the buy is never choked
        assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = true, recentlyTried = true, jupiterLandFailing = false))
        assertFalse(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = false, recentlyTried = true, jupiterLandFailing = false))
        assertTrue(k.jupiterFirstForBuy8369("PUMP_CURVE", jupiterQuotes = true, recentlyTried = false, jupiterLandFailing = true))
        assertTrue(k.quoteCacheFresh8369(true, 0L, 60_000L))
        assertFalse(k.quoteCacheFresh8369(true, 0L, 60_001L))
        assertFalse(k.quoteCacheFresh8369(false, 0L, 30_001L))
    }

    @Test fun theRouteFeeFollowsTheChosenRoute() {
        assertEquals("JUPITER", k.plannedBuyRoute8369(pumpMint = true, venue = "PUMP_CURVE", chosen = "JUPITER", jupiterShare = null))
        assertEquals("PUMPPORTAL", k.plannedBuyRoute8369(pumpMint = true, venue = "PUMP_CURVE", chosen = "PUMPPORTAL", jupiterShare = 0.9))
        assertEquals("JUPITER", k.plannedBuyRoute8369(pumpMint = true, venue = "PUMP_CURVE", chosen = null, jupiterShare = 0.8))
        assertEquals("JUPITER", k.plannedBuyRoute8369(pumpMint = true, venue = "UNKNOWN", chosen = null, jupiterShare = null))  // V5.0.8413
        assertEquals("JUPITER", k.plannedBuyRoute8369(pumpMint = true, venue = "PUMPSWAP", chosen = null, jupiterShare = null))
        assertEquals("JUPITER", k.plannedBuyRoute8369(pumpMint = false, venue = "UNKNOWN", chosen = "PUMPPORTAL", jupiterShare = null))
        // V5.0.8390 — a curve exit sells through Jupiter first; PumpPortal only when Jupiter is not admissible
        assertEquals("JUPITER", k.plannedSellRoute8369(pumpMint = true, venue = "PUMP_CURVE"))
        assertEquals("JUPITER", k.plannedSellRoute8369(pumpMint = true, venue = "PUMPSWAP"))
        assertEquals("PUMPPORTAL", k.plannedSellRoute8369(pumpMint = true, venue = "PUMP_CURVE", jupiterOk = false))
        assertEquals("RAYDIUM", k.plannedSellRoute8369(pumpMint = true, venue = "RAYDIUM"))
        assertEquals("JUPITER", k.plannedSellRoute8369(pumpMint = false, venue = "UNKNOWN"))
        // bought on Jupiter (0.05), sold on PumpPortal (0.5): 0.275 per side, not PumpPortal's 0.5 both ways
        assertEquals(0.3, k.routeFeePerSide8369("JUPITER", "PUMPPORTAL"), 1e-12)
        assertEquals(0.5, k.routeFeePerSide8369("PUMPPORTAL", "PUMPPORTAL"), 1e-12)
        assertEquals(null, k.jupiterShare8369(1L, 1L))
        assertEquals(0.75, k.jupiterShare8369(3L, 1L)!!, 1e-12)
    }

    // ── fixed leg ──

    @Test fun anOrdinaryEntryTipKeepsTheFixedLegInsideHalfAPercentOfTheTicket() {
        val ticket = 29_000_000L   // 0.029 SOL -> cap 145,000 lamports
        // Jupiter High priority ~30,000 lamports: tip 115,000 (was the 200,000 urgent tip)
        assertEquals(115_000L, k.entryTipLamports8369(200_000L, ticket, 30_000L, 5_000L, capFailing = false))
        // the priority alone fills the cap: Sender's minimum tip
        assertEquals(5_000L, k.entryTipLamports8369(200_000L, ticket, 200_000L, 5_000L, capFailing = false))
        // never above the legacy tip
        assertEquals(50_000L, k.entryTipLamports8369(50_000L, ticket, 0L, 5_000L, capFailing = false))
        // Sender's minimum (default endpoint 0.001 SOL) exceeds the cap: no Sender, the standard RPC / Jupiter path
        assertEquals(0L, k.entryTipLamports8369(200_000L, ticket, 30_000L, 1_000_000L, capFailing = false))
        // capped entries not landing: pay to land again
        assertEquals(200_000L, k.entryTipLamports8369(200_000L, ticket, 30_000L, 5_000L, capFailing = true))
        assertEquals(0.5, k.fixedLegPct8369(115_000L, 30_000L, ticket) - 5_000.0 / ticket * 100.0, 1e-9)
    }

    @Test fun theCapLiftsOnlyWhenCappedEntriesStopLanding() {
        val since = 1_000L
        val now = 2_000_000L
        fun row(route: String, state: String, at: Long = 10_000L) = PumpLanding8288.Row8288("BUY", route, state, at)
        val landing = listOf(row("JUPITER", "CONFIRMED"), row("JUPITER", "CONFIRMED"), row("JUPITER", "EXPIRED_PROVEN"))
        assertFalse(k.capLandFailing8369(landing, pumpRoute = false, sinceMs = since, nowMs = now))
        val failing = listOf(row("JUPITER", "CONFIRMED"), row("JUPITER", "EXPIRED_PROVEN"), row("JUPITER", "FAILED_ON_CHAIN"))
        assertTrue(k.capLandFailing8369(failing, pumpRoute = false, sinceMs = since, nowMs = now))
        // PumpPortal rows are judged on their own; rows from before the cap went live do not count
        assertFalse(k.capLandFailing8369(failing, pumpRoute = true, sinceMs = since, nowMs = now))
        assertFalse(k.capLandFailing8369(failing.map { it.copy(createdAtMs = 500L) }, pumpRoute = false, sinceMs = since, nowMs = now))
        assertEquals(0.005, Costs8369.ENTRY_FIXED_CAP_SHARE_8369, 0.0)
        assertEquals(5_000L, CostLanding8169.senderMinTipLamports8169("https://sender.helius-rpc.com/fast?swqos_only=true"))
    }

    // ── wiring (source pins) ──

    @Test fun theCostFixesAreWired() {
        val bot = src("engine/BotService.kt")
        val reconcile = bot.substringAfter("private fun reconcileWalletOffLoop8094(").substringBefore("private fun wireEmergencyHandoff8087()")
        assertTrue(reconcile.contains("RentReclaimer7927.maybeRun()"))
        val rr = src("engine/RentReclaimer7927.kt")
        assertTrue(rr.contains("Costs8369.reclaimPassDue8369(nowMs, startedAt, lastRunMs, live, FIRST_RUN_DELAY_MS, INTERVAL_MS)"))
        assertTrue(rr.contains("FIRST_RUN_DELAY_MS = 2L * 60_000L"))
        assertTrue(rr.contains("INTERVAL_MS = 15L * 60_000L"))
        assertTrue(rr.contains("RENT_RECLAIM_BOOT_SWEEP_8369"))
        assertTrue(rr.contains("rentLocked8369="))
        assertTrue(rr.contains("Costs8369.rentLossRate8369("))
        val lpca = src("engine/sell/LivePositionCloseAuthority.kt")
        val dup = lpca.substringAfter("if (existing != null) {").substringBefore("return existing")
        assertTrue(dup.contains("RentReclaimer7927.noteFullExit8151(mint)"))
        assertTrue(lpca.contains("if (remainingQtyRaw <= 0L) try { com.lifecyclebot.engine.RentReclaimer7927.noteFullExit8151(mint) }"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("Costs8369.jupiterFirstForBuy8369(venue, quotes8369, CostMeter8151.aggregatorTriedRecently8151(ts.mint), Costs8369.jupiterBuysFailing8369())"))
        assertTrue(ex.contains("val tip = if (entry && !urgent && !grabbed8369) Costs8369.entryTip8369(tip8151, ticketSol8369, pump8369) else tip8151"))
        assertTrue(ex.contains("entry = true, ticketSol8369 = buySol, pump8369 = true)"))
        assertTrue(ex.contains("Costs8369.entryCapActive8369(true, RunnerGrab7967.isGrabbed7980(ts.mint))"))
        // V8391: no volume fee; verified terminal profit is assessed separately.
        // superseded by V5.0.8418 — owner re-added 0.5% on the BUY only; the per-side mean is 0.25 (sell side still 0).
        assertEquals(0.25, CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, 0.0)
        assertTrue(src("engine/truth/NetEdge8030.kt").contains("val routePct = com.lifecyclebot.engine.Costs8369.modelRouteFeePctPerSide8369(mint)"))
        assertTrue(src("engine/CostTrim8151.kt").contains("Costs8369.statusLine8369()"))
    }
}
